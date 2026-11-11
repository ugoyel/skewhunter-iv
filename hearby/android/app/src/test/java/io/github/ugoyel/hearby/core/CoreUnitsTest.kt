package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class ClockSyncTest {

    @Test
    fun recoversOffsetDespiteAsymmetricJitter() {
        val rng = Random(1)
        val trueOffset = 987_654_321L // host - client
        val sync = ClockSync()
        var clientNow = 5_000_000L
        repeat(40) {
            val up = 800 + rng.nextInt(6000)   // us, client -> host
            val down = 800 + rng.nextInt(6000) // us, host -> client
            val t0 = clientNow
            val t1 = t0 + up + trueOffset
            val t2 = t1 + 150
            val t3 = t0 + up + 150 + down
            assertTrue(sync.addSample(t0, t1, t2, t3))
            clientNow += 1_000_000
        }
        assertTrue(sync.synced)
        // Error is bounded by half the asymmetry of the best exchange in the window.
        assertTrue("offset error ${sync.offsetUs - trueOffset}", abs(sync.offsetUs - trueOffset) < 1500)
        assertEquals(sync.localToHostUs(1000), 1000 + sync.offsetUs)
        assertEquals(sync.hostToLocalUs(sync.localToHostUs(42)), 42)
    }

    @Test
    fun stepsImmediatelyWhenClocksJump() {
        val sync = ClockSync()
        repeat(5) { sync.addSample(it * 1000L, it * 1000L + 500 + 1_000, it * 1000L + 500 + 1_000, it * 1000L + 1000) }
        assertTrue(abs(sync.offsetUs - 1_000) < 50)
        // Host clock jumps by 10 s: every new exchange disagrees wildly, and wins as soon as it has the best RTT.
        repeat(14) {
            val t0 = 100_000L + it * 1000
            sync.addSample(t0, t0 + 400 + 10_001_000, t0 + 400 + 10_001_000, t0 + 800)
        }
        assertTrue("offset ${sync.offsetUs}", abs(sync.offsetUs - 10_001_000) < 50)
    }

    @Test
    fun ignoresImpossibleSamples() {
        val sync = ClockSync()
        assertFalse(sync.addSample(1000, 5000, 9000, 2000)) // host "processing" longer than the round trip
        assertFalse(sync.addSample(0, 0, 0, 5_000_000))     // absurd round trip
        assertEquals(0, sync.sampleCount)
        assertFalse(sync.synced)
    }
}

class TimestampSmootherTest {

    @Test
    fun followsDriftAndRemovesJitter() {
        val rng = Random(2)
        val smoother = TimestampSmoother()
        val trueFrameUs = 20_000.0 * (1 + 80e-6) // capture clock runs 80 ppm slow against the system clock
        var worst = 0.0
        var previous = 0L
        for (n in 0 until 6000) {
            val ideal = 1_000_000.0 + n * trueFrameUs
            val out = smoother.next((ideal + rng.nextInt(9000)).toLong()) // reads return up to 9 ms late
            if (n > 500) {
                worst = maxOf(worst, abs(out - (ideal + 4500))) // settles on the mean lateness
                assertTrue("frame spacing ${out - previous}", abs((out - previous) - 20_000) < 250)
            }
            previous = out
        }
        assertTrue("worst deviation $worst us", worst < 1200)
    }

    @Test
    fun snapsOnDiscontinuity() {
        val smoother = TimestampSmoother()
        var t = 0L
        repeat(100) { smoother.next(t); t += 20_000 }
        t += 3_000_000 // capture paused for 3 s
        assertEquals(t, smoother.next(t))
    }
}

class JitterBufferTest {
    private val payload = ByteArray(40) { it.toByte() }

    private fun put(jb: JitterBuffer, seq: Long) = jb.put(seq, seq * 20_000, payload, 0, payload.size)

    @Test
    fun reordersAndReportsLoss() {
        val jb = JitterBuffer()
        val out = JitterBuffer.Packet()
        for (s in longArrayOf(10, 12, 11, 14)) assertTrue(put(jb, s)) // 13 lost, 11/12 swapped
        assertFalse(put(jb, 12))                                       // duplicate
        assertEquals(4, jb.size)
        assertEquals(14L, jb.newestSeq)
        assertEquals(280_000L, jb.newestTimestampUs())
        assertEquals(11L, jb.firstAtOrAfter(205_000))
        assertEquals(14L, jb.firstAtOrAfter(245_000)) // skips the hole at 13
        assertEquals(-1L, jb.firstAtOrAfter(300_000))

        jb.dropBelow(11)
        assertFalse(put(jb, 10))                      // now too late
        assertEquals(1L, jb.lateDrops)
        assertTrue(jb.take(11, out)); assertEquals(220_000L, out.timestampUs); assertEquals(40, out.length)
        assertTrue(jb.take(12, out))
        assertFalse(jb.take(13, out))
        assertEquals(-1L, jb.timestampOf(13))
        assertTrue(jb.take(14, out))
        assertEquals(0, jb.size)
        assertEquals(-1L, jb.newestTimestampUs())
    }

