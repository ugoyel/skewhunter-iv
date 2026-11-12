package io.github.ugoyel.hearby.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * End-to-end simulation of one listener: a host producing timestamped frames on its own clocks, a
 * lossy/jittery network, an NTP exchange, and an audio output with its own latency and DAC drift.
 *
 * The fake decoder writes the media position into the samples themselves, so the fake output can
 * check, for every sample it "plays", how far the moment it is heard is from the moment it should
 * be heard. That is the only number that matters for multi-device sync.
 */
class PlayoutEngineSimTest {

    internal class Params(
        val seed: Long,
        val playoutDelayMs: Int = 350,
        val clientClockOffsetUs: Long = -3_200_000_000L,
        val clientClockDriftPpm: Double = 0.0,
        val sinkDriftPpm: Double = 0.0,
        val sinkLatencyUs: Long = 45_000,
        val hostAudioDriftPpm: Double = 0.0,
        val loss: Double = 0.0,
        val netBaseUs: Int = 1_500,
        val netJitterUs: Int = 3_000,
        val spikeProbability: Double = 0.0,
        val spikeUs: Int = 0,
        val pauseAtSeq: Long = Long.MAX_VALUE,
        val pauseNs: Long = 0,
        /** Output latency the phone does NOT report (speaker DSP, an unreported Bluetooth link). */
        val hiddenLatencyUs: Long = 0,
        /** Decode every packet to silence (sound-check tests mix chirps on top). */
        val silent: Boolean = false,
    )

    internal class Sim(val p: Params) : AudioSink, OpusFrameDecoder {
        val rng = Random(p.seed)
        val jitter = JitterBuffer()
        val clock = ClockSync()
        val engine = PlayoutEngine(this, this, jitter, clock).also { it.playoutDelayMs = p.playoutDelayMs }
        var nowNs = 0L

        // ---- event queue ----------------------------------------------------------------------
        private class Event(val timeNs: Long, val order: Long, val action: () -> Unit)

        private val events = PriorityQueue<Event>(compareBy<Event>({ it.timeNs }, { it.order }))
        private var order = 0L

        fun at(timeNs: Long, action: () -> Unit) {
            events.add(Event(timeNs, order++, action))
        }

        fun advance(toNs: Long) {
            generateHostFrames(toNs)
            while (true) {
                val e = events.peek() ?: break
                if (e.timeNs > toNs) break
                events.poll()
                advanceSink(e.timeNs)
                nowNs = e.timeNs
                e.action()
            }
            advanceSink(toNs)
            nowNs = toNs
        }

        fun run(untilNs: Long) {
            while (nowNs < untilNs) engine.pump()
        }

        // ---- clocks ---------------------------------------------------------------------------
        fun hostClockUs(trueNs: Long) = HOST_EPOCH_US + trueNs / 1000

        fun clientClockNs(trueNs: Long): Long =
            (trueNs * (1 + p.clientClockDriftPpm * 1e-6)).roundToLong() + p.clientClockOffsetUs * 1000

        private fun netDelayNs(): Long {
            var us = p.netBaseUs + rng.nextInt(p.netJitterUs + 1)
            if (p.spikeProbability > 0 && rng.nextDouble() < p.spikeProbability) us += rng.nextInt(p.spikeUs + 1)
            return us * 1000L
        }

        // ---- host -----------------------------------------------------------------------------
        private val frameNs = 20_000_000.0 * (1 + p.hostAudioDriftPpm * 1e-6)
        private var nextHostSeq = 0L

        fun captureStartNs(seq: Long): Long =
            HOST_START_NS + (seq * frameNs).roundToLong() + if (seq >= p.pauseAtSeq) p.pauseNs else 0

        fun hostTimestampUs(seq: Long) = hostClockUs(captureStartNs(seq))

        private fun generateHostFrames(toNs: Long) {
            while (true) {
                val seq = nextHostSeq
                val sendNs = captureStartNs(seq) + 23_000_000 // whole frame captured, +3 ms to encode
                if (sendNs > toNs) return
                nextHostSeq++
                if (rng.nextDouble() < p.loss) continue
                at(sendNs + netDelayNs()) {
                    val payload = ByteArray(8)
                    Wire.putU64(payload, 0, seq)
                    jitter.put(seq and 0xFFFFFFFFL, hostTimestampUs(seq), payload, 0, 8)
                }
            }
        }

