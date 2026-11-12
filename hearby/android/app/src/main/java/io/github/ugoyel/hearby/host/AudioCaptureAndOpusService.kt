package io.github.ugoyel.hearby.host

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import androidx.core.content.IntentCompat
import io.github.ugoyel.hearby.LocalNet
import io.github.ugoyel.hearby.MainActivity
import io.github.ugoyel.hearby.R
import io.github.ugoyel.hearby.codec.MediaCodecOpusEncoder
import io.github.ugoyel.hearby.core.Proto
import io.github.ugoyel.hearby.core.TimestampSmoother
import io.github.ugoyel.hearby.core.Wire
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
import kotlin.math.abs

data class ListenerInfo(
    val id: Long,
    val name: String,
    val platform: Int,
    val address: String,
    val rttMs: Float,
    val bufferMs: Int,
    val status: Int,
)

data class HostUiState(
    val running: Boolean = false,
    /** Why the last session ended, or why it could not start. */
    val message: String? = null,
    val port: Int = Proto.PORT,
    val addresses: List<String> = emptyList(),
    val listeners: List<ListenerInfo> = emptyList(),
    /** Peak level of the captured audio, 0..1. */
    val level: Float = 0f,
    /** How long the capture has been digital silence; long silences usually mean the app blocks capture. */
    val silentMs: Long = 0,
    val bitrate: Int = 96_000,
    val playoutDelayMs: Int = 350,
)

/**
 * HOST: captures whatever the phone is playing, encodes it to Opus and fans it out over UDP.
 *
 *   other apps' audio ──AudioPlaybackCapture──▶ AudioRecord (48 kHz stereo PCM16)
 *        └─ capture thread: read 20 ms ─▶ timestamp ─▶ MediaCodec Opus ─▶ [16-byte header + packet]
 *                                                                              └─▶ UDP unicast to every listener
 *   control thread: JOIN / SYNC_REQ (NTP + keep-alive) / LEAVE / DISCOVER on the same socket
 *
 * Runs as a foreground service of type mediaProjection, which Android requires for playback capture.
 * The MediaProjection consent result is passed in by the activity; consent has to be given again
 * for every session.
 */
class AudioCaptureAndOpusService : Service() {

