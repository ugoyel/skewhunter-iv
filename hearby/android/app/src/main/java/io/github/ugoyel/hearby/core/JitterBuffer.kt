package io.github.ugoyel.hearby.core

/**
 * Fixed-capacity reorder buffer for encoded Opus frames, indexed by sequence number.
 *
 * Packets are kept encoded and handed out strictly in sequence order at playout time, which gives
 * late and reordered datagrams the longest possible chance to arrive. Nothing is allocated after
 * construction. Thread-safe: the network thread calls [put], the playout thread everything else.
 *
 * Mirrored by ios/Hearby/Core/JitterBuffer.swift.
 */
class JitterBuffer(private val capacity: Int = 256) {

    /** Caller-owned holder filled by [take]. */
    class Packet {
        var seq = -1L
        var timestampUs = 0L
        val data = ByteArray(Proto.MAX_PAYLOAD)
        var length = 0
    }

    private val slotSeq = LongArray(capacity) { -1L }
    private val slotTs = LongArray(capacity)
    private val slotLen = IntArray(capacity)
    private val slotData = Array(capacity) { ByteArray(Proto.MAX_PAYLOAD) }

    private var high = -1L   // highest unwrapped sequence stored since the last clear
    private var floor = 0L   // frames below this have already been played (or skipped)
    private var count = 0
    private var lastUnwrapped = -1L

    @Volatile
    var lateDrops = 0L
        private set

    /** Extend a 32-bit wire sequence number to 64 bits, following wrap-around. */
    private fun unwrap(seq32: Long): Long {
        if (lastUnwrapped < 0) {
            lastUnwrapped = seq32
            return seq32
        }
        val delta = (seq32 - (lastUnwrapped and 0xFFFFFFFFL)).toInt() // signed 32-bit distance
        val v = lastUnwrapped + delta
        if (v > lastUnwrapped) lastUnwrapped = v
        return v
    }

    private fun slot(seq: Long): Int = (seq % capacity).toInt()

    /** Store one packet. Returns false if it was too late, a duplicate, or oversized. */
    @Synchronized
    fun put(seq32: Long, timestampUs: Long, src: ByteArray, offset: Int, length: Int): Boolean {
        if (length <= 0 || length > Proto.MAX_PAYLOAD) return false
        val seq = unwrap(seq32)
        if (seq < 0) return false
        if (high >= 0 && (seq > high + capacity || seq <= high - capacity)) {
            // A jump larger than the whole buffer: the stream restarted. Start over from here.
            clearLocked()
            lastUnwrapped = seq
            floor = seq
        }
        if (seq < floor) {
            lateDrops++
            return false
        }
        if (high >= 0 && seq > high) {
            // Slots between the old and new head would alias older frames; expire them.
            val from = maxOf(high + 1, seq - capacity + 1)
            for (s in from..seq) invalidate(s)
        }
        val i = slot(seq)
        if (slotSeq[i] == seq) return false // duplicate
        if (slotSeq[i] >= 0) count--
        slotSeq[i] = seq
        slotTs[i] = timestampUs
        slotLen[i] = length
        System.arraycopy(src, offset, slotData[i], 0, length)
        count++
        if (seq > high) high = seq
        return true
    }

    private fun invalidate(seq: Long) {
        val i = slot(seq)
        if (slotSeq[i] >= 0 && slotSeq[i] != seq) {
            slotSeq[i] = -1
            count--
        }
    }

    /** Remove frame [seq] into [out]. Returns false if it has not arrived. */
    @Synchronized
    fun take(seq: Long, out: Packet): Boolean {
        if (seq < 0) return false
        val i = slot(seq)
        if (slotSeq[i] != seq) return false
        out.seq = seq
        out.timestampUs = slotTs[i]
        out.length = slotLen[i]
        System.arraycopy(slotData[i], 0, out.data, 0, slotLen[i])
        slotSeq[i] = -1
        count--
        return true
    }

    /** Timestamp of frame [seq] if it is buffered, else -1. */
    @Synchronized
    fun timestampOf(seq: Long): Long {
        if (seq < 0) return -1
        val i = slot(seq)
        return if (slotSeq[i] == seq) slotTs[i] else -1
    }

    /** Sequence number of the earliest buffered frame whose timestamp is >= [timestampUs], or -1. */
    @Synchronized
    fun firstAtOrAfter(timestampUs: Long): Long {
        if (high < 0) return -1
        var s = maxOf(floor, high - capacity + 1)
        while (s <= high) {
            val i = slot(s)
            if (slotSeq[i] == s && slotTs[i] >= timestampUs) return s
            s++
        }
        return -1
    }

    /** Discard everything older than [seq]; later arrivals below it count as late. */
    @Synchronized
    fun dropBelow(seq: Long) {
        if (seq <= floor) return
        if (high >= 0) {
            var s = maxOf(floor, high - capacity + 1)
            while (s < seq && s <= high) {
                val i = slot(s)
                if (slotSeq[i] == s) {
                    slotSeq[i] = -1
                    count--
                }
                s++
            }
        }
        floor = seq
    }

    /** Highest sequence number received so far, or -1. */
    @get:Synchronized
    val newestSeq: Long get() = high

    /** Timestamp of the newest frame still buffered, or -1 if the buffer is empty. */
    @Synchronized
    fun newestTimestampUs(): Long {
        if (high < 0 || count == 0) return -1
        var s = high
        val low = maxOf(floor, high - capacity + 1)
        while (s >= low) {
            val i = slot(s)
            if (slotSeq[i] == s) return slotTs[i]
            s--
        }
        return -1
    }

    @get:Synchronized
    val size: Int get() = count

    @Synchronized
    fun clear() {
        clearLocked()
        lastUnwrapped = -1
        floor = 0
        lateDrops = 0
    }

    private fun clearLocked() {
        java.util.Arrays.fill(slotSeq, -1L)
        count = 0
        high = -1
    }
}
