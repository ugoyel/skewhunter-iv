package com.cleartether.app.core.tun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Raw packet I/O on a TUN device. Implemented over the VpnService fd on Android. */
interface PacketIo {
    /** Blocks until a packet is available. Returns its length, or -1 once closed. */
    fun read(buffer: ByteArray): Int
    fun write(packet: ByteArray)
    fun close()
}

data class StackConfig(
    val mtu: Int = 1500,
    /** Virtual resolver address handed to apps via `VpnService.Builder.addDnsServer`. */
    val virtualDns: ByteArray = byteArrayOf(10, 0, 0, 1),
    val maxTcpFlows: Int = 4096,
    val tcpIdleTimeoutMs: Long = 2 * 60 * 60_000L,
    val halfClosedTimeoutMs: Long = 120_000L,
    val lingerMs: Long = 4_000L,
)

/** Traffic counters for the UI (bytes as seen by apps on this device). */
class StackStats {
    val uploaded = AtomicLong()
    val downloaded = AtomicLong()
    @Volatile var tcpFlows = 0
    @Volatile var udpAssociations = 0
}

/**
 * User-space TCP/IP stack behind ClearTether Client mode.
 *
 * Reads raw IPv4/IPv6 packets from the VPN TUN interface and:
 *  - terminates TCP locally ([TcpFlow]) and relays each connection as a CONNECT tunnel
 *  - answers DNS (UDP/53 to any address, plus TCP/53 to the virtual resolver) through the host
 *  - relays other UDP through SOCKS5 UDP ASSOCIATE, or replies ICMP unreachable so apps fall back
 *
 * The result: every app on the device reaches the internet through the host phone, with no proxy
 * settings at all. That is the "transparent" part a non-rooted host can't do for other devices.
 */
