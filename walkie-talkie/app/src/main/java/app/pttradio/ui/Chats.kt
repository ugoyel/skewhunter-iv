package app.pttradio.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.core.Group
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.TextMsg
import app.pttradio.core.User
import app.pttradio.net.NearbyInfo
import app.pttradio.net.SosAlert
import app.pttradio.net.SosCodes
import app.pttradio.net.SosSending

fun whenText(at: Long): String = when {
    at <= 0 -> ""
    DateUtils.isToday(at) -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
    DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault()).format(java.util.Date(at))
}

/** People on the app right now: online over internet or heard nearby. */
fun onAppNow(s: RadioState, now: Long): List<User> = s.users.values
    .filter { it.name.isNotBlank() && it.id != s.me?.id && (it.online || isNearby(it, now)) }
    .sortedWith(compareByDescending<User> { isNearby(it, now) }.thenByDescending { it.online }.thenBy { it.name.lowercase() })

fun presenceDot(s: RadioState, uid: String?, now: Long): Color? {
    val u = s.users[uid ?: return null] ?: return null
    return when { isNearby(u, now) -> C.blue; u.online -> C.green; else -> null }
}

/** How a person can be reached right now, e.g. "Nearby · Bluetooth 62%". */
fun reachText(s: RadioState, near: NearbyInfo, uid: String?, now: Long): String {
    val u = s.users[uid ?: return ""] ?: return ""
    val link = near.peers[u.id]
    val how = when { link != null -> if (link.pct >= 0) "${link.tech} ${link.pct}%" else link.tech; u.via == "lan" -> "Field Wi-Fi"; else -> "via another phone" }
    return when { isNearby(u, now) && u.online -> "Online · nearby · $how"; isNearby(u, now) -> "Nearby · $how"; u.online -> "Online"; else -> "Offline" }
}

private fun preview(m: TextMsg, group: Boolean): String {
    val body = when { m.image != null -> "Picture"; m.voiceMs > 0 -> "Voice message"; m.geo != null -> "Location"; else -> m.text }
    return (if (m.mine) "You: " else if (group) "${m.from}: " else "") + body
}

