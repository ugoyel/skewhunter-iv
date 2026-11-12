import Foundation

/// A (frame position, time) pair: frame number `framePosition` reaches the listener at `timeNs`.
struct SinkTimestamp {
    var framePosition: Int64 = 0
    var timeNs: Int64 = 0
}

/// The audio output as the engine sees it. On iOS this is a ring buffer drained by the
/// AVAudioEngine render callback; on Android it wraps an AudioTrack.
protocol AudioSink: AnyObject {
    /// Queue `frames` interleaved stereo frames for playback.
    func write(_ interleaved: [Float], frames: Int)

    /// Report when a recently queued frame is (or was) actually heard, in the local monotonic clock
    /// and including output latency. Frame positions count frames passed to `write`. Returns false
    /// while the output cannot tell yet.
    func getTimestamp(_ out: inout SinkTimestamp) -> Bool
}

/// Decodes one 20 ms Opus packet into 960 interleaved stereo float frames.
protocol OpusFrameDecoder: AnyObject {
    func decode(_ packet: [UInt8], length: Int, into out: inout [Float]) -> Bool
}

/// Timestamp-driven playout: decides, 20 ms at a time, exactly which samples go to the audio output
/// so that every frame is *heard* at
///
///     hostCaptureTime + playoutDelay + userOffset      (expressed in this device's clock)
///
/// waiting   output is being primed / no stream yet; writes silence.
/// aligning  picks the frame that is due and pads with silence, to the sample, until it is due.
/// playing   plays frames in order and measures, for every frame, when it will really be heard
///           versus when it should be. That error steers a resampler by up to +/-0.5 % (inaudible),
///           which absorbs the drift between the host's capture clock, the network clock estimate
///           and this device's DAC clock. Errors too large to steer away trigger a re-align.
///
/// Single-threaded: only the playout thread calls `pump`. Settings and `snapshot` are thread-safe.
///
/// Mirrors android/.../core/PlayoutEngine.kt line for line; the same simulation tests run on both.
final class PlayoutEngine {
    enum State { case waiting, aligning, playing }

    struct Snapshot {
        var state: State = .waiting
        /// Smoothed "heard too late (+) / too early (-)" error while playing, milliseconds.
        var syncErrorMs: Float = 0
        /// Current speed correction in parts per million.
        var speedPpm = 0
        /// Extra delay this device had to add because the audio it needed had not arrived yet
        /// (output latency + negative offset larger than the host's playout delay allows).
        var lateBiasMs = 0
        /// How much received audio lies ahead of the play cursor, milliseconds.
        var cushionMs = 0
        var playedFrames: Int64 = 0
        var concealedFrames: Int64 = 0
        var realignCount: Int64 = 0
        /** Peak level of the audio being played, 0...1, with a short decay. Drives the visualizer. */
        var level: Float = 0
    }

    static let frame = Proto.frameSamples
    static let ch = Proto.channels
    static let outCapacity = 1024
    static let fade = 240 // 5 ms

    static let minStartPackets = 2
    static let minCushionUs: Int64 = 40_000
    static let discontinuityUs: Int64 = 5_000
    static let hardResyncMs = 30.0
    static let hardResyncFrames = 15
    static let errorSmoothing = 0.08
    static let speedGainPerMs = 0.0005 // 1 ms of error -> 0.05 % speed change (time constant ~2 s)
    static let maxSpeedAdjust = 0.005
    static let underflowFrames = 4
    static let stallFrames = 10
    static let cushionWindowFrames = 250 // 5 s
    static let lateBiasHysteresisMs = 30
    static let lateBiasMinStepMs = 20
    static let levelDecay: Float = 0.9 // per 20 ms frame
    static let resamplerDelayNs = Int64(Resampler.delayFrames) * 1_000_000_000 / Int64(Proto.sampleRate)
    private static let noTs = Int64.min

    private let sink: AudioSink
    private let decoder: OpusFrameDecoder
    private let jitter: JitterBuffer
    private let clock: ClockSync

    // Shared with other threads, guarded by `lock`.
    private let lock = NSLock()
    private var sharedPlayoutDelayMs = 350
    private var sharedUserOffsetMs = 0
    private var realignRequest = 0 // 0 none, 1 realign, 2 realign + drop late bias, 3 new stream
    private var shared = Snapshot()

