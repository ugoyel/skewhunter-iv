package io.github.ugoyel.hearby.core

import kotlin.math.abs

/**
 * YouTube mode, AmpMe style: every phone plays the same YouTube video itself, in YouTube's own
 * embedded player, and Hearby keeps the players in time.
 *
 * The DJ phone owns a [VideoTimeline] ("video X was at position P at host time T, playing or
 * paused") and sends it to everyone. Each phone, the DJ's included, converts it to its own clock
 * with the clock sync Hearby already runs, and a [VideoSyncController] steers its player toward
 * that position through the official IFrame Player API only: load, play, pause, seek and (when
 * the player offers fine speeds) a slight speed change. The player itself is never modified.
 *
 * Mirrored by ios/Hearby/Core/VideoSync.swift.
 */
object YouTubeLinks {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val PATTERNS = listOf(
        Regex("""(?:https?://)?(?:www\.|m\.|music\.)?youtube\.com/watch\?(?:[^#\s]*&)?v=([A-Za-z0-9_-]{11})"""),
        Regex("""(?:https?://)?(?:www\.|m\.)?youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""(?:https?://)?(?:www\.|m\.|music\.)?youtube(?:-nocookie)?\.com/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"""),
    )

    /** The video ID in a YouTube link (or a bare ID), or null. Text around the link is ignored. */
    fun videoId(text: String): String? {
        val t = text.trim()
        if (ID.matches(t)) return t
        for (p in PATTERNS) p.find(t)?.let { return it.groupValues[1] }
        return null
    }
}

/** The party's video: what plays, and where it is on the DJ phone's clock. */
data class VideoTimeline(
    val videoId: String,
    val title: String,
    val playing: Boolean,
    /** DJ clock, µs. While playing, the video starts (or resumes) from [positionUs] at this moment. */
    val anchorHostUs: Long,
    /** Video position at [anchorHostUs], µs. */
    val positionUs: Long,
    /** Bumped on every change, so phones can tell a new instruction from a repeat. */
    val version: Long,
) {
    /** Where the video should be at DJ-clock time [hostUs]: held at [positionUs] until the anchor. */
    fun positionAt(hostUs: Long): Long = if (playing) positionUs + maxOf(0L, hostUs - anchorHostUs) else positionUs

    /** How long until playback (re)starts, µs; 0 if it already has or the video is paused. */
    fun startsInUs(hostUs: Long): Long = if (playing && hostUs < anchorHostUs) anchorHostUs - hostUs else 0L
}

/** What a player reports about itself (YouTube IFrame Player API values). */
class PlayerReport(
    val ready: Boolean,
    /** -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    val state: Int,
    /** Player position, µs, as of [measuredAtUs]. */
    val positionUs: Long,
    /** Local clock, µs. */
    val measuredAtUs: Long,
    val rate: Double = 1.0,
    val availableRates: DoubleArray = doubleArrayOf(1.0),
    val videoId: String = "",
    /** YouTube error code, 0 if none (2 bad id, 5 HTML5 error, 100 not found, 101/150 not embeddable). */
    val error: Int = 0,
)

/** One instruction for the player. */
sealed interface PlayerAction {
    data class Load(val videoId: String) : PlayerAction
    /** Press play [afterUs] from now (scheduled precisely by the caller, not at the next check). */
    data class Play(val afterUs: Long = 0) : PlayerAction
    data object Pause : PlayerAction
    data class Seek(val seconds: Double) : PlayerAction
    /** Play at [rate] for [forUs], then back to normal speed (timed precisely by the caller); 0 = until told. */
    data class Rate(val rate: Double, val forUs: Long = 0) : PlayerAction
}

/**
 * Steers one YouTube player onto the shared timeline. Pure logic: call [step] a few times a second
 * with the latest report and the target position, and carry out the returned actions.
 *
 * Big errors (joining late, after an ad, after a pause) are fixed by seeking. A seek takes a
 * moment to take effect, so the controller aims ahead by a lead it learns from its own previous
 * seeks. How long a seek takes varies by tens of ms, though, so smaller errors are pulled in with
 * an exactly timed speed change instead: a gentle one (within 10 %) when the player offers it,
 * otherwise a short one at up to 25 %. Pitch is preserved by the player either way.
 */
class VideoSyncController {

