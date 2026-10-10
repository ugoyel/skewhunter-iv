package com.carsathi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// carsathi brand palette (inspired by the BlaBlaCar look & feel)
val BrandBlue = Color(0xFF00AFF5)
val BrandBlueDark = Color(0xFF0098D6)
val Petrol = Color(0xFF054752)
val PetrolLight = Color(0xFF708C91)
val Mist = Color(0xFFEDEDED)
val Cloud = Color(0xFFF5F5F5)
val Success = Color(0xFF2ED1A2)
val SuccessDark = Color(0xFF14A37F)
val Danger = Color(0xFFF53F5B)
val Warning = Color(0xFFFFB300)
val StarYellow = Color(0xFFFFB800)

@Immutable
data class ExtraColors(
    val heading: Color,
    val subtle: Color,
    val divider: Color,
    val surfaceAlt: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Petrol, PetrolLight, Mist, Cloud, SuccessDark, Danger, Warning)
}

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4FE),
    onPrimaryContainer = Petrol,
    secondary = Petrol,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EEF0),
    onSecondaryContainer = Petrol,
    tertiary = SuccessDark,
    background = Color.White,
    onBackground = Petrol,
    surface = Color.White,
    onSurface = Petrol,
    surfaceVariant = Cloud,
    onSurfaceVariant = PetrolLight,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Cloud,
    surfaceContainerHighest = Mist,
    surfaceContainerLowest = Color.White,
    outline = Color(0xFFC9D3D5),
    outlineVariant = Mist,
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF00445F),
    onPrimaryContainer = Color(0xFFDDF4FE),
    secondary = Color(0xFF9FD3DC),
    onSecondary = Petrol,
    background = Color(0xFF0E1A1C),
    onBackground = Color(0xFFE6EEF0),
    surface = Color(0xFF0E1A1C),
    onSurface = Color(0xFFE6EEF0),
    surfaceVariant = Color(0xFF1A2A2D),
    onSurfaceVariant = Color(0xFF9DB2B6),
    surfaceContainer = Color(0xFF142326),
    surfaceContainerLow = Color(0xFF122022),
    surfaceContainerHigh = Color(0xFF1A2A2D),
    surfaceContainerHighest = Color(0xFF223437),
    surfaceContainerLowest = Color(0xFF0A1416),
    outline = Color(0xFF3B5154),
    outlineVariant = Color(0xFF223437),
    error = Danger,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CarSathiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extra = if (darkTheme) {
        ExtraColors(
            heading = Color(0xFFE6EEF0), subtle = Color(0xFF9DB2B6), divider = Color(0xFF223437),
            surfaceAlt = Color(0xFF1A2A2D), success = Success, danger = Danger, warning = Warning,
        )
    } else {
        ExtraColors(Petrol, PetrolLight, Mist, Cloud, SuccessDark, Danger, Warning)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

object CsTheme {
    val colors: ExtraColors
        @Composable get() = LocalExtraColors.current
}
