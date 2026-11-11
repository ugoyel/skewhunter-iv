import Foundation

/// Variable-ratio resampler used for drift correction.
///
/// The playout engine nudges playback speed by at most a fraction of a percent to keep this device
/// locked to the host timeline. To do that inaudibly we use a 16-tap Kaiser-windowed sinc, stored as
/// 64 polyphase branches with linear interpolation between neighbouring branches. At speed 1.0 with
/// a zero fractional phase the filter degenerates to a pure 8-frame delay, i.e. it is bit-transparent.
///
/// Audio is interleaved float. Group delay is `delayFrames` input frames.
///
/// Mirrors android/.../core/Resampler.kt.
final class Resampler {
    static let taps = 16
    static let phases = 64
    static let history = taps - 1
    static let delayFrames = taps / 2
    private static let kaiserBeta = 6.0

    /// (phases + 1) rows of `taps` coefficients; row k is the filter for a fractional delay k / phases.
    static let table: [Float] = buildTable()

    private let channels: Int
    private var work: [Float]
    private var phase = 0.0 // read position carried into the next block, in input frames, [0, 1)

    init(channels: Int = 2, maxInputFrames: Int = Proto.frameSamples) {
        self.channels = channels
        work = [Float](repeating: 0, count: (Resampler.history + maxInputFrames) * channels)
    }

    /// Consume `inFrames` frames from `input` and write roughly inFrames / speed frames to `out`.
    /// `speed` is input frames consumed per output frame (> 1 plays faster). Returns frames written.
    func process(_ input: [Float], inFrames: Int, speed: Double, out: inout [Float]) -> Int {
        let ch = channels
        let taps = Resampler.taps
        let history = Resampler.history
        let phases = Double(Resampler.phases)
        let maxOut = out.count / ch
        var p = phase
        var produced = 0

        work.withUnsafeMutableBufferPointer { w in
            input.withUnsafeBufferPointer { src in
                for i in 0..<(inFrames * ch) { w[history * ch + i] = src[i] }
            }
            Resampler.table.withUnsafeBufferPointer { table in
                out.withUnsafeMutableBufferPointer { dst in
                    while p < Double(inFrames) && produced < maxOut {
                        let i0 = Int(p)
                        let scaled = (p - Double(i0)) * phases
                        let k = Int(scaled)
                        let mu = Float(scaled - Double(k))
                        let row0 = k * taps
                        let row1 = row0 + taps
                        let base = i0 * ch // work frame i0 is input frame (i0 - history)
                        for c in 0..<ch {
                            var acc: Float = 0
                            var idx = base + c
                            for t in 0..<taps {
                                let h0 = table[row0 + t]
                                acc += (h0 + (table[row1 + t] - h0) * mu) * w[idx]
                                idx += ch
                            }
                            dst[produced * ch + c] = acc
                        }
                        produced += 1
                        p += speed
                    }
                }
            }
            // Keep the last `history` frames for the next block.
            for i in 0..<(history * ch) { w[i] = w[inFrames * ch + i] }
        }
        phase = p - Double(inFrames)
        return produced
    }

    func reset() {
        for i in 0..<work.count { work[i] = 0 }
        phase = 0
    }

    private static func buildTable() -> [Float] {
        var table = [Float](repeating: 0, count: (phases + 1) * taps)
        let half = Double(taps) / 2.0
        let i0Beta = besselI0(kaiserBeta)
        var row = [Double](repeating: 0, count: taps)
        for k in 0...phases {
            let frac = Double(k) / Double(phases)
            var sum = 0.0
            for t in 0..<taps {
                // Distance from the interpolation point to tap t, in input frames.
                let x = Double(taps / 2 - 1) + frac - Double(t)
                let sinc = abs(x) < 1e-9 ? 1.0 : sin(Double.pi * x) / (Double.pi * x)
                let r = x / half
                let window = abs(r) >= 1.0 ? 0.0 : besselI0(kaiserBeta * (1.0 - r * r).squareRoot()) / i0Beta
                row[t] = sinc * window
                sum += row[t]
            }
            // Unity DC gain for every branch, so the level cannot wobble as the phase sweeps.
            for t in 0..<taps { table[k * taps + t] = Float(row[t] / sum) }
        }
        return table
    }

    private static func besselI0(_ x: Double) -> Double {
        var sum = 1.0
        var term = 1.0
        let q = x * x / 4.0
        var n = 1
        while n < 64 {
            term *= q / (Double(n) * Double(n))
            sum += term
            if term < sum * 1e-15 { break }
            n += 1
        }
        return sum
    }
}
