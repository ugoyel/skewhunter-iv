package com.cleartether.app.core.tun

import java.util.concurrent.atomic.AtomicInteger

internal const val PROTO_ICMP = 1
internal const val PROTO_TCP = 6
internal const val PROTO_UDP = 17
internal const val PROTO_ICMPV6 = 58

internal const val TCP_FIN = 0x01
internal const val TCP_SYN = 0x02
internal const val TCP_RST = 0x04
internal const val TCP_PSH = 0x08
internal const val TCP_ACK = 0x10

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.i32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
internal fun ByteArray.put16(i: Int, v: Int) { this[i] = (v ushr 8).toByte(); this[i + 1] = v.toByte() }
internal fun ByteArray.put32(i: Int, v: Int) {
    this[i] = (v ushr 24).toByte(); this[i + 1] = (v ushr 16).toByte(); this[i + 2] = (v ushr 8).toByte(); this[i + 3] = v.toByte()
}

/** TCP sequence-space comparisons (RFC 1982 serial arithmetic). */
internal fun seqLt(a: Int, b: Int) = a - b < 0
internal fun seqLe(a: Int, b: Int) = a - b <= 0
internal fun seqGt(a: Int, b: Int) = a - b > 0

/** Internet checksum helpers (RFC 1071). */
internal object Checksum {
    fun sum(data: ByteArray, off: Int, len: Int, initial: Long = 0): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (data[i].toInt() and 0xFF) shl 8
        return s
    }

    fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.inv().toInt() and 0xFFFF
    }

    /** Pseudo-header sum for TCP/UDP over IPv4 or IPv6. */
    fun pseudoHeader(src: ByteArray, dst: ByteArray, protocol: Int, length: Int): Long {
        var s = sum(src, 0, src.size) + sum(dst, 0, dst.size)
        s += protocol.toLong()
        s += if (src.size == 4) length.toLong() else ((length ushr 16) + (length and 0xFFFF)).toLong()
        return s
    }
}

/**
 * Zero-copy view of an IP packet read from the TUN device. Only IPv4 and IPv6 without
 * fragmentation are accepted. Apps on the TUN never fragment TCP (MSS clamps it), and fragmented
 * UDP is very rare.
 */
internal class IpPacket private constructor(
    val data: ByteArray,
    val length: Int,
    val version: Int,
    val protocol: Int,
    val src: ByteArray,
    val dst: ByteArray,
    val transportOffset: Int,
) {
    val transportLength: Int get() = length - transportOffset
    val isV6: Boolean get() = version == 6

    fun isMulticastOrBroadcastDestination(): Boolean = if (version == 4) {
        val first = dst[0].toInt() and 0xFF
        first in 224..239 || (dst.all { it == 0xFF.toByte() })
    } else {
        dst[0] == 0xFF.toByte()
    }

    companion object {
        fun parse(data: ByteArray, length: Int): IpPacket? {
            if (length < 20) return null
            return when (data.u8(0) ushr 4) {
                4 -> parseV4(data, length)
                6 -> parseV6(data, length)
                else -> null
            }
        }

        private fun parseV4(data: ByteArray, length: Int): IpPacket? {
            val ihl = (data.u8(0) and 0x0F) * 4
            val total = data.u16(2)
            if (ihl < 20 || total < ihl || total > length) return null
            val fragment = data.u16(6)
            if (fragment and 0x3FFF != 0) return null // MF flag or non-zero offset
            return IpPacket(data, total, 4, data.u8(9), data.copyOfRange(12, 16), data.copyOfRange(16, 20), ihl)
        }

        private fun parseV6(data: ByteArray, length: Int): IpPacket? {
            if (length < 40) return null
            val total = 40 + data.u16(4)
            if (total > length) return null
            var next = data.u8(6)
            var offset = 40
            // Skip hop-by-hop, routing and destination-options extension headers.
            while (next == 0 || next == 43 || next == 60) {
                if (offset + 8 > total) return null
                val extLen = (data.u8(offset + 1) + 1) * 8
                next = data.u8(offset)
                offset += extLen
            }
            if (next == 44 || offset > total) return null // fragment header: unsupported
            return IpPacket(data, total, 6, next, data.copyOfRange(8, 24), data.copyOfRange(24, 40), offset)
        }
    }
}

/** View of the TCP header inside an [IpPacket]. */
internal class TcpHeader(val packet: IpPacket) {
    private val d = packet.data
    private val o = packet.transportOffset
    val srcPort = d.u16(o)
    val dstPort = d.u16(o + 2)
    val seq = d.i32(o + 4)
    val ack = d.i32(o + 8)
    val headerLength = (d.u8(o + 12) ushr 4) * 4
    val flags = d.u8(o + 13)
    val window = d.u16(o + 14)
    val payloadOffset = o + headerLength
    val payloadLength = packet.length - payloadOffset

    val syn get() = flags and TCP_SYN != 0
    val ackFlag get() = flags and TCP_ACK != 0
    val fin get() = flags and TCP_FIN != 0
    val rst get() = flags and TCP_RST != 0

    /** MSS option value from a SYN, or [default]. */
    fun mss(default: Int): Int {
        var i = o + 20
        val end = o + headerLength
        while (i < end) {
            when (val kind = d.u8(i)) {
                0 -> return default
                1 -> i++
                else -> {
                    if (i + 1 >= end) return default
                    val len = d.u8(i + 1)
                    if (len < 2) return default
                    if (kind == 2 && len == 4 && i + 3 < end) return d.u16(i + 2)
                    i += len
                }
            }
        }
        return default
    }

