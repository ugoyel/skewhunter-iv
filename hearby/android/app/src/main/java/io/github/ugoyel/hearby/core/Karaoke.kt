package io.github.ugoyel.hearby.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Karaoke: the DJ talks or sings into the DJ phone and every phone plays the voice over the music.
 *
 * Every phone, the DJ phone included, keeps playing the music exactly in step while the mic is on,
 * so the room never hears the music twice. The voice goes into the guests' stream as it is captured
 * and reaches them a cushion later (the time the network needs). Before that it is cleaned up
 * ([VoiceProcessor]): the room's noise and the party itself (which the microphone hears too, and
 * would otherwise send around again as an echo) are taken out, and the voice is brought up loud and
 * even. The music dips to [DUCK_GAIN] meanwhile.
 *
 * Pure logic, tested on the desktop; the microphone itself is host/KaraokeMic.kt.
 */
object Karaoke {
    /** Music level while the mic is held: 30 % of its volume, so the voice sits clearly on top. */
    const val DUCK_GAIN = 0.30f

    /**
     * The voice for the frame sent now is taken from this long ago: the 20 ms frame itself, the
     * cleaner's own 10 ms, and the microphone's delivery. Anything newer isn't captured yet.
     */
    const val LIVE_LAG_US = 60_000L

    /** Mixes mono [voice] into interleaved stereo [music] and keeps the peaks musical (soft limit). */
    fun mix(music: FloatArray, voice: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val v = voice[i]
            music[2 * i] = soften(music[2 * i] + v)
            music[2 * i + 1] = soften(music[2 * i + 1] + v)
        }
    }

    /** Transparent below 0.8, then rounds off toward ±1 instead of clipping. */
    internal fun soften(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = (a - KNEE) / (1f - KNEE)
        val y = KNEE + (1f - KNEE) * tanh(over.toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    private const val KNEE = 0.8f
}

/** Smoothly moves the music level toward a target: down in about 0.3 s, back up in about 0.8 s. */
class Ducker(private val sampleRate: Int = Proto.SAMPLE_RATE) {
    var gain = 1f
        private set

    /** Scales interleaved stereo [frame] in place, ramping sample by sample toward [target]. */
    fun process(frame: FloatArray, frames: Int, target: Float) {
        val down = (1f - Karaoke.DUCK_GAIN) / (DOWN_S * sampleRate)
        val up = (1f - Karaoke.DUCK_GAIN) / (UP_S * sampleRate)
        for (i in 0 until frames) {
            gain = when {
                gain > target -> maxOf(target, gain - down)
                gain < target -> minOf(target, gain + up)
                else -> gain
            }
            frame[2 * i] *= gain
            frame[2 * i + 1] *= gain
        }
    }

    companion object {
        const val DOWN_S = 0.3f
        const val UP_S = 0.8f
    }
}

/**
 * Bands (roughly how the ear groups frequencies) used to judge noise and echo, shared by the
 * microphone's analysis ([VoiceCleaner]) and the room's ([RoomReference]).
 */
internal object VoiceBands {
    const val FFT = 1024
    const val BINS = FFT / 2 + 1
    private val EDGES_HZ = intArrayOf(
        0, 100, 200, 300, 400, 510, 630, 770, 920, 1080, 1270, 1480, 1720, 2000, 2320, 2700,
        3150, 3700, 4400, 5300, 6400, 7700, 9500, 12000, 15500, 20000, 24001,
    )
    val COUNT = EDGES_HZ.size - 1

    /** Band of each FFT bin at 48 kHz. */
    val bandOf = IntArray(BINS) { k ->
        val hz = k * Proto.SAMPLE_RATE.toDouble() / FFT
        (0 until COUNT).first { hz < EDGES_HZ[it + 1] }
    }

    /** Band power (sum of |X|²) from an FFT's first [BINS] bins. */
    fun powers(re: DoubleArray, im: DoubleArray, out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        for (k in 0 until BINS) out[bandOf[k]] += (re[k] * re[k] + im[k] * im[k]).toFloat()
    }

    /** Interpolation from band gains to bin gains: lower band and weight of the upper one, per bin. */
    val lowerBand = IntArray(BINS)
    val upperWeight = FloatArray(BINS)

    init {
        val center = DoubleArray(COUNT) { (EDGES_HZ[it] + EDGES_HZ[it + 1]) / 2.0 }
        for (k in 0 until BINS) {
            val hz = k * Proto.SAMPLE_RATE.toDouble() / FFT
            var b = 0
            while (b < COUNT - 2 && hz > center[b + 1]) b++
            lowerBand[k] = b
            upperWeight[k] = ((hz - center[b]) / (center[b + 1] - center[b])).coerceIn(0.0, 1.0).toFloat()
        }
    }

    fun hannWindow(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / n) }
}

