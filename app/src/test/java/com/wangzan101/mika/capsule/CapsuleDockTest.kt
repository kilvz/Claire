package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapsuleDockTest {
    // A 1000×2000 screen with a status bar of 80 and a bottom gesture bar of 40; the visible capsule sits 30 from the sides, 20 from top and bottom, with a 10 shadow edge
    private val area = DockArea.of(OverlayArea(1000, 2000, insetTop = 80, insetBottom = 40), margin = 30, gap = 20, room = 10)

    @Test fun areaAvoidsBarsAndKeepsMargins() {
        assertEquals(DockArea(1000, left = 20, top = 90, right = 980, bottom = 1950, edgeLeft = -10, edgeRight = 1010), area)
    }

    @Test fun fractionAndYRoundTrip() {
        assertEquals(90, CapsuleDocking.y(0f, area, 100))
        assertEquals(1850, CapsuleDocking.y(1f, area, 100))
        assertEquals(970, CapsuleDocking.y(0.5f, area, 100))
        assertEquals(0.5f, CapsuleDocking.fraction(970, area, 100), 0.001f)
        // Out-of-range values are clamped
        assertEquals(0f, CapsuleDocking.fraction(-500, area, 100))
        assertEquals(1f, CapsuleDocking.fraction(5000, area, 100))
        assertEquals(90, CapsuleDocking.y(-3f, area, 100))
    }

    @Test fun sameFractionAfterRotation() {
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val f = CapsuleDocking.fraction(CapsuleDocking.y(0.25f, area, 100), area, 100)
        val y = CapsuleDocking.y(f, landscape, 100)
        // Landscape: 50 at the top, 990 at the bottom, a quarter of the 840 range
        assertEquals(50 + 210, y)
        // The notch is on the left: docking on the left side keeps clear of it
        assertEquals(100, CapsuleDocking.dockedX(DockSide.Left, landscape, 200))
        assertEquals(1780, CapsuleDocking.dockedX(DockSide.Right, landscape, 200))
    }

    @Test fun tallerWindowMovesUpToFit() {
        // The card is 600 tall and does not fit downward from the capsule's height
        assertEquals(1950 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), area, 600))
        assertEquals(500, CapsuleDocking.fitY(500, area, 600))
        // Taller than the whole range: it sticks to the top
        assertEquals(90, CapsuleDocking.fitY(500, area, 5000))
    }

    @Test fun dragStopsAtTheScreenEdge() {
        assertEquals(-10, CapsuleDocking.dragX(-300, area, 200))
        assertEquals(1010 - 200, CapsuleDocking.dragX(5000, area, 200))
        assertEquals(400, CapsuleDocking.dragX(400, area, 200))
    }

    @Test fun releaseSnapsToTheNearestSide() {
        assertEquals(CapsuleDock(DockSide.Left, 0f), CapsuleDocking.release(250, 90, 200, 100, area))
        assertEquals(DockSide.Right, CapsuleDocking.release(450, 500, 200, 100, area).side)
        assertEquals(DockSide.Left, CapsuleDocking.release(380, 500, 200, 100, area).side)
        assertEquals(20, CapsuleDocking.dockedX(DockSide.Left, area, 200))
        assertEquals(780, CapsuleDocking.dockedX(DockSide.Right, area, 200))
        // With the notch on the left the clearances of the two sides differ: 100 on the left, 20 on the right
        val lopsided = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(100, CapsuleDocking.edgeOffset(DockSide.Left, lopsided))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Right, lopsided))
    }

    @Test fun keyboardPushesTheRangeUp() {
        // The IME top is at 1200: the visible part stays 20 away from it
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190, up.bottom)
        assertEquals(1190 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), up, 600))
        assertEquals(area, area.above(null, 20, 10))
        // The IME is taller than the allowed range: do not go past the top
        assertEquals(area.top, area.above(10, 20, 10).bottom)
    }

    @Test fun keyboardDragSavesTheReleaseHeightInTheFullArea() {
        val up = area.above(1200, gap = 20, room = 10)
        val releaseY = 900
        val dock = CapsuleDocking.release(450, releaseY, 200, 100, up, fractionArea = area)

        // The release fraction is recorded against the full area; the place performed after the snapping animation still restores the release height, and placing directly without animation does the same
        assertEquals((releaseY - area.top) / (area.bottom - area.top - 100).toFloat(), dock.fraction, 0.0001f)
        assertEquals(releaseY, CapsuleDocking.y(dock.fraction, area, 100))
        assertEquals(releaseY, CapsuleDocking.fitY(CapsuleDocking.y(dock.fraction, area, 100), up, 100))
        // After the IME is dismissed it still returns to this height
        assertEquals(releaseY, CapsuleDocking.fitY(CapsuleDocking.y(dock.fraction, area, 100), area, 100))

        // On rotation the same full-area fraction is still used, then clamped to the IME range of the new orientation
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val landscapeUp = landscape.above(600, gap = 20, room = 10)
        val rotatedY = CapsuleDocking.y(dock.fraction, landscape, 100)
        assertEquals(rotatedY, CapsuleDocking.fitY(rotatedY, landscapeUp, 100))
    }

    @Test fun cardKeepsTheTallestImeOfThisRound() {
        val jump = 300
        // The IME appears: top at 1200, remember it
        assertEquals(1200, CapsuleDocking.tallestImeTop(null, 1200, jump))
        // Typing pinyin adds a candidate row (the keyboard gets taller, its top moves to 1120): follow at once so the card is not left behind the IME
        assertEquals(1120, CapsuleDocking.tallestImeTop(1200, 1120, jump))
        // The candidate row retracts (top back to 1200): it only shrank by one row, do not follow
        assertEquals(1120, CapsuleDocking.tallestImeTop(1120, 1200, jump))
        // Switching to a slightly taller IME next to it (shrink below one threshold): do not follow either
        assertEquals(1120, CapsuleDocking.tallestImeTop(1120, 1400, jump))
        // Switching to an emoji panel and back shrinks a lot in one go: follow, and the card is restored
        assertEquals(1500, CapsuleDocking.tallestImeTop(1120, 1500, jump))
        // Dismissed: clear it, and record it afresh next time it appears
        assertNull(CapsuleDocking.tallestImeTop(1500, null, jump))
    }

    // ---------- Card ----------

    /** The window height of the tallest card (1160 visible) together with its top and bottom shadow edges. */
    private val full = 1160 + 20

    @Test fun cardCapIsAShareOfTheScreen() {
        // A screen 2000 tall: at most 58%, which is what fits (chat cards are exactly this tall, task cards at most this tall)
        assertEquals(1160, CardDocking.maxHeight(2000, area, room = 10))
        // The IME appears (top at 1200): shrink to above it, so the middle stretch gets shorter
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 90 - 20, CardDocking.maxHeight(2000, up, room = 10))
        // Landscape: computed from the landscape height
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(580, CardDocking.maxHeight(1000, landscape, room = 10))
    }

    @Test fun cardTopFollowsItsFraction() {
        // The top is placed proportionally between 90..(1950 - tallest card): center, top and bottom of the tallest card
        assertEquals(90 + 340, CardDocking.y(0.5f, area, area, full, full))
        assertEquals(90, CardDocking.y(0f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.y(1f, area, area, full, full))
        // The same fraction after rotating
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(50 + 170, CardDocking.y(0.5f, landscape, landscape, 600, 600))
    }

    @Test fun shorterTaskCardSharesTheTopWithTheChatCard() {
        // By content the task card is only 500 tall: its top matches the chat card at the same fraction while it is shorter downward
        for (f in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(CardDocking.y(f, area, area, full, full), CardDocking.y(f, area, area, full, 500))
        }
        // A fraction recorded earlier against the fixed-height card is read as a fraction of the top: the chat card returns to its place
        assertEquals(CapsuleDocking.y(0.7f, area, full), CardDocking.y(0.7f, area, area, full, full))
    }

    @Test fun switchingCardsKeepsTheTopAndShiftsUpOnlyWhenItOverflows() {
        val top = CardDocking.y(1f, area, area, full, 500)
        // Switching the task card to the chat card: it fits, so the top stays put and it grows downward
        assertEquals(top, CardDocking.y(1f, area, area, full, full))
        // The IME appears (top at 1200): the short card stays put if it fits, while the chat card (shrunk to 1100 + shadow edge) moves up just enough to fit
        val up = area.above(1200, gap = 20, room = 10)
        val shortTop = CardDocking.y(0f, area, up, full, 500)
        assertEquals(90, shortTop)
        assertEquals(90 + 204, CardDocking.y(0.3f, area, up, full, 500))
        assertEquals(1190 - 700, CardDocking.y(1f, area, up, full, 700))
        assertEquals(90, CardDocking.y(0.5f, area, up, full, 1100))
    }

    @Test fun cardDragStaysOnScreenAndRemembersTheTopFraction() {
        assertEquals(90, CardDocking.dragY(430, -1000f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, full))
        assertEquals(530, CardDocking.dragY(430, 100f, area, area, full, full))
        // Dragging the short task card all the way down: the top stops where the chat card would also fit, and the recorded fraction stays within range
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, 500))
        assertEquals(1f, CardDocking.release(1950 - full, area, full))
        assertEquals(0.5f, CardDocking.release(430, area, full), 0.001f)
        // Recorded against the range that does not avoid the IME: after the IME is dismissed it is still in place, and the top is equally unchanged when switching to another height
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, full))
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, 500))
        // Dragging while the IME is up: the whole card stays above it
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 500, CardDocking.dragY(430, 2000f, area, up, full, 500))
    }
}
