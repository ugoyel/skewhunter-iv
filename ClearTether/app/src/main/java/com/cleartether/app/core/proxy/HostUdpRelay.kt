package com.cleartether.app.core.proxy

import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.net.InetAddresses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Host side of a SOCKS5 `UDP ASSOCIATE` (RFC 1928 section 7).
 *
 * The client sends datagrams wrapped in the SOCKS UDP header to [port] on the tether interface.
 * The relay unwraps them and sends the payload from a socket on the phone's own (cellular)
 * network. Replies are wrapped with the sender's address and returned. This carries QUIC/HTTP3,
 * VoIP, games and other UDP traffic for ClearTether Client-mode devices.
 */
class HostUdpRelay(
    bindAddress: InetAddress,
    private val client: InetAddress,
    private val policy: AddressPolicy,
    private val meter: TrafficMeter,
) : Closeable {

    private val inbound = DatagramSocket(InetSocketAddress(bindAddress, 0))
    private val outbound = DatagramSocket()
    private val resolved = ConcurrentHashMap<String, InetAddress>()

    @Volatile private var clientEndpoint: InetSocketAddress? = null

    val port: Int get() = inbound.localPort

    /** Runs both relay loops until [close] is called. Loops block, so pass an elastic dispatcher. */
    suspend fun run(dispatcher: CoroutineDispatcher) = coroutineScope {
        launch(dispatcher) { runCatching { clientToInternet() } }
        launch(dispatcher) { runCatching { internetToClient() } }
    }

    private fun clientToInternet() {
        val buf = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        while (true) {
            packet.length = buf.size
            inbound.receive(packet)
            if (packet.address != client) continue // only the device that opened the association
            val len = packet.length
            if (len < 10 || buf[2].toInt() != 0) continue // too short or fragmented (unsupported)
            var offset = 4
            val target: InetAddress = when (buf[3].toInt()) {
                1 -> InetAddress.getByAddress(buf.copyOfRange(4, 8)).also { offset = 8 }
                4 -> {
                    if (len < 22) continue
                    InetAddress.getByAddress(buf.copyOfRange(4, 20)).also { offset = 20 }
                }
                3 -> {
                    val nameLen = buf[4].toInt() and 0xFF
                    if (len < 7 + nameLen) continue
                    val name = String(buf, 5, nameLen, Charsets.US_ASCII)
                    offset = 5 + nameLen
                    resolve(name) ?: continue
                }
                else -> continue
            }
            val targetPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            offset += 2
            if (targetPort == 0 || !policy.isDestinationAllowed(target)) continue
            clientEndpoint = InetSocketAddress(packet.address, packet.port)
            val payload = len - offset
            runCatching { outbound.send(DatagramPacket(buf, offset, payload, target, targetPort)) }
                .onSuccess { meter.onTraffic(client, payload.toLong(), 0) }
        }
    }

    private fun internetToClient() {
        val buf = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buf, HEADER_ROOM, buf.size - HEADER_ROOM)
        while (true) {
            packet.setData(buf, HEADER_ROOM, buf.size - HEADER_ROOM)
            outbound.receive(packet)
            val dest = clientEndpoint ?: continue
            val from = packet.address
            val addr = from.address
            val headerLen = 4 + addr.size + 2
            val start = HEADER_ROOM - headerLen
            buf[start] = 0; buf[start + 1] = 0; buf[start + 2] = 0
            buf[start + 3] = if (from is Inet4Address) 1 else 4
            System.arraycopy(addr, 0, buf, start + 4, addr.size)
            buf[start + 4 + addr.size] = (packet.port ushr 8).toByte()
            buf[start + 5 + addr.size] = packet.port.toByte()
            val payload = packet.length
            runCatching { inbound.send(DatagramPacket(buf, start, headerLen + payload, dest)) }
                .onSuccess { meter.onTraffic(client, 0, payload.toLong()) }
        }
    }

    private fun resolve(name: String): InetAddress? {
        resolved[name]?.let { return it }
        val address = InetAddresses.parseLiteral(name)
            ?: runCatching { InetAddress.getByName(name) }.getOrNull()
            ?: return null
        if (resolved.size > 256) resolved.clear()
        resolved[name] = address
        return address
    }

    override fun close() {
        inbound.close()
        outbound.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 65_535
        const val HEADER_ROOM = 22 // 4 + 16 (IPv6) + 2
    }
}
