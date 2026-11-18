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
import kotlin.math.sqrt

class KaraokeTest {

    // ---- the mix and the dip -----------------------------------------------------------------

    @Test
    fun guestsLearnTheDjIsOnTheMic() {
        val b = WireParty.encodeNowPlaying(1, true, "Song", "Band", micOn = true)
        assertEquals(0x03, b[8].toInt())
        assertTrue(WireParty.decodeNowPlaying(b, b.size)!!.micOn)
        val off = WireParty.encodeNowPlaying(1, true, "Song", "Band")
        assertFalse(WireParty.decodeNowPlaying(off, off.size)!!.micOn)
    }

    @Test
    fun theMusicDipsTo30PercentSmoothlyAndComesBack() {
        assertEquals(0.30f, Karaoke.DUCK_GAIN)
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

    // ---- the tape: the voice as it was captured ------------------------------------------------

    @Test
    fun theVoiceFlowsSeamlesslyFrameAfterFrame() {
        val tape = VoiceTape()
        tape.anchor(0, 1_000_000)
        val src = FloatArray(48_000) { it.toFloat() } // each sample holds its own index
        tape.write(src, src.size)
        val out = FloatArray(Proto.FRAME_SAMPLES)
        var expected = 4_800f // 100 ms in
        for (k in 0 until 10) {
            assertEquals(Proto.FRAME_SAMPLES, tape.next(1_100_000L + k * Proto.FRAME_US, Proto.FRAME_SAMPLES, out))
            for (v in out) {
                assertEquals(expected, v)
                expected++
            }
        }
    }

    @Test
    fun aMicrophoneClockThatDriftsIsFollowedASampleAtATime() {
        // The microphone runs 200 ppm fast against the clock the sender uses: over a minute the
        // voice must stay within 2 ms of where it belongs, never jumping more than a sample.
        val tape = VoiceTape()
        val src = FloatArray(Proto.FRAME_SAMPLES)
        val out = FloatArray(Proto.FRAME_SAMPLES)
        var written = 0L
        var lastFirst = Float.NaN
        for (k in 0 until 3_000) {
            for (i in src.indices) src[i] = (written + i).toFloat()
            // 960 samples per 20 ms of sender time, but the mic delivers 960.192 per 20 ms.
            tape.write(src, src.size)
            written += src.size
            val sendUs = 1_000_000L + k * Proto.FRAME_US
            // The recorder says: sample `written` was captured at `sendUs * (1 - 200e-6)` (fast clock).
            tape.anchor(written, 1_000_000L + Math.round((written / 48.0) * 1000 / 1.0002))
            tape.next(sendUs - 30_000, Proto.FRAME_SAMPLES, out)
            for (i in 1 until out.size) assertEquals(out[i - 1] + 1, out[i]) // seamless inside a frame
            if (k > 10) assertTrue("frame $k: ${out[0] - lastFirst}", abs(out[0] - lastFirst - Proto.FRAME_SAMPLES) <= 1f)
            lastFirst = out[0]
            if (k > 50) {
                val wantIndex = (sendUs - 30_000 - 1_000_000L) * 1.0002 * 48 / 1000
                assertTrue("frame $k off by ${out[0] - wantIndex} samples", abs(out[0] - wantIndex) <= 96)
            }
        }
    }

    @Test
    fun theVoiceGetsThroughEvenIfTheTimestampsAreWrong() {
        // A phone whose capture timestamps are nonsense: asking for "10 s from now" must still
        // deliver the newest voice, continuously, rather than silence.
        val tape = VoiceTape()
        tape.anchor(0, 0)
        val src = FloatArray(Proto.FRAME_SAMPLES) { 0.5f }
        val out = FloatArray(Proto.FRAME_SAMPLES)
        repeat(20) { k ->
            tape.write(src, src.size)
            assertEquals(Proto.FRAME_SAMPLES, tape.next(10_000_000L + k * Proto.FRAME_US, Proto.FRAME_SAMPLES, out))
            assertTrue(out.all { it == 0.5f })
        }
        // ... and the same when nothing ties the samples to the clock at all.
        val unanchored = VoiceTape()
        unanchored.write(src, src.size)
        assertEquals(Proto.FRAME_SAMPLES, unanchored.next(123, Proto.FRAME_SAMPLES, out))
    }

    // ---- cleaning: noise and the party's echo out, the voice in --------------------------------

    /** A voice-like test signal: a sung vowel (harmonics, vibrato) in 250 ms syllables with gaps. */
    private fun voice(n: Int, amplitude: Double, start: Int = 0): FloatArray {
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = (start + i) / 48_000.0
            val f0 = 180 + 4 * sin(2 * PI * 5 * t)
            phase += 2 * PI * f0 / 48_000
            var s = 0.0
            for (h in 1..15) s += sin(h * phase) / h
            out[i] = (amplitude * syllable(t) * s / 1.8).toFloat()
        }
        return out
    }

