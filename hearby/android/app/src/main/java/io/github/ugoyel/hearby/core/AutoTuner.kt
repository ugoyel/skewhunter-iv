package io.github.ugoyel.hearby.core

/**
 * Picks the party's sync cushion (how far ahead every phone plans playback) and sound quality (the
 * Opus bitrate) by itself, from what every phone reports once a second, so the DJ never has to.
 *
 *  - Cushion: every guest reports how much audio it has in hand beyond what it is playing (its
 *    margin) and whether it had to fall behind. If anyone is behind or nearly out of margin, the
 *    cushion grows; after a calm minute with plenty to spare everywhere, it shrinks again, which
 *    keeps the party responsive (pause, skip and YouTube react sooner).
 *  - Quality: the best the network carries. Guests report how much audio they lost; the bitrate
 *    steps down when someone loses audio and back up after two clean minutes, and big crowds are
 *    capped so a phone hotspot is not swamped.
 *  - Both are only applied at a quiet moment (paused, YouTube, or a short gap the player leaves
 *    between songs), because every phone switches a moment apart.
 *  - What worked is remembered ([delayMs] and [bitrate] at the end of a party) and the next party
 *    starts from it.
 *
 * Pure logic: the host feeds [observe] and applies [wantDelayMs] / [wantBitrate] with [applied].
 */
class AutoTuner(startDelayMs: Int = DEFAULT_DELAY_MS, startBitrate: Int = MAX_BITRATE) {

    /** What one guest said in its last report. */
    class Guest(
        val id: Long,
        /** Received audio beyond what it is playing now, ms; negative if it isn't getting audio. */
        val marginMs: Int,
        val status: Int,
        /** Audio it lost recently, % (0 if it doesn't say). */
        val lossPercent: Int = 0,
        val bluetooth: Boolean = false,
        /** A browser: its speaker delay is a guess until a sound check measures it. */
        val browser: Boolean = false,
        val soundChecked: Boolean = false,
        /** A browser gets PCM over TCP, not the Opus stream, so it doesn't count toward the bitrate. */
        val opus: Boolean = true,
    )

    /** In effect now. */
    var delayMs = startDelayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        private set
    var bitrate = BITRATES.lastOrNull { it <= startBitrate } ?: BITRATES.first()
        private set

    /** What they should become at the next quiet moment. */
    var wantDelayMs = delayMs
        private set
    var wantBitrate = bitrate
        private set

    val pending: Boolean get() = wantDelayMs != delayMs || wantBitrate != bitrate

    /** True while someone on Bluetooth or in a browser would sound better after a sound check. */
    var suggestSoundCheck = false
        private set

    private val worstMargins = ArrayDeque<Pair<Long, Int>>() // (time ms, worst margin that report)
    private var lastLateMs = Long.MIN_VALUE / 2
    private var lastRaiseMs = Long.MIN_VALUE / 2
    private var lastLowerMs = Long.MIN_VALUE / 2
    private var lastLossMs = Long.MIN_VALUE / 2
    private var lastBitrateChangeMs = Long.MIN_VALUE / 2
    private var firstReportMs = -1L
    /** After a change, phones need a few seconds to settle before their reports mean anything. */
    private var settleUntilMs = Long.MIN_VALUE / 2

    fun observe(nowMs: Long, guests: List<Guest>) {
        val inSync = guests.filter { it.status == Proto.STATUS_SYNCED || it.status == Proto.STATUS_LATE }
        suggestSoundCheck = inSync.any { (it.bluetooth || it.browser) && !it.soundChecked }
        if (inSync.isEmpty()) {
            worstMargins.clear()
            firstReportMs = -1
            return
        }
        if (firstReportMs < 0) firstReportMs = nowMs
        tuneCushion(nowMs, inSync)
        tuneQuality(nowMs, inSync)
    }

