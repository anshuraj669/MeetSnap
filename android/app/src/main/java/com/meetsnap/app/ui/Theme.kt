package com.meetsnap.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MeetSnap palette (SPEC.md "Look and feel"): dark graphite with electric blue and teal.
val Bg = Color(0xFF090D14)
val Panel = Color(0xFF111827)
val Panel2 = Color(0xFF1A2333)
val Line = Color(0xFF263247)
val TextC = Color(0xFFEEF2F7)
val Muted = Color(0xFF8A97AB)
val Accent = Color(0xFF3B82F6)
val Accent2 = Color(0xFF2DD4BF)
val Ok = Color(0xFF22C55E)
val Bad = Color(0xFFEF4444)
val Warn = Color(0xFFEAB308)
// Lead temperature: an ordinal one-hue ramp validated on the dark panel (brighter = hotter).
// Always paired with a label or 🔥, never colour alone.
val Hot = Color(0xFFFF8F45)
val WarmC = Color(0xFFC96A2C)
val Cold = Color(0xFF8B5236)
val DeviceC = Color(0xFFB48CFF)
val CloudC = Color(0xFF5AA9FF)
val OnAccent = Color.White
val BrandGradient = Brush.linearGradient(listOf(Accent, Color(0xFF2563EB), Accent2))

fun scoreColor(score: String) = when (score) { "hot" -> Hot; "cold" -> Cold; else -> WarmC }
fun scoreLabel(score: String) = when (score) { "hot" -> "🔥 Hot"; "cold" -> "Cold"; else -> "Warm" }

private val colors = darkColorScheme(
    primary = Accent, onPrimary = OnAccent, secondary = Accent2, onSecondary = Color(0xFF04201C),
    tertiary = CloudC, background = Bg, onBackground = TextC, surface = Panel, onSurface = TextC,
    surfaceVariant = Panel2, onSurfaceVariant = Muted, surfaceContainer = Panel, surfaceContainerHigh = Panel2,
    surfaceContainerHighest = Panel2, outline = Line, outlineVariant = Line, error = Bad,
)

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

private val shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp))

@Composable
fun MeetSnapTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes, content = content)
