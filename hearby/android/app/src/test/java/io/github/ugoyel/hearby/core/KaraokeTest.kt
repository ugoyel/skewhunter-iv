package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class KaraokeTest {

    @Test
    fun theGuestStreamWaitsJustLongEnoughForTheVoice() {
        for (adjustMs in listOf(0L, 15L, 80L, 220L)) {
            val adjustUs = adjustMs * 1000
            val k = Karaoke.lookaheadFrames(adjustUs)
            // The frame sent now is k frames old. Its voice is sung from sungAt(ts) for 20 ms, and the
            // microphone needs MIC_LATENCY_MS on top: all of it must already be captured.
            val now = 10_000_000L
            val ts = now - k * Proto.FRAME_US
            val voiceEnds = Karaoke.sungAtUs(ts, adjustUs) + Proto.FRAME_US
            assertTrue("adjust $adjustMs ms: voice ends ${(voiceEnds - now) / 1000} ms from now", voiceEnds <= now - Karaoke.MIC_LATENCY_MS * 1000L)
            // ... and not much longer: every frame of waiting is a frame less of cushion for the guests.
            assertTrue(voiceEnds > now - Karaoke.MIC_LATENCY_MS * 1000L - Proto.FRAME_US)
        }
        assertEquals(8, Karaoke.lookaheadFrames(0))
    }

    @Test
    fun theVoiceLandsOnTheBeatTheSingerHeard() {
        // The singer claps exactly when this phone plays the frame stamped 2.000 s (its own corrections: +15 ms).
        val adjustUs = 15_000L
        val tape = VoiceTape()
        val captureStartUs = 1_500_000L // sample 0 was captured then
        tape.anchor(0, captureStartUs)
        val samples = FloatArray(48_000 * 2)
        val clapAtUs = 2_000_000L + Karaoke.MONITOR_DELAY_MS * 1000L + adjustUs
        val clapIndex = ((clapAtUs - captureStartUs) * 48 / 1000).toInt()
        samples[clapIndex] = 1f
        tape.write(samples, samples.size)
        val voice = FloatArray(Proto.FRAME_SAMPLES)
        tape.read(Karaoke.sungAtUs(2_000_000L, adjustUs), Proto.FRAME_SAMPLES, voice)
        assertEquals(1f, voice[0]) // first sample of the frame stamped 2.000 s
        // The frame before has no clap in it.
        tape.read(Karaoke.sungAtUs(2_000_000L - Proto.FRAME_US, adjustUs), Proto.FRAME_SAMPLES, voice)
        assertTrue(voice.all { it == 0f })
    }

    @Test
    fun theTapeReadsByTimeAndKnowsWhatItHasnt() {
        val tape = VoiceTape(seconds = 1)
        val out = FloatArray(480)
        assertEquals(0, tape.read(0, 480, out)) // nothing anchored yet
        tape.anchor(0, 1_000_000)
        val src = FloatArray(4800) { it / 10_000f }
        tape.write(src, src.size)
        assertEquals(480, tape.read(1_010_000, 480, out)) // 10 ms in: samples 480..959
        assertEquals(480 / 10_000f, out[0], 1e-6f)
        assertEquals(959 / 10_000f, out[479], 1e-6f)
        assertEquals(240, tape.read(1_095_000, 480, out)) // only the first half has been captured
        assertEquals(0f, out[300])
        // Long gone: overwritten by newer audio, so silence rather than the wrong voice.
        repeat(20) { tape.write(src, src.size) }
        assertEquals(0, tape.read(1_010_000, 480, out))
    }

    @Test
    fun theMusicDipsSmoothlyAndComesBack() {
        val ducker = Ducker()
        val frame = FloatArray(Proto.FRAME_SAMPLES * 2) { 1f }
        var frames = 0
        var last = 1f
        while (ducker.gain > Karaoke.DUCK_GAIN) {
            java.util.Arrays.fill(frame, 1f)
            ducker.process(frame, Proto.FRAME_SAMPLES, Karaoke.DUCK_GAIN)
            for (i in 0 until Proto.FRAME_SAMPLES) {
                assertTrue("no jumps", abs(frame[2 * i] - last) < 0.001f)
                last = frame[2 * i]
            }
            frames++
        }
        assertEquals("down in about 0.3 s", 15, frames)
        frames = 0
        while (ducker.gain < 1f) {
            java.util.Arrays.fill(frame, 1f)
            ducker.process(frame, Proto.FRAME_SAMPLES, 1f)
            frames++
        }
        assertTrue("back up in about 0.8 s: $frames frames", frames in 39..41)
    }

    @Test
    fun theGateKeepsTheRoomOutAndLetsTheVoiceIn() {
        val p = VoiceProcessor()
        val rng = Random(3)
        val block = FloatArray(480)
        fun run(seconds: Double, make: (Int) -> Float): FloatArray {
            val out = ArrayList<Float>()
            var n = 0
            repeat((seconds * 100).toInt()) {
                for (i in block.indices) block[i] = make(n++)
                p.process(block, block.size)
                block.forEach { out += it }
            }
            return out.toFloatArray()
        }
        // A quiet room (hiss at about −54 dBFS) stays shut.
        val room = run(1.0) { (rng.nextGaussian() * 0.002).toFloat() }
        assertTrue("room ${room.takeLast(24_000).maxOf { abs(it) }}", room.takeLast(24_000).maxOf { abs(it) } < 0.001f)
        assertFalse("open in a quiet room", p.open)
        // Someone sings (a 220 Hz tone at −20 dBFS): through, and louder.
        val sung = run(0.5) { (0.1 * sin(2 * PI * 220 * it / 48_000)).toFloat() }
        assertTrue("closed while singing", p.open)
        assertTrue("sung ${sung.takeLast(4_800).maxOf { abs(it) }}", sung.takeLast(4_800).maxOf { abs(it) } in 0.3f..0.45f)
        // The phrase ends: the gate closes after its hold, and fades out (no abrupt cut) within a second.
        val after = run(1.0) { (rng.nextGaussian() * 0.002).toFloat() }
        assertFalse("still open after the phrase", p.open)
        assertTrue("after ${after.takeLast(4_800).maxOf { abs(it) }}", after.takeLast(4_800).maxOf { abs(it) } < 0.001f)
    }

    @Test
    fun rumbleAndHandlingNoiseAreTakenOut() {
        val p = VoiceProcessor()
        val block = FloatArray(48_000) { 0.3f + (0.1 * sin(2 * PI * 440 * it / 48_000)).toFloat() } // DC offset + voice
        p.process(block, block.size)
        val tail = block.copyOfRange(24_000, 48_000)
        assertTrue("offset removed", abs(tail.average()) < 0.005)
    }

    @Test
    fun loudMixesRoundOffInsteadOfClipping() {
        assertEquals(0.5f, Karaoke.soften(0.5f))
        assertEquals(-0.79f, Karaoke.soften(-0.79f))
        var prev = 0f
        for (i in 1..400) {
            val y = Karaoke.soften(i / 100f)
            assertTrue("soften(${i / 100f}) = $y", y <= 1f && y >= prev)
            prev = y
        }
        val music = floatArrayOf(0.7f, -0.7f)
        Karaoke.mix(music, floatArrayOf(0.6f), 1)
        assertTrue("mixed ${music[0]}", music[0] <= 1f && music[0] > 0.9f)
        assertEquals(-0.1f, music[1], 1e-6f)
    }

    @Test
    fun theDuckFlagRidesOnVideo() {
        val t = VideoTimeline("dQw4w9WgXcQ", "Hit", true, 1, 0, 1)
        val b = WireVideo.encode(1, t, duck = true)
        assertEquals(0x07, b[12].toInt())
        assertTrue(WireVideo.decode(b, b.size)!!.duck)
        assertFalse(WireVideo.decode(WireVideo.encode(1, t), b.size)!!.duck)
    }

    @Test
    fun karaokeKeepsTheCushionRoomy() {
        val tuner = AutoTuner(startDelayMs = 200)
        tuner.floorMs = 350
        tuner.observe(0, listOf(AutoTuner.Guest(1, 300, Proto.STATUS_SYNCED)))
        assertEquals(350, tuner.wantDelayMs)
        tuner.applied(0)
        // A long calm stretch with plenty of margin still doesn't go below the floor.
        for (s in 1..300) tuner.observe(s * 1000L, listOf(AutoTuner.Guest(1, 300, Proto.STATUS_SYNCED)))
        assertEquals(350, tuner.wantDelayMs)
    }
}
