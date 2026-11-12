package io.github.ugoyel.hearby.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ugoyel.hearby.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Hearby's look: a night-club palette on a dark stage. Neon pink, violet and electric blue carry the
 * brand; lime means "locked in", sun means "heads up", coral means "stop".
 */
object Party {
    val Night = Color(0xFF0D0620)
    val Pink = Color(0xFFFF3D9A)
    val Violet = Color(0xFF8B5CFF)
    val Blue = Color(0xFF29C5FF)
    val Lime = Color(0xFFC8FF3D)
    val Sun = Color(0xFFFFC53D)
    val Coral = Color(0xFFFF6B5E)

    val Ink = Color.White
    val InkSoft = Color.White.copy(alpha = 0.74f)
    val InkFaint = Color.White.copy(alpha = 0.5f)
    val Glass = Color.White.copy(alpha = 0.07f)
    val GlassBright = Color.White.copy(alpha = 0.11f)
    val GlassStroke = Color.White.copy(alpha = 0.14f)

    val Brand = Brush.linearGradient(listOf(Pink, Violet, Blue))
    val BrandSoft = Brush.linearGradient(listOf(Pink.copy(alpha = 0.34f), Violet.copy(alpha = 0.3f), Blue.copy(alpha = 0.3f)))
    val Stop = Brush.linearGradient(listOf(Color(0xFFFF4D6D), Coral, Color(0xFFFF9E57)))
}

/**
 * Set to false for still rendering (screenshots): every looping animation freezes at a pleasant
 * pose, and the embedded YouTube player is replaced by a placeholder. An animation that never ends
 * would never let the renderer settle.
 */
val LocalMotion = staticCompositionLocalOf { true }

/** A value that loops 0..[to] forever, or stays at [still] when motion is off. */
@Composable
private fun looping(durationMs: Int, to: Float, still: Float, label: String): State<Float> =
    if (LocalMotion.current) {
        rememberInfiniteTransition(label = label).animateFloat(0f, to, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = label)
    } else {
        remember { mutableFloatStateOf(still) }
    }

/** Fredoka: round, friendly, a little bouncy. Bundled under the SIL Open Font License. */
val Fredoka = FontFamily(
    Font(R.font.fredoka_regular, FontWeight.Normal),
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
    Font(R.font.fredoka_bold, FontWeight.Bold),
)

private val PartyColors = darkColorScheme(
    primary = Party.Pink, onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1140), onPrimaryContainer = Color.White,
    secondary = Party.Blue, onSecondary = Color(0xFF00222E),
    tertiary = Party.Lime, onTertiary = Color(0xFF1B2600),
    background = Party.Night, onBackground = Color.White,
    surface = Party.Night, onSurface = Color.White,
    surfaceVariant = Color(0xFF2A1B4D), onSurfaceVariant = Party.InkSoft,
    outline = Party.GlassStroke, outlineVariant = Party.GlassStroke,
    error = Party.Coral, onError = Color.White,
)

/** Always dark: this is a party app, and neon needs a night sky. */
@Composable
fun HearbyTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.party() = copy(fontFamily = Fredoka)
    val typography = Typography(
        displayLarge = base.displayLarge.party(), displayMedium = base.displayMedium.party(), displaySmall = base.displaySmall.party(),
        headlineLarge = base.headlineLarge.party(), headlineMedium = base.headlineMedium.party(), headlineSmall = base.headlineSmall.party(),
        titleLarge = base.titleLarge.party(), titleMedium = base.titleMedium.party(), titleSmall = base.titleSmall.party(),
        bodyLarge = base.bodyLarge.party(), bodyMedium = base.bodyMedium.party(), bodySmall = base.bodySmall.party(),
        labelLarge = base.labelLarge.party(), labelMedium = base.labelMedium.party(), labelSmall = base.labelSmall.party(),
    )
    MaterialTheme(colorScheme = PartyColors, typography = typography, content = content)
}

/**
 * The stage: a dark backdrop with three slow-drifting pools of coloured light. [energy] (0..1)
 * turns the lights up when a party is actually running.
 */
@Composable
fun PartyBackground(energy: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // The lights drift slowly, so ~20 redraws a second look identical to 60 and cost a third of
    // the battery: the backdrop only redraws when the quantised value changes.
    val rawDrift = looping(28_000, to = 1f, still = 0.12f, label = "drift")
    val drift by remember { derivedStateOf { (rawDrift.value * DRIFT_STEPS).toInt() / DRIFT_STEPS } }
    val glow by animateFloatAsState(0.34f + 0.24f * energy.coerceIn(0f, 1f), tween(900), label = "glow")
    Box(
        modifier
            .fillMaxSize()
            .background(Party.Night)
            .drawBehind {
                // State is read here, in the draw phase, so the animation never recomposes the screen.
                val a = drift * 2f * PI.toFloat()
                fun light(color: Color, cx: Float, cy: Float, r: Float) = drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), Offset(cx, cy), r),
                    radius = r, center = Offset(cx, cy),
                )
                light(Party.Pink, size.width * (0.08f + 0.10f * cos(a)), size.height * (0.06f + 0.05f * sin(a)), size.width * 0.85f)
                light(Party.Blue, size.width * (1.00f + 0.08f * sin(a * 2f)), size.height * (0.40f + 0.08f * cos(a)), size.width * 0.75f)
                light(Party.Violet, size.width * (0.28f + 0.14f * sin(a + 1f)), size.height * (1.00f + 0.04f * cos(a * 2f)), size.width * 0.95f)
            },
        content = content,
    )
}