    private fun tuneCushion(nowMs: Long, guests: List<Guest>) {
        if (wantDelayMs != delayMs || nowMs < settleUntilMs) return // a change is on its way or settling
        val late = guests.any { it.status == Proto.STATUS_LATE }
        if (late) lastLateMs = nowMs
        val known = guests.filter { it.marginMs >= 0 }
        if (known.isNotEmpty()) worstMargins.addLast(nowMs to known.minOf { it.marginMs })
        while (worstMargins.isNotEmpty() && worstMargins.first().first < nowMs - CALM_MS) worstMargins.removeFirst()
        val recent = worstMargins.filter { it.first >= nowMs - RECENT_MS }.minOfOrNull { it.second }

        // Someone fell behind or is about to: more cushion, a step at a time until everyone is fine.
        if ((late || (recent != null && recent < LOW_MARGIN_MS)) && nowMs - lastRaiseMs >= RAISE_EVERY_MS) {
            val needed = if (recent != null && !late) TARGET_MARGIN_MS - recent else RAISE_STEP_MS
            val target = roundUp(delayMs + maxOf(RAISE_STEP_MS, needed))
            if (target > wantDelayMs) {
                wantDelayMs = target.coerceAtMost(MAX_DELAY_MS)
                lastRaiseMs = nowMs
            }
            return
        }

        // A calm minute with margin to spare everywhere: give some back.
        val calm = nowMs - lastLateMs >= CALM_MS && nowMs - lastRaiseMs >= CALM_MS && nowMs - lastLowerMs >= CALM_MS &&
            nowMs - firstReportMs >= CALM_MS
        val worstCalm = worstMargins.minOfOrNull { it.second } ?: return
        if (calm && worstMargins.first().first <= nowMs - CALM_MS + 2_000 && worstCalm > TARGET_MARGIN_MS + SPARE_MS) {
            val target = roundDown(delayMs - (worstCalm - TARGET_MARGIN_MS)).coerceAtLeast(MIN_DELAY_MS)
            if (target < delayMs) {
                wantDelayMs = target
                lastLowerMs = nowMs
            }
        }
    }

    private fun tuneQuality(nowMs: Long, guests: List<Guest>) {
        val opusGuests = guests.count { it.opus }
        val cap = when {
            opusGuests > 20 -> BITRATES[0]
            opusGuests > 12 -> BITRATES[1]
            else -> BITRATES.last()
        }
        val worstLoss = guests.filter { it.opus }.maxOfOrNull { it.lossPercent } ?: 0
        if (worstLoss >= LOSSY_PERCENT) lastLossMs = nowMs
        val current = minOf(wantBitrate, cap)
        var next = current
        val sinceChange = nowMs - lastBitrateChangeMs
        if (worstLoss >= LOSSY_PERCENT && sinceChange >= STEP_DOWN_EVERY_MS) {
            next = BITRATES.lastOrNull { it < current } ?: current
        } else if (nowMs - lastLossMs >= CLEAN_MS && sinceChange >= CLEAN_MS) {
            next = BITRATES.firstOrNull { it > current && it <= cap } ?: current
        }
        if (next != wantBitrate) {
            wantBitrate = next
            lastBitrateChangeMs = nowMs
        }
    }

    /** The host switched to [wantDelayMs] and [wantBitrate] at [nowMs]. */
    fun applied(nowMs: Long) {
        if (wantDelayMs != delayMs) {
            worstMargins.clear() // margins measured with the old cushion say nothing about the new one
            settleUntilMs = nowMs + SETTLE_MS
            lastRaiseMs = nowMs
            lastLowerMs = nowMs
        }
        delayMs = wantDelayMs
        bitrate = wantBitrate
    }

    private fun roundUp(ms: Int) = (ms + STEP_MS - 1) / STEP_MS * STEP_MS
    private fun roundDown(ms: Int) = ms / STEP_MS * STEP_MS

    companion object {
        const val DEFAULT_DELAY_MS = 350
        const val MIN_DELAY_MS = 200
        const val MAX_DELAY_MS = 1500
        val BITRATES = intArrayOf(64_000, 96_000, 128_000)
        val MAX_BITRATE = BITRATES.last()

        /** The margin every phone should keep for Wi-Fi hiccups. */
        const val TARGET_MARGIN_MS = 120
        /** Below this, a phone is about to run out: raise now. */
        const val LOW_MARGIN_MS = 50
        /** Lower only when everyone has this much more than the target. */
        const val SPARE_MS = 80
        const val RAISE_STEP_MS = 100
        const val STEP_MS = 50
        const val RECENT_MS = 10_000L
        const val RAISE_EVERY_MS = 8_000L
        const val SETTLE_MS = 12_000L
        const val CALM_MS = 60_000L
        const val LOSSY_PERCENT = 3
        const val STEP_DOWN_EVERY_MS = 15_000L
        const val CLEAN_MS = 120_000L
    }
}
