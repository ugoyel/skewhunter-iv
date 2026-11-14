package app.pttradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

object C {
    val bg = Color(0xFF0B0D0F)
    val panel = Color(0xFF14171A)
    val raised = Color(0xFF1C2024)
    val line = Color(0xFF2A2F35)
    val text = Color(0xFFE8EAED)
    val dim = Color(0xFF8A929B)
    val faint = Color(0xFF596069)
    val amber = Color(0xFFFFB020)
    val green = Color(0xFF34D17B)
    val red = Color(0xFFFF4B3E)
    val lcd = Color(0xFF0E1210)
}

val Mono = FontFamily.Monospace

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.dim, size: TextUnit = 11.sp) =
    Text(text.uppercase(), modifier, color = color, fontSize = size, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = C.text, size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = size * 1.35f)

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when { !enabled -> C.faint; primary -> C.bg; danger -> C.red; else -> C.text }
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(if (primary && enabled) C.amber else C.raised)
            .border(1.dp, if (primary && enabled) C.amber else C.line, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text.uppercase(), color = fg, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, caps: Boolean = false, onDone: (() -> Unit)? = null) {
    BasicTextField(
        value, onChange, modifier,
        singleLine = true,
        textStyle = TextStyle(color = C.text, fontSize = 15.sp),
        cursorBrush = SolidColor(C.amber),
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(6.dp))
                .heightIn(min = 44.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = C.faint, fontSize = 15.sp, maxLines = 1)
                inner()
            }
        }
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun Segments(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, C.line, RoundedCornerShape(6.dp)).background(C.panel)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.weight(1f).background(if (on) C.amber else Color.Transparent).clickable { onSelect(key) }.heightIn(min = 40.dp),
                contentAlignment = Alignment.Center) {
                Text(label.uppercase(), color = if (on) C.bg else C.dim, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(title, color = C.amber, size = 12.sp)
            content()
        }
    }
}

val Pad = PaddingValues(horizontal = 16.dp)
