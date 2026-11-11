import XCTest
@testable import HearbyCore

/// End-to-end simulation of one listener: a host producing timestamped frames on its own clocks, a
/// lossy/jittery network, an NTP exchange, and an audio output with its own latency and DAC drift.
///
/// The fake decoder writes the media position into the samples themselves, so the fake output can
/// check, for every sample it "plays", how far the moment it is heard is from the moment it should
/// be heard. Port of android/.../core/PlayoutEngineSimTest.kt.
final class PlayoutEngineSimTests: XCTestCase {

    struct Params {
        var seed: UInt64
        var playoutDelayMs = 350
        var clientClockOffsetUs: Int64 = -3_200_000_000
        var clientClockDriftPpm = 0.0
        var sinkDriftPpm = 0.0
        var sinkLatencyUs: Int64 = 45_000
        var hostAudioDriftPpm = 0.0
        var loss = 0.0
        var netBaseUs = 1_500
        var netJitterUs = 3_000
        var spikeProbability = 0.0
        var spikeUs = 0
        var pauseAtSeq = Int64.max
        var pauseNs: Int64 = 0
    }

    final class Sim: AudioSink, OpusFrameDecoder {
        static let hostEpochUs: Int64 = 7_000_000_000
        static let blockFrames: Int64 = 64 // position ramp repeats every 1.28 s
        static let sum: Float = 71_000
        static let ringFrames = 8192
        static let sinkBuffer = 3840 // 80 ms, like a typical AudioTrack

        let p: Params
        var rng: SeededRandom
        let jitter = JitterBuffer()
        let clock = ClockSync()
        private(set) var engine: PlayoutEngine!
        var nowNs: Int64 = 0

        init(_ p: Params) {
            self.p = p
            rng = SeededRandom(seed: p.seed)
            frameNs = 20_000_000.0 * (1 + p.hostAudioDriftPpm * 1e-6)
            periodNs = 1e9 / (48_000.0 * (1 + p.sinkDriftPpm * 1e-6))
            engine = PlayoutEngine(sink: self, decoder: self, jitter: jitter, clock: clock)
            engine.setPlayoutDelayMs(p.playoutDelayMs)
            scheduleSync(atNs: 50_000_000, index: 0)
        }

        // MARK: event queue (sorted insert; the queue stays short)

        private struct Event {
            let timeNs: Int64
            let order: Int
            let action: () -> Void
        }

        private var events: [Event] = []
        private var order = 0

        func at(_ timeNs: Int64, _ action: @escaping () -> Void) {
            let event = Event(timeNs: timeNs, order: order, action: action)
            order += 1
            var i = events.count
            while i > 0 && events[i - 1].timeNs > timeNs { i -= 1 }
            events.insert(event, at: i)
        }

        func advance(to toNs: Int64) {
            generateHostFrames(toNs)
            while let first = events.first, first.timeNs <= toNs {
                events.removeFirst()
                advanceSink(first.timeNs)
                nowNs = first.timeNs
                first.action()
            }
            advanceSink(toNs)
            nowNs = toNs
        }

        func run(until untilNs: Int64) {
            while nowNs < untilNs { engine.pump() }
        }

        // MARK: clocks

        func hostClockUs(_ trueNs: Int64) -> Int64 { Sim.hostEpochUs + trueNs / 1000 }

        func clientClockNs(_ trueNs: Int64) -> Int64 {
            Int64((Double(trueNs) * (1 + p.clientClockDriftPpm * 1e-6)).rounded()) + p.clientClockOffsetUs * 1000
        }

        private func netDelayNs() -> Int64 {
            var us = p.netBaseUs + rng.int(p.netJitterUs + 1)
            if p.spikeProbability > 0 && rng.unit() < p.spikeProbability { us += rng.int(p.spikeUs + 1) }
            return Int64(us) * 1000
        }

        // MARK: host

        private let frameNs: Double
        private var nextHostSeq: Int64 = 0

        func captureStartNs(_ seq: Int64) -> Int64 {
            Int64((Double(seq) * frameNs).rounded()) + (seq >= p.pauseAtSeq ? p.pauseNs : 0)
        }

        func hostTimestampUs(_ seq: Int64) -> Int64 { hostClockUs(captureStartNs(seq)) }

        private func generateHostFrames(_ toNs: Int64) {
            while true {
                let seq = nextHostSeq
                let sendNs = captureStartNs(seq) + 23_000_000 // whole frame captured, +3 ms to encode
                if sendNs > toNs { return }
                nextHostSeq += 1
                if rng.unit() < p.loss { continue }
                at(sendNs + netDelayNs()) { [unowned self] in
                    var payload = [UInt8](repeating: 0, count: 8)
                    Wire.putU64(&payload, 0, UInt64(seq))
                    self.jitter.put(
                        seq32: UInt32(truncatingIfNeeded: seq), timestampUs: self.hostTimestampUs(seq),
                        src: payload, offset: 0, length: 8)
                }
            }
        }

