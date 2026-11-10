package com.cleartether.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Teal = Color(0xFF0BA391)
    val TealBright = Color(0xFF2EE6C5)
    val Indigo = Color(0xFF4A5BD6)
    val Gold = Color(0xFFF5B400)
    val GoldDeep = Color(0xFFE08A00)
    val Ink = Color(0xFF0B1416)

    val activeGradient = Brush.linearGradient(listOf(TealBright, Teal, Indigo))
    val premiumGradient = Brush.linearGradient(listOf(Gold, GoldDeep))
    val headerGradient = Brush.linearGradient(listOf(Teal, Indigo))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF007E70),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A5BD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE1FF),
    onSecondaryContainer = Color(0xFF0A1559),
    tertiary = Color(0xFF8C5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB0),
    onTertiaryContainer = Color(0xFF2C1A00),
    background = Color(0xFFF5FAF8),
    onBackground = Color(0xFF0E1C1A),
    surface = Color(0xFFF5FAF8),
    onSurface = Color(0xFF0E1C1A),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F3),
    surfaceContainer = Color(0xFFE9F0EE),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE4E2),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FDBC6),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFFB4F1E6),
    secondary = Color(0xFFBCC3FF),
    onSecondary = Color(0xFF1C2A87),
    secondaryContainer = Color(0xFF33419E),
    onSecondaryContainer = Color(0xFFDFE1FF),
    tertiary = Color(0xFFFFB952),
    onTertiary = Color(0xFF4A2F00),
    tertiaryContainer = Color(0xFF6A4500),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Brand.Ink,
    onBackground = Color(0xFFE2EEEB),
    surface = Brand.Ink,
    onSurface = Color(0xFFE2EEEB),
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFF071012),
    surfaceContainerLow = Color(0xFF111C1E),
    surfaceContainer = Color(0xFF152124),
    surfaceContainerHigh = Color(0xFF1C292C),
    surfaceContainerHighest = Color(0xFF253336),
    outline = Color(0xFF889391),
    outlineVariant = Color(0xFF3F4947),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun ClearTetherTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
