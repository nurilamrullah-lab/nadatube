package com.nadatube.app.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure colour maths: ambient glow tuning for both themes, and WCAG contrast. */
object ColorMath {
    private const val LIGHT_CANVAS = 0xF8F9FA
    private const val DARK_CANVAS = 0x121212

    /**
     * Cover colour → a glow that stays visible on a near-white or near-black canvas.
     * HSL lightness is not perceived brightness (yellow at L=0.6 is far brighter than navy at
     * L=0.6), so after clamping we nudge lightness until the glow clears a minimum contrast
     * against the canvas it sits on.
     */
    fun tuneGlow(rgb: Int, dark: Boolean): Int {
        val (h, s, l) = rgbToHsl(rgb)
        val sat = if (dark) s.coerceIn(0.35f, 0.9f) else s.coerceIn(0.45f, 0.95f)
        var light = if (dark) l.coerceIn(0.48f, 0.72f) else l.coerceIn(0.38f, 0.62f)
        val canvas = if (dark) DARK_CANVAS else LIGHT_CANVAS
        val minContrast = if (dark) 3.0 else 1.8
        var out = hslToRgb(h, sat, light)
        var guard = 0
        while (contrast(out, canvas) < minContrast && guard++ < 40) {
            light = (light + if (dark) 0.02f else -0.02f).coerceIn(0.15f, 0.9f)
            out = hslToRgb(h, sat, light)
        }
        return out
    }

    fun luminance(rgb: Int): Double {
        fun channel(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)
    }

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun rgbToHsl(rgb: Int): Triple<Float, Float, Float> {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return Triple(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        return Triple(h, s, l)
    }

    fun hslToRgb(h: Float, s: Float, l: Float): Int {
        if (s == 0f) {
            val v = (l * 255f + 0.5f).toInt()
            return (v shl 16) or (v shl 8) or v
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        fun c(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (c(hue(h + 1f / 3f)) shl 16) or (c(hue(h)) shl 8) or c(hue(h - 1f / 3f))
    }

    fun hueDistance(a: Int, b: Int): Float {
        val d = abs(rgbToHsl(a).first - rgbToHsl(b).first)
        return min(d, 1f - d)
    }
}