    /** 1 during a syllable (250 ms), 0 in the 150 ms gaps, with 10 ms edges. */
    private fun syllable(t: Double): Double {
        val p = t % 0.4
        return when {
            p < 0.01 -> p / 0.01
            p < 0.24 -> 1.0
            p < 0.25 -> (0.25 - p) / 0.01
            else -> 0.0
        }
    }

    private fun inGap(i: Int): Boolean = (i / 48_000.0) % 0.4 in 0.28..0.38

    private fun inSyllable(i: Int): Boolean = (i / 48_000.0) % 0.4 in 0.03..0.22

    private fun rms(x: FloatArray, from: Int, to: Int, pick: (Int) -> Boolean): Double {
        var sum = 0.0
        var count = 0
        for (i in from until to) if (pick(i)) { sum += x[i].toDouble() * x[i]; count++ }
        return sqrt(sum / maxOf(1, count))
    }

    private fun db(x: Double) = 20 * log10(maxOf(x, 1e-12))

    /** Runs [mic] through a cleaner in 10 ms chunks; [talking] per sample index. */
    private fun clean(mic: FloatArray, room: RoomReference?, talking: (Int) -> Boolean): Pair<FloatArray, Int> {
        val cleaner = VoiceCleaner()
        val out = mic.copyOf()
        val chunk = FloatArray(480)
        var i = 0
        while (i + 480 <= out.size) {
            System.arraycopy(out, i, chunk, 0, 480)
            cleaner.process(chunk, 480, capturedUs = T0 + i * 1_000_000L / 48_000, talking = talking(i), room = room)
            System.arraycopy(chunk, 0, out, i, 480)
            i += 480
        }
        return out to cleaner.latency
    }

    @Test
    fun aClearVoicePassesUntouched() {
        val n = 48_000 * 4
        val v = voice(n, 0.1)
        val rng = Random(1)
        val mic = FloatArray(n) { v[it] + (rng.nextGaussian() * 0.0003).toFloat() } // 50 dB above a quiet room
        val (out, latency) = clean(mic, null) { true }
        assertEquals(480, latency)
        // After the first second, syllable by syllable, the voice comes out at its own level, in shape.
        var err = 0.0
        var sig = 0.0
        for (i in 48_000 until n - latency) if (inSyllable(i)) {
            val d = out[i + latency] - v[i]
            err += d * d
            sig += v[i].toDouble() * v[i]
        }
        assertTrue("distortion ${db(sqrt(err / sig))} dB", db(sqrt(err / sig)) < -20)
    }

    @Test
    fun steadyNoiseIsTakenOut() {
        val n = 48_000 * 5
        val rng = Random(2)
        val noise = FloatArray(n) { (rng.nextGaussian() * 0.003).toFloat() } // a fan, about −50 dBFS
        val v = voice(n, 0.1)
        val mic = FloatArray(n) { noise[it] + if (it > 48_000 * 2) v[it] else 0f }
        val (out, latency) = clean(mic, null) { it > 48_000 * 2 }
        val before = rms(mic, 48_000, 2 * 48_000) { true }
        val after = rms(out, 48_000 + latency, 2 * 48_000) { true }
        assertTrue("noise alone: ${db(before)} → ${db(after)} dB", db(after) < db(before) - 18)
        // Between the syllables of someone talking, too.
        val gapIn = rms(mic, 3 * 48_000, n - latency) { inGap(it) }
        val gapOut = rms(out, 3 * 48_000 + latency, n) { inGap(it - latency) }
        assertTrue("noise between syllables: ${db(gapIn)} → ${db(gapOut)} dB", db(gapOut) < db(gapIn) - 15)
        // The voice itself keeps its level.
        val voiceIn = rms(v, 3 * 48_000, n - latency) { inSyllable(it) }
        val voiceOut = rms(out, 3 * 48_000 + latency, n) { inSyllable(it - latency) }
        assertTrue("voice: ${db(voiceIn)} → ${db(voiceOut)} dB", abs(db(voiceOut) - db(voiceIn)) < 1.5)
    }

    /**
     * The party as the DJ phone's microphone hears it: music from every phone, and the DJ's own
     * voice coming back from the guests' phones [echoDelayS] after it was sung, both through a room
     * (a little delay, a reverb tail) at [musicDb] / [voiceDb] below how they were sent.
     */
    private class Party(val n: Int, musicDb: Double, voiceDb: Double, val sung: FloatArray, echoDelayS: Double = 0.3) {
        val room = RoomReference(size = n / Proto.FRAME_SAMPLES + 1) // the whole party at once, for the test
        val music = FloatArray(n)
        val sentVoice = FloatArray(n) // what the guests play: the voice from echoDelayS ago
        val mic = FloatArray(n)