private const val DRIFT_STEPS = 560f // 28 s cycle: 20 steps per second

/** A frosted panel floating over the stage. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, lit: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (lit) Party.GlassBright else Party.Glass)
            .border(
                1.dp,
                if (lit) Brush.linearGradient(listOf(Party.Pink.copy(alpha = 0.8f), Party.Blue.copy(alpha = 0.8f))) else SolidColor(Party.GlassStroke),
                shape,
            )
            .padding(20.dp),
        content = content,
    )
}

/** The big call-to-action: a glowing gradient pill that squishes when pressed. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    brush: Brush = Party.Brand,
    glow: Color = Party.Pink,
    height: Dp = 60.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "squish")
    Box(
        modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (enabled) 16.dp else 0.dp, CircleShape, ambientColor = glow, spotColor = glow)
            .clip(CircleShape)
            .background(if (enabled) brush else SolidColor(Color.White.copy(alpha = 0.10f)))
            .clickable(interaction, ripple(color = Color.White), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (enabled) Color.White else Party.InkFaint
            if (icon != null) {
                Icon(icon, null, Modifier.size(24.dp), tint = tint)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = tint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Secondary action: a see-through pill. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(Party.Glass)
            .border(1.dp, Party.GlassStroke, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) Color.White else Party.InkFaint
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = tint, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Two-way switch with a gradient thumb that springs across. */
@Composable
fun ModeSwitch(selected: Int, onSelect: (Int) -> Unit, options: List<Pair<String, ImageVector>>, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(CircleShape)
            .background(Party.Glass)
            .border(1.dp, Party.GlassStroke, CircleShape)
            .padding(5.dp),
    ) {
        val segment = maxWidth / options.size
        val thumb by animateDpAsState(segment * selected, spring(dampingRatio = 0.68f, stiffness = 380f), label = "thumb")
        Box(
            Modifier
                .offset { IntOffset(thumb.roundToPx(), 0) } // lambda form: moving the thumb only re-lays-out, no recomposition
                .width(segment)
                .fillMaxHeight()
                .shadow(10.dp, CircleShape, ambientColor = Party.Pink, spotColor = Party.Violet)
                .clip(CircleShape)
                .background(Party.Brand),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, (label, icon) ->
                val tint = if (index == selected) Color.White else Party.InkSoft
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(role = Role.Tab) { onSelect(index) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = tint, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The glowing disc at the top of every screen. While [active] it throws out rings like a speaker
 * cone, and it bumps with the music ([level], 0..1).
 */
@Composable
fun PulseOrb(icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, size: Dp = 72.dp, level: Float = 0f) {
    val ring by looping(1900, to = 1f, still = 0.3f, label = "ring")
    val bump by animateFloatAsState(
        1f + 0.14f * sqrt(level.coerceIn(0f, 1f)), spring(dampingRatio = 0.4f, stiffness = 320f), label = "bump",
    )
    Box(modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val core = size.toPx() / 2f
                for (k in 0 until 2) {
                    val p = (ring + k * 0.5f) % 1f
                    drawCircle(
                        lerp(Party.Pink, Party.Blue, p).copy(alpha = (1f - p) * 0.55f),
                        radius = core * (1f + 0.5f * p),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = bump
                    scaleY = bump
                }
                .shadow(if (active) 20.dp else 0.dp, CircleShape, ambientColor = Party.Pink, spotColor = Party.Violet)
                .clip(CircleShape)
                .background(if (active) Party.Brand else Party.BrandSoft)
                .border(1.dp, Color.White.copy(alpha = if (active) 0.35f else 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(size * 0.48f), tint = Color.White)
        }
    }
}

/**
 * A row of dancing bars driven by the real audio level, so it only moves when music does. With no
 * sound the bars settle into a row of dots.
 */
@Composable
fun Equalizer(level: Float, modifier: Modifier = Modifier, bars: Int = 26) {
    val phase by looping(2600, to = 2f * PI.toFloat(), still = 1.3f, label = "phase")
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(160), label = "level")
    Canvas(modifier.fillMaxWidth().height(52.dp)) {
        val p = phase
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (bars - 1)) / bars
        val energy = sqrt(smooth) // quiet passages should still dance
        for (i in 0 until bars) {
            val wobble = 0.5f + 0.5f * sin(p * (1 + i % 3) + i * 1.7f)
            val h = max(w, size.height * energy * (0.34f + 0.66f * wobble))
            val x = i * (w + gap)
            val f = i / (bars - 1f)
            val color = if (f < 0.5f) lerp(Party.Pink, Party.Violet, f * 2f) else lerp(Party.Violet, Party.Blue, (f - 0.5f) * 2f)
            drawRoundRect(color, Offset(x, (size.height - h) / 2f), Size(w, h), CornerRadius(w / 2f))
        }
    }
}

/** One-shot confetti. Fires whenever [trigger] changes to a new positive value. */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1900, easing = LinearEasing))
        }
    }
    Confetti(progress.value, seed = trigger, modifier = modifier)
}

