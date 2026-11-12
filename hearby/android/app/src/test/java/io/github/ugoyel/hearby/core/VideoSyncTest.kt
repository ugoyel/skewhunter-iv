package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor

class YouTubeLinksTest {
    @Test
    fun findsTheVideoInEveryKindOfLink() {
        val id = "dQw4w9WgXcQ"
        for (link in listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=xYz123",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube-nocookie.com/embed/$id",
            "Check this out! https://youtu.be/$id 🔥",
            "youtube.com/watch?v=$id",
            id,
        )) assertEquals(link, id, YouTubeLinks.videoId(link))
    }

    @Test
    fun ignoresEverythingElse() {
        for (text in listOf("", "hello", "https://vimeo.com/123456", "https://www.youtube.com/", "https://youtu.be/short", "dQw4w9WgXc")) {
            assertNull(text, YouTubeLinks.videoId(text))
        }
    }
}

class VideoTimelineTest {
    @Test
    fun positionFollowsTheClockOnlyWhilePlaying() {
        val playing = VideoTimeline("id", "", true, anchorHostUs = 10_000_000, positionUs = 5_000_000, version = 1)
        assertEquals(5_000_000L, playing.positionAt(8_000_000)) // held until the start
        assertEquals(2_000_000L, playing.startsInUs(8_000_000))
        assertEquals(5_000_000L, playing.positionAt(10_000_000))
        assertEquals(8_500_000L, playing.positionAt(13_500_000))
        assertEquals(0L, playing.startsInUs(13_500_000))
        val paused = playing.copy(playing = false, positionUs = 42_000_000)
        assertEquals(42_000_000L, paused.positionAt(99_000_000))
        assertEquals(0L, paused.startsInUs(0))
    }
}

/**
 * The controller against simulated YouTube players: start and seek delays that vary, seeks that land
 * on the previous keyframe until that part of the video is loaded, playback clocks that drift, and
 * either fine or coarse speed steps.
 */
class VideoSyncTest {

