package app.pttradio.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.App
import app.pttradio.RadioService
import app.pttradio.core.RadioState
import app.pttradio.net.NearbyInfo
import app.pttradio.net.NearbyTransport
import kotlinx.coroutines.delay

@Composable
fun AppRoot(s: RadioState, p: Perms, near: NearbyInfo, mode: String, server: String, btn: ButtonCfg, a: Actions, scr: Scr, tab: Int, onTab: (Int) -> Unit, go: (Scr) -> Unit) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        when {
            !s.ready -> {}
            s.me == null -> Welcome(a)
            scr == Scr.SETUP -> Setup(p, a) { go(Scr.HOME) }
            scr == Scr.SETTINGS -> SettingsScreen(s, p, mode, server, btn, a) { go(Scr.HOME) }
            scr == Scr.RADIO && s.group != null -> RadioScreen(s, a, now) { go(Scr.HOME) }
            else -> Home(s, p, near, a, now, tab, onTab, go)
        }
    }
}

class MainActivity : ComponentActivity(), Actions {
    companion object { @Volatile var visible = false }

    private val app get() = application as App
    private val core get() = app.core
    private var perms by mutableStateOf(Perms())
    private var warning by mutableStateOf<String?>(null)
    private var scr by mutableStateOf(Scr.HOME)
    private var tab by mutableIntStateOf(0)
    private var serverUrl by mutableStateOf("")
    private var askedBattery = false
    private var btn by mutableStateOf(ButtonCfg())

    private fun refreshButton(learning: Boolean = false) {
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains("$packageName/") == true
        btn = ButtonCfg(app.pttKey(), learning, app.pttGroup(), on)
    }

    /** The talk button while this app is on screen (other apps: [app.pttradio.PttKeyService]). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (btn.learning) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP) refreshButton(); return true }
            if (e.action == KeyEvent.ACTION_DOWN) { app.setPttKey(e.keyCode); refreshButton() }
            return true
        }
        if (btn.key != 0 && e.keyCode == btn.key) {
            if (e.action == KeyEvent.ACTION_DOWN) app.buttonDown() else if (e.action == KeyEvent.ACTION_UP) app.buttonUp()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun setPttKey(code: Int) { app.setPttKey(code); refreshButton() }
    override fun learnKey() { refreshButton(learning = true) }
    override fun setPttGroup(gid: String) { app.setPttGroup(gid); refreshButton() }
    override fun fixNearby(problem: String) {
        try {
            when (problem) {
                "perm" -> requestPerm("nearby")
                "bt" -> startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                "loc" -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                else -> { app.nearby.stop(); app.refreshLinks() }
            }
        } catch (e: Exception) { }
    }
    override fun openKeyService() { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) { } }

    private val micReq = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refreshPerms(); if (ok) RadioService.start(this, mic = true) else warning = "Microphone denied. You can listen, but nobody will hear you. The app won't work properly."
    }
    private val notifReq = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        refreshPerms(); if (!ok) warning = "Notifications denied. You won't be told about group invitations or channel calls. The app won't work properly."
    }
    private val nearbyReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        refreshPerms()
        if (r.values.any { !it }) warning = "Nearby devices denied. Without internet the radio can't reach phones around you. The app won't work properly."
        else app.refreshLinks()
        app.nearby.publish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverUrl = app.prefs.getString("server", "") ?: ""
        if (core.state.value.me != null || app.prefs.getString("me", null) != null) {
            if (!app.prefs.getBoolean("setupDone", false)) scr = Scr.SETUP
        }
        handle(intent)
        setContent {
            val s by core.state.collectAsState()
            val mode by app.linkMode.collectAsState()
            val near by app.nearby.info.collectAsState()
            LaunchedEffect(s.me?.id) { if (s.me != null) RadioService.start(this@MainActivity) }
            BackHandler(enabled = s.me != null && scr != Scr.HOME) { scr = Scr.HOME }
            AppRoot(s, perms, near, mode, serverUrl, btn, this, scr, tab, { tab = it }) { scr = it }
            warning?.let { w ->
                Sheet("Warning", { warning = null }) {
                    Body(w, size = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Later", Modifier.weight(1f)) { warning = null }
                        Btn("Open settings", Modifier.weight(1f), primary = true) {
                            warning = null
                            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        val gid = i?.getStringExtra("gid")
        if (gid != null) { core.tune(gid, i.getIntExtra("ch", 0).coerceAtLeast(0)); scr = Scr.RADIO }
        else if (i?.hasExtra("ch") == true) tab = 1
        i?.removeExtra("gid"); i?.removeExtra("ch")
    }

    override fun onResume() {
        super.onResume()
        visible = true
        app.onScreen.value = true
        app.nearby.restartIfIdle()
        app.nearby.publish()
        app.refreshLinks()
        refreshPerms()
        refreshButton()
        RadioService.start(this, mic = perms.mic)
        if (askedBattery) {
            askedBattery = false
            if (!perms.battery) warning = "Background running was not allowed. Android will put the radio to sleep and you will miss calls. The app won't work properly."
        }
    }

    override fun onPause() { visible = false; app.onScreen.value = false; app.buttonUp(); super.onPause() }

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun refreshPerms() {
        perms = Perms(
            mic = has(Manifest.permission.RECORD_AUDIO),
            notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
            nearby = NearbyTransport.hasPermissions(this),
            battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    @SuppressLint("BatteryLife")
    override fun requestPerm(kind: String) {
        when (kind) {
            "mic" -> micReq.launch(Manifest.permission.RECORD_AUDIO)
            "notif" -> if (Build.VERSION.SDK_INT >= 33) notifReq.launch(Manifest.permission.POST_NOTIFICATIONS)
            "nearby" -> nearbyReq.launch(NearbyTransport.permissions())
            "battery" -> {
                askedBattery = true
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (x: Exception) { } }
            }
        }
    }

    override fun setName(n: String) {
        val first = core.state.value.me == null
        core.setName(n)
        if (first) scr = Scr.SETUP
    }
    override fun setupDone() { app.prefs.edit().putBoolean("setupDone", true).apply() }
    override fun createGroup(name: String, ids: List<String>) { core.createGroup(name, ids) }
    override fun addMembers(gid: String, ids: List<String>) { core.addMembers(gid, ids) }
    override fun accept(gid: String) { core.acceptInvite(gid) }
    override fun decline(gid: String) { core.declineInvite(gid) }
    override fun leave(gid: String) { core.leaveGroup(gid) }
    override fun tune(gid: String?, ch: Int) { core.tune(gid, ch) }
    override fun lock() { core.lockChannel() }
    override fun unlock() { core.unlockChannel() }
    override fun summon(uid: String) { core.summon(uid) }
    override fun pttDown() { if (perms.mic) core.pttDown() else requestPerm("mic") }
    override fun pttUp() { core.pttUp() }
    override fun sendText(t: String) { core.sendText(t) }
    override fun setTtl(sec: Int) { core.setTtl(sec) }
    override fun setBeep(on: Boolean) { core.setBeep(on) }
    override fun setMode(m: String) { app.setMode(m) }
    override fun setServer(s: String) { serverUrl = s; app.setServer(s) }
}