/**
 * What the room is playing, by the moment it plays: for each 20 ms of the guests' stream, how much
 * music and how much (DJ) voice it holds in each band, stamped with the time every phone plays it.
 * The microphone hears exactly that a moment later, so [VoiceCleaner] knows what is echo. The sender
 * adds, the microphone thread reads.
 */
class RoomReference(private val size: Int = 128) { // 2.5 s: the longest cushion plus the room's echo tail
    private val playUs = LongArray(size) { Long.MIN_VALUE }
    private val music = Array(size) { FloatArray(VoiceBands.COUNT) }
    private val voice = Array(size) { FloatArray(VoiceBands.COUNT) }
    private var next = 0
    private val window = VoiceBands.hannWindow(Proto.FRAME_SAMPLES)
    private val re = DoubleArray(VoiceBands.FFT)
    private val im = DoubleArray(VoiceBands.FFT)
    private val musicBands = FloatArray(VoiceBands.COUNT)
    private val voiceBands = FloatArray(VoiceBands.COUNT)

    /**
     * One frame of the guests' stream: [musicStereo] (interleaved, as played, ducked) and the mono
     * [voice] mixed over it (null: none), played by every phone from [playAtUs] (host clock).
     */
    fun add(playAtUs: Long, musicStereo: FloatArray, voice: FloatArray?) {
        analyse(musicBands) { i -> (musicStereo[2 * i] + musicStereo[2 * i + 1]) * 0.5 }
        if (voice != null) analyse(voiceBands) { i -> voice[i].toDouble() } else java.util.Arrays.fill(voiceBands, 0f)
        synchronized(this) {
            playUs[next] = playAtUs
            System.arraycopy(musicBands, 0, music[next], 0, VoiceBands.COUNT)
            System.arraycopy(voiceBands, 0, this.voice[next], 0, VoiceBands.COUNT)
            next = (next + 1) % size
        }
    }

    private inline fun analyse(out: FloatArray, sample: (Int) -> Double) {
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        for (i in 0 until Proto.FRAME_SAMPLES) re[i] = sample(i) * window[i]
        Fft.transform(re, im, inverse = false)
        VoiceBands.powers(re, im, out)
    }

    /**
     * How much music and voice the microphone may be hearing from the room at [atUs]: the loudest
     * of what played just before (phones a few ms apart, sound taking a moment to cross the room),
     * with a fading tail for the room's reverb.
     */
    fun at(atUs: Long, outMusic: FloatArray, outVoice: FloatArray) {
        java.util.Arrays.fill(outMusic, 0f)
        java.util.Arrays.fill(outVoice, 0f)
        synchronized(this) {
            for (f in 0 until size) {
                val age = atUs - playUs[f]
                if (playUs[f] == Long.MIN_VALUE || age < -EARLY_US || age > TAIL_US) continue
                val w = if (age <= SPREAD_US) 1f else exp(-(age - SPREAD_US).toDouble() / REVERB_US).toFloat()
                val m = music[f]
                val v = voice[f]
                for (b in 0 until VoiceBands.COUNT) {
                    outMusic[b] = maxOf(outMusic[b], m[b] * w)
                    outVoice[b] = maxOf(outVoice[b], v[b] * w)
                }
            }
        }
    }

