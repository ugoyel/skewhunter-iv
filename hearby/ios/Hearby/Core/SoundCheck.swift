import Foundation

/// The sound-check chirp: a 40 ms linear sweep from 1.5 to 7 kHz.
///
/// The DJ phone (Android) asks each phone to play it at an exact point of the shared timeline and
/// times it with its microphone, so iPhones must play exactly the same waveform as Android phones.
/// Mirrors android/.../core/SoundCheck.kt; both test suites check the same reference samples.
enum Chirp {
    static let samples = 1920
    private static let f0 = 1500.0
    private static let f1 = 7000.0
    private static let amplitude = 0.5
    private static let taper = 96 // 2 ms raised-cosine fade at each end

    static let wave: [Float] = (0..<samples).map { Float(amplitude * envelope($0) * sin(phase($0))) }

    private static func phase(_ i: Int) -> Double {
        let t = Double(i) / Double(Proto.sampleRate)
        let duration = Double(samples) / Double(Proto.sampleRate)
        return 2 * Double.pi * (f0 * t + (f1 - f0) * t * t / (2 * duration))
    }

    private static func envelope(_ i: Int) -> Double {
        if i < taper { return 0.5 - 0.5 * cos(Double.pi * Double(i) / Double(taper)) }
        if i >= samples - taper { return 0.5 - 0.5 * cos(Double.pi * Double(samples - 1 - i) / Double(taper)) }
        return 1
    }
}

/// Mixes scheduled chirps into decoded audio at exact positions on the media timeline. Thread-safe.
final class ToneScheduler {
    private var pending = [Int64](repeating: Int64.min, count: 16)
    private let lock = NSLock()

    /// Play the chirp so that its first sample is heard when media time `mediaTimeUs` is.
    func schedule(_ mediaTimeUs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        if pending.contains(mediaTimeUs) { return } // repeated message
        if let free = pending.firstIndex(of: Int64.min) {
            pending[free] = mediaTimeUs
        } else if let oldest = pending.indices.min(by: { pending[$0] < pending[$1] }) {
            pending[oldest] = mediaTimeUs
        }
    }

    /// Add any chirp overlapping the frame whose first sample has media time `frameTimeUs`.
    func mix(into pcm: inout [Float], frameTimeUs: Int64, frames: Int, channels: Int) {
        lock.lock()
        defer { lock.unlock() }
        for s in pending.indices {
            let start = pending[s]
            if start == Int64.min { continue }
            // Chirp sample index at frame sample 0.
            let offset = Int64((Double(frameTimeUs - start) * Double(Proto.sampleRate) / 1e6).rounded())
            if offset >= Int64(Chirp.samples) {
                pending[s] = Int64.min // finished (or missed entirely)
                continue
            }
            if offset + Int64(frames) <= 0 { continue } // not yet
            for j in 0..<frames {
                let k = Int(offset) + j
                if k < 0 || k >= Chirp.samples { continue }
                let v = Chirp.wave[k]
                for c in 0..<channels { pcm[j * channels + c] += v }
            }
        }
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        for i in pending.indices { pending[i] = Int64.min }
    }

    var busy: Bool {
        lock.lock()
        defer { lock.unlock() }
        return pending.contains { $0 != Int64.min }
    }
}
