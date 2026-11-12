package io.github.ugoyel.hearby.client

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.codec.MediaCodecOpusDecoder
import io.github.ugoyel.hearby.core.AudioSink
import io.github.ugoyel.hearby.core.ClockSync
import io.github.ugoyel.hearby.core.JitterBuffer
import io.github.ugoyel.hearby.core.PlayoutEngine
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.SinkTimestamp
import io.github.ugoyel.hearby.core.Wire
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.math.abs

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
)

/**
 * LISTENER: receives the host's Opus stream and plays every frame at the instant the shared
 * timeline says it is due.
 *
 *   UDP ─▶ rx thread ─▶ JitterBuffer (encoded, by sequence number)
 *                 └──▶ ClockSync (NTP offset to the host clock)
 *   playout thread: PlayoutEngine.pump() ─▶ MediaCodec Opus decode ─▶ drift-correcting resampler
 *                                        ─▶ AudioTrack (blocking write)
 *   AudioTrack.getTimestamp() tells the engine when written frames really reach the speaker /
 *   Bluetooth link, which closes the loop.  target time = host timestamp + playout delay + slider.
 */
class AndroidSyncReceiver(
    context: Context,
    private val host: InetAddress,
    private val port: Int,
    hostLabel: String,
    private val onState: (ReceiverUiState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hearby", Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val clientId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
    private val deviceName = LocalNet.deviceName(appContext)

    private val jitter = JitterBuffer()
    private val clock = ClockSync()

    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var engine: PlayoutEngine? = null
    @Volatile private var track: AudioTrack? = null

    private var rxThread: Thread? = null
    private var controlThread: Thread? = null
    private var playThread: Thread? = null

    // connection state (written by the rx / control threads)
    @Volatile private var joined = false
    @Volatile private var everJoined = false
    @Volatile private var ended = false
    @Volatile private var streamId = -1L
    @Volatile private var hostName = hostLabel
    @Volatile private var playoutDelayMs = 350
    @Volatile private var lastPacketNs = 0L
    @Volatile private var audioPackets = 0L
    @Volatile private var message: String? = null

    // output state
    @Volatile private var userOffsetMs = 0
    @Volatile private var muted = false
    @Volatile private var routeKey = ""
    @Volatile private var routeName = ""
    @Volatile private var routeIsBluetooth = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    fun start() {
        if (running) return
        running = true
        lastPacketNs = System.nanoTime()
        requestFocus()
        ContextCompat.registerReceiver(
            appContext, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
        rxThread = Thread(::receiveLoop, "hearby-rx").also { it.start() }
        playThread = Thread(::playoutLoop, "hearby-playout").also { it.start() }
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
        playThread?.join(1500)
        if (noisyRegistered) {
            noisyRegistered = false
            try {
                appContext.unregisterReceiver(noisyReceiver)
            } catch (e: Exception) {
                // not registered
            }
        }
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** The manual sync slider. Remembered per output device (speaker, each Bluetooth device, ...). */
    fun setUserOffsetMs(ms: Int) {
        val v = ms.coerceIn(-500, 500)
        userOffsetMs = v
        if (routeKey.isNotEmpty()) prefs.edit { putInt(OFFSET_PREFIX + routeKey, v) }
    }

    /** Throw away any fallback delay and line up from scratch. */
    fun resync() {
        engine?.requestRealign(resetLateBias = true)
    }

    fun setMuted(mute: Boolean) {
        if (!mute) requestFocus()
        muted = mute
    }

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
            val len = packet.length
            when (Wire.typeOf(buf, len)) {
                Proto.TYPE_AUDIO -> if (len > Proto.AUDIO_HEADER_SIZE && joined) {
                    lastPacketNs = rxNs
                    audioPackets++
                    jitter.put(
                        Wire.audioSeq(buf), Wire.audioTimestampUs(buf),
                        buf, Proto.AUDIO_HEADER_SIZE, len - Proto.AUDIO_HEADER_SIZE,
                    )
                }
                Proto.TYPE_SYNC_RESP -> {
                    val m = Wire.decodeSyncResp(buf, len) ?: continue
                    lastPacketNs = rxNs
                    val t3 = rxNs / 1000
                    if (m.streamId == streamId && t3 - m.t0Us in 0..1_000_000) {
                        clock.addSample(m.t0Us, m.t1Us, m.t2Us, t3)
                        playoutDelayMs = m.playoutDelayMs
                    }
                }
                Proto.TYPE_JOIN_ACK -> {
                    val m = Wire.decodeJoinAck(buf, len) ?: continue
                    lastPacketNs = rxNs
                    if (m.sampleRate != Proto.SAMPLE_RATE || m.channels != Proto.CHANNELS || m.frameMs != Proto.FRAME_MS) {
                        message = "This party uses an audio format this version of Hearby can't play. Try updating."
                        ended = true
                        continue
                    }
                    if (m.streamId != streamId) {
                        // New session on the host: old audio and old sequence numbers are meaningless.
                        streamId = m.streamId
                        jitter.clear()
                        clock.reset()
                        syncsSent = 0
                        engine?.requestStreamReset()
                    }
                    hostName = m.hostName.ifBlank { hostName }
                    playoutDelayMs = m.playoutDelayMs
                    message = null
                    joined = true
                    everJoined = true
                }
                Proto.TYPE_BYE -> if (Wire.decodeU32Body(buf, len) == streamId) {
                    message = "The DJ ended the party."
                    ended = true
                }
            }
        }
    }

    // ---- network: join, clock sync, keep-alive ----------------------------------------------------

    @Volatile private var syncsSent = 0

    private fun controlLoop(sock: DatagramSocket) {
        val startNs = System.nanoTime()
        var lastJoinNs = 0L
        var lastSyncNs = 0L
        var lastPublishNs = 0L
        var lastRouteCheckNs = 0L
        while (running) {
            val now = System.nanoTime()
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
                    val e = engine
                    val status = when {
                        e == null || e.state != PlayoutEngine.State.PLAYING -> Proto.STATUS_BUFFERING
                        e.lateBiasMs > 0 -> Proto.STATUS_LATE
                        else -> Proto.STATUS_SYNCED
                    }
                    send(sock, Wire.encodeSyncReq(clientId, System.nanoTime() / 1000, clock.rttUs, e?.cushionMs ?: 0, status))
                }
                if (now - lastPacketNs > HOST_TIMEOUT_NS) {
                    joined = false // host went quiet: fall back to knocking until it answers again
                    lastJoinNs = 0
                }
            }
            if (now - lastRouteCheckNs >= 500_000_000L) {
                lastRouteCheckNs = now
                checkRoute()
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

    // ---- playout ---------------------------------------------------------------------------------

    private fun playoutLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var decoder: MediaCodecOpusDecoder? = null
        var sink: TrackSink? = null
        try {
            decoder = MediaCodecOpusDecoder()
            sink = TrackSink()
            val e = PlayoutEngine(sink, decoder, jitter, clock)
            e.playoutDelayMs = playoutDelayMs
            e.userOffsetMs = userOffsetMs
            engine = e
            var appliedMute = false
            while (running && !ended) {
                // Settings are owned by other threads; apply them here, where the engine lives.
                e.playoutDelayMs = playoutDelayMs
                e.userOffsetMs = userOffsetMs
                if (muted != appliedMute) {
                    appliedMute = muted
                    sink.setVolume(if (appliedMute) 0f else 1f)
                }
                e.pump()
                if (sink.consumeRebuilt()) e.requestRealign(resetLateBias = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playout failed", e)
            if (running) {
                message = "Audio playback failed on this device (${e.javaClass.simpleName})."
                ended = true
            }
        } finally {
            engine = null
            sink?.release()
            decoder?.release()
        }
    }

    /**
     * AudioTrack as the engine's output. write() blocks while the track's buffer is full, which is
     * what paces the playout thread. getTimestamp() reports when frames actually hit the output
     * device, including whatever latency the audio HAL (or Bluetooth stack) knows about.
     */
    private inner class TrackSink : AudioSink {
        private var bufferFrames = 0 // set by build(); must be declared before audioTrack
        private var audioTrack: AudioTrack = build()
        private val stamp = AudioTimestamp()
        private var totalFrames = 0L   // everything ever written (the engine's frame counter)
        private var trackBase = 0L     // totalFrames when the current AudioTrack was created
        private var trackStartNs = System.nanoTime()
        private var lastPollNs = 0L
        private var lastGoodNs = 0L
        private var haveStamp = false     // a real AudioTrack timestamp
        private var haveEstimate = false  // a playback-head based estimate (fallback)
        private var cachedPosition = 0L
        private var cachedTimeNs = 0L
        private var rebuilt = false

        private fun build(): AudioTrack {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                // If this phone is hosting too, never feed our own playback back into the capture.
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Proto.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBytes = AudioTrack.getMinBufferSize(
                Proto.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
            )
            // Small enough to keep latency (and so the needed playout delay) low, large enough to
            // ride out scheduling hiccups of this thread.
            val bytes = maxOf(minBytes, TRACK_BUFFER_FRAMES * BYTES_PER_FRAME)
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack could not be created" }
            bufferFrames = t.bufferSizeInFrames
            t.play()
            track = t
            return t
        }

        override fun write(interleaved: FloatArray, frames: Int) {
            val wanted = frames * Proto.CHANNELS
            var done = 0
            while (done < wanted && running) {
                val n = audioTrack.write(interleaved, done, wanted - done, AudioTrack.WRITE_BLOCKING)
                if (n < 0) {
                    // ERROR_DEAD_OBJECT: the output device went away (e.g. Bluetooth disconnected).
                    Log.w(TAG, "AudioTrack.write error $n; rebuilding the track")
                    rebuild()
                    break
                }
                if (n == 0) {
                    // Should not happen with a playing track and a blocking write; never spin on it.
                    try {
                        Thread.sleep(5)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
                done += n
            }
            totalFrames += frames
        }

        private fun rebuild() {
            try {
                audioTrack.release()
            } catch (e: Exception) {
                // already dead
            }
            audioTrack = build()
            trackBase = totalFrames
            trackStartNs = System.nanoTime()
            haveStamp = false
            haveEstimate = false
            lastPollNs = 0
            rebuilt = true
        }

        fun consumeRebuilt(): Boolean = rebuilt.also { rebuilt = false }

        fun setVolume(v: Float) {
            audioTrack.setVolume(v)
        }

        override fun getTimestamp(out: SinkTimestamp): Boolean {
            val now = System.nanoTime()
            if (now - lastPollNs >= TIMESTAMP_POLL_NS) {
                lastPollNs = now
                val ok = try {
                    audioTrack.getTimestamp(stamp)
                } catch (e: Exception) {
                    false
                }
                val written = totalFrames - trackBase
                if (ok && stamp.framePosition > 0 && stamp.framePosition <= written &&
                    abs(now - stamp.nanoTime) < MAX_TIMESTAMP_AGE_NS
                ) {
                    cachedPosition = trackBase + stamp.framePosition
                    cachedTimeNs = stamp.nanoTime
                    lastGoodNs = now
                    haveStamp = true
                    haveEstimate = false
                } else if (haveStamp && now - lastGoodNs > STALE_TIMESTAMP_NS) {
                    haveStamp = false
                }
                if (!haveStamp && now - trackStartNs > FALLBACK_AFTER_NS) {
                    // Some routes never report timestamps. Estimate from the playback head instead;
                    // the manual offset slider absorbs whatever latency this cannot see.
                    val head = audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    if (head in 1..written) {
                        cachedPosition = trackBase + head
                        cachedTimeNs = now + hiddenLatencyNs()
                        haveEstimate = true
                    }
                }
            }
            if (!haveStamp && !haveEstimate) return false
            out.framePosition = cachedPosition
            out.timeNs = cachedTimeNs
            return true
        }

        /** Latency below the track buffer, from the non-SDK AudioTrack.getLatency() when available. */
        private fun hiddenLatencyNs(): Long {
            val total = try {
                (GET_LATENCY?.invoke(audioTrack) as? Int) ?: 0
            } catch (e: Exception) {
                0
            }
            val bufferMs = bufferFrames * 1000L / Proto.SAMPLE_RATE
            return maxOf(0L, total - bufferMs) * 1_000_000L
        }

        fun release() {
            track = null
            try {
                audioTrack.pause()
                audioTrack.flush()
            } catch (e: Exception) {
                // not playing
            }
            audioTrack.release()
        }
    }

    // ---- output route, focus, noisy ------------------------------------------------------------------

    @SuppressLint("InlinedApi") // newer device-type constants are only compared, never passed to the platform
    private fun checkRoute() {
        val device = try {
            track?.routedDevice
        } catch (e: Exception) {
            null
        } ?: return
        val name = device.productName?.toString().orEmpty()
        val key = "${device.type}:$name"
        if (key == routeKey) return
        val bluetooth = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID
        routeKey = key
        routeIsBluetooth = bluetooth
        routeName = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            else -> if (bluetooth) "Bluetooth: ${name.ifBlank { "device" }}" else name.ifBlank { "Audio output" }
        }
        // Every output has its own latency, so every output remembers its own slider position.
        userOffsetMs = prefs.getInt(OFFSET_PREFIX + key, 0)
        engine?.requestRealign(resetLateBias = true)
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> muted = true
            AudioManager.AUDIOFOCUS_GAIN -> muted = false
        }
    }

    private fun requestFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, mainHandler)
            .build()
            .also { focusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    /** Headphones unplugged / Bluetooth dropped: do not suddenly blast from the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            muted = true
        }
    }

    // ---- state for the UI ----------------------------------------------------------------------------

    private var lastLossSampleNs = 0L
    private var lastPlayed = 0L
    private var lastConcealed = 0L
    private var lossPercent = 0f

    private fun publish() {
        val e = engine
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
                playoutDelayMs = playoutDelayMs,
                offsetMs = userOffsetMs,
                outputName = routeName,
                outputIsBluetooth = routeIsBluetooth,
                lossPercent = lossPercent,
                muted = muted,
                level = if (muted) 0f else e?.outputLevel ?: 0f,
            ),
        )
    }

    companion object {
        private const val TAG = "HearbyReceiver"
        private const val OFFSET_PREFIX = "offset:"
        private const val BYTES_PER_FRAME = Proto.CHANNELS * 4
        private const val TRACK_BUFFER_FRAMES = 2880 // 60 ms

        private const val JOIN_INTERVAL_NS = 400_000_000L
        private const val SYNC_BURST = 8
        private const val SYNC_BURST_INTERVAL_NS = 40_000_000L
        private const val SYNC_INTERVAL_NS = 1_000_000_000L
        private const val HOST_TIMEOUT_NS = 4_000_000_000L

        private const val TIMESTAMP_POLL_NS = 100_000_000L
        private const val MAX_TIMESTAMP_AGE_NS = 1_000_000_000L
        private const val STALE_TIMESTAMP_NS = 1_500_000_000L
        private const val FALLBACK_AFTER_NS = 2_000_000_000L

        @Suppress("DiscouragedPrivateApi")
        private val GET_LATENCY = try {
            AudioTrack::class.java.getMethod("getLatency")
        } catch (e: Exception) {
            null
        }
    }
}
