package app.pttradio.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.pttradio.core.RadioCore
import app.pttradio.core.RadioState
import app.pttradio.core.TextMsg
import app.pttradio.core.chName
import app.pttradio.net.NearbyInfo
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The round talk key. Hold to speak, release to mute. While sending, rings radiate outward and a ring of bars
 * around the key moves with the voice; while receiving the rings move inward in green.
 */
@Composable
fun PttKey(
    tx: Boolean, rx: Boolean, modifier: Modifier = Modifier,
    level: StateFlow<Float>? = null, recording: Boolean = false,
    title: String? = null, hint: String? = null,
    onDown: () -> Unit, onUp: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val haptic = LocalHapticFeedback.current
    DisposableEffect(Unit) { onDispose { if (pressed) up() } }
    val active = tx || rx
    val accent = when { recording -> C.amber; tx -> C.red; rx -> C.green; else -> C.accent }
    val inf = rememberInfiniteTransition(label = "ptt")
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val raw = level?.collectAsState()?.value ?: 0f
    val lv by animateFloatAsState(if (active) raw else 0f, tween(90), label = "level")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(80), label = "press")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(196.dp).drawBehind {
            val c = Offset(size.width / 2, size.height / 2)
            val r0 = 68.dp.toPx() * scale
            val outer = size.minDimension / 2
            if (active) for (k in 0 until 3) {
                var p = (phase + k / 3f) % 1f
                if (!tx) p = 1f - p
                drawCircle(accent.copy(alpha = (1f - p) * 0.55f), r0 + (outer - r0) * p, c, style = Stroke(2.dp.toPx()))
            }
            // ring of bars: a quiet grip texture at rest, a live level meter while talking or listening
            val n = 40
            for (i in 0 until n) {
                val ang = 2 * PI * i / n
                val wob = 0.5f + 0.5f * sin(6.283f * (phase * 2.2f + i * 0.17f)).toFloat()
                val len = if (active) (3.dp.toPx() + lv * (6.dp.toPx() + 14.dp.toPx() * wob)) else 3.dp.toPx()
                val start = r0 + 7.dp.toPx()
                val dx = cos(ang).toFloat(); val dy = sin(ang).toFloat()
                drawLine(if (active) accent.copy(alpha = 0.5f + 0.5f * lv) else Color(0xFF2E383E), Offset(c.x + dx * start, c.y + dy * start),
                    Offset(c.x + dx * (start + len), c.y + dy * (start + len)), 2.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(136.dp * scale).clip(CircleShape)
                .background(when {
                    recording -> Brush.verticalGradient(listOf(Color(0xFF6A4C12), Color(0xFF3A290A)))
                    tx -> Brush.verticalGradient(listOf(Color(0xFFE5483B), Color(0xFF9C2219)))
                    rx -> Brush.verticalGradient(listOf(Color(0xFF1E8F55), Color(0xFF0F5232)))
                    else -> Brush.verticalGradient(listOf(Color(0xFF2A3439), Color(0xFF182024)))
                })
                .border(2.dp, if (active) accent else Color(0xFF34424A), CircleShape)
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
                }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(width = 40.dp, height = 16.dp)) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val col = if (active) Color.White else C.accent
                        drawCircle(col, 3.dp.toPx(), c)
                        for (k in 1..2) {
                            val on = !active || ((phase * 3).toInt() % 3) >= k - 1
                            val rr = (4 + k * 5).dp.toPx()
                            for (st in listOf(-50f, 130f))
                                drawArc(col.copy(alpha = if (on) 0.95f else 0.3f), st, 100f, false, Offset(c.x - rr, c.y - rr),
                                    androidx.compose.ui.geometry.Size(rr * 2, rr * 2), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(title ?: when { recording -> "RECORDING"; tx -> "TALKING"; rx -> "LISTENING"; else -> "PUSH\nTO TALK" },
                        color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
                }
            }
        }
        Text(hint ?: when { recording -> "No link · sends when connected"; tx -> "Release to stop"; rx -> "Wait for your turn"; else -> "Hold to talk" },
            color = if (active) accent else C.dim, fontSize = 13.sp)
    }
}