    /** A rough model of the YouTube IFrame player on a phone. */
    private class SimPlayer(
        seed: Long,
        private val driftPpm: Double,
        val rates: DoubleArray,
        private val adUs: Long = 0,
    ) {
        private val rng = Random(seed)
        var state = VideoSyncController.UNSTARTED
        var positionUs = 0.0 // the true media position
        var rate = 1.0
        private var videoId = ""
        private var pendingUntil = Long.MIN_VALUE // a seek or start is in progress until then
        private var pendingTo = 0.0
        private var pendingPlay = false
        private var wantPlay = false
        private var bufferedFrom = 0.0
        private var bufferedTo = 0.0
        private var adLeft = 0L
        private var lastUs = 0L
        private var playAtUs = Long.MIN_VALUE
        private var restoreRateAtUs = Long.MIN_VALUE
        var actions = 0

        fun apply(a: PlayerAction, nowUs: Long) {
            actions++
            when (a) {
                is PlayerAction.Load -> {
                    videoId = a.videoId
                    state = VideoSyncController.CUED
                    positionUs = 0.0
                    bufferedFrom = 0.0
                    bufferedTo = 0.0
                    adLeft = adUs
                }
                is PlayerAction.Play -> if (a.afterUs > 0) playAtUs = nowUs + a.afterUs else play(nowUs)
                PlayerAction.Pause -> {
                    wantPlay = false
                    if (state == VideoSyncController.PLAYING || state == VideoSyncController.BUFFERING) state = VideoSyncController.PAUSED
                    pendingUntil = Long.MIN_VALUE
                }
                is PlayerAction.Seek -> {
                    if (adLeft > 0) return // YouTube ignores seeks during an ad
                    val to = a.seconds * 1e6
                    val loaded = to >= bufferedFrom && to <= bufferedTo
                    val lands = if (loaded) to else floor(to / KEYFRAME_US) * KEYFRAME_US
                    pendingUntil = nowUs + if (loaded) 80_000 + rng.nextInt(120_000) else 300_000 + rng.nextInt(400_000)
                    pendingTo = lands
                    pendingPlay = wantPlay || state == VideoSyncController.PLAYING
                    if (!loaded) {
                        bufferedFrom = lands
                        bufferedTo = lands
                    }
                    state = if (pendingPlay) VideoSyncController.BUFFERING else VideoSyncController.PAUSED
                    positionUs = lands
                }
                is PlayerAction.Rate -> {
                    rate = rates.minBy { abs(it - a.rate) } // the player rounds to what it has
                    restoreRateAtUs = if (a.forUs > 0) nowUs + a.forUs else Long.MIN_VALUE
                }
            }
        }

        private fun play(nowUs: Long) {
            wantPlay = true
            if (pendingUntil != Long.MIN_VALUE) {
                pendingPlay = true // a seek in progress: play when it lands
                return
            }
            if (state != VideoSyncController.PLAYING) {
                pendingUntil = nowUs + 150_000 + rng.nextInt(250_000) + if (bufferedTo <= bufferedFrom) 250_000 else 0
                pendingTo = positionUs
                pendingPlay = true
                state = VideoSyncController.BUFFERING
            }
        }

        /** Advance in small steps so timed play and speed changes happen at their exact moment. */
        fun advance(toUs: Long) {
            while (lastUs < toUs) {
                var next = minOf(toUs, lastUs + 1_000)
                if (playAtUs in (lastUs + 1)..next) next = playAtUs
                if (restoreRateAtUs in (lastUs + 1)..next) next = restoreRateAtUs
                step(next)
                if (next == playAtUs) {
                    playAtUs = Long.MIN_VALUE
                    play(next)
                }
                if (next == restoreRateAtUs) {
                    restoreRateAtUs = Long.MIN_VALUE
                    rate = 1.0
                }
            }
        }

        private fun step(toUs: Long) {
            val dt = toUs - lastUs
            lastUs = toUs
            if (pendingUntil != Long.MIN_VALUE && toUs >= pendingUntil) {
                positionUs = pendingTo + if (pendingPlay) (toUs - pendingUntil).toDouble() else 0.0
                state = if (pendingPlay) VideoSyncController.PLAYING else VideoSyncController.PAUSED
                pendingUntil = Long.MIN_VALUE
                return
            }
            if (state == VideoSyncController.PLAYING) {
                if (adLeft > 0) {
                    adLeft -= dt
                    return
                }
                positionUs += dt * rate * (1 + driftPpm * 1e-6)
                bufferedTo = maxOf(bufferedTo, positionUs + 30_000_000) // it keeps loading ahead
            }
        }

        fun report(nowUs: Long) = PlayerReport(
            ready = true, state = state,
            positionUs = (positionUs + rng.nextGaussian() * 1_500).toLong(), // the API's reading is a little noisy
            measuredAtUs = nowUs, rate = rate, availableRates = rates, videoId = videoId,
        )
    }

    private val fineRates = DoubleArray(36) { 0.25 + 0.05 * it } // 0.25 .. 2.0 in 0.05 steps
    private val coarseRates = doubleArrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    private class Phone(val player: SimPlayer, val controller: VideoSyncController = VideoSyncController())

    /** Run the phones for [seconds], 5 ticks per second, recording the true error once settled. */
    private fun run(
        phones: List<Phone>,
        timeline: (Long) -> VideoTimeline?,
        fromUs: Long,
        seconds: Double,
        settleUs: Long,
        worst: LongArray,
    ): Long {
        var now = fromUs
        val end = fromUs + (seconds * 1e6).toLong()
        while (now < end) {
            now += 200_000
            val t = timeline(now)
            for ((i, ph) in phones.withIndex()) {
                ph.player.advance(now)
                val target = t?.positionAt(now) ?: 0L
                val startsIn = t?.startsInUs(now) ?: 0L
                for (a in ph.controller.step(now, t, target, startsIn, ph.player.report(now), mayStart = true)) ph.player.apply(a, now)
                if (t != null && t.playing && now > settleUs && target > 0) {
                    worst[i] = maxOf(worst[i], abs(ph.player.positionUs.toLong() - target))
                }
            }
        }
        return now
    }