private class ConfettiPiece(val angle: Float, val speed: Float, val spin: Float, val width: Float, val height: Float, val color: Color)

/** Confetti at a given point of its flight ([progress] 0..1). Split out so it can be previewed. */
@Composable
fun Confetti(progress: Float, seed: Int, modifier: Modifier = Modifier) {
    if (progress >= 1f) return
    val pieces = remember(seed) {
        val random = Random(seed)
        val colors = listOf(Party.Pink, Party.Blue, Party.Lime, Party.Sun, Party.Violet, Color.White)
        List(80) {
            ConfettiPiece(
                angle = -PI.toFloat() * (0.08f + 0.84f * random.nextFloat()), // upwards fan
                speed = 0.35f + 0.75f * random.nextFloat(),
                spin = random.nextFloat() * 2f - 1f,
                width = 5f + 6f * random.nextFloat(),
                height = 8f + 8f * random.nextFloat(),
                color = colors[random.nextInt(colors.size)],
            )
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val origin = Offset(size.width / 2f, size.height * 0.30f)
        val ease = 1f - (1f - progress).pow(2.2f) // fast launch, slow drift
        val alpha = (1f - progress).coerceIn(0f, 1f).pow(0.5f)
        for (piece in pieces) {
            val distance = piece.speed * size.width * 0.8f * ease
            val x = origin.x + cos(piece.angle) * distance
            val y = origin.y + sin(piece.angle) * distance + size.height * 0.55f * progress * progress
            val w = piece.width.dp.toPx()
            val h = piece.height.dp.toPx()
            rotate(piece.spin * progress * 900f, pivot = Offset(x, y)) {
                drawRoundRect(piece.color.copy(alpha = alpha), Offset(x - w / 2f, y - h / 2f), Size(w, h), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

/** "Searching": a dot sending out sonar rings. */
@Composable
fun Radar(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Party.Blue) {
    val sweep by looping(1500, to = 1f, still = 0.45f, label = "radar")
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(color, radius = r * 0.22f)
        for (k in 0 until 2) {
            val p = (sweep + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.8f), radius = r * (0.25f + 0.75f * p), style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** A selectable tile: bold name on top, the number underneath. */
@Composable
fun ChoiceTile(title: String, detail: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) Party.BrandSoft else SolidColor(Party.Glass))
            .border(1.5.dp, if (selected) Party.Brand else SolidColor(Party.GlassStroke), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false)
        Text(detail, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else Party.InkSoft, maxLines = 1, softWrap = false)
    }
}

private val AvatarPalette = listOf(
    Party.Pink to Party.Sun, Party.Blue to Party.Violet, Party.Lime to Party.Blue,
    Party.Coral to Party.Pink, Party.Violet to Party.Pink, Party.Sun to Party.Coral,
)

/** A colourful badge with the person's initial; the colours are picked from their name. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val (a, b) = AvatarPalette[abs(name.hashCode()) % AvatarPalette.size]
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "♪"
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = Party.Night, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** A small rounded tag, e.g. "In sync". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(CircleShape).background(color.copy(alpha = 0.18f)).border(1.dp, color.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** One number with its caption, used in the row of live stats. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, accent: Color = Color.White) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(Party.Glass).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Text(label, color = Party.InkSoft, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
    }
}

/** A heads-up panel. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.AutoAwesome, color: Color = Party.Sun, action: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.12f)).border(1.dp, color.copy(alpha = 0.4f), shape).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
        action()
    }
}

/** The wordmark, painted with the brand gradient, over a small tagline. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Not stretched to full width, so the gradient spans exactly the word.
        Text(
            "Hearby",
            style = MaterialTheme.typography.headlineLarge.copy(brush = Party.Brand, fontWeight = FontWeight.Bold),
            maxLines = 1, overflow = TextOverflow.Clip,
        )
        Text(
            "ONE BEAT · EVERY PHONE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.4.sp),
            color = Party.InkFaint, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}