    private var loadedId: String? = null
    private var cooldownUntilUs = Long.MIN_VALUE
    private var nudgeUntilUs = Long.MIN_VALUE
    private var nudging = false
    private var seekLeadUs = INITIAL_SEEK_LEAD_US
    private var playLeadUs = INITIAL_PLAY_LEAD_US
    private var awaitingSeekResult = false
    private var leadAtSeek = 0L
    private var awaitingStartResult = false
    private var started = false
    private val errors = ArrayDeque<Long>()

    /** Last measured error (player − target), µs, or null while not measuring. Positive = ahead. */
    var lastErrorUs: Long? = null
        private set

    var seeks = 0
        private set

    var nudges = 0
        private set

    /**
     * @param target the party's timeline, or null when no video is on.
     * @param targetPositionUs where the video should be at [nowUs] on this phone (corrections included).
     * @param startsInUs how long until it should (re)start playing on this phone, 0 if it should be playing now.
     * @param mayStart false while the player is not on screen: YouTube's rules do not allow starting
     *   playback of a player that is not visible.
     */
    fun step(
        nowUs: Long,
        target: VideoTimeline?,
        targetPositionUs: Long,
        startsInUs: Long,
        report: PlayerReport,
        mayStart: Boolean,
    ): List<PlayerAction> {
        if (target == null) {
            reset()
            return if (report.ready && (report.state == PLAYING || report.state == BUFFERING)) listOf(PlayerAction.Pause) else emptyList()
        }
        if (!report.ready || report.error != 0) return emptyList()
        if (loadedId != target.videoId) {
            reset()
            loadedId = target.videoId
            cooldownUntilUs = nowUs + LOAD_COOLDOWN_US
            return listOf(PlayerAction.Load(target.videoId))
        }
        if (nowUs < cooldownUntilUs) return emptyList()

        val playing = report.state == PLAYING
        val position = report.positionUs + if (playing) ((nowUs - report.measuredAtUs) * report.rate).toLong() else 0L

        if (!target.playing) {
            started = false
            restoreRate(report)?.let { return listOf(it) }
            if (playing || report.state == BUFFERING) return listOf(PlayerAction.Pause)
            if (abs(position - targetPositionUs) > PAUSED_TOLERANCE_US && targetPositionUs >= 0) {
                cooldownUntilUs = nowUs + SEEK_COOLDOWN_US
                return listOf(PlayerAction.Seek(targetPositionUs / 1e6))
            }
            return emptyList()
        }

        // A start or resume is scheduled: wait at the right spot, then press play just early enough.
        if (startsInUs > 0) {
            if (playing || report.state == BUFFERING) return listOf(PlayerAction.Pause)
            if (abs(position - targetPositionUs) > PARKED_TOLERANCE_US) {
                cooldownUntilUs = nowUs + PARK_COOLDOWN_US
                return listOf(PlayerAction.Seek(targetPositionUs / 1e6))
            }
            if (startsInUs <= playLeadUs + START_WINDOW_US && mayStart) {
                awaitingStartResult = true
                started = true
                errors.clear()
                val after = (startsInUs - playLeadUs).coerceAtLeast(0)
                cooldownUntilUs = nowUs + after + START_COOLDOWN_US
                return listOf(PlayerAction.Play(after))
            }
            return emptyList()
        }

        if (report.state == BUFFERING) return emptyList()
        if (!playing) {
            if (!mayStart) return emptyList()
            // Joined late, resumed, or the player stopped: jump to where everyone is, aiming ahead.
            return seekTo(nowUs, targetPositionUs, play = true)
        }

        // Playing: measure.
        val error = position - targetPositionUs
        if (nudging) {
            if (nowUs < nudgeUntilUs + NUDGE_SETTLE_US) return emptyList()
            nudging = false // the caller has already gone back to normal speed
            errors.clear()
        }
        restoreRate(report)?.let { return listOf(it) }

        // Learn from the last seek or start: aiming at target + lead landed us `error` off.
        if (awaitingSeekResult) {
            awaitingSeekResult = false
            if (abs(error) < LEARN_LIMIT_US) seekLeadUs = (leadAtSeek - error).coerceIn(0, MAX_LEAD_US).let { (seekLeadUs + it) / 2 }
        }
        if (awaitingStartResult) {
            awaitingStartResult = false
            // Pressed play `playLead` early and landed `error` ahead: the real start delay is playLead − error.
            if (abs(error) < LEARN_LIMIT_US) playLeadUs = (playLeadUs - error).coerceIn(0, MAX_LEAD_US).let { (playLeadUs + it) / 2 }
        }

        errors.addLast(error)
        while (errors.size > 3) errors.removeFirst()
        val e = errors.sorted()[errors.size / 2] // median of the last three: one odd sample cannot trigger a seek
        lastErrorUs = e

        if (abs(error) > HARD_ERROR_US || (errors.size >= 3 && abs(e) > SEEK_THRESHOLD_US)) {
            return seekTo(nowUs, targetPositionUs, play = false)
        }
        if (errors.size < 3) return emptyList()
        val rate = when {
            abs(e) > COARSE_NUDGE_THRESHOLD_US -> nudgeRate(report.availableRates, slower = e > 0, MAX_FINE_NUDGE)
                ?: nudgeRate(report.availableRates, slower = e > 0, MAX_COARSE_NUDGE)
            abs(e) > NUDGE_THRESHOLD_US -> nudgeRate(report.availableRates, slower = e > 0, MAX_FINE_NUDGE)
            else -> null
        } ?: return emptyList()
        val forUs = (abs(e) / abs(rate - 1.0)).toLong()
        nudging = true
        nudges++
        nudgeUntilUs = nowUs + forUs
        return listOf(PlayerAction.Rate(rate, forUs))
    }

