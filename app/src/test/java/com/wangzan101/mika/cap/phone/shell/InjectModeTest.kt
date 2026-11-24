package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Injection mode: keys wait for the system to settle who receives them and report failure when they were not dispatched (only then does the caller fall back); touches keep waiting for nothing, as before.
 * A down/up pair: fall back only when the down was not delivered; when the down succeeded, only the up is retried.
 */
class InjectModeTest {
    // InputManager.INJECT_INPUT_EVENT_MODE_*
    private val async = 0
    private val waitForResult = 1
    private val waitForFinish = 2

    @Test fun keysWaitForTheDispatchResult() {
        assertEquals(waitForResult, ShellService.injectMode(key = true, waitForFinish = false))
    }

    @Test fun touchesStayAsync() {
        assertEquals(async, ShellService.injectMode(key = false, waitForFinish = false))
    }

    @Test fun explicitWaitForFinishWins() {
        assertEquals(waitForFinish, ShellService.injectMode(key = true, waitForFinish = true))
        assertEquals(waitForFinish, ShellService.injectMode(key = false, waitForFinish = true))
    }

    @Test fun undeliveredDownFallsBack() {
        var ups = 0
        var fallbacks = 0
        assertTrue(ShellService.sendPair({ false }, { ups++; true }, { fallbacks++; true }))
        assertEquals(1, fallbacks)
        assertEquals(0, ups)
        assertFalse(ShellService.sendPair({ false }, { true }, { false }))
    }

    @Test fun deliveredDownNeverFallsBackAndRetriesUpOnce() {
        var ups = 0
        var fallbacks = 0
        assertFalse(ShellService.sendPair({ true }, { ups++; false }, { fallbacks++; true }))
        assertEquals(2, ups)
        assertEquals(0, fallbacks)
    }
}