    private var session: Session? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> finish(null)
            else -> if (session == null) stopSelf() // restarted without a projection token: nothing to do
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (session != null) return
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 96_000)
        val delayMs = intent.getIntExtra(EXTRA_DELAY_MS, 350)

        // 1. Become a mediaProjection foreground service. On Android 14+ this is only allowed once
        //    the user has accepted the capture dialog, which the activity did just before.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            finish("Android did not allow audio sharing to start (${e.javaClass.simpleName}).")
            return
        }

        // 2. Redeem the consent token.
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val projection = try {
            if (resultData == null) null
            else getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }
        if (projection == null) {
            finish("Android didn't give the go-ahead to share this phone's audio.")
            return
        }
        projection.registerCallback(projectionCallback, mainHandler)

        // 3. Capture, encode, serve.
        val s = Session(projection, bitrate, delayMs)
        try {
            s.start()
        } catch (e: Exception) {
            Log.e(TAG, "could not start session", e)
            s.stop()
            finish(e.message ?: "Could not start audio capture.")
            return
        }
        session = s
        _state.value = HostUiState(
            running = true, port = s.port, addresses = LocalNet.localIpv4Addresses(),
            bitrate = bitrate, playoutDelayMs = delayMs,
        )
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Revoked from the status bar chip, by another capture starting, or (Android 15 QPR1+)
            // by the device being locked. Ignore the echo of our own shutdown: by then the session
            // is already gone.
            if (session != null) {
                finish("Android stopped the party: the screen was locked, or sharing was ended from the status bar.")
            }
        }
    }

    /** Tear everything down. Safe to call repeatedly and from any thread's posted callback. */
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
        if (_state.value.running) _state.update { HostUiState(bitrate = it.bitrate, playoutDelayMs = it.playoutDelayMs) }
        super.onDestroy()
    }

    // ---- notification ---------------------------------------------------------------------------

    private fun buildNotification(listeners: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_host), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AudioCaptureAndOpusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (listeners == 0) getString(R.string.notif_host_text_none)
        else resources.getQuantityString(R.plurals.notif_host_text, listeners, listeners)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hearby)
            .setContentTitle(getString(R.string.notif_host_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    // ---- one hosting session --------------------------------------------------------------------

    private class Client(val address: InetSocketAddress, val id: Long, val name: String, val platform: Int) {
        @Volatile var lastSeenNs = System.nanoTime()
        @Volatile var rttUs = 0L
        @Volatile var bufferMs = 0
        @Volatile var status = Proto.STATUS_CONNECTING
        /** Reused for every audio datagram to this listener (capture thread only). */
        val audioPacket = DatagramPacket(ByteArray(0), 0, address)
    }

    private inner class Session(
        private val projection: MediaProjection,
        private val bitrate: Int,
        private val playoutDelayMs: Int,
    ) {
        private val streamId = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        private val hostName = LocalNet.deviceName(this@AudioCaptureAndOpusService)

        @Volatile private var running = false
        @Volatile private var clients: Array<Client> = emptyArray()
        private val clientsLock = Any()

        private var socket: DatagramSocket? = null
        private var record: AudioRecord? = null
        private var encoder: MediaCodecOpusEncoder? = null
        private var captureThread: Thread? = null
        private var controlThread: Thread? = null

        private var wakeLock: PowerManager.WakeLock? = null
        private var wifiLock: WifiManager.WifiLock? = null
        private var multicastLock: WifiManager.MulticastLock? = null
        private var nsdListener: NsdManager.RegistrationListener? = null

        @Volatile private var peakLevel = 0f
        @Volatile private var lastSoundNs = System.nanoTime()
        private var notifiedListeners = -1

        var port = Proto.PORT
            private set

        @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before the service starts
        fun start() {
            // -- network --
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
            LocalNet.bindHostSocketIfNeeded(this@AudioCaptureAndOpusService, s)
            socket = s
            port = s.localPort

            // -- capture: everything other apps play as media / game / unknown usage --
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBytes = AudioRecord.getMinBufferSize(
                Proto.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val r = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, FRAME_BYTES * 4))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            record = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "Playback capture is not available on this device." }

            encoder = try {
                MediaCodecOpusEncoder(bitrate)
            } catch (e: Exception) {
                throw IllegalStateException("This device has no Opus encoder.", e)
            }

            // -- stay awake, keep Wi-Fi out of power save --
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

            running = true
            captureThread = Thread(::captureLoop, "hearby-capture").also { it.start() }
            controlThread = Thread(::controlLoop, "hearby-control").also { it.start() }
            registerService()
        }

        fun stop() {
            val wasRunning = running
            running = false
            unregisterService()

            // Tell listeners we are done (three times; it is UDP).
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

            try {
                record?.stop() // unblocks the capture thread
            } catch (e: Exception) {
                // not recording
            }
            s?.close() // unblocks the control thread
            captureThread?.join(1500)
            controlThread?.join(1500)
            record?.release()
            encoder?.release()
            record = null
            encoder = null
            socket = null

            try {
                projection.unregisterCallback(projectionCallback)
                projection.stop()
            } catch (e: Exception) {
                // already stopped
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
            multicastLock?.let { if (it.isHeld) it.release() }
        }

        // ---- capture thread: PCM -> Opus -> UDP ---------------------------------------------------

        private fun captureLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rec = record ?: return
            val enc = encoder ?: return
            val sock = socket ?: return
            val pcm = ByteArray(FRAME_BYTES)
            val datagram = ByteArray(Proto.AUDIO_HEADER_SIZE + Proto.MAX_PAYLOAD)
            val smoother = TimestampSmoother()
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
                }
            }

            try {
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    fail("Could not start capturing audio. Is another app already recording?")
                    return
                }
                while (running) {
                    var filled = 0
                    while (filled < FRAME_BYTES) {
                        val n = rec.read(pcm, filled, FRAME_BYTES - filled)
                        if (n <= 0) {
                            if (running) fail("Audio capture stopped unexpectedly (error $n).")
                            return
                        }
                        filled += n
                    }
                    // The block ended "now", so its first sample was captured one frame ago.
                    val timestampUs = smoother.next(System.nanoTime() / 1000 - Proto.FRAME_US)
                    meter(pcm)
                    enc.encode(pcm, timestampUs, send)
                    if (enc.unsupportedFraming) {
                        fail("This device's Opus encoder uses an unsupported frame size.")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "capture loop died", e)
                if (running) fail("Audio capture failed: ${e.javaClass.simpleName}")
            }
        }

        private fun meter(pcm: ByteArray) {
            var peak = 0
            var i = 0
            while (i < FRAME_BYTES) { // every 8th sample is plenty for a level meter
                val v = abs((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF))
                if (v > peak) peak = v
                i += 16
            }
            val level = peak / 32768f
            if (level > peakLevel) peakLevel = level
            if (peak > 8) lastSoundNs = System.nanoTime()
        }

        private fun fail(message: String) {
            mainHandler.post { if (session === this) finish(message) }
        }

        // ---- control thread: join / clock sync / discovery ---------------------------------------

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
                    housekeeping(now)
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
                }
                Proto.TYPE_SYNC_REQ -> {
                    val m = Wire.decodeSyncReq(b, len) ?: return
                    val c = clients.firstOrNull { it.address == from } ?: return // must JOIN first
                    c.lastSeenNs = System.nanoTime()
                    c.rttUs = m.rttUs
                    c.bufferMs = m.bufferMs
                    c.status = m.status
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

        private fun housekeeping(nowNs: Long) {
            synchronized(clientsLock) {
                if (clients.any { nowNs - it.lastSeenNs > CLIENT_TIMEOUT_NS }) {
                    clients = clients.filter { nowNs - it.lastSeenNs <= CLIENT_TIMEOUT_NS }.toTypedArray()
                }
            }
            val snapshot = clients
            val listeners = snapshot.map {
                ListenerInfo(
                    it.id, it.name, it.platform, it.address.address?.hostAddress ?: "?",
                    it.rttUs / 1000f, it.bufferMs, it.status,
                )
            }
            val level = peakLevel
            peakLevel = 0f
            val silentMs = (nowNs - lastSoundNs) / 1_000_000
            _state.update {
                if (!it.running) it
                else it.copy(
                    listeners = listeners, level = level, silentMs = silentMs,
                    addresses = if (it.addresses.isEmpty()) LocalNet.localIpv4Addresses() else it.addresses,
                )
            }
            if (snapshot.size != notifiedListeners) {
                notifiedListeners = snapshot.size
                try {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(snapshot.size))
                } catch (e: Exception) {
                    // notifications disabled
                }
            }
        }

        // ---- mDNS / DNS-SD advertisement ------------------------------------------------------------

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
    }

    companion object {
        private const val TAG = "HearbyHost"
        private const val ACTION_START = "io.github.ugoyel.hearby.host.START"
        private const val ACTION_STOP = "io.github.ugoyel.hearby.host.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_BITRATE = "bitrate"
        private const val EXTRA_DELAY_MS = "delayMs"
        private const val CHANNEL_ID = "host"
        private const val NOTIFICATION_ID = 41

        private const val FRAME_BYTES = MediaCodecOpusEncoder.PCM_BLOCK_BYTES
        private const val MAX_LISTENERS = 32
        private const val CLIENT_TIMEOUT_NS = 6_000_000_000L
        private const val MAX_SESSION_MS = 12 * 60 * 60 * 1000L

        private val _state = MutableStateFlow(HostUiState())
        val state: StateFlow<HostUiState> = _state.asStateFlow()

        /**
         * Start hosting. [resultCode] / [resultData] are what the MediaProjection consent activity
         * returned (see MainScreen).
         */
        fun start(context: Context, resultCode: Int, resultData: Intent, bitrate: Int, playoutDelayMs: Int) {
            _state.update { it.copy(message = null) }
            val intent = Intent(context, AudioCaptureAndOpusService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_BITRATE, bitrate)
                .putExtra(EXTRA_DELAY_MS, playoutDelayMs)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            // Go through the service so it can release the projection itself, in order. Simply
            // calling stopService() makes Android revoke the projection first (the foreground
            // service is gone), which would look like the system ended the capture.
            val intent = Intent(context, AudioCaptureAndOpusService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                context.stopService(intent)
            }
        }

        fun clearMessage() = _state.update { it.copy(message = null) }
    }
}
