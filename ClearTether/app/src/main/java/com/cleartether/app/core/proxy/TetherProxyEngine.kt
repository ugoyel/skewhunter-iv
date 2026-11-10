package com.cleartether.app.core.proxy

import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.net.AsyncSocket
import com.cleartether.app.core.net.BlockedDestinationException
import com.cleartether.app.core.net.BufferedAsyncReader
import com.cleartether.app.core.net.DirectUpstreamConnector
import com.cleartether.app.core.net.UpstreamConnector
import com.cleartether.app.core.net.UpstreamTimeoutException
import com.cleartether.app.core.net.acceptSuspend
import com.cleartether.app.core.net.describeUpstreamFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.net.UnknownHostException
import java.nio.channels.AsynchronousServerSocketChannel
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Static configuration of a [TetherProxyEngine]. */
data class ProxyConfig(
    /** Address to listen on: the Wi-Fi Direct group-owner IP, never 0.0.0.0 in production. */
    val bindAddress: InetAddress,
    val port: Int = DEFAULT_PORT,
    val policy: AddressPolicy = AddressPolicy(),
    val captivePortalMitigation: Boolean = true,
    val maxConnections: Int = 4096,
    /** Tunnels with no traffic in either direction for this long are closed. */
    val idleTimeoutMs: Long = 30 * 60_000L,
    /** Time a client has to send its request line / SOCKS greeting. */
    val handshakeTimeoutMs: Long = 30_000L,
) {
    companion object {
        const val DEFAULT_PORT = 8228
    }
}

/**
 * ClearTether's user-space tethering proxy.
 *
 * One TCP port speaks several protocols, chosen by sniffing the first byte:
 *
 * | First byte | Protocol | Used by |
 * |---|---|---|
 * | `0x05` | SOCKS5 (CONNECT + UDP ASSOCIATE) | ClearTether Client mode (UDP), SOCKS-aware apps |
 * | `0x04` | SOCKS4 / 4a | legacy tools |
 * | ASCII  | HTTP/1.1 proxy: `CONNECT` blind tunnel + absolute-URI forwarding | every OS via PAC/system proxy |
 * | ASCII, origin-form | local setup site, PAC, profiles, DoH | freshly joined clients |
 *
 * ### Corporate VPN / SSL-pinning compatibility
 * `CONNECT` tunnels are a blind layer-4 conduit. After the `200` line the engine copies bytes
 * and never inspects, decrypts or re-signs them. TLS certificates reach the client exactly as
 * the origin sent them, so certificate pinning, mutual TLS and EDR/MITM detectors on managed
 * laptops see a clean, unmodified path.
 *
 * ### Concurrency
 * Built on [AsynchronousSocketChannel][java.nio.channels.AsynchronousSocketChannel] and
 * coroutines. Idle tunnels hold no threads, and each connection is a child [Job] of the engine
 * scope, so [pause] and [stop] cancel and close everything deterministically.
 */
