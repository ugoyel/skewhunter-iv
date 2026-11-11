package com.cleartether.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.cleartether.app.ui.theme.Brand

/**
 * The single big switch. Off: neutral ring. Busy: spinner. On: animated gradient with a soft
 * pulse. [remaining] (0..1) draws the free-tier allowance as an arc around the button.
 */
@Composable
fun PowerButton(
    active: Boolean,
    busy: Boolean,
    remaining: Float?,
    label: String,
    stateLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 188.dp,
) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale",
    )
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.08f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "alpha",
    )
    val arc by animateFloatAsState(remaining ?: 1f, tween(600), label = "arc")
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val arcColor = if ((remaining ?: 1f) < 0.15f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(
        modifier
            .size(diameter + 36.dp)
            .semantics {
                contentDescription = label
                stateDescription = stateLabel
                role = Role.Switch
            },
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                Modifier
                    .size(diameter)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)),
            )
        }
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = 6.dp.toPx()
            drawArc(trackColor, 0f, 360f, false, style = Stroke(stroke))
            if (remaining != null) {
                drawArc(arcColor, -90f, 360f * arc, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier
                .size(diameter - 16.dp)
                .shadow(if (active) 18.dp else 4.dp, CircleShape, ambientColor = Brand.Teal, spotColor = Brand.Teal)
                .clip(CircleShape)
                .then(
                    if (active) Modifier.background(Brand.activeGradient)
                    else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(56.dp), strokeWidth = 5.dp,
                    color = if (active) Color.White else MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    Icons.Rounded.PowerSettingsNew, contentDescription = null,
                    tint = if (active) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
            }
        }
    }
}