@Composable
private fun Bubble(m: TextMsg, now: Long, group: Boolean, a: Actions) {
    val left = ((m.expires - now) / 1000).coerceAtLeast(0)
    val time = remember(m.at, m.pending) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.at)) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 290.dp).clip(RoundedCornerShape(14.dp)).background(if (m.mine) C.mine else C.theirs).padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (group && !m.mine) Text(m.from, color = nameColor(m.fromId), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            val geo = m.geo
            val img = m.image
            when {
                img != null -> {
                    var full by remember { mutableStateOf(false) }
                    val bmp = remember(m.id) { try { BitmapFactory.decodeByteArray(img, 0, img.size)?.asImageBitmap() } catch (e: Exception) { null } }
                    if (bmp == null) Text("Picture could not be shown", color = C.dim, fontSize = 14.sp)
                    else {
                        Image(bmp, "Picture from ${m.from}", Modifier.padding(top = 2.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).clickable { full = true }, contentScale = ContentScale.Fit)
                        if (full) Dialog(onDismissRequest = { full = false }) {
                            Image(bmp, "Picture from ${m.from}", Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { full = false }, contentScale = ContentScale.Fit)
                        }
                    }
                }
                geo != null -> Row(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x22000000)).clickable { a.openMap(geo.first, geo.second, m.from) }
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, Modifier.size(22.dp), tint = C.red)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Location", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(if (geo.third > 0) "Accurate to ${geo.third} m · tap to open map" else "Tap to open map", color = C.dim, fontSize = 12.sp)
                    }
                }
                m.voiceMs > 0 -> Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (m.voice != null) C.accent else C.raised).clickable(enabled = m.voice != null) { a.playVoice(m.id) },
                        contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(22.dp), tint = if (m.voice != null) C.bg else C.dim) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Voice · ${((m.voiceMs + 500) / 1000).coerceAtLeast(1)} s", color = C.text, fontSize = 15.sp)
                        Text(when { m.pending -> "Recorded offline · sends when connected"; m.dnd -> "Arrived while on Do not disturb"; m.voice != null -> "Tap to play again"; else -> "Recorded while offline" },
                            color = C.dim, fontSize = 12.sp)
                    }
                }
                else -> Text(linked(m.text), color = C.text, fontSize = 16.sp, lineHeight = 21.sp)
            }
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (m.pending) "🕓 not sent yet" else "$time · ⏳${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                    color = if (m.pending) C.amber else Color(0xAAE9EDEF), fontSize = 11.sp)
            }
        }
    }
}

/** Web links in a message become tappable. */
private fun linked(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in Regex("https?://\\S+").findAll(text)) {
        append(text.substring(i, m.range.first))
        withLink(LinkAnnotation.Url(m.value, TextLinkStyles(SpanStyle(color = C.blue, textDecoration = TextDecoration.Underline)))) { append(m.value) }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

/** True while the on-screen keyboard is up. */
@Composable
fun keyboardVisible(): Boolean {
    val view = LocalView.current
    var shown by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            shown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(l)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(l) }
    }
    return shown
}

/** Small camera glyph (the bundled icon set has none). */
@Composable
private fun CameraButton(onClick: () -> Unit) {
    Canvas(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(10.dp).size(22.dp)) {
        val w = size.width; val h = size.height
        val stroke = Stroke(1.8.dp.toPx())
        drawRoundRect(C.dim, androidx.compose.ui.geometry.Offset(1f, h * 0.25f), androidx.compose.ui.geometry.Size(w - 2f, h * 0.68f),
            androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = stroke)
        drawRect(C.dim, androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.1f), androidx.compose.ui.geometry.Size(w * 0.36f, h * 0.16f))
        drawCircle(C.dim, w * 0.2f, androidx.compose.ui.geometry.Offset(w / 2, h * 0.59f), style = stroke)
    }
}

private val nameColors = listOf(0xFF53BDEB, 0xFFFF9F43, 0xFFA78BFA, 0xFFF472B6, 0xFF34D399, 0xFFFBBF24, 0xFF60A5FA).map { Color(it) }
private fun nameColor(id: String) = nameColors[(id.hashCode() and 0x7fffffff) % nameColors.size]

