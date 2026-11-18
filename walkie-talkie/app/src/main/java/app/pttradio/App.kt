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
import app.pttradio.net.LanTransport
import app.pttradio.net.NearbyTransport
import app.pttradio.net.Sos
import app.pttradio.net.SosAlert
import app.pttradio.net.SosCodes
import app.pttradio.ui.MainActivity
import app.pttradio.ui.PttWidget
import app.pttradio.ui.QuickTalkActivity
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
    lateinit var lan: LanTransport
    lateinit var sos: Sos
    /** auto = internet, plus nearby whenever there is no internet or the app is on screen · net · nearby · both */
    val linkMode = MutableStateFlow("auto")
    private val bump = MutableStateFlow(0)
    private val netTier = MutableStateFlow(3)
    lateinit var audio: AudioEngine

    /** 3 = good internet, 2 = slow or weak. Based on Android's bandwidth estimate and the signal strength. */
    private fun tierOf(c: android.net.NetworkCapabilities): Int {
        if (!c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return 1
        val down = c.linkDownstreamBandwidthKbps
        val sig = if (android.os.Build.VERSION.SDK_INT >= 29) c.signalStrength else Int.MIN_VALUE
        val wifi = c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        val weak = sig != Int.MIN_VALUE && sig < (if (wifi) -78 else -110)
        return if ((down in 1..1499) || weak) 2 else 3
    }

    /** Hidden from nearby people: the Bluetooth / Wi-Fi link stays off whatever the link mode. */
    val visibleNearby = MutableStateFlow(true)
    fun setVisible(on: Boolean) { visibleNearby.value = on }

    fun setBoost(level: Int) { prefs.edit().putInt("boost", level).apply(); audio.boost = level }

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
        }, AudioEngine(this).also { audio = it }, opus = OpusCodec())
        val alarms = Alarms(this)
        mqtt = MqttTransport({ servers() }, { alarms }, { alarms.retryIn(it) })
        alarms.onRetry = { mqtt.retryNow() }
        nearby = NearbyTransport(this)
        core.onEvent = { Notifier.event(this, it) }
        core.start()
        core.attach(mqtt)
        core.attach(nearby)
        lan = LanTransport(this)
        core.attach(lan)
        sos = Sos(this, { core.state.value.me?.id ?: "" }, { id ->
            core.state.value.let { st -> if (st.users[id] != null || st.groups.any { g -> g.members.any { it.id == id } }) st.nameOf(id) else null }
        }, { id -> core.state.value.groups.any { g -> g.members.any { it.id == id } } }, { Notifier.sos(this, it) })
        // emergency alerts are listened for whenever the local radios run
        // SOS alerts are still heard while hidden from nearby people
        scope.launch { combine(nearby.info.map { it.running }.distinctUntilChanged(), visibleNearby) { r, v -> r || !v }.collect { sos.listen(it) } }
        scope.launch { core.state.map { it.netUp }.distinctUntilChanged().collect { nearby.internetUp = it } }
        scope.launch { onScreen.collect { nearby.foreground = it; sos.setForeground(it) } }
        // widgets show chat names: redraw them when groups are renamed, joined or removed
        scope.launch { core.state.map { st -> st.groups.map { st.titleOf(it) } }.distinctUntilChanged().collect { try { PttWidget.refreshAll(this@App) } catch (e: Exception) { } } }

        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mqtt.kick() }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) { netTier.value = tierOf(caps) }
            override fun onLost(network: Network) { netTier.value = 1 }
        })
        // voice quality follows the connection: HD on good internet, lower bit rates as it gets worse
        scope.launch { combine(netTier, core.state.map { it.netUp }.distinctUntilChanged()) { t, up -> if (up) t else 1 }.collect { core.setLinkTier(it) } }
        audio.boost = prefs.getInt("boost", 1)
        visibleNearby.value = prefs.getBoolean("visible", true)
        // background: lighter presence and announcements
        scope.launch { onScreen.collect { core.setForeground(it) } }
        // Rugged phones announce their dedicated PTT key with a broadcast instead of a key code.
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { if (i.action in PTT_DOWN) buttonDown() else buttonUp() }
        }, IntentFilter().apply { (PTT_DOWN + PTT_UP).forEach { addAction(it) } }, ContextCompat.RECEIVER_EXPORTED)

        scope.launch {
            combine(core.state.map { it.netUp to (it.me != null) }.distinctUntilChanged(), linkMode, combine(bump, visibleNearby) { b, v -> b to v }, onScreen) { a, m, _, vis -> listOf(a.first, a.second, m, vis) }
                .collectLatest { (netUpV, readyV, modeV, visV) ->
                    val netUp = netUpV as Boolean; val ready = readyV as Boolean; val mode = modeV as String; val vis = visV as Boolean
                    if (!ready) return@collectLatest
                    if (mode == "nearby") mqtt.stop() else mqtt.start()
                    if (!visibleNearby.value) { radios(false); return@collectLatest }
                    when {
                        mode == "nearby" || mode == "both" -> radios(true)
                        mode == "net" -> radios(false)
                        // auto: the radios run whenever there is no internet, and also while the app is open so
                        // people around you are visible; with internet and the app closed they rest to save battery
                        !netUp || vis -> { if (!vis) delay(3_000); radios(true) }
                        // never drop phones that are linked right now: they may have no other way to reach us
                        else -> { delay(20_000); if (nearby.info.value.linked == 0 && lan.peerCount == 0) radios(false) }
                    }
                }
        }
    }

    /** Bluetooth / Wi-Fi Direct and field Wi-Fi links are switched on and off together. */
    private fun radios(on: Boolean) { if (on) { nearby.start(); lan.start() } else { nearby.stop(); lan.stop() } }

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
    private const val SOS = "sos"
    private const val TALK = "talk"
    private const val CALLS = "calls"
    const val CALL_NOTE = 7001

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(STANDBY, "Radio standby", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT, "Invitations and channel calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
        })
        // heads-up but silent: the voice itself is already playing, and messages vibrate on their own
        nm.createNotificationChannel(NotificationChannel(TALK, "Someone talking or messaging you", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(SOS, "Emergency alerts from nearby phones", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 250, 600, 250, 600)
        })
    }

    private fun open(c: Context, req: Int, gid: String? = null, ch: Int = -1): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("gid", gid).putExtra("ch", ch), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Opens the talk key over whatever is on screen, already talking (tap within 5 s to keep going). */
    private fun talk(c: Context, req: Int, gid: String?): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, QuickTalkActivity::class.java).putExtra("gid", gid).putExtra("start", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun talkAction(c: Context, req: Int, gid: String?, label: String) =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_radio), label, talk(c, req, gid)).build()

    fun statusLine(s: RadioState): String {
        val link = if (s.netUp) "Internet" else if (s.nearbyUp) "Nearby" else "No link"
        val g = s.group ?: return "Standby · $link"
        val name = s.titleOf(g) + if (s.tunedCh != 0) " · ${chName(s.tunedCh)}" else ""
        return when {
            s.transmitting -> "Transmitting · $name"
            s.talker != null -> "${s.talker} is talking · $name"
            else -> "$name · $link"
        }
    }

    fun standby(c: Context, text: String, gid: String? = null): Notification = Notification.Builder(c, STANDBY)
        .setSmallIcon(R.drawable.ic_stat_radio).setContentTitle("ZeroBar").setContentText(text)
        .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(c, 0))
        .apply { if (gid != null) addAction(talkAction(c, 2, gid, "Talk")) }.build()

    /** Vibrates through the alarm channel, which the ringer's silent and vibrate modes do not mute. */
    fun sos(c: Context, a: SosAlert) {
        buzz(c, longArrayOf(0, 600, 250, 600, 250, 600))
        val where = if (a.lat != null) " · position included" else ""
        val via = if (a.hops > 0) " · passed on by ${a.hops} phone${if (a.hops > 1) "s" else ""}" else if (a.pct >= 0) " · signal ${a.pct}%" else ""
        try {
            c.getSystemService(NotificationManager::class.java).notify(a.from.hashCode() + 7, Notification.Builder(c, SOS)
                .setSmallIcon(R.drawable.ic_stat_radio).setContentTitle("${a.name}: ${SosCodes.of(a.code)}")
                .setContentText("Emergency message$where$via").setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true).setContentIntent(open(c, 3)).build())
        } catch (e: Exception) { }
    }

    fun buzz(c: Context, pattern: LongArray = longArrayOf(0, 140, 90, 140)) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
                    else c.getSystemService(Vibrator::class.java)
            if (v == null || !v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(pattern, -1)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
        } catch (x: Exception) { }
    }

    /** Content of locked chats (or of everything while the app lock is on) is not shown in notifications. */
    private fun hidden(c: Context, gid: String): Boolean {
        val p = (c.applicationContext as App).prefs
        return p.getBoolean("appLock", false) || gid in (p.getStringSet("lockedChats", emptySet()) ?: emptySet())
    }

    fun event(c: Context, e: Event) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val b = Notification.Builder(c, ALERT).setSmallIcon(R.drawable.ic_stat_radio).setAutoCancel(true)
        when (e) {
            is Event.InviteReceived -> nm.notify(e.invite.group.id.hashCode(), b.setContentTitle("Join ${e.invite.group.name}?")
                .setContentText("${e.invite.fromName} added you to a group. Tap to join.").setContentIntent(open(c, e.invite.group.id.hashCode())).build())
            is Event.Message -> {
                buzz(c)
                val st = (c.applicationContext as App).core.state.value
                if (e.gid.isNotEmpty() && (!MainActivity.visible || e.gid != st.tunedGroup)) {
                    val title = if (e.chat.isEmpty() || e.chat == e.from) e.from else "${e.from} · ${e.chat}"
                    nm.notify(e.gid.hashCode() + 13, Notification.Builder(c, TALK).setSmallIcon(R.drawable.ic_stat_radio).setAutoCancel(true)
                        .setContentTitle(title).setContentText(if (hidden(c, e.gid)) "New message" else e.text).setContentIntent(open(c, e.gid.hashCode() + 13, e.gid, 0))
                        .addAction(talkAction(c, e.gid.hashCode() + 14, e.gid, "Talk back")).build())
                }
            }
            is Event.IncomingCall -> {
                val answer = PendingIntent.getActivity(c, 7002, Intent(c, MainActivity::class.java).putExtra("answer", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val show = PendingIntent.getActivity(c, 7003, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val decline = PendingIntent.getBroadcast(c, 7004, Intent(c, CallActionReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val icon = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_radio)
                nm.notify(CALL_NOTE, Notification.Builder(c, CALLS).setSmallIcon(R.drawable.ic_stat_radio)
                    .setContentTitle("${e.from} is calling").setContentText("ZeroBar audio call").setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true).setTimeoutAfter(RadioCore.RING_MS).setContentIntent(show).setFullScreenIntent(show, true)
                    .addAction(Notification.Action.Builder(icon, "Decline", decline).build())
                    .addAction(Notification.Action.Builder(icon, "Answer", answer).build()).build())
            }
            is Event.CallOver -> nm.cancel(CALL_NOTE)
            is Event.Talk -> {
                val st = (c.applicationContext as App).core.state.value
                if (!MainActivity.visible || e.gid != st.tunedGroup)
                    nm.notify(e.gid.hashCode() + 11, Notification.Builder(c, TALK).setSmallIcon(R.drawable.ic_stat_radio).setAutoCancel(true)
                        .setContentTitle("${e.from} is talking").setContentText(if (e.chat == e.from) "Tap Talk back to answer" else e.chat)
                        .setCategory(Notification.CATEGORY_CALL).setTimeoutAfter(90_000)
                        .setContentIntent(open(c, e.gid.hashCode() + 11, e.gid, 0))
                        .addAction(talkAction(c, e.gid.hashCode() + 12, e.gid, "Talk back")).build())
            }
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
            app.core.state.map { Notifier.statusLine(it) to it.tunedGroup }.distinctUntilChanged().collectLatest { (text, gid) ->
                try { getSystemService(NotificationManager::class.java).notify(1, Notifier.standby(this@RadioService, text, gid)) } catch (e: Exception) { }
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

/** "Decline" on the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        (c.applicationContext as App).core.endCall()
        c.getSystemService(NotificationManager::class.java).cancel(Notifier.CALL_NOTE)
    }
}
