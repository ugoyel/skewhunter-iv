package app.pttradio

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import app.pttradio.audio.AudioEngine
import app.pttradio.core.Event
import app.pttradio.core.KV
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.chName
import app.pttradio.net.MqttTransport
import app.pttradio.net.NearbyTransport
import app.pttradio.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

const val DEFAULT_SERVER = "ssl://broker.emqx.io:8883"
const val BACKUP_SERVER = "ssl://broker.hivemq.com:8883"

class App : Application() {
    lateinit var prefs: SharedPreferences
    lateinit var core: RadioCore
    lateinit var mqtt: MqttTransport
    lateinit var nearby: NearbyTransport
    /** auto = internet first, nearby when there is none · net · nearby · both */
    val linkMode = MutableStateFlow("auto")
    private val bump = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("radio", MODE_PRIVATE)
        linkMode.value = prefs.getString("mode", "auto") ?: "auto"
        Notifier.channels(this)
        core = RadioCore(object : KV {
            override fun get(k: String) = prefs.getString(k, null)
            override fun put(k: String, v: String?) { prefs.edit().putString(k, v).apply() }
        }, AudioEngine(this))
        mqtt = MqttTransport { servers() }
        nearby = NearbyTransport(this)
        core.onEvent = { Notifier.event(this, it) }
        core.start()
        core.attach(mqtt)
        core.attach(nearby)

        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mqtt.kick() }
        })
        scope.launch {
            combine(core.state.map { it.netUp to (it.me != null) }.distinctUntilChanged(), linkMode, bump) { a, m, _ -> Triple(a.first, a.second, m) }
                .collectLatest { (netUp, ready, mode) ->
                    if (!ready) return@collectLatest
                    if (mode == "nearby") mqtt.stop() else mqtt.start()
                    when {
                        mode == "nearby" || mode == "both" -> nearby.start()
                        mode == "net" -> nearby.stop()
                        // auto: radios (the battery-hungry part) are only switched on while there is no internet
                        netUp -> { delay(20_000); nearby.stop() }
                        else -> { delay(5_000); nearby.start() }
                    }
                }
        }
    }

    fun servers(): List<String> {
        val s = normalize(prefs.getString("server", null))
        return if (s == BACKUP_SERVER || s != DEFAULT_SERVER) listOf(s) else listOf(s, BACKUP_SERVER)
    }

    fun setServer(v: String) { prefs.edit().putString("server", v.trim().ifEmpty { null }).apply(); mqtt.stop(); if (linkMode.value != "nearby") mqtt.start() }
    fun setMode(m: String) { prefs.edit().putString("mode", m).apply(); linkMode.value = m }
    /** Re-evaluate links, e.g. after the nearby permissions were granted. */
    fun refreshLinks() { bump.value++ }

    companion object {
        fun normalize(v: String?): String {
            val s = v?.trim().orEmpty()
            return when { s.isEmpty() -> DEFAULT_SERVER; s.contains("://") -> s; s.contains(":") -> "ssl://$s"; else -> "ssl://$s:8883" }
        }
    }
}

object Notifier {
    private const val STANDBY = "standby"
    private const val ALERT = "alert"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STANDBY, "Radio standby", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT, "Invitations and channel calls", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun open(c: Context, req: Int, gid: String? = null, ch: Int = -1): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("gid", gid).putExtra("ch", ch), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun statusLine(s: RadioState): String {
        val link = if (s.netUp) "Internet" else if (s.nearbyUp) "Nearby" else "No link"
        val g = s.group ?: return "Standby · $link"
        return when {
            s.transmitting -> "Transmitting · ${g.name} ${chName(s.tunedCh)}"
            s.talker != null -> "Receiving ${s.talker} · ${g.name} ${chName(s.tunedCh)}"
            else -> "${g.name} ${chName(s.tunedCh)} · $link"
        }
    }

    fun standby(c: Context, text: String): Notification = Notification.Builder(c, STANDBY)
        .setSmallIcon(R.drawable.ic_stat_radio).setContentTitle("PTT Radio").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(c, 0)).build()

    fun event(c: Context, e: Event) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(c, ALERT).setSmallIcon(R.drawable.ic_stat_radio).setAutoCancel(true)
        when (e) {
            is Event.InviteReceived -> nm.notify(e.invite.group.id.hashCode(), b.setContentTitle("Join ${e.invite.group.name}?")
                .setContentText("${e.invite.fromName} added you to a group. Tap to join.").setContentIntent(open(c, e.invite.group.id.hashCode())).build())
            is Event.Summoned -> if (!e.autoTuned || !MainActivity.visible) nm.notify(e.gid.hashCode() + 1, b.setContentTitle("${e.by} wants you on ${chName(e.ch)}")
                .setContentText("${e.groupName} · tap to switch channel").setContentIntent(open(c, e.gid.hashCode() + 1, e.gid, e.ch)).build())
        }
    }
}

/** Keeps the radio connected and able to play incoming voice while the app is closed. */
class RadioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        try {
            val n = Notifier.standby(this, Notifier.statusLine(app.core.state.value))
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        } catch (e: Exception) { stopSelf(); return }
        scope.launch {
            app.core.state.map { Notifier.statusLine(it) }.distinctUntilChanged().collectLatest {
                try { getSystemService(NotificationManager::class.java).notify(1, Notifier.standby(this@RadioService, it)) } catch (e: Exception) { }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(c: Context) {
            if ((c.applicationContext as App).core.state.value.me == null && (c.applicationContext as App).prefs.getString("me", null) == null) return
            try { c.startForegroundService(Intent(c, RadioService::class.java)) } catch (e: Exception) { }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { RadioService.start(c) }
}