    // Playout-thread state.
    private var playoutDelayMs = 350
    private var userOffsetMs = 0
    private var state = State.waiting
    private var syncErrorMs: Float = 0
    private var speedPpm = 0
    private var lateBiasMs = 0
    private var cushionMs = 0
    private var playedFrames: Int64 = 0
    private var concealedFrames: Int64 = 0
    private var realignCount: Int64 = 0
    private var outputLevel: Float = 0

    private var ts = SinkTimestamp()
    private let packet = JitterPacket()
    private var pcm = [Float](repeating: 0, count: PlayoutEngine.frame * PlayoutEngine.ch)
    private var resampled = [Float](repeating: 0, count: PlayoutEngine.outCapacity * PlayoutEngine.ch)
    private let silence = [Float](repeating: 0, count: PlayoutEngine.frame * PlayoutEngine.ch)
    private var tail = [Float](repeating: 0, count: PlayoutEngine.fade * PlayoutEngine.ch)
    private let resampler = Resampler(channels: PlayoutEngine.ch, maxInputFrames: PlayoutEngine.frame)

    private var written: Int64 = 0
    private var nextSeq: Int64 = -1
    private var lastTimestampUs = PlayoutEngine.noTs
    private var haveTail = false
    private var fadeIn = false
    private var smoothedErrMs = 0.0
    private var speed = 1.0
    private var hardRun = 0
    private var missRun = 0
    private var underflowRun = 0
    private var windowFrames = 0
    private var windowMinCushionUs = Int64.max

    init(sink: AudioSink, decoder: OpusFrameDecoder, jitter: JitterBuffer, clock: ClockSync) {
        self.sink = sink
        self.decoder = decoder
        self.jitter = jitter
        self.clock = clock
    }

    // MARK: settings (any thread)

    /// Common delay chosen by the host; identical on every listener.
    func setPlayoutDelayMs(_ value: Int) {
        lock.lock()
        if sharedPlayoutDelayMs != value {
            sharedPlayoutDelayMs = value
            realignRequest = max(realignRequest, 2)
        }
        lock.unlock()
    }

    /// Per-device fine tuning (the "Bluetooth sync" slider). Positive plays later.
    func setUserOffsetMs(_ value: Int) {
        lock.lock()
        if sharedUserOffsetMs != value {
            sharedUserOffsetMs = value
            realignRequest = max(realignRequest, 2)
        }
        lock.unlock()
    }

    /// Re-run alignment at the next frame (settings changed, or the user asked to re-sync).
    func requestRealign(resetLateBias: Bool) {
        lock.lock()
        realignRequest = max(realignRequest, resetLateBias ? 2 : 1)
        lock.unlock()
    }

    /// Forget the stream entirely (the host started a new session).
    func requestStreamReset() {
        lock.lock()
        realignRequest = 3
        lock.unlock()
    }

    func snapshot() -> Snapshot {
        lock.lock()
        defer { lock.unlock() }
        return shared
    }

    // MARK: playout thread

    /// Do one step of work: queues roughly 20 ms of audio on the sink.
    func pump() {
        lock.lock()
        let request = realignRequest
        realignRequest = 0
        playoutDelayMs = sharedPlayoutDelayMs
        userOffsetMs = sharedUserOffsetMs
        lock.unlock()

        step(request)

        lock.lock()
        shared = Snapshot(
            state: state, syncErrorMs: syncErrorMs, speedPpm: speedPpm, lateBiasMs: lateBiasMs, cushionMs: cushionMs,
            playedFrames: playedFrames, concealedFrames: concealedFrames, realignCount: realignCount, level: outputLevel)
        lock.unlock()
    }

    private func step(_ request: Int) {
        if request == 3 {
            resetStream()
        } else if request != 0 {
            if request == 2 { lateBiasMs = 0 }
            if state == .playing {
                writeConcealment() // fade out instead of cutting off
                enter(.aligning)
                return
            }
        }
        switch state {
        case .waiting: pumpWaiting()
        case .aligning: pumpAligning()
        case .playing: pumpPlaying()
        }
    }

    private func resetStream() {
        nextSeq = -1
        lastTimestampUs = PlayoutEngine.noTs
        lateBiasMs = 0
        haveTail = false
        enter(.waiting)
    }

    private func enter(_ next: State) {
        if next != .playing {
            speed = 1.0
            speedPpm = 0
            syncErrorMs = 0
            outputLevel = 0
        }
        if next == .aligning && state == .playing { realignCount += 1 }
        state = next
    }

