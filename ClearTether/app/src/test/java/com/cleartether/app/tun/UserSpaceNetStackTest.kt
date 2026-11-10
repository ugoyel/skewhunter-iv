package com.cleartether.app.tun

import com.cleartether.app.core.tun.DnsForwarder
import com.cleartether.app.core.tun.DnsUpstream
import com.cleartether.app.core.tun.FlowUpstream
import com.cleartether.app.core.tun.IpPacket
import com.cleartether.app.core.tun.PROTO_ICMP
import com.cleartether.app.core.tun.PacketFactory
import com.cleartether.app.core.tun.PacketIo
import com.cleartether.app.core.tun.StackConfig
import com.cleartether.app.core.tun.TCP_ACK
import com.cleartether.app.core.tun.TCP_FIN
import com.cleartether.app.core.tun.TCP_PSH
import com.cleartether.app.core.tun.TCP_RST
import com.cleartether.app.core.tun.TCP_SYN
import com.cleartether.app.core.tun.TcpHeader
import com.cleartether.app.core.tun.UdpHeader
import com.cleartether.app.core.tun.UserSpaceNetStack
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class UserSpaceNetStackTest {
    private val app = byteArrayOf(10, 0, 0, 2)
    private val server = byteArrayOf(1, 2, 3, 4)

    private class QueueIo : PacketIo {
        val toStack = LinkedBlockingQueue<ByteArray>()
        val fromStack = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                val p = toStack.poll(50, TimeUnit.MILLISECONDS) ?: continue
                System.arraycopy(p, 0, buffer, 0, p.size)
                return p.size
            }
            return -1
        }
        override fun write(packet: ByteArray) { fromStack.put(packet) }
        override fun close() { closed = true }
    }

    /** Echoes written bytes back; EOF after shutdownOutput. */
    private class EchoUpstream : FlowUpstream {
        private val ch = Channel<ByteArray>(Channel.UNLIMITED)
        private var current: ByteArray? = null
        private var pos = 0
        override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
            val c = current ?: ch.receiveCatching().getOrNull()?.also { current = it; pos = 0 } ?: return -1
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, buf, off, n)
            pos += n
            if (pos == c.size) current = null
            return n
        }
        override suspend fun write(buf: ByteArray, off: Int, len: Int) { ch.send(buf.copyOfRange(off, off + len)) }
        override fun shutdownOutput() { ch.close() }
        override fun close() { ch.close() }
    }

    private val io = QueueIo()
    private var stack: UserSpaceNetStack? = null

    private fun start(dialFails: Boolean = false, dns: DnsForwarder = DnsForwarder(listOf(DnsUpstream { q -> q.copyOf().also { it[2] = 0x80.toByte() } }))) {
        stack = UserSpaceNetStack(
            io, StackConfig(),
            tcpDialer = { _, _ -> if (dialFails) throw IOException("refused") else EchoUpstream() },
            dns = dns, udpProxy = null,
        ).also { it.start() }
    }

    @After
    fun stop() { stack?.stop() }

    private fun send(seq: Int, ack: Int, flags: Int, data: ByteArray? = null, mss: Int = 0) =
        io.toStack.put(PacketFactory.tcp(app, 40000, server, 80, seq, ack, flags, 65535, data, 0, data?.size ?: 0, mss))

    private fun next(): TcpHeader {
        val p = io.fromStack.poll(5, TimeUnit.SECONDS) ?: error("no packet from stack")
        return TcpHeader.parse(IpPacket.parse(p, p.size)!!)!!
    }

    @Test
    fun handshakeEchoAndGracefulClose() {
        start()
        send(100, 0, TCP_SYN, mss = 1460)
        val synAck = next()
        assertEquals(TCP_SYN or TCP_ACK, synAck.flags)
        assertEquals(101, synAck.ack)
        val isn = synAck.seq
        assertEquals(1460, synAck.mss(0))

        send(101, isn + 1, TCP_ACK)
        send(101, isn + 1, TCP_ACK or TCP_PSH, "hello".toByteArray())

        var acked = false
        var echoed: String? = null
        while (!acked || echoed == null) {
            val h = next()
            if (h.payloadLength > 0) {
                assertEquals(isn + 1, h.seq)
                echoed = String(h.packet.data, h.payloadOffset, h.payloadLength)
            }
            if (h.ack == 106) acked = true
        }
        assertEquals("hello", echoed)
        send(106, isn + 6, TCP_ACK)

        send(106, isn + 6, TCP_FIN or TCP_ACK)
        var finSeen = false
        var finAckSeen = false
        while (!finSeen || !finAckSeen) {
            val h = next()
            if (h.ack == 107) finAckSeen = true
            if (h.fin) { finSeen = true; assertEquals(isn + 6, h.seq) }
        }
        send(107, isn + 7, TCP_ACK)
        // Retransmitted FIN after close is ACKed, not reset.
        send(106, isn + 7, TCP_FIN or TCP_ACK)
        val late = next()
        assertEquals(0, late.flags and TCP_RST)
        assertEquals(107, late.ack)
    }

    @Test
    fun refusedUpstreamResetsTheApp() {
        start(dialFails = true)
        send(500, 0, TCP_SYN, mss = 1460)
        val h = next()
        assertTrue(h.rst)
        assertEquals(501, h.ack)
    }

    @Test
    fun strayAckGetsReset() {
        start()
        send(1, 999, TCP_ACK)
        val h = next()
        assertTrue(h.rst)
        assertEquals(999, h.seq)
    }

    @Test
    fun dnsQueriesAreAnsweredAndOtherUdpGetsIcmp() {
        start()
        val query = ByteArray(20) { 1 }
        io.toStack.put(PacketFactory.udp(app, 5555, byteArrayOf(8, 8, 8, 8), 53, query))
        val p = io.fromStack.poll(5, TimeUnit.SECONDS)
        assertNotNull(p)
        val ip = IpPacket.parse(p!!, p.size)!!
        val udp = UdpHeader.parse(ip)!!
        assertEquals(53, udp.srcPort)
        assertEquals(5555, udp.dstPort)
        assertTrue(ip.src.contentEquals(byteArrayOf(8, 8, 8, 8)))

        io.toStack.put(PacketFactory.udp(app, 5556, server, 443, ByteArray(30)))
        val icmp = io.fromStack.poll(5, TimeUnit.SECONDS)!!
        assertEquals(PROTO_ICMP, IpPacket.parse(icmp, icmp.size)!!.protocol)
    }
}
