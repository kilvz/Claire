package com.wangzan101.mika.core.trigger

import com.wangzan101.mika.core.trigger.TriggerWindowState.ActionDispatched
import com.wangzan101.mika.core.trigger.TriggerWindowState.Delivered
import com.wangzan101.mika.core.trigger.TriggerWindowState.Failed
import com.wangzan101.mika.core.trigger.TriggerWindowState.Skipped
import com.wangzan101.mika.core.trigger.TriggerWindowState.Unknown
import org.junit.Assert.assertEquals
import org.junit.Test

/** How many times a scheduled task has failed in a row: count backwards through this plan's own records, stop at a delivery or an unclear result, and skipped runs do not count. */
class FailureStreakTest {
    private fun w(due: Long, state: TriggerWindowState, plan: String = "plan") = TriggerWindow(plan, due, due, state = state)

    @Test fun countsFailuresRightBeforeThisOne() {
        val now = w(50, ActionDispatched)
        val windows = listOf(w(10, Delivered), w(20, Failed), w(30, Skipped), w(40, Failed), now)
        // 40 and 20 failed; 30 in between was skipped because the condition was not met, which does not break the streak; 10 was delivered, so stop
        assertEquals(2, failureStreakBefore(windows, now.key))
    }

    @Test fun successOrUnknownResultEndsTheStreak() {
        val now = w(40, ActionDispatched)
        assertEquals(0, failureStreakBefore(listOf(w(10, Failed), w(30, Delivered), now), now.key))
        assertEquals(1, failureStreakBefore(listOf(w(10, Failed), w(20, Unknown), w(30, Failed), now), now.key))
    }

    @Test fun onlyThisPlanAndOnlyEarlierRuns() {
        val now = w(30, ActionDispatched)
        val windows = listOf(w(10, Failed), w(20, Failed, plan = "other"), now, w(40, Failed))
        assertEquals(1, failureStreakBefore(windows, now.key))
        assertEquals(0, failureStreakBefore(windows, "missing:1"))
    }
}
