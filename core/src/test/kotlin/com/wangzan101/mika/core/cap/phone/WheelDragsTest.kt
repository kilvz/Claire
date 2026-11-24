package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Runs [WheelDrags] against a simulated wheel drawn by the app itself: dragging is the only option, and reading
 * back returns [radius] items above and below the selected one; the selected item's name may carry a state word.
 * Reproduces the clock on a real device: both the 24-hour and minute wheels wrap, the selected item's label is a
 * Chinese "selected" prefix plus 21, and its neighbors are 20 and 22.
 */
class WheelDragsTest {
    /**
     * [size] values starting at 0, with [value] selected; dragging one row up by [rowPx] pixels turns one item
     * (the number grows, or shrinks when [upIncreases] is false); the first [deadDrags] drags have no effect;
     * [label] is each item's name (the second argument is true for the selected item).
     */
    private class Wheel(
        var value: Int,
        val size: Int,
        val wrap: Boolean,
        val radius: Int = 1,
        val rowPx: Int = 80,
        val upIncreases: Boolean = true,
        var deadDrags: Int = 0,
        val label: (Int, Boolean) -> String = { v, sel -> if (sel) "已选中，$v" else "$v" },
    ) {
        var drags = 0
        private fun visibleValues(): List<Pair<Int, Boolean>> = (-radius..radius).mapNotNull { o ->
            val v = value + o
            val shown = if (wrap) Math.floorMod(v, size) else v.takeIf { it in 0 until size }
            shown?.let { it to (o == 0) }
        }
        fun items() = visibleValues().map { (v, sel) -> label(v, sel) }
        fun selected() = visibleValues().indexOfFirst { it.second }
        /** The centers of the read-back items: laid out assuming 80 pixels per row (the actual row is [rowPx]) to exercise calibration. */
        fun centers() = visibleValues().indices.map { 300 + it * 80 }
        fun drag(upPx: Int) {
            drags++
            if (deadDrags > 0) { deadDrags--; return }
            val n = (upPx.toDouble() / rowPx).roundToInt() * if (upIncreases) 1 else -1
            value = if (wrap) Math.floorMod(value + n, size) else (value + n).coerceIn(0, size - 1)
        }
    }

    /** Runs until it stops and returns the last step plus how many steps the first drag covered. */
    private fun run(w: Wheel, target: String, rowHeight: Int = 80): Pair<WheelDrags.Step, Int?> {
        val plan = WheelDrags(target, rowHeight)
        var dragged = 0
        var first: Int? = null
        repeat(60) {
            when (val s = plan.next(w.items(), w.selected(), w.centers(), dragged)) {
                is WheelDrags.Step.Drag -> {
                    if (first == null) first = s.steps
                    dragged = (s.steps * plan.itemHeight * s.boost).roundToInt()
                    w.drag(dragged)
                }
                else -> return s to first
            }
        }
        error("did not stop")
    }

    @Test fun hoursFrom21To7WithStatePrefixedLabel() {
        val w = Wheel(21, 24, wrap = true)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    @Test fun minutesFrom21To25() {
        val w = Wheel(21, 60, wrap = true)
        val (step, first) = run(w, "25")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(25, w.value)
        assertEquals(4, first)
    }

    @Test fun englishStatePrefixAndUnitSuffix() {
        val en = Wheel(21, 24, wrap = true, label = { v, sel -> if (sel) "Selected, $v" else "$v" })
        assertEquals(WheelDrags.Step.Done, run(en, "7").first)
        assertEquals(7, en.value)
        val unit = Wheel(21, 24, wrap = true, label = { v, _ -> "${v}点" })
        assertEquals(WheelDrags.Step.Done, run(unit, "7").first)
        assertEquals(7, unit.value)
    }

    /** When 23 → 0 is visible it takes the short way round: 21 → 7 goes 10 steps forward, not 14 back. */
    @Test fun wrapSeenTakesTheShortWayRound() {
        val w = Wheel(21, 24, wrap = true, radius = 3)
        val (step, first) = run(w, "7")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(10, first)
        assertEquals(7, w.value)
    }

    /** The first drag has no effect (too short, taken as a tap): that is not treated as the end, so it drags farther and retries. */
    @Test fun aDragWithNoEffectIsRetriedNotReportedAsTheEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 1)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    /** The wheel never moves however it is dragged: report not_moving (the model swipes itself), not at_end. */
    @Test fun wheelThatNeverMovesIsNotMovingNotAtEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 100)
        assertEquals(WheelDrags.Step.Stop("not_moving"), run(w, "7").first)
        assertEquals(3, w.drags)
        assertEquals(21, w.value)
    }

    /** Only when a non-wrapping wheel is dragged to its end and no items remain in that direction is it really at the end. */
    @Test fun nonWrappingWheelStopsAtItsEnd() {
        val w = Wheel(8, 11, wrap = false)
        assertEquals(WheelDrags.Step.Stop("at_end"), run(w, "15").first)
        assertEquals(10, w.value)
    }

    /** A wheel whose number shrinks when dragged up: the direction is calibrated after the first turn. */
    @Test fun wheelWhoseUpDragDecreasesIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, upIncreases = false)
        assertEquals(WheelDrags.Step.Done, run(w, "25").first)
        assertEquals(25, w.value)
    }

    /** A row taller than estimated: after calibrating from the rows actually turned, it drags the rest of the way. */
    @Test fun rowHeightIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, rowPx = 120)
        val (step, _) = run(w, "40", rowHeight = 80)
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(40, w.value)
        assertTrue(w.drags <= 4)
    }

    @Test fun valueNotOnTheWheelIsNotForced() {
        val w = Wheel(0, 60, wrap = true, radius = 2)
        val (step, _) = run(w, "75")
        assertTrue("$step", step is WheelDrags.Step.Stop)
    }
}
