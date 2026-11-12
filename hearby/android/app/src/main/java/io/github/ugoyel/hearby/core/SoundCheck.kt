package io.github.ugoyel.hearby.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sound check: measures, with the DJ phone's microphone, when each phone's sound *actually* comes
 * out of its speaker, and computes the correction that lines every phone up with the DJ.
 *
 * Why it is needed: clock sync tells each phone *when* to play, and AudioTrack.getTimestamp() tells
 * it how long its audio path takes, but many phones under-report that path by a few milliseconds
 * (speaker DSP, amplifier buffers), and Bluetooth by much more. Those few milliseconds are exactly
 * the audible "flam" between phones. Only listening tells the truth.
 *
 * How: every phone plays the same short chirp, each at its own scheduled moment on the shared
 * audio timeline. The DJ phone records them all. For phone k, the chirp scheduled at media time M_k
 * should be heard at M_k + playoutDelay + (that phone's own adjustments); the difference between
 * when it was really heard and that target is the phone's error e_k. The DJ phone measures its own
 * chirp too, so the microphone's own (unknown) input latency is in every measurement and cancels:
 * the correction for phone k is simply e_dj - e_k.
 *
 * Everything here is plain math so it is unit-tested; ios/Hearby/Core/SoundCheck.swift mirrors the
 * chirp, which iPhones must play identically.
 */
object Chirp {
    const val SAMPLES = 1920 // 40 ms at 48 kHz
    private const val F0 = 1500.0
    private const val F1 = 7000.0
    private const val AMPLITUDE = 0.5
    private const val TAPER = 96 // 2 ms raised-cosine fade at each end

    /** The chirp as played: a linear sweep from 1.5 to 7 kHz. */
    val wave: FloatArray = FloatArray(SAMPLES) { (AMPLITUDE * envelope(it) * sin(phase(it))).toFloat() }

    /** Quadrature copy (cosine), used with [wave] to measure the arrival envelope. */
    internal val quadrature: FloatArray = FloatArray(SAMPLES) { (AMPLITUDE * envelope(it) * cos(phase(it))).toFloat() }

    private fun phase(i: Int): Double {
        val t = i.toDouble() / Proto.SAMPLE_RATE
        val duration = SAMPLES.toDouble() / Proto.SAMPLE_RATE
        return 2 * PI * (F0 * t + (F1 - F0) * t * t / (2 * duration))
    }

    private fun envelope(i: Int): Double = when {
        i < TAPER -> 0.5 - 0.5 * cos(PI * i / TAPER)
        i >= SAMPLES - TAPER -> 0.5 - 0.5 * cos(PI * (SAMPLES - 1 - i) / TAPER)
        else -> 1.0
    }
}

/** Finds when a chirp arrived in a microphone recording. */
object ChirpDetector {

    class Hit(
        /** Position of the chirp's first sample in the recording, in (fractional) samples. */
        val position: Double,
        /** Peak-to-median ratio of the matched-filter envelope: above ~8 is a clear detection. */
        val clarity: Double,
    )

    const val MIN_CLARITY = 8.0

    /**
     * Look for the chirp starting anywhere in `recording[from, to)`. Returns null if nothing
     * stands out. The *first* strong peak wins, so a wall reflection arriving a few ms after the
     * direct sound is not mistaken for it.
     */
    fun find(recording: FloatArray, from: Int, to: Int): Hit? {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(recording.size - Chirp.SAMPLES)
        if (end - start < 8) return null
        val lags = end - start
        val n = Fft.nextPow2(lags + Chirp.SAMPLES)

        // Correlate with the complex chirp (cos + i·sin) in one FFT: |result| is the envelope.
        val sRe = DoubleArray(n)
        val sIm = DoubleArray(n)
        for (i in 0 until minOf(n, recording.size - start)) sRe[i] = recording[start + i].toDouble()
        val hRe = DoubleArray(n)
        val hIm = DoubleArray(n)
        for (i in 0 until Chirp.SAMPLES) {
            hRe[i] = Chirp.quadrature[i].toDouble()
            hIm[i] = Chirp.wave[i].toDouble()
        }
        Fft.transform(sRe, sIm, inverse = false)
        Fft.transform(hRe, hIm, inverse = false)
        for (k in 0 until n) { // S · conj(H)
            val re = sRe[k] * hRe[k] + sIm[k] * hIm[k]
            val im = sIm[k] * hRe[k] - sRe[k] * hIm[k]
            sRe[k] = re
            sIm[k] = im
        }
        Fft.transform(sRe, sIm, inverse = true)

        val env = DoubleArray(lags) { hypot(sRe[it], sIm[it]) }
        var maxIdx = 0
        for (i in 1 until lags) if (env[i] > env[maxIdx]) maxIdx = i
        val max = env[maxIdx]
        if (max <= 0.0) return null
        val median = env.copyOf().also { it.sort() }[lags / 2].coerceAtLeast(1e-12)
        val clarity = max / median
        if (clarity < MIN_CLARITY) return null

        // Direct path: the earliest local peak within 12 ms before the strongest one that reaches 60 % of it.
        var peak = maxIdx
        val earliest = (maxIdx - 576).coerceAtLeast(1)
        for (i in earliest until maxIdx) {
            if (env[i] >= 0.6 * max && env[i] >= env[i - 1] && env[i] >= env[i + 1]) {
                peak = i
                break
            }
        }
        // Sub-sample refinement by fitting a parabola through the peak and its neighbours.
        var frac = 0.0
        if (peak in 1 until lags - 1) {
            val a = env[peak - 1]
            val b = env[peak]
            val c = env[peak + 1]
            val d = a - 2 * b + c
            if (d < 0) frac = (0.5 * (a - c) / d).coerceIn(-0.5, 0.5)
        }
        return Hit(start + peak + frac, clarity)
    }
}

/** Planning and bookkeeping for one sound check. */
object SoundCheckPlan {
    /** Time between chirps. Must exceed the search window so neighbours never overlap. */
    const val SLOT_US = 650_000L
    /** Where to look around the expected arrival: Bluetooth may be far later than its phone thinks. */
    const val WINDOW_BEFORE_US = 100_000L
    const val WINDOW_AFTER_US = 450_000L
    /** Lead time from scheduling to the first chirp, so every phone has the message in time. */
    const val START_LEAD_US = 700_000L
    /** Corrections are applied in rounds; the second one only has to mop up the last fraction of a ms. */
    const val ROUNDS = 2

    class Slot(val clientId: Long, val mediaTimeUs: Long, val isReference: Boolean)

    /**
     * One round: the reference (DJ) phone chirps first and last, everyone else in between. Measuring
     * the reference at both ends cancels any slow drift between the microphone clock and the system
     * clock over the few seconds the round takes.
     */
    fun round(referenceId: Long, others: List<Long>, startMediaUs: Long): List<Slot> {
        val slots = ArrayList<Slot>()
        var t = startMediaUs
        slots.add(Slot(referenceId, t, true))
        for (id in others) {
            t += SLOT_US
            slots.add(Slot(id, t, false))
        }
        t += SLOT_US
        slots.add(Slot(referenceId, t, true))
        return slots
    }

    /**
     * One slot's measurement: when the chirp was really heard minus `mediaTime + playoutDelay`, µs,
     * or null if it was not heard. Deliberately *not* relative to the phone's own adjustments: the
     * aim is that everyone is heard at the same moment as the DJ, whatever each phone thinks it is
     * doing. (The adjustments are only used to know where to look for the chirp.)
     */
    class Measurement(val slot: Slot, val errorUs: Double?)

    /**
     * Correction (µs, positive = play later) for every non-reference phone that was heard, relative
     * to the reference phone. Null for a phone that was not heard. Null map if the reference itself
     * was not heard (then nothing can be compared).
     */
    fun corrections(measurements: List<Measurement>): Map<Long, Double?>? {
        val refs = measurements.filter { it.slot.isReference && it.errorUs != null }
        if (refs.isEmpty()) return null
        fun referenceErrorAt(t: Long): Double {
            if (refs.size == 1) return refs[0].errorUs!!
            val a = refs.first()
            val b = refs.last()
            val span = (b.slot.mediaTimeUs - a.slot.mediaTimeUs).toDouble()
            if (span <= 0) return a.errorUs!!
            val f = (t - a.slot.mediaTimeUs) / span
            return a.errorUs!! + (b.errorUs!! - a.errorUs!!) * f
        }
        val out = LinkedHashMap<Long, Double?>()
        for (m in measurements) {
            if (m.slot.isReference) continue
            out[m.slot.clientId] = m.errorUs?.let { referenceErrorAt(m.slot.mediaTimeUs) - it }
        }
        return out
    }
}

/** Compact iterative radix-2 FFT (in place). Sizes must be powers of two. */
object Fft {
    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) { // bit-reversal permutation
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }
    }
}