    private fun phones(rates: DoubleArray, ad: Long = 0, seed: Long = 0) = listOf(
        Phone(SimPlayer(seed * 3 + 1, driftPpm = 40.0, rates = rates)),
        Phone(SimPlayer(seed * 3 + 2, driftPpm = -55.0, rates = rates, adUs = ad)),
        Phone(SimPlayer(seed * 3 + 3, driftPpm = 10.0, rates = rates)),
    )

    @Test
    fun startsTogetherAndStaysWithinAFewMillisecondsWithFineSpeeds() {
        val phones = phones(fineRates)
        val start = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val worst = LongArray(3)
        run(phones, { start }, 0, 180.0, settleUs = 10_000_000, worst = worst)
        for ((i, w) in worst.withIndex()) {
            assertTrue("phone $i worst ${w / 1000.0} ms after settling (seeks ${phones[i].controller.seeks})", w < 15_000)
            assertTrue("phone $i seeked ${phones[i].controller.seeks} times", phones[i].controller.seeks <= 6)
        }
        println("fine speeds: worst ${worst.map { it / 1000.0 }} ms, seeks ${phones.map { it.controller.seeks }}, nudges ${phones.map { it.controller.nudges }}")
    }

    @Test
    fun staysWithinTheSeekThresholdWithOnlyCoarseSpeeds() {
        val phones = phones(coarseRates)
        val start = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val worst = LongArray(3)
        run(phones, { start }, 0, 180.0, settleUs = 10_000_000, worst = worst)
        for ((i, w) in worst.withIndex()) {
            assertTrue("phone $i worst ${w / 1000.0} ms after settling", w < 20_000)
            assertTrue("phone $i seeked ${phones[i].controller.seeks} times", phones[i].controller.seeks <= 6)
        }
        println("coarse speeds: worst ${worst.map { it / 1000.0 }} ms, seeks ${phones.map { it.controller.seeks }}, nudges ${phones.map { it.controller.nudges }}")
    }

    @Test
    fun resumesFromThePausePointTogether() {
        val phones = phones(fineRates)
        val playing = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val paused = playing.copy(playing = false, anchorHostUs = 20_000_000, positionUs = playing.positionAt(20_000_000), version = 2)
        val resumed = paused.copy(playing = true, anchorHostUs = 26_000_000, version = 3) // "resume in 1 s", sent at 25 s
        val timeline = { now: Long -> if (now < 20_000_000) playing else if (now < 25_000_000) paused else resumed }
        // Right after the resume a phone can be off by however much its player's start delay varied...
        val early = LongArray(3)
        val now = run(phones, timeline, 0, 27.5, settleUs = 26_500_000, worst = early)
        for ((i, w) in early.withIndex()) assertTrue("phone $i right after resuming: ${w / 1000.0} ms", w < 250_000)
        // ... and a few seconds later everyone is back together.
        val later = LongArray(3)
        run(phones, timeline, now, 30.0, settleUs = 33_000_000, worst = later)
        for ((i, w) in later.withIndex()) assertTrue("phone $i 7 s after resuming: ${w / 1000.0} ms", w < 15_000)
        println("resume: right after ${early.map { it / 1000.0 }} ms, 7 s later ${later.map { it / 1000.0 }} ms")
    }

    @Test
    fun aPhoneThatGetsAnAdCatchesUpAfterIt() {
        val phones = phones(fineRates, ad = 6_000_000)
        val start = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val worst = LongArray(3)
        run(phones, { start }, 0, 60.0, settleUs = 20_000_000, worst = worst)
        assertTrue("phone with the ad: worst ${worst[1] / 1000.0} ms after catching up", worst[1] < 15_000)
    }

