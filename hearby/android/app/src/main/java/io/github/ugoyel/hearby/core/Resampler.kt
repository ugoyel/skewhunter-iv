package io.github.ugoyel.hearby.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Variable-ratio resampler used for drift correction.
 *
 * The playout engine nudges playback speed by at most a fraction of a percent to keep this device
 * locked to the host timeline. To do that inaudibly we use a 16-tap Kaiser-windowed sinc, stored as
 * 64 polyphase branches with linear interpolation between neighbouring branches. At speed 1.0 with
 * a zero fractional phase the filter degenerates to a pure 8-frame delay, i.e. it is bit-transparent.
 *
 * Audio is interleaved float. Group delay is [DELAY_FRAMES] input frames.
 *
 * Mirrored by ios/Hearby/Core/Resampler.swift.
 */
class Resampler(private val channels: Int = 2, maxInputFrames: Int = Proto.FRAME_SAMPLES) {

    private val work = FloatArray((HISTORY + maxInputFrames) * channels)
    private var phase = 0.0 // read position carried into the next block, in input frames, [0, 1)

    /**
     * Consume [inFrames] frames from [input] and write roughly inFrames / speed frames to [out].
     * [speed] is input frames consumed per output frame (> 1 plays faster). Returns frames written.
     */
    fun process(input: FloatArray, inFrames: Int, speed: Double, out: FloatArray): Int {
        val ch = channels
        System.arraycopy(input, 0, work, HISTORY * ch, inFrames * ch)

        var p = phase
        var produced = 0
        val maxOut = out.size / ch
        while (p < inFrames && produced < maxOut) {
            val i0 = p.toInt()
            val scaled = (p - i0) * PHASES
            val k = scaled.toInt()
            val mu = (scaled - k).toFloat()
            val row0 = k * TAPS
            val row1 = row0 + TAPS
            val base = i0 * ch // work frame i0 is input frame (i0 - HISTORY)
            for (c in 0 until ch) {
                var acc = 0f
                var w = base + c
                for (t in 0 until TAPS) {
                    val h0 = TABLE[row0 + t]
                    acc += (h0 + (TABLE[row1 + t] - h0) * mu) * work[w]
                    w += ch
                }
                out[produced * ch + c] = acc
            }
            produced++
            p += speed
        }
        phase = p - inFrames

        // Keep the last HISTORY frames for the next block.
        System.arraycopy(work, inFrames * ch, work, 0, HISTORY * ch)
        return produced
    }

    fun reset() {
        java.util.Arrays.fill(work, 0f)
        phase = 0.0
    }

    companion object {
        const val TAPS = 16
        const val PHASES = 64
        const val HISTORY = TAPS - 1
        const val DELAY_FRAMES = TAPS / 2
        private const val KAISER_BETA = 6.0

        /** (PHASES + 1) rows of TAPS coefficients; row k is the filter for a fractional delay k / PHASES. */
        internal val TABLE: FloatArray = buildTable()

        private fun buildTable(): FloatArray {
            val table = FloatArray((PHASES + 1) * TAPS)
            val half = TAPS / 2.0
            val i0Beta = besselI0(KAISER_BETA)
            val row = DoubleArray(TAPS)
            for (k in 0..PHASES) {
                val frac = k.toDouble() / PHASES
                var sum = 0.0
                for (t in 0 until TAPS) {
                    // Distance from the interpolation point to tap t, in input frames.
                    val x = (TAPS / 2 - 1) + frac - t
                    val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                    val r = x / half
                    val window = if (abs(r) >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1.0 - r * r)) / i0Beta
                    row[t] = sinc * window
                    sum += row[t]
                }
                // Unity DC gain for every branch, so the level cannot wobble as the phase sweeps.
                for (t in 0 until TAPS) table[k * TAPS + t] = (row[t] / sum).toFloat()
            }
            return table
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var n = 1
            while (n < 64) {
                term *= q / (n.toDouble() * n)
                sum += term
                if (term < sum * 1e-15) break
                n++
            }
            return sum
        }
    }
}