    private companion object {
        const val EARLY_US = 30_000L // a phone a little early, or the window reaching ahead
        const val SPREAD_US = 60_000L // the frame itself, sound crossing the room, phones a little late
        const val REVERB_US = 40_000.0
        const val TAIL_US = 400_000L
    }
}

/**
 * Takes the room out of the microphone, the way a good headset mic does: steady noise (fans, hum,
 * crowd murmur) and the party itself (the music and the DJ's own voice coming back from the other
 * phones, which would otherwise go round again as an echo). Works on 20 ms windows every 10 ms,
 * band by band: where the voice is clearly louder than noise and echo, it passes untouched; where
 * it isn't, that band is turned down (to [MIN_GAIN]). Adds [LATENCY] samples of delay.
 *
 * Noise is learned from the quietest moments. Echo is predicted from what the room is playing
 * ([RoomReference]) and how loudly this phone hears it, which it learns while the mic button is up
 * (nobody talking into it): how the microphone's level follows the room's music and the voice's
 * last echoes, by regression, so sounds that have nothing to do with the party (people chatting,
 * a YouTube video) don't fool it.
 */
class VoiceCleaner {
    private val window = VoiceBands.hannWindow(WINDOW).map { sqrt(it) }.toDoubleArray()
    private val history = FloatArray(WINDOW) // newest WINDOW input samples
    private val overlap = FloatArray(WINDOW)
    private var hopFill = 0
    private val pendingOut = FloatArray(4 * HOP)
    private var outCount = 0
    private val re = DoubleArray(VoiceBands.FFT)
    private val im = DoubleArray(VoiceBands.FFT)

    private val power = FloatArray(VoiceBands.COUNT)
    private val smooth = FloatArray(VoiceBands.COUNT)
    private val noise = FloatArray(VoiceBands.COUNT)
    private val prevClean = FloatArray(VoiceBands.COUNT) // last window's clean power / (noise + echo)
    private val bandGain = FloatArray(VoiceBands.COUNT) { 1f }
    private val roomMusic = FloatArray(VoiceBands.COUNT)
    private val roomVoice = FloatArray(VoiceBands.COUNT)
    private val echo = FloatArray(VoiceBands.COUNT)
    /** How loudly this phone hears the room's music and voice, per band (power ratio). */
    internal val musicCoupling = FloatArray(VoiceBands.COUNT) { PRIOR_COUPLING }
    internal val voiceCoupling = FloatArray(VoiceBands.COUNT) { PRIOR_COUPLING }
    // Running averages for the regression of the microphone's level (o) on the room's music (m)
    // and voice (v), per band: E[m], E[v], E[o], E[mm], E[vv], E[mv], E[mo], E[vo].
    private val stats = Array(VoiceBands.COUNT) { DoubleArray(8) }
    private val heardMusic = IntArray(VoiceBands.COUNT)
    private val heardVoice = IntArray(VoiceBands.COUNT)
    private var started = false
    private var hops = 0

    /** Delay added, samples. */
    var latency = WINDOW - HOP
        private set

    /** Fraction of the last window's energy that was voice (after cleaning), 0..1. */
    var voiceShare = 0f
        private set

    /**
     * Cleans [n] samples in place. [capturedUs] is when the first of them was captured (host clock,
     * µs) and [room] what the room was playing (null: nothing known, noise only). [talking] is true
     * while the mic button is held.
     */
    fun process(x: FloatArray, n: Int, capturedUs: Long, talking: Boolean, room: RoomReference?) {
        var i = 0
        var outPos = 0
        while (i < n) {
            val take = minOf(HOP - hopFill, n - i)
            System.arraycopy(history, take, history, 0, WINDOW - take)
            System.arraycopy(x, i, history, WINDOW - take, take)
            hopFill += take
            i += take
            if (hopFill == HOP) {
                hopFill = 0
                // The window ends at sample i of this call; its middle is WINDOW/2 earlier.
                val centerUs = capturedUs + (i - WINDOW / 2) * 1_000_000L / Proto.SAMPLE_RATE
                hop(centerUs, talking, room)
                outPos = emit(x, outPos, minOf(outCount, i - outPos)) // never past what was read
            }
        }
        // Hand back exactly n samples; at the very start (or with odd chunk sizes) that means
        // waiting a little longer once.
        val need = n - outPos
        if (outCount < need) {
            val missing = need - outCount
            System.arraycopy(pendingOut, 0, pendingOut, missing, outCount)
            java.util.Arrays.fill(pendingOut, 0, missing, 0f)
            outCount = need
            latency += missing
        }
        emit(x, outPos, need)
    }

