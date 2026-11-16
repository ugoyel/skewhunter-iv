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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.pttradio.BuildConfig
import app.pttradio.net.Auth
import app.pttradio.RadioService
import app.pttradio.core.RadioState
import app.pttradio.net.NearbyInfo
import app.pttradio.net.NearbyTransport
import app.pttradio.net.SosCodes
import kotlinx.coroutines.delay

@Composable
fun AppRoot(verified: Boolean, s: RadioState, p: Perms, near: NearbyInfo, alerts: List<app.pttradio.net.SosAlert>, sending: app.pttradio.net.SosSending?,
            field: Pair<String, String>, sosAnyone: Boolean, boost: Int, profile: Profile, level: kotlinx.coroutines.flow.StateFlow<Float>?, login: Boolean, mode: String, server: String, btn: ButtonCfg, a: Actions, scr: Scr, tab: Int, onTab: (Int) -> Unit, go: (Scr) -> Unit) {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(500); value = System.currentTimeMillis() } }
    // Android 16 draws apps edge to edge: keep content clear of the status bar, navigation bar and keyboard
    Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
        when {
            !s.ready -> {}
            s.me == null -> Welcome(a, login, verified)
            scr == Scr.SETUP -> Setup(p, a) { go(Scr.HOME) }
            scr == Scr.SETTINGS -> SettingsScreen(s, p, mode, server, btn, field, sosAnyone, boost, profile, a) { go(Scr.HOME) }
            scr == Scr.RADIO && s.group != null -> RadioScreen(s, near, level, a, now) { go(Scr.HOME) }
            else -> Home(s, p, near, alerts, sending, a, now, tab, onTab, go)
        }
    }
}

class MainActivity : ComponentActivity(), Actions {
    companion object { @Volatile var visible = false }

    private val app get() = application as App
    private val core get() = app.core
    private var perms by mutableStateOf(Perms())
    private var warning by mutableStateOf<String?>(null)
    /** A plain note, without the "open app settings" button that warnings have. */
    private var info by mutableStateOf<String?>(null)
    private var scr by mutableStateOf(Scr.HOME)
    private var tab by mutableIntStateOf(0)
    private var serverUrl by mutableStateOf("")
    private var askedBattery = false
    private val auth by lazy { Auth(BuildConfig.LOGIN_URL, BuildConfig.LOGIN_KEY) }

    override fun requestCode(email: String, name: String, phone: String, marketing: Boolean, done: (String?) -> Unit) {
        app.prefs.edit().putString("email", email).apply()
        auth.requestCode(email, name, phone, marketing) { e -> runOnUiThread { done(e) } }
    }

