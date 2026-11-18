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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pttradio.core.Call
import app.pttradio.core.RadioState

/** Full-screen audio call: ringing in or out, or connected with mute, speaker and hang up. */
@Composable
fun CallScreen(s: RadioState, c: Call, now: Long, a: Actions) {
    val name = s.nameOf(c.peer)
    val secs = ((now - c.since) / 1000).coerceAtLeast(0)
    val status = when (c.state) {
        "calling" -> "Calling…"
        "ringing" -> "Incoming audio call"
        else -> "%d:%02d".format(secs / 60, secs % 60) + if (s.netUp) " · internet" else " · nearby link"
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B1A14)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Avatar(name, c.peer, 120.dp, pic = picOf(s, c.peer))
        Spacer(Modifier.height(20.dp))
        Text(name, color = C.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(status, color = C.dim, fontSize = 16.sp)
        Text("🔒 End-to-end encrypted", Modifier.padding(top = 6.dp), color = C.faint, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (c.state == "ringing") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey("✕", "Decline", C.red) { a.endCall() }
            RoundKey("📞", "Answer", C.green) { a.answerCall() }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(if (c.muted) "🔇" else "🎙", if (c.muted) "Unmute" else "Mute", if (c.muted) C.text else C.raised, enabled = c.state == "active") { a.setCallMuted(!c.muted) }
            RoundKey("✕", "End", C.red) { a.endCall() }
            RoundKey(if (c.speaker) "🔊" else "🔈", if (c.speaker) "Earpiece" else "Speaker", if (c.speaker) C.text else C.raised) { a.setCallSpeaker(!c.speaker) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun RoundKey(icon: String, label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).clip(CircleShape).background(color).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 26.sp, color = if (color == C.text) C.bg else Color.White)
        }
        Text(label, Modifier.padding(top = 8.dp), color = C.dim, fontSize = 13.sp)
    }
}
