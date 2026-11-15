package app.pttradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.TextMsg
import app.pttradio.core.chName
import app.pttradio.net.NearbyInfo
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog

/** The talk key: a wide rubber side-key with grip ridges, like the PTT bar on a handheld radio. */
@Composable
fun PttKey(tx: Boolean, rx: Boolean, modifier: Modifier = Modifier, onDown: () -> Unit, onUp: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val haptic = LocalHapticFeedback.current
    DisposableEffect(Unit) { onDispose { if (pressed) up() } }
    val hot = tx
    Box(
        modifier.height(136.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    down()
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                    pressed = false
                    up()
                }
            }
            .drawBehind {
                val r = 22.dp.toPx()
                // bezel
                drawRoundRect(Color(0xFF040506), cornerRadius = CornerRadius(r))
                drawRoundRect(Color(0xFF2A2F35), cornerRadius = CornerRadius(r), style = Stroke(1.dp.toPx()))
                // key cap
                val inset = (if (pressed) 9 else 6).dp.toPx()
                val capSize = Size(size.width - inset * 2, size.height - inset * 2)
                val cr = CornerRadius(r - inset / 2)
                val cap = when {
                    hot -> Brush.verticalGradient(listOf(Color(0xFF6E1A13), Color(0xFF3A0E0A)))
                    pressed -> Brush.verticalGradient(listOf(Color(0xFF1B1E22), Color(0xFF23272C)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF30353B), Color(0xFF1A1D21)))
                }
                drawRoundRect(cap, Offset(inset, inset), capSize, cr)
                drawRoundRect(if (hot) Color(0xFFFF4B3E) else Color(0xFF3B4149), Offset(inset, inset), capSize, cr, style = Stroke(1.dp.toPx()))
                // grip ridges on both ends
                val ridgeW = 34.dp.toPx()
                val gap = 9.dp.toPx()
                val rows = 7
                val top = size.height / 2 - gap * (rows - 1) / 2
                for (side in 0..1) {
                    val x0 = if (side == 0) inset + 18.dp.toPx() else size.width - inset - 18.dp.toPx() - ridgeW
                    for (i in 0 until rows) {
                        val y = top + i * gap
                        drawLine(Color(0xAA000000), Offset(x0, y), Offset(x0 + ridgeW, y), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(if (hot) Color(0x55FF8A80) else Color(0x33FFFFFF), Offset(x0, y + 2.dp.toPx()), Offset(x0 + ridgeW, y + 2.dp.toPx()), 1.dp.toPx())
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (tx) C.red else if (rx) C.green else Color(0xFF3B4149), 9.dp)
            Text(if (tx) "TRANSMITTING" else "PUSH TO TALK", color = if (tx) Color.White else C.text, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Label(if (tx) "Release to mute" else if (rx) "Channel busy" else "Hold to speak", color = if (tx) Color(0xFFFFB4AD) else C.dim, size = 10.sp)
        }
    }
}

@Composable
private fun Lcd(s: RadioState, near: NearbyInfo, now: Long) {
    val g = s.group ?: return
    val lock = s.lockOf(g.id, s.tunedCh)
    val occ = s.occupants(g.id, s.tunedCh)
    val (stColor, stText) = when {
        s.transmitting -> C.red to "TX ▶ TRANSMITTING"
        s.talker != null -> C.green to "RX ◀ ${s.talker.uppercase()}"
        s.notice != null -> C.amber to s.notice
        else -> C.dim to "STANDBY"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.lcd).border(1.dp, C.line, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Without internet: which radio carries the link and how strongly the other phone is received.
            val direct = near.peers.entries.filter { it.key in occ }.ifEmpty { near.peers.entries }
            val nearText = when {
                direct.size == 1 -> direct.first().value.let { "${it.tech} ${it.pct}%" }
                direct.size > 1 -> direct.groupBy { it.value.tech }.entries.joinToString(" · ") { (t, l) -> "$t ${l.minOf { it.value.pct }}–${l.maxOf { it.value.pct }}%" }
                else -> "NEARBY"
            }
            val (lc, lt) = when { s.netUp -> C.green to "INTERNET"; s.nearbyUp -> C.amber to nearText; else -> C.red to "NO LINK" }
            Dot(lc, 6.dp); Spacer(Modifier.width(6.dp)); Label(lt, size = 10.sp)
            Spacer(Modifier.weight(1f))
            Label("${occ.size} on channel", size = 10.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(chName(s.tunedCh), color = C.amber, fontFamily = Mono, fontSize = 50.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            if (lock != null) Icon(Icons.Filled.Lock, "Locked", Modifier.padding(start = 12.dp).size(24.dp), tint = C.amber)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(stColor, 8.dp); Spacer(Modifier.width(8.dp))
            Text(stText, color = stColor, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Body(occ.joinToString(" · ") { s.nameOf(it) }.ifEmpty { "—" } + (lock?.let { "   locked by ${s.nameOf(it.by)}" } ?: ""), color = C.faint, size = 12.sp, maxLines = 1)
    }
}

@Composable
private fun MessageRow(m: TextMsg, now: Long, a: Actions) {
    val left = ((m.expires - now) / 1000).coerceAtLeast(0)
    val frac = ((m.expires - now).toFloat() / (m.expires - m.at).coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Label(if (m.mine) "You" else m.from, Modifier.width(72.dp).padding(top = 3.dp), color = if (m.mine) C.amber else C.green, size = 10.sp)
            val geo = m.geo
            val img = m.image
            when {
                img != null -> {
                    var full by remember { mutableStateOf(false) }
                    val bmp = remember(m.id) { try { BitmapFactory.decodeByteArray(img, 0, img.size)?.asImageBitmap() } catch (e: Exception) { null } }
                    if (bmp == null) Body("Picture could not be shown", Modifier.weight(1f), color = C.dim, size = 14.sp)
                    else {
                        Box(Modifier.weight(1f)) {
                            Image(bmp, "Picture from ${m.from}", Modifier.heightIn(max = 170.dp).clip(RoundedCornerShape(6.dp)).clickable { full = true }, contentScale = ContentScale.Fit)
                        }
                        if (full) Dialog(onDismissRequest = { full = false }) {
                            Image(bmp, "Picture from ${m.from}", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { full = false }, contentScale = ContentScale.Fit)
                        }
                    }
                }
                geo != null -> Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(C.raised).border(1.dp, C.line, RoundedCornerShape(6.dp))
                    .clickable { a.openMap(geo.first, geo.second, m.from) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, Modifier.size(20.dp), tint = C.amber)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(String.format(java.util.Locale.US, "%.5f, %.5f", geo.first, geo.second), color = C.text, fontFamily = Mono, fontSize = 13.sp)
                        Label(if (geo.third > 0) "Accurate to ${geo.third} m · tap for map" else "Tap for map", size = 10.sp)
                    }
                }
                else -> Body(m.text, Modifier.weight(1f), size = 14.sp)
            }
            Text("${left / 60}:${(left % 60).toString().padStart(2, '0')}", Modifier.padding(start = 8.dp, top = 2.dp), color = C.faint, fontFamily = Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)) { Box(Modifier.fillMaxWidth(frac).height(1.dp).background(C.dim)) }
    }
}

@Composable
fun RadioScreen(s: RadioState, near: NearbyInfo, a: Actions, now: Long, onBack: () -> Unit) {
    val g = s.group
    if (g == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Radio", onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label("No group tuned") }
        }
        return
    }
    val lock = s.lockOf(g.id, s.tunedCh)
    var showCh by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { a.sendText(text); text = "" } }

    Column(Modifier.fillMaxSize()) {
        TopBar(g.name, onBack) {
            Icon(Icons.Filled.Lock, if (lock != null) "Unlock channel" else "Lock channel",
                Modifier.clip(RoundedCornerShape(20.dp)).clickable { if (lock != null) a.unlock() else a.lock() }.padding(10.dp).size(22.dp),
                tint = if (lock != null) C.amber else C.faint)
            Icon(Icons.Filled.Person, "Members", Modifier.clip(RoundedCornerShape(20.dp)).clickable { showMembers = true }.padding(10.dp).size(22.dp), tint = C.text)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Lcd(s, near, now)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("◀") { a.tune(g.id, if (s.tunedCh == 0) RadioCore.MAX_CH else s.tunedCh - 1) }
                Btn("Channels", Modifier.weight(1f)) { showCh = true }
                Btn("▶") { a.tune(g.id, if (s.tunedCh == RadioCore.MAX_CH) 0 else s.tunedCh + 1) }
            }
            Spacer(Modifier.height(6.dp))
            if (s.messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Label("Messages vanish after ${if (s.ttlSec >= 60) "${s.ttlSec / 60} min" else "${s.ttlSec} s"}", color = C.faint, size = 10.sp)
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true) {
                items(s.messages.reversed(), key = { it.id }) { MessageRow(it, now, a) }
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.LocationOn, "Share my location", Modifier.clip(RoundedCornerShape(6.dp)).background(C.raised).clickable { a.shareLocation() }.padding(11.dp).size(22.dp), tint = C.text)
                Icon(Icons.Filled.Add, "Send a picture", Modifier.clip(RoundedCornerShape(6.dp)).background(C.raised).clickable { a.pickImage() }.padding(11.dp).size(22.dp), tint = C.text)
                Field(text, { text = it.take(500) }, "Message", Modifier.weight(1f), onDone = send)
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(if (text.isBlank()) C.raised else C.amber).clickable(enabled = text.isNotBlank(), onClick = send),
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.Send, "Send", Modifier.size(20.dp), tint = if (text.isBlank()) C.faint else C.bg) }
            }
        }
        PttKey(s.transmitting, s.talker != null, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), a::pttDown, a::pttUp)
    }

    if (showCh) Sheet("${g.name} · channels", { showCh = false }) {
        (0..RadioCore.MAX_CH).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch ->
                    val here = ch == s.tunedCh
                    val n = s.occupants(g.id, ch).size
                    val locked = s.lockOf(g.id, ch) != null
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (here) C.amber else C.raised).border(1.dp, if (here) C.amber else C.line, RoundedCornerShape(6.dp))
                        .clickable { a.tune(g.id, ch); showCh = false }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (ch == 0) "MAIN" else ch.toString().padStart(2, '0'), color = if (here) C.bg else C.text, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (locked) Icon(Icons.Filled.Lock, "Locked", Modifier.size(10.dp), tint = if (here) C.bg else C.amber)
                            Text(if (n > 0) "$n" else "·", color = if (here) C.bg else C.dim, fontFamily = Mono, fontSize = 11.sp)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Body("Main is the group room. Move to a channel for a side conversation, bring people in, and lock it.", color = C.dim, size = 12.sp)
    }

    if (showMembers) Sheet("Members · bring to ${chName(s.tunedCh)}", { showMembers = false }) {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            g.members.forEach { m ->
                val p = s.presence[g.id]?.get(m.id)
                val me = m.id == s.me?.id
                val here = p?.ch == s.tunedCh
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (here) C.green else if (p != null) C.amber else C.faint)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Body(m.name + if (me) " (you)" else "", weight = FontWeight.Medium, maxLines = 1)
                        Label(when { p != null -> chName(p.ch); !m.joined -> "Invited"; else -> "Off air" }, size = 10.sp)
                    }
                    if (!me && !here && m.joined) Btn("Bring") { a.summon(m.id); showMembers = false }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Leave", Modifier.weight(1f), danger = true) { showMembers = false; confirmLeave = true }
            Btn("Add people", Modifier.weight(1f)) { showMembers = false; showAdd = true }
        }
    }

    if (showAdd) {
        var sel by remember { mutableStateOf(setOf<String>()) }
        val candidates = visibleUsers(s, now).filter { u -> g.members.none { it.id == u.id } }
        Sheet("Add to ${g.name}", { showAdd = false }) {
            if (candidates.isEmpty()) Body("Everyone you can see is already in this group.", color = C.dim, size = 13.sp)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { u -> UserRow(u, now, u.id in sel, near.peers[u.id]) { sel = if (u.id in sel) sel - u.id else sel + u.id } }
            }
            Btn("Invite · ${sel.size}", Modifier.fillMaxWidth(), primary = true, enabled = sel.isNotEmpty()) { a.addMembers(g.id, sel.toList()); showAdd = false }
        }
    }

    if (confirmLeave) Sheet("Leave ${g.name}?", { confirmLeave = false }) {
        Body("You will stop receiving this group. Someone has to invite you again to return.", color = C.dim, size = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Stay", Modifier.weight(1f)) { confirmLeave = false }
            Btn("Leave", Modifier.weight(1f), danger = true) { confirmLeave = false; a.leave(g.id); onBack() }
        }
    }
}