        fun startClockSync() = scheduleSync(50_000_000, 0)

        private fun scheduleSync(atNs: Long, index: Int) {
            at(atNs) {
                val t0 = clientClockNs(nowNs) / 1000
                val hostRxNs = nowNs + netDelayNs()
                val t1 = hostClockUs(hostRxNs)
                val t2 = t1 + 120
                at(hostRxNs + 120_000 + netDelayNs()) { clock.addSample(t0, t1, t2, clientClockNs(nowNs) / 1000) }
                scheduleSync(nowNs + if (index < 8) 40_000_000L else 1_000_000_000L, index + 1)
            }
        }

        // ---- decoder: encodes "where in the stream am I" into the samples -----------------------
        override fun decode(packet: ByteArray, length: Int, out: FloatArray): Boolean {
            if (p.silent) {
                java.util.Arrays.fill(out, 0f)
                return true
            }
            val seq = Wire.getU64(packet, 0)
            val base = (seq % BLOCK_FRAMES) * 960
            for (i in 0 until 960) {
                val pos = (base + i).toFloat()
                out[2 * i] = pos + 1000f
                out[2 * i + 1] = SUM - 1000f - pos
            }
            return true
        }

        // ---- audio output: fixed latency, its own sample clock ----------------------------------
        private val ring = FloatArray(RING * 2)
        private var consumed = 0L
        private var written = 0L
        private val periodNs = 1e9 / (48_000.0 * (1 + p.sinkDriftPpm * 1e-6))
        private var nextConsumeNs = 0.0
        private var slots = 0L
        private var tsPosition = -1L
        private var tsTimeNs = 0L
        var underruns = 0L

        /** When set, every sample this phone plays is added here at its true time (48 kHz). */
        var heard: FloatArray? = null

        private fun advanceSink(toNs: Long) {
            while (nextConsumeNs <= toNs) {
                if (slots++ % 480 == 0L) { // the "mixer" publishes a timestamp every 10 ms, with noise
                    tsPosition = consumed
                    tsTimeNs = clientClockNs(nextConsumeNs.toLong()) + p.sinkLatencyUs * 1000 + (rng.nextInt(401) - 200) * 1000L
                }
                if (consumed < written) {
                    val i = (consumed % RING).toInt() * 2
                    val heardNs = nextConsumeNs.toLong() + (p.sinkLatencyUs + p.hiddenLatencyUs) * 1000
                    observe(ring[i], ring[i + 1], heardNs)
                    heard?.let { track -> // what a microphone in the room picks up from this phone
                        val idx = (heardNs * Proto.SAMPLE_RATE / 1_000_000_000L).toInt()
                        if (idx in track.indices) track[idx] += ring[i]
                    }
                    consumed++
                } else if (written > 0) {
                    underruns++
                }
                nextConsumeNs += periodNs
            }
        }

        override fun write(interleaved: FloatArray, frames: Int) {
            var done = 0
            while (done < frames) {
                val room = SINK_BUFFER - (written - consumed).toInt()
                if (room <= 0) {
                    advance(nowNs + 1_000_000) // blocking write: time passes
                    continue
                }
                val n = minOf(room, frames - done)
                for (k in 0 until n) {
                    val i = ((written + k) % RING).toInt() * 2
                    ring[i] = interleaved[2 * (done + k)]
                    ring[i + 1] = interleaved[2 * (done + k) + 1]
                }
                written += n
                done += n
            }
        }

        override fun getTimestamp(out: SinkTimestamp): Boolean {
            if (tsPosition < 0 || nowNs < 150_000_000) return false
            out.framePosition = tsPosition
            out.timeNs = tsTimeNs
            return true
        }

        // ---- measurement ----------------------------------------------------------------------
        var sampleTimes = LongArray(1 shl 16)
        var sampleErrMs = FloatArray(1 shl 16)
        var samples = 0
        private var decimate = 0
        private var previousPos = Double.NaN
        private var cleanRun = 0

