package app.pttradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.core.Group
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.User
import app.pttradio.core.chName
import app.pttradio.net.NearbyInfo
import app.pttradio.net.PeerLink
import app.pttradio.net.SosAlert
import app.pttradio.net.SosCodes
import app.pttradio.net.SosSending

data class Perms(val mic: Boolean = true, val notif: Boolean = true, val nearby: Boolean = true, val battery: Boolean = true) {
    val allOk get() = mic && notif && nearby && battery
}

/** Hardware talk button settings. */
data class Profile(val email: String = "", val mobile: String = "")

data class ButtonCfg(val key: Int = 0, val learning: Boolean = false, val group: String = "")

enum class Scr { HOME, RADIO, SETTINGS, SETUP }

interface Actions {
    fun setName(n: String)
    fun createGroup(name: String, ids: List<String>)
    fun addMembers(gid: String, ids: List<String>)
    fun accept(gid: String)
    fun decline(gid: String)
    fun leave(gid: String)
    fun tune(gid: String?, ch: Int)
    fun lock()
    fun unlock()
    fun summon(uid: String)
    fun pttDown()
    fun pttUp()
    fun sendText(t: String)
    fun setTtl(sec: Int)
    fun setBeep(on: Boolean)
    fun setHd(on: Boolean)
    /** Speaker loudness: 0 normal, 1 loud, 2 maximum. */
    fun setBoost(level: Int)
    fun pickPhoto()
    fun removePhoto()
    /** Saves the mobile number; [done] gets a short status line to show. */
    fun saveMobile(mobile: String, done: (String) -> Unit)
    fun signOut()
    fun setMode(m: String)
    fun setServer(s: String)
    fun requestPerm(kind: String)
    fun setupDone()
    fun setPttKey(code: Int)
    fun learnKey()
    fun setPttGroup(gid: String)
    fun fixNearby(problem: String)
    /** Drop and rebuild every link now. */
    fun reconnect()
    /** Put a 1x1 talk widget for this group on the home screen. */
    fun addToHome(gid: String)
    /** Open (or start) the one-to-one chat with a person. */
    fun openDirect(uid: String)
    fun renameGroup(gid: String, name: String)
    /** Emergency broadcast to every phone with the app nearby, no connection needed. */
    fun sendSos(code: Int)
    fun stopSos()
    fun dismissAlert(key: String)
    fun saveFieldWifi(ssid: String, pass: String)
    fun openHotspot(ssid: String, pass: String)
    fun shareLocation()
    fun pickImage()
    /** Camera photo, followed by a text with address, coordinates, time and map link. */
    fun takePhoto()
    fun setSosAnyone(on: Boolean)
    fun openMap(lat: Double, lon: Double, label: String)
    /** E-mail code sign-in; callbacks get null on success or a message to show. */
    fun requestCode(email: String, name: String, phone: String, marketing: Boolean, done: (String?) -> Unit)
    fun verifyCode(email: String, code: String, done: (String?) -> Unit)
    /** Mobile sign-in: the code goes to the person's Telegram. */
    fun requestPhoneCode(phone: String, marketing: Boolean, done: (String?) -> Unit)
    fun verifyPhoneCode(phone: String, code: String, done: (String?) -> Unit)
}

fun isNearby(u: User, now: Long) = now - u.nearbyAt < RadioCore.NEARBY_EXPIRE

@Composable
fun LinkPill(s: RadioState) {
    val (c, t) = when { s.netUp -> C.green to "INTERNET"; s.nearbyUp -> C.amber to "NEARBY"; else -> C.red to "NO LINK" }
    Row(Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, C.line, RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(c, 7.dp); Label(t, color = C.text, size = 10.sp)
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Icon(Icons.Filled.ArrowBack, "Back", Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onBack).padding(10.dp).size(22.dp), tint = C.text)
        else Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), Modifier.weight(1f).padding(horizontal = 4.dp), color = C.text, fontSize = 15.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        actions()
    }
}

/**
 * Sign-up, one question per screen: e-mail (sign-in link), then the name people will see. [verified] turns true when the e-mail link brings the person back.
 */