    @Test
    fun followsSequenceWrapAround() {
        val jb = JitterBuffer()
        val out = JitterBuffer.Packet()
        assertTrue(put(jb, 0xFFFFFFFEL))
        assertTrue(put(jb, 0xFFFFFFFFL))
        assertTrue(put(jb, 0x00000000L)) // wrapped
        assertTrue(put(jb, 0x00000001L))
        assertEquals(0x100000001L, jb.newestSeq)
        assertTrue(jb.take(0xFFFFFFFEL, out))
        assertTrue(jb.take(0xFFFFFFFFL, out))
        assertTrue(jb.take(0x100000000L, out))
        assertTrue(jb.take(0x100000001L, out))
    }

    @Test
    fun oldFramesExpireWhenTheWindowMovesOn() {
        val jb = JitterBuffer(capacity = 16)
        val out = JitterBuffer.Packet()
        for (s in 0L until 16) put(jb, s)
        assertEquals(16, jb.size)
        for (s in 16L until 24) put(jb, s) // overwrites 0..7
        assertEquals(16, jb.size)
        assertFalse(jb.take(3, out))
        assertTrue(jb.take(8, out))
        assertEquals(9L, jb.firstAtOrAfter(0)) // 8 was just taken; 9 is now the oldest
    }

    @Test
    fun restartsOnAHugeJump() {
        val jb = JitterBuffer()
        for (s in 1000L until 1010) put(jb, s)
        assertTrue(put(jb, 5)) // host restarted and counts from the beginning again
        assertEquals(1, jb.size)
        assertEquals(5L, jb.newestSeq)
        assertTrue(put(jb, 6))
        assertEquals(2, jb.size)
    }

    @Test
    fun rejectsOversizedPayloads() {
        val jb = JitterBuffer()
        assertFalse(jb.put(1, 0, ByteArray(2000), 0, 2000))
        assertFalse(jb.put(1, 0, ByteArray(10), 0, 0))
    }
}

class ResamplerTest {

    /** Signal-to-error ratio (dB) of a resampled sine against the mathematically exact result. */
    private fun sineSnrDb(freqHz: Double, speed: Double): Double {
        val r = Resampler(2, 960)
        val input = FloatArray(960 * 2)
        val out = FloatArray(1024 * 2)
        val w = 2 * PI * freqHz / 48_000
        var inIndex = 0L
        var outPos = 0.0 // input-domain position of the next output frame
        var signal = 0.0
        var noise = 0.0
        for (block in 0 until 60) {
            for (i in 0 until 960) {
                val v = (0.5 * sin(w * (inIndex + i))).toFloat()
                input[2 * i] = v
                input[2 * i + 1] = -v
            }
            inIndex += 960
            val n = r.process(input, 960, speed, out)
            for (j in 0 until n) {
                val expected = 0.5 * sin(w * (outPos - Resampler.DELAY_FRAMES))
                if (block >= 2) {
                    signal += expected * expected
                    noise += (out[2 * j] - expected) * (out[2 * j] - expected)
                    assertEquals(-out[2 * j], out[2 * j + 1], 1e-6f) // channels stay independent
                }
                outPos += speed
            }
        }
        return 10 * log10(signal / noise)
    }

    @Test
    fun isTransparentAtUnitySpeed() {
        val r = Resampler(2, 960)
        val rng = Random(3)
        val input = FloatArray(960 * 2) { rng.nextFloat() - 0.5f }
        val out = FloatArray(1024 * 2)
        val first = input.copyOf()
        assertEquals(960, r.process(input, 960, 1.0, out))
        val second = FloatArray(960 * 2) { rng.nextFloat() - 0.5f }
        val out2 = FloatArray(1024 * 2)
        assertEquals(960, r.process(second, 960, 1.0, out2))
        // Output is the input delayed by exactly DELAY_FRAMES, bit for bit.
        val d = Resampler.DELAY_FRAMES
        for (i in d until 960) assertEquals(first[2 * (i - d)], out[2 * i], 0f)
        for (i in 0 until d) assertEquals(first[2 * (960 - d + i) + 1], out2[2 * i + 1], 0f)
    }

    @Test
    fun producesTheRightNumberOfFrames() {
        val r = Resampler(2, 960)
        val input = FloatArray(960 * 2)
        val out = FloatArray(1024 * 2)
        var total = 0L
        repeat(500) { total += r.process(input, 960, 1.004, out) }
        assertEquals(500 * 960 / 1.004, total.toDouble(), 2.0)
        r.reset()
        total = 0
        repeat(500) { total += r.process(input, 960, 0.996, out) }
        assertEquals(500 * 960 / 0.996, total.toDouble(), 2.0)
    }

    @Test
    fun staysCleanAcrossTheAudioBand() {
        for (speed in doubleArrayOf(1.0005, 0.9995, 1.005, 0.995)) {
            val low = sineSnrDb(1_000.0, speed)
            val mid = sineSnrDb(8_000.0, speed)
            val high = sineSnrDb(15_000.0, speed)
            assertTrue("1 kHz @ $speed: $low dB", low > 70)
            assertTrue("8 kHz @ $speed: $mid dB", mid > 60)
            assertTrue("15 kHz @ $speed: $high dB", high > 40)
        }
    }
}
