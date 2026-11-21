package com.nadatube.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String, val description: String) {
    LIGHT("Terang (Light)", "Kanvas bersih, kartu putih"),
    DARK("Gelap (Dark)", "Nyaman untuk memutar musik malam hari"),
    SYSTEM("Ikuti Sistem (System Default)", "Mengikuti pengaturan perangkat");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun of(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

data class NadaPalette(
    val dark: Boolean,
    val canvas: Color,
    val card: Color,
    val border: Color,
    val indigo: Color,
    val indigoLight: Color,
    val indigoSoft: Color,
    val indigoSoft2: Color,
    val indigoInk: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val faint: Color,
    val green: Color,
    val greenSoft: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberInk: Color,
    val rose: Color,
    val roseSoft: Color,
    val surfaceVariant: Color,
)

val LightPalette = NadaPalette(
    dark = false,
    canvas = Color(0xFFF8F9FA), card = Color(0xFFFFFFFF), border = Color(0xFFE5E7EB),
    indigo = Color(0xFF4F46E5), indigoLight = Color(0xFF6366F1), indigoSoft = Color(0xFFEEF2FF),
    indigoSoft2 = Color(0xFFE0E7FF), indigoInk = Color(0xFF3730A3),
    ink = Color(0xFF111827), inkSoft = Color(0xFF374151), muted = Color(0xFF6B7280), faint = Color(0xFF9CA3AF),
    green = Color(0xFF059669), greenSoft = Color(0xFFECFDF5),
    amber = Color(0xFFF59E0B), amberSoft = Color(0xFFFFFBEB), amberInk = Color(0xFFB45309),
    rose = Color(0xFFE11D48), roseSoft = Color(0xFFFFF1F2),
    surfaceVariant = Color(0xFFF3F4F6),
)

val DarkPalette = NadaPalette(
    dark = true,
    canvas = Color(0xFF121212), card = Color(0xFF1E1E22), border = Color(0xFF2F2F36),
    indigo = Color(0xFF6366F1), indigoLight = Color(0xFF818CF8), indigoSoft = Color(0xFF25254A),
    indigoSoft2 = Color(0xFF30305F), indigoInk = Color(0xFFC7D2FE),
    ink = Color(0xFFF4F4F5), inkSoft = Color(0xFFD4D4D8), muted = Color(0xFFA1A1AA), faint = Color(0xFF71717A),
    green = Color(0xFF34D399), greenSoft = Color(0xFF0F2A22),
    amber = Color(0xFFFBBF24), amberSoft = Color(0xFF2B2310), amberInk = Color(0xFFFCD34D),
    rose = Color(0xFFFB7185), roseSoft = Color(0xFF2D1519),
    surfaceVariant = Color(0xFF27272D),
)

/**
 * Colour tokens used across the UI. Backed by snapshot state, so every read — in composition or
 * inside a draw lambda — re-runs when the theme switches, without threading the palette through
 * every composable.
 */
object Nada {
    var palette by mutableStateOf(LightPalette)
        internal set

    val Canvas get() = palette.canvas
    val Card get() = palette.card
    val Border get() = palette.border
    val Indigo get() = palette.indigo
    val IndigoLight get() = palette.indigoLight
    val IndigoSoft get() = palette.indigoSoft
    val IndigoSoft2 get() = palette.indigoSoft2
    val IndigoInk get() = palette.indigoInk
    val Ink get() = palette.ink
    val InkSoft get() = palette.inkSoft
    val Muted get() = palette.muted
    val Faint get() = palette.faint
    val Green get() = palette.green
    val GreenSoft get() = palette.greenSoft
    val Amber get() = palette.amber
    val AmberSoft get() = palette.amberSoft
    val AmberInk get() = palette.amberInk
    val Rose get() = palette.rose
    val RoseSoft get() = palette.roseSoft
    val IsDark get() = palette.dark
}

private fun scheme(p: NadaPalette): ColorScheme {
    val seed = if (p.dark) {
        darkColorScheme(primary = p.indigo, onPrimary = Color.White, primaryContainer = p.indigoSoft, onPrimaryContainer = p.indigoInk)
    } else {
        lightColorScheme(primary = p.indigo, onPrimary = Color.White, primaryContainer = p.indigoSoft, onPrimaryContainer = p.indigoInk)
    }
    return seed.copy(
        secondary = p.indigoLight, onSecondary = Color.White,
        secondaryContainer = p.indigoSoft, onSecondaryContainer = p.indigoInk,
        tertiary = p.green,
        background = p.canvas, onBackground = p.ink,
        surface = p.card, onSurface = p.ink,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.muted,
        surfaceContainer = p.card, surfaceContainerLow = p.canvas,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.surfaceVariant,
        outline = p.border, outlineVariant = p.border, error = p.rose,
    )
}

private val base = FontFamily.SansSerif

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = base, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = base, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = base, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = base, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = base, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.4.sp),
)

private val shapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun NadaTubeTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = if (mode.isDark(isSystemInDarkTheme())) DarkPalette else LightPalette
    // Written before children compose so the first frame already uses the right colours.
    if (Nada.palette !== palette) Nada.palette = palette
    MaterialTheme(colorScheme = scheme(palette), typography = typography, shapes = shapes, content = content)
}