/** Mixes scheduled chirps into decoded audio at exact positions on the media timeline. */
class ToneScheduler {
    private val pending = LongArray(16) { Long.MIN_VALUE }

    /** Play the chirp so that its first sample is heard when media time [mediaTimeUs] is. Thread-safe. */
    @Synchronized
    fun schedule(mediaTimeUs: Long) {
        if (pending.any { it == mediaTimeUs }) return // repeated message
        val free = pending.indexOfFirst { it == Long.MIN_VALUE }
        val slot = if (free >= 0) free else pending.indices.minBy { pending[it] }
        pending[slot] = mediaTimeUs
    }

    /** Add any chirp overlapping the frame whose first sample has media time [frameTimeUs]. */
    @Synchronized
    fun mixInto(pcm: FloatArray, frameTimeUs: Long, frames: Int, channels: Int) {
        for (s in pending.indices) {
            val start = pending[s]
            if (start == Long.MIN_VALUE) continue
            val offset = Math.round((frameTimeUs - start) * Proto.SAMPLE_RATE / 1e6) // chirp sample at frame sample 0
            if (offset >= Chirp.SAMPLES) {
                pending[s] = Long.MIN_VALUE // finished (or missed entirely)
                continue
            }
            if (offset + frames <= 0) continue // not yet
            for (j in 0 until frames) {
                val k = offset + j
                if (k < 0 || k >= Chirp.SAMPLES) continue
                val v = Chirp.wave[k.toInt()]
                for (c in 0 until channels) pcm[j * channels + c] += v
            }
        }
    }

    @Synchronized
    fun clear() = pending.fill(Long.MIN_VALUE)

    /** True while a chirp is still to be played (for tests). */
    @get:Synchronized
    val busy: Boolean get() = pending.any { it != Long.MIN_VALUE }
}
