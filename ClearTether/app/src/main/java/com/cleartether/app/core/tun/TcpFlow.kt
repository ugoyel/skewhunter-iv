package com.cleartether.app.core.tun

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Where a flow sends packets back towards the app (the TUN writer). */
internal interface PacketSink {
    /** Non-blocking; may drop under pressure (TCP recovers). */
    fun offer(packet: ByteArray)

    /** Suspends while the TUN write queue is full (used for bulk data). */
    suspend fun send(packet: ByteArray)
}

/**
 * One TCP connection from an app on this device, terminated in user space.
 *
 * The app's kernel talks real TCP to us through the TUN interface. The payload is relayed over
 * a [FlowUpstream] (normally an HTTP CONNECT tunnel to the ClearTether host). What it implements:
 *
 * - three-way handshake, answered only after the upstream tunnel is open, so a refused
 *   destination becomes a fast RST instead of a hang
 * - receive flow control: the advertised window reflects bytes still queued for the upstream,
 *   so a slow cellular uplink pushes back on the app instead of buffering without limit
 * - send flow control against the app's advertised window, with zero-window probes
 * - retransmission (RTO with exponential backoff, plus fast retransmit on 3 duplicate ACKs)
 * - independent half-close in both directions, and RST on errors
 *
 * Window scaling, SACK and timestamps are not offered. Without them the peer falls back to plain
 * 16-bit windows, which is plenty on a sub-millisecond loopback TUN path.
 */
