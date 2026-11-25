package com.meetsnap.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MeetSnap design tokens, the same values as the web app (meetsnap/src/style.css): neutral surfaces,
 * one blue accent, quiet borders. Light by default; dark follows the system setting.
 */
data class MsColors(
    val dark: Boolean,
    val bg: Color, val surface: Color, val surface2: Color, val surface3: Color,
    val border: Color, val borderStrong: Color,
    val text: Color, val text2: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val accentText: Color, val onAccent: Color,
    val success: Color, val successSoft: Color, val warning: Color, val warningSoft: Color, val danger: Color, val dangerSoft: Color,
    val hot: Color, val hotSoft: Color, val warm: Color, val warmSoft: Color, val cold: Color, val coldSoft: Color,
    val device: Color, val deviceSoft: Color, val cloud: Color, val cloudSoft: Color,
    val stNew: Color, val stReady: Color, val stContacted: Color, val stWon: Color, val stLost: Color,
)

val LightMs = MsColors(
    dark = false,
    bg = Color(0xFFF7F8FA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF2F4F7), surface3 = Color(0xFFEAECF0),
    border = Color(0xFFE4E7EC), borderStrong = Color(0xFFD0D5DD),
    text = Color(0xFF101828), text2 = Color(0xFF344054), muted = Color(0xFF667085), faint = Color(0xFF98A2B3),
    accent = Color(0xFF2563EB), accentSoft = Color(0xFFEFF4FF), accentText = Color(0xFF1D4ED8), onAccent = Color.White,
    success = Color(0xFF079455), successSoft = Color(0xFFECFDF3), warning = Color(0xFFB54708), warningSoft = Color(0xFFFFFAEB),
    danger = Color(0xFFD92D20), dangerSoft = Color(0xFFFEF3F2),
    hot = Color(0xFFE04F16), hotSoft = Color(0xFFFEF6EE), warm = Color(0xFFCA8504), warmSoft = Color(0xFFFEFBE8),
    cold = Color(0xFF667085), coldSoft = Color(0xFFF2F4F7),
    device = Color(0xFF6941C6), deviceSoft = Color(0xFFF4F3FF), cloud = Color(0xFF1570EF), cloudSoft = Color(0xFFEFF8FF),
    stNew = Color(0xFF98A2B3), stReady = Color(0xFF2563EB), stContacted = Color(0xFF7A5AF8), stWon = Color(0xFF12B76A), stLost = Color(0xFFF04438),
)

private fun Color.soft(a: Float = 0.14f) = copy(alpha = a)

val DarkMs = MsColors(
    dark = true,
    bg = Color(0xFF0C0E12), surface = Color(0xFF13161B), surface2 = Color(0xFF1A1E24), surface3 = Color(0xFF22272E),
    border = Color(0xFF252A31), borderStrong = Color(0xFF333A43),
    text = Color(0xFFF5F6F7), text2 = Color(0xFFCFD4DC), muted = Color(0xFF94A0B0), faint = Color(0xFF6B7584),
    accent = Color(0xFF3B82F6), accentSoft = Color(0xFF3B82F6).soft(), accentText = Color(0xFF93C5FD), onAccent = Color.White,
    success = Color(0xFF47CD89), successSoft = Color(0xFF47CD89).soft(0.12f), warning = Color(0xFFFDB022), warningSoft = Color(0xFFFDB022).soft(0.12f),
    danger = Color(0xFFF97066), dangerSoft = Color(0xFFF97066).soft(0.12f),
    hot = Color(0xFFF97316), hotSoft = Color(0xFFF97316).soft(), warm = Color(0xFFEAAA08), warmSoft = Color(0xFFEAAA08).soft(0.13f),
    cold = Color(0xFF94A0B0), coldSoft = Color(0xFF94A0B0).soft(),
    device = Color(0xFFA48AFB), deviceSoft = Color(0xFFA48AFB).soft(), cloud = Color(0xFF53B1FD), cloudSoft = Color(0xFF53B1FD).soft(0.13f),
    stNew = Color(0xFF6B7584), stReady = Color(0xFF3B82F6), stContacted = Color(0xFF9B8AFB), stWon = Color(0xFF32D583), stLost = Color(0xFFF97066),
)

val LocalMs = staticCompositionLocalOf { LightMs }

/** The current MeetSnap colours: `val c = ms()` at the top of a composable. */
@Composable
@ReadOnlyComposable
fun ms(): MsColors = LocalMs.current

fun MsColors.score(score: String) = when (score) { "hot" -> hot; "cold" -> cold; else -> warm }
fun MsColors.scoreSoft(score: String) = when (score) { "hot" -> hotSoft; "cold" -> coldSoft; else -> warmSoft }
fun scoreLabel(score: String) = when (score) { "hot" -> "Hot"; "cold" -> "Cold"; else -> "Warm" }

private val type = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp))

@Composable
fun MeetSnapTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkMs else LightMs
    val scheme = if (c.dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
        outline = c.borderStrong, outlineVariant = c.border, error = c.danger,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accent, background = c.bg, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.surface2, onSurfaceVariant = c.muted,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface3,
        outline = c.borderStrong, outlineVariant = c.border, error = c.danger,
    )
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes) {
        // Text outside a Material Surface uses LocalContentColor, which defaults to black: use the theme's text colour.
        CompositionLocalProvider(LocalMs provides c, LocalContentColor provides c.text, content = content)
    }
}
