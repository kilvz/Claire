package com.wangzan101.mika.ui.theme

import androidx.compose.ui.graphics.Color
import com.wangzan101.mika.settings.SkinSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background image: color extraction recognizes the dominant color and recognizes a gray image; the extracted accent and the text under the scrim stay legible. */
class SkinAccentTest {
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun fill(n: Int, color: Int) = IntArray(n) { color }

    /** Distance between hues on the circle. */
    private fun hueDistance(a: Float, b: Float): Float = minOf(Math.floorMod((a - b).toInt(), 360), Math.floorMod((b - a).toInt(), 360)).toFloat()

    @Test fun findsTheMainColor() {
        assertTrue(hueDistance(SkinAccent.hue(fill(100, argb(220, 30, 30)))!!, 0f) <= 6f)
        assertTrue(hueDistance(SkinAccent.hue(fill(100, argb(30, 60, 220)))!!, 230f) <= 8f)
        // Ninety percent gray, ten percent green: the dominant color is green
        val mostlyGray = fill(90, argb(128, 128, 128)) + fill(10, argb(40, 200, 60))
        assertTrue(hueDistance(SkinAccent.hue(mostlyGray)!!, 128f) <= 8f)
    }

    @Test fun grayOrTransparentImagesHaveNoMainColor() {
        assertNull(SkinAccent.hue(fill(100, argb(128, 128, 128))))
        assertNull(SkinAccent.hue(fill(100, argb(250, 250, 250))))
        assertNull(SkinAccent.hue(fill(100, argb(220, 30, 30, a = 0))))
        // Too few vivid pixels (under 5%) does not count either
        assertNull(SkinAccent.hue(fill(98, argb(20, 20, 20)) + fill(2, argb(220, 30, 30))))
        assertNull(SkinAccent.hue(IntArray(0)))
    }

    @Test fun redsOnBothSidesOfZeroStayRed() {
        val reds = fill(50, argb(230, 20, 40)) + fill(50, argb(230, 40, 20))
        assertTrue(hueDistance(SkinAccent.hue(reds)!!, 0f) <= 8f)
    }

    @Test fun everyHueGivesALegibleAccent() {
        for (dark in listOf(false, true)) {
            val base = if (dark) DarkColors else LightColors
            val floor = contrast(base.accent, base.onAccent)
            for (h in 0 until 360 step 10) {
                val (color, on) = SkinAccent.tone(h.toFloat(), dark)
                assertTrue("hue $h dark=$dark on-accent", contrast(color, on) >= floor - 0.01)
                assertTrue("hue $h dark=$dark vs page", contrast(color, base.bg) >= 3.0)
            }
        }
    }

    @Test fun textStaysReadableOverAnyImageAtTheLightestScrim() {
        // Worst-case image: pure white or pure black. At the lightest scrim, body text contrast is ≥ 4.5 and secondary text ≥ 3
        val alpha = SkinSpec.MIN_SCRIM / 100f
        for (c in listOf(LightColors, DarkColors)) {
            for (image in listOf(Color.White, Color.Black)) {
                val page = over(c.bg, alpha, image)
                assertTrue("ink dark=${c.isDark} image=$image", contrast(c.ink, page) >= 4.5)
                assertTrue("ink2 dark=${c.isDark} image=$image", contrast(c.ink2, page) >= 3.0)
            }
        }
    }

    @Test fun scrimRangeIsSane() {
        assertTrue(SkinSpec.MIN_SCRIM < SkinSpec.DEFAULT_SCRIM && SkinSpec.DEFAULT_SCRIM < SkinSpec.MAX_SCRIM)
        assertNotNull(SkinSpec().blur)
        assertEquals(0f, SkinSpec().focus)
    }

    /** [top] drawn over [under] at opacity [alpha] (blended per sRGB component, the same way Android renders it). */
    private fun over(top: Color, alpha: Float, under: Color) = Color(
        red = top.red * alpha + under.red * (1 - alpha),
        green = top.green * alpha + under.green * (1 - alpha),
        blue = top.blue * alpha + under.blue * (1 - alpha),
    )
}