        /** Called for every frame at the instant it becomes audible. */
        private fun observe(l: Float, r: Float, heardNs: Long) {
            val pos = (l - 1000f).toDouble()
            // The two channels sum to a constant only for untouched audio (not silence, fades or
            // concealment), and the position must have advanced by about one frame per frame for a
            // while, which excludes the filter ringing around every wrap of the position ramp.
            val clean = abs(l + r - SUM) < 0.05f && abs(pos - previousPos - 1.0) < 0.02
            previousPos = pos
            cleanRun = if (clean) cleanRun + 1 else 0
            if (cleanRun < 48) return
            if (decimate++ % 16 != 0) return

            // Where the stream *should* be right now (zero user offset), in frames.
            var mediaTrueNs = heardNs - p.playoutDelayMs * 1_000_000L
            if (p.pauseNs > 0) {
                val resume = captureStartNs(p.pauseAtSeq)
                if (mediaTrueNs >= resume) mediaTrueNs -= p.pauseNs
                else if (mediaTrueNs >= resume - p.pauseNs) return // inside the host's pause
            }
            val expected = (mediaTrueNs - HOST_START_NS) / frameNs
            val within = pos / 960.0
            val block = Math.round((expected - within) / BLOCK_FRAMES)
            val actual = block * BLOCK_FRAMES + within
            val lateMs = (expected - actual) * frameNs / 1e6

            if (samples == sampleTimes.size) {
                sampleTimes = sampleTimes.copyOf(samples * 2)
                sampleErrMs = sampleErrMs.copyOf(samples * 2)
            }
            sampleTimes[samples] = heardNs
            sampleErrMs[samples] = lateMs.toFloat()
            samples++
        }

        /** Largest |lateness - expected| among samples heard in [fromNs, toNs). */
        fun worstDeviationMs(fromNs: Long, toNs: Long, expectedMs: Double = 0.0): Double {
            var worst = 0.0
            var n = 0
            for (i in 0 until samples) {
                if (sampleTimes[i] < fromNs || sampleTimes[i] >= toNs) continue
                worst = maxOf(worst, abs(sampleErrMs[i] - expectedMs))
                n++
            }
            assertTrue("no audio was heard between ${fromNs / 1e9} s and ${toNs / 1e9} s", n > 100)
            return worst
        }

        companion object {
            const val HOST_EPOCH_US = 7_000_000_000L
            const val HOST_START_NS = 0L
            const val BLOCK_FRAMES = 64L // position ramp repeats every 1.28 s
            const val SUM = 71_000f
            const val RING = 8192
            const val SINK_BUFFER = 3840 // 80 ms, like a typical AudioTrack
        }
    }

    private fun seconds(s: Double) = (s * 1e9).toLong()

    private fun start(p: Params) = Sim(p).also { it.startClockSync() }

    @Test
    fun locksToTheHostTimelineDespiteClockAndDacDrift() {
        val sim = start(Params(seed = 11, clientClockDriftPpm = 35.0, sinkDriftPpm = -80.0, hostAudioDriftPpm = 25.0))
        sim.run(seconds(90.0))
        val worst = sim.worstDeviationMs(seconds(8.0), seconds(90.0))
        assertTrue("worst sync error $worst ms", worst < 1.5)
        assertEquals(PlayoutEngine.State.PLAYING, sim.engine.state)
        assertEquals(0L, sim.engine.concealedFrames)
        assertEquals(0L, sim.engine.realignCount)
        assertEquals(0, sim.engine.lateBiasMs)
        assertEquals(0L, sim.underruns)
        assertTrue("speed ${sim.engine.speedPpm} ppm", abs(sim.engine.speedPpm) < 400)
    }

    @Test
    fun aSecondListenerWithVeryDifferentHardwareLandsOnTheSameTimeline() {
        // e.g. Bluetooth output: far more latency, clocks drifting the other way
        val sim = start(
            Params(
                seed = 12, clientClockOffsetUs = 91_000_000_000L, clientClockDriftPpm = -60.0,
                sinkDriftPpm = 110.0, sinkLatencyUs = 180_000, hostAudioDriftPpm = 25.0,
            ),
        )
        sim.run(seconds(90.0))
        val worst = sim.worstDeviationMs(seconds(8.0), seconds(90.0))
        assertTrue("worst sync error $worst ms", worst < 1.5)
        assertEquals(0, sim.engine.lateBiasMs)
        assertEquals(0L, sim.engine.concealedFrames)
    }

