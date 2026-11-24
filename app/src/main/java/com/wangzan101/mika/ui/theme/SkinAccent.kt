package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Extracts the accent color from a background image: first find the hue of the image's dominant vivid color ([hue]),
 * then derive a shade ([tone]) on which white text and placement on the page are both legible. Pure computation, no Android involvement.
 */
object SkinAccent {
    /** Vivid pixels must make up at least this share for the image to count as having a dominant color; below that it's treated as a gray image and no color is taken from it. */
    private const val MIN_SHARE = 0.05
    private const val BUCKETS = 36

    /** [pixels] are ARGB. Returns null when the image is essentially gray (or fully transparent). */
    fun hue(pixels: IntArray): Float? {
        val weight = DoubleArray(BUCKETS)
        val count = IntArray(BUCKETS)
        var total = 0
        for (p in pixels) {
            if ((p ushr 24 and 0xFF) < 128) continue
            total++
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            val s = if (hi == 0f) 0f else (hi - lo) / hi
            if (s < 0.25f || hi < 0.2f) continue
            val bucket = (hueOf(r, g, b, hi, lo) / (360f / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            weight[bucket] += (s * hi).toDouble()
            count[bucket]++
        }
        if (total == 0) return null
        // Compare three adjacent buckets together, so a color landing exactly on a bucket boundary isn't split apart
        fun at(i: Int) = (i + BUCKETS) % BUCKETS
        val best = (0 until BUCKETS).maxBy { weight[at(it - 1)] + weight[it] + weight[at(it + 1)] }
        val share = (count[at(best - 1)] + count[best] + count[at(best + 1)]).toDouble() / total
        if (share < MIN_SHARE) return null
        // Weighted average over the three buckets, keyed on the middle one, to handle the 0/360 seam
        var sum = 0.0
        var w = 0.0
        for (d in -1..1) {
            val i = at(best + d)
            sum += (best + d + 0.5) * (360.0 / BUCKETS) * weight[i]
            w += weight[i]
        }
        return (((sum / w) % 360 + 360) % 360).toFloat()
    }

    /**
     * Uses the hue [hue] as the accent color and returns (accent color, text color on top of it).
     * The contrast for white text on it is no lower than the default blue, and its contrast with the page background is no lower than 3:1; if nothing works out, the default blue is used.
     */
    fun tone(hue: Float, dark: Boolean): Pair<Color, Color> {
        val base = if (dark) DarkColors else LightColors
        val floor = contrast(base.accent, base.onAccent)
        // Scan from light to dark for the first shade where white text is legible enough; in dark mode it must also be light enough against the page background
        var l = if (dark) 0.70f else 0.60f
        while (l >= 0.15f) {
            val c = Color.hsl(hue, 0.7f, l)
            if (contrast(c, Color.White) >= floor && contrast(c, base.bg) >= 3.0) return c to Color.White
            l -= 0.01f
        }
        return base.accent to base.onAccent
    }

    private fun hueOf(r: Float, g: Float, b: Float, hi: Float, lo: Float): Float {
        val d = hi - lo
        if (d == 0f) return 0f
        val h = when (hi) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return (h + 360f) % 360f
    }
}

/** WCAG 2 contrast ratio. */
internal fun contrast(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/** WCAG 2 relative luminance (sRGB). */
internal fun luminance(c: Color): Double {
    fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}