internal class TcpFlow(
    val key: FlowKey,
    clientIsn: Int,
    clientMss: Int,
    clientWindow: Int,
    mtu: Int,
    private val sink: PacketSink,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val onBytes: (uploaded: Long, downloaded: Long) -> Unit,
) {
    enum class State { CONNECTING, SYN_RECEIVED, ESTABLISHED, CLOSED }

    private class Segment(val seq: Int, val data: ByteArray?, val syn: Boolean = false, val fin: Boolean = false) {
        val end: Int get() = seq + (data?.size ?: 0) + (if (syn || fin) 1 else 0)
    }

    private val lock = Any()
    private val local = key.local
    private val remote = key.remote
    private val isV6 = local.size == 16
    private val ourMss = mtu - (if (isV6) 60 else 40)
    private val sendMss = minOf(clientMss, ourMss).coerceAtLeast(MIN_MSS)

    @Volatile var state = State.CONNECTING
        private set

    private val iss = Random.nextInt()
    private var sndUna = iss
    private var sndNxt = iss
    private var rcvNxt = clientIsn + 1
    private var peerWindow = clientWindow
    private val unacked = ArrayDeque<Segment>()
    private var rto = INITIAL_RTO_MS
    private var timerStartedAt = 0L
    private var retries = 0
    private var dupAcks = 0
    private var clientFinReceived = false
    private var finSent = false
    private var finAcked = false
    private var resetSent = false
    private var lastAdvertisedWindow = RECEIVE_BUFFER

    private val queuedForUpstream = AtomicInteger(0)
    private val toUpstream = Channel<ByteArray>(Channel.UNLIMITED)
    private val windowSignal = Channel<Unit>(Channel.CONFLATED)
    private val established = CompletableDeferred<Unit>()
    @Volatile private var upstream: FlowUpstream? = null
    private var job: Job? = null

    @Volatile var lastActivityAt: Long = clock()
        private set
    @Volatile var closedAt: Long = 0
        private set

    val isClosed: Boolean get() = state == State.CLOSED
    val isHalfClosedByApp: Boolean get() = clientFinReceived

    /** Opens the upstream; replies SYN-ACK on success or RST on failure. */
    fun start(dial: suspend () -> FlowUpstream) {
        job = scope.launch {
            val up = try {
                withTimeout(CONNECT_TIMEOUT_MS) { dial() }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (up == null) {
                abort(sendReset = true)
                return@launch
            }
            val synAck: ByteArray
            synchronized(lock) {
                if (state == State.CLOSED) {
                    up.close()
                    return@launch
                }
                upstream = up
                state = State.SYN_RECEIVED
                unacked.addLast(Segment(iss, null, syn = true))
                sndNxt = iss + 1
                timerStartedAt = clock()
                synAck = buildPacket(iss, TCP_SYN or TCP_ACK, mss = ourMss)
            }
            sink.offer(synAck)
            launch { upstreamWriter(up) }
            established.await()
            upstreamReader(up)
        }
    }

    /** Handles a segment from the app. Called on the TUN reader thread, so it must never block. */
    fun onSegment(tcp: TcpHeader) {
        val replies = ArrayList<ByteArray>(2)
        var abortFlow = false
        var closeGracefully = false
        synchronized(lock) {
            if (state == State.CLOSED) {
                // Lingering: absorb late retransmissions politely.
                if (!tcp.rst && (tcp.payloadLength > 0 || tcp.fin)) {
                    replies += if (resetSent) buildPacket(sndNxt, TCP_RST or TCP_ACK) else buildPacket(sndNxt, TCP_ACK)
                }
                return@synchronized
            }
            lastActivityAt = clock()
            if (tcp.rst) {
                abortFlow = true
                return@synchronized
            }
            if (tcp.syn) {
                if (state == State.SYN_RECEIVED) replies += buildPacket(iss, TCP_SYN or TCP_ACK, mss = ourMss)
                return@synchronized
            }
            if (state == State.CONNECTING) return@synchronized
            if (!tcp.ackFlag) return@synchronized
            if (!processAck(tcp, replies)) return@synchronized
            processData(tcp, replies)
            if (clientFinReceived && finSent && finAcked) closeGracefully = true
        }
        replies.forEach(sink::offer)
        if (abortFlow) abort(sendReset = false)
        if (closeGracefully) finish()
    }

    /** Retransmission timer, called by the stack every few hundred ms. */
    fun onTimer(now: Long) {
        var packet: ByteArray? = null
        var giveUp = false
        synchronized(lock) {
            if (state == State.CLOSED || unacked.isEmpty()) return
            if (now - timerStartedAt < rto) return
            if (++retries > MAX_RETRIES) {
                giveUp = true
            } else {
                packet = rebuild(unacked.first())
                rto = (rto * 2).coerceAtMost(MAX_RTO_MS)
                timerStartedAt = now
            }
        }
        packet?.let(sink::offer)
        if (giveUp) abort(sendReset = true)
    }

    /** Aborts the flow, optionally telling the app with a RST. */
    fun abort(sendReset: Boolean) {
        val rst: ByteArray?
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            closedAt = clock()
            resetSent = sendReset
            unacked.clear()
            rst = if (sendReset) buildPacket(sndNxt, TCP_RST or TCP_ACK) else null
        }
        rst?.let(sink::offer)
        release()
    }

    private fun finish() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            closedAt = clock()
        }
        release()
    }

    private fun release() {
        toUpstream.close()
        windowSignal.close()
        established.cancel()
        upstream?.close()
        job?.cancel()
    }

    // ------------------------------------------------------------------ inbound (app -> upstream)

    /** Returns false if the segment must be ignored. Caller holds [lock]. */
    private fun processAck(tcp: TcpHeader, replies: MutableList<ByteArray>): Boolean {
        val ack = tcp.ack
        if (state == State.SYN_RECEIVED) {
            if (ack != iss + 1) {
                replies += PacketFactory.tcp(remote, key.remotePort, local, key.localPort, ack, 0, TCP_RST, 0)
                return false
            }
            state = State.ESTABLISHED
            sndUna = ack
            unacked.clear()
            retries = 0
            rto = INITIAL_RTO_MS
            peerWindow = tcp.window
            established.complete(Unit)
            windowSignal.trySend(Unit)
            return true
        }
        val previousWindow = peerWindow
        peerWindow = tcp.window
        if (seqGt(ack, sndUna) && seqLe(ack, sndNxt)) {
            sndUna = ack
            while (unacked.isNotEmpty() && seqLe(unacked.first().end, ack)) unacked.removeFirst()
            val head = unacked.firstOrNull()
            if (head?.data != null && seqLt(head.seq, ack)) {
                // Partially acknowledged: keep only the unacknowledged tail for retransmission.
                val cut = ack - head.seq
                unacked[0] = Segment(ack, head.data.copyOfRange(cut, head.data.size), fin = head.fin)
            }
            if (finSent && ack == sndNxt) finAcked = true
            retries = 0
            dupAcks = 0
            rto = INITIAL_RTO_MS
            timerStartedAt = clock()
            windowSignal.trySend(Unit)
        } else if (ack == sndUna && unacked.isNotEmpty() && tcp.payloadLength == 0 && !tcp.fin && tcp.window == previousWindow) {
            if (++dupAcks == 3) {
                replies += rebuild(unacked.first())
                timerStartedAt = clock()
            }
        } else if (tcp.window != previousWindow) {
            windowSignal.trySend(Unit)
        }
        return true
    }

    /** Caller holds [lock]. */
    private fun processData(tcp: TcpHeader, replies: MutableList<ByteArray>) {
        var length = tcp.payloadLength
        if (length <= 0 && !tcp.fin) return
        if (clientFinReceived) {
            replies += buildPacket(sndNxt, TCP_ACK)
            return
        }
        var seq = tcp.seq
        var offset = tcp.payloadOffset
        if (seqLt(seq, rcvNxt)) {
            val duplicate = rcvNxt - seq
            if (duplicate >= length + (if (tcp.fin) 1 else 0)) {
                replies += buildPacket(sndNxt, TCP_ACK) // pure retransmission
                return
            }
            val trim = minOf(duplicate, length)
            offset += trim
            length -= trim
            seq += trim
        }
        if (seq != rcvNxt) {
            replies += buildPacket(sndNxt, TCP_ACK) // out of order: duplicate ACK, app retransmits
            return
        }
        if (length > 0) {
            val accept = minOf(length, receiveWindow())
            if (accept > 0) {
                val chunk = tcp.packet.data.copyOfRange(offset, offset + accept)
                queuedForUpstream.addAndGet(accept)
                toUpstream.trySend(chunk)
                rcvNxt += accept
                onBytes(accept.toLong(), 0)
            }
            if (accept < length) {
                replies += buildPacket(sndNxt, TCP_ACK) // window full: the rest is dropped and resent later
                return
            }
        }
        if (tcp.fin) {
            rcvNxt += 1
            clientFinReceived = true
            toUpstream.trySend(FIN_MARKER)
        }
        replies += buildPacket(sndNxt, TCP_ACK)
    }

    private suspend fun upstreamWriter(up: FlowUpstream) {
        try {
            for (chunk in toUpstream) {
                if (chunk === FIN_MARKER) {
                    up.shutdownOutput()
                    continue
                }
                up.write(chunk, 0, chunk.size)
                queuedForUpstream.addAndGet(-chunk.size)
                var update: ByteArray? = null
                synchronized(lock) {
                    if (state != State.CLOSED && lastAdvertisedWindow < WINDOW_UPDATE_THRESHOLD &&
                        receiveWindow() >= WINDOW_UPDATE_THRESHOLD
                    ) {
                        update = buildPacket(sndNxt, TCP_ACK)
                    }
                }
                update?.let(sink::offer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            abort(sendReset = true)
        }
    }

    // ------------------------------------------------------------------ outbound (upstream -> app)

    private suspend fun upstreamReader(up: FlowUpstream) {
        val buffer = ByteArray(READ_CHUNK)
        var probeDelay = PROBE_INITIAL_MS
        try {
            while (true) {
                var allowed: Int
                while (true) {
                    var probe: ByteArray? = null
                    synchronized(lock) {
                        if (state == State.CLOSED) return
                        val inFlight = sndNxt - sndUna
                        allowed = peerWindow - inFlight
                        if (allowed <= 0 && inFlight == 0) probe = buildPacket(sndNxt - 1, TCP_ACK)
                    }
                    if (allowed > 0) break
                    val signalled = withTimeoutOrNull(probeDelay) { windowSignal.receive() } != null
                    if (!signalled) {
                        // Zero window and nothing in flight: probe so a lost window update can't stall us.
                        probe?.let(sink::offer)
                        probeDelay = (probeDelay * 2).coerceAtMost(PROBE_MAX_MS)
                    }
                }
                probeDelay = PROBE_INITIAL_MS
                val n = up.read(buffer, 0, minOf(allowed, buffer.size))
                if (n < 0) {
                    sendFin()
                    return
                }
                if (n == 0) continue
                onBytes(0, n.toLong())
                val packets = ArrayList<ByteArray>(n / sendMss + 1)
                synchronized(lock) {
                    if (state == State.CLOSED) return
                    if (unacked.isEmpty()) timerStartedAt = clock()
                    var off = 0
                    while (off < n) {
                        val len = minOf(sendMss, n - off)
                        val data = buffer.copyOfRange(off, off + len)
                        unacked.addLast(Segment(sndNxt, data))
                        val last = off + len >= n
                        packets += buildPacket(sndNxt, TCP_ACK or (if (last) TCP_PSH else 0), data)
                        sndNxt += len
                        off += len
                    }
                }
                for (p in packets) sink.send(p)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            abort(sendReset = true)
        }
    }

    private fun sendFin() {
        var packet: ByteArray? = null
        var close = false
        synchronized(lock) {
            if (finSent || state == State.CLOSED) return
            finSent = true
            if (unacked.isEmpty()) timerStartedAt = clock()
            unacked.addLast(Segment(sndNxt, null, fin = true))
            packet = buildPacket(sndNxt, TCP_FIN or TCP_ACK)
            sndNxt += 1
            close = clientFinReceived && finAcked
        }
        packet?.let(sink::offer)
        if (close) finish()
    }

    // ------------------------------------------------------------------ helpers (caller holds lock)

    private fun receiveWindow(): Int = (RECEIVE_BUFFER - queuedForUpstream.get()).coerceIn(0, 0xFFFF)

    private fun rebuild(segment: Segment): ByteArray = when {
        segment.syn -> buildPacket(segment.seq, TCP_SYN or TCP_ACK, mss = ourMss)
        segment.fin && segment.data == null -> buildPacket(segment.seq, TCP_FIN or TCP_ACK)
        else -> buildPacket(segment.seq, TCP_ACK or TCP_PSH, segment.data)
    }

    private fun buildPacket(seq: Int, flags: Int, data: ByteArray? = null, mss: Int = 0): ByteArray {
        val window = receiveWindow()
        lastAdvertisedWindow = window
        val ack = if (flags and TCP_ACK != 0) rcvNxt else 0
        return PacketFactory.tcp(
            remote, key.remotePort, local, key.localPort, seq, ack, flags, window,
            data, 0, data?.size ?: 0, mss,
        )
    }

    companion object {
        private val FIN_MARKER = ByteArray(0)
        const val RECEIVE_BUFFER = 0xFFFF
        private const val WINDOW_UPDATE_THRESHOLD = 16 * 1024
        private const val READ_CHUNK = 32 * 1024
        private const val MIN_MSS = 536
        private const val INITIAL_RTO_MS = 300L
        private const val MAX_RTO_MS = 4_000L
        private const val MAX_RETRIES = 10
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val PROBE_INITIAL_MS = 500L
        private const val PROBE_MAX_MS = 8_000L
    }
}