    companion object {
        fun parse(packet: IpPacket): TcpHeader? {
            if (packet.transportLength < 20) return null
            val h = TcpHeader(packet)
            if (h.headerLength < 20 || h.payloadOffset > packet.length) return null
            return h
        }
    }
}

/** View of the UDP header inside an [IpPacket]. */
internal class UdpHeader(val packet: IpPacket) {
    private val d = packet.data
    private val o = packet.transportOffset
    val srcPort = d.u16(o)
    val dstPort = d.u16(o + 2)
    val payloadOffset = o + 8
    val payloadLength = (d.u16(o + 4) - 8).coerceAtMost(packet.length - payloadOffset)

    companion object {
        fun parse(packet: IpPacket): UdpHeader? {
            if (packet.transportLength < 8) return null
            val h = UdpHeader(packet)
            return if (h.payloadLength < 0) null else h
        }
    }
}

/** Builds IPv4/IPv6 + TCP/UDP/ICMP packets to write back into the TUN device. */
internal object PacketFactory {
    private val ipId = AtomicInteger(1)

    fun tcp(
        src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int,
        seq: Int, ack: Int, flags: Int, window: Int,
        payload: ByteArray? = null, payloadOff: Int = 0, payloadLen: Int = 0,
        mss: Int = 0,
    ): ByteArray {
        val optionsLen = if (mss > 0) 4 else 0
        val tcpLen = 20 + optionsLen + payloadLen
        val ipLen = if (src.size == 4) 20 else 40
        val packet = ByteArray(ipLen + tcpLen)
        writeIpHeader(packet, src, dst, PROTO_TCP, tcpLen)
        val o = ipLen
        packet.put16(o, srcPort)
        packet.put16(o + 2, dstPort)
        packet.put32(o + 4, seq)
        packet.put32(o + 8, ack)
        packet[o + 12] = (((20 + optionsLen) / 4) shl 4).toByte()
        packet[o + 13] = flags.toByte()
        packet.put16(o + 14, window.coerceIn(0, 0xFFFF))
        if (mss > 0) {
            packet[o + 20] = 2; packet[o + 21] = 4
            packet.put16(o + 22, mss)
        }
        if (payloadLen > 0) System.arraycopy(payload!!, payloadOff, packet, o + 20 + optionsLen, payloadLen)
        val sum = Checksum.sum(packet, o, tcpLen, Checksum.pseudoHeader(src, dst, PROTO_TCP, tcpLen))
        packet.put16(o + 16, Checksum.finish(sum))
        return packet
    }

    fun udp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        val udpLen = 8 + len
        val ipLen = if (src.size == 4) 20 else 40
        val packet = ByteArray(ipLen + udpLen)
        writeIpHeader(packet, src, dst, PROTO_UDP, udpLen)
        val o = ipLen
        packet.put16(o, srcPort)
        packet.put16(o + 2, dstPort)
        packet.put16(o + 4, udpLen)
        System.arraycopy(payload, off, packet, o + 8, len)
        var sum = Checksum.finish(Checksum.sum(packet, o, udpLen, Checksum.pseudoHeader(src, dst, PROTO_UDP, udpLen)))
        if (sum == 0) sum = 0xFFFF
        packet.put16(o + 6, sum)
        return packet
    }

    /** ICMP "port unreachable" for [original] so apps fall back fast (e.g. QUIC to TCP). */
    fun portUnreachable(original: IpPacket): ByteArray {
        val v4 = !original.isV6
        val quoted = minOf(original.length, if (v4) 548 else 1232)
        val icmpLen = 8 + quoted
        val ipLen = if (v4) 20 else 40
        val packet = ByteArray(ipLen + icmpLen)
        val protocol = if (v4) PROTO_ICMP else PROTO_ICMPV6
        writeIpHeader(packet, original.dst, original.src, protocol, icmpLen)
        val o = ipLen
        if (v4) { packet[o] = 3; packet[o + 1] = 3 } else { packet[o] = 1; packet[o + 1] = 4 }
        System.arraycopy(original.data, 0, packet, o + 8, quoted)
        val initial = if (v4) 0L else Checksum.pseudoHeader(original.dst, original.src, PROTO_ICMPV6, icmpLen)
        packet.put16(o + 2, Checksum.finish(Checksum.sum(packet, o, icmpLen, initial)))
        return packet
    }

    private fun writeIpHeader(packet: ByteArray, src: ByteArray, dst: ByteArray, protocol: Int, payloadLen: Int) {
        if (src.size == 4) {
            packet[0] = 0x45
            packet.put16(2, 20 + payloadLen)
            packet.put16(4, ipId.getAndIncrement() and 0xFFFF)
            packet.put16(6, 0x4000) // don't fragment
            packet[8] = 64
            packet[9] = protocol.toByte()
            System.arraycopy(src, 0, packet, 12, 4)
            System.arraycopy(dst, 0, packet, 16, 4)
            packet.put16(10, Checksum.finish(Checksum.sum(packet, 0, 20)))
        } else {
            packet[0] = 0x60
            packet.put16(4, payloadLen)
            packet[6] = protocol.toByte()
            packet[7] = 64
            System.arraycopy(src, 0, packet, 8, 16)
            System.arraycopy(dst, 0, packet, 24, 16)
        }
    }
}

/** Identity of a transport flow as seen from the app: (local addr, local port, remote addr, remote port). */
internal class FlowKey(val local: ByteArray, val localPort: Int, val remote: ByteArray, val remotePort: Int) {
    private val hash = (local.contentHashCode() * 31 + localPort) * 31 * 31 + remote.contentHashCode() * 31 + remotePort

    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is FlowKey && other.localPort == localPort &&
        other.remotePort == remotePort && other.local.contentEquals(local) && other.remote.contentEquals(remote)
}
