import XCTest
@testable import HearbyCore

/// iPhones must play exactly the chirp the Android DJ listens for: same reference samples as
/// android/.../core/SoundCheckTest.kt (ChirpVectorTest).
final class ChirpTests: XCTestCase {
    func testMatchesTheSharedReferenceSamples() {
        let expected: [Int: Double] = [
            50: -0.20209772733274278, 100: 0.23147882850588578, 500: 0.2541215558681973,
            1500: 0.04289865617222823, 1900: 0.026364333928787693,
        ]
        for (i, v) in expected { XCTAssertEqual(Double(Chirp.wave[i]), v, accuracy: 1e-6, "sample \(i)") }
        XCTAssertEqual(Chirp.samples, 1920)
        XCTAssertEqual(Chirp.wave[0], 0)
    }
}

final class ToneSchedulerTests: XCTestCase {
    func testMixesTheChirpAtItsExactSampleAcrossFrames() {
        let tones = ToneScheduler()
        let start: Int64 = 1_000_000 + 5_000 // 5 ms into the frame that starts at 1 s
        tones.schedule(start)
        tones.schedule(start) // duplicates are ignored
        var out = [Float](repeating: 0, count: 4 * 960 * 2)
        for f in 0..<4 {
            var frame = [Float](repeating: 0, count: 960 * 2)
            tones.mix(into: &frame, frameTimeUs: 1_000_000 + Int64(f) * 20_000, frames: 960, channels: 2)
            for i in 0..<frame.count { out[f * 960 * 2 + i] = frame[i] }
        }
        let first = 240 // 5 ms at 48 kHz
        for i in 0..<first { XCTAssertEqual(out[2 * i], 0) }
        for i in 0..<Chirp.samples {
            XCTAssertEqual(out[2 * (first + i)], Chirp.wave[i])
            XCTAssertEqual(out[2 * (first + i) + 1], Chirp.wave[i])
        }
        for i in (first + Chirp.samples)..<(4 * 960) { XCTAssertEqual(out[2 * i], 0) }
        XCTAssertFalse(tones.busy)
    }
}
