package com.nadatube.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Nada {
    val Canvas = Color(0xFFF8F9FA)
    val Card = Color(0xFFFFFFFF)
    val Border = Color(0xFFE5E7EB)
    val Indigo = Color(0xFF4F46E5)
    val IndigoLight = Color(0xFF6366F1)
    val IndigoSoft = Color(0xFFEEF2FF)
    val IndigoInk = Color(0xFF3730A3)
    val Ink = Color(0xFF111827)
    val InkSoft = Color(0xFF374151)
    val Muted = Color(0xFF6B7280)
    val Faint = Color(0xFF9CA3AF)
    val Green = Color(0xFF059669)
    val GreenSoft = Color(0xFFECFDF5)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFFFFFBEB)
    val Rose = Color(0xFFE11D48)
}

private val colors = lightColorScheme(
    primary = Nada.Indigo,
    onPrimary = Color.White,
    primaryContainer = Nada.IndigoSoft,
    onPrimaryContainer = Nada.IndigoInk,
    secondary = Nada.IndigoLight,
    onSecondary = Color.White,
    secondaryContainer = Nada.IndigoSoft,
    onSecondaryContainer = Nada.IndigoInk,
    tertiary = Nada.Green,
    background = Nada.Canvas,
    onBackground = Nada.Ink,
    surface = Nada.Card,
    onSurface = Nada.Ink,
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Nada.Muted,
    surfaceContainer = Nada.Card,
    surfaceContainerLow = Nada.Canvas,
    surfaceContainerHigh = Nada.Card,
    surfaceContainerHighest = Color(0xFFF3F4F6),
    outline = Nada.Border,
    outlineVariant = Nada.Border,
    error = Nada.Rose,
)

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
fun NadaTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