private fun activityText(s: RadioState, g: Group): String {
    val r = s.recent.firstOrNull { it.gid == g.id && it.kind != "open" } ?: return if (g.direct) "Tap to talk" else "${g.members.size} members"
    val who = if (r.mine) "You" else s.nameOf(r.uid)
    return when (r.kind) {
        "talk" -> "$who talked"; "pic" -> "$who sent a picture"; "loc" -> "$who shared a location"; "voice" -> "$who sent a voice message"
        else -> "$who sent a message"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Home(
    s: RadioState, p: Perms, near: NearbyInfo, alerts: List<SosAlert>, sending: SosSending?, a: Actions, now: Long,
    tab: Int, onTab: (Int) -> Unit, go: (Scr) -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }                 // 0 all, 1 groups, 2 people
    var picking by remember { mutableStateOf(false) }            // choosing people (for a new group)
    var sel by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var sosMenu by remember { mutableStateOf(false) }
    var newChat by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf<Group?>(null) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var deleting by remember { mutableStateOf<Group?>(null) }
    val selecting = picking || sel.isNotEmpty()
    val haptic = LocalHapticFeedback.current
    val clearSel = { sel = emptySet(); picking = false }
    fun open(gid: String) { a.tune(gid, if (s.tunedGroup == gid) s.tunedCh else 0); go(Scr.RADIO) }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- top bar
        if (selecting) Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Close, "Cancel", Modifier.clip(CircleShape).clickable { clearSel() }.padding(10.dp).size(22.dp), tint = C.text)
            Text(if (sel.isEmpty()) "Select people" else "${sel.size} selected", Modifier.weight(1f).padding(start = 8.dp), color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            val single = sel.singleOrNull()?.let { s.directWith(it) }
            if (single != null) {
                Icon(Icons.Filled.Home, "Add to home screen", Modifier.clip(CircleShape).clickable { a.addToHome(single.id); clearSel() }.padding(10.dp).size(22.dp), tint = C.text)
                Icon(Icons.Filled.Delete, "Delete chat", Modifier.clip(CircleShape).clickable { deleting = single; clearSel() }.padding(10.dp).size(22.dp), tint = C.text)
            }
            if (sel.isNotEmpty()) {
                if (s.groups.any { !it.direct }) Text("Add to…", Modifier.clip(RoundedCornerShape(18.dp)).clickable { addTo = true }.padding(horizontal = 10.dp, vertical = 8.dp), color = C.accent, fontSize = 15.sp)
                Text("New group", Modifier.clip(RoundedCornerShape(18.dp)).clickable { naming = true }.padding(horizontal = 10.dp, vertical = 8.dp), color = C.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ZeroBar", Modifier.weight(1f), color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("SOS", Modifier.clip(RoundedCornerShape(16.dp)).background(if (sending != null) C.red else Color(0x26FF4B3E)).clickable { sosMenu = true }
                .padding(horizontal = 12.dp, vertical = 6.dp), color = if (sending != null) Color.White else C.red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            val (lc, lt) = when { s.netUp -> C.green to "Online"; s.nearbyUp -> C.blue to "Nearby"; else -> C.red to "No link" }
            Row(verticalAlignment = Alignment.CenterVertically) { Dot(lc, 8.dp); Spacer(Modifier.width(5.dp)); Text(lt, color = C.dim, fontSize = 12.sp) }
            Box {
                Icon(Icons.Filled.MoreVert, "Menu", Modifier.padding(start = 4.dp).clip(CircleShape).clickable { menu = true }.padding(10.dp).size(22.dp), tint = C.text)
                DropdownMenu(menu, { menu = false }, Modifier.background(C.raised)) {
                    DropdownMenuItem({ Text("New group", color = C.text) }, { menu = false; onTab(0); picking = true })
                    DropdownMenuItem({ Text("Reconnect", color = C.text) }, { menu = false; a.reconnect() })
                    DropdownMenuItem({ Text("Settings", color = C.text) }, { menu = false; go(Scr.SETTINGS) })
                }
            }
        }

        // ---------------------------------------------------------------- search + filters
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(C.panel).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = C.dim)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(if (selecting) "Search people" else "Search groups and people", color = C.dim, fontSize = 15.sp)
                BasicTextField(q, { q = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(color = C.text, fontSize = 15.sp), cursorBrush = SolidColor(C.accent))
            }
            if (q.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", Modifier.clip(CircleShape).clickable { q = "" }.padding(4.dp).size(18.dp), tint = C.dim)
        }
        if (tab == 0 && !selecting) Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Groups", "People").forEachIndexed { i, t ->
                Text(t, Modifier.clip(RoundedCornerShape(16.dp)).background(if (filter == i) Color(0xFF103D2A) else Color.Transparent)
                    .border(1.dp, if (filter == i) Color.Transparent else C.line, RoundedCornerShape(16.dp)).clickable { filter = i }
                    .padding(horizontal = 14.dp, vertical = 6.dp), color = if (filter == i) C.accent else C.dim, fontSize = 14.sp)
            }
        }

        // ---------------------------------------------------------------- banners
        if (sending != null) Banner(C.red, "Broadcasting: ${SosCodes.of(sending.code)}",
            (if (sending.hasFix) "With your position" else "Getting your position") + " · " + ((sending.until - now) / 1000).coerceAtLeast(0).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')} left" },
            "Stop") { a.stopSos() }
        alerts.take(3).forEach { al -> AlertCard(al, now, a) }
        if (!p.allOk) Banner(C.amber, "Permissions missing", "The radio won't work properly until they are allowed.", "Fix") { go(Scr.SETUP) }
        val me = s.me
        if (me != null && s.users.values.any { it.id != me.id && it.name.equals(me.name, true) && (it.online || isNearby(it, now)) })
            Banner(C.amber, "Someone else is also called ${me.name}", "Pick a more unique name so people can tell you apart.", "Change") { go(Scr.SETTINGS) }
        if (!s.netUp && !selecting) StatusLine(s, near, now, a)

        // ---------------------------------------------------------------- content
        Box(Modifier.weight(1f)) {
            if (tab == 0 || selecting) ChatList(s, near, now, q.trim(), if (selecting) 2 else filter, sel, selecting,
                onChat = { g ->
                    val other = s.otherIn(g)
                    if (selecting) { if (other != null) sel = if (other in sel) sel - other else sel + other }
                    else open(g.id)
                },
                onChatLong = { g ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val other = s.otherIn(g)
                    if (other != null) sel = sel + other else if (!selecting) groupMenu = g
                },
                onPerson = { u -> if (selecting) sel = if (u.id in sel) sel - u.id else sel + u.id else { a.openDirect(u.id); go(Scr.RADIO) } },
                onPersonLong = { u -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); sel = sel + u.id },
                a = a, go = go)
            else RecentList(s, now, q.trim()) { r ->
                if (s.groups.any { it.id == r.gid }) open(r.gid) else { a.openDirect(r.uid); go(Scr.RADIO) }
            }
            if (tab == 0 && !selecting) Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(58.dp).clip(RoundedCornerShape(18.dp)).background(C.accent)
                .clickable { newChat = true }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, "New chat or group", Modifier.size(28.dp), tint = C.bg) }
        }

        // ---------------------------------------------------------------- bottom navigation
        if (!selecting) Row(Modifier.fillMaxWidth().background(C.bg).padding(vertical = 6.dp)) {
            val unread = s.unread.values.sum()
            listOf("Chats", "Recent").forEachIndexed { i, t ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onTab(i) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (tab == i) Color(0xFF103D2A) else Color.Transparent).padding(horizontal = 22.dp, vertical = 5.dp)) {
                        Text(if (i == 0) "💬" else "🕘", fontSize = 18.sp)
                        if (i == 0 && (unread > 0 || s.invites.isNotEmpty())) Box(Modifier.align(Alignment.TopEnd).padding(start = 30.dp).size(9.dp).clip(CircleShape).background(C.accent))
                    }
                    Text(t, color = if (tab == i) C.text else C.dim, fontSize = 13.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }

    // ---------------------------------------------------------------- sheets
    if (sosMenu) Sheet("Emergency message", { sosMenu = false }) {
        Body("Sent over Bluetooth to phones with ZeroBar in range, with no connection needed, and passed on by those phones. " +
            "Shown to your contacts only, unless you allow anyone in Settings. Your GPS position is included. With internet it also goes to your groups.",
            color = C.dim, size = 13.sp)
        Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SosCodes.text.forEachIndexed { i, t -> Btn(t, Modifier.fillMaxWidth(), primary = i == 0) { sosMenu = false; a.sendSos(i) } }
        }
    }
    if (newChat) Sheet("New chat", { newChat = false }) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { newChat = false; picking = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, null, Modifier.size(24.dp), tint = C.bg) }
            Spacer(Modifier.width(14.dp))
            Body("New group", weight = FontWeight.SemiBold)
        }
        Label("On ZeroBar now", Modifier.padding(top = 4.dp))
        val people = onAppNow(s, now)
        if (people.isEmpty()) Body("Nobody else is on the app right now.", color = C.dim, size = 13.sp)
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            people.forEach { u -> PersonRow(u, s, near, now, false, false, { newChat = false; a.openDirect(u.id); go(Scr.RADIO) }, {}) }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        val create = { a.createGroup(name, sel.toList()); clearSel(); naming = false; go(Scr.RADIO) }
        Sheet("New group", { naming = false }) {
            Body(sel.joinToString(", ") { s.nameOf(it) }, color = C.dim, size = 13.sp, maxLines = 3)
            Field(name, { name = it.take(24) }, "Group name", Modifier.fillMaxWidth(), onDone = { if (name.isNotBlank()) create() })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", Modifier.weight(1f)) { naming = false }
                Btn("Create", Modifier.weight(1f), primary = true, enabled = name.isNotBlank(), onClick = create)
            }
        }
    }
    if (addTo) Sheet("Add ${sel.size} to…", { addTo = false }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            s.groups.filter { !it.direct }.sortedByDescending { s.lastAt(it.id) }.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { a.addMembers(g.id, sel.toList()); addTo = false; clearSel() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(g.name, g.id, 40.dp, group = true); Spacer(Modifier.width(12.dp)); Body(g.name, maxLines = 1)
                }
            }
        }
    }
    groupMenu?.let { g ->
        Sheet(s.titleOf(g), { groupMenu = null }) {
            Btn("Rename group", Modifier.fillMaxWidth()) { groupMenu = null; renaming = g }
            Btn("Add talk widget to home screen", Modifier.fillMaxWidth()) { groupMenu = null; a.addToHome(g.id) }
            Btn("Delete group", Modifier.fillMaxWidth(), danger = true) { groupMenu = null; deleting = g }
        }
    }
    renaming?.let { g ->
        var name by remember(g.id) { mutableStateOf(g.name) }
        Sheet("Rename group", { renaming = null }) {
            Field(name, { name = it.take(24) }, "Group name", Modifier.fillMaxWidth(), onDone = { a.renameGroup(g.id, name); renaming = null })
            Body("Everyone in the group sees the new name.", color = C.dim, size = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", Modifier.weight(1f)) { renaming = null }
                Btn("Save", Modifier.weight(1f), primary = true, enabled = name.isNotBlank()) { a.renameGroup(g.id, name); renaming = null }
            }
        }
    }
    deleting?.let { g ->
        Sheet("Delete ${s.titleOf(g)}?", { deleting = null }) {
            Body(if (g.direct) "The chat is removed from this phone." else
                "The group is removed from this phone and the others see that you left. Someone in the group has to invite you again to come back.",
                color = C.dim, size = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Keep", Modifier.weight(1f)) { deleting = null }
                Btn("Delete", Modifier.weight(1f), danger = true) { a.leave(g.id); deleting = null }
            }
        }
    }
}