        init {
            val rng = Random(7)
            // Music: noise shaped by a 2 Hz beat, so its level keeps moving.
            var lp = 0.0
            for (i in 0 until n) {
                lp += 0.2 * (rng.nextGaussian() - lp)
                music[i] = (0.25 * (0.4 + 0.6 * abs(sin(2 * PI * 2 * i / 48_000.0))) * lp * 3).toFloat()
            }
            val delay = (echoDelayS * 48_000).toInt()
            for (i in delay until n) sentVoice[i] = sung[i - delay] * 3 // the cleaned voice goes out loud
            // Every phone plays frame k of the stream at T0 + 20k ms.
            val stereo = FloatArray(Proto.FRAME_SAMPLES * 2)
            val v = FloatArray(Proto.FRAME_SAMPLES)
            var k = 0
            while ((k + 1) * Proto.FRAME_SAMPLES <= n) {
                for (j in 0 until Proto.FRAME_SAMPLES) {
                    stereo[2 * j] = music[k * Proto.FRAME_SAMPLES + j]
                    stereo[2 * j + 1] = music[k * Proto.FRAME_SAMPLES + j]
                    v[j] = sentVoice[k * Proto.FRAME_SAMPLES + j]
                }
                room.add(T0 + k * Proto.FRAME_US, stereo, v)
                k++
            }
            // The room: sound arrives 6 ms later, then a reverb tail (40 ms decay) about 5 dB below it.
            val gm = Math.pow(10.0, musicDb / 20)
            val gv = Math.pow(10.0, voiceDb / 20)
            val tail = DoubleArray(4_000) { if (it == 288) 1.0 else if (it > 288) 0.05 * Math.exp(-(it - 288) / 1_900.0) * rng.nextGaussian() else 0.0 }
            for (i in 0 until n) {
                var e = 0.0
                for (d in tail.indices step 8) {
                    if (tail[d] == 0.0 || i - d < 0) continue
                    e += tail[d] * (gm * music[i - d] + gv * sentVoice[i - d])
                }
                mic[i] = e.toFloat()
            }
        }
    }

    @Test
    fun thePartysEchoIsTakenOutBetweenWordsAndTheVoiceStays() {
        val n = 48_000 * 10
        val sung = voice(n, 0.05)
        val party = Party(n, musicDb = -25.0, voiceDb = -20.0, sung = sung)
        val rng = Random(3)
        // First 4 s the button is up (the mic listens, muted); then the DJ talks.
        val talkFrom = 48_000 * 4
        val mic = FloatArray(n) { party.mic[it] + (rng.nextGaussian() * 0.0005).toFloat() + if (it >= talkFrom) sung[it] else 0f }
        val (out, latency) = clean(mic, party.room) { it >= talkFrom }
        // Between words: the music and the voice coming back are what the mic hears; most of it goes.
        val echoIn = rms(mic, talkFrom + 48_000, n - latency) { inGap(it) }
        val echoOut = rms(out, talkFrom + 48_000 + latency, n) { inGap(it - latency) }
        assertTrue("echo between words: ${db(echoIn)} → ${db(echoOut)} dB", db(echoOut) < db(echoIn) - 15)
        // While talking, the voice keeps nearly all its level (here its own echo comes back only
        // about 9 dB below it, a loud room, so the overlap costs a little).
        val voiceIn = rms(sung, talkFrom + 48_000, n - latency) { inSyllable(it) }
        val voiceOut = rms(out, talkFrom + 48_000 + latency, n) { inSyllable(it - latency) }
        assertTrue("voice: ${db(voiceIn)} → ${db(voiceOut)} dB", abs(db(voiceOut) - db(voiceIn)) < 3)
        // And without the room reference (no idea what's playing) it would have been much worse.
        val (blind, _) = clean(mic, null) { it >= talkFrom }
        val blindOut = rms(blind, talkFrom + 48_000 + latency, n) { inGap(it - latency) }
        assertTrue("knowing the party helps: ${db(blindOut)} vs ${db(echoOut)} dB", db(echoOut) < db(blindOut) - 6)
    }

