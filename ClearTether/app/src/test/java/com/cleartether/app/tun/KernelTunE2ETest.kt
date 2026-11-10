package com.cleartether.app.tun

import com.cleartether.app.EchoServer
import com.cleartether.app.TinyHttpServer
import com.cleartether.app.core.net.AddressPolicy
import com.cleartether.app.core.net.DirectUpstreamConnector
import com.cleartether.app.core.proxy.ProxyConfig
import com.cleartether.app.core.proxy.TetherProxyEngine
import com.cleartether.app.core.tun.DnsForwarder
import com.cleartether.app.core.tun.FlowDialer
import com.cleartether.app.core.tun.PacketIo
import com.cleartether.app.core.tun.ProxyEndpoint
import com.cleartether.app.core.tun.ProxyTunnelDialer
import com.cleartether.app.core.tun.StackConfig
import com.cleartether.app.core.tun.UserSpaceNetStack
import com.cleartether.app.readExactly
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * End-to-end test of Client mode against the real Linux kernel TCP stack:
 *
 *   test socket -> kernel TCP -> TUN (tools/tun-e2e/tun_bridge.py) -> UserSpaceNetStack
 *     -> CONNECT tunnel -> TetherProxyEngine -> local test server
 *
 * Runs only as root on Linux with /dev/net/tun, `ip` and python3 available. Set
 * CT_TUN_DROP=0.02 to inject 2% random packet loss in both directions.
 */
