package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

class ChirpDetectorTest {

    private val rate = Proto.SAMPLE_RATE.toDouble()

    /** Add the chirp at a fractional sample position, as a speaker in a room would deliver it. */
    private fun place(into: FloatArray, at: Double, gain: Double) {
        val whole = at.toInt()
        val frac = at - whole
        for (i in 0 until Chirp.SAMPLES + 1) {
            // linear interpolation of the chirp for the fractional part of the delay
            val a = if (i < Chirp.SAMPLES) Chirp.wave[i] else 0f
            val b = if (i > 0) Chirp.wave[i - 1] else 0f
            val v = (1 - frac) * a + frac * b
            val idx = whole + i
            if (idx in into.indices) into[idx] += (gain * v).toFloat()
        }
    }

    /** A small room: the direct sound, wall reflections a few ms later, and a reverb tail. */
    private fun room(into: FloatArray, at: Double, gain: Double, rng: Random) {
        place(into, at, gain)
        place(into, at + 0.0031 * rate, gain * 0.55) // floor
        place(into, at + 0.0074 * rate, gain * 0.40) // nearest wall
        place(into, at + 0.0137 * rate, gain * 0.25)
        for (k in 0 until 40) place(into, at + (0.02 + 0.2 * rng.nextDouble()) * rate, gain * 0.04 * rng.nextGaussian())
    }

    @Test
    fun findsTheDirectSoundToATenthOfAMillisecondInANoisyEchoeyRoom() {
        val rng = Random(5)
        val rec = FloatArray(48_000 * 3)
        val truth = doubleArrayOf(12_345.37, 52_000.81, 91_234.5, 120_001.12)
        val gains = doubleArrayOf(0.9, 0.08, 0.3, 0.02) // near, far, normal, very quiet
        for (k in truth.indices) room(rec, truth[k], gains[k], rng)
        for (i in rec.indices) {
            // crowd noise plus a loud bass line, which the 1.5-7 kHz chirp does not care about
            rec[i] += (0.01 * rng.nextGaussian() + 0.3 * sin(2 * Math.PI * 82.0 * i / rate)).toFloat()
        }
        for (k in truth.indices) {
            val from = (truth[k] - 4800).toInt()
            val hit = ChirpDetector.find(rec, from, from + 26_400)
            assertNotNull("chirp $k not found", hit)
            val errorMs = (hit!!.position - truth[k]) / rate * 1000
            assertTrue("chirp $k off by $errorMs ms (clarity ${hit.clarity})", abs(errorMs) < 0.1)
        }
    }

    @Test
    fun refusesToGuessWhenThereIsOnlyNoise() {
        val rng = Random(6)
        val rec = FloatArray(48_000) { (0.2 * rng.nextGaussian()).toFloat() }
        assertNull(ChirpDetector.find(rec, 0, 40_000))
    }

    @Test
    fun copesWithAClippedMicrophone() {
        val rec = FloatArray(48_000)
        place(rec, 10_000.25, 4.0) // far too loud: clipped hard below
        for (i in rec.indices) rec[i] = rec[i].coerceIn(-0.7f, 0.7f)
        val hit = ChirpDetector.find(rec, 0, 30_000)!!
        assertEquals(10_000.25, hit.position, 2.5) // within 0.05 ms
    }
}

/** The chirp must be identical on Android and iOS: ios/Tests/HearbyCoreTests checks the same values. */
class ChirpVectorTest {
    @Test
    fun matchesTheSharedReferenceSamples() {
        val expected = mapOf(
            50 to -0.20209772733274278, 100 to 0.23147882850588578, 500 to 0.2541215558681973,
            1500 to 0.04289865617222823, 1900 to 0.026364333928787693,
        )
        for ((i, v) in expected) assertEquals("sample $i", v, Chirp.wave[i].toDouble(), 1e-6)
        assertEquals(1920, Chirp.SAMPLES)
        assertEquals(0f, Chirp.wave[0], 0f)
    }
}

