package com.cleartether.app.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Coroutine-friendly wrapper around [AsynchronousSocketChannel].
 *
 * Every operation is truly non-blocking: no thread is parked while waiting for the network, so
 * the proxy and the user-space TCP stack can hold thousands of idle tunnels on a handful of
 * threads. Cancelling a suspended call closes the socket. That is the behaviour we want for a
 * relay (a cancelled tunnel is a dead tunnel), and it guarantees no leaked file descriptors.
 *
 * Implementation note: `ByteBuffer` is only ever created through `ByteBuffer.wrap`, and never
 * mutated through the covariant `flip()/position(int)` overloads. Those overloads don't exist on
 * older Android runtimes when the code is compiled against a newer JDK.
 */
class AsyncSocket(val channel: AsynchronousSocketChannel) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get() && channel.isOpen

    val remoteAddress: InetSocketAddress?
        get() = runCatching { channel.remoteAddress as? InetSocketAddress }.getOrNull()

    val localAddress: InetSocketAddress?
        get() = runCatching { channel.localAddress as? InetSocketAddress }.getOrNull()

    /** Reads up to [len] bytes. Returns -1 on orderly EOF. */
    suspend fun read(dst: ByteArray, off: Int = 0, len: Int = dst.size - off, timeoutMs: Long = 0): Int {
        if (len == 0) return 0
        val buffer = ByteBuffer.wrap(dst, off, len)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { close() }
            val handler = object : CompletionHandler<Int, Unit?> {
                override fun completed(result: Int, attachment: Unit?) = cont.resume(result)
                override fun failed(exc: Throwable, attachment: Unit?) = cont.resumeWithException(exc)
            }
            try {
                if (timeoutMs > 0) channel.read(buffer, timeoutMs, TimeUnit.MILLISECONDS, null, handler)
                else channel.read(buffer, null, handler)
            } catch (t: Throwable) {
                cont.resumeWithException(t)
            }
        }
    }

    /** Writes the whole range, looping over partial writes. */
    suspend fun writeFully(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        val buffer = ByteBuffer.wrap(src, off, len)
        while (buffer.hasRemaining()) {
            val written = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { close() }
                try {
                    channel.write(buffer, null, object : CompletionHandler<Int, Unit?> {
                        override fun completed(result: Int, attachment: Unit?) = cont.resume(result)
                        override fun failed(exc: Throwable, attachment: Unit?) = cont.resumeWithException(exc)
                    })
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            if (written < 0) throw IOException("Socket closed during write")
        }
    }

    suspend fun writeFully(text: String) = writeFully(text.toByteArray(Charsets.ISO_8859_1))

    fun shutdownOutput() {
        runCatching { channel.shutdownOutput() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { channel.close() }
    }

    companion object {
        /** Opens a channel and connects it, honouring coroutine cancellation (e.g. `withTimeout`). */
        suspend fun connect(address: SocketAddress): AsyncSocket {
            val channel = AsynchronousSocketChannel.open()
            val socket = AsyncSocket(channel)
            try {
                runCatching { channel.setOption(StandardSocketOptions.TCP_NODELAY, true) }
                runCatching { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true) }
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { socket.close() }
                    try {
                        channel.connect(address, null, object : CompletionHandler<Void?, Unit?> {
                            override fun completed(result: Void?, attachment: Unit?) = cont.resume(Unit)
                            override fun failed(exc: Throwable, attachment: Unit?) = cont.resumeWithException(exc)
                        })
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }
                return socket
            } catch (t: Throwable) {
                socket.close()
                throw t
            }
        }
    }
}

/** Suspends until a client connects. Throws once the server channel is closed. */
suspend fun AsynchronousServerSocketChannel.acceptSuspend(): AsyncSocket {
    val channel = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { close() } }
        try {
            accept(null, object : CompletionHandler<AsynchronousSocketChannel, Unit?> {
                override fun completed(result: AsynchronousSocketChannel, attachment: Unit?) {
                    // If the waiter was cancelled the accepted channel would leak, so close it.
                    if (!cont.isActive) runCatching { result.close() } else cont.resume(result)
                }
                override fun failed(exc: Throwable, attachment: Unit?) = cont.resumeWithException(exc)
            })
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }
    runCatching { channel.setOption(StandardSocketOptions.TCP_NODELAY, true) }
    runCatching { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true) }
    return AsyncSocket(channel)
}

/**
 * Buffered reader over an [AsyncSocket] used while parsing protocol headers (HTTP, SOCKS).
 * Bytes read past the header stay in the buffer and can be drained with [takeBuffered], so the
 * relay starts with no data lost.
 */
class BufferedAsyncReader(private val socket: AsyncSocket, initialCapacity: Int = 4096) {
    private var buf = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    val buffered: Int get() = end - start

    /** Ensures at least [n] bytes are buffered. Returns false on EOF before that. */
    suspend fun fill(n: Int, timeoutMs: Long = 0): Boolean {
        while (buffered < n) {
            ensureRoom(n - buffered)
            val read = socket.read(buf, end, buf.size - end, timeoutMs)
            if (read < 0) return false
            end += read
        }
        return true
    }

    /** Reads at least one more chunk. Returns false on EOF. */
    suspend fun fillMore(timeoutMs: Long = 0): Boolean {
        ensureRoom(1024)
        val read = socket.read(buf, end, buf.size - end, timeoutMs)
        if (read < 0) return false
        end += read
        return true
    }

    fun peek(index: Int): Int = buf[start + index].toInt() and 0xFF

    suspend fun readByte(timeoutMs: Long = 0): Int {
        if (!fill(1, timeoutMs)) throw IOException("Unexpected end of stream")
        return buf[start++].toInt() and 0xFF
    }

    suspend fun readExactly(n: Int, timeoutMs: Long = 0): ByteArray {
        if (!fill(n, timeoutMs)) throw IOException("Unexpected end of stream")
        val out = buf.copyOfRange(start, start + n)
        start += n
        return out
    }

    /** Index of the first CRLFCRLF (or LFLF) terminator relative to the buffered window, or -1. */
    fun indexOfHeaderEnd(): Int {
        var i = start
        while (i < end) {
            if (buf[i] == LF) {
                if (i + 1 < end && buf[i + 1] == LF) return i + 2 - start
                if (i + 2 < end && buf[i + 1] == CR && buf[i + 2] == LF) return i + 3 - start
            }
            i++
        }
        return -1
    }

    fun take(n: Int): ByteArray {
        val out = buf.copyOfRange(start, start + n)
        start += n
        return out
    }

    fun takeBuffered(): ByteArray = take(buffered)

    private fun ensureRoom(extra: Int) {
        if (end + extra <= buf.size) return
        val live = end - start
        if (start > 0 && live + extra <= buf.size) {
            System.arraycopy(buf, start, buf, 0, live)
        } else {
            val bigger = ByteArray(maxOf(buf.size * 2, live + extra))
            System.arraycopy(buf, start, bigger, 0, live)
            buf = bigger
        }
        start = 0
        end = live
    }

    private companion object {
        const val CR: Byte = '\r'.code.toByte()
        const val LF: Byte = '\n'.code.toByte()
    }
}