@Composable
fun RadioScreen(s: RadioState, near: NearbyInfo, level: StateFlow<Float>?, a: Actions, now: Long, locked: Set<String> = emptySet(), onBack: () -> Unit) {
    val g = s.group ?: return
    val other = s.otherIn(g)
    val title = s.titleOf(g)
    val lock = s.lockOf(g.id, s.tunedCh)
    var showCh by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var attach by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val send = { if (text.isNotBlank()) { a.sendText(text); text = "" } }
    // follow the keyboard itself: when Back closes it, the talk button comes straight back
    val typing = keyboardVisible()
    LaunchedEffect(typing) { if (!typing) focus.clearFocus() }

    val occ = s.occupants(g.id, s.tunedCh).filter { it != s.me?.id }
    val talkingHere = s.talker != null && s.talkGid == g.id
    val subtitle = when {
        s.recording -> "Recording · sends when connected"
        s.transmitting -> "You are talking" + if (s.txQuality.isNotEmpty()) " · ${s.txQuality}" else ""
        talkingHere -> "${s.talker} is talking…"
        s.talker != null -> "${s.talker} is talking in ${s.groups.firstOrNull { it.id == s.talkGid }?.let { s.titleOf(it) } ?: "another chat"}"
        s.notice != null -> s.notice.lowercase().replaceFirstChar { it.uppercase() }
        g.direct -> (if (s.users[other]?.status == "dnd") "⛔ Do not disturb · " else "") + lastSeen(s, near, other, now)
        else -> listOfNotNull(if (s.tunedCh != 0) chName(s.tunedCh).replace("CH ", "Channel ") else null,
            "${occ.size} of ${g.members.size - 1} here", if (!s.netUp && s.nearbyUp) "nearby link" else if (!s.netUp) "no link" else null).joinToString(" · ")
    }
    val subColor = when { s.recording -> C.amber; s.transmitting -> C.red; talkingHere -> C.accent; s.notice != null -> C.amber; else -> C.dim }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- header
        Row(Modifier.fillMaxWidth().background(C.panel).height(64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ArrowBack, "Back", Modifier.clip(CircleShape).clickable(onClick = onBack).padding(12.dp).size(22.dp), tint = C.text)
            Avatar(title, other ?: g.id, 40.dp, dot = presenceDot(s, other, now), group = !g.direct, pic = picOf(s, other))
            Column(Modifier.weight(1f).padding(start = 10.dp).clickable(enabled = !g.direct) { showMembers = true }) {
                Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = subColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (g.direct) Icon(Icons.Filled.Call, "Audio call", Modifier.clip(CircleShape).clickable { a.startCall(g.id) }.padding(10.dp).size(22.dp), tint = C.text)
            if (!g.direct) {
                Text(if (s.tunedCh == 0) "Ch" else s.tunedCh.toString(), Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))
                    .clickable { showCh = true }.padding(horizontal = 10.dp, vertical = 5.dp), color = if (s.tunedCh == 0) C.dim else C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (s.tunedCh != 0) Icon(Icons.Filled.Lock, if (lock != null) "Unlock channel" else "Lock channel",
                    Modifier.clip(CircleShape).clickable { if (lock != null) a.unlock() else a.lock() }.padding(10.dp).size(20.dp), tint = if (lock != null) C.amber else C.faint)
            }
            Box {
                Icon(Icons.Filled.MoreVert, "Menu", Modifier.clip(CircleShape).clickable { menu = true }.padding(10.dp).size(22.dp), tint = C.text)
                DropdownMenu(menu, { menu = false }, Modifier.background(C.raised)) {
                    if (!g.direct) {
                        DropdownMenuItem({ Text("Members", color = C.text) }, { menu = false; showMembers = true })
                        DropdownMenuItem({ Text("Add people", color = C.text) }, { menu = false; showAdd = true })
                        DropdownMenuItem({ Text("Channels", color = C.text) }, { menu = false; showCh = true })
                    }
                    DropdownMenuItem({ Text("Add to home screen", color = C.text) }, { menu = false; a.addToHome(g.id) })
                    DropdownMenuItem({ Text(if (g.id in locked) "Unlock chat" else "Lock chat", color = C.text) }, { menu = false; a.lockChats(setOf(g.id), g.id !in locked) })
                    DropdownMenuItem({ Text(if (g.direct) "Delete chat" else "Leave group", color = C.red) }, { menu = false; confirmLeave = true })
                }
            }
        }

        // ---------------------------------------------------------------- messages
        Box(Modifier.weight(1f).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { focus.clearFocus() }) {
            if (s.messages.isEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (g.direct) "Talk to $title" else "Talk to $title", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("Messages disappear ${if (s.ttlSec >= 60) "${s.ttlSec / 60} min" else "${s.ttlSec} s"} after they are sent. Nothing is saved.",
                    color = C.dim, fontSize = 13.sp, textAlign = TextAlign.Center)
            } else LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
                items(s.messages.reversed(), key = { it.id }) { Bubble(it, now, !g.direct, a) }
            }
        }

        // ---------------------------------------------------------------- input
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(C.panel).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Icon(Icons.Filled.Add, "Attach", Modifier.clip(CircleShape).clickable { attach = true }.padding(8.dp).size(22.dp), tint = C.dim)
                    DropdownMenu(attach, { attach = false }, Modifier.background(C.raised)) {
                        DropdownMenuItem({ Text("Picture from gallery" + if (s.netUp) " (HD)" else " (SD)", color = C.text) }, { attach = false; a.pickImage() })
                        DropdownMenuItem({ Text("My location", color = C.text) }, { attach = false; a.shareLocation() })
                    }
                }
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (text.isEmpty()) Text("Message", color = C.dim, fontSize = 16.sp)
                    BasicTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = C.text, fontSize = 16.sp), cursorBrush = SolidColor(C.accent), maxLines = 5)
                }
                CameraButton { a.takePhoto() }
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (text.isBlank()) C.raised else C.accent).clickable(enabled = text.isNotBlank(), onClick = send),
                contentAlignment = Alignment.Center) { Icon(Icons.Filled.Send, "Send", Modifier.size(22.dp), tint = if (text.isBlank()) C.faint else C.bg) }
        }
        // while typing only the conversation and the keyboard are on screen
        if (!typing) PttKey(s.transmitting, talkingHere, Modifier.fillMaxWidth().padding(bottom = 10.dp), level, s.recording, onDown = a::pttDown, onUp = a::pttUp)
    }

    if (showCh) Sheet("$title · channels", { showCh = false }) {
        Body("Main is where everyone in the group is. Use a channel for a side conversation; you can bring people in and lock it.", color = C.dim, size = 12.sp)
        (0..RadioCore.MAX_CH).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ch ->
                    val here = ch == s.tunedCh
                    val n = s.occupants(g.id, ch).size
                    val locked = s.lockOf(g.id, ch) != null
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (here) C.accent else C.raised)
                        .clickable { a.tune(g.id, ch); showCh = false }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (ch == 0) "Main" else ch.toString(), color = if (here) C.bg else C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (locked) Icon(Icons.Filled.Lock, "Locked", Modifier.size(10.dp), tint = if (here) C.bg else C.amber)
                            Text(if (n > 0) "$n" else "·", color = if (here) C.bg else C.dim, fontSize = 11.sp)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    if (showMembers) Sheet("${g.members.size} members", { showMembers = false }) {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            g.members.forEach { m ->
                val p = s.presence[g.id]?.get(m.id)
                val me = m.id == s.me?.id
                val here = p?.ch == s.tunedCh
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.name, m.id, 40.dp, dot = presenceDot(s, m.id, now), pic = picOf(s, m.id))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Body(m.name + if (me) " (you)" else "", weight = FontWeight.Medium, maxLines = 1)
                        Text(when { p != null -> if (p.ch == 0) "In the group" else "On channel ${p.ch}"; !m.joined -> "Invited"; else -> "Not here" }, color = C.dim, fontSize = 12.sp)
                    }
                    if (!me && !here && m.joined && s.tunedCh != 0) Btn("Bring here") { a.summon(m.id); showMembers = false }
                }
            }
        }
        Btn("Add people", Modifier.fillMaxWidth()) { showMembers = false; showAdd = true }
    }

    if (showAdd) {
        var sel by remember { mutableStateOf(setOf<String>()) }
        val candidates = onAppNow(s, now).filter { u -> g.members.none { it.id == u.id } }
        Sheet("Add to $title", { showAdd = false }) {
            if (candidates.isEmpty()) Body("Everyone on the app right now is already in this group.", color = C.dim, size = 13.sp)
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { u -> PersonRow(u, s, near, now, u.id in sel, true, { sel = if (u.id in sel) sel - u.id else sel + u.id }, {}) }
            }
            Btn("Add ${sel.size}", Modifier.fillMaxWidth(), primary = true, enabled = sel.isNotEmpty()) { a.addMembers(g.id, sel.toList()); showAdd = false }
        }
    }

    if (confirmLeave) Sheet(if (g.direct) "Delete chat with $title?" else "Leave $title?", { confirmLeave = false }) {
        Body(if (g.direct) "The chat is removed from this phone." else "You will stop receiving this group. Someone has to invite you again to return.", color = C.dim, size = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", Modifier.weight(1f)) { confirmLeave = false }
            Btn(if (g.direct) "Delete" else "Leave", Modifier.weight(1f), danger = true) { confirmLeave = false; a.leave(g.id); onBack() }
        }
    }
}
