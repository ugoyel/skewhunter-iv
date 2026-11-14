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
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 8))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
        } catch (e: Exception) { }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return false }
        capturing = true
        thread(isDaemon = true, name = "ptt-rec") {
            val buf = ShortArray(frame)
            try {
                while (capturing) {
                    var n = 0
                    while (n < frame && capturing) {
                        val r = rec.read(buf, n, frame - n)
                        if (r <= 0) { capturing = false; break }
                        n += r
                    }
                    if (n == frame) onFrame(buf.copyOf())
                }
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }
        return true
    }

    override fun stopCapture() { capturing = false }

    override fun play(pcm: ShortArray) { if (pcm.isNotEmpty()) queue.offer(pcm) }

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
            .setBufferSizeInBytes(maxOf(min, frame * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
    }

    private fun playLoop() {
        var track: AudioTrack? = null
        while (true) {
            var cur: ShortArray? = try { queue.take() } catch (e: InterruptedException) { return }
            try {
                wake.acquire(20_000)
                val t = track ?: newTrack().also { track = it }
                // Tiny jitter buffer: wait for a second frame before starting so playback does not stutter.
                var waited = 0
                while (queue.isEmpty() && waited < 120) { Thread.sleep(20); waited += 20 }
                t.play()
                while (cur != null) {
                    t.write(cur, 0, cur.size)
                    wake.acquire(20_000)
                    cur = queue.poll(450, TimeUnit.MILLISECONDS)
                }
                t.pause(); t.flush()
            } catch (e: Exception) {
                try { track?.release() } catch (x: Exception) { }
                track = null
            } finally {
                if (wake.isHeld) try { wake.release() } catch (e: Exception) { }
            }
        }
    }
}
