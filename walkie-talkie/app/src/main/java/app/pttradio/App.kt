package app.pttradio

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import app.pttradio.audio.AudioEngine
import app.pttradio.audio.OpusCodec
import app.pttradio.core.Event
import app.pttradio.core.KV
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.chName
import app.pttradio.net.MqttTransport
import app.pttradio.net.NearbyTransport
import app.pttradio.ui.MainActivity
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms
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
    /** auto = internet, plus nearby whenever there is no internet or the app is on screen · net · nearby · both */
    val linkMode = MutableStateFlow("auto")
    private val bump = MutableStateFlow(0)
    /** True while the app is on screen. */
    val onScreen = MutableStateFlow(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("radio", MODE_PRIVATE)
        linkMode.value = prefs.getString("mode", "auto") ?: "auto"
        Notifier.channels(this)
        core = RadioCore(object : KV {
            override fun get(k: String) = prefs.getString(k, null)
            override fun put(k: String, v: String?) { prefs.edit().putString(k, v).apply() }
        }, AudioEngine(this), opus = OpusCodec())
        val alarms = Alarms(this)
        mqtt = MqttTransport({ servers() }, { alarms }, { alarms.retryIn(it) })
        alarms.onRetry = { mqtt.retryNow() }
        nearby = NearbyTransport(this)
        core.onEvent = { Notifier.event(this, it) }
        core.start()
        core.attach(mqtt)
        core.attach(nearby)

        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mqtt.kick() }
        })
        // Rugged phones announce their dedicated PTT key with a broadcast instead of a key code.
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (i.action in PTT_DOWN) buttonDown() else buttonUp() }
        }, IntentFilter().apply { (PTT_DOWN + PTT_UP).forEach { addAction(it) } }, ContextCompat.RECEIVER_EXPORTED)

        scope.launch {
            combine(core.state.map { it.netUp to (it.me != null) }.distinctUntilChanged(), linkMode, bump, onScreen) { a, m, _, vis -> listOf(a.first, a.second, m, vis) }
                .collectLatest { (netUpV, readyV, modeV, visV) ->
                    val netUp = netUpV as Boolean; val ready = readyV as Boolean; val mode = modeV as String; val vis = visV as Boolean
                    if (!ready) return@collectLatest
                    if (mode == "nearby") mqtt.stop() else mqtt.start()
                    when {
                        mode == "nearby" || mode == "both" -> nearby.start()
                        mode == "net" -> nearby.stop()
                        // auto: radios (the battery-hungry part) are only switched on while there is no internet
                        // auto: the radios run whenever there is no internet, and also while the app is open so
                        // people around you are visible; with internet and the app closed they rest to save battery
                        !netUp || vis -> { if (!vis) delay(3_000); nearby.start() }
                        // never drop phones that are linked right now: they may have no other way to reach us
                        else -> { delay(20_000); if (nearby.info.value.linked == 0) nearby.stop() }
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

    // ---- hardware talk button ------------------------------------------------------------------
    @Volatile private var held = false
    /** Key code chosen as talk button, 0 = none. */
    fun pttKey(): Int = prefs.getInt("pttKey", 0)
    fun setPttKey(code: Int) { prefs.edit().putInt("pttKey", code).apply() }
    /** Group the button talks on; empty = whatever channel the radio is tuned to. */
    fun pttGroup(): String = prefs.getString("pttGroup", "") ?: ""
    fun setPttGroup(gid: String) { prefs.edit().putString("pttGroup", gid).apply() }

    fun buttonDown() {
        if (held) return // key auto-repeat
        held = true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        core.pttDown(pttGroup().ifEmpty { null })
        try { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) { }
    }

    fun buttonUp() { held = false; core.pttUp() }

    companion object {
        val PTT_DOWN = setOf("app.pttradio.PTT_DOWN", "android.intent.action.PTT.down", "com.sonim.intent.action.PTT_KEY_DOWN")
        val PTT_UP = setOf("app.pttradio.PTT_UP", "android.intent.action.PTT.up", "com.sonim.intent.action.PTT_KEY_UP")
        fun normalize(v: String?): String {
            val s = v?.trim().orEmpty()
            return when { s.isEmpty() -> DEFAULT_SERVER; s.contains("://") -> s; s.contains(":") -> "ssl://$s"; else -> "ssl://$s:8883" }
        }
    }
}

/**
 * Wakes the phone just long enough to send the relay keep-alive or retry a lost connection.
 * Java timers stop while the CPU sleeps; alarms do not, and they cost far less than holding a wake lock.
 */
class Alarms(private val ctx: Context) : MqttPingSender {
    private val ping = "app.pttradio.PING"
    private val retry = "app.pttradio.RETRY"
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val wl = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pttradio:link").apply { setReferenceCounted(false) }
    @Volatile private var comms: ClientComms? = null
    @Volatile var onRetry: (() -> Unit)? = null

    init {
        ContextCompat.registerReceiver(ctx, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try { wl.acquire(8_000) } catch (e: Exception) { }
                try { if (i.action == ping) comms?.checkForActivity() else onRetry?.invoke() } catch (e: Exception) { }
            }
        }, IntentFilter().apply { addAction(ping); addAction(retry) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun pi(action: String) = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun set(action: String, ms: Long) {
        try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, pi(action)) } catch (e: Exception) { }
    }

    override fun init(c: ClientComms) { comms = c }
    override fun start() { comms?.let { schedule(it.keepAlive) } }
    override fun stop() { try { am.cancel(pi(ping)) } catch (e: Exception) { } }
    // Inexact alarms may fire late, so aim early; an early check simply reschedules itself.
    override fun schedule(delayMs: Long) = set(ping, delayMs * 55 / 100)
    fun retryIn(ms: Long) = set(retry, ms)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sent while the app is on screen: Android only grants background microphone use (needed for the
        // hardware talk button) to a service that was promoted while the app was visible.
        if (intent?.action == ACTION_MIC && Build.VERSION.SDK_INT >= 30) try {
            startForeground(1, Notifier.standby(this, Notifier.statusLine((application as App).core.state.value)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) { }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_MIC = "mic"
        fun start(c: Context, mic: Boolean = false) {
            if ((c.applicationContext as App).core.state.value.me == null && (c.applicationContext as App).prefs.getString("me", null) == null) return
            try { c.startForegroundService(Intent(c, RadioService::class.java).setAction(if (mic) ACTION_MIC else null)) } catch (e: Exception) { }
        }
    }
}
