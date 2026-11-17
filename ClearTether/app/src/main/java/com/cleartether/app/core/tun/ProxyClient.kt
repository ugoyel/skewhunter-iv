package com.cleartether.app.core.tun

import com.cleartether.app.core.net.AsyncSocket
import com.cleartether.app.core.net.BufferedAsyncReader
import com.cleartether.app.core.net.Cidr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Where Client mode sends its traffic: the ClearTether host (or any HTTP CONNECT proxy). */
data class ProxyEndpoint(val address: InetAddress, val port: Int) {
    val socketAddress get() = InetSocketAddress(address, port)
    override fun toString(): String = "${address.hostAddress}:$port"
}

class ProxyHandshakeException(message: String) : IOException(message)

/**
 * Opens app connections as HTTP CONNECT tunnels through the host proxy. Destinations inside
 * [directSubnet] (the tether network, e.g. the host's setup page) are dialed directly.
 */
class ProxyTunnelDialer(
    private val proxy: ProxyEndpoint,
    private val directSubnet: Cidr? = null,
    private val handshakeTimeoutMs: Long = 15_000,
) : FlowDialer {

    override suspend fun dial(address: InetAddress, port: Int): FlowUpstream {
        if (directSubnet?.contains(address) == true) {
            return SocketFlowUpstream(AsyncSocket.connect(InetSocketAddress(address, port)))
        }
        val socket = AsyncSocket.connect(proxy.socketAddress)
        try {
            val authority = authority(address, port)
            socket.writeFully("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\nUser-Agent: ClearTether-Client/1\r\n\r\n")
            val reader = BufferedAsyncReader(socket, 1024)
            val head = readResponseHead(reader, handshakeTimeoutMs)
            if (head.status != 200) throw ProxyHandshakeException("Proxy refused $authority: ${head.status}")
            return SocketFlowUpstream(socket, reader.takeBuffered().takeIf { it.isNotEmpty() })
        } catch (t: Throwable) {
            socket.close()
            throw t
        }
    }

    companion object {
        fun authority(address: InetAddress, port: Int): String =
            if (address is Inet6Address) "[${address.hostAddress?.substringBefore('%')}]:$port" else "${address.hostAddress}:$port"
    }
}

internal class ResponseHead(val status: Int, val headers: Map<String, String>)

internal suspend fun readResponseHead(reader: BufferedAsyncReader, timeoutMs: Long): ResponseHead {
    while (reader.indexOfHeaderEnd() < 0) {
        if (reader.buffered > 16 * 1024) throw ProxyHandshakeException("Oversized proxy response")
        if (!reader.fillMore(timeoutMs)) throw ProxyHandshakeException("Proxy closed the connection")
    }
    return parseResponseHead(reader.take(reader.indexOfHeaderEnd()))
}

internal fun parseResponseHead(raw: ByteArray): ResponseHead {
    val lines = String(raw, Charsets.ISO_8859_1).split("\r\n", "\n").filter { it.isNotEmpty() }
    val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
        ?: throw ProxyHandshakeException("Malformed proxy response")
    val headers = lines.drop(1).mapNotNull { line ->
        val c = line.indexOf(':')
        if (c <= 0) null else line.substring(0, c).trim().lowercase() to line.substring(c + 1).trim()
    }.toMap()
    return ResponseHead(status, headers)
}

/** DNS over plain HTTP to the ClearTether host's `/dns-query` endpoint (local Wi-Fi link only). */
class HostDohUpstream(private val host: ProxyEndpoint) : DnsUpstream {
    override suspend fun exchange(query: ByteArray): ByteArray {
        AsyncSocket.connect(host.socketAddress).use { socket ->
            val head = "POST /dns-query HTTP/1.1\r\nHost: $host\r\nContent-Type: application/dns-message\r\n" +
                "Accept: application/dns-message\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
            socket.writeFully(head.toByteArray(Charsets.ISO_8859_1) + query)
            val reader = BufferedAsyncReader(socket, 2048)
            val response = readResponseHead(reader, 5_000)
            if (response.status != 200) throw IOException("DoH status ${response.status}")
            val length = response.headers["content-length"]?.toIntOrNull() ?: throw IOException("No length")
            if (length !in 12..65_535) throw IOException("Bad DNS length")
            return reader.readExactly(length, 5_000)
        }
    }
}

/**
 * Fallback for generic proxies without `/dns-query`: DNS-over-HTTPS to a public resolver through
 * a CONNECT tunnel, with full certificate and hostname verification.
 */
class TunneledDohUpstream(
    private val proxy: ProxyEndpoint,
    private val resolverIp: String = "1.1.1.1",
    private val resolverHost: String = "cloudflare-dns.com",
) : DnsUpstream {
    override suspend fun exchange(query: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        Socket().use { raw ->
            raw.soTimeout = 5_000
            raw.connect(proxy.socketAddress, 5_000)
            val out = raw.getOutputStream()
            out.write("CONNECT $resolverIp:443 HTTP/1.1\r\nHost: $resolverIp:443\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val head = readHeadBlocking(raw.getInputStream())
            if (parseResponseHead(head).status != 200) throw IOException("CONNECT refused")
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, resolverHost, 443, true) as SSLSocket
            tls.use { ssl ->
                ssl.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(resolverHost, ssl.session)) {
                    throw IOException("DoH certificate mismatch")
                }
                val request = "POST /dns-query HTTP/1.1\r\nHost: $resolverHost\r\nContent-Type: application/dns-message\r\n" +
                    "Accept: application/dns-message\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
                ssl.outputStream.write(request.toByteArray(Charsets.ISO_8859_1) + query)
                ssl.outputStream.flush()
                val input = ssl.inputStream
                val response = parseResponseHead(readHeadBlocking(input))
                if (response.status != 200) throw IOException("DoH status ${response.status}")
                val length = response.headers["content-length"]?.toIntOrNull() ?: throw IOException("No length")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) throw IOException("Truncated DoH response")
                    read += n
                }
                body
            }
        }
    }

    private fun readHeadBlocking(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Unexpected EOF")
            out.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (out.size() > 16 * 1024) throw IOException("Oversized head")
        }
        return out.toByteArray()
    }
}
