package com.cleartether.app.core.tun

import com.cleartether.app.core.net.AsyncSocket
import com.cleartether.app.core.net.BufferedAsyncReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Carries app UDP (QUIC/HTTP3, VoIP, games) to the host using SOCKS5 UDP ASSOCIATE.
 *
 * Each app UDP socket (local address + port) gets its own association. Replies are tagged by the
 * relay with their true source, so this behaves like a full-cone NAT, which is what QUIC and
 * WebRTC expect.
 */
internal class UdpRelayClient(
    private val proxy: ProxyEndpoint,
    private val scope: CoroutineScope,
    private val blocking: CoroutineDispatcher,
    private val sink: PacketSink,
    private val mtu: Int,
    private val clock: () -> Long,
    private val onBytes: (uploaded: Long, downloaded: Long) -> Unit,
) {
    private class SourceKey(val address: ByteArray, val port: Int) {
        override fun hashCode() = address.contentHashCode() * 31 + port
        override fun equals(other: Any?) = other is SourceKey && other.port == port && other.address.contentEquals(address)
    }

    private inner class Association(val key: SourceKey) {
        @Volatile var lastActivity = clock()
        @Volatile var socket: DatagramSocket? = null
        @Volatile var relay: InetSocketAddress? = null
        @Volatile var closed = false
        val pending = ConcurrentLinkedQueue<ByteArray>()
        var control: AsyncSocket? = null
        var job: Job? = null

        fun start() {
            job = scope.launch {
                try {
                    val ctl = withTimeout(HANDSHAKE_TIMEOUT_MS) { handshake() }
                    control = ctl
                    flushPending()
                    launch(blocking) { receiveLoop() }
                    // Association ends when the host closes the control connection.
                    val sink = ByteArray(64)
                    while (ctl.read(sink) >= 0) {
                        // Nothing is expected on the control channel.
                    }
                } catch (e: Exception) {
                    if (!closed) markUnsupportedIfHandshakeFailed()
                } finally {
                    close()
                }
            }
        }

        private suspend fun handshake(): AsyncSocket {
            val ctl = AsyncSocket.connect(proxy.socketAddress)
            try {
                ctl.writeFully(byteArrayOf(5, 1, 0))
                val reader = BufferedAsyncReader(ctl, 64)
                val choice = reader.readExactly(2)
                if (choice[0].toInt() != 5 || choice[1].toInt() != 0) throw ProxyHandshakeException("SOCKS auth refused")
                ctl.writeFully(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                val reply = reader.readExactly(4)
                if (reply[1].toInt() != 0) throw ProxyHandshakeException("UDP ASSOCIATE refused: ${reply[1]}")
                val addrBytes = when (reply[3].toInt()) {
                    1 -> reader.readExactly(4)
                    4 -> reader.readExactly(16)
                    else -> throw ProxyHandshakeException("Unexpected relay address type")
                }
                val portBytes = reader.readExactly(2)
                val port = portBytes.u16(0)
                var relayAddress = InetAddress.getByAddress(addrBytes)
                if (relayAddress.isAnyLocalAddress) relayAddress = proxy.address
                relay = InetSocketAddress(relayAddress, port)
                socket = DatagramSocket()
                supported = true
                return ctl
            } catch (t: Throwable) {
                ctl.close()
                throw t
            }
        }

        fun send(datagram: ByteArray) {
            lastActivity = clock()
            val s = socket
            val r = relay
            if (s == null || r == null) {
                if (pending.size < MAX_PENDING) pending.add(datagram)
                return
            }
            runCatching { s.send(DatagramPacket(datagram, datagram.size, r)) }
        }

        private fun flushPending() {
            while (true) {
                val d = pending.poll() ?: break
                send(d)
            }
        }

        private fun receiveLoop() {
            val s = socket ?: return
            val buf = ByteArray(65_535)
            val packet = DatagramPacket(buf, buf.size)
            try {
                while (!closed) {
                    packet.setData(buf, 0, buf.size)
                    s.receive(packet)
                    val len = packet.length
                    if (len < 10 || buf[2].toInt() != 0) continue
                    val addrLen = when (buf[3].toInt()) { 1 -> 4; 4 -> 16; else -> continue }
                    if (len < 6 + addrLen) continue
                    if (addrLen != key.address.size) continue // family mismatch
                    val from = buf.copyOfRange(4, 4 + addrLen)
                    val fromPort = buf.u16(4 + addrLen)
                    val dataOffset = 6 + addrLen
                    val dataLength = len - dataOffset
                    val ipOverhead = if (addrLen == 4) 28 else 48
                    if (dataLength + ipOverhead > mtu) continue
                    lastActivity = clock()
                    onBytes(0, dataLength.toLong())
                    sink.offer(PacketFactory.udp(from, fromPort, key.address, key.port, buf, dataOffset, dataLength))
                }
            } catch (e: Exception) {
                // socket closed
            }
        }

        fun close() {
            if (closed) return
            closed = true
            associations.remove(key, this)
            control?.close()
            socket?.close()
            job?.cancel()
        }
    }

    private val associations = ConcurrentHashMap<SourceKey, Association>()
    @Volatile private var supported: Boolean? = null
    @Volatile private var unsupportedUntil = 0L

    /** Queues an app datagram. Returns false if UDP can't be relayed (caller sends ICMP unreachable). */
    fun send(local: ByteArray, localPort: Int, remote: ByteArray, remotePort: Int, payload: ByteArray, off: Int, len: Int): Boolean {
        if (supported != true && clock() < unsupportedUntil) return false
        val key = SourceKey(local, localPort)
        val association = associations[key] ?: run {
            if (associations.size >= MAX_ASSOCIATIONS) return false
            Association(key).also { created ->
                val existing = associations.putIfAbsent(key, created)
                if (existing == null) created.start() else return@run existing
            }
        }
        val header = 4 + remote.size + 2
        val datagram = ByteArray(header + len)
        datagram[3] = if (remote.size == 4) 1 else 4
        System.arraycopy(remote, 0, datagram, 4, remote.size)
        datagram.put16(4 + remote.size, remotePort)
        System.arraycopy(payload, off, datagram, header, len)
        association.send(datagram)
        onBytes(len.toLong(), 0)
        return true
    }

    private fun markUnsupportedIfHandshakeFailed() {
        if (supported != true) {
            supported = false
            unsupportedUntil = clock() + UNSUPPORTED_BACKOFF_MS
        }
    }

    /** Closes associations idle longer than the UDP flow timeout. */
    fun maintenance(now: Long) {
        for (a in associations.values) if (now - a.lastActivity > IDLE_TIMEOUT_MS) a.close()
    }

    fun closeAll() {
        associations.values.toList().forEach { it.close() }
    }

    val activeAssociations: Int get() = associations.size

    private companion object {
        const val MAX_ASSOCIATIONS = 256
        const val MAX_PENDING = 32
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val IDLE_TIMEOUT_MS = 120_000L
        const val UNSUPPORTED_BACKOFF_MS = 60_000L
    }
}