        private func scheduleSync(atNs: Int64, index: Int) {
            at(atNs) { [unowned self] in
                let t0 = self.clientClockNs(self.nowNs) / 1000
                let hostRxNs = self.nowNs + self.netDelayNs()
                let t1 = self.hostClockUs(hostRxNs)
                let t2 = t1 + 120
                self.at(hostRxNs + 120_000 + self.netDelayNs()) { [unowned self] in
                    self.clock.addSample(t0Us: t0, t1Us: t1, t2Us: t2, t3Us: self.clientClockNs(self.nowNs) / 1000)
                }
                self.scheduleSync(atNs: self.nowNs + (index < 8 ? 40_000_000 : 1_000_000_000), index: index + 1)
            }
        }

        // MARK: decoder: encodes "where in the stream am I" into the samples

        func decode(_ packet: [UInt8], length: Int, into out: inout [Float]) -> Bool {
            let seq = Int64(Wire.getU64(packet, 0))
            let base = (seq % Sim.blockFrames) * 960
            for i in 0..<960 {
                let pos = Float(base + Int64(i))
                out[2 * i] = pos + 1000
                out[2 * i + 1] = Sim.sum - 1000 - pos
            }
            return true
        }

        // MARK: audio output: fixed latency, its own sample clock

        private var ring = [Float](repeating: 0, count: Sim.ringFrames * 2)
        private var consumed: Int64 = 0
        private var written: Int64 = 0
        private let periodNs: Double
        private var nextConsumeNs = 0.0
        private var slots: Int64 = 0
        private var tsPosition: Int64 = -1
        private var tsTimeNs: Int64 = 0
        private(set) var underruns: Int64 = 0

        private func advanceSink(_ toNs: Int64) {
            while nextConsumeNs <= Double(toNs) {
                if slots % 480 == 0 { // the "mixer" publishes a timestamp every 10 ms, with noise
                    tsPosition = consumed
                    tsTimeNs = clientClockNs(Int64(nextConsumeNs)) + p.sinkLatencyUs * 1000 + Int64(rng.int(401) - 200) * 1000
                }
                slots += 1
                if consumed < written {
                    let i = Int(consumed % Int64(Sim.ringFrames)) * 2
                    observe(ring[i], ring[i + 1], heardNs: Int64(nextConsumeNs) + p.sinkLatencyUs * 1000)
                    consumed += 1
                } else if written > 0 {
                    underruns += 1
                }
                nextConsumeNs += periodNs
            }
        }

        func write(_ interleaved: [Float], frames: Int) {
            var done = 0
            while done < frames {
                let room = Sim.sinkBuffer - Int(written - consumed)
                if room <= 0 {
                    advance(to: nowNs + 1_000_000) // blocking write: time passes
                    continue
                }
                let n = min(room, frames - done)
                for k in 0..<n {
                    let i = Int((written + Int64(k)) % Int64(Sim.ringFrames)) * 2
                    ring[i] = interleaved[2 * (done + k)]
                    ring[i + 1] = interleaved[2 * (done + k) + 1]
                }
                written += Int64(n)
                done += n
            }
        }

        func getTimestamp(_ out: inout SinkTimestamp) -> Bool {
            if tsPosition < 0 || nowNs < 150_000_000 { return false }
            out.framePosition = tsPosition
            out.timeNs = tsTimeNs
            return true
        }

        // MARK: measurement

        private(set) var sampleTimes: [Int64] = []
        private(set) var sampleErrMs: [Float] = []
        private var decimate = 0
        private var previousPos = Double.nan
        private var cleanRun = 0

        /// Called for every frame at the instant it becomes audible.
        private func observe(_ l: Float, _ r: Float, heardNs: Int64) {
            let pos = Double(l - 1000)
            // The two channels sum to a constant only for untouched audio (not silence, fades or
            // concealment), and the position must have advanced by about one frame per frame for a
            // while, which excludes the filter ringing around every wrap of the position ramp.
            let clean = abs(l + r - Sim.sum) < 0.05 && abs(pos - previousPos - 1.0) < 0.02
            previousPos = pos
            cleanRun = clean ? cleanRun + 1 : 0
            if cleanRun < 48 { return }
            decimate += 1
            if decimate % 16 != 0 { return }

            // Where the stream *should* be right now (zero user offset), in frames.
            var mediaTrueNs = heardNs - Int64(p.playoutDelayMs) * 1_000_000
            if p.pauseNs > 0 {
                let resume = captureStartNs(p.pauseAtSeq)
                if mediaTrueNs >= resume {
                    mediaTrueNs -= p.pauseNs
                } else if mediaTrueNs >= resume - p.pauseNs {
                    return // inside the host's pause
                }
            }
            let expected = Double(mediaTrueNs) / frameNs
            let within = pos / 960.0
            let block = ((expected - within) / Double(Sim.blockFrames)).rounded()
            let actual = block * Double(Sim.blockFrames) + within
            sampleTimes.append(heardNs)
            sampleErrMs.append(Float((expected - actual) * frameNs / 1e6))
        }

