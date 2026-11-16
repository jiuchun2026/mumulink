package com.mumulink.remote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- MuMuLink palette: deep space ink + aurora accents ----
val Ink = Color(0xFF0B0E14)
val InkVariant = Color(0xFF10141F)
val Surface1 = Color(0xFF121724)
val Surface2 = Color(0xFF1A2133)
val Surface3 = Color(0xFF222B41)
val Primary = Color(0xFF7C9CFF)
val PrimaryDeep = Color(0xFF4C6FE0)
val Secondary = Color(0xFF58E1C1)
val Tertiary = Color(0xFFFF8FA3)
val OnInk = Color(0xFFE9EDF7)
val Muted = Color(0xFF8A93A8)
val OutlineSoft = Color(0xFF2A3348)
val Danger = Color(0xFFFF6B6B)
val Success = Color(0xFF57D9A3)
val Warning = Color(0xFFFFC857)

// ---- Telemetry type: tabular monospace so numbers never jitter ----
val MonoFamily: FontFamily = FontFamily.Monospace

/** Hero telemetry numeral (fps hero readouts, big counters). */
val NumericHero = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    letterSpacing = (-1.6).sp,
)

/** Standard telemetry numeral used inside chips and rows. */
val NumericBody = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = (-0.3).sp,
)

/** Small mono label, for addresses / ports / codes. */
val NumericSmall = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = (-0.1).sp,
)

private val Colors = darkColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF08101F),
    primaryContainer = PrimaryDeep,
    onPrimaryContainer = Color(0xFFEDF1FF),
    secondary = Secondary,
    onSecondary = Color(0xFF04140F),
    secondaryContainer = Color(0xFF123A31),
    onSecondaryContainer = Color(0xFFD9FFF4),
    tertiary = Tertiary,
    onTertiary = Color(0xFF25060C),
    background = Ink,
    onBackground = OnInk,
    surface = Surface1,
    onSurface = OnInk,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = OutlineSoft,
    outlineVariant = Color(0xFF1E2639),
    error = Danger,
    onError = Color(0xFF2A0606),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, lineHeight = 52.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1.0).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.2.sp),
)

/** 24–28dp cards, 999dp pills — soft, physical, never sharp. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MuMuLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
