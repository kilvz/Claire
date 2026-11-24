package com.wangzan101.mika.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relock obligation left behind after a task unlocked the phone: it stays pending until the system confirms the device is locked. */
class TaskRelockTest {
    @Test fun keepsObligationUntilSystemConfirmsLock() {
        val c = TaskRelock()
        c.unlocked(7)
        assertEquals(TaskRelock.Decision.WAIT, c.decide(runsRemain = true, locked = false, interactions = 7, canLock = true))
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
        // The obligation remains until the system confirms: a rejected or ineffective relock can be retried
        assertTrue(c.hasPending())
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
        c.confirmed()
        assertFalse(c.hasPending())
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = false, interactions = 7, canLock = true))
    }

    @Test fun onlyLocksAfterAnIdleGracePeriod() {
        val c = TaskRelock()
        c.unlocked(3)
        // New interaction on the home screen while waiting: wait for idle first, and do not lock against the old baseline
        assertEquals(TaskRelock.Decision.WAIT_IDLE, c.decide(runsRemain = false, locked = false, interactions = 4, canLock = true))
        // Interaction changed again during the idle window: not accepted, and the obligation remains
        assertFalse(c.acceptIdle(start = 4, current = 5))
        assertTrue(c.hasPending())
        // The idle window ended with no further interaction change: accept the current count as the new baseline
        assertTrue(c.acceptIdle(start = 5, current = 5))
        assertEquals(TaskRelock.Decision.LOCK, c.decide(runsRemain = false, locked = false, interactions = 5, canLock = true))
        assertEquals(TaskRelock.Decision.WAIT_IDLE, c.decide(runsRemain = false, locked = false, interactions = 6, canLock = true))
    }

    @Test fun unavailableLockPathWarnsWithoutDroppingObligation() {
        val c = TaskRelock()
        c.unlocked(5)
        assertEquals(TaskRelock.Decision.WARN, c.decide(runsRemain = false, locked = false, interactions = 5, canLock = false))
        assertTrue(c.hasPending())
        // Already locked (for example, the user locked it themselves): the obligation is settled
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = true, interactions = 5, canLock = false))
        assertFalse(c.hasPending())
    }

    @Test fun nothingToRelockWithoutAnUnlock() {
        val c = TaskRelock()
        assertFalse(c.hasPending())
        assertEquals(TaskRelock.Decision.NONE, c.decide(runsRemain = false, locked = false, interactions = 0, canLock = true))
    }
}
