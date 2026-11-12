package io.github.ugoyel.hearby.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.MainActivity
import io.github.ugoyel.hearby.R
import io.github.ugoyel.hearby.client.PlayoutRunner
import io.github.ugoyel.hearby.codec.MediaCodecOpusEncoder
import io.github.ugoyel.hearby.core.ClockSync
import io.github.ugoyel.hearby.core.VideoTimeline
import io.github.ugoyel.hearby.core.WireVideo
import io.github.ugoyel.hearby.core.JitterBuffer
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.Wire
import io.github.ugoyel.hearby.core.WireParty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

data class ListenerInfo(
    val id: Long,
    val name: String,
    val platform: Int,
    val address: String,
    val rttMs: Float,
    val bufferMs: Int,
    val status: Int,
    /** Sound-check result for this phone, if one ran. */
    val check: CheckResult? = null,
)

data class SongInfo(val title: String, val artist: String, val durationMs: Long)

data class SoundCheckUi(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String? = null,
    /** True once a sound check finished successfully in this party. */
    val done: Boolean = false,
)

data class HostUiState(
    val running: Boolean = false,
    /** Why the last session ended, or why it could not start. */
    val message: String? = null,
    val port: Int = Proto.PORT,
    val addresses: List<String> = emptyList(),
    val listeners: List<ListenerInfo> = emptyList(),
    /** Level of what this phone is playing right now, 0..1. */
    val level: Float = 0f,
    val bitrate: Int = 96_000,
    val playoutDelayMs: Int = 350,
    val queue: List<SongInfo> = emptyList(),
    val current: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val loading: Boolean = false,
    val songError: String? = null,
    /** Where this phone's own sound goes (it plays in sync with everyone else). */
    val outputName: String = "",
    val outputIsBluetooth: Boolean = false,
    val soundCheck: SoundCheckUi = SoundCheckUi(),
    /** YouTube mode: the video every phone plays, or null for music. */
    val video: VideoTimeline? = null,
    /** This phone's own correction (slider + sound check), µs, applied to its video player too. */
    val correctionUs: Long = 0,
)

/**
 * HOST (the DJ): plays songs saved on this phone to every phone at the party, this one included.
 *
 *   MusicPlayer (decode, 48 kHz) ─▶ sender thread: one 20 ms frame per 20 ms, music or silence
 *        ─▶ MediaCodec Opus ─▶ [16-byte header + packet] ─┬─▶ UDP to every guest
 *                                                         └─▶ this phone's own PlayoutRunner
 *   control thread: JOIN / SYNC (clock sync + keep-alive) / LEAVE / DISCOVER on one socket
 *   sound check: chirps on every phone, timed by this phone's microphone, corrections sent back
 *
 * The timeline never stops while the party is on (pausing just sends silence), so phones stay
 * locked to it through pauses and song changes. Nothing captures the screen or other apps: this
 * is a plain mediaPlayback foreground service.
 */
