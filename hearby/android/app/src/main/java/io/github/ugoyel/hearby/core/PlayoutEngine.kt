package io.github.ugoyel.hearby.core

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil

/** A (frame position, time) pair: frame number [framePosition] reaches the listener at [timeNs]. */
class SinkTimestamp {
    var framePosition = 0L
    var timeNs = 0L
}

/**
 * The audio output as the engine sees it. On Android this wraps an AudioTrack, on iOS a ring buffer
 * drained by the AVAudioEngine render callback.
 */
interface AudioSink {
    /** Queue [frames] interleaved stereo frames for playback. May block until there is room. */
    fun write(interleaved: FloatArray, frames: Int)

    /**
     * Report when a recently queued frame is (or was) actually heard, in the local monotonic clock
     * and including output latency. Frame positions count frames passed to [write]. Returns false
     * while the output cannot tell yet.
     */
    fun getTimestamp(out: SinkTimestamp): Boolean
}

/** Decodes one 20 ms Opus packet into 960 interleaved stereo float frames. */
interface OpusFrameDecoder {
    fun decode(packet: ByteArray, length: Int, out: FloatArray): Boolean
}

/**
 * Timestamp-driven playout: decides, 20 ms at a time, exactly which samples go to the audio output
 * so that every frame is *heard* at
 *
 *     hostCaptureTime + playoutDelay + userOffset      (expressed in this device's clock)
 *
 * WAITING   output is being primed / no stream yet; writes silence.
 * ALIGNING  picks the frame that is due and pads with silence, to the sample, until it is due.
 * PLAYING   plays frames in order and measures, for every frame, when it will really be heard
 *           versus when it should be. That error steers a resampler by up to +/-0.5 % (inaudible),
 *           which absorbs the drift between the host's capture clock, the network clock estimate
 *           and this device's DAC clock. Errors too large to steer away trigger a re-align.
 *
 * Single-threaded: only the playout thread calls [pump]. Settings may be changed from any thread.
 *
 * Mirrored by ios/Hearby/Core/PlayoutEngine.swift.
 */