    private fun seekTo(nowUs: Long, targetPositionUs: Long, play: Boolean): List<PlayerAction> {
        seeks++
        leadAtSeek = seekLeadUs
        awaitingSeekResult = true
        errors.clear()
        cooldownUntilUs = nowUs + SEEK_COOLDOWN_US
        val seek = PlayerAction.Seek((targetPositionUs + seekLeadUs) / 1e6)
        return if (play) listOf(seek, PlayerAction.Play()) else listOf(seek)
    }

    private fun restoreRate(report: PlayerReport): PlayerAction? =
        if (!nudging && abs(report.rate - 1.0) > 1e-3) PlayerAction.Rate(1.0) else null

    private fun reset() {
        loadedId = null
        nudging = false
        awaitingSeekResult = false
        awaitingStartResult = false
        started = false
        errors.clear()
        lastErrorUs = null
    }

    companion object {
        const val UNSTARTED = -1
        const val ENDED = 0
        const val PLAYING = 1
        const val PAUSED = 2
        const val BUFFERING = 3
        const val CUED = 5

        const val HARD_ERROR_US = 1_000_000L
        const val SEEK_THRESHOLD_US = 150_000L
        const val COARSE_NUDGE_THRESHOLD_US = 15_000L
        const val NUDGE_THRESHOLD_US = 8_000L
        const val NUDGE_SETTLE_US = 150_000L
        const val START_WINDOW_US = 400_000L
        const val PAUSED_TOLERANCE_US = 300_000L
        const val PARKED_TOLERANCE_US = 50_000L
        const val PARK_COOLDOWN_US = 600_000L
        const val INITIAL_SEEK_LEAD_US = 250_000L
        const val INITIAL_PLAY_LEAD_US = 150_000L
        const val MAX_LEAD_US = 2_000_000L
        /**
         * Only landings this close teach anything about delay: a seek into a part of the video that
         * is not loaded yet lands on an earlier keyframe, which says nothing about timing.
         */
        const val LEARN_LIMIT_US = 300_000L
        const val LOAD_COOLDOWN_US = 1_000_000L
        const val SEEK_COOLDOWN_US = 1_500_000L
        const val START_COOLDOWN_US = 1_200_000L
        const val MAX_FINE_NUDGE = 0.10
        const val MAX_COARSE_NUDGE = 0.25

        /** The available speed closest to normal, within [maxChange], on the requested side. */
        fun nudgeRate(rates: DoubleArray, slower: Boolean, maxChange: Double = MAX_FINE_NUDGE): Double? = rates
            .filter { if (slower) it < 1.0 && it >= 1.0 - maxChange - 1e-9 else it > 1.0 && it <= 1.0 + maxChange + 1e-9 }
            .minByOrNull { abs(it - 1.0) }
    }
}
