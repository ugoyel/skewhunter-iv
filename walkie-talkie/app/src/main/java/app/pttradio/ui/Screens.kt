package app.pttradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
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

data class Perms(val mic: Boolean = true, val notif: Boolean = true, val nearby: Boolean = true, val battery: Boolean = true) {
    val allOk get() = mic && notif && nearby && battery
}

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
    fun setMode(m: String)
    fun setServer(s: String)
    fun requestPerm(kind: String)
    fun setupDone()
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

@Composable
fun Welcome(a: Actions) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("PTT RADIO", color = C.amber, fontFamily = Mono, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Body("Push-to-talk voice for your groups. Over internet, or straight phone-to-phone when there is none.", color = C.dim)
        Spacer(Modifier.height(36.dp))
        Label("Your call sign")
        Spacer(Modifier.height(8.dp))
        Field(name, { name = it.take(24) }, "Name shown to others", Modifier.fillMaxWidth()) { if (name.isNotBlank()) a.setName(name) }
        Spacer(Modifier.height(16.dp))
        Btn("Power on", Modifier.fillMaxWidth(), primary = true, enabled = name.isNotBlank()) { a.setName(name) }
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
        PermRow("Nearby devices", "Bluetooth and Wi-Fi link to phones around you when there is no internet.", p.nearby) { a.requestPerm("nearby") }
    }
}

@Composable
fun Setup(p: Perms, a: Actions, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Radio check")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Body("A walkie-talkie has to be listening all the time. Allow these so calls reach you instantly.", color = C.dim)
            PermList(p, a)
            if (!p.allOk) WarningBox("Without these the app won't work properly: you can miss calls when the screen is off.")
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
fun Home(s: RadioState, p: Perms, a: Actions, now: Long, tab: Int, onTab: (Int) -> Unit, go: (Scr) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PTT RADIO", color = C.amber, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Label(s.me?.name ?: "", size = 10.sp)
            }
            LinkPill(s)
            Icon(Icons.Filled.Settings, "Settings", Modifier.padding(start = 4.dp).clip(RoundedCornerShape(20.dp)).clickable { go(Scr.SETTINGS) }.padding(10.dp).size(22.dp), tint = C.dim)
        }
        if (!p.allOk) Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            WarningBox("Permissions missing. The radio won't work properly. Tap to fix.") { go(Scr.SETUP) }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) Contacts(s, a, now) { go(Scr.RADIO) } else Groups(s, a, { onTab(0) }) { gid, ch -> a.tune(gid, ch); go(Scr.RADIO) }
        }
        Row(Modifier.fillMaxWidth().background(C.panel).border(1.dp, C.line)) {
            listOf("Contacts", "Groups").forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clickable { onTab(i) }.padding(top = 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) C.amber else Color.Transparent))
                    Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label(t, color = if (tab == i) C.amber else C.dim, size = 12.sp)
                        if (i == 1 && s.invites.isNotEmpty()) Dot(C.red, 7.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun UserRow(u: User, now: Long, selected: Boolean, onClick: () -> Unit) {
    val near = isNearby(u, now)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (selected) C.raised else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(if (near) C.amber else if (u.online) C.green else C.faint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Body(u.name, weight = FontWeight.Medium, maxLines = 1)
            Label(if (near) "Nearby" else if (u.online) "Online" else "Off air", size = 10.sp, color = if (near) C.amber else C.dim)
        }
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(if (selected) C.amber else Color.Transparent).border(1.dp, if (selected) C.amber else C.line, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = C.bg) }
    }
}

fun visibleUsers(s: RadioState, now: Long, q: String = ""): List<User> = s.users.values
    .filter { it.name.isNotBlank() && (it.online || isNearby(it, now) || now - it.ts < RadioCore.STALE_PROFILE) }
    .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
    .sortedWith(compareByDescending<User> { isNearby(it, now) }.thenByDescending { it.online }.thenBy { it.name.lowercase() })

