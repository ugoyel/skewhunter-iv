package com.cleartether.app.core.tun

import com.cleartether.app.core.net.AsyncSocket
import java.net.InetAddress

/** The far side of a TCP flow terminated by the user-space stack. */
interface FlowUpstream {
    /** Reads up to [len] bytes; -1 on EOF. */
    suspend fun read(buf: ByteArray, off: Int, len: Int): Int
    suspend fun write(buf: ByteArray, off: Int, len: Int)
    fun shutdownOutput()
    fun close()
}

/** Opens a [FlowUpstream] for an app connection to [address]:[port]. */
fun interface FlowDialer {
    suspend fun dial(address: InetAddress, port: Int): FlowUpstream
}

/** [FlowUpstream] over a real socket. [preamble] holds bytes already read during a handshake. */
class SocketFlowUpstream(private val socket: AsyncSocket, private var preamble: ByteArray? = null) : FlowUpstream {
    override suspend fun read(buf: ByteArray, off: Int, len: Int): Int {
        preamble?.let { early ->
            val n = minOf(len, early.size)
            System.arraycopy(early, 0, buf, off, n)
            preamble = if (n == early.size) null else early.copyOfRange(n, early.size)
            return n
        }
        return socket.read(buf, off, len)
    }

    override suspend fun write(buf: ByteArray, off: Int, len: Int) = socket.writeFully(buf, off, len)
    override fun shutdownOutput() = socket.shutdownOutput()
    override fun close() = socket.close()
}
