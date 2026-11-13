package io.github.ugoyel.hearby.client

import android.content.Context
import android.os.Process
import android.util.Log
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.core.ClockSync
import io.github.ugoyel.hearby.core.VideoTimeline
import io.github.ugoyel.hearby.core.WireVideo
import io.github.ugoyel.hearby.core.JitterBuffer
import io.github.ugoyel.hearby.core.PlayoutEngine
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.Wire
import io.github.ugoyel.hearby.core.WireParty
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

enum class Phase { IDLE, CONNECTING, SYNCING, PLAYING, RECONNECTING, ENDED }

data class ReceiverUiState(
    val phase: Phase = Phase.IDLE,
    val hostName: String = "",
    val hostAddress: String = "",
    val message: String? = null,
    /** Measured "heard late (+) / early (-)" error against the shared timeline, ms. */
    val syncErrorMs: Float = 0f,
    val rttMs: Float = 0f,
    /** Received audio waiting ahead of the play cursor, ms. */
    val bufferMs: Int = 0,
    /** Non-zero when this device cannot meet the host's playout delay and plays this much late. */
    val lateBiasMs: Int = 0,
    val playoutDelayMs: Int = 0,
    /** The manual sync slider, -500..+500 ms. Positive plays later. */
    val offsetMs: Int = 0,
    val outputName: String = "",
    val outputIsBluetooth: Boolean = false,
    val lossPercent: Float = 0f,
    val muted: Boolean = false,
    /** Peak level of what is playing right now, 0..1. Drives the visualizer. */
    val level: Float = 0f,
    /** What the DJ is playing. */
    val songTitle: String = "",
    val songArtist: String = "",
    val songPlaying: Boolean = false,
    /** True once the DJ's sound check has lined this phone up. */
    val soundChecked: Boolean = false,
    /** Sound-check correction applied to this phone, ms (negative = plays earlier). */
    val soundCheckMs: Float = 0f,
    /** YouTube mode: the video the DJ is playing on every phone, or null. */
    val video: VideoTimeline? = null,
    /** DJ clock minus this phone's clock, µs. */
    val clockOffsetUs: Long = 0,
    /** This phone's own correction (slider + sound check), µs; positive plays later. */
    val correctionUs: Long = 0,
)

/**
 * LISTENER: receives the DJ's Opus stream and plays every frame at the instant the shared timeline
 * says it is due.
 *
 *   UDP ─▶ rx thread ─▶ JitterBuffer (encoded, by sequence number)
 *                 ├──▶ ClockSync (NTP offset to the DJ phone's clock)
 *                 └──▶ sound check: chirp requests and corrections from the DJ
 *   PlayoutRunner: playout engine ─▶ Opus decode ─▶ resampler ─▶ AudioTrack
 *
 *   target time = DJ timestamp + playout delay + slider + sound-check correction.
 */