class KernelTunE2ETest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val virtualServer = "10.99.0.2"
    private var bridge: Process? = null
    private var stack: UserSpaceNetStack? = null
    private var engine: TetherProxyEngine? = null
    private val closeables = mutableListOf<AutoCloseable>()

    @Before
    fun setUp() {
        val script = File(System.getProperty("user.dir")).resolve("../tools/tun-e2e/tun_bridge.py").canonicalFile
        assumeTrue("TUN e2e needs root + /dev/net/tun", File("/dev/net/tun").exists() && script.exists() &&
            System.getProperty("user.name") == "root" && System.getenv("CT_SKIP_TUN") == null)
        val port = ServerSocket(0).use { it.localPort }
        val drop = System.getenv("CT_TUN_DROP") ?: "0"
        val process = ProcessBuilder("python3", script.path, port.toString(), drop).redirectErrorStream(true).start()
        bridge = process
        val ready = process.inputStream.bufferedReader().readLine()
        assumeTrue("bridge failed to start: $ready", ready == "READY")

        val permissive = AddressPolicy(emptyList(), true, emptyList(), true)
        val proxy = TetherProxyEngine(ProxyConfig(loopback, 0, permissive), DirectUpstreamConnector(permissive))
        engine = proxy
        val proxyPort = proxy.start().port
        val tunnel = ProxyTunnelDialer(ProxyEndpoint(loopback, proxyPort))
        // The "internet" host 10.99.0.2 is really this machine's loopback.
        val dialer = FlowDialer { address, p ->
            tunnel.dial(if (address.hostAddress == virtualServer) loopback else address, p)
        }

        val link = Socket(loopback, port).apply { tcpNoDelay = true }
        closeables += link
        val input = DataInputStream(link.getInputStream().buffered(256 * 1024))
        val output = DataOutputStream(link.getOutputStream())
        val io = object : PacketIo {
            override fun read(buffer: ByteArray): Int = try {
                val len = input.readUnsignedShort()
                input.readFully(buffer, 0, len)
                len
            } catch (e: Exception) { -1 }
            override fun write(packet: ByteArray) = synchronized(output) {
                output.writeShort(packet.size)
                output.write(packet)
                output.flush()
            }
            override fun close() = link.close()
        }
        stack = UserSpaceNetStack(io, StackConfig(), dialer, DnsForwarder(emptyList()), null).also { it.start() }
    }

    @After
    fun tearDown() {
        stack?.stop()
        engine?.stop()
        closeables.forEach { runCatching { it.close() } }
        bridge?.destroy()
        bridge?.waitFor()
    }

    private fun connect(port: Int) = Socket().apply {
        connect(InetSocketAddress(virtualServer, port), 10_000)
        soTimeout = 60_000
    }

    @Test
    fun bulkFullDuplexTransferIsByteExact() {
        val echo = EchoServer().also { closeables += it }
        val data = Random.nextBytes(16 * 1024 * 1024)
        connect(echo.port).use { s ->
            val writer = thread { s.getOutputStream().write(data); s.shutdownOutput() }
            val received = s.getInputStream().readBytes()
            writer.join()
            assertEquals(data.size, received.size)
            assertArrayEquals(sha(data), sha(received))
        }
    }

    @Test
    fun diagLossyBulk() {
        assumeTrue(System.getenv("CT_DIAG") != null)
        val echo = EchoServer().also { closeables += it }
        val data = Random.nextBytes(16 * 1024 * 1024)
        connect(echo.port).use { s ->
            val sent = java.util.concurrent.atomic.AtomicLong()
            val got = java.util.concurrent.atomic.AtomicLong()
            val writer = thread {
                val o = s.getOutputStream(); var off = 0
                while (off < data.size) { val n = minOf(65536, data.size - off); o.write(data, off, n); off += n; sent.set(off.toLong()) }
                s.shutdownOutput()
            }
            val mon = thread(isDaemon = true) {
                while (true) {
                    Thread.sleep(2000)
                    val ss = ProcessBuilder("ss", "-tin", "dst", virtualServer).redirectErrorStream(true).start().inputStream.bufferedReader().readText().replace("\n", " | ")
                    println("DIAG sent=${sent.get()} got=${got.get()} flows=${stack?.debugFlows()} SS: $ss")
                }
            }
            val input = s.getInputStream(); val buf = ByteArray(65536); var total = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; got.set(total) }
            writer.join()
            println("DIAG done total=$total")
        }
    }

    @Test
    fun manyParallelConnections() {
        val echo = EchoServer().also { closeables += it }
        val ok = AtomicInteger()
        val threads = (1..40).map { i ->
            thread {
                connect(echo.port).use { s ->
                    val msg = Random.nextBytes(256 * 1024 + i)
                    val w = thread { s.getOutputStream().write(msg); s.shutdownOutput() }
                    val back = s.getInputStream().readBytes()
                    w.join()
                    if (back.contentEquals(msg)) ok.incrementAndGet()
                }
            }
        }
        threads.forEach { it.join(120_000) }
        assertEquals(40, ok.get())
    }

    @Test
    fun serverClosesFirstHttpStyle() {
        val body = Random.nextBytes(3 * 1024 * 1024)
        val http = TinyHttpServer(body).also { closeables += it }
        connect(http.port).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val response = s.getInputStream().readBytes()
            val headerEnd = String(response, 0, 200, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
            assertArrayEquals(sha(body), sha(response.copyOfRange(headerEnd, response.size)))
        }
    }

    @Test
    fun slowReaderExercisesZeroWindowFlowControl() {
        val payload = Random.nextBytes(2 * 1024 * 1024)
        val server = ServerSocket(0, 5, loopback).also { closeables += it }
        thread {
            server.accept().use { it.getOutputStream().write(payload) }
        }
        connect(server.localPort).use { s ->
            s.receiveBufferSize = 8 * 1024
            val input = s.getInputStream()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var reads = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (++reads % 16 == 0) Thread.sleep(20) // let the window fill up
            }
            assertArrayEquals(sha(payload), sha(out.toByteArray()))
        }
    }

    @Test
    fun refusedDestinationFailsFast() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val started = System.nanoTime()
        val error = runCatching { connect(closedPort).use { it.getInputStream().read() } }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("expected refusal, got $error", error is ConnectException || error is java.net.SocketException)
        assertTrue("refusal took $elapsedMs ms", elapsedMs < 5_000)
    }

    @Test
    fun echoRoundTripsAreSmall() {
        val echo = EchoServer().also { closeables += it }
        connect(echo.port).use { s ->
            repeat(200) { i ->
                val msg = "ping-$i".toByteArray()
                s.getOutputStream().write(msg)
                assertArrayEquals(msg, s.getInputStream().readExactly(msg.size))
            }
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
