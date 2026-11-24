package com.wangzan101.mika.cap.phone.shell

/**
 * Tasks moved behind the main screen during cleanup; waits until they have really retreated out of the user's
 * sight (pure logic, no system APIs, tested directly by unit tests).
 *
 * After the move the system plays a TO_BACK transition, during which TaskInfo.isVisible is still true, so a
 * single "visible" reading cannot be treated as a failure. Therefore check every [POLL_MS]: once none are
 * visible, settle; the ones still visible are reordered to the very back again every [REORDER_EVERY_MS];
 * anything still visible at [TIMEOUT_MS] must not be left in front of the user and goes to the fallback
 * ([Plan.Done.fallback]): moved back to Claire's display and closed along with the display, or closed directly
 * when it cannot be moved back (the same path as for one that makes sound after being moved), and it is
 * recorded as not retained. Tasks no longer on the main screen (taken over by another task, or already closed)
 * are ignored and do not count as retained.
 */
object SettleBehind {
    const val TIMEOUT_MS = 2_000L
    const val REORDER_EVERY_MS = 750L
    const val POLL_MS = 100L

    /** The state of one moved task right now: [onMain] whether it is still on the main screen; [visible] whether it is visible on the main screen (when isVisible cannot be read, the caller supplies it based on whether it is at the top of the main screen). */
    data class Seen(val id: Int, val onMain: Boolean, val visible: Boolean)

    sealed interface Plan {
        /** Still transitioning: [reorder] are the ones to send to the very back again this round (may be empty), [gone] are no longer tracked; check again after [POLL_MS]. */
        data class Wait(val reorder: Set<Int>, val gone: Set<Int>) : Plan

        /** Settled: [kept] stay behind the main screen; [fallback] were still visible at the timeout and take the fallback; [gone] are no longer on the main screen. */
        data class Done(val kept: Set<Int>, val fallback: Set<Int>, val gone: Set<Int>) : Plan
    }

    /**
     * What to do this round. [moved] are the tasks still being tracked, [seen] their state right now (those not
     * listed count as no longer on the main screen), [elapsedMs] how long it has been since waiting started, and
     * [sinceReorderMs] how long since the last reorder (null when none has happened yet).
     */
    fun plan(moved: Set<Int>, seen: List<Seen>, elapsedMs: Long, sinceReorderMs: Long?): Plan {
        val byId = seen.associateBy { it.id }
        val gone = moved.filterTo(LinkedHashSet()) { byId[it]?.onMain != true }
        val front = moved.filterTo(LinkedHashSet()) { byId[it]?.let { s -> s.onMain && s.visible } == true }
        val behind = moved - gone - front
        if (front.isEmpty()) return Plan.Done(behind, emptySet(), gone)
        if (elapsedMs >= TIMEOUT_MS) return Plan.Done(behind, front, gone)
        val due = sinceReorderMs == null || sinceReorderMs >= REORDER_EVERY_MS
        return Plan.Wait(if (due) front else emptySet(), gone)
    }

    /**
     * Decides once more after the wait, back under the lock (waiting does not hold the lock, and during that
     * time tasks may be taken over by other tasks). [moved] are all the moved tasks, [fallback] the ones still
     * visible at the timeout, and [seen] is the current state of the main screen: those not on the main screen
     * no longer count as retained; only those that timed out and are still visible take the fallback; ones that
     * had already receded but are visible again were opened by the user and are left as they are.
     */
    fun closeOut(moved: Set<Int>, fallback: Set<Int>, seen: List<Seen>): Plan.Done {
        val byId = seen.associateBy { it.id }
        val gone = moved.filterTo(LinkedHashSet()) { byId[it]?.onMain != true }
        val front = (fallback intersect moved).filterTo(LinkedHashSet()) { byId[it]?.let { s -> s.onMain && s.visible } == true }
        return Plan.Done(moved - gone - front, front, gone)
    }
}