    private fun emit(x: FloatArray, at: Int, count: Int): Int {
        System.arraycopy(pendingOut, 0, x, at, count)
        System.arraycopy(pendingOut, count, pendingOut, 0, outCount - count)
        outCount -= count
        return at + count
    }

    private fun hop(centerUs: Long, talking: Boolean, room: RoomReference?) {
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        for (k in 0 until WINDOW) re[k] = history[k] * window[k]
        Fft.transform(re, im, inverse = false)
        VoiceBands.powers(re, im, power)

        if (room != null) room.at(centerUs, roomMusic, roomVoice) else {
            java.util.Arrays.fill(roomMusic, 0f)
            java.util.Arrays.fill(roomVoice, 0f)
        }
        // The first second, nothing is known about the room yet: learn its noise quickly.
        val rise = if (talking && hops > STARTUP_HOPS) RISE_TALKING else RISE_LISTENING
        hops++
        var cleanEnergy = 0f
        var totalEnergy = 0f
        for (b in 0 until VoiceBands.COUNT) {
            val p = power[b]
            if (!started) {
                smooth[b] = p
                noise[b] = p * 0.5f + 1e-12f
            }
            smooth[b] = SMOOTH * smooth[b] + (1 - SMOOTH) * p

            // Echo: what the room plays, times how loudly this phone hears it.
            val m = roomMusic[b]
            val v = roomVoice[b]
            if (m + v > 0f) learn(b, smooth[b], m, v, talking)
            echo[b] = musicCoupling[b] * m + voiceCoupling[b] * v

            // Noise: what is left once the echo is accounted for; follows the quietest moments down
            // at once and creeps up slowly.
            val rest = maxOf(smooth[b] - echo[b], 0.05f * smooth[b])
            noise[b] = if (rest < noise[b]) rest else noise[b] * rise
            noise[b] = maxOf(noise[b], 1e-12f)

            // Gain: how much of this band is voice (decision-directed, which keeps it from warbling).
            val unwanted = NOISE_OVER * noise[b] + ECHO_OVER * echo[b]
            val post = p / unwanted
            val prior = DD * prevClean[b] + (1 - DD) * maxOf(post - 1f, 0f)
            val g = maxOf(prior / (1f + prior), MIN_GAIN)
            prevClean[b] = g * g * post
            bandGain[b] = g
            cleanEnergy += g * g * p
            totalEnergy += p
        }
        started = true
        voiceShare = if (totalEnergy > 0f) cleanEnergy / totalEnergy else 0f

        for (k in 0 until VoiceBands.BINS) {
            val lo = VoiceBands.lowerBand[k]
            val w = VoiceBands.upperWeight[k]
            val g = bandGain[lo] * (1 - w) + bandGain[lo + 1] * w
            re[k] *= g
            im[k] *= g
            if (k != 0 && k != VoiceBands.FFT / 2) { // keep the spectrum symmetric (real output)
                re[VoiceBands.FFT - k] = re[k]
                im[VoiceBands.FFT - k] = -im[k]
            }
        }
        Fft.transform(re, im, inverse = true)
        for (k in 0 until WINDOW) overlap[k] += (re[k] * window[k]).toFloat()
        System.arraycopy(overlap, 0, pendingOut, outCount, HOP)
        outCount += HOP
        System.arraycopy(overlap, HOP, overlap, 0, WINDOW - HOP)
        java.util.Arrays.fill(overlap, WINDOW - HOP, WINDOW, 0f)
    }