@Composable
fun Contacts(s: RadioState, a: Actions, now: Long, onCreated: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var naming by remember { mutableStateOf(false) }
    val users = visibleUsers(s, now, q)
    Column(Modifier.fillMaxSize()) {
        Field(q, { q = it }, "Search people", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (users.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Label(if (q.isNotBlank()) "No match" else if (s.netUp || s.nearbyUp) "Nobody else on air yet" else "Searching for a link")
            Spacer(Modifier.height(8.dp))
            Body("Everyone who installs the app appears here. With no internet, phones in range show up as Nearby.", color = C.faint, size = 13.sp)
        } else LazyColumn(Modifier.weight(1f)) {
            items(users, key = { it.id }) { u -> UserRow(u, now, u.id in sel) { sel = if (u.id in sel) sel - u.id else sel + u.id } }
        }
        if (sel.isNotEmpty()) Btn("Create group · ${sel.size}", Modifier.fillMaxWidth().padding(16.dp), primary = true) { naming = true }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        val create = { a.createGroup(name, sel.toList()); sel = emptySet(); naming = false; onCreated() }
        Sheet("New group", { naming = false }) {
            Body("${sel.size} member${if (sel.size > 1) "s" else ""} will be notified to join.", color = C.dim, size = 13.sp)
            Field(name, { name = it.take(24) }, "Group name", Modifier.fillMaxWidth(), onDone = { if (name.isNotBlank()) create() })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", Modifier.weight(1f)) { naming = false }
                Btn("Create", Modifier.weight(1f), primary = true, enabled = name.isNotBlank(), onClick = create)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(s: RadioState, g: Group, open: (String, Int) -> Unit) {
    val tuned = s.tunedGroup == g.id
    val pres = s.presence[g.id] ?: emptyMap()
    val active = (pres.values.map { it.ch } + (s.locks[g.id]?.map { it.ch } ?: emptyList()) + 0).distinct().sorted()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.panel).border(1.dp, if (tuned) C.amber else C.line, RoundedCornerShape(8.dp))
        .clickable { open(g.id, if (tuned) s.tunedCh else 0) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Body(g.name, weight = FontWeight.SemiBold, size = 16.sp, maxLines = 1)
                Label("${g.members.size} members · ${pres.size} on air", size = 10.sp)
            }
            if (tuned) Label("Tuned", color = C.amber, size = 10.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            active.forEach { ch ->
                val n = pres.values.count { it.ch == ch }
                val here = tuned && s.tunedCh == ch
                Row(Modifier.clip(RoundedCornerShape(5.dp)).background(C.raised).border(1.dp, if (here) C.amber else C.line, RoundedCornerShape(5.dp))
                    .clickable { open(g.id, ch) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(chName(ch), color = if (here) C.amber else C.text, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (s.lockOf(g.id, ch) != null) Icon(Icons.Filled.Lock, "Locked", Modifier.size(12.dp), tint = C.amber)
                    Text("$n", color = C.dim, fontFamily = Mono, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun Groups(s: RadioState, a: Actions, toContacts: () -> Unit, open: (String, Int) -> Unit) {
    if (s.groups.isEmpty() && s.invites.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Label("No groups yet")
            Spacer(Modifier.height(8.dp))
            Body("Pick people in Contacts to create one.", color = C.faint, size = 13.sp)
            Spacer(Modifier.height(16.dp))
            Btn("Open contacts", onClick = toContacts)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(2.dp)) }
        items(s.invites, key = { "i" + it.group.id }) { inv ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x1434D17B)).border(1.dp, C.green, RoundedCornerShape(8.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Invitation", color = C.green, size = 10.sp)
                Body("${inv.fromName} added you to ${inv.group.name}", weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Decline", Modifier.weight(1f)) { a.decline(inv.group.id) }
                    Btn("Join", Modifier.weight(1f), primary = true) { a.accept(inv.group.id); open(inv.group.id, 0) }
                }
            }
        }
        items(s.groups, key = { it.id }) { g -> GroupCard(s, g, open) }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Label(title); content() }
}

@Composable
fun SettingsScreen(s: RadioState, p: Perms, mode: String, server: String, a: Actions, onBack: () -> Unit) {
    var name by remember { mutableStateOf(s.me?.name ?: "") }
    var srv by remember { mutableStateOf(server) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Section("Call sign") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(name, { name = it.take(24) }, "Name", Modifier.weight(1f))
                    Btn("Save", enabled = name.isNotBlank() && name.trim() != s.me?.name) { a.setName(name) }
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
                    else -> "Internet first. When there is none, nearby phones are found and linked automatically."
                }, color = C.dim, size = 12.sp)
            }
            Section("End-of-transmission beep") {
                Segments(listOf("1" to "On", "0" to "Off"), if (s.beep) "1" else "0", Modifier.width(180.dp)) { a.setBeep(it == "1") }
            }
            Section("Relay server") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(srv, { srv = it.trim() }, "Default free relay", Modifier.weight(1f))
                    Btn("Apply", enabled = srv != server) { a.setServer(srv) }
                }
                Body("A free public MQTT relay passes encrypted packets between phones. Everyone in a group must use the same server. Leave empty for the default.", color = C.dim, size = 12.sp)
            }
            Section("Permissions") { PermList(p, a) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
