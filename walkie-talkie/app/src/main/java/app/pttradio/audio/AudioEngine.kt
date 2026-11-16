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
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.os.PowerManager
import app.pttradio.core.AudioIO
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Microphone capture and speaker playback. Audio only ever lives in RAM.
 *
 * Sending: the phone's voice processing (noise suppression, gain control), a rumble filter and a gentle noise gate.
 * Receiving: a jitter buffer that never throws speech away, automatic level control that brings quiet voices up to a
 * loud, even level without lifting background noise, a soft limiter so loud voices never distort, and Android's
 * loudness enhancer on top.
 */
class AudioEngine(ctx: Context) : AudioIO {
    private val rate = 16000
    private val frame = 960
    @Volatile private var capturing = false
    private val queue = LinkedBlockingQueue<ShortArray>(400)
    // Held only while a transmission is being received, so playback is not cut when the screen is off.
    private val wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pttradio:rx").apply { setReferenceCounted(false) }

    /** Speaker loudness: 0 normal, 1 loud, 2 maximum. */
    @Volatile var boost = 1

    companion object {
        const val PREBUFFER_MS = 300L      // collected before a transmission starts playing
        const val PREBUFFER_WAIT = 450L    // longest wait for that much audio
        const val MAX_BACKLOG_MS = 2500L   // only beyond this is anything skipped, and then silence first
        const val TARGET_RMS = 7000.0      // loud, even speech level (about -13 dBFS)
        const val NOISE_RMS = 260.0        // quieter than this is treated as background, never amplified
    }

    init { thread(isDaemon = true, name = "ptt-play") { playLoop() } }

    // ------------------------------------------------------------------ microphone

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
            var gate = 1.0
            try {
                while (capturing) {
                    var n = 0
                    while (n < frame && capturing) {
                        val r = rec.read(buf, n, frame - n)
                        if (r <= 0) { capturing = false; break }
                        n += r
                    }
                    if (n != frame) continue
                    // rumble filter: hum and wind below ~100 Hz only waste bits and mask speech
                    val out = DoubleArray(frame)
                    var sum = 0.0
                    for (k in 0 until frame) {
                        val x = buf[k].toDouble()
                        val y = x - x1 + 0.96 * y1
                        x1 = x; y1 = y
                        out[k] = y; sum += y * y
                    }
                    // gentle noise gate: opens at once for speech, closes slowly, never fully mutes
                    val rms = sqrt(sum / frame)
                    val want = if (rms >= NOISE_RMS * 1.3) 1.0 else gate + (0.35 - gate) * 0.3
                    onFrame(ramp(out, gate, want, 1.0))
                    gate = want
                }
            } finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
            }
        }
        return true
    }

    override fun stopCapture() { capturing = false }

    // ------------------------------------------------------------------ speaker

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

    private var gain = 1.0

    /** Brings speech to a loud, even level. Quiet background is left alone, peaks are softly limited. */
    private fun shape(p: ShortArray): ShortArray {
        var sum = 0.0
        for (v in p) sum += v.toDouble() * v
        val rms = sqrt(sum / p.size)
        val maxGain = when (boost) { 0 -> 1.6; 2 -> 7.0; else -> 4.0 }
        val want = if (rms < NOISE_RMS) minOf(gain, 1.0) else (TARGET_RMS / rms).coerceIn(1.0, maxGain)
        // falls fast (no blasting when someone shouts), rises gently (no pumping)
        val next = if (want < gain) want else gain + (want - gain) * 0.3
        val out = ramp(DoubleArray(p.size) { p[it].toDouble() }, gain, next, 1.0)
        gain = next
        return out
    }

    /** Applies a gain that moves smoothly from [from] to [to] across the block, with a soft limiter. */
    private fun ramp(x: DoubleArray, from: Double, to: Double, @Suppress("SameParameterValue") pre: Double): ShortArray {
        val n = x.size
        return ShortArray(n) { i ->
            val g = from + (to - from) * i / n
            val v = x[i] * g * pre / 32768.0
            val a = abs(v)
            val lim = if (a < 0.7) a else 0.7 + 0.3 * tanh((a - 0.7) / 0.3)
            ((if (v < 0) -lim else lim) * 32767).toInt().toShort()
        }
    }

    private var enhancer: LoudnessEnhancer? = null

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, frame * 2 * 12))    // room for ~700 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
        try { enhancer?.release() } catch (e: Exception) { }
        enhancer = try { LoudnessEnhancer(t.audioSessionId) } catch (e: Exception) { null }
        return t
    }

    private fun ms(l: Collection<ShortArray>) = l.sumOf { it.size } * 1000L / rate

    private val pending = ArrayList<ShortArray>()

    /** Collects about [PREBUFFER_MS] of audio (waiting at most [PREBUFFER_WAIT]) starting with [f]. */
    private fun fill(f: ShortArray) {
        pending.add(f)
        val until = System.currentTimeMillis() + PREBUFFER_WAIT
        while (ms(pending) < PREBUFFER_MS) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) break
            pending.add(queue.poll(left, TimeUnit.MILLISECONDS) ?: break)
        }
    }

    /** Writes what is pending; returns when the track will have played all of it. */
    private fun writeAll(t: AudioTrack, from: Long): Long {
        var at = from
        for (p in pending) { val o = shape(p); t.write(o, 0, o.size); at += o.size * 1000L / rate }
        pending.clear()
        return at
    }

    /** Only if far behind: skip near-silent chunks first, speech only as a last resort. */
    private fun trimBacklog() {
        if (ms(queue) <= MAX_BACKLOG_MS) return
        val quiet = queue.firstOrNull { c -> var s = 0.0; for (v in c) s += v.toDouble() * v; sqrt(s / c.size) < NOISE_RMS }
        if (quiet != null) queue.remove(quiet) else queue.poll()
    }

    /**
     * Playback with a jitter buffer. Voice over the internet arrives unevenly, so each transmission starts once
     * ~300 ms are buffered; if the buffer runs dry it is refilled before playing on, instead of chopping words.
     */
    private fun playLoop() {
        var track: AudioTrack? = null
        while (true) {
            val first = try { queue.take() } catch (e: InterruptedException) { return }
            try {
                wake.acquire(20_000)
                val t = track ?: newTrack().also { track = it }
                try {
                    enhancer?.setTargetGain(when (boost) { 0 -> 0; 2 -> 900; else -> 500 })
                    enhancer?.enabled = boost > 0
                } catch (e: Exception) { }
                gain = 1.0
                fill(first)
                t.play()
                var drainAt = writeAll(t, System.currentTimeMillis())
                while (true) {
                    trimBacklog()
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
}
