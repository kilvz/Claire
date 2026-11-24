package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ProactiveLockWatch.Reading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveLockWatchTest {
    private var interactive = false
    private var keyguardLocked = true
    private var reads = 0

    private fun TestScope.watch() = ProactiveLockWatch({ reads++; Reading(interactive, keyguardLocked) }, backgroundScope)

    private fun TestScope.ticks(n: Int) = repeat(n) {
        advanceTimeBy(ProactiveLockWatch.POLL_MS)
        runCurrent()
    }

    @Test fun pollsOnlyWhenWatchingAndScreenOnWithKeyguard() {
        for (active in listOf(true, false)) for (i in listOf(true, false)) for (k in listOf(true, false)) {
            assertEquals("$active $i $k", active && i && k, ProactiveLockWatch.shouldPoll(active, Reading(i, k)))
        }
    }

    // No polling while the screen is off, and nothing is read no matter how long passes
    @Test fun noPollWhileScreenOff() = runTest {
        val w = watch()
        w.start()
        assertTrue(w.locked.value)
        assertNull(w.pollJob)
        val before = reads
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(before, reads)
        assertNull(w.pollJob)
        // Same when the keyguard is not up while the screen is off (just turned off, not yet locked)
        keyguardLocked = false
        w.refresh()
        assertTrue(w.locked.value)
        assertNull(w.pollJob)
    }

    // Screen on but the keyguard still up: poll until an unlock is read, then stop; no reads afterwards
    @Test fun pollStopsOnUnlock() = runTest {
        val w = watch()
        w.start()
        interactive = true
        w.refresh() // SCREEN_ON
        assertNotNull(w.pollJob)
        ticks(3)
        assertTrue(w.locked.value)
        keyguardLocked = false // the keyguard goes away, with no broadcast
        ticks(1)
        assertFalse(w.locked.value)
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
    }

    // Screen goes off while polling: stop at the next read that sees the screen off
    @Test fun pollStopsWhenScreenGoesOff() = runTest {
        val w = watch()
        interactive = true
        w.start()
        assertNotNull(w.pollJob)
        interactive = false // the broadcast has not arrived yet; polling reads it itself
        ticks(1)
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
        // Same immediate stop when the broadcast arrives first
        interactive = true
        w.refresh()
        assertNotNull(w.pollJob)
        interactive = false
        w.refresh() // SCREEN_OFF
        assertNull(w.pollJob)
        val now = reads
        ticks(20)
        assertEquals(now, reads)
    }

    // Stopping the watch stops polling; while stopped, an incoming broadcast does not start it again
    @Test fun stopCancelsAndKeepsPollOff() = runTest {
        val w = watch()
        interactive = true
        w.start()
        assertNotNull(w.pollJob)
        w.stop()
        assertNull(w.pollJob)
        w.refresh()
        assertNull(w.pollJob)
        val after = reads
        ticks(20)
        assertEquals(after, reads)
    }

    // Unlock -> lock again -> screen on: polling starts up again
    @Test fun pollRestartsOnTheNextLockedScreenOn() = runTest {
        val w = watch()
        interactive = true
        w.start()
        keyguardLocked = false
        ticks(1)
        assertNull(w.pollJob)
        interactive = false; keyguardLocked = true
        w.refresh() // SCREEN_OFF
        assertNull(w.pollJob)
        interactive = true
        w.refresh() // SCREEN_ON
        assertNotNull(w.pollJob)
        assertTrue(w.pollJob!!.isActive)
        keyguardLocked = false
        ticks(1)
        assertFalse(w.locked.value)
        assertNull(w.pollJob)
    }
}