@Composable
fun Welcome(a: Actions, login: Boolean, verified: Boolean) {
    var step by remember { mutableStateOf(if (login) "email" else "name") }
    var phone by remember { mutableStateOf("+91") }
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var marketing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (verified && step == "emailSent") step = "name"
    val emailOk = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$").matches(email.trim())
    val phoneOk = Regex("^\\+[1-9][0-9]{7,14}$").matches(phone)
    fun go(next: String) { step = next; error = null; code = "" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Welcome to ZeroBar", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Body("Hold to talk with your people, over internet or straight phone-to-phone when there is none.", color = C.dim)
        Spacer(Modifier.height(28.dp))
        @Composable fun consent() = Row(Modifier.padding(top = 14.dp).clickable { marketing = !marketing }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (marketing) C.accent else Color.Transparent).border(1.dp, if (marketing) C.accent else C.line, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center) { if (marketing) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = C.bg) }
            Spacer(Modifier.width(12.dp))
            Body("Send me news and offers. Optional.", Modifier.weight(1f), color = C.dim, size = 13.sp)
        }
        @Composable fun link(text: String, onClick: () -> Unit) =
            Text(text, Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
                color = C.accent, fontSize = 15.sp)
        when (step) {
            "phone" -> {
                Body("Your mobile number", weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("We send a sign-in code to your Telegram on this number.", color = C.dim, size = 13.sp)
                Spacer(Modifier.height(10.dp))
                Field(phone, { v -> phone = ("+" + v.filter { it.isDigit() }).take(16) }, "+91 98765 43210", Modifier.fillMaxWidth())
                consent()
                error?.let { Spacer(Modifier.height(12.dp)); Body(it, color = C.red, size = 13.sp) }
                Spacer(Modifier.height(16.dp))
                Btn(if (busy) "Sending…" else "Send code", Modifier.fillMaxWidth(), primary = true, enabled = !busy && phoneOk) {
                    busy = true; error = null
                    a.requestPhoneCode(phone, marketing) { e -> busy = false; if (e == null) go("phoneCode") else error = e }
                }
                link("Sign up with e-mail instead") { go("email") }
            }
            "phoneCode" -> {
                Body("Enter the code", weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("Telegram sent a code to $phone.", color = C.dim, size = 13.sp)
                Spacer(Modifier.height(10.dp))
                Field(code, { v -> code = v.filter { it.isDigit() }.take(8) }, "6-digit code", Modifier.fillMaxWidth())
                error?.let { Spacer(Modifier.height(12.dp)); Body(it, color = C.red, size = 13.sp) }
                Spacer(Modifier.height(16.dp))
                Btn(if (busy) "Checking…" else "Continue", Modifier.fillMaxWidth(), primary = true, enabled = !busy && code.length >= 4) {
                    busy = true; error = null
                    a.verifyPhoneCode(phone, code) { e -> busy = false; if (e == null) go("name") else error = e }
                }
                link("Change number") { go("phone") }
            }
            "email" -> {
                Body("Your e-mail", weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("We e-mail you a sign-in link.", color = C.dim, size = 13.sp)
                Spacer(Modifier.height(10.dp))
                Field(email, { email = it.trim().take(120) }, "you@example.com", Modifier.fillMaxWidth())
                consent()
                error?.let { Spacer(Modifier.height(12.dp)); Body(it, color = C.red, size = 13.sp) }
                Spacer(Modifier.height(16.dp))
                PrivacyNote()
                Spacer(Modifier.height(16.dp))
                Btn(if (busy) "Sending…" else "Send link", Modifier.fillMaxWidth(), primary = true, enabled = !busy && emailOk) {
                    busy = true; error = null
                    a.requestCode(email.trim(), "", "", marketing) { e -> busy = false; if (e == null) go("emailSent") else error = e }
                }
            }
            "emailSent" -> {
                Body("Check your e-mail", weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("We sent a sign-in link to $email. Open that e-mail on this phone and tap the link. " +
                    "You will come back here automatically and can pick your name.", color = C.dim, size = 14.sp)
                Spacer(Modifier.height(6.dp))
                Body("No e-mail? Check spam, or wait a minute and send it again.", color = C.faint, size = 12.sp)
                link("Send the link again") { busy = true; a.requestCode(email.trim(), "", "", marketing) { e -> busy = false; error = e } }
                error?.let { Spacer(Modifier.height(8.dp)); Body(it, color = C.red, size = 13.sp) }
                link("Use a different e-mail") { go("email") }
            }
            else -> {
                Body("What should people call you?", weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("Pick a name your team will recognise and that is unlikely to be taken, like \"Rahul K\" or \"Base Camp 2\". You can change it later.",
                    color = C.dim, size = 13.sp)
                Spacer(Modifier.height(10.dp))
                Field(name, { name = it.take(24) }, "Your name", Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Btn("Let's go", Modifier.fillMaxWidth(), primary = true, enabled = name.trim().length >= 2) { a.setName(name) }
            }
        }
    }
}

/** Reassurance shown at sign-up, during setup and in Settings. */
@Composable
fun PrivacyNote() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x1421C063)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Body("🔒 Your privacy", weight = FontWeight.SemiBold, size = 14.sp)
        Body("• Voice, messages, photos and locations are end-to-end encrypted: only the people in the chat can open them. We cannot read or hear anything.", color = C.dim, size = 12.sp)
        Body("• Nothing you say or send is stored on any server. Messages delete themselves within 2 minutes, and voice is never recorded.", color = C.dim, size = 12.sp)
        Body("• No ads, no tracking. Your e-mail is only used to sign you in.", color = C.dim, size = 12.sp)
    }
}

@Composable
private fun PermRow(title: String, why: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Body(title, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Body(why, color = C.dim, size = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (ok) Icon(Icons.Filled.Check, "Allowed", Modifier.size(22.dp), tint = C.green) else Btn("Allow", primary = true, onClick = onFix)
    }
}

@Composable
fun PermList(p: Perms, a: Actions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermRow("Always on in background", "Keeps the radio connected when the app is closed. Idle use is light on battery.", p.battery) { a.requestPerm("battery") }
        PermRow("Microphone", "Used only while you hold the talk key.", p.mic) { a.requestPerm("mic") }
        PermRow("Notifications", "Group invitations and channel calls.", p.notif) { a.requestPerm("notif") }
        PermRow("Nearby devices and Location", "Bluetooth and Wi-Fi link to phones around you with no internet. Android only lets apps scan for phones with Location allowed; your position is never read or sent.", p.nearby) { a.requestPerm("nearby") }
    }
}

@Composable
fun Setup(p: Perms, a: Actions, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Radio check")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Body("A walkie-talkie has to be listening all the time. Allow these so calls reach you instantly. Each one says why it is needed.", color = C.dim)
            PermList(p, a)
            if (!p.allOk) WarningBox("Without these the app won't work properly: you can miss calls when the screen is off.")
            PrivacyNote()
        }
        Btn(if (p.allOk) "Continue" else "Continue anyway", Modifier.fillMaxWidth().padding(16.dp), primary = p.allOk) { a.setupDone(); onDone() }
    }
}

