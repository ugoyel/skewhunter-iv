import XCTest
@testable import HearbyCore

final class ClockSyncTests: XCTestCase {

    func testRecoversOffsetDespiteAsymmetricJitter() {
        var rng = SeededRandom(seed: 1)
        let trueOffset: Int64 = 987_654_321 // host - client
        let sync = ClockSync()
        var clientNow: Int64 = 5_000_000
        for _ in 0..<40 {
            let up = Int64(800 + rng.int(6000))   // us, client -> host
            let down = Int64(800 + rng.int(6000)) // us, host -> client
            let t0 = clientNow
            let t1 = t0 + up + trueOffset
            let t2 = t1 + 150
            let t3 = t0 + up + 150 + down
            XCTAssertTrue(sync.addSample(t0Us: t0, t1Us: t1, t2Us: t2, t3Us: t3))
            clientNow += 1_000_000
        }
        XCTAssertTrue(sync.synced)
        // Error is bounded by half the asymmetry of the best exchange in the window.
        XCTAssertLessThan(abs(sync.offsetUs - trueOffset), 1500)
        XCTAssertEqual(sync.hostToLocalUs(sync.localToHostUs(42)), 42)
    }

    func testStepsImmediatelyWhenClocksJump() {
        let sync = ClockSync()
        for i in 0..<5 {
            let t0 = Int64(i) * 1000
            sync.addSample(t0Us: t0, t1Us: t0 + 1500, t2Us: t0 + 1500, t3Us: t0 + 1000)
        }
        XCTAssertLessThan(abs(sync.offsetUs - 1000), 50)
        for i in 0..<14 {
            let t0 = 100_000 + Int64(i) * 1000
            sync.addSample(t0Us: t0, t1Us: t0 + 400 + 10_001_000, t2Us: t0 + 400 + 10_001_000, t3Us: t0 + 800)
        }
        XCTAssertLessThan(abs(sync.offsetUs - 10_001_000), 50)
    }

    func testIgnoresImpossibleSamples() {
        let sync = ClockSync()
        XCTAssertFalse(sync.addSample(t0Us: 1000, t1Us: 5000, t2Us: 9000, t3Us: 2000))
        XCTAssertFalse(sync.addSample(t0Us: 0, t1Us: 0, t2Us: 0, t3Us: 5_000_000))
        XCTAssertEqual(sync.sampleCount, 0)
        XCTAssertFalse(sync.synced)
    }
}

final class JitterBufferTests: XCTestCase {
    private let payload = [UInt8](repeating: 7, count: 40)

    @discardableResult
    private func put(_ jb: JitterBuffer, _ seq: UInt32) -> Bool {
        jb.put(seq32: seq, timestampUs: Int64(seq) * 20_000, src: payload, offset: 0, length: payload.count)
    }

    func testReordersAndReportsLoss() {
        let jb = JitterBuffer()
        let out = JitterPacket()
        for s: UInt32 in [10, 12, 11, 14] { XCTAssertTrue(put(jb, s)) } // 13 lost, 11/12 swapped
        XCTAssertFalse(put(jb, 12))                                     // duplicate
        XCTAssertEqual(jb.size, 4)
        XCTAssertEqual(jb.newestSeq, 14)
        XCTAssertEqual(jb.newestTimestampUs(), 280_000)
        XCTAssertEqual(jb.firstAtOrAfter(205_000), 11)
        XCTAssertEqual(jb.firstAtOrAfter(245_000), 14) // skips the hole at 13
        XCTAssertEqual(jb.firstAtOrAfter(300_000), -1)

        jb.dropBelow(11)
        XCTAssertFalse(put(jb, 10)) // now too late
        XCTAssertEqual(jb.lateDrops, 1)
        XCTAssertTrue(jb.take(11, into: out))
        XCTAssertEqual(out.timestampUs, 220_000)
        XCTAssertEqual(out.length, 40)
        XCTAssertEqual(out.data[39], 7)
        XCTAssertTrue(jb.take(12, into: out))
        XCTAssertFalse(jb.take(13, into: out))
        XCTAssertEqual(jb.timestampOf(13), -1)
        XCTAssertTrue(jb.take(14, into: out))
        XCTAssertEqual(jb.size, 0)
        XCTAssertEqual(jb.newestTimestampUs(), -1)
    }

    func testFollowsSequenceWrapAround() {
        let jb = JitterBuffer()
        let out = JitterPacket()
        XCTAssertTrue(put(jb, 0xFFFF_FFFE))
        XCTAssertTrue(put(jb, 0xFFFF_FFFF))
        XCTAssertTrue(put(jb, 0)) // wrapped
        XCTAssertTrue(put(jb, 1))
        XCTAssertEqual(jb.newestSeq, 0x1_0000_0001)
        XCTAssertTrue(jb.take(0xFFFF_FFFE, into: out))
        XCTAssertTrue(jb.take(0xFFFF_FFFF, into: out))
        XCTAssertTrue(jb.take(0x1_0000_0000, into: out))
        XCTAssertTrue(jb.take(0x1_0000_0001, into: out))
    }

