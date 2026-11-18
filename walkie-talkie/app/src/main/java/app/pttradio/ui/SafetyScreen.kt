package app.pttradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Safety tools for hikers and people far from coverage. */
@Composable
fun SafetyScreen(checkinAt: Long, now: Long, share: Boolean, trail: Boolean, a: Actions, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Safety tools", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Signal for help without any network")
                Btn("🔦 Flash + siren", Modifier.fillMaxWidth(), primary = true) { a.startSignal(true) }
                Btn("🔦 Flash only (silent)", Modifier.fillMaxWidth()) { a.startSignal(false) }
                Body("The flashlight blinks SOS in Morse (··· ––– ···), visible for kilometres at night, and the screen flashes with it. " +
                    "The siren plays at full alarm volume, even in silent mode.", color = C.dim, size = 12.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("Check-in timer")
                if (checkinAt > 0) {
                    val left = ((checkinAt - now) / 60_000).coerceAtLeast(0)
                    val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(checkinAt))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x2221C063)).padding(14.dp)) {
                        Body("Check in by $at", weight = FontWeight.SemiBold)
                        Body(if (checkinAt > now) "${left / 60} h ${left % 60} min left" else "Time is up: your groups have been alerted.", color = C.dim, size = 13.sp)
                    }
                    Btn("✅ I'm OK", Modifier.fillMaxWidth(), primary = true) { a.setCheckIn(0) }
                } else Body("Set a time to be back. If you have not tapped \"I'm OK\" by then, your groups get an alert with your last position, " +
                    "and an SOS goes out to phones nearby. You get a reminder 15 minutes before.", color = C.dim, size = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 480 to "8 h").forEach { (m, l) ->
                        Text(l, Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(C.raised).clickable { a.setCheckIn(m) }.padding(vertical = 10.dp),
                            color = C.text, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Position and trail")
                SwitchRow("Share my position with my groups", "Members see where you are, how far and how much higher or lower, even without internet.", share) { a.setShare(it) }
                SwitchRow("Record my trail", "Keeps your path (every couple of minutes) so you and your group can see where you went. Memory only.", trail) { a.setTrail(it) }
                Body("Open a group and choose Group map to see everyone.", color = C.dim, size = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Full-screen flashing while the flashlight SOS runs. */
@Composable
fun SignalScreen(lit: Boolean, a: Actions) {
    Box(Modifier.fillMaxSize().background(if (lit) Color.White else Color.Black), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("SOS", color = if (lit) Color.Black else Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(24.dp))
            Btn("Stop", Modifier.fillMaxWidth(), danger = true) { a.stopSignal() }
        }
    }
}