@Composable
fun WarningBox(text: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x22FFB020)).border(1.dp, Color(0x66FFB020), RoundedCornerShape(8.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = C.amber)
        Body(text, Modifier.weight(1f), color = C.text, size = 13.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Label(title); content() }
}

@Composable
fun SettingsScreen(s: RadioState, p: Perms, mode: String, server: String, btn: ButtonCfg, field: Pair<String, String>, sosAnyone: Boolean, boost: Int, profile: Profile, a: Actions, onBack: () -> Unit) {
    var fSsid by remember { mutableStateOf(field.first) }
    var fPass by remember { mutableStateOf(field.second) }
    var name by remember { mutableStateOf(s.me?.name ?: "") }
    var srv by remember { mutableStateOf(server) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Section("Profile") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape).clickable { a.pickPhoto() }) { Avatar(s.me?.name ?: "", s.me?.id ?: "", 64.dp, pic = s.me?.pic ?: "") }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (s.me?.pic.isNullOrEmpty()) "Add a profile photo" else "Change photo", Modifier.clickable { a.pickPhoto() }, color = C.accent, fontSize = 15.sp)
                        if (!s.me?.pic.isNullOrEmpty()) Text("Remove photo", Modifier.padding(top = 6.dp).clickable { a.removePhoto() }, color = C.dim, fontSize = 13.sp)
                    }
                }
                Body("Your photo is shown to people on the app.", color = C.dim, size = 12.sp)
                Label("Name", Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(name, { name = it.take(24) }, "Name", Modifier.weight(1f))
                    Btn("Save", enabled = name.isNotBlank() && name.trim() != s.me?.name) { a.setName(name) }
                }
                if (profile.email.isNotEmpty()) { Label("E-mail", Modifier.padding(top = 6.dp)); Body(profile.email, color = C.dim, size = 14.sp) }
                Label("Mobile number", Modifier.padding(top = 6.dp))
                var mobile by remember { mutableStateOf(profile.mobile) }
                var saved by remember { mutableStateOf<String?>(null) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(mobile, { v -> mobile = v.filter { it.isDigit() || it == '+' }.take(16) }, "e.g. +91 98765 43210", Modifier.weight(1f))
                    Btn("Save", enabled = mobile != profile.mobile) { a.saveMobile(mobile) { r -> saved = r } }
                }
                saved?.let { Body(it, color = C.dim, size = 12.sp) }
                Body("Kept with your account for support; never shown to other users.", color = C.dim, size = 12.sp)
                var out by remember { mutableStateOf(false) }
                Btn("Sign out", Modifier.fillMaxWidth().padding(top = 8.dp), danger = true) { out = true }
                if (out) Sheet("Sign out?", { out = false }) {
                    Body("Your name, keys, chats and settings are removed from this phone. You can sign in again with your e-mail, " +
                        "but people will have to add you to their groups again.", color = C.dim, size = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Cancel", Modifier.weight(1f)) { out = false }
                        Btn("Sign out", Modifier.weight(1f), danger = true) { out = false; a.signOut() }
                    }
                }
            }
            Section("Privacy") { PrivacyNote() }
            Section("Talk button") {
                val vd = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
                val vu = android.view.KeyEvent.KEYCODE_VOLUME_UP
                val sel = when (btn.key) { 0 -> "off"; vd -> "vd"; vu -> "vu"; else -> "custom" }
                Segments(listOf("off" to "Off", "vd" to "Vol −", "vu" to "Vol +", "custom" to "Other"), if (btn.learning) "custom" else sel, Modifier.fillMaxWidth()) {
                    when (it) { "off" -> a.setPttKey(0); "vd" -> a.setPttKey(vd); "vu" -> a.setPttKey(vu); else -> a.learnKey() }
                }
                when {
                    btn.learning -> WarningBox("Press the button you want to use now.")
                    sel == "custom" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Body("Using " + android.view.KeyEvent.keyCodeToString(btn.key).removePrefix("KEYCODE_").replace('_', ' '), Modifier.weight(1f), color = C.dim, size = 13.sp)
                        Btn("Change") { a.learnKey() }
                    }
                }
                Body("Hold the button to talk, release to mute, while ZeroBar is open on screen. Android does not let any app take over the power button; volume keys and programmable side keys work.", color = C.dim, size = 12.sp)
                if (btn.key != 0) {
                    Label("Button talks on", Modifier.padding(top = 6.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).background(C.panel)) {
                        (listOf("" to "Current chat") + s.groups.map { it.id to s.titleOf(it) }).forEach { (id, name) ->
                            val on = btn.group == id || (id.isEmpty() && s.groups.none { it.id == btn.group })
                            Row(Modifier.fillMaxWidth().clickable { a.setPttGroup(id) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Dot(if (on) C.amber else C.line)
                                Spacer(Modifier.width(12.dp))
                                Body(name, color = if (on) C.text else C.dim, maxLines = 1)
                                if (id.isNotEmpty()) Label("  ·  main room", size = 10.sp)
                            }
                        }
                    }
                }
            }
            Section("Messages delete themselves after") {
                Segments(listOf("15" to "15 s", "30" to "30 s", "60" to "1 min", "120" to "2 min"), s.ttlSec.toString(), Modifier.fillMaxWidth()) { a.setTtl(it.toInt()) }
                Body("Text is kept in memory only, on this phone, for at most 2 minutes. Voice is never stored anywhere.", color = C.dim, size = 12.sp)
            }
            Section("Link") {
                Segments(listOf("auto" to "Auto", "net" to "Internet", "nearby" to "Nearby", "both" to "Both"), mode, Modifier.fillMaxWidth()) { a.setMode(it) }
                Body(when (mode) {
                    "net" -> "Internet only. Bluetooth and Wi-Fi discovery stay off."
                    "nearby" -> "Phone-to-phone only, over Bluetooth and Wi-Fi. No server is used."
                    "both" -> "Internet and nearby together. Lets this phone stay reachable both ways; uses the most battery."
                    else -> "Internet when there is one. Nearby phones are found over Bluetooth and Wi-Fi whenever there is no internet, and also while the app is open."
                }, color = C.dim, size = 12.sp)
            }
            Section("Voice quality") {
                Segments(listOf("1" to "Auto", "0" to "Standard"), if (s.hd && s.hdOk) "1" else "0", Modifier.fillMaxWidth()) { a.setHd(it == "1") }
                Body(when {
                    !s.hdOk -> "This phone cannot use adaptive voice, so it stays on Standard (about 64 kbit/s)."
                    s.hd -> "Follows the connection: HD voice on good internet (32 kbit/s), low-data voice on a weak one (16 kbit/s), " +
                        "data saver without internet (12 kbit/s). Used when every phone on the channel supports it; otherwise Standard."
                    else -> "About 64 kbit/s, no compression. Works with every version of the app."
                }, color = C.dim, size = 12.sp)
            }
            Section("Speaker loudness") {
                Segments(listOf("0" to "Normal", "1" to "Loud", "2" to "Maximum"), boost.toString(), Modifier.fillMaxWidth()) { a.setBoost(it.toInt()) }
                Body("Quiet voices are raised to a loud, even level; background noise is not amplified and loud voices are limited so they do not distort. " +
                    "Turn the phone's media volume up too.", color = C.dim, size = 12.sp)
            }
            Section("End-of-transmission beep") {
                Segments(listOf("1" to "On", "0" to "Off"), if (s.beep) "1" else "0", Modifier.width(180.dp)) { a.setBeep(it == "1") }
            }
            Section("Emergency (SOS) messages are shown to") {
                Segments(listOf("c" to "My contacts", "a" to "Anyone nearby"), if (sosAnyone) "a" else "c", Modifier.fillMaxWidth()) { a.setSosAnyone(it == "a") }
                Body(if (sosAnyone) "Every phone with ZeroBar in range shows your alert, including strangers who could help."
                     else "Only people in your groups and chats see your alert. Other phones with the app still pass it on, without showing it, so it can reach a contact further away.",
                    color = C.dim, size = 12.sp)
            }
            Section("Range extender · field Wi-Fi") {
                Body("For places with no internet. One phone turns on its hotspot with this name and password; every other phone that saved it " +
                    "joins automatically when in range, and the app talks over that Wi-Fi. The hotspot phone is the hub, so people can be further " +
                    "apart than Bluetooth allows. Use the same name and password on every phone.", color = C.dim, size = 12.sp)
                Field(fSsid, { fSsid = it.take(32) }, "Network name", Modifier.fillMaxWidth())
                Field(fPass, { fPass = it.take(63) }, "Password (8+ characters)", Modifier.fillMaxWidth())
                val ok = fSsid.isNotBlank() && fPass.length >= 8
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Save on this phone", Modifier.weight(1f), enabled = ok) { a.saveFieldWifi(fSsid.trim(), fPass) }
                    Btn("Be the hotspot", Modifier.weight(1f), primary = true, enabled = ok) { a.openHotspot(fSsid.trim(), fPass) }
                }
                Body("Android does not let apps switch the hotspot on by themselves: \"Be the hotspot\" opens the hotspot settings and copies the " +
                    "password. Set security to WPA2. When internet is back, turn the hotspot off; the other phones go back to their usual networks on their own.",
                    color = C.dim, size = 12.sp)
            }
            Section("Relay server") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(srv, { srv = it.trim() }, "e.g. ssl://broker.emqx.io:8883", Modifier.weight(1f))
                    Btn("Apply", enabled = srv != server) { a.setServer(srv) }
                }
                Body("A free public MQTT relay passes encrypted packets between phones. Everyone in a group must use the same server. Leave empty for the default.", color = C.dim, size = 12.sp)
            }
            Section("Permissions") { PermList(p, a) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