@Composable
private fun Banner(color: Color, title: String, text: String, action: String, onAction: () -> Unit) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.13f))
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Body(title, weight = FontWeight.SemiBold, size = 14.sp, maxLines = 2)
            Body(text, color = C.dim, size = 12.sp, maxLines = 2)
        }
        Text(action, Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlertCard(al: SosAlert, now: Long, a: Actions) {
    val ago = ((now - al.firstAt) / 60_000).let { if (it < 1) "just now" else "$it min ago" }
    val heard = when { al.hops > 0 -> "passed on by ${al.hops} phone${if (al.hops > 1) "s" else ""}"; al.pct >= 0 -> "signal ${al.pct}%"; else -> "" }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x26FF4B3E))
        .border(1.dp, C.red, RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Body("🆘 ${al.name}: ${SosCodes.of(al.code)}", weight = FontWeight.SemiBold, size = 15.sp, maxLines = 2)
        Body(listOf(ago, heard).filter { it.isNotEmpty() }.joinToString(" · "), color = C.dim, size = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (al.lat != null && al.lon != null) Btn("Show on map", Modifier.weight(1f), primary = true) { a.openMap(al.lat, al.lon, al.name) }
            Btn("Dismiss", Modifier.weight(1f)) { a.dismissAlert(al.key) }
        }
    }
}

