package app.pttradio

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import app.pttradio.net.SosCodes
import app.pttradio.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Signals that reach further than any phone radio: the flashlight blinking SOS in Morse (··· ––– ···), visible
 * for kilometres at night, and a loud siren on the alarm volume, which silent mode does not mute.
 */
class SosSignal(private val ctx: Context) {
    val on = MutableStateFlow(false)
    /** Current state of the light, so the screen can flash along with the torch. */
    val lit = MutableStateFlow(false)
    private var worker: Thread? = null
    private var siren: AudioTrack? = null
    private val cams = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? by lazy {
        try { cams?.cameraIdList?.firstOrNull { cams.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
        catch (e: Exception) { null }
    }

    fun start(sound: Boolean) {
        if (on.value) return
        on.value = true
        worker = thread(isDaemon = true, name = "sos-light") { blink() }
        if (sound) startSiren()
    }

    fun stop() {
        on.value = false
        worker?.interrupt(); worker = null
        try { siren?.stop(); siren?.release() } catch (e: Exception) { }
        siren = null
        light(false)
    }

    private fun light(v: Boolean) {
        lit.value = v
        try { torchId?.let { cams?.setTorchMode(it, v) } } catch (e: Exception) { }
    }

    private fun blink() {
        val unit = 220L                                // Morse timing: dot 1, dash 3, gap 1, letter gap 3, word gap 7
        val marks = intArrayOf(1, 1, 1, 3, 3, 3, 1, 1, 1)
        try {
            while (on.value) {
                for ((i, len) in marks.withIndex()) {
                    light(true); Thread.sleep(len * unit)
                    light(false); Thread.sleep(if (i == 2 || i == 5) 3 * unit else unit)
                }
                Thread.sleep(7 * unit)
            }
        } catch (e: InterruptedException) { }
        light(false)
    }

    /** A rising and falling 700–1600 Hz tone, played on a loop at full alarm volume. */
    private fun startSiren() {
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am?.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val rate = 22050
            val n = rate * 2
            var phase = 0.0
            val pcm = ShortArray(n) { i ->
                val t = i.toDouble() / n
                val f = 700 + 900 * (if (t < 0.5) t * 2 else (1 - t) * 2)
                phase += 2 * PI * f / rate
                (sin(phase) * 30000).toInt().toShort()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, n)
            t.setLoopPoints(0, n, -1)
            t.play()
            siren = t
        } catch (e: Exception) { }
    }
}

/**
 * Check-in timer: "if I have not said I'm OK by this time, tell my groups and send an SOS with my last position".
 * A reminder comes 15 minutes before. Android may deliver the alarm a few minutes late to save battery.
 */
object CheckIn {
    private const val REMIND = "app.pttradio.CHECKIN_REMIND"
    private const val DUE = "app.pttradio.CHECKIN_DUE"
    private const val OK = "app.pttradio.CHECKIN_OK"
    const val NOTE = 7101

    fun due(c: Context): Long = (c.applicationContext as App).prefs.getLong("checkinAt", 0)

    private fun pi(c: Context, action: String) = PendingIntent.getBroadcast(c, action.hashCode(), Intent(c, CheckInReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun set(c: Context, at: Long) {
        val app = c.applicationContext as App
        app.prefs.edit().putLong("checkinAt", at).putBoolean("checkinMissed", false).apply()
        app.checkinAt.value = at
        val am = c.getSystemService(AlarmManager::class.java)
        try {
            if (at - 15 * 60_000 > System.currentTimeMillis()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at - 15 * 60_000, pi(c, REMIND))
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, DUE))
        } catch (e: Exception) { }
    }

    /** "I'm OK": cancels the timer; if the alert already went out, tells the groups that all is well. */
    fun ok(c: Context) {
        val app = c.applicationContext as App
        val missed = app.prefs.getBoolean("checkinMissed", false)
        app.prefs.edit().putLong("checkinAt", 0).putBoolean("checkinMissed", false).apply()
        app.checkinAt.value = 0
        val am = c.getSystemService(AlarmManager::class.java)
        try { am.cancel(pi(c, REMIND)); am.cancel(pi(c, DUE)) } catch (e: Exception) { }
        c.getSystemService(NotificationManager::class.java).cancel(NOTE)
        if (missed) { app.core.alertGroups("✅ ${app.core.state.value.me?.name ?: "I"} checked in: I'm OK.", null); app.sos.stop() }
    }