class TetherProxyEngine(
    private val config: ProxyConfig,
    private val upstream: UpstreamConnector = DirectUpstreamConnector(config.policy),
    private val meter: TrafficMeter = TrafficMeter.NONE,
    private val dns: DnsBackend? = null,
    private val site: LocalSite? = null,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TetherProxyEngine") +
            CoroutineExceptionHandler { _, t -> log("Unhandled proxy error: $t") },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val blockingIo = Dispatchers.IO.limitedParallelism(BLOCKING_THREADS)

    private val connections: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private var server: AsynchronousServerSocketChannel? = null

    @Volatile private var pauseReason: String? = null
    @Volatile private var stopped = false

    /** Number of in-flight client connections (tunnels, SOCKS sessions, local requests). */
    val activeConnections: Int get() = connections.size

    val isPaused: Boolean get() = pauseReason != null

    /** The bound address once [start] returns. */
    var boundAddress: InetSocketAddress? = null
        private set

    /** Binds the listening socket and starts accepting. Throws if the port is unavailable. */
    @Synchronized
    fun start(): InetSocketAddress {
        check(!stopped) { "Engine already stopped" }
        boundAddress?.let { return it }
        val channel = AsynchronousServerSocketChannel.open()
        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            channel.bind(InetSocketAddress(config.bindAddress, config.port), BACKLOG)
        } catch (t: Throwable) {
            runCatching { channel.close() }
            throw t
        }
        server = channel
        val address = channel.localAddress as InetSocketAddress
        boundAddress = address
        scope.launch { acceptLoop(channel) }
        log("Proxy listening on ${address.address.hostAddress}:${address.port}")
        return address
    }

    /** Rejects new traffic (503 / SOCKS "not allowed") and closes every open tunnel. */
    fun pause(reason: String) {
        pauseReason = reason
        connections.forEach { it.cancel(CancellationException("Paused: $reason")) }
    }

    fun resume() {
        pauseReason = null
    }

    /** Closes the listener and all connections. The engine can't be restarted. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { server?.close() }
        server = null
        scope.cancel(CancellationException("Proxy stopped"))
    }

    private suspend fun acceptLoop(channel: AsynchronousServerSocketChannel) {
        while (scope.isActive && channel.isOpen) {
            val client = try {
                channel.acceptSuspend()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!channel.isOpen) break
                log("accept failed: $e")
                delay(ACCEPT_BACKOFF_MS) // e.g. EMFILE: back off instead of spinning
                continue
            }
            if (connections.size >= config.maxConnections) {
                client.close()
                continue
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { handleClient(client) }
            connections += job
            job.invokeOnCompletion {
                connections -= job
                client.close()
            }
            job.start()
        }
    }

    private suspend fun handleClient(client: AsyncSocket) {
        val peer = client.remoteAddress?.address
        if (peer == null || !config.policy.isClientAllowed(peer)) {
            log("Rejected client ${peer?.hostAddress}")
            client.close()
            return
        }
        meter.onConnectionOpened(peer)
        try {
            val reader = BufferedAsyncReader(client)
            if (!reader.fill(1, config.handshakeTimeoutMs)) return
            when (reader.peek(0)) {
                SOCKS5 -> handleSocks5(client, reader, peer)
                SOCKS4 -> handleSocks4(client, reader, peer)
                else -> handleHttp(client, reader, peer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Peer resets, timeouts and protocol errors are routine on a proxy.
        } catch (e: Exception) {
            log("Connection from ${peer.hostAddress} failed: $e")
        } finally {
            client.close()
            meter.onConnectionClosed(peer)
        }
    }

    // ---------------------------------------------------------------- HTTP

    private suspend fun handleHttp(client: AsyncSocket, reader: BufferedAsyncReader, peer: InetAddress) {
        val head = try {
            HttpRequestHead.read(reader, timeoutMs = config.handshakeTimeoutMs) ?: return
        } catch (e: HttpProtocolException) {
            HttpResponse.errorPage(400, "Bad Request", e.message ?: "Malformed request").writeTo(client)
            return
        }

        if (head.method == "CONNECT") return handleConnect(client, reader, head, peer)

        val absolute = AbsoluteUri.parse(head.target)
        if (absolute == null) {
            if (!head.target.startsWith("/")) {
                HttpResponse.errorPage(400, "Bad Request", "Unsupported request target.").writeTo(client)
                return
            }
            return serveLocal(client, reader, head)
        }

        val target = absolute.hostPort
        if (target.host == LocalSite.PROXY_CHECK_HOST) {
            HttpResponse(
                200, "OK", "application/json", """{"proxied":true,"paused":${isPaused}}""".toByteArray(),
                listOf("Access-Control-Allow-Origin" to "*"),
            ).writeTo(client)
            return
        }
        if (isSelf(target)) return serveLocal(client, reader, head, absolute.pathAndQuery)
        if (absolute.scheme != "http") {
            HttpResponse.errorPage(400, "Bad Request", "Use CONNECT for ${absolute.scheme} URLs.").writeTo(client)
            return
        }
        pauseReason?.let { reason ->
            pausedPage(reason).writeTo(client, headOnly = head.method == "HEAD")
            return
        }
        if (config.captivePortalMitigation) {
            CaptivePortal.match(target.host, absolute.pathAndQuery)?.let {
                it.writeTo(client, headOnly = head.method == "HEAD")
                return
            }
        }
        forwardHttp(client, reader, head, absolute, peer)
    }

    private suspend fun handleConnect(
        client: AsyncSocket,
        reader: BufferedAsyncReader,
        head: HttpRequestHead,
        peer: InetAddress,
    ) {
        val target = HostPort.parseAuthority(head.target, 443)
        if (target == null) {
            HttpResponse.errorPage(400, "Bad Request", "Invalid CONNECT target.").writeTo(client)
            return
        }
        pauseReason?.let { pausedPage(it).writeTo(client); return }
        if (isSelf(target)) {
            HttpResponse.errorPage(403, "Forbidden", "Loop detected.").writeTo(client)
            return
        }
        val remote = try {
            upstream.connect(target.host, target.port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            upstreamFailurePage(e, target).writeTo(client)
            return
        }
        remote.use {
            client.writeFully(CONNECT_ESTABLISHED)
            // Clients may pipeline the TLS ClientHello right behind the CONNECT request.
            val early = reader.takeBuffered()
            if (early.isNotEmpty()) {
                remote.writeFully(early)
                meter.onTraffic(peer, early.size.toLong(), 0)
            }
            relay(client, remote, peer)
        }
    }

    private suspend fun forwardHttp(
        client: AsyncSocket,
        reader: BufferedAsyncReader,
        head: HttpRequestHead,
        uri: AbsoluteUri,
        peer: InetAddress,
    ) {
        val remote = try {
            upstream.connect(uri.hostPort.host, uri.hostPort.port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            upstreamFailurePage(e, uri.hostPort).writeTo(client)
            return
        }
        remote.use {
            val connectionTokens = head.header("Connection")
                ?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.toSet().orEmpty()
            val isUpgrade = head.header("Upgrade") != null && "upgrade" in connectionTokens
            val out = StringBuilder(512)
            out.append(head.method).append(' ').append(uri.pathAndQuery).append(" HTTP/1.1\r\n")
            var hasHost = false
            for ((name, value) in head.headers) {
                val lower = name.lowercase(Locale.ROOT)
                if (lower == "host") hasHost = true
                val hopByHop = lower in HOP_BY_HOP_HEADERS || lower in connectionTokens
                if (hopByHop && !(isUpgrade && lower == "upgrade")) continue
                out.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) {
                val defaultPort = uri.hostPort.port == 80
                out.append("Host: ").append(if (defaultPort) hostLiteral(uri.hostPort.host) else uri.hostPort.toString()).append("\r\n")
            }
            // Close-delimited exchanges keep the proxy stateless and correct without parsing response framing.
            out.append(if (isUpgrade) "Connection: Upgrade\r\n" else "Connection: close\r\n")
            out.append("\r\n")
            val headBytes = out.toString().toByteArray(Charsets.ISO_8859_1)
            remote.writeFully(headBytes)
            val early = reader.takeBuffered()
            if (early.isNotEmpty()) remote.writeFully(early)
            meter.onTraffic(peer, (headBytes.size + early.size).toLong(), 0)
            relay(client, remote, peer)
        }
    }

    private suspend fun serveLocal(
        client: AsyncSocket,
        reader: BufferedAsyncReader,
        head: HttpRequestHead,
        overrideTarget: String? = null,
    ) {
        val request = if (overrideTarget != null) HttpRequestHead(head.method, overrideTarget, head.version, head.headers) else head
        val length = request.header("Content-Length")?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_LOCAL_BODY) {
            HttpResponse.errorPage(413, "Payload Too Large", "Request body too large.").writeTo(client)
            return
        }
        val body = if (length > 0) reader.readExactly(length, config.handshakeTimeoutMs) else ByteArray(0)
        val response = site?.handle(request, body, dns)
            ?: HttpResponse.errorPage(404, "Not found", "ClearTether proxy is running.")
        response.writeTo(client, headOnly = request.method == "HEAD")
    }

    private fun isSelf(target: HostPort): Boolean {
        val bound = boundAddress ?: return false
        return target.port == bound.port &&
            (target.host == bound.address.hostAddress || target.host == config.bindAddress.hostAddress)
    }

    private fun pausedPage(reason: String) = HttpResponse.errorPage(
        503, "Session limit reached",
        "$reason Open ClearTether on the host phone to upgrade to Premium for unlimited tethering.",
    )

    private fun upstreamFailurePage(e: Throwable, target: HostPort): HttpResponse {
        val reason = e.describeUpstreamFailure()
        return when (e) {
            is BlockedDestinationException -> HttpResponse.errorPage(403, "Forbidden", "$target can't be reached through the tether.")
            is UnknownHostException -> HttpResponse.errorPage(502, "Bad Gateway", "$reason: ${target.host}")
            is UpstreamTimeoutException -> HttpResponse.errorPage(504, "Gateway Timeout", "$reason: $target")
            else -> HttpResponse.errorPage(502, "Bad Gateway", "$reason: $target")
        }
    }

    // ---------------------------------------------------------------- SOCKS

    private suspend fun handleSocks5(client: AsyncSocket, reader: BufferedAsyncReader, peer: InetAddress) {
        val t = config.handshakeTimeoutMs
        reader.readByte(t) // version
        val methodCount = reader.readByte(t)
        val methods = reader.readExactly(methodCount, t)
        if (methods.none { it.toInt() == 0 }) {
            client.writeFully(byteArrayOf(5, 0xFF.toByte()))
            return
        }
        client.writeFully(byteArrayOf(5, 0))

        val request = reader.readExactly(4, t)
        if (request[0].toInt() != 5) return
        val command = request[1].toInt()
        val host: String = when (request[3].toInt()) {
            1 -> InetAddress.getByAddress(reader.readExactly(4, t)).hostAddress!!
            3 -> String(reader.readExactly(reader.readByte(t), t), Charsets.US_ASCII)
            4 -> InetAddress.getByAddress(reader.readExactly(16, t)).hostAddress!!
            else -> { socks5Reply(client, SOCKS5_ADDRESS_TYPE_UNSUPPORTED); return }
        }
        val portBytes = reader.readExactly(2, t)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        if (pauseReason != null) { socks5Reply(client, SOCKS5_NOT_ALLOWED); return }
        when (command) {
            1 -> {
                if (port == 0) { socks5Reply(client, SOCKS5_GENERAL_FAILURE); return }
                if (isSelf(HostPort(host, port))) { socks5Reply(client, SOCKS5_NOT_ALLOWED); return }
                val remote = try {
                    upstream.connect(host, port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    socks5Reply(client, socks5ErrorCode(e))
                    return
                }
                remote.use {
                    socks5Reply(client, SOCKS5_SUCCEEDED, remote.localAddress)
                    val early = reader.takeBuffered()
                    if (early.isNotEmpty()) {
                        remote.writeFully(early)
                        meter.onTraffic(peer, early.size.toLong(), 0)
                    }
                    relay(client, remote, peer)
                }
            }
            3 -> handleUdpAssociate(client, peer)
            else -> socks5Reply(client, SOCKS5_COMMAND_UNSUPPORTED)
        }
    }

    private suspend fun handleUdpAssociate(client: AsyncSocket, peer: InetAddress) {
        val localIp = client.localAddress?.address ?: config.bindAddress
        val relay = try {
            HostUdpRelay(localIp, peer, config.policy, meter)
        } catch (e: IOException) {
            socks5Reply(client, SOCKS5_GENERAL_FAILURE)
            return
        }
        try {
            coroutineScope {
                launch { relay.run(blockingIo) }
                socks5Reply(client, SOCKS5_SUCCEEDED, InetSocketAddress(localIp, relay.port))
                // The association lives exactly as long as the TCP control connection.
                val sink = ByteArray(256)
                try {
                    while (client.read(sink) >= 0) {
                        // Discard: the client only closes this connection to end the association.
                    }
                } finally {
                    relay.close()
                }
            }
        } finally {
            relay.close()
        }
    }

    private suspend fun handleSocks4(client: AsyncSocket, reader: BufferedAsyncReader, peer: InetAddress) {
        val t = config.handshakeTimeoutMs
        val header = reader.readExactly(8, t)
        val command = header[1].toInt()
        val port = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val ip = header.copyOfRange(4, 8)
        readNullTerminated(reader, t) // user id, ignored
        val host = if (ip[0].toInt() == 0 && ip[1].toInt() == 0 && ip[2].toInt() == 0 && ip[3].toInt() != 0) {
            readNullTerminated(reader, t) // SOCKS4a domain name
        } else {
            InetAddress.getByAddress(ip).hostAddress!!
        }
        val reject = byteArrayOf(0, 0x5B, 0, 0, 0, 0, 0, 0)
        if (command != 1 || pauseReason != null || port == 0 || isSelf(HostPort(host, port))) {
            client.writeFully(reject)
            return
        }
        val remote = try {
            upstream.connect(host, port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            client.writeFully(reject)
            return
        }
        remote.use {
            client.writeFully(byteArrayOf(0, 0x5A, 0, 0, 0, 0, 0, 0))
            val early = reader.takeBuffered()
            if (early.isNotEmpty()) remote.writeFully(early)
            relay(client, remote, peer)
        }
    }

    private suspend fun readNullTerminated(reader: BufferedAsyncReader, timeoutMs: Long): String {
        val sb = StringBuilder()
        while (true) {
            val b = reader.readByte(timeoutMs)
            if (b == 0) return sb.toString()
            if (sb.length >= 255) throw IOException("SOCKS4 field too long")
            sb.append(b.toChar())
        }
    }

    private suspend fun socks5Reply(client: AsyncSocket, code: Int, bound: InetSocketAddress? = null) {
        val addr = bound?.address?.address ?: byteArrayOf(0, 0, 0, 0)
        val port = bound?.port ?: 0
        val reply = ByteArray(6 + addr.size)
        reply[0] = 5
        reply[1] = code.toByte()
        reply[2] = 0
        reply[3] = if (addr.size == 4) 1 else 4
        System.arraycopy(addr, 0, reply, 4, addr.size)
        reply[4 + addr.size] = (port ushr 8).toByte()
        reply[5 + addr.size] = port.toByte()
        client.writeFully(reply)
    }

    private fun socks5ErrorCode(e: Throwable) = when (e) {
        is BlockedDestinationException -> SOCKS5_NOT_ALLOWED
        is UnknownHostException -> SOCKS5_HOST_UNREACHABLE
        is UpstreamTimeoutException -> SOCKS5_TTL_EXPIRED
        is ConnectException -> SOCKS5_CONNECTION_REFUSED
        else -> SOCKS5_NETWORK_UNREACHABLE
    }

    // ---------------------------------------------------------------- relay

    /**
     * Copies bytes both ways until both directions reach EOF, either side fails, or the tunnel is
     * idle for [ProxyConfig.idleTimeoutMs]. Half-closes are propagated, so protocols that send a
     * request and then shut down their write side keep working.
     */
    private suspend fun relay(client: AsyncSocket, remote: AsyncSocket, peer: InetAddress) = coroutineScope {
        val lastActivity = AtomicLong(System.nanoTime())
        val watchdog = launch {
            val idleNanos = config.idleTimeoutMs * 1_000_000
            while (isActive) {
                delay(minOf(config.idleTimeoutMs / 4, 60_000L).coerceAtLeast(10L))
                if (System.nanoTime() - lastActivity.get() > idleNanos) {
                    client.close()
                    remote.close()
                    break
                }
            }
        }
        val up = launch {
            pump(client, remote, lastActivity) { meter.onTraffic(peer, it.toLong(), 0) }
            remote.shutdownOutput()
        }
        val down = launch {
            pump(remote, client, lastActivity) { meter.onTraffic(peer, 0, it.toLong()) }
            client.shutdownOutput()
        }
        try {
            joinAll(up, down)
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun pump(from: AsyncSocket, to: AsyncSocket, activity: AtomicLong, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(RELAY_BUFFER)
        while (true) {
            val n = try {
                from.read(buffer)
            } catch (e: IOException) {
                if (!from.isOpen) return else throw e
            }
            if (n < 0) return
            if (n == 0) continue
            activity.set(System.nanoTime())
            to.writeFully(buffer, 0, n)
            onBytes(n)
        }
    }

    private fun hostLiteral(host: String) = if (host.contains(':')) "[$host]" else host

    companion object {
        private const val SOCKS5 = 0x05
        private const val SOCKS4 = 0x04
        private const val BACKLOG = 256
        private const val RELAY_BUFFER = 16 * 1024
        private const val MAX_LOCAL_BODY = 64 * 1024
        private const val ACCEPT_BACKOFF_MS = 100L
        private const val BLOCKING_THREADS = 512

        private const val SOCKS5_SUCCEEDED = 0x00
        private const val SOCKS5_GENERAL_FAILURE = 0x01
        private const val SOCKS5_NOT_ALLOWED = 0x02
        private const val SOCKS5_NETWORK_UNREACHABLE = 0x03
        private const val SOCKS5_HOST_UNREACHABLE = 0x04
        private const val SOCKS5_CONNECTION_REFUSED = 0x05
        private const val SOCKS5_TTL_EXPIRED = 0x06
        private const val SOCKS5_COMMAND_UNSUPPORTED = 0x07
        private const val SOCKS5_ADDRESS_TYPE_UNSUPPORTED = 0x08

        private val CONNECT_ESTABLISHED =
            "HTTP/1.1 200 Connection Established\r\nProxy-Agent: ClearTether\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        /** True if [address] is an IPv4 address in the Wi-Fi Direct group subnet. */
        fun isGroupAddress(address: InetAddress): Boolean =
            address is Inet4Address && address.address[0] == 192.toByte() && address.address[1] == 168.toByte() &&
                address.address[2] == 49.toByte()
    }
}
