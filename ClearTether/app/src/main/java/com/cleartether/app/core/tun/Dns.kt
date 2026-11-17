package com.cleartether.app.core.tun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.Locale

/** Minimal DNS wire-format helpers: just enough for caching and error replies. */
internal object DnsMessage {
    private const val HEADER = 12

    /** Cache key "name|type|class" for a single-question query, or null. */
    fun questionKey(msg: ByteArray): String? {
        if (msg.size < HEADER + 5 || msg.u16(4) != 1) return null
        val sb = StringBuilder()
        var i = HEADER
        while (true) {
            if (i >= msg.size) return null
            val len = msg.u8(i)
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, i + 1, len, Charsets.ISO_8859_1).lowercase(Locale.ROOT))
            i += 1 + len
        }
        if (i + 4 > msg.size) return null
        return "$sb|${msg.u16(i)}|${msg.u16(i + 2)}"
    }

    /** Smallest TTL among answer + authority records, or null if the message can't be parsed. */
    fun minTtlSeconds(msg: ByteArray): Long? {
        if (msg.size < HEADER) return null
        val qd = msg.u16(4)
        val records = msg.u16(6) + msg.u16(8)
        var i = HEADER
        repeat(qd) {
            i = skipName(msg, i) ?: return null
            i += 4
        }
        var min = Long.MAX_VALUE
        repeat(records) {
            i = skipName(msg, i) ?: return null
            if (i + 10 > msg.size) return null
            val ttl = msg.i32(i + 4).toLong() and 0xFFFFFFFFL
            val rdLength = msg.u16(i + 8)
            i += 10 + rdLength
            if (i > msg.size) return null
            if (ttl < min) min = ttl
        }
        return if (min == Long.MAX_VALUE) null else min
    }

    fun rcode(msg: ByteArray): Int = if (msg.size >= 4) msg.u8(3) and 0x0F else 2

    /** SERVFAIL answer for [query], so apps fail fast instead of timing out. */
    fun servFail(query: ByteArray): ByteArray {
        val questionEnd = questionEnd(query) ?: HEADER.coerceAtMost(query.size)
        val out = query.copyOfRange(0, questionEnd)
        if (out.size >= HEADER) {
            out[2] = (0x80 or (query.u8(2) and 0x79)).toByte() // QR=1, keep opcode and RD
            out[3] = 0x82.toByte() // RA=1, RCODE=SERVFAIL
            out.put16(6, 0); out.put16(8, 0); out.put16(10, 0)
        }
        return out
    }

    /** Truncated copy (header + question, TC bit) for responses that don't fit a UDP packet. */
    fun truncated(response: ByteArray): ByteArray {
        val end = questionEnd(response) ?: return servFail(response)
        val out = response.copyOfRange(0, end)
        out[2] = (out.u8(2) or 0x02).toByte()
        out.put16(6, 0); out.put16(8, 0); out.put16(10, 0)
        return out
    }

    fun withId(msg: ByteArray, idSource: ByteArray): ByteArray {
        val out = msg.copyOf()
        if (out.size >= 2 && idSource.size >= 2) { out[0] = idSource[0]; out[1] = idSource[1] }
        return out
    }

    private fun questionEnd(msg: ByteArray): Int? {
        if (msg.size < HEADER) return null
        var i = HEADER
        repeat(msg.u16(4)) {
            i = skipName(msg, i) ?: return null
            i += 4
        }
        return if (i <= msg.size) i else null
    }

    private fun skipName(msg: ByteArray, start: Int): Int? {
        var i = start
        while (i < msg.size) {
            val len = msg.u8(i)
            when {
                len == 0 -> return i + 1
                len and 0xC0 == 0xC0 -> return if (i + 2 <= msg.size) i + 2 else null
                len and 0xC0 != 0 -> return null
                else -> i += 1 + len
            }
        }
        return null
    }
}

/** Exchanges a DNS wire-format message with some resolver. */
fun interface DnsUpstream {
    suspend fun exchange(query: ByteArray): ByteArray
}