    fun handle(c: Context, action: String?) {
        val app = c.applicationContext as App
        val at = due(c)
        if (at == 0L) return
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
        val okIntent = pi(c, OK)
        val open = PendingIntent.getActivity(c, 7102, Intent(c, MainActivity::class.java).putExtra("safety", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val icon = android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_radio)
        val nm = c.getSystemService(NotificationManager::class.java)
        when (action) {
            OK -> ok(c)
            REMIND -> nm.notify(NOTE, Notification.Builder(c, "alert").setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle("Check in by $time").setContentText("Tap I'm OK, or your groups get an alert with your position.")
                .setContentIntent(open).addAction(Notification.Action.Builder(icon, "I'm OK", okIntent).build()).build())
            DUE -> {
                if (System.currentTimeMillis() < at - 60_000 || app.prefs.getBoolean("checkinMissed", false)) return
                app.prefs.edit().putBoolean("checkinMissed", true).apply()
                val name = app.core.state.value.me?.name ?: "Someone"
                val text = "⚠️ $name did not check in (due $time). Last known position follows. Try to reach them."
                val fix = app.lastFix
                app.core.alertGroups(text, fix?.let { "geo:" + String.format(java.util.Locale.US, "%.6f,%.6f,%d", it.latitude, it.longitude, it.accuracy.toInt()) })
                fix?.let { app.sos.setFix(it.latitude, it.longitude) }
                app.sos.send(SosCodes.MISSED_CHECKIN, app.prefs.getBoolean("sosAnyone", false))
                Notifier.buzz(c, longArrayOf(0, 600, 250, 600, 250, 600))
                nm.notify(NOTE, Notification.Builder(c, "alert").setSmallIcon(R.drawable.ic_stat_radio)
                    .setContentTitle("Check-in missed: alert sent").setContentText("Your groups were told. Tap I'm OK if you are fine.")
                    .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(icon, "I'm OK", okIntent).build()).build())
            }
        }
    }
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { CheckIn.handle(c, i.action) }
}

/** "I'm OK" on a roll-call notification. */
class RollReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val gid = i.getStringExtra("gid") ?: return
        (c.applicationContext as App).core.rollOk(gid)
        c.getSystemService(NotificationManager::class.java).cancel(gid.hashCode() + 21)
    }
}

/**
 * Follows this phone's GPS while position sharing or the trail is on. GPS needs no internet or SIM. Once a minute
 * (every 5 minutes with Data saver), or sooner after moving 50 m, the position goes to the groups.
 */
class Tracker(private val app: App) : android.location.LocationListener {
    val share = MutableStateFlow(app.prefs.getBoolean("sharePos", false))
    val trail = MutableStateFlow(app.prefs.getBoolean("trail", false))
    private var running = false
    private var sentAt = 0L
    private var sentFix: android.location.Location? = null
    val active get() = share.value || trail.value
    private fun every() = if (app.saver.value) 300_000L else 60_000L

    fun setShare(on: Boolean) { app.prefs.edit().putBoolean("sharePos", on).apply(); share.value = on; apply() }
    fun setTrail(on: Boolean) { app.prefs.edit().putBoolean("trail", on).apply(); trail.value = on; apply() }

    fun allowed() = app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")
    fun apply() {
        val lm = app.getSystemService(android.location.LocationManager::class.java) ?: return
        if (running) { try { lm.removeUpdates(this) } catch (e: Exception) { }; running = false }
        if (!active || !allowed()) return
        val providers = lm.getProviders(true).filter { it == android.location.LocationManager.GPS_PROVIDER || it == android.location.LocationManager.NETWORK_PROVIDER }
        for (pr in providers) try { lm.requestLocationUpdates(pr, every() / 2, 10f, this, android.os.Looper.getMainLooper()); running = true } catch (e: Exception) { }
        sentAt = 0
    }

    override fun onLocationChanged(l: android.location.Location) {
        // a network position is only used while there is no recent GPS one
        val last = app.lastFix
        if (l.provider != android.location.LocationManager.GPS_PROVIDER && last?.provider == android.location.LocationManager.GPS_PROVIDER &&
            l.time - last.time < 120_000) return
        app.lastFix = l
        val alt = when {
            android.os.Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters
            l.hasAltitude() -> l.altitude
            else -> Double.NaN
        }
        val now = System.currentTimeMillis()
        val moved = sentFix?.let { it.distanceTo(l) >= 50 } ?: true
        val send = share.value && (now - sentAt >= every() - 5_000 || moved)
        if (send) { sentAt = now; sentFix = l }
        app.core.myPosition(l.latitude, l.longitude, alt, if (l.hasAccuracy()) l.accuracy.toInt() else 0, send, trail.value)
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
