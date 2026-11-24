package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerToggleTest {
    private val now = 1_000_000L

    private fun plan(enabled: Boolean = false) =
        TriggerDefinition("plan", "thread", "goal", now + 60_000, enabled = enabled)

    private fun window(dueAtMs: Long, state: TriggerWindowState = TriggerWindowState.Delivered) =
        TriggerWindow("plan", dueAtMs, dueAtMs, state = state)

    private fun engine(plan: TriggerDefinition, window: TriggerWindow?): TriggerEngine {
        val store = MemoryTriggerStore(TriggerSnapshot(listOf(plan), listOfNotNull(window)))
        return TriggerEngine(store, {}, dispatch = { _, _ -> })
    }

    /** A manual run is recorded only in the "now" slot and does not consume the plan's own next slot, so the plan can still be re-enabled after pausing. */
    @Test fun manualRunDoesNotBlockResumingAOneShot() {
        val plan = plan()
        val manual = window(now)

        assertTrue(plan.canToggle(listOf(manual)))
        assertTrue(engine(plan, manual).setEnabled(plan.id, true, now))
    }

    /** A one-shot that has run at its scheduled time cannot be re-enabled; running it again requires changing the time or running it now; the toggle and the engine must agree. */
    @Test fun aOneShotThatUsedItsOwnSlotCannotBeResumed() {
        val plan = plan()
        val used = window(plan.nextAtMs)

        assertFalse(plan.canToggle(listOf(used)))
        assertFalse(engine(plan, used).setEnabled(plan.id, true, now))
    }

    @Test fun enabledPlansCanAlwaysBePaused() {
        val plan = plan(enabled = true)
        assertTrue(plan.canToggle(listOf(window(plan.nextAtMs))))
    }

    @Test fun repeatingPlansStayToggleable() {
        val plan = plan().copy(intervalMs = 60_000)

        assertTrue(plan.canToggle(emptyList()))
        assertTrue(engine(plan, null).setEnabled(plan.id, true, now))
    }
}