class UserSpaceNetStack(
    private val io: PacketIo,
    private val config: StackConfig,
    private val tcpDialer: FlowDialer,
    private val dns: DnsForwarder,
    udpProxy: ProxyEndpoint?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    val stats = StackStats()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("UserSpaceNetStack") +
            CoroutineExceptionHandler { _, t -> log("Stack error: $t") },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val blocking = Dispatchers.IO.limitedParallelism(256)

    private val outbound = Channel<ByteArray>(OUTBOUND_QUEUE)
    private val sink = object : PacketSink {
        override fun offer(packet: ByteArray) {
            outbound.trySend(packet)
        }
        override suspend fun send(packet: ByteArray) {
            outbound.send(packet)
        }
    }

    private val flows = ConcurrentHashMap<FlowKey, TcpFlow>()
    private val countBytes: (Long, Long) -> Unit = { up, down ->
        if (up > 0) stats.uploaded.addAndGet(up)
        if (down > 0) stats.downloaded.addAndGet(down)
    }
    private val udpRelay = udpProxy?.let {
        UdpRelayClient(it, scope, blocking, sink, config.mtu, clock, countBytes)
    }

    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch(blocking) { readLoop() }
        scope.launch(blocking) { writeLoop() }
        scope.launch { maintenanceLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        flows.values.forEach { it.abort(sendReset = false) }
        flows.clear()
        udpRelay?.closeAll()
        outbound.close()
        io.close()
        scope.cancel(CancellationException("Stack stopped"))
    }

    private fun readLoop() {
        val buffer = ByteArray(config.mtu.coerceAtLeast(1500) + 64)
        while (running) {
            val n = try {
                io.read(buffer)
            } catch (e: Exception) {
                if (running) log("TUN read failed: $e")
                break
            }
            if (n < 0) break
            if (n == 0) continue
            try {
                // Safe to reuse the buffer: everything that outlives this call is copied out.
                handlePacket(buffer, n)
            } catch (e: Exception) {
                log("Dropping malformed packet: $e")
            }
        }
    }

    private suspend fun writeLoop() {
        for (packet in outbound) {
            try {
                io.write(packet)
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private suspend fun maintenanceLoop() {
        while (scope.isActive) {
            delay(TIMER_TICK_MS)
            val now = clock()
            val iterator = flows.entries.iterator()
            while (iterator.hasNext()) {
                val flow = iterator.next().value
                if (flow.isClosed) {
                    if (now - flow.closedAt > config.lingerMs) iterator.remove()
                    continue
                }
                flow.onTimer(now)
                val idle = now - flow.lastActivityAt
                if ((flow.isHalfClosedByApp && idle > config.halfClosedTimeoutMs) || idle > config.tcpIdleTimeoutMs) {
                    flow.abort(sendReset = true)
                }
            }
            udpRelay?.maintenance(now)
            stats.tcpFlows = flows.count { !it.value.isClosed }
            stats.udpAssociations = udpRelay?.activeAssociations ?: 0
        }
    }

    /** Diagnostic dump of live TCP flows. */
    fun debugFlows(): List<String> = flows.values.filter { !it.isClosed }.map { it.debugState() }

    internal fun handlePacket(data: ByteArray, length: Int) {
        val packet = IpPacket.parse(data, length) ?: return
        when (packet.protocol) {
            PROTO_TCP -> TcpHeader.parse(packet)?.let { handleTcp(packet, it) }
            PROTO_UDP -> UdpHeader.parse(packet)?.let { handleUdp(packet, it) }
            else -> Unit // ICMP echo etc. can't cross an HTTP proxy, so drop it.
        }
    }

    private fun handleTcp(packet: IpPacket, tcp: TcpHeader) {
        val key = FlowKey(packet.src, tcp.srcPort, packet.dst, tcp.dstPort)
        val existing = flows[key]
        if (existing != null && !(existing.isClosed && tcp.syn && !tcp.ackFlag)) {
            existing.onSegment(tcp)
            return
        }
        if (!tcp.syn || tcp.ackFlag) {
            if (!tcp.rst) sink.offer(resetFor(packet, tcp)) // no such connection
            return
        }
        if (packet.isMulticastOrBroadcastDestination() || flows.size >= config.maxTcpFlows) {
            sink.offer(resetFor(packet, tcp))
            return
        }
        val toVirtualDns = packet.dst.contentEquals(config.virtualDns)
        if (toVirtualDns && tcp.dstPort != 53) {
            // e.g. DNS-over-TLS probes to the virtual resolver: refuse at once so Android falls back.
            sink.offer(resetFor(packet, tcp))
            return
        }
        val flow = TcpFlow(
            key, tcp.seq, tcp.mss(536), tcp.window, config.mtu, sink, scope, clock, countBytes,
        )
        flows[key] = flow
        val destination = InetAddress.getByAddress(packet.dst)
        val port = tcp.dstPort
        flow.start {
            if (toVirtualDns) LocalDnsTcpUpstream(dns) else tcpDialer.dial(destination, port)
        }
    }

    private fun handleUdp(packet: IpPacket, udp: UdpHeader) {
        if (udp.dstPort == 53) {
            val query = packet.data.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
            val src = packet.src
            val dst = packet.dst
            val srcPort = udp.srcPort
            countBytes(query.size.toLong(), 0)
            scope.launch {
                var response = dns.resolve(query)
                val overhead = if (src.size == 4) 28 else 48
                if (response.size + overhead > config.mtu) response = DnsMessage.truncated(response)
                countBytes(0, response.size.toLong())
                sink.offer(PacketFactory.udp(dst, 53, src, srcPort, response))
            }
            return
        }
        if (packet.isMulticastOrBroadcastDestination()) return // mDNS/SSDP never leave the device
        val relayed = udpRelay?.send(packet.src, udp.srcPort, packet.dst, udp.dstPort, packet.data, udp.payloadOffset, udp.payloadLength) ?: false
        if (!relayed) sink.offer(PacketFactory.portUnreachable(packet))
    }

    private fun resetFor(packet: IpPacket, tcp: TcpHeader): ByteArray {
        val seqSpace = tcp.payloadLength + (if (tcp.syn) 1 else 0) + (if (tcp.fin) 1 else 0)
        return if (tcp.ackFlag) {
            PacketFactory.tcp(packet.dst, tcp.dstPort, packet.src, tcp.srcPort, tcp.ack, 0, TCP_RST, 0)
        } else {
            PacketFactory.tcp(packet.dst, tcp.dstPort, packet.src, tcp.srcPort, 0, tcp.seq + seqSpace, TCP_RST or TCP_ACK, 0)
        }
    }

    private companion object {
        const val OUTBOUND_QUEUE = 4096
        const val TIMER_TICK_MS = 100L
    }
}