    /** The sign-in e-mail's link opened the app: check it and finish setting up. */
    private fun signInLink(uri: Uri) {
        val frag = (uri.fragment ?: uri.query ?: "").split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to Uri.decode(it[1]) } }.toMap()
        frag["error_description"]?.let { warning = "Sign-in failed: ${it.replace('+', ' ')}. Ask for a new link."; return }
        val token = frag["access_token"] ?: return
        frag["refresh_token"]?.let { app.prefs.edit().putString("refresh", it).apply() }
        auth.checkSession(token) { e -> runOnUiThread {
            if (e != null) warning = e
            else verified = true
        } }
    }
    /** True once the e-mail link (or a code) has confirmed the sign-up; the name step comes next. */
    private var verified by mutableStateOf(false)

    override fun requestPhoneCode(phone: String, marketing: Boolean, done: (String?) -> Unit) =
        auth.requestPhoneCode(phone, marketing) { e -> runOnUiThread { done(e) } }
    override fun verifyPhoneCode(phone: String, code: String, done: (String?) -> Unit) =
        auth.verifyPhone(phone, code) { e -> runOnUiThread { done(e) } }

    override fun verifyCode(email: String, code: String, done: (String?) -> Unit) =
        auth.verify(email, code) { e -> runOnUiThread { done(e) } }

    // ---- pictures: shrunk to a JPEG, sent encrypted, never kept on the phone ----

    /** Reads an image at most [maxDim] pixels on its long side, upright (camera rotation applied). */
    private fun decode(src: android.graphics.ImageDecoder.Source?, uri: Uri?, file: java.io.File?, maxDim: Int): android.graphics.Bitmap {
        if (Build.VERSION.SDK_INT >= 28 && src != null)
            return android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val big = maxOf(info.size.width, info.size.height)
                if (big > maxDim) { val k = maxDim.toFloat() / big; d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1)) }
            }
        val opts = android.graphics.BitmapFactory.Options()
        var b = (if (file != null) android.graphics.BitmapFactory.decodeFile(file.path, opts) else contentResolver.openInputStream(uri!!).use { android.graphics.BitmapFactory.decodeStream(it) })
        val big = maxOf(b.width, b.height)
        if (big > maxDim) b = android.graphics.Bitmap.createScaledBitmap(b, b.width * maxDim / big, b.height * maxDim / big, true)
        val turn = try {
            when ((if (file != null) android.media.ExifInterface(file.path) else contentResolver.openInputStream(uri!!).use { android.media.ExifInterface(it!!) })
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        } catch (e: Exception) { 0f }
        return if (turn == 0f) b else android.graphics.Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(turn) }, true)
    }

    private fun jpeg(b: android.graphics.Bitmap, maxBytes: Int): ByteArray {
        var q = 80
        var out: ByteArray
        do {
            val bos = java.io.ByteArrayOutputStream()
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, bos)
            out = bos.toByteArray(); q -= 10
        } while (out.size > maxBytes && q >= 30)
        return out
    }

    /** HD over internet, SD (smaller, quicker over Bluetooth / Wi-Fi) without. */
    private fun quality() = if (core.state.value.netUp) 1600 to 600_000 else 960 to 170_000

    private val imageReq = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) kotlin.concurrent.thread(isDaemon = true) {
            try {
                val (dim, bytes) = quality()
                val src = if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.createSource(contentResolver, uri) else null
                core.sendImage(jpeg(decode(src, uri, null, dim), bytes), pickTarget)
            } catch (e: Exception) { core.note("COULD NOT READ PICTURE") }
        }
    }
    /** The chat a picture or photo was started from: it goes there even if another chat is open when it is ready. */
    private var pickTarget: Pair<String, Int>? = null
    private var shotTarget: Pair<String, Int>? = null
    private fun openChat() = core.state.value.let { st -> st.tunedGroup?.let { it to st.tunedCh } }

    override fun pickImage() { pickTarget = openChat(); try { imageReq.launch("image/*") } catch (e: Exception) { } }

    // ---- camera: the photo (SD) plus a text with address, coordinates, time and a map link ----
    private var shot: java.io.File? = null
    @Volatile private var lastFix: android.location.Location? = null

    private val cameraReq = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = shot
        val to = shotTarget
        shot = null
        if (!ok || f == null) { f?.delete(); return@registerForActivityResult }
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val src = if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.createSource(f) else null
                core.sendImage(jpeg(decode(src, null, f, 960), 170_000), to)
            } catch (e: Exception) { core.note("COULD NOT READ PHOTO") }
            finally { f.delete() }        // the photo is not kept on the phone
            val takenAt = System.currentTimeMillis()
            val recent = lastFix?.takeIf { System.currentTimeMillis() - it.time < 120_000 }
            if (recent != null) stamp(recent, takenAt, to) else runOnUiThread { fix { l -> kotlin.concurrent.thread(isDaemon = true) { stamp(l, takenAt, to) } } }
        }
    }

    override fun takePhoto() {
        try {
            val dir = java.io.File(cacheDir, "cam").apply { mkdirs() }
            val f = java.io.File(dir, "shot.jpg")
            shot = f
            shotTarget = openChat()
            cameraReq.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f))
            // find the position while the camera is open, so the stamp is ready when the photo is
            fix { l -> if (l != null) lastFix = l }
        } catch (e: Exception) { warning = "No camera app is available." }
    }

    /** The text that goes with a photo: address (when it can be looked up), coordinates, date and time, map link. */
    private fun stamp(l: android.location.Location?, at: Long, to: Pair<String, Int>?) {
        val time = java.text.SimpleDateFormat("dd-MM-yyyy, HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(at))
        if (l == null) { core.sendText("📷 $time\nLocation unavailable", to); return }
        val lat = String.format(java.util.Locale.US, "%.6f", l.latitude)
        val lon = String.format(java.util.Locale.US, "%.6f", l.longitude)
        val address = address(l.latitude, l.longitude)
        val acc = if (l.hasAccuracy()) " (±${l.accuracy.toInt().coerceAtLeast(1)} m)" else ""
        core.sendText(listOfNotNull(address?.let { "📍 $it" }, "$lat,$lon$acc", time, "https://maps.google.com/?q=$lat,$lon").joinToString("\n"), to)
    }

    /** Street address from the phone's geocoder; usually needs internet, so it is simply left out when offline. */
    private fun address(lat: Double, lon: Double): String? = try {
        if (!android.location.Geocoder.isPresent()) null
        else if (Build.VERSION.SDK_INT >= 33) {
            val q = java.util.concurrent.ArrayBlockingQueue<String>(1)
            android.location.Geocoder(this).getFromLocation(lat, lon, 1, object : android.location.Geocoder.GeocodeListener {
                override fun onGeocode(list: MutableList<android.location.Address>) { q.offer(list.firstOrNull()?.getAddressLine(0) ?: "") }
                override fun onError(msg: String?) { q.offer("") }
            })
            q.poll(6, java.util.concurrent.TimeUnit.SECONDS)?.ifEmpty { null }
        } else @Suppress("DEPRECATION") android.location.Geocoder(this).getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
    } catch (e: Exception) { null }

    // ---- location: GPS works with no internet; the fix is sent as a normal expiring message ----
    override fun shareLocation() {
        core.note("GETTING POSITION")
        fix { l -> if (l != null) core.sendLocation(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toInt().coerceAtLeast(1) else 0) else core.note("NO POSITION FIX") }
    }

    /** One position fix: GPS first (works with no internet), then the other providers. */
    private fun fix(done: (android.location.Location?) -> Unit) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) { requestPerm("nearby"); done(null); return }
        val lm = getSystemService(android.location.LocationManager::class.java)
        val order = listOf(android.location.LocationManager.GPS_PROVIDER, "fused", android.location.LocationManager.NETWORK_PROVIDER)
            .filter { p -> try { lm.allProviders.contains(p) && lm.isProviderEnabled(p) } catch (e: Exception) { false } }
        if (order.isEmpty()) { warning = "Location is switched off. Turn it on to share where you are."; done(null); return }
        locate(lm, order, 0, done)
    }

    @SuppressLint("MissingPermission")
    private fun locate(lm: android.location.LocationManager, order: List<String>, i: Int, done0: (android.location.Location?) -> Unit) {
        if (i >= order.size) { done0(null); return }
        val handler = android.os.Handler(mainLooper)
        var done = false
        val got = { l: android.location.Location? ->
            if (!done) {
                done = true
                if (l != null) done0(l) else locate(lm, order, i + 1, done0)
            }
        }
        try {
            // GPS gets up to 30 s (a cold fix without internet is slow); the fall-backs get 10 s
            val wait = if (i == 0) 30_000L else 10_000L
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = android.os.CancellationSignal()
                lm.getCurrentLocation(order[i], cancel, mainExecutor) { l -> got(l) }
                handler.postDelayed({ if (!done) { cancel.cancel(); got(null) } }, wait)
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) { got(l) }
                    @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                @Suppress("DEPRECATION") lm.requestSingleUpdate(order[i], listener, mainLooper)
                handler.postDelayed({ if (!done) { lm.removeUpdates(listener); got(null) } }, wait)
            }
        } catch (e: Exception) { got(null) }
    }

    override fun addToHome(gid: String) {
        val ok = try { PttWidget.pin(this, gid) } catch (e: Exception) { false }
        if (!ok) warning = "This home screen does not let apps place widgets. Long-press the home screen, choose Widgets, and add ZeroBar from there."
    }

    override fun sendSos(code: Int) {
        if (!NearbyTransport.hasPermissions(this)) { requestPerm("nearby"); return }
        if (getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled != true)
            warning = "Bluetooth is off, so phones nearby cannot hear the emergency message. Turn Bluetooth on."
        app.sos.send(code, app.prefs.getBoolean("sosAnyone", false))
        val text = "SOS · " + SosCodes.of(code)
        fix { l ->
            if (l != null) {
                app.sos.setFix(l.latitude, l.longitude)
                core.alertGroups(text, "geo:" + String.format(java.util.Locale.US, "%.6f,%.6f,%d", l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toInt().coerceAtLeast(1) else 0))
            } else core.alertGroups(text, null)
        }
    }
    override fun stopSos() { app.sos.stop() }
    override fun setSosAnyone(on: Boolean) { app.prefs.edit().putBoolean("sosAnyone", on).apply(); sosOpen = on }
    override fun dismissAlert(key: String) { app.sos.dismiss(key) }

    // Wi-Fi passwords need at least 8 characters, so the default is ZeroBar1
    private val field get() = (app.prefs.getString("fieldSsid", null) ?: "ZeroBar") to (app.prefs.getString("fieldPass", null) ?: "ZeroBar1")
    private var profile by mutableStateOf(Profile())

    override fun saveFieldWifi(ssid: String, pass: String) {
        app.prefs.edit().putString("fieldSsid", ssid).putString("fieldPass", pass).apply()
        try {
            val sug = android.net.wifi.WifiNetworkSuggestion.Builder().setSsid(ssid).setWpa2Passphrase(pass).build()
            if (Build.VERSION.SDK_INT >= 30) {
                // the system asks once, then the phone joins this network by itself whenever it is in range
                startActivity(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(sug)))
            } else {
                getSystemService(android.net.wifi.WifiManager::class.java).addNetworkSuggestions(listOf(sug))
                info = "Saved. Android may show a notification asking to allow networks suggested by ZeroBar: allow it."
            }
        } catch (e: Exception) {
            info = "This phone could not save the network automatically. Open Wi-Fi settings and add \"$ssid\" with the password by hand."
        }
    }

    override fun openHotspot(ssid: String, pass: String) {
        app.prefs.edit().putString("fieldSsid", ssid).putString("fieldPass", pass).apply()
        try {
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Hotspot password", pass))
        } catch (e: Exception) { }
        info = "Set the hotspot name to \"$ssid\", security WPA2, password \"$pass\" (copied), then switch the hotspot on. " +
            "You only set this once; afterwards the hotspot button in quick settings is enough."
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS))
        for (i in tries) try { startActivity(i); return } catch (e: Exception) { }
    }

    override fun openDirect(uid: String) { core.openDirect(uid) }
    override fun renameGroup(gid: String, name: String) { core.renameGroup(gid, name) }

    // ---- profile ----
    private val photoReq = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) kotlin.concurrent.thread(isDaemon = true) {
            try {
                val src = if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.createSource(contentResolver, uri) else null
                val b = decode(src, uri, null, 400)
                val side = minOf(b.width, b.height)
                val sq = android.graphics.Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
                val small = android.graphics.Bitmap.createScaledBitmap(sq, 128, 128, true)
                core.setPhoto(android.util.Base64.encodeToString(jpeg(small, 12_000), android.util.Base64.NO_WRAP))
            } catch (e: Exception) { core.note("COULD NOT READ PICTURE") }
        }
    }
    override fun pickPhoto() { try { photoReq.launch("image/*") } catch (e: Exception) { } }
    override fun removePhoto() { core.setPhoto("") }

    override fun saveMobile(mobile: String, done: (String) -> Unit) {
        app.prefs.edit().putString("mobile", mobile).apply()
        profile = profile.copy(mobile = mobile)
        val refresh = app.prefs.getString("refresh", null)
        if (refresh == null || !auth.enabled) { done("Saved on this phone."); return }
        auth.updateData(refresh, org.json.JSONObject().put("phone", mobile)) { e, r ->
            r?.let { app.prefs.edit().putString("refresh", it).apply() }
            runOnUiThread { done(e ?: "Saved to your account.") }
        }
    }

    override fun signOut() {
        app.prefs.edit().clear().commit()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)   // start again from a clean state
    }

    override fun reconnect() {
        core.note("RECONNECTING")
        app.nearby.restart()
        app.lan.stop(); app.lan.start()
        app.mqtt.kick()
        app.refreshLinks()
    }

    override fun openMap(lat: Double, lon: Double, label: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})"))) }
        catch (e: Exception) { warning = "No maps app is installed to show this position." }
    }
    private var btn by mutableStateOf(ButtonCfg())
    private var sosOpen by mutableStateOf(false)
    private var boostLevel by mutableStateOf(1)

    private fun refreshButton(learning: Boolean = false) {
        btn = ButtonCfg(app.pttKey(), learning, app.pttGroup())
    }

    /** The talk button while this app is on screen (Android blocks side-loaded apps that watch keys in other apps). */
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
        sosOpen = app.prefs.getBoolean("sosAnyone", false)
        profile = Profile(app.prefs.getString("email", "") ?: "", app.prefs.getString("mobile", "") ?: "")
        boostLevel = app.prefs.getInt("boost", 1)
        if (core.state.value.me != null || app.prefs.getString("me", null) != null) {
            if (!app.prefs.getBoolean("setupDone", false)) scr = Scr.SETUP
        }
        handle(intent)
        darkBars()
        setContent {
            val s by core.state.collectAsState()
            val mode by app.linkMode.collectAsState()
            val near by app.nearby.info.collectAsState()
            val alerts by app.sos.alerts.collectAsState()
            val sending by app.sos.sending.collectAsState()
            LaunchedEffect(s.me?.id) { if (s.me != null) RadioService.start(this@MainActivity) }
            BackHandler(enabled = s.me != null && scr != Scr.HOME) { scr = Scr.HOME }
            AppRoot(verified, s, perms, near, alerts, sending, field, sosOpen, boostLevel, profile, core.level, auth.enabled, mode, serverUrl, btn, this, scr, tab, { tab = it }) { scr = it }
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
            info?.let { t ->
                Sheet("Note", { info = null }) {
                    Body(t, size = 14.sp)
                    Btn("OK", Modifier.fillMaxWidth(), primary = true) { info = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    private fun handle(i: Intent?) {
        i?.data?.takeIf { it.scheme == "zerobar" }?.let { signInLink(it); i.data = null; return }
        val gid = i?.getStringExtra("gid")
        if (gid != null) { core.tune(gid, i.getIntExtra("ch", 0).coerceAtLeast(0)); scr = Scr.RADIO }
        else if (i?.hasExtra("ch") == true) tab = 0
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
        if (first) { scr = Scr.SETUP; askAll() }
    }

    private val allReq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPerms()
        app.refreshLinks()
        if (!perms.battery) requestPerm("battery")
    }

    /** Every permission at once through Android's own dialogs; the setup screen then explains anything denied. */
    private fun askAll() {
        val list = arrayListOf(Manifest.permission.RECORD_AUDIO) + NearbyTransport.permissions()
        val all = if (Build.VERSION.SDK_INT >= 33) list + Manifest.permission.POST_NOTIFICATIONS else list
        try { allReq.launch(all.toTypedArray()) } catch (e: Exception) { }
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
    override fun setHd(on: Boolean) { core.setHd(on) }
    override fun setBoost(level: Int) { app.setBoost(level); boostLevel = level }
    override fun setMode(m: String) { app.setMode(m) }
    override fun setServer(s: String) { serverUrl = s; app.setServer(s) }
}

/** Edge-to-edge with light icons on the dark background. */
fun ComponentActivity.darkBars() = enableEdgeToEdge(
    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