class AndroidSyncReceiver(
    context: Context,
    private val host: InetAddress,
    private val port: Int,
    hostLabel: String,
    private val onState: (ReceiverUiState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val clientId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
    private val deviceName = LocalNet.deviceName(appContext)

    private val jitter = JitterBuffer()
    private val clock = ClockSync()
    private val playout = PlayoutRunner(appContext, jitter, clock)

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var controlThread: Thread? = null

    // connection state (written by the rx / control threads)
    @Volatile private var joined = false
    @Volatile private var everJoined = false
    @Volatile private var ended = false
    @Volatile private var streamId = -1L
    @Volatile private var hostName = hostLabel
    @Volatile private var lastPacketNs = 0L
    @Volatile private var message: String? = null
    @Volatile private var syncsSent = 0

    // party state from the DJ
    @Volatile private var songTitle = ""
    @Volatile private var songArtist = ""
    @Volatile private var songPlaying = false
    @Volatile private var lastCheckId = -1
    @Volatile private var soundChecked = false
    @Volatile private var video: VideoTimeline? = null

    fun start() {
        if (running) return
        running = true
        lastPacketNs = System.nanoTime()
        playout.start()
        rxThread = Thread(::receiveLoop, "hearby-rx").also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        val s = socket
        if (s != null && joined) {
            // Best-effort goodbye so the host drops us immediately instead of timing out.
            val leave = Wire.encodeLeave(clientId)
            Thread {
                try {
                    s.send(DatagramPacket(leave, leave.size))
                } catch (e: Exception) {
                    // host is gone
                }
            }.apply { start(); join(300) }
        }
        s?.close()
        rxThread?.join(1000)
        controlThread?.join(1000)
        playout.stop()
    }

    fun setUserOffsetMs(ms: Int) = playout.setUserOffsetMs(ms)

    fun resync() = playout.resync()

    fun setMuted(mute: Boolean) = playout.setMuted(mute)

    fun reclaimSound() = playout.reclaimSound()

    // ---- network: receive -------------------------------------------------------------------------

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sock: DatagramSocket
        try {
            sock = DatagramSocket()
            LocalNet.bindToWifi(appContext, sock)
            // Connected UDP: the kernel filters out everyone but the host for us.
            sock.connect(InetSocketAddress(host, port))
            sock.soTimeout = 100
            try {
                sock.trafficClass = 0xB8
            } catch (e: Exception) {
                // not fatal
            }
        } catch (e: Exception) {
            Log.e(TAG, "socket setup failed", e)
            message = "Could not open a network socket."
            ended = true
            publish()
            return
        }
        socket = sock
        controlThread = Thread({ controlLoop(sock) }, "hearby-control").also { it.start() }

        val buf = ByteArray(Proto.MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                packet.setData(buf, 0, buf.size)
                sock.receive(packet)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                // ICMP "port unreachable" (host not listening yet) surfaces here on a connected socket.
                if (!running) break
                try {
                    Thread.sleep(50)
                } catch (ie: InterruptedException) {
                    break
                }
                continue
            }
            val rxNs = System.nanoTime() // t3: taken before anything else
            handle(buf, packet.length, rxNs)
        }
    }

    private fun handle(buf: ByteArray, len: Int, rxNs: Long) {
        when (Wire.typeOf(buf, len)) {
            Proto.TYPE_AUDIO -> if (len > Proto.AUDIO_HEADER_SIZE && joined) {
                lastPacketNs = rxNs
                jitter.put(
                    Wire.audioSeq(buf), Wire.audioTimestampUs(buf),
                    buf, Proto.AUDIO_HEADER_SIZE, len - Proto.AUDIO_HEADER_SIZE,
                )
            }
            Proto.TYPE_SYNC_RESP -> {
                val m = Wire.decodeSyncResp(buf, len) ?: return
                lastPacketNs = rxNs
                val t3 = rxNs / 1000
                if (m.streamId == streamId && t3 - m.t0Us in 0..1_000_000) {
                    clock.addSample(m.t0Us, m.t1Us, m.t2Us, t3)
                    playout.playoutDelayMs = m.playoutDelayMs
                }
            }
            Proto.TYPE_JOIN_ACK -> {
                val m = Wire.decodeJoinAck(buf, len) ?: return
                lastPacketNs = rxNs
                if (m.sampleRate != Proto.SAMPLE_RATE || m.channels != Proto.CHANNELS || m.frameMs != Proto.FRAME_MS) {
                    message = "This party uses an audio format this version of Hearby can't play. Try updating."
                    ended = true
                    return
                }
                if (m.streamId != streamId) {
                    // New session on the host: old audio, sequence numbers and corrections are meaningless.
                    streamId = m.streamId
                    jitter.clear()
                    clock.reset()
                    syncsSent = 0
                    lastCheckId = -1
                    soundChecked = false
                    video = null
                    playout.resetStream()
                }
                hostName = m.hostName.ifBlank { hostName }
                playout.playoutDelayMs = m.playoutDelayMs
                message = null
                joined = true
                everJoined = true
            }
            Proto.TYPE_NOW_PLAYING -> {
                val m = WireParty.decodeNowPlaying(buf, len) ?: return
                if (m.streamId != streamId) return
                songTitle = m.title
                songArtist = m.artist
                songPlaying = m.playing
            }
            Proto.TYPE_VIDEO -> {
                val m = WireVideo.decode(buf, len) ?: return
                if (m.streamId == streamId) video = m.timeline
            }
            Proto.TYPE_CHIRP -> {
                val m = WireParty.decodeChirp(buf, len) ?: return
                if (m.streamId == streamId && m.clientId == clientId) playout.scheduleTone(m.mediaTimeUs)
            }
            Proto.TYPE_OFFSET -> {
                val m = WireParty.decodeOffset(buf, len) ?: return
                // The DJ sends each correction a few times (it is UDP); apply each one exactly once.
                if (m.streamId == streamId && m.clientId == clientId && m.checkId != lastCheckId) {
                    lastCheckId = m.checkId
                    playout.calibrationUs = (playout.calibrationUs + m.adjustUs).coerceIn(-MAX_CALIBRATION_US, MAX_CALIBRATION_US)
                    soundChecked = true
                }
            }
            Proto.TYPE_BYE -> if (Wire.decodeU32Body(buf, len) == streamId) {
                message = "The DJ ended the party."
                ended = true
            }
        }
    }

    // ---- network: join, clock sync, keep-alive ----------------------------------------------------

    private fun controlLoop(sock: DatagramSocket) {
        val startNs = System.nanoTime()
        var lastJoinNs = 0L
        var lastSyncNs = 0L
        var lastPublishNs = 0L
        while (running) {
            val now = System.nanoTime()
            playout.failure?.let {
                message = it
                ended = true
            }
            if (ended) {
                publish()
                break
            }
            if (!joined) {
                if (now - lastJoinNs >= JOIN_INTERVAL_NS) {
                    lastJoinNs = now
                    send(sock, Wire.encodeJoin(clientId, Proto.PLATFORM_ANDROID, deviceName))
                }
                if (!everJoined && message == null && now - startNs > 5_000_000_000L) {
                    message = "No answer from the DJ yet. Are you both on the same Wi-Fi, or on their hotspot?"
                }
            } else {
                // A quick burst to get a clock fix, then once a second to follow drift (and as keep-alive).
                val interval = if (syncsSent < SYNC_BURST) SYNC_BURST_INTERVAL_NS else SYNC_INTERVAL_NS
                if (now - lastSyncNs >= interval) {
                    lastSyncNs = now
                    syncsSent++
                    val e = playout.engine
                    val status = when {
                        e == null || e.state != PlayoutEngine.State.PLAYING -> Proto.STATUS_BUFFERING
                        e.lateBiasMs > 0 -> Proto.STATUS_LATE
                        else -> Proto.STATUS_SYNCED
                    }
                    send(
                        sock,
                        Wire.encodeSyncReq(
                            clientId, System.nanoTime() / 1000, clock.rttUs, e?.cushionMs ?: 0, status, playout.adjustUs(),
                            Wire.syncFlags(playout.routeIsBluetooth, lossPercent), // for the DJ phone's auto-tuning
                        ),
                    )
                }
                if (now - lastPacketNs > HOST_TIMEOUT_NS) {
                    joined = false // host went quiet: fall back to knocking until it answers again
                    lastJoinNs = 0
                }
            }
            if (now - lastPublishNs >= 120_000_000L) {
                lastPublishNs = now
                publish()
            }
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun send(sock: DatagramSocket, data: ByteArray) {
        try {
            sock.send(DatagramPacket(data, data.size))
        } catch (e: Exception) {
            // unreachable right now; the next attempt may work
        }
    }

    // ---- state for the UI ----------------------------------------------------------------------------

    private var lastLossSampleNs = 0L
    private var lastPlayed = 0L
    private var lastConcealed = 0L
    private var lossPercent = 0f

    private fun publish() {
        val e = playout.engine
        val now = System.nanoTime()
        if (e != null && now - lastLossSampleNs > 2_000_000_000L) {
            val played = e.playedFrames
            val concealed = e.concealedFrames
            val total = (played - lastPlayed) + (concealed - lastConcealed)
            lossPercent = if (total > 0 && e.state == PlayoutEngine.State.PLAYING) {
                100f * (concealed - lastConcealed) / total
            } else {
                0f
            }
            lastPlayed = played
            lastConcealed = concealed
            lastLossSampleNs = now
        }
        val phase = when {
            ended -> Phase.ENDED
            !joined -> if (everJoined) Phase.RECONNECTING else Phase.CONNECTING
            e == null || e.state != PlayoutEngine.State.PLAYING -> Phase.SYNCING
            else -> Phase.PLAYING
        }
        onState(
            ReceiverUiState(
                phase = phase,
                hostName = hostName,
                hostAddress = host.hostAddress ?: "",
                message = message,
                syncErrorMs = e?.syncErrorMs ?: 0f,
                rttMs = clock.rttUs / 1000f,
                bufferMs = e?.cushionMs ?: 0,
                lateBiasMs = e?.lateBiasMs ?: 0,
                playoutDelayMs = playout.playoutDelayMs,
                offsetMs = playout.userOffsetMs,
                outputName = playout.routeName,
                outputIsBluetooth = playout.routeIsBluetooth,
                lossPercent = lossPercent,
                muted = playout.muted,
                level = if (playout.muted) 0f else e?.outputLevel ?: 0f,
                songTitle = songTitle,
                songArtist = songArtist,
                songPlaying = songPlaying,
                soundChecked = soundChecked,
                soundCheckMs = playout.calibrationUs / 1000f,
                video = video,
                clockOffsetUs = clock.offsetUs,
                correctionUs = playout.userOffsetMs * 1000L + playout.calibrationUs,
            ),
        )
    }

    companion object {
        private const val TAG = "HearbyReceiver"
        private const val JOIN_INTERVAL_NS = 400_000_000L
        private const val SYNC_BURST = 8
        private const val SYNC_BURST_INTERVAL_NS = 40_000_000L
        private const val SYNC_INTERVAL_NS = 1_000_000_000L
        private const val HOST_TIMEOUT_NS = 4_000_000_000L
        const val MAX_CALIBRATION_US = 1_000_000L
    }
}
