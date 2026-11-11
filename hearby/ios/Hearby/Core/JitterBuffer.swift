import Foundation

/// Caller-owned holder filled by `JitterBuffer.take`.
final class JitterPacket {
    var seq: Int64 = -1
    var timestampUs: Int64 = 0
    var data = [UInt8](repeating: 0, count: Proto.maxPayload)
    var length = 0
}

/// Fixed-capacity reorder buffer for encoded Opus frames, indexed by sequence number.
///
/// Packets are kept encoded and handed out strictly in sequence order at playout time, which gives
/// late and reordered datagrams the longest possible chance to arrive. Nothing is allocated after
/// construction. Thread-safe: the network queue calls `put`, the playout thread everything else.
///
/// Mirrors android/.../core/JitterBuffer.kt.
final class JitterBuffer {
    private let capacity: Int
    private var slotSeq: [Int64]
    private var slotTs: [Int64]
    private var slotLen: [Int]
    private var slotData: [UInt8] // capacity * maxPayload, one fixed region per slot

    private var high: Int64 = -1   // highest unwrapped sequence stored since the last clear
    private var floor: Int64 = 0   // frames below this have already been played (or skipped)
    private var count = 0
    private var lastUnwrapped: Int64 = -1
    private var _lateDrops: Int64 = 0
    private let lock = NSLock()

    init(capacity: Int = 256) {
        self.capacity = capacity
        slotSeq = [Int64](repeating: -1, count: capacity)
        slotTs = [Int64](repeating: 0, count: capacity)
        slotLen = [Int](repeating: 0, count: capacity)
        slotData = [UInt8](repeating: 0, count: capacity * Proto.maxPayload)
    }

    var lateDrops: Int64 { lock.lock(); defer { lock.unlock() }; return _lateDrops }

    /// Extend a 32-bit wire sequence number to 64 bits, following wrap-around.
    private func unwrap(_ seq32: UInt32) -> Int64 {
        if lastUnwrapped < 0 {
            lastUnwrapped = Int64(seq32)
            return lastUnwrapped
        }
        let delta = Int32(truncatingIfNeeded: seq32 &- UInt32(truncatingIfNeeded: lastUnwrapped)) // signed distance
        let v = lastUnwrapped + Int64(delta)
        if v > lastUnwrapped { lastUnwrapped = v }
        return v
    }

    private func slot(_ seq: Int64) -> Int { Int(seq % Int64(capacity)) }

    /// Store one packet (`length` bytes of `src` starting at `offset`).
    /// Returns false if it was too late, a duplicate, or oversized.
    @discardableResult
    func put(seq32: UInt32, timestampUs: Int64, src: [UInt8], offset: Int, length: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if length <= 0 || length > Proto.maxPayload || offset + length > src.count { return false }
        let seq = unwrap(seq32)
        if seq < 0 { return false }
        if high >= 0 && (seq > high + Int64(capacity) || seq <= high - Int64(capacity)) {
            // A jump larger than the whole buffer: the stream restarted. Start over from here.
            clearLocked()
            lastUnwrapped = seq
            floor = seq
        }
        if seq < floor {
            _lateDrops += 1
            return false
        }
        if high >= 0 && seq > high {
            // Slots between the old and new head would alias older frames; expire them.
            var s = max(high + 1, seq - Int64(capacity) + 1)
            while s <= seq {
                let i = slot(s)
                if slotSeq[i] >= 0 && slotSeq[i] != s {
                    slotSeq[i] = -1
                    count -= 1
                }
                s += 1
            }
        }
        let i = slot(seq)
        if slotSeq[i] == seq { return false } // duplicate
        if slotSeq[i] >= 0 { count -= 1 }
        slotSeq[i] = seq
        slotTs[i] = timestampUs
        slotLen[i] = length
        let base = i * Proto.maxPayload
        for k in 0..<length { slotData[base + k] = src[offset + k] }
        count += 1
        if seq > high { high = seq }
        return true
    }

    /// Remove frame `seq` into `out`. Returns false if it has not arrived.
    func take(_ seq: Int64, into out: JitterPacket) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if seq < 0 { return false }
        let i = slot(seq)
        if slotSeq[i] != seq { return false }
        out.seq = seq
        out.timestampUs = slotTs[i]
        out.length = slotLen[i]
        let base = i * Proto.maxPayload
        for k in 0..<slotLen[i] { out.data[k] = slotData[base + k] }
        slotSeq[i] = -1
        count -= 1
        return true
    }

    /// Timestamp of frame `seq` if it is buffered, else -1.
    func timestampOf(_ seq: Int64) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        if seq < 0 { return -1 }
        let i = slot(seq)
        return slotSeq[i] == seq ? slotTs[i] : -1
    }

    /// Sequence number of the earliest buffered frame whose timestamp is >= `timestampUs`, or -1.
    func firstAtOrAfter(_ timestampUs: Int64) -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        if high < 0 { return -1 }
        var s = max(floor, high - Int64(capacity) + 1)
        while s <= high {
            let i = slot(s)
            if slotSeq[i] == s && slotTs[i] >= timestampUs { return s }
            s += 1
        }
        return -1
    }

    /// Discard everything older than `seq`; later arrivals below it count as late.
    func dropBelow(_ seq: Int64) {
        lock.lock()
        defer { lock.unlock() }
        if seq <= floor { return }
        if high >= 0 {
            var s = max(floor, high - Int64(capacity) + 1)
            while s < seq && s <= high {
                let i = slot(s)
                if slotSeq[i] == s {
                    slotSeq[i] = -1
                    count -= 1
                }
                s += 1
            }
        }
        floor = seq
    }

    /// Highest sequence number received so far, or -1.
    var newestSeq: Int64 { lock.lock(); defer { lock.unlock() }; return high }

    /// Timestamp of the newest frame still buffered, or -1 if the buffer is empty.
    func newestTimestampUs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        if high < 0 || count == 0 { return -1 }
        var s = high
        let low = max(floor, high - Int64(capacity) + 1)
        while s >= low {
            let i = slot(s)
            if slotSeq[i] == s { return slotTs[i] }
            s -= 1
        }
        return -1
    }

    var size: Int { lock.lock(); defer { lock.unlock() }; return count }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        clearLocked()
        lastUnwrapped = -1
        floor = 0
        _lateDrops = 0
    }

    private func clearLocked() {
        for i in 0..<capacity { slotSeq[i] = -1 }
        count = 0
        high = -1
    }
}