class PartyHostService : Service() {

    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent.getIntExtra(EXTRA_BITRATE, 96_000), intent.getIntExtra(EXTRA_DELAY_MS, 350))
            ACTION_STOP -> finish(null)
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession(bitrate: Int, delayMs: Int) {
        if (session != null) return
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            finish("Android did not allow the party to start (${e.javaClass.simpleName}).")
            return
        }
        val s = Session(bitrate, delayMs)
        try {
            s.start()
        } catch (e: Exception) {
            Log.e(TAG, "could not start session", e)
            s.stop()
            finish(e.message ?: "Could not start the party.")
            return
        }
        session = s
        _state.value = HostUiState(
            running = true, port = s.port, addresses = LocalNet.localIpv4Addresses(), bitrate = bitrate, playoutDelayMs = delayMs,
        )
        // Songs or a video shared to Hearby before the party started.
        val waiting = synchronized(pending) { pending.toList().also { pending.clear() } }
        _pendingSongs.value = 0
        if (waiting.isNotEmpty()) s.player.addUris(waiting)
        pendingVideo.getAndSet(null)?.let { s.playVideo(it) }
        _pendingVideoId.value = null
    }

    /** Tear everything down. Safe to call repeatedly. */
    private fun finish(message: String?) {
        val s = session
        session = null
        s?.stop()
        _state.update { HostUiState(message = message, bitrate = it.bitrate, playoutDelayMs = it.playoutDelayMs) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        val s = session
        session = null
        s?.stop()
        if (instance === this) instance = null
        if (_state.value.running) _state.update { HostUiState(bitrate = it.bitrate, playoutDelayMs = it.playoutDelayMs) }
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------------

    private fun buildNotification(listeners: Int, song: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.channel_host), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PartyHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val crowd = if (listeners == 0) getString(R.string.notif_host_text_none)
        else resources.getQuantityString(R.plurals.notif_host_text, listeners, listeners)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hearby)
            .setContentTitle(song ?: getString(R.string.notif_host_title))
            .setContentText(crowd)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    // ---- one party ------------------------------------------------------------------------------

    private class Client(val address: InetSocketAddress, val id: Long, val name: String, val platform: Int) {
        @Volatile var lastSeenNs = System.nanoTime()
        @Volatile var rttUs = 0L
        @Volatile var bufferMs = 0
        @Volatile var status = Proto.STATUS_CONNECTING
        @Volatile var adjustUs = 0L
        @Volatile var check: CheckResult? = null
        /** Reused for every audio datagram to this listener (sender thread only). */
        val audioPacket = DatagramPacket(ByteArray(0), 0, address)
    }

    private inner class Session(private val bitrate: Int, private val playoutDelayMs: Int) {
        private val streamId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        private val hostName = LocalNet.deviceName(this@PartyHostService)
        val player = MusicPlayer(this@PartyHostService)

        @Volatile private var running = false
        @Volatile private var clients: Array<Client> = emptyArray()
        private val clientsLock = Any()

        private var socket: DatagramSocket? = null
        private var encoder: MediaCodecOpusEncoder? = null
        private var senderThread: Thread? = null
        private var controlThread: Thread? = null

        // This phone as a speaker: fed directly, clock offset zero by definition.
        private val localJitter = JitterBuffer()
        private val localClock = ClockSync()
        private val local = PlayoutRunner(this@PartyHostService, localJitter, localClock)
        val localId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL

        @Volatile private var lastSentTimestampUs = 0L
        @Volatile private var video: VideoTimeline? = null
        private var videoVersion = 0L
        private var sentVideoVersion = -1L
        private var lastVideoNs = 0L
        @Volatile private var checkRunner: SoundCheckRunner? = null
        @Volatile private var checkIds = 0
        @Volatile private var soundCheck = SoundCheckUi()

        private var wakeLock: PowerManager.WakeLock? = null
        private var wifiLock: WifiManager.WifiLock? = null
        private var multicastLock: WifiManager.MulticastLock? = null
        private var nsdListener: NsdManager.RegistrationListener? = null
        private var notified = -1 to ""
        private var lastNowPlaying = ""
        private var lastNowPlayingNs = 0L

        var port = Proto.PORT
            private set

        fun start() {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            try {
                s.bind(InetSocketAddress(Proto.PORT))
            } catch (e: IOException) {
                s.bind(InetSocketAddress(0)) // port taken: mDNS still advertises whichever we got
            }
            try {
                s.trafficClass = 0xB8 // DSCP EF: Wi-Fi puts these frames in the voice queue (WMM AC_VO)
            } catch (e: Exception) {
                // not fatal
            }
            LocalNet.bindHostSocketIfNeeded(this@PartyHostService, s)
            socket = s
            port = s.localPort

            encoder = try {
                MediaCodecOpusEncoder(bitrate)
            } catch (e: Exception) {
                throw IllegalStateException("This device has no Opus encoder.", e)
            }

            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hearby:host").apply {
                setReferenceCounted(false)
                acquire(MAX_SESSION_MS)
            }
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi?.createWifiLock(wifiMode, "hearby:host")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = wifi?.createMulticastLock("hearby:host")?.apply {
                setReferenceCounted(false)
                acquire()
            }

            // Same clock on both sides: three zero-offset, zero-delay "exchanges" make it synced.
            repeat(3) { val t = System.nanoTime() / 1000; localClock.addSample(t, t, t, t) }
            local.playoutDelayMs = playoutDelayMs
            local.start()

            running = true
            senderThread = Thread(::sendLoop, "hearby-sender").also { it.start() }
            controlThread = Thread(::controlLoop, "hearby-control").also { it.start() }
            registerService()
        }

        fun stop() {
            val wasRunning = running
            running = false
            checkRunner?.cancel()
            unregisterService()
            val s = socket
            if (wasRunning && s != null) {
                val bye = Wire.encodeBye(streamId)
                Thread {
                    repeat(3) {
                        for (c in clients) {
                            try {
                                s.send(DatagramPacket(bye, bye.size, c.address))
                            } catch (e: Exception) {
                                // listener is gone already
                            }
                        }
                    }
                }.apply { start(); join(500) }
            }
            s?.close()
            senderThread?.join(1500)
            controlThread?.join(1500)
            local.stop()
            encoder?.release()
            encoder = null
            socket = null
            player.release()
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        }

        // ---- sender: one frame every 20 ms, music or silence ------------------------------------

        private fun sendLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val enc = encoder ?: return
            val sock = socket ?: return
            val frame = FloatArray(Proto.FRAME_SAMPLES * Proto.CHANNELS)
            val pcm = ByteArray(MediaCodecOpusEncoder.PCM_BLOCK_BYTES)
            val datagram = ByteArray(Proto.AUDIO_HEADER_SIZE + Proto.MAX_PAYLOAD)
            var seq = 0L
            val send = MediaCodecOpusEncoder.PacketSink { data: ByteBuffer, size: Int, timestampUs: Long ->
                if (size <= Proto.MAX_PAYLOAD) {
                    Wire.writeAudioHeader(datagram, seq, timestampUs)
                    data.get(datagram, Proto.AUDIO_HEADER_SIZE, size)
                    seq++
                    val length = Proto.AUDIO_HEADER_SIZE + size
                    for (c in clients) {
                        c.audioPacket.setData(datagram, 0, length)
                        try {
                            sock.send(c.audioPacket)
                        } catch (e: IOException) {
                            // unreachable right now; the control thread drops it if it stays silent
                        }
                    }
                    // ... and this phone plays exactly the same packet.
                    localJitter.put(Wire.audioSeq(datagram), timestampUs, datagram, Proto.AUDIO_HEADER_SIZE, size)
                    lastSentTimestampUs = timestampUs
                }
            }

            var nextNs = System.nanoTime()
            try {
                while (running) {
                    val now = System.nanoTime()
                    if (now < nextNs) {
                        LockSupport.parkNanos(nextNs - now)
                        continue
                    }
                    if (now - nextNs > MAX_LAG_NS) nextNs = now // starved for a while: jump (phones re-align once)
                    // While a YouTube video is on, every phone plays it from YouTube: the stream carries silence.
                    if (video == null) player.pull(frame) else java.util.Arrays.fill(frame, 0f)
                    toPcm16(frame, pcm)
                    enc.encode(pcm, nextNs / 1000, send)
                    if (enc.unsupportedFraming) {
                        fail("This device's Opus encoder uses an unsupported frame size.")
                        return
                    }
                    nextNs += Proto.FRAME_US * 1000
                }
            } catch (e: Exception) {
                Log.e(TAG, "send loop died", e)
                if (running) fail("The party stopped unexpectedly: ${e.javaClass.simpleName}")
            }
        }

        private fun toPcm16(src: FloatArray, dst: ByteArray) {
            for (i in src.indices) {
                val v = (src[i].coerceIn(-1f, 1f) * 32767f).toInt()
                dst[2 * i] = v.toByte()
                dst[2 * i + 1] = (v shr 8).toByte()
            }
        }

        private fun fail(message: String) {
            mainHandler.post { if (session === this) finish(message) }
        }

        // ---- control: join / clock sync / discovery ----------------------------------------------

        private fun controlLoop() {
            val sock = socket ?: return
            val buf = ByteArray(Proto.MAX_DATAGRAM)
            val packet = DatagramPacket(buf, buf.size)
            var lastHousekeepingNs = 0L
            try {
                sock.soTimeout = 100
            } catch (e: Exception) {
                return
            }
            while (running) {
                try {
                    packet.setData(buf, 0, buf.size)
                    sock.receive(packet)
                    val rxUs = System.nanoTime() / 1000 // t1: as close to arrival as we can get
                    handle(sock, buf, packet.length, packet.socketAddress as InetSocketAddress, rxUs)
                } catch (e: SocketTimeoutException) {
                    // fall through to housekeeping
                } catch (e: IOException) {
                    if (!running) break
                }
                val now = System.nanoTime()
                if (now - lastHousekeepingNs > 120_000_000L) {
                    lastHousekeepingNs = now
                    housekeeping(sock, now)
                }
            }
        }

        private fun handle(sock: DatagramSocket, b: ByteArray, len: Int, from: InetSocketAddress, rxUs: Long) {
            when (Wire.typeOf(b, len)) {
                Proto.TYPE_JOIN -> {
                    val m = Wire.decodeJoin(b, len) ?: return
                    synchronized(clientsLock) {
                        val kept = clients.filter { it.address != from && it.id != m.clientId }
                        if (kept.size >= MAX_LISTENERS) return
                        clients = (kept + Client(from, m.clientId, m.name, m.platform)).toTypedArray()
                    }
                    reply(sock, Wire.encodeJoinAck(streamId, playoutDelayMs, hostName), from)
                    lastNowPlaying = "" // make sure the newcomer hears what is playing
                }
                Proto.TYPE_SYNC_REQ -> {
                    val m = Wire.decodeSyncReq(b, len) ?: return
                    val c = clients.firstOrNull { it.address == from } ?: return // must JOIN first
                    c.lastSeenNs = System.nanoTime()
                    c.rttUs = m.rttUs
                    c.bufferMs = m.bufferMs
                    c.status = m.status
                    c.adjustUs = m.adjustUs
                    val resp = Wire.encodeSyncResp(streamId, m.t0Us, rxUs, 0, playoutDelayMs)
                    Wire.patchSyncRespT2(resp, System.nanoTime() / 1000) // t2: immediately before sending
                    reply(sock, resp, from)
                }
                Proto.TYPE_LEAVE -> synchronized(clientsLock) {
                    clients = clients.filter { it.address != from }.toTypedArray()
                }
                Proto.TYPE_DISCOVER -> {
                    val nonce = Wire.decodeU32Body(b, len)
                    if (nonce >= 0) reply(sock, Wire.encodeDiscoverResp(nonce, streamId, clients.size, hostName), from)
                }
            }
        }

        private fun reply(sock: DatagramSocket, data: ByteArray, to: InetSocketAddress) {
            try {
                sock.send(DatagramPacket(data, data.size, to))
            } catch (e: IOException) {
                // listener unreachable
            }
        }

        private fun sendToClient(id: Long, data: ByteArray) {
            val c = clients.firstOrNull { it.id == id } ?: return
            socket?.let { reply(it, data, c.address) }
        }

        private fun housekeeping(sock: DatagramSocket, nowNs: Long) {
            synchronized(clientsLock) {
                if (clients.any { nowNs - it.lastSeenNs > CLIENT_TIMEOUT_NS }) {
                    clients = clients.filter { nowNs - it.lastSeenNs <= CLIENT_TIMEOUT_NS }.toTypedArray()
                }
            }
            val snapshot = clients
            val music = player.snapshot()
            val song = music.queue.getOrNull(music.index)

            // Tell everyone what is playing: on every change, and once a second for anyone who missed it.
            val v = video
            val title = if (v != null) v.title.ifBlank { "YouTube video" } else song?.title.orEmpty()
            val artist = if (v != null) "YouTube" else song?.artist.orEmpty()
            val on = if (v != null) v.playing else music.playing && !player.hold
            val nowPlaying = "$title|$artist|$on"
            if (nowPlaying != lastNowPlaying || nowNs - lastNowPlayingNs > 1_000_000_000L) {
                lastNowPlaying = nowPlaying
                lastNowPlayingNs = nowNs
                val msg = WireParty.encodeNowPlaying(streamId, on, title, artist)
                for (c in snapshot) reply(sock, msg, c.address)
            }
            // ... and the video timeline: on every change, then once a second.
            if (videoVersion != sentVideoVersion || nowNs - lastVideoNs > 1_000_000_000L) {
                sentVideoVersion = videoVersion
                lastVideoNs = nowNs
                val msg = WireVideo.encode(streamId, v)
                for (c in snapshot) reply(sock, msg, c.address)
            }

            _state.update {
                if (!it.running) it
                else it.copy(
                    listeners = snapshot.map { c ->
                        ListenerInfo(c.id, c.name, c.platform, c.address.address?.hostAddress ?: "?", c.rttUs / 1000f, c.bufferMs, c.status, c.check)
                    },
                    level = if (local.muted) 0f else local.engine?.outputLevel ?: 0f,
                    addresses = if (it.addresses.isEmpty()) LocalNet.localIpv4Addresses() else it.addresses,
                    queue = music.queue.map { s -> SongInfo(s.title, s.artist, s.durationMs) },
                    current = music.index,
                    playing = music.playing,
                    positionMs = music.positionMs,
                    loading = music.loading,
                    songError = music.error,
                    outputName = local.routeName,
                    outputIsBluetooth = local.routeIsBluetooth,
                    soundCheck = soundCheck,
                    video = v,
                    correctionUs = local.userOffsetMs * 1000L + local.calibrationUs,
                )
            }
            val note = snapshot.size to (if (music.playing) song?.title.orEmpty() else "")
            if (note != notified) {
                notified = note
                try {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(snapshot.size, note.second.ifBlank { null }))
                } catch (e: Exception) {
                    // notifications disabled
                }
            }
        }

        // ---- YouTube mode ----------------------------------------------------------------------------
        // The timeline is defined on this phone's clock; every phone (this one too) steers its own
        // YouTube player onto it. Changes are announced a moment ahead so all phones act together.

        private fun nowUs() = System.nanoTime() / 1000

        @Synchronized
        fun playVideo(videoId: String) {
            player.pause()
            videoVersion++
            video = VideoTimeline(videoId, "", playing = true, anchorHostUs = nowUs() + VIDEO_START_LEAD_US, positionUs = 0, version = videoVersion)
            lastVideoNs = 0
        }

        @Synchronized
        fun toggleVideo() {
            val v = video ?: return
            val now = nowUs()
            videoVersion++
            video = if (v.playing) {
                v.copy(playing = false, anchorHostUs = now, positionUs = v.positionAt(now), version = videoVersion)
            } else {
                v.copy(playing = true, anchorHostUs = now + VIDEO_RESUME_LEAD_US, version = videoVersion)
            }
        }

        @Synchronized
        fun stopVideo() {
            if (video == null) return
            videoVersion++
            video = null
        }

        @Synchronized
        fun videoTitle(videoId: String, title: String) {
            val v = video ?: return
            if (v.videoId != videoId || v.title == title || title.isBlank()) return
            videoVersion++
            video = v.copy(title = title.take(120), version = videoVersion)
        }

        @Synchronized
        fun videoEnded(videoId: String) {
            val v = video ?: return
            if (v.videoId != videoId || !v.playing) return
            val now = nowUs()
            if (v.startsInUs(now) > 0) return
            videoVersion++
            video = v.copy(playing = false, anchorHostUs = now, positionUs = v.positionAt(now), version = videoVersion)
        }

        // ---- sound check -------------------------------------------------------------------------

        fun startSoundCheck() {
            if (checkRunner != null) return
            val guests = clients.filter { it.status == Proto.STATUS_SYNCED || it.status == Proto.STATUS_LATE }
            if (guests.isEmpty()) {
                soundCheck = SoundCheckUi(message = "Wait until your friends show \"In sync\", then run the sound check.")
                return
            }
            val base = checkIds
            checkIds += 10
            val runner = SoundCheckRunner(
                this@PartyHostService, localId, playoutDelayMs,
                mediaNowUs = { lastSentTimestampUs },
                requestChirp = { id, mediaUs, checkId ->
                    if (id == localId) local.scheduleTone(mediaUs) else sendToClient(id, WireParty.encodeChirp(streamId, id, mediaUs, checkId))
                },
                applyCorrection = { id, adjustUs, checkId, errorUs ->
                    repeat(3) { sendToClient(id, WireParty.encodeOffset(streamId, id, adjustUs, checkId, errorUs)) }
                },
                onProgress = { text, progress -> soundCheck = SoundCheckUi(running = true, progress = progress, message = text) },
            )
            checkRunner = runner
            soundCheck = SoundCheckUi(running = true, message = "Getting ready…")
            Thread({
                player.hold = true // the beeps need a quiet moment
                val outcome = try {
                    runner.run(
                        guests.map { c -> CheckParticipant(c.id, c.name) { c.adjustUs } },
                        CheckParticipant(localId, "DJ") { local.adjustUs() },
                        base,
                    )
                } finally {
                    player.hold = false
                    checkRunner = null
                }
                for (c in guests) outcome.results[c.id]?.let { c.check = it }
                soundCheck = summarize(outcome)
            }, "hearby-soundcheck").start()
        }

        private fun summarize(outcome: SoundCheckRunner.Outcome): SoundCheckUi {
            outcome.failure?.let { return SoundCheckUi(message = it) }
            val results = outcome.results.values
            val missed = results.count { !it.heard }
            val worst = results.filter { it.heard }.maxOfOrNull { abs(it.afterMs) } ?: 0f
            val text = when {
                missed == results.size -> "Couldn't hear any of the other phones. Hold them close to this one, turn their volume up, and try again."
                missed > 0 -> "Lined up ${results.size - missed} of ${results.size} phones. Move the others closer and run it again."
                worst > 3f -> "Lined up, but a phone was still ${"%.1f".format(worst)} ms off. Pick a bigger sync cushion if it keeps happening."
                else -> "Every phone lined up to within ${"%.1f".format(maxOf(worst, 0.1f))} ms."
            }
            return SoundCheckUi(message = text, done = missed < results.size)
        }

        // ---- mDNS / DNS-SD advertisement -----------------------------------------------------------

        private fun registerService() {
            val nsd = getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "Hearby $hostName".take(60)
                serviceType = Proto.SERVICE_TYPE
                port = this@Session.port
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "advertising as ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode (broadcast discovery still works)")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
                nsdListener = listener
            } catch (e: Exception) {
                Log.w(TAG, "mDNS registration threw", e)
            }
        }

        private fun unregisterService() {
            val listener = nsdListener ?: return
            nsdListener = null
            try {
                getSystemService(NsdManager::class.java)?.unregisterService(listener)
            } catch (e: Exception) {
                // never registered
            }
        }

        fun setOffsetMs(ms: Int) = local.setUserOffsetMs(ms)
    }

    companion object {
        private const val TAG = "HearbyHost"
        private const val ACTION_START = "io.github.ugoyel.hearby.host.START"
        private const val ACTION_STOP = "io.github.ugoyel.hearby.host.STOP"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_DELAY_MS = "delayMs"
        private const val CHANNEL_ID = "host"
        private const val NOTIFICATION_ID = 41

        private const val MAX_LISTENERS = 32
        private const val CLIENT_TIMEOUT_NS = 6_000_000_000L
        private const val MAX_SESSION_MS = 12 * 60 * 60 * 1000L
        private const val MAX_LAG_NS = 200_000_000L
        /** Time for every phone to load the video before it starts. */
        private const val VIDEO_START_LEAD_US = 2_500_000L
        private const val VIDEO_RESUME_LEAD_US = 800_000L

        private val _state = MutableStateFlow(HostUiState())
        val state: StateFlow<HostUiState> = _state.asStateFlow()

        @Volatile
        private var instance: PartyHostService? = null

        private val player get() = instance?.session?.player

        fun start(context: Context, bitrate: Int, playoutDelayMs: Int) {
            _state.update { it.copy(message = null) }
            val intent = Intent(context, PartyHostService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BITRATE, bitrate)
                .putExtra(EXTRA_DELAY_MS, playoutDelayMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PartyHostService::class.java).setAction(ACTION_STOP))
        }

        fun clearMessage() = _state.update { it.copy(message = null) }

        fun addSongs(uris: List<Uri>) = player?.addUris(uris)

        private val pending = ArrayList<Uri>()
        private val _pendingSongs = MutableStateFlow(0)

        /** Songs shared to Hearby that will be queued as soon as the party starts. */
        val pendingSongs: StateFlow<Int> = _pendingSongs.asStateFlow()

        /** Queue songs now if the party is on, or as soon as it starts. */
        fun queueSongs(uris: List<Uri>) {
            val p = player
            if (p != null) {
                p.addUris(uris)
            } else {
                synchronized(pending) {
                    pending.addAll(uris)
                    _pendingSongs.value = pending.size
                }
            }
            _djRequests.value++
        }

        fun playSong(index: Int) = player?.play(index)

        fun togglePlay() = player?.toggle()

        fun next() = player?.next()

        fun previous() = player?.previous()

        fun removeSong(index: Int) = player?.remove(index)

        fun soundCheck() = instance?.session?.startSoundCheck()

        private val pendingVideo = java.util.concurrent.atomic.AtomicReference<String?>(null)
        private val _pendingVideoId = MutableStateFlow<String?>(null)

        /** A YouTube video shared to Hearby that starts as soon as the party does. */
        val pendingVideoId: StateFlow<String?> = _pendingVideoId.asStateFlow()

        private val _djRequests = MutableStateFlow(0)

        /** Counts songs or videos shared to Hearby: the screen switches to the DJ tab when it changes. */
        val djRequests: StateFlow<Int> = _djRequests.asStateFlow()

        /** Play a YouTube video on every phone now, or as soon as the party starts. */
        fun queueVideo(videoId: String) {
            val s = instance?.session
            if (s != null) {
                s.playVideo(videoId)
            } else {
                pendingVideo.set(videoId)
                _pendingVideoId.value = videoId
            }
            _djRequests.value++
        }

        fun playVideo(videoId: String) = instance?.session?.playVideo(videoId)

        fun toggleVideo() = instance?.session?.toggleVideo()

        fun stopVideo() = instance?.session?.stopVideo()

        fun videoTitle(videoId: String, title: String) = instance?.session?.videoTitle(videoId, title)

        fun videoEnded(videoId: String) = instance?.session?.videoEnded(videoId)


    }
}