class ToneSchedulerTest {
    @Test
    fun mixesTheChirpAtItsExactSampleAcrossFrames() {
        val tones = ToneScheduler()
        val start = 1_000_000L + 5_000 // 5 ms into the frame that starts at 1 s
        tones.schedule(start)
        tones.schedule(start) // duplicates are ignored
        val out = FloatArray(4 * 960 * 2)
        for (f in 0 until 4) {
            val frame = FloatArray(960 * 2)
            tones.mixInto(frame, 1_000_000L + f * 20_000L, 960, 2)
            frame.copyInto(out, f * 960 * 2)
        }
        val first = 240 // 5 ms at 48 kHz
        for (i in 0 until first) assertEquals(0f, out[2 * i], 0f)
        for (i in 0 until Chirp.SAMPLES) {
            assertEquals(Chirp.wave[i], out[2 * (first + i)], 0f)
            assertEquals(Chirp.wave[i], out[2 * (first + i) + 1], 0f)
        }
        for (i in first + Chirp.SAMPLES until 4 * 960) assertEquals(0f, out[2 * i], 0f)
        assertTrue(!tones.busy)
    }
}

class SoundCheckPlanTest {
    @Test
    fun referenceBracketsEveryoneAndCancelsDrift() {
        val slots = SoundCheckPlan.round(1, listOf(2, 3), 10_000_000)
        assertEquals(listOf(1L, 2L, 3L, 1L), slots.map { it.clientId })
        assertTrue(slots.first().isReference && slots.last().isReference)
        // The reference error drifts linearly from 1.0 ms to 1.3 ms over the round; phone 2 is really
        // 4 ms later than the reference and phone 3 is 0.5 ms earlier.
        val errs = doubleArrayOf(1000.0, 1100.0 + 4000, 1200.0 - 500, 1300.0)
        val m = slots.mapIndexed { i, s -> SoundCheckPlan.Measurement(s, errs[i]) }
        val c = SoundCheckPlan.corrections(m)!!
        assertEquals(-4000.0, c[2L]!!, 1e-6)
        assertEquals(500.0, c[3L]!!, 1e-6)
    }

    @Test
    fun reportsWhoWasNotHeard() {
        val slots = SoundCheckPlan.round(1, listOf(2, 3), 0)
        val m = listOf(
            SoundCheckPlan.Measurement(slots[0], 0.0), SoundCheckPlan.Measurement(slots[1], null),
            SoundCheckPlan.Measurement(slots[2], 300.0), SoundCheckPlan.Measurement(slots[3], null),
        )
        val c = SoundCheckPlan.corrections(m)!!
        assertNull(c[2L])
        assertEquals(-300.0, c[3L]!!, 1e-6)
        assertNull(SoundCheckPlan.corrections(m.map { SoundCheckPlan.Measurement(it.slot, if (it.slot.isReference) null else 1.0) }))
    }
}

/**
 * The whole sound check, end to end, on simulated phones: real playout engines, real clock sync,
 * real chirp mixing, real detector, with speaker latencies that the phones themselves get wrong.
 */
class SoundCheckSimTest {
    private val rate = Proto.SAMPLE_RATE

    private fun seconds(s: Double) = (s * 1e9).toLong()

