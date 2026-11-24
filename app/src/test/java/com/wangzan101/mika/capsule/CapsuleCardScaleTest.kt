package com.wangzan101.mika.capsule

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleCardScaleTest {
    @Test fun defaultsToNormal() {
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.DEFAULT)
        assertEquals(1f, CapsuleCardScale.DEFAULT.factor, 0f)
    }

    @Test fun unknownPercentFallsBackToNormal() {
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.parse(101))
        assertEquals(CapsuleCardScale.Normal, CapsuleCardScale.parse(0))
        assertEquals(CapsuleCardScale.XLarge, CapsuleCardScale.parse(130))
    }

    @Test fun normalKeepsTodaySWidth() {
        // The standard tier matches the status quo: 322dp when it fits, reduced to screen width − 24dp on narrow screens, and never below 240dp on very narrow ones
        assertEquals(322.dp, CapsuleCardScale.Normal.width(600))
        assertEquals(322.dp, CapsuleCardScale.Normal.width(360))
        assertEquals(296.dp, CapsuleCardScale.Normal.width(320))
        assertEquals(240.dp, CapsuleCardScale.Normal.width(250))
    }

    @Test fun scaledWidthNeverLeavesTheSamePixelBounds() {
        for (s in CapsuleCardScale.entries) {
            // Rendered width = raw dp × factor, and once converted every tier lands in the same effective range as the standard tier:
            // [240dp, screen width − 24dp]; on ultra-narrow screens (screen width − 24 < 240) the 240dp lower bound applies, as before
            for (screen in listOf(250, 320, 360, 411, 600, 840)) {
                val rendered = s.width(screen) * s.factor
                val upper = maxOf(screen - 24, 240).dp
                assert(rendered <= upper + 0.01.dp) { "$s on $screen -> $rendered" }
                assert(rendered >= 240.dp - 0.01.dp) { "$s on $screen -> $rendered" }
            }
        }
    }

    @Test fun wideScreenKeepsFullCardWidthWhenItFits() {
        // On a 600dp-wide screen (an unfolded foldable), 130% renders 322×1.3=418.6dp, which still fits within 576dp, so the width is not squeezed
        assertEquals(322.dp, CapsuleCardScale.XLarge.width(600))
        // On a 360dp narrow screen it is squeezed to the equivalent of screen width − 24dp
        assertEquals(336.dp, CapsuleCardScale.XLarge.width(360) * CapsuleCardScale.XLarge.factor)
    }
}