    func testOldFramesExpireWhenTheWindowMovesOn() {
        let jb = JitterBuffer(capacity: 16)
        let out = JitterPacket()
        for s: UInt32 in 0..<16 { put(jb, s) }
        XCTAssertEqual(jb.size, 16)
        for s: UInt32 in 16..<24 { put(jb, s) } // overwrites 0..7
        XCTAssertEqual(jb.size, 16)
        XCTAssertFalse(jb.take(3, into: out))
        XCTAssertTrue(jb.take(8, into: out))
        XCTAssertEqual(jb.firstAtOrAfter(0), 9)
    }

    func testRestartsOnAHugeJump() {
        let jb = JitterBuffer()
        for s: UInt32 in 1000..<1010 { put(jb, s) }
        XCTAssertTrue(put(jb, 5)) // host restarted and counts from the beginning again
        XCTAssertEqual(jb.size, 1)
        XCTAssertEqual(jb.newestSeq, 5)
        XCTAssertTrue(put(jb, 6))
        XCTAssertEqual(jb.size, 2)
    }

    func testRejectsOversizedPayloads() {
        let jb = JitterBuffer()
        XCTAssertFalse(jb.put(seq32: 1, timestampUs: 0, src: [UInt8](repeating: 0, count: 2000), offset: 0, length: 2000))
        XCTAssertFalse(jb.put(seq32: 1, timestampUs: 0, src: [UInt8](repeating: 0, count: 10), offset: 0, length: 0))
        XCTAssertFalse(jb.put(seq32: 1, timestampUs: 0, src: [UInt8](repeating: 0, count: 10), offset: 5, length: 10))
    }
}

final class ResamplerTests: XCTestCase {

    /// Signal-to-error ratio (dB) of a resampled sine against the mathematically exact result.
    private func sineSnrDb(_ freqHz: Double, _ speed: Double) -> Double {
        let r = Resampler(channels: 2, maxInputFrames: 960)
        var input = [Float](repeating: 0, count: 960 * 2)
        var out = [Float](repeating: 0, count: 1024 * 2)
        let w = 2 * Double.pi * freqHz / 48_000
        var inIndex = 0
        var outPos = 0.0 // input-domain position of the next output frame
        var signal = 0.0
        var noise = 0.0
        for block in 0..<60 {
            for i in 0..<960 {
                let v = Float(0.5 * sin(w * Double(inIndex + i)))
                input[2 * i] = v
                input[2 * i + 1] = -v
            }
            inIndex += 960
            let n = r.process(input, inFrames: 960, speed: speed, out: &out)
            for j in 0..<n {
                let expected = 0.5 * sin(w * (outPos - Double(Resampler.delayFrames)))
                if block >= 2 {
                    signal += expected * expected
                    let e = Double(out[2 * j]) - expected
                    noise += e * e
                    XCTAssertEqual(out[2 * j + 1], -out[2 * j], accuracy: 1e-6) // channels stay independent
                }
                outPos += speed
            }
        }
        return 10 * log10(signal / noise)
    }

    func testIsTransparentAtUnitySpeed() {
        var rng = SeededRandom(seed: 3)
        let r = Resampler(channels: 2, maxInputFrames: 960)
        var first = [Float](repeating: 0, count: 960 * 2)
        var second = [Float](repeating: 0, count: 960 * 2)
        for i in 0..<first.count {
            first[i] = Float(rng.unit() - 0.5)
            second[i] = Float(rng.unit() - 0.5)
        }
        var out = [Float](repeating: 0, count: 1024 * 2)
        var out2 = [Float](repeating: 0, count: 1024 * 2)
        XCTAssertEqual(r.process(first, inFrames: 960, speed: 1.0, out: &out), 960)
        XCTAssertEqual(r.process(second, inFrames: 960, speed: 1.0, out: &out2), 960)
        // Output is the input delayed by exactly delayFrames, bit for bit.
        let d = Resampler.delayFrames
        for i in d..<960 { XCTAssertEqual(out[2 * i], first[2 * (i - d)]) }
        for i in 0..<d { XCTAssertEqual(out2[2 * i + 1], first[2 * (960 - d + i) + 1]) }
    }

    func testProducesTheRightNumberOfFrames() {
        let r = Resampler(channels: 2, maxInputFrames: 960)
        let input = [Float](repeating: 0, count: 960 * 2)
        var out = [Float](repeating: 0, count: 1024 * 2)
        var total = 0
        for _ in 0..<500 { total += r.process(input, inFrames: 960, speed: 1.004, out: &out) }
        XCTAssertEqual(Double(total), 500 * 960 / 1.004, accuracy: 2)
        r.reset()
        total = 0
        for _ in 0..<500 { total += r.process(input, inFrames: 960, speed: 0.996, out: &out) }
        XCTAssertEqual(Double(total), 500 * 960 / 0.996, accuracy: 2)
    }

    func testStaysCleanAcrossTheAudioBand() {
        for speed in [1.0005, 0.9995, 1.005, 0.995] {
            XCTAssertGreaterThan(sineSnrDb(1_000, speed), 70, "1 kHz @ \(speed)")
            XCTAssertGreaterThan(sineSnrDb(8_000, speed), 60, "8 kHz @ \(speed)")
            XCTAssertGreaterThan(sineSnrDb(15_000, speed), 40, "15 kHz @ \(speed)")
        }
    }
}
