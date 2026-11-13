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
import androidx.core.content.edit
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.MainActivity
import io.github.ugoyel.hearby.R
import io.github.ugoyel.hearby.client.PlayoutRunner
import io.github.ugoyel.hearby.codec.MediaCodecOpusEncoder
import io.github.ugoyel.hearby.core.AutoTuner
import io.github.ugoyel.hearby.core.ClockSync
import io.github.ugoyel.hearby.core.VoiceTape
import io.github.ugoyel.hearby.core.Karaoke
import io.github.ugoyel.hearby.core.Ducker
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
    /** Someone is on Bluetooth or in a browser and not sound-checked yet: offer the sound check. */
    val suggestSoundCheck: Boolean = false,
    /** Karaoke: the DJ's microphone is on (and how loud the voice is, 0..1). */
    val micOn: Boolean = false,
    val micLevel: Float = 0f,
    /** This phone went quiet: another app took the sound, or its headphones were unplugged. */
    val muted: Boolean = false,
    /** The guest page for phones without the app (iPhones), e.g. http://192.168.43.1:47801, or null. */
    val webUrls: List<String> = emptyList(),
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
            ACTION_START -> startSession()
            ACTION_STOP -> finish(null)
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (session != null) return
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            finish("Android did not allow the party to start (${e.javaClass.simpleName}).")
            return
        }
        // Start from what worked at the last party; the tuner takes it from there.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tuner = AutoTuner(
            prefs.getInt(PREF_DELAY_MS, AutoTuner.DEFAULT_DELAY_MS),
            prefs.getInt(PREF_BITRATE, AutoTuner.MAX_BITRATE),
        )
        val s = Session(tuner)
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
            running = true, port = s.port, addresses = LocalNet.localIpv4Addresses(), bitrate = tuner.bitrate, playoutDelayMs = tuner.delayMs,
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
        @Volatile var lossPercent = 0
        @Volatile var bluetooth = false
        @Volatile var rttUs = 0L
        @Volatile var bufferMs = 0
        @Volatile var status = Proto.STATUS_CONNECTING
        @Volatile var adjustUs = 0L
        @Volatile var check: CheckResult? = null
        /** Reused for every audio datagram to this listener (sender thread only). */
        val audioPacket = DatagramPacket(ByteArray(0), 0, address)
    }

    private inner class Session(private val tuner: AutoTuner) {
        /** The sync cushion in effect; the tuner changes it at quiet moments. */
        @Volatile private var playoutDelayMs = tuner.delayMs
        private val streamId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        private val hostName = LocalNet.deviceName(this@PartyHostService)
        val player = MusicPlayer(this@PartyHostService)

        @Volatile private var running = false
        @Volatile private var clients: Array<Client> = emptyArray()
        private val clientsLock = Any()

        private var socket: DatagramSocket? = null
        private var encoder: MediaCodecOpusEncoder? = null
        private var localEncoder: MediaCodecOpusEncoder? = null

        /**
         * The karaoke microphone while it is ready: listening, this phone a little ahead of the room,
         * and the guests' stream waiting for the voice. It stays ready for a while after each use so
         * push-to-talk doesn't make this phone's playback jump every time.
         */
        @Volatile private var mic: KaraokeMic? = null
        /** The voice goes out right now (the button is held, or switched on). */
        @Volatile private var micOpen = false
        @Volatile private var micReadyUntilNs = 0L
        private var senderThread: Thread? = null
        private var controlThread: Thread? = null

        // This phone as a speaker: fed directly, clock offset zero by definition.
        private val localJitter = JitterBuffer()
        private val localClock = ClockSync()
        private val local = PlayoutRunner(this@PartyHostService, localJitter, localClock)
        val localId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL

        // Phones without the app join in their browser (iPhones in Safari): see WebGuests.
        private val web = WebGuests(
            files = { name -> readAsset("web/$name") },
            helloJson = { id ->
                """{"t":"hello","id":$id,"stream":$streamId,"host":${FlatJson.quote(hostName)},"delay":$playoutDelayMs}"""
            },
            log = { m, e -> Log.w(TAG, m, e) },
        )
        @Volatile private var webPort = 0

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
        private var lastTuneNs = 0L

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

            localEncoder = try {
                MediaCodecOpusEncoder(LOCAL_BITRATE)
            } catch (e: Exception) {
                throw IllegalStateException("This device has no Opus encoder.", e)
            }
            encoder = try {
                MediaCodecOpusEncoder(tuner.bitrate)
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

            webPort = try {
                web.start()
            } catch (e: IOException) {
                Log.w(TAG, "guest page unavailable", e)
                0
            }

            // When a cushion or quality change is waiting, leave a short gap between songs to make it in.
            player.gapBetweenSongs = { if (tuner.pending && checkRunner == null) SONG_GAP_FRAMES else 0 }

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
            if (wasRunning) web.stop("The DJ ended the party.") else web.stop()
            senderThread?.join(1500)
            controlThread?.join(1500)
            local.stop()
            if (wasRunning) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit {
                    putInt(PREF_DELAY_MS, tuner.delayMs)
                    putInt(PREF_BITRATE, tuner.bitrate)
                }
            }
            mic?.stop()
            mic = null
            encoder?.release()
            encoder = null
            localEncoder?.release()
            localEncoder = null
            socket = null
            player.release()
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        }

        // ---- sender: one frame every 20 ms, music or silence ------------------------------------

        private fun sendLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var enc = encoder ?: return
            val localEnc = localEncoder ?: return
            val sock = socket ?: return
            val frame = FloatArray(Proto.FRAME_SAMPLES * Proto.CHANNELS)
            val localFrame = FloatArray(frame.size)
            val voice = FloatArray(Proto.FRAME_SAMPLES)
            val pcm = ByteArray(MediaCodecOpusEncoder.PCM_BLOCK_BYTES)
            val datagram = ByteArray(Proto.AUDIO_HEADER_SIZE + Proto.MAX_PAYLOAD)
            val localPacket = ByteArray(Proto.AUDIO_HEADER_SIZE + Proto.MAX_PAYLOAD)
            var seq = 0L
            var localSeq = 0L
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
                    lastSentTimestampUs = timestampUs
                }
            }
            // This phone's own speaker gets its own copy: the same music, but never the karaoke voice
            // (no echo of yourself), and on time even while the guests' stream waits for the voice.
            val toLocal = MediaCodecOpusEncoder.PacketSink { data: ByteBuffer, size: Int, timestampUs: Long ->
                if (size <= Proto.MAX_PAYLOAD) {
                    Wire.writeAudioHeader(localPacket, localSeq, timestampUs)
                    data.get(localPacket, Proto.AUDIO_HEADER_SIZE, size)
                    localJitter.put(localSeq, timestampUs, localPacket, Proto.AUDIO_HEADER_SIZE, size)
                    localSeq++
                }
            }

            val localDucker = Ducker()
            val guestDucker = Ducker()
            // Karaoke: the guests' frames wait here until the voice sung over them has been captured.
            val held = ArrayDeque<Pair<Long, FloatArray>>()
            var lookahead = 0
            var lastTape: VoiceTape? = null
            var webSeq = 0L

            fun toGuests(ts: Long, f: FloatArray, tape: VoiceTape?, duck: Float) {
                guestDucker.process(f, Proto.FRAME_SAMPLES, duck)
                if (tape != null) {
                    tape.read(Karaoke.sungAtUs(ts, local.adjustUs()), Proto.FRAME_SAMPLES, voice)
                    Karaoke.mix(f, voice, Proto.FRAME_SAMPLES)
                }
                toPcm16(f, pcm)
                // Browsers get the same frame as PCM (silence is skipped: they keep time by clock sync).
                if (f.any { it != 0f }) web.pushAudio(webSeq, ts, pcm, Proto.FRAME_SAMPLES * Proto.CHANNELS * 2)
                webSeq++
                enc.encode(pcm, ts, send)
            }

            var quietFrames = 0
            var nextNs = System.nanoTime()
            try {
                while (running) {
                    val now = System.nanoTime()
                    if (now < nextNs) {
                        LockSupport.parkNanos(nextNs - now)
                        continue
                    }
                    if (now - nextNs > MAX_LAG_NS) nextNs = now // starved for a while: jump (phones re-align once)
                    val ts = nextNs / 1000
                    // While a YouTube video is on, every phone plays it from YouTube: the stream carries silence
                    // (and the karaoke voice).
                    val audible = if (video == null) player.pull(frame) else false.also { java.util.Arrays.fill(frame, 0f) }
                    quietFrames = if (audible) 0 else quietFrames + 1
                    val m = mic
                    // Cushion and quality change only while nothing is playing: phones switch a moment apart.
                    if (quietFrames >= QUIET_FRAMES && tuner.pending && checkRunner == null && m == null && held.isEmpty()) {
                        enc = applyTuning(enc)
                    }
                    // Songs duck in the stream itself; YouTube ducks in every phone's player.
                    val duck = if (micOpen && video == null) Karaoke.DUCK_GAIN else 1f

                    System.arraycopy(frame, 0, localFrame, 0, frame.size)
                    localDucker.process(localFrame, Proto.FRAME_SAMPLES, duck)
                    toPcm16(localFrame, pcm)
                    localEnc.encode(pcm, ts, toLocal)

                    if (m != null) {
                        lastTape = m.tape
                        lookahead = maxOf(lookahead, Karaoke.lookaheadFrames(local.adjustUs()))
                        held.addLast(ts to frame.copyOf())
                        while (held.size > lookahead) {
                            val (t, f) = held.removeFirst()
                            toGuests(t, f, m.tape, duck)
                        }
                    } else {
                        // The mic just went off: send what was waiting (with the voice already sung).
                        while (held.isNotEmpty()) {
                            val (t, f) = held.removeFirst()
                            toGuests(t, f, lastTape, duck)
                        }
                        lookahead = 0
                        toGuests(ts, frame, null, duck)
                    }
                    if (enc.unsupportedFraming || localEnc.unsupportedFraming) {
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

        /** Switch to the tuner's cushion and quality (sending thread, at a quiet moment). */
        private fun applyTuning(current: MediaCodecOpusEncoder): MediaCodecOpusEncoder = synchronized(tuner) {
            var enc = current
            if (tuner.wantBitrate != tuner.bitrate) {
                try {
                    val fresh = MediaCodecOpusEncoder(tuner.wantBitrate)
                    current.release()
                    enc = fresh
                    encoder = fresh
                } catch (e: Exception) {
                    Log.w(TAG, "could not switch to ${tuner.wantBitrate} bps", e)
                }
            }
            if (tuner.wantDelayMs != playoutDelayMs) {
                playoutDelayMs = tuner.wantDelayMs // guests pick it up from their next SYNC_RESP
                if (mic == null) local.playoutDelayMs = playoutDelayMs
            }
            tuner.applied(System.nanoTime() / 1_000_000)
            Log.i(TAG, "auto-tuned: cushion ${tuner.delayMs} ms, ${tuner.bitrate / 1000} kbps")
            enc
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
                    c.lossPercent = m.lossPercent
                    c.bluetooth = m.bluetooth
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

        /** What is playing, for the guest page (see WebGuests). */
        private fun webState(title: String, artist: String, playing: Boolean, v: VideoTimeline?): String {
            val video = v?.let {
                """{"id":${FlatJson.quote(it.videoId)},"title":${FlatJson.quote(it.title)},"playing":${it.playing},""" +
                    """"anchor":${it.anchorHostUs},"pos":${it.positionUs},"v":${it.version}}"""
            } ?: "null"
            return """{"t":"state","title":${FlatJson.quote(title)},"artist":${FlatJson.quote(artist)},"playing":$playing,""" +
                """"delay":$playoutDelayMs,"duck":$micOpen,"video":$video}"""
        }

        private fun readAsset(path: String): ByteArray? = try {
            assets.open(path).use { it.readBytes() }
        } catch (e: IOException) {
            null
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
            web.dropSilent(CLIENT_TIMEOUT_NS, nowNs)
            // The DJ phone's own YouTube player mustn't silence its stream either (see PlayoutRunner).
            local.ownVideoOn = video != null
            // Push-to-talk: once the mic has been closed for a while, put this phone back in step.
            if (mic != null && !micOpen && nowNs > micReadyUntilNs) micOff()
            val snapshot = clients
            val browsers = web.guests
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
                val msg = WireVideo.encode(streamId, v, duck = micOpen)
                for (c in snapshot) reply(sock, msg, c.address)
            }
            web.setState(webState(title, artist, on, v))

            // Once a second, let the tuner look at how everyone is doing.
            if (nowNs - lastTuneNs >= 1_000_000_000L) {
                lastTuneNs = nowNs
                val reports = snapshot.map { c ->
                    AutoTuner.Guest(c.id, c.bufferMs, c.status, c.lossPercent, c.bluetooth, soundChecked = c.check?.heard == true)
                } + browsers.map { g ->
                    AutoTuner.Guest(g.id, g.bufferMs, g.status, browser = true, soundChecked = g.check?.heard == true, opus = false)
                }
                synchronized(tuner) { tuner.observe(nowNs / 1_000_000, reports) }
            }

            _state.update {
                if (!it.running) it
                else it.copy(
                    listeners = snapshot.map { c ->
                        ListenerInfo(c.id, c.name, c.platform, c.address.address?.hostAddress ?: "?", c.rttUs / 1000f, c.bufferMs, c.status, c.check)
                    } + browsers.map { g ->
                        ListenerInfo(g.id, g.name, Proto.PLATFORM_WEB, g.address, g.rttUs / 1000f, g.bufferMs, g.status, g.check)
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
                    // Karaoke: this phone's video runs ahead of the room so the voice lands on the beat.
                    correctionUs = if (mic != null) {
                        local.adjustUs() + (Karaoke.MONITOR_DELAY_MS - playoutDelayMs) * 1000L
                    } else {
                        local.userOffsetMs * 1000L + local.calibrationUs
                    },
                    micOn = micOpen,
                    micLevel = if (micOpen) mic?.level ?: 0f else 0f,
                    bitrate = tuner.bitrate,
                    playoutDelayMs = playoutDelayMs,
                    suggestSoundCheck = tuner.suggestSoundCheck,
                    muted = local.muted,
                    webUrls = if (webPort == 0) emptyList() else (if (it.addresses.isEmpty()) LocalNet.localIpv4Addresses() else it.addresses)
                        .map { a -> "http://$a:$webPort" },
                )
            }
            val note = snapshot.size + browsers.size to (if (music.playing) song?.title.orEmpty() else "")
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
        fun unmute() = local.setMuted(false)

        /**
         * The DJ's mic: open (voice to every phone, music dips) or closed. Opening makes it ready
         * first: this phone then plays a little ahead of the room (so the voice sung over it lands on
         * the beat everywhere) and the guests' stream waits a few frames for the voice. False if the
         * microphone could not be opened.
         */
        @Synchronized
        fun setMicOpen(open: Boolean): Boolean {
            if (open) {
                if (checkRunner != null) return false
                if (mic == null) {
                    val m = KaraokeMic(this@PartyHostService)
                    if (!m.start()) return false
                    synchronized(tuner) { tuner.floorMs = maxOf(tuner.floorMs, KARAOKE_MIN_DELAY_MS) } // keep room for the voice
                    local.playoutDelayMs = Karaoke.MONITOR_DELAY_MS
                    mic = m
                }
                mic?.open = true
                micOpen = true
                micReadyUntilNs = Long.MAX_VALUE
            } else {
                mic?.open = false
                micOpen = false
                micReadyUntilNs = System.nanoTime() + MIC_READY_NS
            }
            lastVideoNs = 0 // tell every phone's YouTube player to duck (or not) right away
            return true
        }

        /** Mic fully off: this phone back in step with the room (leaving the app, sound check, party over). */
        @Synchronized
        fun micOff() {
            micOpen = false
            val m = mic ?: return
            mic = null
            local.playoutDelayMs = playoutDelayMs
            m.stop()
            lastVideoNs = 0
        }

        fun reclaimSound() = local.reclaimSound()

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
            micOff() // the check needs the microphone, and a quiet moment
            val inSync = { status: Int -> status == Proto.STATUS_SYNCED || status == Proto.STATUS_LATE }
            val guests = clients.filter { inSync(it.status) }
            val browsers = web.guests.filter { inSync(it.status) }
            if (guests.isEmpty() && browsers.isEmpty()) {
                soundCheck = SoundCheckUi(message = "Wait until your friends show \"In sync\", then run the sound check.")
                return
            }
            val base = checkIds
            checkIds += 10
            val runner = SoundCheckRunner(
                this@PartyHostService, localId, playoutDelayMs,
                mediaNowUs = { lastSentTimestampUs },
                requestChirp = { id, mediaUs, checkId ->
                    when {
                        id == localId -> local.scheduleTone(mediaUs)
                        browsers.any { it.id == id } -> web.send(id, """{"t":"chirp","media":$mediaUs,"check":$checkId}""")
                        else -> sendToClient(id, WireParty.encodeChirp(streamId, id, mediaUs, checkId))
                    }
                },
                applyCorrection = { id, adjustUs, checkId, errorUs ->
                    if (browsers.any { it.id == id }) {
                        web.send(id, """{"t":"offset","adjust":$adjustUs,"check":$checkId,"err":$errorUs}""") // TCP: once is enough
                    } else {
                        repeat(3) { sendToClient(id, WireParty.encodeOffset(streamId, id, adjustUs, checkId, errorUs)) }
                    }
                },
                onProgress = { text, progress -> soundCheck = SoundCheckUi(running = true, progress = progress, message = text) },
            )
            checkRunner = runner
            soundCheck = SoundCheckUi(running = true, message = "Getting ready…")
            Thread({
                player.hold = true // the beeps need a quiet moment
                val pausedVideo = video?.playing == true
                if (pausedVideo) toggleVideo() // the video too: the microphone listens for the beeps
                val outcome = try {
                    runner.run(
                        guests.map { c -> CheckParticipant(c.id, c.name) { c.adjustUs } } +
                            browsers.map { g -> CheckParticipant(g.id, g.name) { g.adjustUs } },
                        CheckParticipant(localId, "DJ") { local.adjustUs() },
                        base,
                    )
                } finally {
                    player.hold = false
                    checkRunner = null
                    if (pausedVideo && video?.playing == false) toggleVideo()
                }
                for (c in guests) outcome.results[c.id]?.let { c.check = it }
                for (g in browsers) outcome.results[g.id]?.let { g.check = it }
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
        private const val PREFS = "hearby"
        private const val PREF_DELAY_MS = "auto.delayMs"
        private const val PREF_BITRATE = "auto.bitrate"
        /** 200 ms of silence: quiet enough to switch the cushion or quality. */
        private const val QUIET_FRAMES = 10
        /** The gap left between songs when a change is waiting: covers every phone's next SYNC_RESP. */
        private const val SONG_GAP_FRAMES = 75
        /** This phone's own copy: quality doesn't cost the network anything. */
        private const val LOCAL_BITRATE = 128_000
        /** Cushion that leaves the guests room for the karaoke voice (a few frames of waiting). */
        private const val KARAOKE_MIN_DELAY_MS = 350
        /** After the mic closes, it stays ready this long, so the next press doesn't jump. */
        private const val MIC_READY_NS = 30_000_000_000L
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

        /** Start the party. Sound quality and sync cushion tune themselves (see AutoTuner). */
        fun start(context: Context) {
            _state.update { it.copy(message = null) }
            ContextCompat.startForegroundService(context, Intent(context, PartyHostService::class.java).setAction(ACTION_START))
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

        /** The DJ's mic open (talking or singing to every phone) or closed; false if it could not be opened. */
        fun setMicOpen(open: Boolean): Boolean = instance?.session?.setMicOpen(open) ?: false

        /** The mic fully off (Hearby left the screen). */
        fun micOff() = instance?.session?.micOff()

        /** Unmute the DJ phone's own speaker (the party never stopped for everyone else). */
        fun unmute() = instance?.session?.unmute()

        /** Hearby is on screen again: take the sound back if another app had taken it. */
        fun reclaimSound() = instance?.session?.reclaimSound()

        fun videoTitle(videoId: String, title: String) = instance?.session?.videoTitle(videoId, title)

        fun videoEnded(videoId: String) = instance?.session?.videoEnded(videoId)


    }
}
