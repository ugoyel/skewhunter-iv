package com.cleartether.app.tun

import com.cleartether.app.core.tun.Checksum
import com.cleartether.app.core.tun.IpPacket
import com.cleartether.app.core.tun.PROTO_TCP
import com.cleartether.app.core.tun.PROTO_UDP
import com.cleartether.app.core.tun.PacketFactory
import com.cleartether.app.core.tun.TCP_ACK
import com.cleartether.app.core.tun.TCP_SYN
import com.cleartether.app.core.tun.TcpHeader
import com.cleartether.app.core.tun.UdpHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {
    private val a4 = byteArrayOf(10, 0, 0, 2)
    private val b4 = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
    private val a6 = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
    private val b6 = ByteArray(16).also { it[0] = 0x26; it[1] = 0x06; it[15] = 1 }

    @Test
    fun tcpV4RoundTripWithValidChecksums() {
        val payload = "hello".toByteArray()
        val p = PacketFactory.tcp(a4, 40000, b4, 443, 1000, 2000, TCP_ACK or TCP_SYN, 65535, payload, 0, payload.size, mss = 1460)
        val ip = IpPacket.parse(p, p.size)!!
        assertEquals(4, ip.version)
        assertEquals(PROTO_TCP, ip.protocol)
        assertEquals(0, Checksum.finish(Checksum.sum(p, 0, 20))) // IPv4 header checksum
        val tcpLen = p.size - 20
        assertEquals(0, Checksum.finish(Checksum.sum(p, 20, tcpLen, Checksum.pseudoHeader(a4, b4, PROTO_TCP, tcpLen))))
        val tcp = TcpHeader.parse(ip)!!
        assertEquals(40000, tcp.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(1000, tcp.seq)
        assertEquals(2000, tcp.ack)
        assertTrue(tcp.syn && tcp.ackFlag)
        assertEquals(1460, tcp.mss(536))
        assertEquals("hello", String(p, tcp.payloadOffset, tcp.payloadLength))
    }

    @Test
    fun tcpAndUdpV6RoundTrip() {
        val p = PacketFactory.tcp(a6, 1, b6, 2, -5, 7, TCP_ACK, 100)
        val ip = IpPacket.parse(p, p.size)!!
        assertEquals(6, ip.version)
        val len = p.size - 40
        assertEquals(0, Checksum.finish(Checksum.sum(p, 40, len, Checksum.pseudoHeader(a6, b6, PROTO_TCP, len))))
        assertEquals(-5, TcpHeader.parse(ip)!!.seq)

        val u = PacketFactory.udp(a6, 5353, b6, 53, byteArrayOf(1, 2, 3))
        val uip = IpPacket.parse(u, u.size)!!
        assertEquals(PROTO_UDP, uip.protocol)
        val udp = UdpHeader.parse(uip)!!
        assertEquals(53, udp.dstPort)
        assertEquals(3, udp.payloadLength)
        val ulen = u.size - 40
        assertEquals(0, Checksum.finish(Checksum.sum(u, 40, ulen, Checksum.pseudoHeader(a6, b6, PROTO_UDP, ulen))))
    }

    @Test
    fun fragmentsAndTruncatedPacketsAreRejected() {
        val p = PacketFactory.udp(a4, 1, b4, 2, ByteArray(10))
        p[6] = 0x20 // MF flag
        assertEquals(null, IpPacket.parse(p, p.size))
        val ok = PacketFactory.udp(a4, 1, b4, 2, ByteArray(10))
        assertEquals(null, IpPacket.parse(ok, ok.size - 1))
        assertNotNull(IpPacket.parse(ok, ok.size))
    }

    @Test
    fun icmpUnreachableQuotesOriginal() {
        val u = PacketFactory.udp(a4, 5000, b4, 443, ByteArray(100))
        val icmp = PacketFactory.portUnreachable(IpPacket.parse(u, u.size)!!)
        assertEquals(3, icmp[20].toInt())
        assertEquals(3, icmp[21].toInt())
        assertEquals(0, Checksum.finish(Checksum.sum(icmp, 20, icmp.size - 20)))
    }
}