    private func pumpWaiting() {
        writeSilence(PlayoutEngine.frame)
        if clock.synced && jitter.size >= PlayoutEngine.minStartPackets && sink.getTimestamp(&ts) { enter(.aligning) }
    }

    /// Local time (ns) at which the next frame handed to the sink will be heard.
    private func presentationNs() -> Int64 {
        ts.timeNs + (written - ts.framePosition) * 1_000_000_000 / Int64(Proto.sampleRate)
    }

    private func totalDelayUs() -> Int64 { Int64(playoutDelayMs + userOffsetMs + lateBiasMs) * 1000 }

    private func pumpAligning() {
        let newestTs = jitter.newestTimestampUs()
        if !clock.synced || newestTs < 0 || !sink.getTimestamp(&ts) {
            writeSilence(PlayoutEngine.frame)
            enter(.waiting)
            return
        }
        // Media time (host clock) that must be heard at the moment the next written frame plays.
        var mediaUs = clock.localToHostUs(presentationNs() / 1000) - totalDelayUs()

        // We can only play what has arrived. If the frame that is due has not been received yet
        // (plus a little headroom for network jitter), this device cannot meet the host's playout
        // delay; fall back by exactly as much as is missing and report it.
        let cushionUs = newestTs - mediaUs
        if cushionUs < PlayoutEngine.minCushionUs {
            let addMs = Int((Double(PlayoutEngine.minCushionUs - cushionUs) / 1000.0).rounded(.up))
            lateBiasMs += addMs
            mediaUs -= Int64(addMs) * 1000
        }

        let seq = jitter.firstAtOrAfter(mediaUs)
        if seq < 0 {
            writeSilence(PlayoutEngine.frame)
            return
        }
        let padFrames = (jitter.timestampOf(seq) - mediaUs) * Int64(Proto.sampleRate) / 1_000_000
        if padFrames >= Int64(PlayoutEngine.frame) {
            writeSilence(PlayoutEngine.frame) // not due yet; look again after this block
            return
        }
        if padFrames > 0 { writeSilence(Int(padFrames)) }

        jitter.dropBelow(seq)
        nextSeq = seq
        cushionMs = max(0, Int((newestTs - mediaUs) / 1000))
        lastTimestampUs = PlayoutEngine.noTs
        resampler.reset()
        haveTail = false
        fadeIn = true
        smoothedErrMs = 0
        speed = 1.0
        hardRun = 0
        missRun = 0
        underflowRun = 0
        windowFrames = 0
        windowMinCushionUs = Int64.max
        enter(.playing)
    }

