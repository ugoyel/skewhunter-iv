package app.pttradio.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.PowerManager
import app.pttradio.core.AudioIO
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Microphone capture and speaker playback. Audio only ever lives in RAM. */
class AudioEngine(ctx: Context) : AudioIO {
    private val rate = 16000
    private val frame = 960
    @Volatile private var capturing = false
    private val queue = LinkedBlockingQueue<ShortArray>(64)
    // Held only while a transmission is being received, so playback is not cut when the screen is off.
    private val wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pttradio:rx").apply { setReferenceCounted(false) }

    init { thread(isDaemon = true, name = "ptt-play") { playLoop() } }

    @SuppressLint("MissingPermission")
    override fun startCapture(onFrame: (ShortArray) -> Unit): Boolean {
        if (capturing) return true
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        // The voice-communication source switches on the phone's own noise suppression and gain control;
        // the plain microphone is the fall-back for phones that refuse it.
        var found: AudioRecord? = null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = try {
                AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 8))
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { found = r; break }
            r?.release()
        }
        val rec = found ?: return false
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
        } catch (e: Exception) { }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return false }
        capturing = true
        thread(isDaemon = true, name = "ptt-rec") {
            val buf = ShortArray(frame)
            var x1 = 0.0; var y1 = 0.0
            try {
                while (capturing) {
                    var n = 0
                    while (n < frame && capturing) {
                        val r = rec.read(buf, n, frame - n)
                        if (r <= 0) { capturing = false; break }
                        n += r
                    }
                    if (n == frame) {
                        // remove hum and wind rumble below ~100 Hz, which only wastes bits and masks speech
                        val out = ShortArray(frame)
                        for (k in 0 until frame) {
                            val x = buf[k].toDouble()
                            val y = x - x1 + 0.96 * y1
                            x1 = x; y1 = y
                            out[k] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
                        }
                        onFrame(out)
                    }
                }
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }
        return true
    }

    override fun stopCapture() { capturing = false }

    override fun play(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        try { wake.acquire(20_000) } catch (e: Exception) { }
        queue.offer(pcm)
    }

    override fun endOfTalk(beep: Boolean) { if (beep) queue.offer(rogerBeep) }

    private val rogerBeep: ShortArray by lazy {
        val n = rate / 10
        ShortArray(n) { i ->
            val env = minOf(1.0, i / 80.0, (n - i) / 240.0)
            (6500 * env * sin(2 * PI * (if (i < n / 2) 1250 else 950) * i / rate)).toInt().toShort()
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, frame * 2 * 10))    // room for 600 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }

    /**
     * Playback with a jitter buffer. Voice over the internet arrives unevenly (bursts, late packets), so each
     * transmission starts only once ~240 ms are buffered; if the buffer ever runs dry it is refilled before playing
     * on, instead of chopping. Latency is capped by skipping audio when a burst piles up.
     */
    private fun playLoop() {
        var track: AudioTrack? = null
        while (true) {
            val first = try { queue.take() } catch (e: InterruptedException) { return }
            try {
                wake.acquire(20_000)
                val t = track ?: newTrack().also { track = it }
                fill(first)
                t.play()
                var drainAt = writeAll(t, System.currentTimeMillis())
                while (true) {
                    while (queue.size > MAX_QUEUE) queue.poll()          // a burst piled up: catch up
                    val f = queue.poll(900, TimeUnit.MILLISECONDS) ?: break // nothing for a while: the talk is over
                    val now = System.currentTimeMillis()
                    if (now > drainAt + 20) fill(f) else pending.add(f)     // ran dry: buffer up again before going on
                    drainAt = writeAll(t, maxOf(now, drainAt))
                    wake.acquire(20_000)
                }
                t.pause(); t.flush()
            } catch (e: Exception) {
                try { track?.release() } catch (x: Exception) { }
                track = null
            } finally {
                pending.clear()
                if (wake.isHeld) try { wake.release() } catch (e: Exception) { }
            }
        }
    }

    private val pending = ArrayList<ShortArray>()

    /** Collects up to PREBUFFER frames (or waits at most PREBUFFER_MS) starting with [f]. */
    private fun fill(f: ShortArray) {
        pending.add(f)
        val until = System.currentTimeMillis() + PREBUFFER_MS
        while (pending.size < PREBUFFER) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            pending.add(queue.poll(left, TimeUnit.MILLISECONDS) ?: break)
        }
    }

    /** Writes what is pending; returns when the track will have played all of it. */
    private fun writeAll(t: AudioTrack, from: Long): Long {
        var at = from
        for (p in pending) { t.write(p, 0, p.size); at += p.size * 1000L / rate }
        pending.clear()
        return at
    }

    companion object {
        const val PREBUFFER = 4          // frames of 60 ms
        const val PREBUFFER_MS = 300L
        const val MAX_QUEUE = 12
    }
}