/**
 * Resolves app DNS queries through the host phone, with a small TTL-aware cache and failover
 * across several [upstreams].
 */
class DnsForwarder(
    private val upstreams: List<DnsUpstream>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = 5_000,
) {
    private class Entry(val response: ByteArray, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }

    @Volatile private var preferred = 0

    /** Always returns a response: a real answer, or SERVFAIL. */
    suspend fun resolve(query: ByteArray): ByteArray {
        if (query.size < 12) return DnsMessage.servFail(query)
        val key = DnsMessage.questionKey(query)
        if (key != null) {
            synchronized(cache) {
                val hit = cache[key]
                if (hit != null && hit.expiresAt > clock()) return DnsMessage.withId(hit.response, query)
                if (hit != null) cache.remove(key)
            }
        }
        val count = upstreams.size
        for (attempt in 0 until count) {
            val index = (preferred + attempt) % count
            val response = try {
                withTimeout(timeoutMs) { upstreams[index].exchange(query) }
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
            } catch (e: Exception) {
                null
            } ?: continue
            if (response.size < 12) continue
            preferred = index
            if (key != null) store(key, response)
            return DnsMessage.withId(response, query)
        }
        return DnsMessage.servFail(query)
    }

    private fun store(key: String, response: ByteArray) {
        val rcode = DnsMessage.rcode(response)
        val ttl = when (rcode) {
            0 -> DnsMessage.minTtlSeconds(response)?.let { if (it == 0L) null else it.coerceIn(MIN_TTL_S, MAX_TTL_S) }
                ?: if (response.u16(6) == 0) NEGATIVE_TTL_S else null
            3 -> NEGATIVE_TTL_S // NXDOMAIN
            else -> null
        } ?: return
        synchronized(cache) { cache[key] = Entry(response, clock() + ttl * 1000) }
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private companion object {
        const val MAX_ENTRIES = 1024
        const val MIN_TTL_S = 10L
        const val MAX_TTL_S = 300L
        const val NEGATIVE_TTL_S = 30L
    }
}

/**
 * Serves DNS-over-TCP (RFC 7766 length-prefixed messages) for the VPN's virtual resolver, which
 * apps use for responses that were truncated over UDP.
 */
internal class LocalDnsTcpUpstream(private val forwarder: DnsForwarder) : FlowUpstream {
    private val incoming = java.io.ByteArrayOutputStream()
    private val responses = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = 0
    private var inputClosed = false
    private var current: ByteArray? = null
    private var currentOffset = 0

    override suspend fun write(buf: ByteArray, off: Int, len: Int) {
        incoming.write(buf, off, len)
        val data = incoming.toByteArray()
        var i = 0
        val queries = ArrayList<ByteArray>()
        while (i + 2 <= data.size) {
            val msgLen = data.u16(i)
            if (i + 2 + msgLen > data.size) break
            queries += data.copyOfRange(i + 2, i + 2 + msgLen)
            i += 2 + msgLen
        }
        incoming.reset()
        incoming.write(data, i, data.size - i)
        for (q in queries) {
            synchronized(this) { pending++ }
            val response = forwarder.resolve(q)
            val framed = ByteArray(response.size + 2)
            framed.put16(0, response.size)
            System.arraycopy(response, 0, framed, 2, response.size)
            responses.trySend(framed)
            finishOne()
        }
    }

    private fun finishOne() {
        synchronized(this) {
            pending--
            if (inputClosed && pending == 0) responses.close()
        }
    }

    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        val chunk = current ?: (responses.receiveCatching().getOrNull() ?: return -1).also {
            current = it
            currentOffset = 0
        }
        val n = minOf(len, chunk.size - currentOffset)
        System.arraycopy(chunk, currentOffset, buf, off, n)
        currentOffset += n
        if (currentOffset >= chunk.size) current = null
        return n
    }

    override fun shutdownOutput() {
        synchronized(this) {
            inputClosed = true
            if (pending == 0) responses.close()
        }
    }

    override fun close() {
        responses.close()
    }
}