    @Test
    fun holdsSyncThroughPacketLossAndDelaySpikes() {
        val sim = start(
            Params(
                seed = 13, clientClockDriftPpm = 20.0, sinkDriftPpm = 50.0,
                loss = 0.03, spikeProbability = 0.02, spikeUs = 60_000,
            ),
        )
        sim.run(seconds(60.0))
        val worst = sim.worstDeviationMs(seconds(8.0), seconds(60.0))
        assertTrue("worst sync error $worst ms", worst < 2.0)
        val total = sim.engine.playedFrames + sim.engine.concealedFrames
        val concealedShare = sim.engine.concealedFrames.toDouble() / total
        assertTrue("concealed $concealedShare", concealedShare > 0.01 && concealedShare < 0.06)
        assertEquals("loss must not push the device late", 0, sim.engine.lateBiasMs)
        assertEquals(0L, sim.engine.realignCount)
    }

    @Test
    fun fallsBackByExactlyWhatIsMissingWhenOutputLatencyExceedsTheBudget() {
        // 300 ms of output latency cannot fit in a 150 ms playout delay.
        val sim = start(Params(seed = 14, playoutDelayMs = 150, sinkLatencyUs = 300_000, sinkDriftPpm = 30.0))
        sim.run(seconds(40.0))
        val bias = sim.engine.lateBiasMs
        assertTrue("late bias $bias ms", bias in 230..340)
        // It is late by what it reports, and rock steady there.
        val worst = sim.worstDeviationMs(seconds(20.0), seconds(40.0), expectedMs = bias.toDouble())
        assertTrue("deviation from reported bias $worst ms", worst < 1.5)
        assertEquals(PlayoutEngine.State.PLAYING, sim.engine.state)
    }

    @Test
    fun realignsAfterTheHostPauses() {
        val sim = start(Params(seed = 15, sinkDriftPpm = -40.0, pauseAtSeq = 1000, pauseNs = seconds(2.5)))
        sim.run(seconds(50.0))
        assertTrue(sim.worstDeviationMs(seconds(8.0), seconds(19.0)) < 1.5)
        // The pause ends at 22.5 s of capture time; allow one second to line up again.
        val worst = sim.worstDeviationMs(seconds(24.0), seconds(50.0))
        assertTrue("worst sync error after pause $worst ms", worst < 1.5)
        assertEquals(0, sim.engine.lateBiasMs)
    }

    @Test
    fun theUserOffsetMovesPlayoutByExactlyThatMuch() {
        val sim = start(Params(seed = 16, clientClockDriftPpm = -25.0, sinkDriftPpm = 60.0))
        sim.run(seconds(20.0))
        assertTrue(sim.worstDeviationMs(seconds(8.0), seconds(20.0)) < 1.5)

        sim.engine.userOffsetMs = 120 // play later
        sim.run(seconds(40.0))
        val later = sim.worstDeviationMs(seconds(21.0), seconds(40.0), expectedMs = 120.0)
        assertTrue("offset +120: off by $later ms", later < 1.5)

        sim.engine.userOffsetMs = -80 // play earlier (compensating Bluetooth lag)
        sim.run(seconds(60.0))
        val earlier = sim.worstDeviationMs(seconds(41.0), seconds(60.0), expectedMs = -80.0)
        assertTrue("offset -80: off by $earlier ms", earlier < 1.5)
        assertEquals(0, sim.engine.lateBiasMs)
    }

    @Test
    fun handsBackLateBiasOnceTheNetworkRecovers() {
        // A long burst of very late packets right when playback starts forces a fallback...
        val sim = Sim(Params(seed = 17, sinkDriftPpm = 20.0))
        sim.startClockSync()
        sim.run(seconds(3.0))
        sim.engine.requestRealign(resetLateBias = false)
        // ...simulated here by injecting bias directly through the same path the engine uses.
        val field = PlayoutEngine::class.java.getDeclaredField("lateBiasMs").also { it.isAccessible = true }
        field.setInt(sim.engine, 90)
        sim.run(seconds(30.0))
        assertEquals("bias should have been released", 0, sim.engine.lateBiasMs)
        val worst = sim.worstDeviationMs(seconds(20.0), seconds(30.0))
        assertTrue("worst sync error $worst ms", worst < 1.5)
    }
}