    /**
     * Learns how loudly this phone hears the room in band [b], from the microphone's level [o] and
     * the room's music [m] and voice [v] at that moment. Only while nobody talks into the mic: the
     * slope of a regression with an intercept, so whatever else the microphone hears (people, a
     * video, noise) goes into the intercept instead of the coupling. While someone talks, an
     * estimate that is clearly too high (more echo predicted than heard in all) is lowered.
     */
    private fun learn(b: Int, o: Float, m: Float, v: Float, talking: Boolean) {
        if (talking) {
            if (musicCoupling[b] * m + voiceCoupling[b] * v > o) {
                musicCoupling[b] = maxOf(MIN_COUPLING, musicCoupling[b] * TALKING_DECAY)
                voiceCoupling[b] = maxOf(MIN_COUPLING, voiceCoupling[b] * TALKING_DECAY)
            }
            return
        }
        val s = stats[b]
        val md = m.toDouble()
        val vd = v.toDouble()
        val od = o.toDouble()
        val first = heardMusic[b] + heardVoice[b] == 0
        val a = if (first) 1.0 else REGRESSION_RATE
        s[0] += a * (md - s[0]); s[1] += a * (vd - s[1]); s[2] += a * (od - s[2])
        s[3] += a * (md * md - s[3]); s[4] += a * (vd * vd - s[4]); s[5] += a * (md * vd - s[5])
        s[6] += a * (md * od - s[6]); s[7] += a * (vd * od - s[7])
        if (m > 0f) heardMusic[b]++
        if (v > 0f) heardVoice[b]++
        val varM = s[3] - s[0] * s[0]
        val varV = s[4] - s[1] * s[1]
        val covMV = s[5] - s[0] * s[1]
        val covMO = s[6] - s[0] * s[2]
        val covVO = s[7] - s[1] * s[2]
        val trustM = heardMusic[b] >= TRUST_HOPS && varM > 1e-6 * s[3]
        val trustV = heardVoice[b] >= TRUST_HOPS && varV > 1e-6 * s[4]
        val det = varM * varV - covMV * covMV
        when {
            trustM && trustV && det > 1e-6 * s[3] * s[4] -> {
                musicCoupling[b] = clampCoupling((covMO * varV - covVO * covMV) / det)
                voiceCoupling[b] = clampCoupling((covVO * varM - covMO * covMV) / det)
            }
            trustM && !trustV -> musicCoupling[b] = clampCoupling(covMO / varM)
            trustV && !trustM -> voiceCoupling[b] = clampCoupling(covVO / varV)
        }
    }

    private fun clampCoupling(x: Double): Float = x.toFloat().coerceIn(MIN_COUPLING, MAX_COUPLING)

    companion object {
        const val WINDOW = 960
        const val HOP = 480
        /** What is left of noise and echo: −24 dB (all the way down sounds unnatural). */
        const val MIN_GAIN = 0.06f
        private const val STARTUP_HOPS = 100
        private const val SMOOTH = 0.5f // short: the gaps between words are short
        private const val DD = 0.96f
        private const val NOISE_OVER = 1.5f
        private const val ECHO_OVER = 1.5f
        private val RISE_TALKING = exp(ln(10.0) * 3 / 10 / 100).toFloat() // +3 dB per second
        private val RISE_LISTENING = exp(ln(10.0) * 10 / 10 / 100).toFloat() // +10 dB per second
        /** The regression remembers about the last 2 s the button was up (10 ms steps). */
        private const val REGRESSION_RATE = 0.005
        /** 0.3 s of the room heard before trusting what was learned. */
        private const val TRUST_HOPS = 30
        private const val TALKING_DECAY = 0.99f
        internal const val PRIOR_COUPLING = 1e-3f // −30 dB until it has listened to the room
        private const val MIN_COUPLING = 1e-7f
        private const val MAX_COUPLING = 10f
    }
}

