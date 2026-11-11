package com.cleartether.app.proxy

import com.cleartether.app.EchoServer
import com.cleartether.app.TinyHttpServer
import com.cleartether.app.UdpEchoServer
import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.net.DirectUpstreamConnector
import com.cleartether.app.core.proxy.ClientRegistry
import com.cleartether.app.core.proxy.DnsBackend
import com.cleartether.app.core.proxy.HostSiteInfo
import com.cleartether.app.core.proxy.LocalSite
import com.cleartether.app.core.proxy.ProxyConfig
import com.cleartether.app.core.proxy.TetherEndpoint
import com.cleartether.app.core.proxy.TetherProxyEngine
import com.cleartether.app.readExactly
import com.cleartether.app.readHead
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import kotlin.random.Random

class TetherProxyEngineTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val permissive = AddressPolicy(
        allowedClientNetworks = emptyList(),
        allowLoopbackClients = true,
        blockedDestinationNetworks = emptyList(),
        allowLoopbackDestinations = true,
    )
    private val registry = ClientRegistry()
    private val cleanups = mutableListOf<AutoCloseable>()
    private lateinit var engine: TetherProxyEngine

    private fun startEngine(
        policy: AddressPolicy = permissive,
        dns: DnsBackend? = null,
    ): Int {
        val config = ProxyConfig(bindAddress = loopback, port = 0, policy = policy)
        val site = LocalSite { HostSiteInfo(TetherEndpoint("DIRECT-CT-Test", "secret123", "192.168.49.1", 8228)) }
        engine = TetherProxyEngine(config, DirectUpstreamConnector(policy), registry, dns, site)
        return engine.start().port
    }

    private fun connect(port: Int) = Socket().apply {
        connect(InetSocketAddress(loopback, port), 5_000)
        soTimeout = 10_000
        cleanups += this
    }

    @After
    fun tearDown() {
        cleanups.forEach { runCatching { it.close() } }
        if (::engine.isInitialized) engine.stop()
    }

    @Test
    fun connectTunnelRelaysBytesBlindlyIncludingPipelinedData() {
        val echo = EchoServer().also { cleanups += it }
        val port = startEngine()
        val s = connect(port)
        val payload = Random.nextBytes(2 * 1024 * 1024)
        // Pipeline the first bytes right behind the CONNECT head, like TLS clients do.
        s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray() + payload.copyOfRange(0, 1000))
        val head = readHead(s.getInputStream())
        assertTrue(head, head.startsWith("HTTP/1.1 200"))
        val writer = Thread { s.getOutputStream().write(payload, 1000, payload.size - 1000); s.shutdownOutput() }
        writer.start()
        val echoed = s.getInputStream().readBytes()
        writer.join()
        assertEquals(payload.size, echoed.size)
        assertArrayEquals(sha(payload), sha(echoed))
        val stats = registry.snapshot().single()
        assertEquals(payload.size.toLong(), stats.bytesUploaded)
        assertEquals(payload.size.toLong(), stats.bytesDownloaded)
    }

    @Test
    fun httpForwardRewritesToOriginFormAndStripsHopByHopHeaders() {
        val origin = TinyHttpServer().also { cleanups += it }
        val port = startEngine()
        val s = connect(port)
        s.getOutputStream().write(
            ("GET http://127.0.0.1:${origin.port}/hello?x=1 HTTP/1.1\r\nHost: 127.0.0.1:${origin.port}\r\n" +
                "Proxy-Connection: keep-alive\r\nProxy-Authorization: Basic Zm9v\r\nConnection: keep-alive, X-Secret\r\n" +
                "X-Secret: drop-me\r\nAccept: */*\r\n\r\n").toByteArray(),
        )
        val response = String(s.getInputStream().readBytes())
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.endsWith("hello from origin"))
        val forwarded = origin.requests.single()
        assertTrue(forwarded, forwarded.startsWith("GET /hello?x=1 HTTP/1.1\r\n"))
        assertTrue(forwarded.contains("Connection: close"))
        assertTrue(forwarded.contains("Accept: */*"))
        assertFalse(forwarded.contains("Proxy-Connection"))
        assertFalse(forwarded.contains("Proxy-Authorization"))
        assertFalse(forwarded.contains("X-Secret"))
    }

    @Test
    fun captivePortalProbesAreAnsweredLocally() {
        val port = startEngine()
        val apple = connect(port)
        apple.getOutputStream().write("GET http://captive.apple.com/hotspot-detect.html HTTP/1.1\r\nHost: captive.apple.com\r\n\r\n".toByteArray())
        val appleResponse = String(apple.getInputStream().readBytes())
        assertTrue(appleResponse.startsWith("HTTP/1.1 200"))
        assertTrue(appleResponse.contains("<BODY>Success</BODY>"))

        val android = connect(port)
        android.getOutputStream().write("GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: connectivitycheck.gstatic.com\r\n\r\n".toByteArray())
        assertTrue(String(android.getInputStream().readBytes()).startsWith("HTTP/1.1 204"))

        val windows = connect(port)
        windows.getOutputStream().write("GET http://www.msftconnecttest.com/connecttest.txt HTTP/1.1\r\nHost: www.msftconnecttest.com\r\n\r\n".toByteArray())
        assertTrue(String(windows.getInputStream().readBytes()).endsWith("Microsoft Connect Test"))
    }

    @Test
    fun localSiteServesPacProfileAndProxyCheck() {
        val port = startEngine()
        val pac = connect(port)
        pac.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\nHost: 192.168.49.1:8228\r\n\r\n".toByteArray())
        val pacText = String(pac.getInputStream().readBytes())
        assertTrue(pacText.contains("application/x-ns-proxy-autoconfig"))
        assertTrue(pacText.contains("PROXY 192.168.49.1:8228; DIRECT"))

        val profile = connect(port)
        profile.getOutputStream().write("GET /cleartether.mobileconfig HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val profileText = String(profile.getInputStream().readBytes())
        assertTrue(profileText.contains("<string>DIRECT-CT-Test</string>"))
        assertTrue(profileText.contains("<key>ProxyPACURL</key><string>http://192.168.49.1:8228/proxy.pac</string>"))

        val check = connect(port)
        check.getOutputStream().write("GET http://check.cleartether.invalid/ping HTTP/1.1\r\nHost: check.cleartether.invalid\r\n\r\n".toByteArray())
        val checkText = String(check.getInputStream().readBytes())
        assertTrue(checkText.contains("\"proxied\":true"))
        assertTrue(checkText.contains("Access-Control-Allow-Origin: *"))

        val portal = connect(port)
        portal.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nUser-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)\r\n\r\n".toByteArray())
        val portalText = String(portal.getInputStream().readBytes())
        assertTrue(portalText.contains("This device"))
        assertTrue(portalText.contains("iPhone &amp; iPad"))
    }

    @Test
    fun dnsQueryEndpointUsesBackend() {
        val port = startEngine(dns = { q -> q.copyOf().also { it[2] = 0x81.toByte() } })
        val s = connect(port)
        val query = ByteArray(29) { it.toByte() }
        s.getOutputStream().write("POST /dns-query HTTP/1.1\r\nHost: x\r\nContent-Type: application/dns-message\r\nContent-Length: ${query.size}\r\n\r\n".toByteArray() + query)
        val head = readHead(s.getInputStream())
        assertTrue(head, head.startsWith("HTTP/1.1 200"))
        assertTrue(head.contains("application/dns-message"))
        val body = s.getInputStream().readExactly(query.size)
        assertEquals(0x81.toByte(), body[2])
    }

    @Test
    fun socks5ConnectAndSocks4aConnectWork() {
        val echo = EchoServer().also { cleanups += it }
        val port = startEngine()

        val s5 = connect(port)
        val o = s5.getOutputStream()
        val i = s5.getInputStream()
        o.write(byteArrayOf(5, 1, 0))
        assertArrayEquals(byteArrayOf(5, 0), i.readExactly(2))
        val host = "localhost".toByteArray()
        o.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf((echo.port shr 8).toByte(), echo.port.toByte()))
        val reply = i.readExactly(4)
        assertEquals(0, reply[1].toInt())
        i.readExactly(if (reply[3].toInt() == 1) 6 else 18)
        o.write("ping-socks5".toByteArray())
        assertEquals("ping-socks5", String(i.readExactly(11)))

        val s4 = connect(port)
        s4.getOutputStream().write(
            byteArrayOf(4, 1, (echo.port shr 8).toByte(), echo.port.toByte(), 0, 0, 0, 1) + "user".toByteArray() + 0 +
                "localhost".toByteArray() + 0,
        )
        val r4 = s4.getInputStream().readExactly(8)
        assertEquals(0x5A, r4[1].toInt())
        s4.getOutputStream().write("ping-socks4".toByteArray())
        assertEquals("ping-socks4", String(s4.getInputStream().readExactly(11)))
    }

    @Test
    fun socks5UdpAssociateRelaysDatagrams() {
        val udpEcho = UdpEchoServer().also { cleanups += it }
        val port = startEngine()
        val control = connect(port)
        val o = control.getOutputStream()
        val i = control.getInputStream()
        o.write(byteArrayOf(5, 1, 0)); i.readExactly(2)
        o.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
        val reply = i.readExactly(10)
        assertEquals(0, reply[1].toInt())
        val relayPort = ((reply[8].toInt() and 0xFF) shl 8) or (reply[9].toInt() and 0xFF)

        DatagramSocket(0, loopback).use { udp ->
            udp.soTimeout = 5_000
            val payload = "quic-like-datagram".toByteArray()
            val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (udpEcho.port shr 8).toByte(), udpEcho.port.toByte())
            udp.send(DatagramPacket(header + payload, header.size + payload.size, loopback, relayPort))
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            udp.receive(p)
            assertEquals(1, buf[3].toInt())
            val fromPort = ((buf[8].toInt() and 0xFF) shl 8) or (buf[9].toInt() and 0xFF)
            assertEquals(udpEcho.port, fromPort)
            assertEquals("quic-like-datagram", String(buf, 10, p.length - 10))
        }
    }

    @Test
    fun loopbackDestinationsAreBlockedByDefaultPolicy() {
        val echo = EchoServer().also { cleanups += it }
        val strictDestinations = AddressPolicy(allowedClientNetworks = emptyList(), allowLoopbackClients = true)
        val port = startEngine(policy = strictDestinations)
        val s = connect(port)
        s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(readHead(s.getInputStream()).startsWith("HTTP/1.1 403"))
        val s2 = connect(port)
        s2.getOutputStream().write("CONNECT localhost:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(readHead(s2.getInputStream()).startsWith("HTTP/1.1 403"))
    }

    @Test
    fun clientsOutsideTheGroupSubnetAreRejected() {
        val port = startEngine(policy = AddressPolicy()) // loopback clients not allowed
        val s = connect(port)
        s.getOutputStream().write("GET /proxy.pac HTTP/1.1\r\n\r\n".toByteArray())
        assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
    }

    @Test
    fun pauseClosesTunnelsAndRejectsNewOnes() {
        val echo = EchoServer().also { cleanups += it }
        val port = startEngine()
        val s = connect(port)
        s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(readHead(s.getInputStream()).startsWith("HTTP/1.1 200"))
        engine.pause("Free session limit reached.")
        assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))

        val s2 = connect(port)
        s2.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(readHead(s2.getInputStream()).startsWith("HTTP/1.1 503"))

        engine.resume()
        val s3 = connect(port)
        s3.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(readHead(s3.getInputStream()).startsWith("HTTP/1.1 200"))
    }

    @Test
    fun manyConcurrentTunnels() {
        val echo = EchoServer().also { cleanups += it }
        val port = startEngine()
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..64).map { n ->
            Thread {
                val s = connect(port)
                s.getOutputStream().write("CONNECT 127.0.0.1:${echo.port} HTTP/1.1\r\n\r\n".toByteArray())
                check(readHead(s.getInputStream()).startsWith("HTTP/1.1 200"))
                val msg = "tunnel-$n-".repeat(500).toByteArray()
                s.getOutputStream().write(msg)
                check(s.getInputStream().readExactly(msg.size).contentEquals(msg))
                s.close()
                ok.incrementAndGet()
            }.apply { start() }
        }
        threads.forEach { it.join(20_000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(64, ok.get())
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
