package com.cleartether.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Blocking helper servers for tests. */
class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "echo-accept") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val buf = ByteArray(32 * 1024)
                        val input = it.getInputStream()
                        val output = it.getOutputStream()
                        while (true) {
                            val n = runCatching { input.read(buf) }.getOrDefault(-1)
                            if (n < 0) break
                            output.write(buf, 0, n)
                        }
                        runCatching { it.shutdownOutput() }
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

/** Minimal HTTP/1.1 origin that records request heads and answers with a fixed body. */
class TinyHttpServer(private val body: ByteArray = "hello from origin".toByteArray()) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "http-accept") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val head = readHead(it.getInputStream())
                        requests += head
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}

class UdpEchoServer : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "udp-echo") {
            val buf = ByteArray(65535)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                runCatching { socket.receive(p) }.getOrNull() ?: break
                runCatching { socket.send(DatagramPacket(p.data, p.offset, p.length, p.socketAddress)) }
            }
        }
    }

    override fun close() = socket.close()
}

fun readHead(input: InputStream): String {
    val out = ByteArrayOutputStream()
    var last4 = 0
    while (true) {
        val b = input.read()
        if (b < 0) break
        out.write(b)
        last4 = (last4 shl 8) or b
        if (last4 == 0x0D0A0D0A) break
    }
    return out.toString(Charsets.ISO_8859_1.name())
}

fun Socket.readAll(): ByteArray = getInputStream().readBytes()

fun InputStream.readExactly(n: Int): ByteArray {
    val out = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(out, read, n - read)
        if (r < 0) error("EOF after $read of $n bytes")
        read += r
    }
    return out
}
