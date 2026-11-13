package io.github.ugoyel.hearby.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Karaoke: the DJ sings or talks into the DJ phone and every phone plays the voice on the beat.
 *
 * Guests play everything a cushion (D) after it was sent, so a singer following the room would
 * always land D late. Instead, while the mic is on, the DJ phone itself plays D − M ahead of the
 * room (its own copy of the music runs with a small delay M, and its YouTube player runs ahead),
 * the singer follows that, and each slice of voice goes into the stream at the moment of music the
 * singer was hearing. The stream to guests is held back a few frames to wait for that voice. In the
 * room, voice and music land together on every phone.
 *
 * Pure logic, tested on the desktop; the microphone itself is host/KaraokeMic.kt.
 */
object Karaoke {
    /** How long after its timestamp the DJ phone plays its own copy while the mic is on (incl. output). */
    const val MONITOR_DELAY_MS = 100

    /** The microphone's own buffering, generously: voice older than this is surely captured. */
    const val MIC_LATENCY_MS = 40

    /** Music level while the mic is on: −7 dB, so the voice sits on top without the party stopping. */
    const val DUCK_GAIN = 0.45f

    /**
     * Frames the guest stream is held back: the voice for a frame is sung while the DJ phone plays
     * it (its timestamp + [MONITOR_DELAY_MS] + the DJ phone's own corrections) and must be captured
     * before the frame goes out.
     */
    fun lookaheadFrames(djAdjustUs: Long): Int {
        val ms = MONITOR_DELAY_MS + (djAdjustUs / 1000).coerceAtLeast(0) + MIC_LATENCY_MS + Proto.FRAME_MS
        return ((ms + Proto.FRAME_MS - 1) / Proto.FRAME_MS).toInt()
    }

    /** When the voice for the frame stamped [timestampUs] was sung (DJ phone clock, µs). */
    fun sungAtUs(timestampUs: Long, djAdjustUs: Long): Long = timestampUs + MONITOR_DELAY_MS * 1000L + djAdjustUs

    /** Mixes mono [voice] into interleaved stereo [music] and keeps the peaks musical (soft limit). */
    fun mix(music: FloatArray, voice: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val v = voice[i]
            music[2 * i] = soften(music[2 * i] + v)
            music[2 * i + 1] = soften(music[2 * i + 1] + v)
        }
    }

    /** Transparent below 0.8, then rounds off toward ±1 instead of clipping. */
    internal fun soften(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val y = KNEE + (1f - KNEE) * tanh(over.toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    private const val KNEE = 0.8f
}

/** Smoothly moves the music level toward a target: down in about 0.3 s, back up in about 0.8 s. */
class Ducker(private val sampleRate: Int = Proto.SAMPLE_RATE) {
    var gain = 1f
        private set

    /** Scales interleaved stereo [frame] in place, ramping sample by sample toward [target]. */
    fun process(frame: FloatArray, frames: Int, target: Float) {
        val down = (1f - Karaoke.DUCK_GAIN) / (DOWN_S * sampleRate)
        val up = (1f - Karaoke.DUCK_GAIN) / (UP_S * sampleRate)
        for (i in 0 until frames) {
            gain = when {
                gain > target -> maxOf(target, gain - down)
                gain < target -> minOf(target, gain + up)
                else -> gain
            }
            frame[2 * i] *= gain
            frame[2 * i + 1] *= gain
        }
    }

    companion object {
        const val DOWN_S = 0.3f
        const val UP_S = 0.8f
    }
}

/**
 * Cleans up the microphone for the party: takes out rumble and handling noise (high-pass at 100 Hz),
 * keeps it quiet between phrases (a noise gate, so the room's own sound isn't sent back out), and
 * brings the voice up to a strong level.
 */
class VoiceProcessor(private val sampleRate: Int = Proto.SAMPLE_RATE) {
    private val hpA = exp(-2.0 * PI * HIGH_PASS_HZ / sampleRate).toFloat()
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var envelope = 0f
    private var gate = 0f
    private var holdLeft = 0

    /** True while the gate is open (someone is singing or talking). */
    val open: Boolean get() = gate > 0.5f

    fun process(mono: FloatArray, frames: Int) {
        val attack = 1f - exp(-1.0 / (ATTACK_S * sampleRate)).toFloat()
        val release = 1f - exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()
        val envCoef = 1f - exp(-1.0 / (ENVELOPE_S * sampleRate)).toFloat()
        val holdFrames = (HOLD_S * sampleRate).toInt()
        for (i in 0 until frames) {
            // High-pass: y[n] = a * (y[n-1] + x[n] - x[n-1])
            val x = mono[i]
            val hp = hpA * (hpPrevOut + x - hpPrevIn)
            hpPrevIn = x
            hpPrevOut = hp
            // Level, and the gate that follows it.
            envelope += (hp * hp - envelope) * envCoef
            val loud = sqrt(envelope) > OPEN_LEVEL
            if (loud) holdLeft = holdFrames else if (holdLeft > 0) holdLeft--
            val target = if (loud || holdLeft > 0) 1f else 0f
            gate += (target - gate) * if (target > gate) attack else release
            mono[i] = hp * gate * GAIN
        }
    }

    companion object {
        const val HIGH_PASS_HZ = 100.0
        /** RMS above which the gate opens: about −42 dBFS, well above a quiet room's hiss. */
        const val OPEN_LEVEL = 0.008f
        const val ATTACK_S = 0.005f
        const val RELEASE_S = 0.15f
        const val HOLD_S = 0.2f
        const val ENVELOPE_S = 0.01f
        /** Phones' microphones are quiet at arm's length: +12 dB. */
        const val GAIN = 4f
    }
}

/**
 * The last couple of seconds of microphone audio, each sample tied to the moment it was captured
 * (from the recorder's own timestamps), so the sender can take exactly the voice sung during a given
 * stretch of time. One thread writes, one reads.
 */
class VoiceTape(private val sampleRate: Int = Proto.SAMPLE_RATE, seconds: Int = 3) {
    private val ring = FloatArray(sampleRate * seconds)
    @Volatile private var written = 0L // samples written so far
    private val lock = Any()
    private var anchorIndex = 0L // sample index captured at anchorUs
    private var anchorUs = Long.MIN_VALUE

    /** Appends captured samples. */
    fun write(src: FloatArray, n: Int) {
        var w = written
        for (i in 0 until n) {
            ring[(w % ring.size).toInt()] = src[i]
            w++
        }
        written = w
    }

    /** Ties sample [index] to the moment it was captured, µs (from AudioRecord.getTimestamp). */
    fun anchor(index: Long, capturedUs: Long) = synchronized(lock) {
        anchorIndex = index
        anchorUs = capturedUs
    }

    val samplesWritten: Long get() = written

    /**
     * Copies [n] samples captured from [fromUs] on into [out]; anything not (or no longer) in the
     * tape is silence. Returns how many samples were really there.
     */
    fun read(fromUs: Long, n: Int, out: FloatArray): Int {
        val (aIndex, aUs) = synchronized(lock) { anchorIndex to anchorUs }
        java.util.Arrays.fill(out, 0, n, 0f)
        if (aUs == Long.MIN_VALUE) return 0
        val start = aIndex + Math.round((fromUs - aUs) * sampleRate / 1e6)
        val end = written
        val oldest = maxOf(0L, end - ring.size + sampleRate / 10) // keep clear of the writer
        var got = 0
        for (i in 0 until n) {
            val idx = start + i
            if (idx in oldest until end) {
                out[i] = ring[(idx % ring.size).toInt()]
                got++
            }
        }
        return got
    }
}