        /// Largest |lateness - expected| among samples heard in [fromNs, toNs).
        func worstDeviationMs(from fromNs: Int64, to toNs: Int64, expectedMs: Double = 0) -> Double {
            var worst = 0.0
            var n = 0
            for i in 0..<sampleTimes.count where sampleTimes[i] >= fromNs && sampleTimes[i] < toNs {
                worst = max(worst, abs(Double(sampleErrMs[i]) - expectedMs))
                n += 1
            }
            XCTAssertGreaterThan(n, 100, "no audio was heard in the window")
            return worst
        }
    }

    private func seconds(_ s: Double) -> Int64 { Int64(s * 1e9) }

    func testLocksToTheHostTimelineDespiteClockAndDacDrift() {
        let sim = Sim(Params(seed: 11, clientClockDriftPpm: 35, sinkDriftPpm: -80, hostAudioDriftPpm: 25))
        sim.run(until: seconds(60))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(8), to: seconds(60)), 1.5)
        let snap = sim.engine.snapshot()
        XCTAssertEqual(snap.state, .playing)
        XCTAssertEqual(snap.concealedFrames, 0)
        XCTAssertEqual(snap.realignCount, 0)
        XCTAssertEqual(snap.lateBiasMs, 0)
        XCTAssertEqual(sim.underruns, 0)
        XCTAssertLessThan(abs(snap.speedPpm), 400)
    }

    func testASecondListenerWithVeryDifferentHardwareLandsOnTheSameTimeline() {
        // e.g. AirPods: far more output latency, clocks drifting the other way
        let sim = Sim(Params(
            seed: 12, clientClockOffsetUs: 91_000_000_000, clientClockDriftPpm: -60,
            sinkDriftPpm: 110, sinkLatencyUs: 180_000, hostAudioDriftPpm: 25))
        sim.run(until: seconds(60))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(8), to: seconds(60)), 1.5)
        XCTAssertEqual(sim.engine.snapshot().lateBiasMs, 0)
        XCTAssertEqual(sim.engine.snapshot().concealedFrames, 0)
    }

    func testHoldsSyncThroughPacketLossAndDelaySpikes() {
        let sim = Sim(Params(
            seed: 13, clientClockDriftPpm: 20, sinkDriftPpm: 50, loss: 0.03, spikeProbability: 0.02, spikeUs: 60_000))
        sim.run(until: seconds(60))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(8), to: seconds(60)), 2.0)
        let snap = sim.engine.snapshot()
        let share = Double(snap.concealedFrames) / Double(snap.playedFrames + snap.concealedFrames)
        XCTAssertGreaterThan(share, 0.01)
        XCTAssertLessThan(share, 0.06)
        XCTAssertEqual(snap.lateBiasMs, 0, "loss must not push the device late")
        XCTAssertEqual(snap.realignCount, 0)
    }

    func testFallsBackByExactlyWhatIsMissingWhenOutputLatencyExceedsTheBudget() {
        // 300 ms of output latency cannot fit in a 150 ms playout delay.
        let sim = Sim(Params(seed: 14, playoutDelayMs: 150, sinkDriftPpm: 30, sinkLatencyUs: 300_000))
        sim.run(until: seconds(40))
        let bias = sim.engine.snapshot().lateBiasMs
        XCTAssertTrue((230...340).contains(bias), "late bias \(bias) ms")
        // It is late by what it reports, and rock steady there.
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(20), to: seconds(40), expectedMs: Double(bias)), 1.5)
        XCTAssertEqual(sim.engine.snapshot().state, .playing)
    }

    func testRealignsAfterTheHostPauses() {
        let sim = Sim(Params(seed: 15, sinkDriftPpm: -40, pauseAtSeq: 1000, pauseNs: seconds(2.5)))
        sim.run(until: seconds(50))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(8), to: seconds(19)), 1.5)
        // The pause ends at 22.5 s of capture time; allow one second to line up again.
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(24), to: seconds(50)), 1.5)
        XCTAssertEqual(sim.engine.snapshot().lateBiasMs, 0)
    }

    func testTheUserOffsetMovesPlayoutByExactlyThatMuch() {
        let sim = Sim(Params(seed: 16, clientClockDriftPpm: -25, sinkDriftPpm: 60))
        sim.run(until: seconds(20))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(8), to: seconds(20)), 1.5)

        sim.engine.setUserOffsetMs(120) // play later
        sim.run(until: seconds(40))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(21), to: seconds(40), expectedMs: 120), 1.5)

        sim.engine.setUserOffsetMs(-80) // play earlier (compensating Bluetooth lag)
        sim.run(until: seconds(60))
        XCTAssertLessThan(sim.worstDeviationMs(from: seconds(41), to: seconds(60), expectedMs: -80), 1.5)
        XCTAssertEqual(sim.engine.snapshot().lateBiasMs, 0)
    }
}
