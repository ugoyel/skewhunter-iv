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
import androidx.compose.ui.graphics.asImageBitmap
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

/** Dark, chat-app style palette: near-black background, green accent, red only for transmitting and SOS. */
object C {
    val bg = Color(0xFF0C1013)
    val panel = Color(0xFF161C20)
    val raised = Color(0xFF20282D)
    val line = Color(0xFF263036)
    val text = Color(0xFFE9EDEF)
    val dim = Color(0xFF8696A0)
    val faint = Color(0xFF5E6C75)
    val accent = Color(0xFF21C063)
    val amber = Color(0xFFFFB020)
    val green = Color(0xFF21C063)
    val blue = Color(0xFF53BDEB)
    val red = Color(0xFFFF4B3E)
    val lcd = Color(0xFF0E1210)
    val mine = Color(0xFF134D37)
    val theirs = Color(0xFF1F2C33)
}

private val avatarColors = listOf(0xFF2F7D6D, 0xFF6A4FB3, 0xFFB5603B, 0xFF3A6EA5, 0xFF9C4A7A, 0xFF5C8A2E, 0xFFA8842D, 0xFF4A5D8F).map { Color(it) }

fun initials(name: String): String =
    name.trim().split(Regex("[\\s·_-]+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Round avatar with initials; [dot] adds a presence dot (green online, blue nearby). */
@Composable
fun Avatar(name: String, key: String, size: Dp = 50.dp, dot: Color? = null, group: Boolean = false, pic: String = "") {
    val photo = androidx.compose.runtime.remember(pic) {
        if (pic.isEmpty()) null else try {
            val b = android.util.Base64.decode(pic, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        } catch (e: Exception) { null }
    }
    Box(Modifier.size(size)) {
        if (photo != null) androidx.compose.foundation.Image(photo, name, Modifier.size(size).clip(CircleShape), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else
        Box(Modifier.size(size).clip(CircleShape).background(avatarColors[(key.hashCode() and 0x7fffffff) % avatarColors.size]), contentAlignment = Alignment.Center) {
            Text(if (group) initials(name).take(1) + "#" else initials(name), color = Color.White, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.SemiBold)
        }
        if (dot != null) Box(Modifier.align(Alignment.BottomEnd).size(size * 0.28f).clip(CircleShape).background(C.bg).padding(2.dp).clip(CircleShape).background(dot))
    }
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
        modifier.clip(RoundedCornerShape(22.dp))
            .background(if (primary && enabled) C.accent else C.raised)
            .border(1.dp, if (primary && enabled) C.accent else C.line, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, caps: Boolean = false, onDone: (() -> Unit)? = null) {
    BasicTextField(
        value, onChange, modifier,
        singleLine = true,
        textStyle = TextStyle(color = C.text, fontSize = 15.sp),
        cursorBrush = SolidColor(C.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(22.dp))
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
    Row(modifier.clip(RoundedCornerShape(22.dp)).border(1.dp, C.line, RoundedCornerShape(22.dp)).background(C.panel)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(Modifier.weight(1f).background(if (on) C.accent else Color.Transparent).clickable { onSelect(key) }.heightIn(min = 40.dp),
                contentAlignment = Alignment.Center) {
                Text(label.uppercase(), color = if (on) C.bg else C.dim, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.panel).border(1.dp, C.line, RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(title, color = C.accent, size = 12.sp)
            content()
        }
    }
}

val Pad = PaddingValues(horizontal = 16.dp)