    @Test
    fun chatterAndAVideoDontFoolTheEchoLearning() {
        // While the button is up, the mic also hears things that aren't the party's stream (people,
        // a YouTube video): the learned echo must not grow from them, or it would eat the voice.
        val n = 48_000 * 10
        val sung = voice(n, 0.05)
        val party = Party(n, musicDb = -40.0, voiceDb = -40.0, sung = sung) // the room's echo is faint
        val rng = Random(4)
        val talkFrom = 48_000 * 6
        val other = voice(n, 0.03, start = 12_345) // someone else talking near the phone
        var lp = 0.0
        val mic = FloatArray(n) {
            lp += 0.1 * (rng.nextGaussian() - lp)
            party.mic[it] + (lp * 0.01).toFloat() + (if (it < talkFrom) other[it] else sung[it])
        }
        val (out, latency) = clean(mic, party.room) { it >= talkFrom }
        val voiceIn = rms(sung, talkFrom + 48_000, n - latency) { inSyllable(it) }
        val voiceOut = rms(out, talkFrom + 48_000 + latency, n) { inSyllable(it - latency) }
        assertTrue("voice: ${db(voiceIn)} → ${db(voiceOut)} dB", abs(db(voiceOut) - db(voiceIn)) < 2)
    }

    // ---- the whole voice chain -----------------------------------------------------------------

    @Test
    fun anySpeakerComesOutLoudAndEven() {
        fun level(amplitude: Double): Pair<Double, Float> {
            val p = VoiceProcessor()
            val n = 48_000 * 6
            val v = voice(n, amplitude)
            val rng = Random(5)
            for (i in 0 until n) v[i] += (rng.nextGaussian() * 0.0005).toFloat()
            var i = 0
            while (i + 480 <= n) {
                val chunk = v.copyOfRange(i, i + 480)
                p.process(chunk, 480, T0 + i * 1_000_000L / 48_000)
                System.arraycopy(chunk, 0, v, i, 480)
                i += 480
            }
            return rms(v, 4 * 48_000, n) { inSyllable(it - p.latency) } to v.maxOf { abs(it) }
        }
        val (soft, softPeak) = level(0.01) // talking at arm's length, about −45 dBFS
        val (loud, loudPeak) = level(0.5) // shouting into the phone, about −10 dBFS
        assertTrue("soft voice comes out at ${db(soft)} dBFS", db(soft) in -20.0..-11.0)
        assertTrue("loud voice comes out at ${db(loud)} dBFS", db(loud) in -20.0..-11.0)
        assertTrue("34 dB apart in, ${db(loud) - db(soft)} dB out", abs(db(loud) - db(soft)) < 4)
        assertTrue("peaks $softPeak / $loudPeak", softPeak <= VoiceProcessor.LIMIT && loudPeak <= VoiceProcessor.LIMIT)
    }

    @Test
    fun theGateKeepsTheRoomOutAndLetsTheVoiceIn() {
        val p = VoiceProcessor()
        val rng = Random(3)
        val block = FloatArray(480)
        var n = 0
        fun run(seconds: Double, make: (Int) -> Float): FloatArray {
            val out = ArrayList<Float>()
            repeat((seconds * 100).toInt()) {
                for (i in block.indices) block[i] = make(n++)
                p.process(block, block.size, T0 + n * 1_000_000L / 48_000)
                block.forEach { out += it }
            }
            return out.toFloatArray()
        }
        // A quiet room (hiss at about −54 dBFS) stays shut.
        val room = run(2.0) { (rng.nextGaussian() * 0.002).toFloat() }
        assertTrue("room ${room.takeLast(24_000).maxOf { abs(it) }}", room.takeLast(24_000).maxOf { abs(it) } < 0.001f)
        assertFalse("open in a quiet room", p.open)
        // Someone sings (a 220 Hz tone at −20 dBFS): through, and loud.
        val sung = run(1.0) { (0.1 * sin(2 * PI * 220 * it / 48_000) + rng.nextGaussian() * 0.002).toFloat() }
        assertTrue("closed while singing", p.open)
        assertTrue("sung ${sung.takeLast(4_800).maxOf { abs(it) }}", sung.takeLast(4_800).maxOf { abs(it) } in 0.15f..VoiceProcessor.LIMIT)
        // The phrase ends: the gate closes after its hold, and fades out (no abrupt cut) within a second.
        val after = run(1.0) { (rng.nextGaussian() * 0.002).toFloat() }
        assertFalse("still open after the phrase", p.open)
        assertTrue("after ${after.takeLast(4_800).maxOf { abs(it) }}", after.takeLast(4_800).maxOf { abs(it) } < 0.002f)
    }

    @Test
    fun rumbleAndHandlingNoiseAreTakenOut() {
        val p = VoiceProcessor()
        val block = FloatArray(48_000) { 0.3f + (0.1 * sin(2 * PI * 440 * it / 48_000)).toFloat() } // DC offset + voice
        p.process(block, block.size)
        val tail = block.copyOfRange(24_000, 48_000)
        assertTrue("offset removed", abs(tail.average()) < 0.005)
    }

    private companion object {
        const val T0 = 5_000_000L
    }
}
