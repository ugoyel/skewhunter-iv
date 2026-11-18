package app.pttradio.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.core.Pos
import app.pttradio.core.RadioState
import app.pttradio.core.bearing
import app.pttradio.core.compass
import app.pttradio.core.distance
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val palette = listOf(Color(0xFF53BDEB), Color(0xFFFFB74D), Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4DD0E1), Color(0xFFAED581), Color(0xFFF06292), Color(0xFFFFF176))
private fun colorOf(id: String) = palette[(id.hashCode() and 0x7fffffff) % palette.size]

fun distText(m: Double) = when { m < 1000 -> "${m.roundToInt()} m"; m < 10_000 -> String.format("%.1f km", m / 1000); else -> "${(m / 1000).roundToInt()} km" }

/** "↑ 85 m higher", "↓ 40 m lower", or "same level"; GPS height is only good to about 10–20 m. */
fun heightText(mine: Pos?, other: Pos): String? {
    if (mine == null || mine.alt.isNaN() || other.alt.isNaN()) return if (!other.alt.isNaN()) "${other.alt.roundToInt()} m above sea" else null
    val d = other.alt - mine.alt
    return when { abs(d) < 15 -> "about the same height"; d > 0 -> "↑ ${d.roundToInt()} m higher (up the hill)"; else -> "↓ ${(-d).roundToInt()} m lower (down the hill)" }
}

/** The phone's compass heading in degrees, or null without a compass. */
@Composable
private fun rememberHeading(): Float? {
    val ctx = LocalContext.current
    var heading by remember { mutableFloatStateOf(Float.NaN) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val l = object : SensorEventListener {
            val rot = FloatArray(9); val o = FloatArray(3)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, o)
                val deg = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                // smooth, and only redraw when it moved a couple of degrees
                if (heading.isNaN() || abs(((deg - heading + 540) % 360) - 180) > 2) heading = deg
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l) }
    }
    return heading.takeIf { !it.isNaN() }
}

private fun niceScale(m: Double): Double = listOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 25_000.0, 50_000.0, 100_000.0, 250_000.0, 1e6, 5e6, 2e7)
    .firstOrNull { it >= m } ?: 2e7

private fun ago(now: Long, at: Long): String { val m = (now - at) / 60_000; return when { m < 1 -> "now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ago" } }

/**
 * Group map without internet or map tiles: a radar centred on this phone, turned with the compass, showing where
 * each member is, how far, in which direction, how much higher or lower, their trails and the meeting point.
 */