/** Shown only without internet: how far the local radios got, and what to fix if they are blocked. */
@Composable
private fun StatusLine(s: RadioState, near: NearbyInfo, now: Long, a: Actions) {
    val nearN = s.users.values.count { it.id != s.me?.id && isNearby(it, now) }
    val text = when {
        !near.running -> "No internet · nearby radio is off"
        near.linked > 0 || nearN > 0 -> "No internet · $nearN nearby"
        near.found > 0 -> "No internet · found ${near.found}, connecting…"
        else -> "No internet · looking for phones nearby…"
    }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
        Text(text, color = C.dim, fontSize = 12.sp)
        if (near.linked == 0 && near.lastFail != null) Text("Last try: ${near.lastFail}", color = C.faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (near.problem != null) Row(Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22FFB020)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Body(near.detail ?: "The nearby link is not working.", Modifier.weight(1f), size = 13.sp)
            Text(if (near.problem == "err") "Retry" else "Fix", Modifier.clip(RoundedCornerShape(16.dp)).clickable { a.fixNearby(near.problem) }.padding(8.dp), color = C.amber, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(
    s: RadioState, near: NearbyInfo, now: Long, q: String, filter: Int, sel: Set<String>, selecting: Boolean,
    onChat: (Group) -> Unit, onChatLong: (Group) -> Unit, onPerson: (User) -> Unit, onPersonLong: (User) -> Unit, a: Actions, go: (Scr) -> Unit,
) {
    fun hit(name: String) = q.isEmpty() || name.contains(q, ignoreCase = true)
    val chats = s.groups.filter { g -> hit(s.titleOf(g)) && when (filter) { 1 -> !g.direct; 2 -> g.direct; else -> true } }
        .sortedByDescending { s.lastAt(it.id) }
    val withChat = s.groups.filter { it.direct }.mapNotNull { s.otherIn(it) }.toSet()
    val people = if (filter == 1) emptyList() else onAppNow(s, now).filter { it.id !in withChat && hit(it.name) }
    val invites = if (selecting || filter == 2) emptyList() else s.invites.filter { hit(it.group.name) }
    if (chats.isEmpty() && people.isEmpty() && invites.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Body(if (q.isNotEmpty()) "Nothing matches \"$q\"" else if (selecting) "Nobody to pick yet" else "No chats yet", weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Body("People show up here while they are on the app: over internet, or nearby with Bluetooth and Location on.", color = C.dim, size = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(invites, key = { "i" + it.group.id }) { inv ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(inv.group.name, inv.group.id, group = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Body(inv.group.name, weight = FontWeight.SemiBold, maxLines = 1)
                    Body("${inv.fromName} added you", color = C.dim, size = 13.sp, maxLines = 1)
                }
                Text("Join", Modifier.clip(RoundedCornerShape(16.dp)).background(C.accent).clickable { a.accept(inv.group.id); go(Scr.RADIO) }
                    .padding(horizontal = 14.dp, vertical = 7.dp), color = C.bg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Icon(Icons.Filled.Close, "Decline", Modifier.padding(start = 6.dp).clip(CircleShape).clickable { a.decline(inv.group.id) }.padding(6.dp).size(20.dp), tint = C.dim)
            }
        }
        items(chats, key = { it.id }) { g ->
            val other = s.otherIn(g)
            val title = s.titleOf(g)
            val list = if (g.id == s.tunedGroup) s.messages else s.inbox["${g.id}/0"] ?: emptyList()
            val last = list.lastOrNull()
            val talking = s.talkGid == g.id && s.talker != null
            val sub = when {
                talking -> "${s.talker} is talking…"
                last != null -> preview(last, !g.direct)
                g.direct -> reachText(s, near, other, now).takeIf { it.isNotEmpty() && it != "Offline" } ?: activityText(s, g)
                else -> activityText(s, g)
            }
            val unread = s.unread[g.id] ?: 0
            val selected = other != null && other in sel
            Row(Modifier.fillMaxWidth().background(if (selected) Color(0x2221C063) else Color.Transparent)
                .combinedClickable(onClick = { onChat(g) }, onLongClick = { onChatLong(g) }).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Avatar(title, other ?: g.id, dot = presenceDot(s, other, now), group = !g.direct)
                    if (selected) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = C.bg)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(whenText(s.lastAt(g.id)), color = if (unread > 0) C.accent else C.dim, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sub, Modifier.weight(1f), color = if (talking) C.accent else C.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (unread > 0) Box(Modifier.padding(start = 6.dp).heightIn(min = 20.dp).clip(CircleShape).background(C.accent).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center) { Text("$unread", color = C.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        if (people.isNotEmpty()) {
            item { Text("On ZeroBar now", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp), color = C.dim, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            items(people, key = { "p" + it.id }) { u -> PersonRow(u, s, near, now, u.id in sel, true, { onPerson(u) }, { onPersonLong(u) }) }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonRow(u: User, s: RadioState, near: NearbyInfo, now: Long, selected: Boolean, check: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0x2221C063) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Avatar(u.name, u.id, 46.dp, dot = presenceDot(s, u.id, now))
            if (selected) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = C.bg)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reachText(s, near, u.id, now), color = if (isNearby(u, now)) C.blue else C.dim, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RecentList(s: RadioState, now: Long, q: String, onClick: (app.pttradio.core.Recent) -> Unit) {
    val me = s.me?.id
    val people = s.recent.filter { it.uid != me && it.kind != "open" }.distinctBy { it.uid }
        .filter { q.isEmpty() || s.nameOf(it.uid).contains(q, ignoreCase = true) }
    if (people.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Body("No recent activity", weight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Body("People you talk or message with show up here, newest first.", color = C.dim, size = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(people, key = { it.uid }) { r ->
            val g = s.groups.firstOrNull { it.id == r.gid }
            val where = if (g == null || g.direct) "" else " in ${g.name}"
            val what = when (r.kind) {
                "talk" -> if (r.mine) "You talked$where" else "Talked$where"
                "pic" -> if (r.mine) "You sent a picture$where" else "Sent a picture$where"
                "loc" -> if (r.mine) "You shared a location$where" else "Shared a location$where"
                "voice" -> "Voice message$where"
                else -> if (r.mine) "You sent a message$where" else "Sent a message$where"
            }
            Row(Modifier.fillMaxWidth().clickable { onClick(r) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.nameOf(r.uid), r.uid, dot = presenceDot(s, r.uid, now))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.nameOf(r.uid), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (r.mine) "↗ " else "↙ ") + what, color = C.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(whenText(r.at), color = C.dim, fontSize = 12.sp)
            }
        }
    }
}

/** Picker used by the widget set-up: every chat and everyone on the app now. */
@Composable
fun ChatPicker(s: RadioState, near: NearbyInfo, now: Long, onChat: (Group) -> Unit, onPerson: (User) -> Unit) {
    val withChat = s.groups.filter { it.direct }.mapNotNull { s.otherIn(it) }.toSet()
    LazyColumn(Modifier.fillMaxSize()) {
        items(s.groups.sortedByDescending { s.lastAt(it.id) }, key = { it.id }) { g ->
            val other = s.otherIn(g)
            Row(Modifier.fillMaxWidth().clickable { onChat(g) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(s.titleOf(g), other ?: g.id, dot = presenceDot(s, other, now), group = !g.direct)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.titleOf(g), color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(if (g.direct) "Person" else "Group · ${g.members.size} members", color = C.dim, fontSize = 13.sp)
                }
            }
        }
        val people = onAppNow(s, now).filter { it.id !in withChat }
        if (people.isNotEmpty()) {
            item { Text("On ZeroBar now", Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp), color = C.dim, fontSize = 13.sp) }
            items(people, key = { "p" + it.id }) { u -> PersonRow(u, s, near, now, false, false, { onPerson(u) }, {}) }
        }
    }
}