class PlayoutEngine(
    private val sink: AudioSink,
    private val decoder: OpusFrameDecoder,
    private val jitter: JitterBuffer,
    private val clock: ClockSync,
) {
    enum class State { WAITING, ALIGNING, PLAYING }

    // ---- settings -----------------------------------------------------------------------------

    /** Common delay chosen by the host; identical on every listener. */
    @Volatile
    var playoutDelayMs: Int = 350
        set(value) {
            if (field != value) {
                field = value
                requestRealign(resetLateBias = true)
            }
        }

    /** Per-device fine tuning (the "Bluetooth sync" slider). Positive plays later. */
    @Volatile
    var userOffsetMs: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestRealign(resetLateBias = true)
            }
        }

    /** Correction measured by the DJ's sound check, microseconds. Positive plays later. */
    @Volatile
    var calibrationUs: Long = 0
        set(value) {
            if (field != value) {
                field = value
                requestRealign(resetLateBias = true)
            }
        }

    /** Sound-check chirps waiting to be mixed into the output at their exact media time. */
    val tones = ToneScheduler()

    // ---- observable state -----------------------------------------------------------------------

    @Volatile var state: State = State.WAITING; private set

    /** Smoothed "heard too late (+) / too early (-)" error while PLAYING, milliseconds. */
    @Volatile var syncErrorMs: Float = 0f; private set

    /** Current speed correction in parts per million. */
    @Volatile var speedPpm: Int = 0; private set

    /**
     * Extra delay this device had to add because the audio it needed had not arrived yet (output
     * latency + negative offset larger than the host's playout delay allows). 0 when fully in sync.
     */
    @Volatile var lateBiasMs: Int = 0; private set

    /** How much received audio lies ahead of the play cursor, milliseconds. */
    @Volatile var cushionMs: Int = 0; private set

    /** Peak level of the audio being played, 0..1, with a short decay. Drives the visualizer. */
    @Volatile var outputLevel: Float = 0f; private set

    @Volatile var playedFrames: Long = 0; private set
    @Volatile var concealedFrames: Long = 0; private set
    @Volatile var realignCount: Long = 0; private set

    // ---- internals ------------------------------------------------------------------------------

    private val ts = SinkTimestamp()
    private val packet = JitterBuffer.Packet()
    private val pcm = FloatArray(FRAME * CH)
    private val resampled = FloatArray(OUT_CAPACITY * CH)
    private val silence = FloatArray(FRAME * CH)
    private val tail = FloatArray(FADE * CH)
    private val resampler = Resampler(CH, FRAME)

    private var written = 0L
    private var nextSeq = -1L
    private var lastTimestampUs = NO_TS
    private var haveTail = false
    private var fadeIn = false
    private var smoothedErrMs = 0.0
    private var speed = 1.0
    private var hardRun = 0
    private var missRun = 0
    private var underflowRun = 0
    private var windowFrames = 0
    private var windowMinCushionUs = Long.MAX_VALUE
    private val realignRequest = AtomicInteger(0)

    /** Re-run alignment at the next frame (settings changed, or the user asked to re-sync). */
    fun requestRealign(resetLateBias: Boolean) {
        val want = if (resetLateBias) 2 else 1
        realignRequest.updateAndGet { maxOf(it, want) }
    }

    /** Forget the stream entirely (the host started a new session). Safe from any thread. */
    fun requestStreamReset() {
        realignRequest.set(3)
    }

    private fun resetStream() {
        nextSeq = -1
        lastTimestampUs = NO_TS
        lateBiasMs = 0
        tones.clear()
        haveTail = false
        enter(State.WAITING)
    }

    /** Do one step of work: queues roughly 20 ms of audio on the sink. */
    fun pump() {
        val request = realignRequest.getAndSet(0)
        if (request == 3) {
            resetStream()
        } else if (request != 0) {
            if (request == 2) lateBiasMs = 0
            if (state == State.PLAYING) {
                writeConcealment() // fade out instead of cutting off
                enter(State.ALIGNING)
                return
            }
        }
        when (state) {
            State.WAITING -> pumpWaiting()
            State.ALIGNING -> pumpAligning()
            State.PLAYING -> pumpPlaying()
        }
    }

    private fun enter(next: State) {
        if (next != State.PLAYING) {
            speed = 1.0
            speedPpm = 0
            syncErrorMs = 0f
            outputLevel = 0f
        }
        if (next == State.ALIGNING && state == State.PLAYING) realignCount++
        state = next
    }

    private fun pumpWaiting() {
        writeSilence(FRAME)
        if (clock.synced && jitter.size >= MIN_START_PACKETS && sink.getTimestamp(ts)) enter(State.ALIGNING)
    }

    /** Local time (ns) at which the next frame handed to the sink will be heard. */
    private fun presentationNs(): Long =
        ts.timeNs + (written - ts.framePosition) * 1_000_000_000L / Proto.SAMPLE_RATE

    private fun totalDelayUs(): Long = (playoutDelayMs + userOffsetMs + lateBiasMs) * 1000L + calibrationUs

    /** Everything this device adds on top of the host's playout delay, µs (reported to the host). */
    fun adjustUs(): Long = (userOffsetMs + lateBiasMs) * 1000L + calibrationUs

    private fun pumpAligning() {
        val newestTs = jitter.newestTimestampUs()
        if (!clock.synced || newestTs < 0 || !sink.getTimestamp(ts)) {
            writeSilence(FRAME)
            enter(State.WAITING)
            return
        }
        // Media time (host clock) that must be heard at the moment the next written frame plays.
        var mediaUs = clock.localToHostUs(presentationNs() / 1000) - totalDelayUs()

        // We can only play what has arrived. If the frame that is due has not been received yet
        // (plus a little headroom for network jitter), this device cannot meet the host's playout
        // delay; fall back by exactly as much as is missing and report it.
        val cushionUs = newestTs - mediaUs
        if (cushionUs < MIN_CUSHION_US) {
            val addMs = ceil((MIN_CUSHION_US - cushionUs) / 1000.0).toInt()
            lateBiasMs += addMs
            mediaUs -= addMs * 1000L
        }

        val seq = jitter.firstAtOrAfter(mediaUs)
        if (seq < 0) {
            writeSilence(FRAME)
            return
        }
        val padFrames = (jitter.timestampOf(seq) - mediaUs) * Proto.SAMPLE_RATE / 1_000_000L
        if (padFrames >= FRAME) {
            writeSilence(FRAME) // not due yet; look again after this block
            return
        }
        if (padFrames > 0) writeSilence(padFrames.toInt())

        jitter.dropBelow(seq)
        nextSeq = seq
        cushionMs = ((newestTs - mediaUs) / 1000).toInt().coerceAtLeast(0)
        lastTimestampUs = NO_TS
        resampler.reset()
        haveTail = false
        fadeIn = true
        smoothedErrMs = 0.0
        speed = 1.0
        hardRun = 0
        missRun = 0
        underflowRun = 0
        windowFrames = 0
        windowMinCushionUs = Long.MAX_VALUE
        enter(State.PLAYING)
    }

    private fun pumpPlaying() {
        val frameTs = jitter.timestampOf(nextSeq)

        if (frameTs >= 0) {
            // The host's timeline jumped (it paused, or dropped audio): line up again from scratch.
            if (lastTimestampUs != NO_TS && abs(frameTs - (lastTimestampUs + Proto.FRAME_US)) > DISCONTINUITY_US) {
                writeConcealment()
                enter(State.ALIGNING)
                return
            }
            if (sink.getTimestamp(ts) && clock.synced) {
                val heardNs = presentationNs() + RESAMPLER_DELAY_NS
                val dueNs = clock.hostToLocalUs(frameTs) * 1000L + totalDelayUs() * 1000L
                val errMs = (heardNs - dueNs) / 1e6
                if (abs(errMs) > HARD_RESYNC_MS) {
                    if (++hardRun >= HARD_RESYNC_FRAMES) {
                        writeConcealment()
                        enter(State.ALIGNING)
                        return
                    }
                } else {
                    hardRun = 0
                }
                val bounded = errMs.coerceIn(-HARD_RESYNC_MS, HARD_RESYNC_MS)
                smoothedErrMs += (bounded - smoothedErrMs) * ERROR_SMOOTHING
                // Late (positive error) -> consume input faster to catch up.
                speed = 1.0 + (smoothedErrMs * SPEED_GAIN_PER_MS).coerceIn(-MAX_SPEED_ADJUST, MAX_SPEED_ADJUST)
                syncErrorMs = smoothedErrMs.toFloat()
                speedPpm = Math.round((speed - 1.0) * 1e6).toInt()
            }
        }

        val decoded = frameTs >= 0 && jitter.take(nextSeq, packet) && decoder.decode(packet.data, packet.length, pcm)
        if (decoded) {
            if (fadeIn) {
                applyFadeIn(pcm)
                fadeIn = false
            }
            System.arraycopy(pcm, (FRAME - FADE) * CH, tail, 0, FADE * CH)
            haveTail = true
            var peak = 0f
            var i = 0
            while (i < pcm.size) { // every 8th frame is plenty for a meter
                val v = abs(pcm[i])
                if (v > peak) peak = v
                i += 16
            }
            outputLevel = maxOf(minOf(peak, 1f), outputLevel * LEVEL_DECAY)
            lastTimestampUs = frameTs
            missRun = 0
            underflowRun = 0
            playedFrames++
            trackCushion(frameTs)
        } else {
            fillConcealment(pcm)
            outputLevel *= LEVEL_DECAY
            if (lastTimestampUs != NO_TS) lastTimestampUs += Proto.FRAME_US
            concealedFrames++
            missRun++
            // Nothing at or beyond this frame has arrived: we are consuming faster than the network delivers.
            if (jitter.newestSeq < nextSeq) underflowRun++ else underflowRun = 0
        }

        // Sound-check chirps go in after the fades, at their exact sample on the media timeline.
        if (lastTimestampUs != NO_TS) tones.mixInto(pcm, lastTimestampUs, FRAME, CH)

        val n = resampler.process(pcm, FRAME, speed, resampled)
        sink.write(resampled, n)
        written += n
        nextSeq++

        if (underflowRun >= UNDERFLOW_FRAMES || missRun >= STALL_FRAMES) {
            // Ran dry. Go quiet and line up again once audio is flowing; the cushion check in
            // pumpAligning() will add late bias if this device simply cannot keep up.
            enter(State.WAITING)
        }
    }

    /** Every few seconds, hand back late bias that the measured headroom shows is no longer needed. */
    private fun trackCushion(frameTs: Long) {
        val newest = jitter.newestTimestampUs()
        val cushionUs = if (newest >= 0) newest - frameTs else 0L
        if (cushionUs < windowMinCushionUs) windowMinCushionUs = cushionUs
        if (++windowFrames < CUSHION_WINDOW_FRAMES) return
        cushionMs = (windowMinCushionUs / 1000).toInt()
        if (lateBiasMs > 0) {
            val spareMs = cushionMs - (MIN_CUSHION_US / 1000).toInt() - LATE_BIAS_HYSTERESIS_MS
            if (spareMs >= minOf(lateBiasMs, LATE_BIAS_MIN_STEP_MS)) {
                lateBiasMs -= minOf(lateBiasMs, spareMs)
                requestRealign(resetLateBias = false)
            }
        }
        windowFrames = 0
        windowMinCushionUs = Long.MAX_VALUE
    }

    private fun writeSilence(frames: Int) {
        sink.write(silence, frames)
        written += frames
    }

    /** One concealment frame through the normal path (used to fade out before leaving PLAYING). */
    private fun writeConcealment() {
        fillConcealment(pcm)
        val n = resampler.process(pcm, FRAME, 1.0, resampled)
        sink.write(resampled, n)
        written += n
    }

    /**
     * Cheap, click-free loss concealment. The platform Opus decoders expose no PLC, so for the first
     * missing frame we play the last few milliseconds backwards while fading to zero: the waveform
     * stays continuous (it starts on the very sample we stopped at) and simply dies away.
     */
    private fun fillConcealment(out: FloatArray) {
        java.util.Arrays.fill(out, 0f)
        if (haveTail) {
            for (i in 0 until FADE) {
                val gain = 1f - (i + 1).toFloat() / FADE
                val src = (FADE - 1 - i) * CH
                for (c in 0 until CH) out[i * CH + c] = tail[src + c] * gain
            }
            haveTail = false
        }
        fadeIn = true
    }

    private fun applyFadeIn(buf: FloatArray) {
        for (i in 0 until FADE) {
            val gain = i.toFloat() / FADE
            for (c in 0 until CH) buf[i * CH + c] *= gain
        }
    }

    companion object {
        const val FRAME = Proto.FRAME_SAMPLES
        const val CH = Proto.CHANNELS
        const val OUT_CAPACITY = 1024
        const val FADE = 240 // 5 ms

        const val MIN_START_PACKETS = 2
        const val MIN_CUSHION_US = 40_000L
        const val DISCONTINUITY_US = 5_000L
        const val HARD_RESYNC_MS = 30.0
        const val HARD_RESYNC_FRAMES = 15
        const val ERROR_SMOOTHING = 0.08
        const val SPEED_GAIN_PER_MS = 0.0005 // 1 ms of error -> 0.05 % speed change (time constant ~2 s)
        const val MAX_SPEED_ADJUST = 0.005
        const val UNDERFLOW_FRAMES = 4
        const val STALL_FRAMES = 10
        const val CUSHION_WINDOW_FRAMES = 250 // 5 s
        const val LATE_BIAS_HYSTERESIS_MS = 30
        const val LATE_BIAS_MIN_STEP_MS = 20
        const val LEVEL_DECAY = 0.9f // per 20 ms frame
        const val RESAMPLER_DELAY_NS = Resampler.DELAY_FRAMES * 1_000_000_000L / Proto.SAMPLE_RATE
        private const val NO_TS = Long.MIN_VALUE
    }
}
