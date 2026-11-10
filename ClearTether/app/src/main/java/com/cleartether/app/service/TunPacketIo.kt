package com.cleartether.app.service

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.cleartether.app.core.tun.PacketIo

/**
 * [PacketIo] over the VPN's TUN descriptor in non-blocking mode. `poll()` with a short timeout
 * lets the reader thread notice [close] promptly; a plain blocking `read()` on a TUN fd can't
 * be interrupted by closing it from another thread.
 */
class TunPacketIo(private val pfd: ParcelFileDescriptor) : PacketIo {
    private val fd = pfd.fileDescriptor
    @Volatile private var closed = false

    override fun read(buffer: ByteArray): Int {
        val pollFds = arrayOf(StructPollfd().apply { fd = this@TunPacketIo.fd; events = OsConstants.POLLIN.toShort() })
        while (!closed) {
            val ready = try {
                Os.poll(pollFds, POLL_TIMEOUT_MS)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                return -1
            }
            if (ready <= 0) continue
            val revents = pollFds[0].revents.toInt()
            if (revents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) return -1
            if (revents and OsConstants.POLLIN == 0) continue
            return try {
                Os.read(fd, buffer, 0, buffer.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                -1
            }
        }
        return -1
    }

    override fun write(packet: ByteArray) {
        if (closed) return
        var attempts = 0
        while (true) {
            try {
                Os.write(fd, packet, 0, packet.size)
                return
            } catch (e: ErrnoException) {
                // Kernel queue momentarily full: retry briefly, then drop (TCP recovers).
                if ((e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) && ++attempts < 50) {
                    Thread.sleep(1)
                    continue
                }
                return
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { pfd.close() }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 250
    }
}