/**
 * The DJ's voice, party-ready: rumble out (high-pass at 100 Hz), room out ([VoiceCleaner]), silence
 * between phrases (a gate), then loud and even like a DJ on a PA: an automatic level that brings any
 * voice to the same loudness, a compressor and a limiter. Nothing to set.
 */
class VoiceProcessor(private val sampleRate: Int = Proto.SAMPLE_RATE) {
    private val cleaner = VoiceCleaner()
    private val hpA = exp(-2.0 * PI * HIGH_PASS_HZ / sampleRate).toFloat()
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var envelope = 0f
    private var gate = 0f
    private var holdLeft = 0
    private var level2 = (TARGET_RMS / START_GAIN) * (TARGET_RMS / START_GAIN)
    private var agc = START_GAIN
    private var compEnvelope = 0f

    /** True while the gate is open (someone is singing or talking). */
    val open: Boolean get() = gate > 0.5f

    /** Delay added, samples. */
    val latency: Int get() = cleaner.latency

    /** The automatic level right now (×). */
    val gain: Float get() = agc

    /**
     * Processes [frames] samples in place. [capturedUs]: when the first was captured (host clock);
     * [talking]: the mic button is held; [room]: what the party is playing, to take its echo out.
     */
    fun process(mono: FloatArray, frames: Int, capturedUs: Long = 0, talking: Boolean = true, room: RoomReference? = null) {
        for (i in 0 until frames) {
            val x = mono[i]
            val hp = hpA * (hpPrevOut + x - hpPrevIn) // y[n] = a * (y[n-1] + x[n] - x[n-1])
            hpPrevIn = x
            hpPrevOut = hp
            mono[i] = hp
        }
        cleaner.process(mono, frames, capturedUs, talking, room)

        val attack = 1f - exp(-1.0 / (ATTACK_S * sampleRate)).toFloat()
        val release = 1f - exp(-1.0 / (RELEASE_S * sampleRate)).toFloat()
        val envCoef = 1f - exp(-1.0 / (ENVELOPE_S * sampleRate)).toFloat()
        val holdFrames = (HOLD_S * sampleRate).toInt()
        val levelCoef = 1f - exp(-1.0 / (LEVEL_S * sampleRate)).toFloat()
        val agcDown = 1f - exp(-1.0 / (AGC_DOWN_S * sampleRate)).toFloat()
        val agcUp = 1f - exp(-1.0 / (AGC_UP_S * sampleRate)).toFloat()
        val compAttack = 1f - exp(-1.0 / (COMP_ATTACK_S * sampleRate)).toFloat()
        val compRelease = 1f - exp(-1.0 / (COMP_RELEASE_S * sampleRate)).toFloat()
        for (i in 0 until frames) {
            val x = mono[i]
            // Gate: open while someone talks, shut (smoothly) between phrases.
            envelope += (x * x - envelope) * envCoef
            val loud = sqrt(envelope) > OPEN_LEVEL
            if (loud) holdLeft = holdFrames else if (holdLeft > 0) holdLeft--
            val target = if (loud || holdLeft > 0) 1f else 0f
            gate += (target - gate) * if (target > gate) attack else release
            var y = x * gate
            // Automatic level: learns how loud this voice is while it talks, and brings it to the
            // party's level (quickly down if it gets loud, slowly up if it is soft).
            if (loud) {
                level2 += (x * x - level2) * levelCoef
                val want = (TARGET_RMS / sqrt(maxOf(level2, 1e-12f))).coerceIn(MIN_GAIN, MAX_GAIN)
                agc += (want - agc) * if (want < agc) agcDown else agcUp
            }
            y *= agc
            // Compressor: above the threshold, every 3 dB louder comes out only 1 dB louder.
            val a = abs(y)
            compEnvelope += (a - compEnvelope) * if (a > compEnvelope) compAttack else compRelease
            if (compEnvelope > COMP_THRESHOLD) y *= (COMP_THRESHOLD + (compEnvelope - COMP_THRESHOLD) / COMP_RATIO) / compEnvelope
            // Limiter: peaks round off below full scale instead of clipping.
            mono[i] = LIMIT * tanh((y / LIMIT).toDouble()).toFloat()
        }
    }

