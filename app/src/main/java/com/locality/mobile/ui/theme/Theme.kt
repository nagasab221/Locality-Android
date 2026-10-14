package com.locality.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** "5arye" palette, ported token-for-token from the desktop app's tailwind.config.cjs. */
object Palette {
    val Bg = Color(0xFF0D0D0D)
    val Surface = Color(0xFF141210)
    val Raised = Color(0xFF1B1815)
    val Hov = Color(0xFF262019)
    val Input = Color(0xFF1A1612)
    val InBorder = Color(0xFF3A342B)
    val CardBorder = Color(0xFF2A2620)
    val Divider = Color(0xFF211E19)
    val Accent = Color(0xFFC4A47C)
    val AccentHov = Color(0xFFD5B78D)
    val AccentText = Color(0xFF161310)
    val Track = Color(0xFF38332B)
    val Heading = Color(0xFFF4EEE0)
    val Body = Color(0xFFE9E1D2)
    val Muted = Color(0xFF8C8478)
    val Hint = Color(0xFFB69A70)
    val Warn = Color(0xFFD9A24C)
    val Ok = Color(0xFF6E9E6A)
    val Danger = Color(0xFFCE8479)
}

private val LocalityColorScheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.AccentText,
    primaryContainer = Palette.Hov,
    onPrimaryContainer = Palette.Body,
    secondary = Palette.Hint,
    onSecondary = Palette.AccentText,
    tertiary = Palette.Warn,
    background = Palette.Bg,
    onBackground = Palette.Body,
    surface = Palette.Surface,
    onSurface = Palette.Body,
    surfaceVariant = Palette.Raised,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Raised,
    surfaceContainerHigh = Palette.Hov,
    surfaceContainerHighest = Palette.Hov,
    outline = Palette.InBorder,
    outlineVariant = Palette.Divider,
    error = Palette.Danger,
    onError = Palette.AccentText,
)

/** Serif display faces echo the desktop's Palatino headings. */
private val LocalityTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = Palette.Heading),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Palette.Heading),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Palette.Heading),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Palette.Heading),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Palette.Heading),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

@Composable
fun LocalityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LocalityColorScheme,
        typography = LocalityTypography,
        content = content,
    )
}
