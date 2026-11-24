package com.wangzan101.mika.task

/**
 * The re-lock obligation left behind after a task unlocks the phone. The main-screen accessibility interaction count is recorded as a baseline on unlock;
 * the obligation stays pending until the system confirms it is locked again, and a rejected or ineffective re-lock can be retried.
 * Re-locking only happens once all tasks have finished and main-screen interactions are back at the baseline (the user isn't still using it); if new interactions occur while waiting, wait for an idle stretch first,
 * and when the idle stretch ends, take the count at that time as the new baseline and decide again.
 */
internal class TaskRelock {
    private var baseline: Long? = null

    @Synchronized fun hasPending() = baseline != null

    /** A task-driven unlock has completed: [interactions] is the main-screen interaction count at this moment. The baseline is not moved when the obligation is already pending. */
    @Synchronized fun unlocked(interactions: Long) { if (baseline == null) baseline = interactions }

    /** The system confirms it is locked: the obligation is settled. */
    @Synchronized fun confirmed() { baseline = null }

    /** The idle wait ended: accept the count at this moment as the new baseline only if there were no new interactions ([start] == [current]); if it changed, don't. */
    @Synchronized fun acceptIdle(start: Long, current: Long): Boolean {
        if (baseline == null || start != current) return false
        baseline = current
        return true
    }

    @Synchronized fun decide(runsRemain: Boolean, locked: Boolean, interactions: Long, canLock: Boolean): Decision {
        val recorded = baseline ?: return Decision.NONE
        if (runsRemain) return Decision.WAIT
        if (locked) { baseline = null; return Decision.NONE }
        return when {
            !canLock -> Decision.WARN
            interactions != recorded -> Decision.WAIT_IDLE
            else -> Decision.LOCK
        }
    }

    enum class Decision { NONE, WAIT, WAIT_IDLE, WARN, LOCK }
}
