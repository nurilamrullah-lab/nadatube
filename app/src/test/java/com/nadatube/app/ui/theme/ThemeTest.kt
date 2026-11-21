package com.nadatube.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.nadatube.app.data.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    private fun rgb(c: Color) = c.toArgb() and 0xFFFFFF

    // ---------------------------------------------------------------- mode logic

    @Test
    fun `mode resolves against the system setting`() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `unknown or missing stored value falls back to system default`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("PURPLE"))
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.of(it.name)) }
    }

    @Test
    fun `the three requested options exist with their labels`() {
        assertEquals(
            listOf("Terang (Light)", "Gelap (Dark)", "Ikuti Sistem (System Default)"),
            listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).map { it.label },
        )
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun `theme choice survives restarting the app`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("nadatube_settings", 0).edit().clear().commit()

        val first = SettingsStore(ctx)
        assertEquals("fresh install follows the system", ThemeMode.SYSTEM, first.themeMode.value)
        first.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, first.themeMode.value)

        val reopened = SettingsStore(ctx)
        assertEquals(ThemeMode.DARK, reopened.themeMode.value)
        reopened.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, SettingsStore(ctx).themeMode.value)
    }

    // ---------------------------------------------------------------- palettes

    @Test
    fun `palettes use the requested canvas and card colours`() {
        assertEquals(0xF8F9FA, rgb(LightPalette.canvas))
        assertEquals(0xFFFFFF, rgb(LightPalette.card))
        assertEquals(0xE5E7EB, rgb(LightPalette.border))
        assertEquals(0x4F46E5, rgb(LightPalette.indigo))
        assertEquals(0x121212, rgb(DarkPalette.canvas))
        assertTrue("dark cards are lighter than the canvas for separation", ColorMath.luminance(rgb(DarkPalette.card)) > ColorMath.luminance(rgb(DarkPalette.canvas)))
    }

    @Test
    fun `text and accents are readable in both themes`() {
        listOf(LightPalette, DarkPalette).forEach { p ->
            val name = if (p.dark) "dark" else "light"
            fun c(fg: Color, bg: Color) = ColorMath.contrast(rgb(fg), rgb(bg))
            for (surface in listOf(p.canvas, p.card)) {
                assertTrue("$name ink/surface ${c(p.ink, surface)}", c(p.ink, surface) >= 12.0)
                assertTrue("$name inkSoft ${c(p.inkSoft, surface)}", c(p.inkSoft, surface) >= 7.0)
                assertTrue("$name muted ${c(p.muted, surface)}", c(p.muted, surface) >= 4.5)
                assertTrue("$name accent icons ${c(p.indigo, surface)}", c(p.indigo, surface) >= 3.0)
                assertTrue("$name rose ${c(p.rose, surface)}", c(p.rose, surface) >= 3.0)
                assertTrue("$name green ${c(p.green, surface)}", c(p.green, surface) >= 3.0)
            }
            assertTrue("$name white on indigo buttons ${c(Color.White, p.indigo)}", c(Color.White, p.indigo) >= 4.4)
            assertTrue("$name indigoInk on soft ${c(p.indigoInk, p.indigoSoft)}", c(p.indigoInk, p.indigoSoft) >= 4.5)
            assertTrue("$name amberInk on soft ${c(p.amberInk, p.amberSoft)}", c(p.amberInk, p.amberSoft) >= 4.5)
            assertTrue("$name green on greenSoft ${c(p.green, p.greenSoft)}", c(p.green, p.greenSoft) >= 3.0)
            assertTrue("$name border is visible but subtle", c(p.border, p.card) in 1.1..2.5)
        }
    }

    // ---------------------------------------------------------------- live switching

    @Test
    fun `switching mode at runtime recolours the whole tree`() {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var seenBackground = Color.Unspecified
        var seenIsDark = false
        compose.setContent {
            NadaTubeTheme(mode) {
                seenBackground = MaterialTheme.colorScheme.background
                seenIsDark = Nada.IsDark
                Box { Text("NadaTube") }
            }
        }
        compose.waitForIdle()
        assertEquals(0xF8F9FA, rgb(seenBackground))
        assertFalse(seenIsDark)

        mode = ThemeMode.DARK
        compose.waitForIdle()
        assertEquals(0x121212, rgb(seenBackground))
        assertTrue(seenIsDark)
        assertEquals(DarkPalette.card, Nada.Card)

        mode = ThemeMode.LIGHT
        compose.waitForIdle()
        assertEquals(LightPalette.card, Nada.Card)
    }

    @Test
    fun `system default follows the device night qualifier`() {
        // Robolectric defaults to a day configuration.
        var dark = true
        compose.setContent { NadaTubeTheme(ThemeMode.SYSTEM) { dark = Nada.IsDark } }
        compose.waitForIdle()
        assertFalse(dark)
    }

    // ---------------------------------------------------------------- album-art glow

    private val covers = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "near-white" to 0xF4F4F4, "near-black" to 0x0A0A0A,
        "red" to 0xE53935, "navy" to 0x0D1B4C, "yellow" to 0xFFEB3B, "gray" to 0x808080, "green" to 0x2E7D32,
    )

    @Test
    fun `glow stays visible on a dark canvas`() {
        covers.forEach { (name, rgb) ->
            val glow = ColorMath.tuneGlow(rgb, dark = true)
            val l = ColorMath.rgbToHsl(glow).third
            assertTrue("$name lightness $l", l in 0.47f..0.9f)
            assertTrue("$name contrast", ColorMath.contrast(glow, rgb(DarkPalette.canvas)) >= 3.0)
        }
    }

    @Test
    fun `glow stays visible on a light canvas without turning muddy`() {
        covers.forEach { (name, rgb) ->
            val glow = ColorMath.tuneGlow(rgb, dark = false)
            val l = ColorMath.rgbToHsl(glow).third
            assertTrue("$name lightness $l", l in 0.15f..0.63f)
            assertTrue("$name contrast", ColorMath.contrast(glow, rgb(LightPalette.canvas)) >= 1.8)
        }
    }

    @Test
    fun `glow keeps the cover's hue`() {
        listOf(0xE53935, 0x1E88E5, 0x43A047, 0xFB8C00, 0x8E24AA).forEach { rgb ->
            listOf(true, false).forEach { dark ->
                assertTrue("hue drift for ${Integer.toHexString(rgb)} dark=$dark", ColorMath.hueDistance(rgb, ColorMath.tuneGlow(rgb, dark)) < 0.02f)
            }
        }
    }

    @Test
    fun `hsl round trip and contrast maths`() {
        listOf(0x4F46E5, 0x123456, 0xFEDCBA, 0x808080).forEach { rgb ->
            val (h, s, l) = ColorMath.rgbToHsl(rgb)
            val back = ColorMath.hslToRgb(h, s, l)
            for (shift in listOf(16, 8, 0)) assertTrue(Math.abs(((rgb shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 1)
        }
        assertEquals(21.0, ColorMath.contrast(0x000000, 0xFFFFFF), 0.01)
        assertEquals(1.0, ColorMath.contrast(0x777777, 0x777777), 0.0001)
        assertNotEquals(ColorMath.tuneGlow(0x000000, true), ColorMath.tuneGlow(0x000000, false))
    }
}
