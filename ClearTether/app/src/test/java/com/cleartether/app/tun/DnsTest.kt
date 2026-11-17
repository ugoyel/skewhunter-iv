package com.cleartether.app.tun

import com.cleartether.app.core.tun.DnsForwarder
import com.cleartether.app.core.tun.DnsMessage
import com.cleartether.app.core.tun.DnsUpstream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsTest {
    private fun query(id: Int, name: String, type: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id ushr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // RD
        out.write(0); out.write(1); repeat(6) { out.write(0) }
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray()) }
        out.write(0)
        out.write(0); out.write(type); out.write(0); out.write(1)
        return out.toByteArray()
    }

    private fun answer(q: ByteArray, ttl: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(q, 0, q.size)
        val r = out.toByteArray()
        r[2] = 0x81.toByte(); r[3] = 0x80.toByte(); r[7] = 1 // ANCOUNT=1
        val rr = ByteArrayOutputStream()
        rr.write(0xC0); rr.write(12) // pointer to question name
        rr.write(0); rr.write(1); rr.write(0); rr.write(1)
        rr.write(ttl ushr 24); rr.write(ttl ushr 16); rr.write(ttl ushr 8); rr.write(ttl)
        rr.write(0); rr.write(4); rr.write(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        return r + rr.toByteArray()
    }

    @Test
    fun parsesQuestionKeyAndTtl() {
        val q = query(0x1234, "Example.COM")
        assertEquals("example.com|1|1", DnsMessage.questionKey(q))
        assertEquals(300L, DnsMessage.minTtlSeconds(answer(q, 300)))
    }

    @Test
    fun cacheHitRewritesTransactionId() = runBlocking {
        var calls = 0
        val forwarder = DnsForwarder(listOf(DnsUpstream { q -> calls++; answer(q, 120) }))
        val first = forwarder.resolve(query(1, "example.com"))
        val second = forwarder.resolve(query(0xBEEF, "example.com"))
        assertEquals(1, calls)
        assertEquals(0xBE.toByte(), second[0])
        assertEquals(0xEF.toByte(), second[1])
        assertEquals(first.size, second.size)
    }

    @Test
    fun failoverAndServfail() = runBlocking {
        val failing = DnsForwarder(listOf(DnsUpstream { error("down") }))
        val q = query(7, "example.org")
        val r = failing.resolve(q)
        assertEquals(2, r[3].toInt() and 0x0F)
        assertTrue(r[2].toInt() and 0x80 != 0)
        assertEquals(7, r[1].toInt())

        val fallback = DnsForwarder(listOf(DnsUpstream { error("down") }, DnsUpstream { x -> answer(x, 60) }))
        assertEquals(0, fallback.resolve(q)[3].toInt() and 0x0F)
    }

    @Test
    fun truncationKeepsQuestionAndSetsTc() {
        val q = query(9, "big.example")
        val t = DnsMessage.truncated(answer(q, 60))
        assertEquals(q.size, t.size)
        assertTrue(t[2].toInt() and 0x02 != 0)
        assertEquals(0, t[7].toInt())
    }
}
