package io.github.ugoyel.hearby.core

/**
 * The internet relay's clock, as seen from this phone: every phone in an internet party syncs to it
 * (see hearby/relay), so a video position stamped in relay time means the same moment everywhere.
 *
 * One exchange: this phone sends its time c, the relay answers with its time s, the answer arrives
 * at t. Assuming the way there took as long as the way back, the relay's clock read s at (c + t) / 2.
 * Mobile networks are uneven, so the exchange with the shortest round trip among the recent ones is
 * trusted (it had the least room to be uneven). Pure logic.
 */
class RelayClock {
    private val samples = ArrayDeque<LongArray>() // (round trip, offset) µs

    /** Relay time minus this phone's time, µs, or null until the first answer. Changes only by >1 ms. */
    var offsetUs: Long? = null
        private set

    /** Round trip of the exchange trusted now, µs. */
    var rttUs = 0L
        private set

    val count: Int get() = samples.size

    fun addSample(sentUs: Long, relayUs: Long, receivedUs: Long) {
        val rtt = receivedUs - sentUs
        if (rtt < 0 || rtt > MAX_RTT_US) return
        samples.addLast(longArrayOf(rtt, relayUs - (sentUs + rtt / 2)))
        while (samples.size > WINDOW) samples.removeFirst()
        val best = samples.minBy { it[0] }
        rttUs = best[0]
        val current = offsetUs
        if (current == null || kotlin.math.abs(best[1] - current) > STEADY_US) offsetUs = best[1]
    }

    fun reset() {
        samples.clear()
        offsetUs = null
        rttUs = 0
    }

    private companion object {
        const val WINDOW = 20
        const val MAX_RTT_US = 3_000_000L
        /** Smaller wobbles than this aren't passed on, so the party's timeline isn't re-sent for nothing. */
        const val STEADY_US = 1_000L
    }
}