    companion object {
        const val HIGH_PASS_HZ = 100.0
        /** Level (after cleaning, so the room's hiss is already well below it) that opens the gate: −56 dBFS. */
        const val OPEN_LEVEL = 0.0015f
        const val ATTACK_S = 0.005f
        const val RELEASE_S = 0.15f
        const val HOLD_S = 0.25f
        const val ENVELOPE_S = 0.01f
        /** The voice's loudness at the party: −15 dBFS RMS, well above the dipped music. */
        const val TARGET_RMS = 0.18f
        const val START_GAIN = 6f
        const val MIN_GAIN = 0.5f
        /** Up to +30 dB for a soft voice at arm's length. */
        const val MAX_GAIN = 32f
        const val LEVEL_S = 0.3f
        const val AGC_DOWN_S = 0.05f
        const val AGC_UP_S = 1.5f
        const val COMP_THRESHOLD = 0.3f
        const val COMP_RATIO = 3f
        const val COMP_ATTACK_S = 0.003f
        const val COMP_RELEASE_S = 0.12f
        const val LIMIT = 0.95f
    }
}

/**
 * The last couple of seconds of (cleaned) microphone audio, each sample tied to the moment it was
 * captured (from the recorder's own timestamps), so the sender can take the voice captured at a
 * given moment. One thread writes, one reads.
 */
class VoiceTape(private val sampleRate: Int = Proto.SAMPLE_RATE, seconds: Int = 3) {
    private val ring = FloatArray(sampleRate * seconds)
    @Volatile private var written = 0L // samples written so far
    private val lock = Any()
    private var anchorIndex = 0L // sample index captured at anchorUs
    private var anchorUs = Long.MIN_VALUE

    /** Appends samples. */
    fun write(src: FloatArray, n: Int) {
        var w = written
        for (i in 0 until n) {
            ring[(w % ring.size).toInt()] = src[i]
            w++
        }
        written = w
    }

    /** Ties sample [index] to the moment it was captured, µs (from AudioRecord.getTimestamp). */
    fun anchor(index: Long, capturedUs: Long) = synchronized(lock) {
        anchorIndex = index
        anchorUs = capturedUs
    }

    val samplesWritten: Long get() = written

    private var cursor = -1L // where the next frame's voice starts (reader only)

    /**
     * The voice for the next 20 ms frame: [n] samples from the moment [fromUs], continuing seamlessly
     * from the previous frame. The capture timestamps decide where to start; a small drift between
     * the microphone's clock and the sender's is caught a sample at a time (inaudible), a big one by
     * jumping. If they point at audio not captured yet, or long gone (a phone whose timestamps can't
     * be trusted), the newest voice is used instead: the voice always gets through.
     */
    fun next(fromUs: Long, n: Int, out: FloatArray): Int {
        val end = written
        val oldest = maxOf(0L, end - ring.size + sampleRate / 10)
        val want = indexFor(fromUs)
        if (want != null) {
            val drift = want - cursor
            when {
                cursor < 0 || abs(drift) > sampleRate / 100 -> cursor = want
                drift > sampleRate / 1000 -> cursor++ // the microphone runs a little fast: skip one
                drift < -sampleRate / 1000 -> cursor-- // a little slow: repeat one
            }
        }
        if (cursor < 0 || cursor + n > end) cursor = end - n // not captured yet: the newest there is
        if (cursor < oldest) cursor = maxOf(oldest, end - n)
        var got = 0
        for (i in 0 until n) {
            val idx = cursor + i
            if (idx in oldest until end) {
                out[i] = ring[(idx % ring.size).toInt()]
                got++
            } else {
                out[i] = 0f
            }
        }
        cursor += n
        return got
    }

    private fun indexFor(us: Long): Long? {
        val (aIndex, aUs) = synchronized(lock) { anchorIndex to anchorUs }
        if (aUs == Long.MIN_VALUE) return null
        return aIndex + Math.round((us - aUs) * sampleRate / 1e6)
    }
}