@Composable
fun MapScreen(s: RadioState, now: Long, sharing: Boolean, a: Actions, onBack: () -> Unit) {
    val g = s.group ?: return
    val meId = s.me?.id ?: return
    val mine = s.positions[meId]
    val ids = g.members.map { it.id }.filter { it != meId && s.positions[it] != null }
    val meet = s.meets[g.id]
    val heading = rememberHeading()
    var turn by remember { mutableStateOf(true) }
    val tm = rememberTextMeasurer()
    val roll = s.rolls[g.id]
    Column(Modifier.fillMaxSize()) {
        TopBar("Group map · ${s.titleOf(g)}", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!sharing) Banner(C.amber, "You are not sharing your position", "Others can't see you, and distances need your position.", "Share") { a.setShare(true) }
            if (mine == null) Body(if (sharing) "Waiting for a GPS fix… go outside, under open sky." else "Turn on position sharing to see the map.", color = C.dim, size = 13.sp)
            if (mine != null) {
                val pts = ids.mapNotNull { s.positions[it] } + listOfNotNull(meet?.let { Pos(it.lat, it.lon, Double.NaN, 0, 0) })
                val far = pts.maxOfOrNull { distance(mine.lat, mine.lon, it.lat, it.lon) } ?: 0.0
                val scale = niceScale(maxOf(far * 1.15, 50.0))
                val rot = if (turn && heading != null) -heading else 0f
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Color(0xFF0E1A14))) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val r = size.width / 2 * 0.92f
                        fun at(p: Pos): Offset {
                            val d = distance(mine.lat, mine.lon, p.lat, p.lon) / scale * r
                            val b = Math.toRadians(bearing(mine.lat, mine.lon, p.lat, p.lon))
                            return Offset(c.x + (d * sin(b)).toFloat(), c.y - (d * cos(b)).toFloat())
                        }
                        for (k in 1..4) drawCircle(Color(0x3321C063), r * k / 4, c, style = Stroke(1.5f))
                        drawText(tm, distText(scale), Offset(c.x + 6, c.y - r + 4), TextStyle(Color(0x9921C063), 11.sp))
                        rotate(rot, c) {
                            drawText(tm, "N", Offset(c.x - 6, c.y - r - 2), TextStyle(Color(0xFFE57373), 14.sp, FontWeight.Bold))
                            // trails
                            for ((uid, tr) in s.trails) if (tr.size > 1 && (uid == meId || uid in ids)) {
                                val path = Path()
                                tr.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                                drawPath(path, (if (uid == meId) C.accent else colorOf(uid)).copy(alpha = 0.55f), style = Stroke(3f))
                            }
                            meet?.let { m ->
                                val o = at(Pos(m.lat, m.lon, Double.NaN, 0, 0))
                                drawCircle(Color.White, 11f, o); drawCircle(Color(0xFFE53935), 8f, o)
                                drawText(tm, "📍 ${m.label.ifBlank { "Meet" }}", o + Offset(12f, -12f), TextStyle(Color.White, 12.sp))
                            }
                            for (uid in ids) {
                                val p = s.positions[uid]!!
                                val o = at(p)
                                val stale = now - p.at > 10 * 60_000
                                drawCircle(colorOf(uid).copy(alpha = if (stale) 0.4f else 1f), 13f, o)
                                val h = if (!mine.alt.isNaN() && !p.alt.isNaN()) (p.alt - mine.alt).let { if (abs(it) < 15) "" else if (it > 0) " ↑${it.roundToInt()}m" else " ↓${(-it).roundToInt()}m" } else ""
                                drawText(tm, s.nameOf(uid).take(12) + h, o + Offset(16f, -14f), TextStyle(Color.White, 12.sp, FontWeight.Medium))
                            }
                        }
                        // me, with the way the phone points
                        drawCircle(Color.White, 15f, c); drawCircle(C.accent, 11f, c)
                        if (!turn && heading != null) {
                            val b = Math.toRadians(heading.toDouble())
                            drawLine(C.accent, c, Offset(c.x + (40 * sin(b)).toFloat(), c.y - (40 * cos(b)).toFloat()), 5f)
                        } else drawLine(C.accent, c, Offset(c.x, c.y - 40), 5f)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Body(if (heading == null) "No compass on this phone: north is up." else if (turn) "Turned with your compass: straight ahead is up." else "North is up.", Modifier.weight(1f), color = C.dim, size = 12.sp)
                    if (heading != null) Btn(if (turn) "North up" else "Follow compass") { turn = !turn }
                }
                Body("Your height: " + (if (mine.alt.isNaN()) "unknown" else "${mine.alt.roundToInt()} m above sea") + " · accuracy ±${mine.acc} m", color = C.dim, size = 12.sp)
            }
            // people
            for (m in g.members.filter { it.id != meId }) {
                val p = s.positions[m.id]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (p != null) colorOf(m.id) else C.faint))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Body(s.nameOf(m.id) + if (roll != null && m.id in roll.ok) "  ✅" else "", weight = FontWeight.Medium)
                        if (p == null) Body("No position shared", color = C.faint, size = 12.sp)
                        else {
                            val line = if (mine != null) distText(distance(mine.lat, mine.lon, p.lat, p.lon)) + " " + compass(bearing(mine.lat, mine.lon, p.lat, p.lon)) + " · " else ""
                            Body(line + ago(now, p.at), color = C.dim, size = 13.sp)
                            heightText(mine, p)?.let { Body(it, color = if (it.startsWith("↑")) C.amber else C.dim, size = 13.sp) }
                        }
                    }
                    if (p != null) Text("Map", Modifier.clip(CircleShape).background(C.raised).clickable { a.openMap(p.lat, p.lon, s.nameOf(m.id)) }
                        .padding(horizontal = 14.dp, vertical = 6.dp), color = C.text, fontSize = 13.sp)
                }
            }
            meet?.let { m ->
                Body("📍 Meeting point: ${m.label.ifBlank { "set by " + s.nameOf(m.by) }}" + (mine?.let { " · " + distText(distance(it.lat, it.lon, m.lat, m.lon)) + " " + compass(bearing(it.lat, it.lon, m.lat, m.lon)) } ?: ""), size = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(if (meet == null) "Meet here" else "Move meet here", Modifier.weight(1f), enabled = mine != null) { a.setMeetHere(g.id) }
                if (meet != null) Btn("Remove meet", Modifier.weight(1f)) { a.clearMeet(g.id) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("📋 Roll call", Modifier.weight(1f)) { a.rollCall(g.id) }
                Btn("Clear my trail", Modifier.weight(1f)) { a.clearTrail() }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
