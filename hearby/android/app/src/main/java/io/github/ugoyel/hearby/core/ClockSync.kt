package io.github.ugoyel.hearby.core

import kotlin.math.abs

/**
 * NTP-style estimate of (host clock - local clock).
 *
 * For one request/response exchange
 *
 *     t0 = client send      (client clock)
 *     t1 = host receive     (host clock)
 *     t2 = host send        (host clock)
 *     t3 = client receive   (client clock)
 *
 *     rtt    = (t3 - t0) - (t2 - t1)
 *     offset = ((t1 - t0) + (t2 - t3)) / 2
 *
 * The offset is only exact when the two network legs take equal time, and its error is bounded by
 * rtt / 2. So we keep a short window of exchanges and trust the one with the smallest round trip
 * (the same idea as NTP's clock filter), then low-pass the result so playout never sees a step
 * unless the clocks really jumped.
 *
 * Mirrored by ios/Hearby/Core/ClockSync.swift.
 */
class ClockSync(private val window: Int = 12) {
    private val offsets = LongArray(window)
    private val rtts = LongArray(window)
    private var filled = 0
    private var next = 0
    private var smoothed = 0.0

    /** Host clock minus local clock, microseconds. */
    @Volatile
    var offsetUs: Long = 0
        private set

    /** Round trip of the exchange currently trusted, microseconds. */
    @Volatile
    var rttUs: Long = 0
        private set

    @Volatile
    var sampleCount: Int = 0
        private set

    val synced: Boolean get() = sampleCount >= MIN_SAMPLES

    fun hostToLocalUs(hostUs: Long): Long = hostUs - offsetUs

    fun localToHostUs(localUs: Long): Long = localUs + offsetUs

    /** Returns false if the exchange was implausible and ignored. */
    @Synchronized
    fun addSample(t0Us: Long, t1Us: Long, t2Us: Long, t3Us: Long): Boolean {
        val rtt = (t3Us - t0Us) - (t2Us - t1Us)
        if (rtt < 0 || rtt > MAX_RTT_US) return false
        val offset = ((t1Us - t0Us) + (t2Us - t3Us)) / 2

        offsets[next] = offset
        rtts[next] = rtt
        next = (next + 1) % window
        if (filled < window) filled++

        var best = 0
        for (i in 1 until filled) if (rtts[i] < rtts[best]) best = i
        val candidate = offsets[best].toDouble()

        smoothed = if (sampleCount == 0 || abs(candidate - smoothed) > STEP_US) {
            candidate // first fix, or the clocks really moved: step
        } else {
            smoothed + (candidate - smoothed) * SMOOTHING
        }
        offsetUs = Math.round(smoothed)
        rttUs = rtts[best]
        sampleCount++
        return true
    }

    @Synchronized
    fun reset() {
        filled = 0
        next = 0
        smoothed = 0.0
        offsetUs = 0
        rttUs = 0
        sampleCount = 0
    }

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_RTT_US = 1_000_000L
        const val STEP_US = 20_000.0
        const val SMOOTHING = 0.25
    }
}

/**
 * Host side: turns jittery "the capture read just returned" times into smooth per-frame timestamps.
 *
 * Audio is captured in bursts, so the wall-clock time at which each 20 ms read returns jitters by
 * several milliseconds. A first-order loop follows the long-term rate of the capture clock (which
 * drifts against the system clock by tens of ppm) while averaging that jitter out.
 */
class TimestampSmoother(
    private val frameUs: Long = Proto.FRAME_US,
    private val gain: Double = 0.02,
    private val resetThresholdUs: Long = 100_000,
) {
    private var last = Double.NaN

    fun next(measuredUs: Long): Long {
        if (last.isNaN()) {
            last = measuredUs.toDouble()
            return measuredUs
        }
        val predicted = last + frameUs
        val error = measuredUs - predicted
        last = if (abs(error) > resetThresholdUs) measuredUs.toDouble() else predicted + error * gain
        return Math.round(last)
    }

    fun reset() {
        last = Double.NaN
    }
}