    @Test
    fun linesUpPhonesWhoseSpeakersAreSecretlyLate() {
        // The "Bluetooth" sync cushion: a speaker with 150 ms of unreported latency needs the room.
        val delayMs = 700
        fun phone(seed: Long, hiddenUs: Long, sinkUs: Long, drift: Double, clockOffset: Long) =
            PlayoutEngineSimTest.Sim(
                PlayoutEngineSimTest.Params(
                    seed = seed, playoutDelayMs = delayMs, silent = true, hiddenLatencyUs = hiddenUs,
                    sinkLatencyUs = sinkUs, sinkDriftPpm = drift, clientClockDriftPpm = drift / 3, clientClockOffsetUs = clockOffset,
                ),
            ).also { it.startClockSync() }

        val dj = phone(31, hiddenUs = 1_500, sinkUs = 40_000, drift = -30.0, clockOffset = 0)
        val pixel = phone(32, hiddenUs = 8_000, sinkUs = 55_000, drift = 45.0, clockOffset = -5_000_000_000L) // HAL under-reports by 8 ms
        val speaker = phone(33, hiddenUs = 150_000, sinkUs = 70_000, drift = 80.0, clockOffset = 9_000_000_000L) // Bluetooth, unreported
        val phones = listOf(dj, pixel, speaker)
        val ids = listOf(1L, 2L, 3L)
        val duration = 30.0
        for (p in phones) p.heard = FloatArray((duration * rate).toInt())

        // The DJ's microphone has its own input latency; it must cancel out.
        val micLatencyUs = 17_000L
        val rng = Random(9)

        fun runAll(untilS: Double) = phones.forEach { it.run(seconds(untilS)) }

        /** One round: schedule chirps, let them play, listen, return the errors in µs per slot. */
        fun round(atS: Double): List<SoundCheckPlan.Measurement> {
            val startMediaUs = PlayoutEngineSimTest.Sim.HOST_EPOCH_US + (atS * 1e6).toLong() + SoundCheckPlan.START_LEAD_US
            val slots = SoundCheckPlan.round(1, listOf(2, 3), startMediaUs)
            // Each phone reports its adjustments (as SYNC_REQ.adjustUs does) before the round.
            val adjust = phones.associate { ids[phones.indexOf(it)] to it.engine.adjustUs() }
            for (s in slots) phones[ids.indexOf(s.clientId)].engine.tones.schedule(s.mediaTimeUs)
            val endS = atS + (slots.last().mediaTimeUs - startMediaUs + SoundCheckPlan.START_LEAD_US) / 1e6 + delayMs / 1e3 + 0.8
            runAll(endS)

            // What the DJ phone's microphone recorded: everyone mixed, a bit of noise, mic latency.
            val mic = FloatArray((duration * rate).toInt())
            val shift = (micLatencyUs * rate / 1_000_000).toInt()
            for (p in phones) {
                val h = p.heard!!
                for (i in h.indices) if (i + shift < mic.size) mic[i + shift] += h[i] * if (p === dj) 1.0f else 0.35f
            }
            for (i in mic.indices) mic[i] += (0.003 * rng.nextGaussian()).toFloat()

            fun idx(hostUs: Long) = ((hostUs - PlayoutEngineSimTest.Sim.HOST_EPOCH_US) * rate / 1_000_000).toInt()
            return slots.map { slot ->
                // The host clock here is "true time" plus a constant, which cancels like the mic latency does.
                val nominalUs = slot.mediaTimeUs + delayMs * 1000L
                val lookAt = idx(nominalUs + adjust.getValue(slot.clientId)) // where this phone should be
                val hit = ChirpDetector.find(
                    mic,
                    lookAt - (SoundCheckPlan.WINDOW_BEFORE_US * rate / 1_000_000).toInt(),
                    lookAt + (SoundCheckPlan.WINDOW_AFTER_US * rate / 1_000_000).toInt(),
                )
                SoundCheckPlan.Measurement(slot, hit?.let { (it.position - idx(nominalUs)) * 1e6 / rate })
            }
        }

        runAll(8.0) // join and lock

        val first = SoundCheckPlan.corrections(round(8.0))!!
        // Relative to the DJ (1.5 ms hidden): the Pixel is 6.5 ms late, the speaker 148.5 ms late.
        assertEquals(-6_500.0, first[2L]!!, 1_200.0)
        assertEquals(-148_500.0, first[3L]!!, 1_200.0)
        pixel.engine.calibrationUs += Math.round(first[2L]!!)
        speaker.engine.calibrationUs += Math.round(first[3L]!!)

        runAll(13.0) // re-align with the corrections
        val second = SoundCheckPlan.corrections(round(13.0))!!
        assertTrue("pixel still off by ${second[2L]} us", abs(second[2L]!!) < 1_000)
        assertTrue("speaker still off by ${second[3L]} us", abs(second[3L]!!) < 1_000)
        pixel.engine.calibrationUs += Math.round(second[2L]!!)
        speaker.engine.calibrationUs += Math.round(second[3L]!!)

        // And it stays lined up: measure once more without correcting.
        runAll(19.0)
        val after = SoundCheckPlan.corrections(round(19.0))!!
        assertTrue("pixel off by ${after[2L]} us after the sound check", abs(after[2L]!!) < 1_000)
        assertTrue("speaker off by ${after[3L]} us after the sound check", abs(after[3L]!!) < 1_000)
        for (p in phones) {
            assertEquals(PlayoutEngine.State.PLAYING, p.engine.state)
            assertEquals("no phone had to fall back", 0, p.engine.lateBiasMs)
        }
        println("sound check: before ${first.mapValues { "%.2f ms".format(-it.value!! / 1000) }}, " +
            "after ${after.mapValues { "%.2f ms".format(-it.value!! / 1000) }}")
    }
}
