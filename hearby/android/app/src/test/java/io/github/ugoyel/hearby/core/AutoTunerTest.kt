package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AutoTunerTest {

    /** A phone at the party: how long audio takes to reach it and to come out of its speaker. */
    private class Phone(val id: Long, val netMs: Int, val outputMs: Int, val spikeMs: Int, val bluetooth: Boolean = false)

    /**
     * Runs a party for [minutes]: every second each phone reports, the way the real playout engine
     * does (when the cushion is too small for it, it plays a little late and says so). Changes are
     * applied only at quiet moments: a gap between songs every [songS] seconds.
     */
    private fun party(
        tuner: AutoTuner,
        phones: List<Phone>,
        minutes: Int,
        songS: Int = 180,
        seed: Long = 1,
        onSecond: (Int, Int) -> Unit = { _, _ -> }, // (second, phones playing late)
    ) {
        val rng = Random(seed)
        for (s in 0 until minutes * 60) {
            val reports = phones.map { p ->
                val spike = if (rng.nextInt(20) == 0) rng.nextInt(p.spikeMs + 1) else rng.nextInt(p.spikeMs / 4 + 1)
                val margin = tuner.delayMs - p.netMs - p.outputMs - spike
                if (margin < 20) {
                    AutoTuner.Guest(p.id, 20, Proto.STATUS_LATE, bluetooth = p.bluetooth)
                } else {
                    AutoTuner.Guest(p.id, margin, Proto.STATUS_SYNCED, bluetooth = p.bluetooth)
                }
            }
            onSecond(s, reports.count { it.status == Proto.STATUS_LATE })
            tuner.observe(s * 1000L, reports)
            if (tuner.pending && s % songS == songS - 1) tuner.applied(s * 1000L)
        }
    }

    @Test
    fun aBluetoothSpeakerOnShakyWifiGetsTheCushionItNeeds() {
        val tuner = AutoTuner(startDelayMs = 350)
        val phones = listOf(
            Phone(1, netMs = 8, outputMs = 30, spikeMs = 30),
            Phone(2, netMs = 12, outputMs = 25, spikeMs = 40),
            Phone(3, netMs = 10, outputMs = 240, spikeMs = 90, bluetooth = true),
        )
        var lateInLastFive = 0
        party(tuner, phones, minutes = 20, songS = 60) { s, late -> if (s >= 15 * 60) lateInLastFive += late }
        println("bluetooth on shaky wifi: cushion settled at ${tuner.delayMs} ms")
        assertEquals("phones still late after settling (cushion ${tuner.delayMs} ms)", 0, lateInLastFive)
        assertTrue("cushion ${tuner.delayMs} ms", tuner.delayMs in 400..650)
    }

    @Test
    fun anEasyCrowdGetsASnappyCushion() {
        val tuner = AutoTuner(startDelayMs = 700)
        val phones = listOf(Phone(1, 5, 25, 20), Phone(2, 8, 30, 25), Phone(3, 6, 35, 20))
        var late = 0
        party(tuner, phones, minutes = 15, songS = 60) { _, n -> late += n }
        println("easy crowd: cushion went from 700 to ${tuner.delayMs} ms")
        assertEquals(0, late)
        assertTrue("cushion ${tuner.delayMs} ms", tuner.delayMs <= 300)
        assertTrue(tuner.delayMs >= AutoTuner.MIN_DELAY_MS)
    }

    @Test
    fun itSettlesInsteadOfSeesawing() {
        val tuner = AutoTuner(startDelayMs = 350)
        val phones = listOf(Phone(1, 8, 30, 60), Phone(2, 10, 250, 120, bluetooth = true))
        val changes = ArrayList<Int>()
        var last = tuner.delayMs
        party(tuner, phones, minutes = 30, songS = 60) { _, _ ->
            if (tuner.delayMs != last) {
                changes += tuner.delayMs
                last = tuner.delayMs
            }
        }
        println("30 minutes: cushion changes $changes")
        assertTrue("cushion changed ${changes.size} times: $changes", changes.size in 1..4)
        assertTrue("it went back down: $changes", changes.zipWithNext().all { (a, b) -> b > a } || changes.size <= 2)
    }

    @Test
    fun changesWaitForAQuietMoment() {
        val tuner = AutoTuner(startDelayMs = 300)
        val late = listOf(AutoTuner.Guest(1, 20, Proto.STATUS_LATE))
        tuner.observe(0, late)
        assertTrue(tuner.pending)
        assertEquals(300, tuner.delayMs) // nothing changes by itself ...
        assertEquals(400, tuner.wantDelayMs)
        repeat(30) { tuner.observe(1000L * (it + 1), late) }
        assertEquals(400, tuner.wantDelayMs) // ... and it doesn't pile on while waiting
        tuner.applied(31_000)
        assertEquals(400, tuner.delayMs)
        assertFalse(tuner.pending)
    }

    @Test
    fun browsersWithoutAudioDontPushTheCushion() {
        val tuner = AutoTuner(startDelayMs = 350)
        repeat(120) { tuner.observe(it * 1000L, listOf(AutoTuner.Guest(1, -1, Proto.STATUS_SYNCED, browser = true, opus = false))) }
        assertFalse(tuner.pending)
    }

    @Test
    fun qualityFollowsTheConnection() {
        val tuner = AutoTuner(startBitrate = 128_000)
        fun report(t: Long, loss: Int) = tuner.observe(t, listOf(AutoTuner.Guest(1, 150, Proto.STATUS_SYNCED, lossPercent = loss)))
        report(0, 6)
        assertEquals(96_000, tuner.wantBitrate)
        report(5_000, 6)
        assertEquals(96_000, tuner.wantBitrate) // one step at a time
        report(20_000, 6)
        assertEquals(64_000, tuner.wantBitrate)
        report(30_000, 0)
        report(100_000, 0)
        assertEquals(64_000, tuner.wantBitrate) // two clean minutes first
        report(150_000, 0)
        assertEquals(96_000, tuner.wantBitrate)
        report(271_000, 0)
        assertEquals(128_000, tuner.wantBitrate)
    }

    @Test
    fun bigCrowdsAreCapped() {
        val tuner = AutoTuner(startBitrate = 128_000)
        val crowd = (1L..15L).map { AutoTuner.Guest(it, 150, Proto.STATUS_SYNCED) }
        tuner.observe(0, crowd)
        assertEquals(96_000, tuner.wantBitrate)
        tuner.observe(1_000, crowd + (16L..25L).map { AutoTuner.Guest(it, 150, Proto.STATUS_SYNCED) })
        assertEquals(64_000, tuner.wantBitrate)
        // Browsers get their own stream, so they don't count.
        val tuner2 = AutoTuner(startBitrate = 128_000)
        tuner2.observe(0, (1L..15L).map { AutoTuner.Guest(it, 150, Proto.STATUS_SYNCED, browser = true, opus = false) })
        assertEquals(128_000, tuner2.wantBitrate)
    }

    @Test
    fun theSoundCheckIsOfferedOnlyWhenItHelps() {
        val tuner = AutoTuner()
        tuner.observe(0, listOf(AutoTuner.Guest(1, 150, Proto.STATUS_SYNCED)))
        assertFalse(tuner.suggestSoundCheck) // phone speakers: a few ms apart, nothing to fix
        tuner.observe(1_000, listOf(AutoTuner.Guest(1, 150, Proto.STATUS_SYNCED, bluetooth = true)))
        assertTrue(tuner.suggestSoundCheck)
        tuner.observe(2_000, listOf(AutoTuner.Guest(1, 150, Proto.STATUS_SYNCED, bluetooth = true, soundChecked = true)))
        assertFalse(tuner.suggestSoundCheck)
        tuner.observe(3_000, listOf(AutoTuner.Guest(2, 150, Proto.STATUS_SYNCED, browser = true, opus = false)))
        assertTrue(tuner.suggestSoundCheck)
    }

    @Test
    fun syncRequestFlagsCarryBluetoothAndLoss() {
        val flags = Wire.syncFlags(bluetooth = true, lossPercent = 3.4f)
        assertEquals(0x07, flags)
        val b = Wire.encodeSyncReq(1, 2, 3, 4, Proto.STATUS_SYNCED, 0, flags)
        val m = Wire.decodeSyncReq(b, b.size)!!
        assertTrue(m.bluetooth)
        assertEquals(3, m.lossPercent)
        assertEquals(100, Wire.decodeSyncReq(Wire.encodeSyncReq(1, 2, 3, 4, 2, 0, Wire.syncFlags(false, 250f)), 28)!!.lossPercent)
    }
}