    @Test
    fun pausesAndResumesWithEveryone() {
        val phones = phones(fineRates)
        val playing = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val paused = playing.copy(playing = false, anchorHostUs = 30_000_000, positionUs = playing.positionAt(30_000_000), version = 2)
        val resumed = paused.copy(playing = true, anchorHostUs = 41_000_000, version = 3)
        val timeline = { now: Long -> if (now < 30_000_000) playing else if (now < 40_000_000) paused else resumed }
        val worst = LongArray(3)
        var now = run(phones, timeline, 0, 34.0, settleUs = 12_000_000, worst = worst)
        for (ph in phones) {
            assertEquals("paused", VideoSyncController.PAUSED, ph.player.state)
            assertTrue("parked near the pause point", abs(ph.player.positionUs - paused.positionUs) < 400_000)
        }
        val after = LongArray(3)
        now = run(phones, timeline, now, 60.0, settleUs = 50_000_000, worst = after)
        for ((i, w) in after.withIndex()) assertTrue("phone $i after resuming: ${w / 1000.0} ms", w < 15_000)
    }

    @Test
    fun aLateJoinerJumpsStraightIn() {
        val phone = Phone(SimPlayer(9, driftPpm = 20.0, rates = fineRates))
        val timeline = VideoTimeline("vid", "Song", true, anchorHostUs = 0, positionUs = 0, version = 1) // started long ago
        val worst = LongArray(1)
        run(listOf(phone), { timeline }, 95_000_000, 30.0, settleUs = 105_000_000, worst = worst)
        assertTrue("late joiner worst ${worst[0] / 1000.0} ms", worst[0] < 15_000)
    }

    /** The bounds above are not luck: they hold for every one of many other random runs too. */
    @Test
    fun holdsUpAcrossManyRandomRuns() {
        val start = VideoTimeline("vid", "Song", true, anchorHostUs = 2_000_000, positionUs = 0, version = 1)
        val paused = start.copy(playing = false, anchorHostUs = 20_000_000, positionUs = start.positionAt(20_000_000), version = 2)
        val resumed = paused.copy(playing = true, anchorHostUs = 26_000_000, version = 3)
        val timeline = { now: Long -> if (now < 20_000_000) start else if (now < 25_000_000) paused else resumed }
        var fineWorst = 0L
        var coarseWorst = 0L
        var resumeWorst = 0L
        for (seed in 1L..40L) {
            val fine = LongArray(3)
            val finePhones = phones(fineRates, seed = seed)
            run(finePhones, { start }, 0, 90.0, settleUs = 10_000_000, worst = fine)
            val coarse = LongArray(3)
            run(phones(coarseRates, seed = seed), { start }, 0, 90.0, settleUs = 10_000_000, worst = coarse)
            val early = LongArray(3)
            val resumePhones = phones(fineRates, ad = if (seed % 4 == 0L) 6_000_000 else 0, seed = seed)
            val now = run(resumePhones, timeline, 0, 27.5, settleUs = 26_500_000, worst = early)
            val later = LongArray(3)
            run(resumePhones, timeline, now, 30.0, settleUs = 33_000_000, worst = later)
            assertTrue("seed $seed fine ${fine.toList()}", fine.all { it < 15_000 })
            assertTrue("seed $seed seeks ${finePhones.map { it.controller.seeks }}", finePhones.all { it.controller.seeks <= 6 })
            assertTrue("seed $seed coarse ${coarse.toList()}", coarse.all { it < 20_000 })
            assertTrue("seed $seed resume ${later.toList()}", later.all { it < 15_000 })
            fineWorst = maxOf(fineWorst, fine.max())
            coarseWorst = maxOf(coarseWorst, coarse.max())
            resumeWorst = maxOf(resumeWorst, later.max())
        }
        println("40 random runs: worst fine ${fineWorst / 1000.0} ms, coarse ${coarseWorst / 1000.0} ms, 7 s after resume ${resumeWorst / 1000.0} ms")
    }

    @Test
    fun nudgesWithTheGentlestSpeedOnTheRightSide() {
        assertEquals(0.95, VideoSyncController.nudgeRate(fineRates, slower = true)!!, 1e-9)
        assertEquals(1.05, VideoSyncController.nudgeRate(fineRates, slower = false)!!, 1e-9)
        assertNull(VideoSyncController.nudgeRate(coarseRates, slower = true))
        assertEquals(0.75, VideoSyncController.nudgeRate(coarseRates, slower = true, VideoSyncController.MAX_COARSE_NUDGE)!!, 1e-9)
    }

    companion object {
        const val KEYFRAME_US = 2_000_000.0
    }
}
