package io.github.ugoyel.hearby.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ugoyel.hearby.MainActivity
import io.github.ugoyel.hearby.R
import io.github.ugoyel.hearby.core.Proto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * Keeps an [AndroidSyncReceiver] alive as a mediaPlayback foreground service so listening continues
 * with the screen off or the app in the background.
 */
class ReceiverService : Service() {

    private var receiver: AndroidSyncReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var shownHost = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val port = intent.getIntExtra(EXTRA_PORT, Proto.PORT)
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                if (address == null) stopSelf() else join(address, port, name)
            }
            ACTION_LEAVE -> leave(keepState = false)
            else -> if (receiver == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun join(address: String, port: Int, name: String) {
        receiver?.stop()
        receiver = null
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(name.ifBlank { address }),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            _state.value = ReceiverUiState(phase = Phase.ENDED, message = "Android did not allow playback to start.")
            stopSelf()
            return
        }
        acquireLocks()
        _state.value = ReceiverUiState(phase = Phase.CONNECTING, hostName = name, hostAddress = address)
        instance = this

        // Literal IP addresses only (they come from discovery or the manual field), so this never hits DNS.
        val host = try {
            InetAddress.getByName(address)
        } catch (e: Exception) {
            _state.value = ReceiverUiState(phase = Phase.ENDED, message = "\"$address\" is not a valid address.")
            leave(keepState = true)
            return
        }
        val r = AndroidSyncReceiver(this, host, port, name) { state -> onReceiverState(state) }
        receiver = r
        r.start()
    }

    private fun onReceiverState(state: ReceiverUiState) {
        _state.value = state
        if (state.phase == Phase.ENDED) {
            // Called on a receiver thread; tear down from the main thread, keep the reason on screen.
            mainHandler.post { leave(keepState = true) }
        } else if (state.hostName != shownHost && state.hostName.isNotBlank()) {
            shownHost = state.hostName
            mainHandler.post {
                if (receiver != null) {
                    try {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state.hostName))
                    } catch (e: Exception) {
                        // notifications disabled
                    }
                }
            }
        }
    }

    private fun leave(keepState: Boolean) {
        val r = receiver
        receiver = null
        r?.stop()
        releaseLocks()
        if (instance === this) instance = null
        if (!keepState) _state.value = ReceiverUiState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        receiver?.stop()
        receiver = null
        releaseLocks()
        if (instance === this) instance = null
        if (_state.value.phase != Phase.ENDED) _state.value = ReceiverUiState()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hearby:listen").apply {
                    setReferenceCounted(false)
                    acquire(MAX_SESSION_MS)
                }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "hearby:listen")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun buildNotification(hostName: String): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_listen), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leave = PendingIntent.getService(
            this, 2, Intent(this, ReceiverService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hearby)
            .setContentTitle(getString(R.string.notif_listen_title, hostName))
            .setContentText(getString(R.string.notif_listen_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_leave), leave)
            .build()
    }

    companion object {
        private const val TAG = "HearbyListen"
        private const val ACTION_START = "io.github.ugoyel.hearby.client.START"
        private const val ACTION_LEAVE = "io.github.ugoyel.hearby.client.LEAVE"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "listen"
        private const val NOTIFICATION_ID = 42
        private const val MAX_SESSION_MS = 12 * 60 * 60 * 1000L

        private val _state = MutableStateFlow(ReceiverUiState())
        val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

        @Volatile
        private var instance: ReceiverService? = null

        fun join(context: Context, address: String, port: Int, hostName: String) {
            _state.value = ReceiverUiState(phase = Phase.CONNECTING, hostName = hostName, hostAddress = address)
            val intent = Intent(context, ReceiverService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_NAME, hostName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun leave(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
            _state.value = ReceiverUiState()
        }

        /** Dismiss the "session ended" notice. */
        fun dismiss() {
            if (_state.value.phase == Phase.ENDED) _state.value = ReceiverUiState()
        }

        fun setOffsetMs(ms: Int) {
            instance?.receiver?.setUserOffsetMs(ms)
        }

        fun resync() {
            instance?.receiver?.resync()
        }

        fun setMuted(muted: Boolean) {
            instance?.receiver?.setMuted(muted)
        }
    }
}
