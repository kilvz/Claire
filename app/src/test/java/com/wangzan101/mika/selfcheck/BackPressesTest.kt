package com.wangzan101.mika.selfcheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-check back presses: while the IME is up, those presses only dismiss the IME and do not count towards the tries; the total number of presses is capped. */
class BackPressesTest {
    /** One screen: the IME takes [imePresses] presses to dismiss (first clearing the text that has not been committed, then dismissing), after which [pageLeavesAfter] presses make the page leave. */
    private class Screen(var imePresses: Int, val pageLeavesAfter: Int = 1) {
        var presses = 0
        private var pageBacks = 0
        var gone = false
        fun press() {
            presses++
            if (imePresses > 0) imePresses-- else if (++pageBacks >= pageLeavesAfter) gone = true
        }
    }

    private fun run(screen: Screen, tries: Int = 2, max: Int = 5) = runBlocking {
        BackPresses.until(tries, max, { screen.imePresses > 0 }, { screen.press() }, { screen.gone })
    }

    @Test fun keyboardThatTakesTwoPressesNoLongerFailsTheCheck() {
        // Previously, both presses being swallowed by the IME made the check fail
        val screen = Screen(imePresses = 2)
        assertTrue(run(screen))
        assertEquals(3, screen.presses)
    }

    @Test fun withoutKeyboardOnlyTheTriesCount() {
        val screen = Screen(imePresses = 0, pageLeavesAfter = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(2, screen.presses)
    }

    @Test fun keyboardThatNeverHidesStopsAtTheCap() {
        val screen = Screen(imePresses = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(5, screen.presses)
    }

    @Test fun firstPressLeavingIsEnough() {
        val screen = Screen(imePresses = 0)
        assertTrue(run(screen))
        assertEquals(1, screen.presses)
    }
}
