package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.SettleBehind.Plan
import com.wangzan101.mika.cap.phone.shell.SettleBehind.Seen
import org.junit.Assert.assertEquals
import org.junit.Test

class SettleBehindTest {
    private fun behind(id: Int) = Seen(id, onMain = true, visible = false)
    private fun front(id: Int) = Seen(id, onMain = true, visible = true)

    @Test fun nothingMovedIsDoneAtOnce() {
        assertEquals(Plan.Done(emptySet(), emptySet(), emptySet()), SettleBehind.plan(emptySet(), emptyList(), 0, null))
    }

    @Test fun allBehindIsKept() {
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 0, null))
    }

    @Test fun visibleDuringTheTransitionWaitsInsteadOfFailing() {
        // Seen for the first time: reorder once more
        assertEquals(Plan.Wait(setOf(1), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 0, null))
        // Just reordered: only wait, do not reorder back to back
        assertEquals(Plan.Wait(emptySet(), emptySet()), SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), 300, 300))
        // Enough time has passed: reorder again
        assertEquals(Plan.Wait(setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), 900, SettleBehind.REORDER_EVERY_MS))
        // Settled: keep it
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(behind(1), behind(2)), 1_200, 300))
    }

    @Test fun stillVisibleAtTheDeadlineTakesTheFallback() {
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1, 2), listOf(front(1), behind(2)), SettleBehind.TIMEOUT_MS, 100))
        assertEquals(Plan.Done(emptySet(), setOf(1), emptySet()),
            SettleBehind.plan(setOf(1), listOf(front(1)), SettleBehind.TIMEOUT_MS + 500, null))
    }

    @Test fun closeOutRechecksUnderTheLock() {
        // The one still visible at the deadline is still visible: take the fallback; the other stays
        assertEquals(Plan.Done(setOf(2), setOf(1), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(front(1), behind(2))))
        // The timed-out one has settled by now: keep it
        assertEquals(Plan.Done(setOf(1, 2), emptySet(), emptySet()),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(behind(1), behind(2))))
        // Settled but visible again means the user opened it: keep it too, do not take it away
        assertEquals(Plan.Done(setOf(2), emptySet(), emptySet()), SettleBehind.closeOut(setOf(2), emptySet(), listOf(front(2))))
        // Picked up by another task on that display while waiting, or closed: not counted as kept, and no fallback either
        assertEquals(Plan.Done(emptySet(), emptySet(), setOf(1, 2)),
            SettleBehind.closeOut(setOf(1, 2), setOf(1), listOf(Seen(1, onMain = false, visible = true))))
    }

    @Test fun tasksNoLongerOnMainAreDroppedNotKept() {
        // Moved to another display by another task, or already closed (not listed)
        val elsewhere = Seen(3, onMain = false, visible = true)
        assertEquals(Plan.Wait(setOf(1), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(front(1), elsewhere), 0, null))
        assertEquals(Plan.Done(setOf(1), emptySet(), setOf(3, 4)), SettleBehind.plan(setOf(1, 3, 4), listOf(behind(1), elsewhere), 0, null))
        // Those no longer on the main display at the deadline do not take the fallback
        assertEquals(Plan.Done(emptySet(), setOf(1), setOf(3)),
            SettleBehind.plan(setOf(1, 3), listOf(front(1), elsewhere), SettleBehind.TIMEOUT_MS, null))
    }
}
