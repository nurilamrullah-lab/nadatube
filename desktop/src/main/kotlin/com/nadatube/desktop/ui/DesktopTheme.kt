package com.nadatube.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Token warna desktop — nilainya SAMA dengan palet Android di
 * `app/.../ui/theme/Theme.kt` (LightPalette/DarkPalette), disalin ke sini karena
 * modul Android memakai androidx.compose.material3 yang tidak tersedia di JVM desktop;
 * Compose Multiplatform memakai paket yang sama (`androidx.compose.*`) tapi artefak
 * yang berbeda, sehingga token harus didefinisikan ulang di modul ini.
 */
data class DesktopPalette(
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
    // Token Fluent 2 (Windows 11) — tambahan dari redesign, token lama dipertahankan.
    val mica: Color,
    val layer: Color,
    val cardHover: Color,
    val acrylic: Color,
    val flyout: Color,
    val strokeSubtle: Color,
)

val DesktopLightPalette = DesktopPalette(
    dark = false,
    canvas = Color(0xFFF8F9FA), card = Color(0xFFFFFFFF), border = Color(0xFFE5E7EB),
    indigo = Color(0xFF4F46E5), indigoLight = Color(0xFF6366F1), indigoSoft = Color(0xFFEEF2FF),
    indigoSoft2 = Color(0xFFE0E7FF), indigoInk = Color(0xFF3730A3),
    ink = Color(0xFF111827), inkSoft = Color(0xFF374151), muted = Color(0xFF6B7280), faint = Color(0xFF9CA3AF),
    green = Color(0xFF059669), greenSoft = Color(0xFFECFDF5),
    amber = Color(0xFFF59E0B), amberSoft = Color(0xFFFFFBEB), amberInk = Color(0xFFB45309),
    rose = Color(0xFFE11D48), roseSoft = Color(0xFFFFF1F2),
    surfaceVariant = Color(0xFFF3F4F6),
    mica = Color(0xFFF1F3F8), layer = Color(0xFFF8F9FA), cardHover = Color(0xFFECEEF2),
    acrylic = Color(0xDBFFFFFF), flyout = Color(0xF5FFFFFF), strokeSubtle = Color(0x0F111827),
)

val DesktopDarkPalette = DesktopPalette(
    dark = true,
    canvas = Color(0xFF121212), card = Color(0xFF1E1E22), border = Color(0xFF2F2F36),
    indigo = Color(0xFF6366F1), indigoLight = Color(0xFF818CF8), indigoSoft = Color(0xFF25254A),
    indigoSoft2 = Color(0xFF30305F), indigoInk = Color(0xFFC7D2FE),
    ink = Color(0xFFF4F4F5), inkSoft = Color(0xFFD4D4D8), muted = Color(0xFFA1A1AA), faint = Color(0xFF71717A),
    green = Color(0xFF34D399), greenSoft = Color(0xFF0F2A22),
    amber = Color(0xFFFBBF24), amberSoft = Color(0xFF2B2310), amberInk = Color(0xFFFCD34D),
    rose = Color(0xFFFB7185), roseSoft = Color(0xFF2D1519),
    surfaceVariant = Color(0xFF27272D),
    mica = Color(0xFF121216), layer = Color(0xFF18181E), cardHover = Color(0xFF262630),
    acrylic = Color(0xD618181F), flyout = Color(0xF522222B), strokeSubtle = Color(0x11FFFFFF),
)

/** Token warna aktif; diganti saat tema berubah, seluruh UI ikut berubah. */
object Nada {
    var palette by mutableStateOf(DesktopLightPalette)
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
    val Mica get() = palette.mica
    val Layer get() = palette.layer
    val CardHover get() = palette.cardHover
    val Acrylic get() = palette.acrylic
    val Flyout get() = palette.flyout
    val StrokeSubtle get() = palette.strokeSubtle
    val IsDark get() = palette.dark
}

private fun scheme(p: DesktopPalette): ColorScheme {
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
        surfaceContainer = p.card, surfaceContainerLow = p.layer,
        surfaceContainerHigh = p.card, surfaceContainerHighest = p.surfaceVariant,
        outline = p.border, outlineVariant = p.border, error = p.rose,
    )
}

@Composable
fun NadaTubeTheme(dark: Boolean, content: @Composable () -> Unit) {
    Nada.palette = if (dark) DesktopDarkPalette else DesktopLightPalette
    MaterialTheme(colorScheme = scheme(Nada.palette), content = content)
}