    private func pumpPlaying() {
        let frameTs = jitter.timestampOf(nextSeq)

        if frameTs >= 0 {
            // The host's timeline jumped (it paused, or dropped audio): line up again from scratch.
            if lastTimestampUs != PlayoutEngine.noTs
                && abs(frameTs - (lastTimestampUs + Proto.frameUs)) > PlayoutEngine.discontinuityUs {
                writeConcealment()
                enter(.aligning)
                return
            }
            if sink.getTimestamp(&ts) && clock.synced {
                let heardNs = presentationNs() + PlayoutEngine.resamplerDelayNs
                let dueNs = clock.hostToLocalUs(frameTs) * 1000 + totalDelayUs() * 1000
                let errMs = Double(heardNs - dueNs) / 1e6
                if abs(errMs) > PlayoutEngine.hardResyncMs {
                    hardRun += 1
                    if hardRun >= PlayoutEngine.hardResyncFrames {
                        writeConcealment()
                        enter(.aligning)
                        return
                    }
                } else {
                    hardRun = 0
                }
                let bounded = min(max(errMs, -PlayoutEngine.hardResyncMs), PlayoutEngine.hardResyncMs)
                smoothedErrMs += (bounded - smoothedErrMs) * PlayoutEngine.errorSmoothing
                // Late (positive error) -> consume input faster to catch up.
                let adjust = min(max(smoothedErrMs * PlayoutEngine.speedGainPerMs, -PlayoutEngine.maxSpeedAdjust), PlayoutEngine.maxSpeedAdjust)
                speed = 1.0 + adjust
                syncErrorMs = Float(smoothedErrMs)
                speedPpm = Int((adjust * 1e6).rounded())
            }
        }

        let decoded = frameTs >= 0 && jitter.take(nextSeq, into: packet)
            && decoder.decode(packet.data, length: packet.length, into: &pcm)
        if decoded {
            if fadeIn {
                applyFadeIn()
                fadeIn = false
            }
            let tailStart = (PlayoutEngine.frame - PlayoutEngine.fade) * PlayoutEngine.ch
            for i in 0..<(PlayoutEngine.fade * PlayoutEngine.ch) { tail[i] = pcm[tailStart + i] }
            haveTail = true
            var peak: Float = 0
            var i = 0
            while i < pcm.count { // every 8th frame is plenty for a meter
                let v = abs(pcm[i])
                if v > peak { peak = v }
                i += 16
            }
            outputLevel = max(min(peak, 1), outputLevel * PlayoutEngine.levelDecay)
            lastTimestampUs = frameTs
            missRun = 0
            underflowRun = 0
            playedFrames += 1
            trackCushion(frameTs)
        } else {
            fillConcealment()
            outputLevel *= PlayoutEngine.levelDecay
            if lastTimestampUs != PlayoutEngine.noTs { lastTimestampUs += Proto.frameUs }
            concealedFrames += 1
            missRun += 1
            // Nothing at or beyond this frame has arrived: we are consuming faster than the network delivers.
            if jitter.newestSeq < nextSeq { underflowRun += 1 } else { underflowRun = 0 }
        }

        let n = resampler.process(pcm, inFrames: PlayoutEngine.frame, speed: speed, out: &resampled)
        sink.write(resampled, frames: n)
        written += Int64(n)
        nextSeq += 1

        if underflowRun >= PlayoutEngine.underflowFrames || missRun >= PlayoutEngine.stallFrames {
            // Ran dry. Go quiet and line up again once audio is flowing; the cushion check in
            // pumpAligning() will add late bias if this device simply cannot keep up.
            enter(.waiting)
        }
    }

    /// Every few seconds, hand back late bias that the measured headroom shows is no longer needed.
    private func trackCushion(_ frameTs: Int64) {
        let newest = jitter.newestTimestampUs()
        let cushionUs = newest >= 0 ? newest - frameTs : 0
        if cushionUs < windowMinCushionUs { windowMinCushionUs = cushionUs }
        windowFrames += 1
        if windowFrames < PlayoutEngine.cushionWindowFrames { return }
        cushionMs = Int(windowMinCushionUs / 1000)
        if lateBiasMs > 0 {
            let spareMs = cushionMs - Int(PlayoutEngine.minCushionUs / 1000) - PlayoutEngine.lateBiasHysteresisMs
            if spareMs >= min(lateBiasMs, PlayoutEngine.lateBiasMinStepMs) {
                lateBiasMs -= min(lateBiasMs, spareMs)
                requestRealign(resetLateBias: false)
            }
        }
        windowFrames = 0
        windowMinCushionUs = Int64.max
    }

    private func writeSilence(_ frames: Int) {
        sink.write(silence, frames: frames)
        written += Int64(frames)
    }

    /// One concealment frame through the normal path (used to fade out before leaving `playing`).
    private func writeConcealment() {
        fillConcealment()
        let n = resampler.process(pcm, inFrames: PlayoutEngine.frame, speed: 1.0, out: &resampled)
        sink.write(resampled, frames: n)
        written += Int64(n)
    }

    /// Cheap, click-free loss concealment. The platform Opus decoders expose no PLC, so for the first
    /// missing frame we play the last few milliseconds backwards while fading to zero: the waveform
    /// stays continuous (it starts on the very sample we stopped at) and simply dies away.
    private func fillConcealment() {
        let ch = PlayoutEngine.ch
        let fade = PlayoutEngine.fade
        for i in 0..<pcm.count { pcm[i] = 0 }
        if haveTail {
            for i in 0..<fade {
                let gain = 1 - Float(i + 1) / Float(fade)
                let src = (fade - 1 - i) * ch
                for c in 0..<ch { pcm[i * ch + c] = tail[src + c] * gain }
            }
            haveTail = false
        }
        fadeIn = true
    }

    private func applyFadeIn() {
        let ch = PlayoutEngine.ch
        let fade = PlayoutEngine.fade
        for i in 0..<fade {
            let gain = Float(i) / Float(fade)
            for c in 0..<ch { pcm[i * ch + c] *= gain }
        }
    }
}
