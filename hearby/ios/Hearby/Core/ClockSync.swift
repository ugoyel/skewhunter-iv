import Foundation

/// NTP-style estimate of (host clock - local clock).
///
/// For one request/response exchange
///
///     t0 = client send      (client clock)
///     t1 = host receive     (host clock)
///     t2 = host send        (host clock)
///     t3 = client receive   (client clock)
///
///     rtt    = (t3 - t0) - (t2 - t1)
///     offset = ((t1 - t0) + (t2 - t3)) / 2
///
/// The offset is only exact when both network legs take equal time, and its error is bounded by
/// rtt / 2. So we keep a short window of exchanges and trust the one with the smallest round trip
/// (the idea behind NTP's clock filter), then low-pass the result so playout never sees a step
/// unless the clocks really jumped.
///
/// Mirrors android/.../core/ClockSync.kt. Thread-safe.
final class ClockSync {
    static let minSamples = 3
    static let maxRttUs: Int64 = 1_000_000
    static let stepUs = 20_000.0
    static let smoothing = 0.25

    private let window: Int
    private var offsets: [Int64]
    private var rtts: [Int64]
    private var filled = 0
    private var next = 0
    private var smoothed = 0.0
    private var _offsetUs: Int64 = 0
    private var _rttUs: Int64 = 0
    private var _sampleCount = 0
    private let lock = NSLock()

    init(window: Int = 12) {
        self.window = window
        offsets = [Int64](repeating: 0, count: window)
        rtts = [Int64](repeating: 0, count: window)
    }

    /// Host clock minus local clock, microseconds.
    var offsetUs: Int64 { lock.lock(); defer { lock.unlock() }; return _offsetUs }

    /// Round trip of the exchange currently trusted, microseconds.
    var rttUs: Int64 { lock.lock(); defer { lock.unlock() }; return _rttUs }

    var sampleCount: Int { lock.lock(); defer { lock.unlock() }; return _sampleCount }

    var synced: Bool { sampleCount >= ClockSync.minSamples }

    func hostToLocalUs(_ hostUs: Int64) -> Int64 { hostUs - offsetUs }

    func localToHostUs(_ localUs: Int64) -> Int64 { localUs + offsetUs }

    /// Returns false if the exchange was implausible and ignored.
    @discardableResult
    func addSample(t0Us: Int64, t1Us: Int64, t2Us: Int64, t3Us: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let rtt = (t3Us - t0Us) - (t2Us - t1Us)
        if rtt < 0 || rtt > ClockSync.maxRttUs { return false }
        let offset = ((t1Us - t0Us) + (t2Us - t3Us)) / 2

        offsets[next] = offset
        rtts[next] = rtt
        next = (next + 1) % window
        if filled < window { filled += 1 }

        var best = 0
        for i in 1..<max(filled, 1) where rtts[i] < rtts[best] { best = i }
        let candidate = Double(offsets[best])

        if _sampleCount == 0 || abs(candidate - smoothed) > ClockSync.stepUs {
            smoothed = candidate // first fix, or the clocks really moved: step
        } else {
            smoothed += (candidate - smoothed) * ClockSync.smoothing
        }
        _offsetUs = Int64(smoothed.rounded())
        _rttUs = rtts[best]
        _sampleCount += 1
        return true
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        filled = 0
        next = 0
        smoothed = 0
        _offsetUs = 0
        _rttUs = 0
        _sampleCount = 0
    }
}
