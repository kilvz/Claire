package com.wangzan101.mika.core.agent

/**
 * Compaction bookkeeping for one model: whether this task has switched to aggressive compaction (the model reported
 * an overlong context), how many steps were folded on the last send, and how forceful it was.
 * One instance per model; compaction of a lightweight model does not affect the main model.
 */
internal data class CompactionState(val aggressive: Boolean = false, val lastFolded: Int = 0, val lastAggressive: Boolean = false)

/** Compaction timing: when to record a compaction event, and whether to compact harder and resend on context overflow. Pure functions. */
internal object CompactionPolicy {
    /** [folded] steps were folded on this send. Returns the updated bookkeeping and whether to record a compaction event (only when the folded step count or the forcefulness changed). */
    fun sent(s: CompactionState, folded: Int): Pair<CompactionState, Boolean> {
        val report = folded > 0 && (folded != s.lastFolded || s.aggressive != s.lastAggressive)
        return s.copy(lastFolded = folded, lastAggressive = s.aggressive) to report
    }

    /**
     * The model reported an overlong context. Each overflow is resent only once: if [retried] is true (this call has
     * already been resent because of an overflow), do not resend; otherwise [shrinks] compacts the full history
     * aggressively once more, and we resend only if it is smaller than what was just sent (also counts if the history
     * has grown again since the last aggressive compaction). Afterwards always compact aggressively.
     */
    fun overflow(s: CompactionState, retried: Boolean, shrinks: () -> Boolean): Pair<CompactionState, Boolean> =
        s.copy(aggressive = true) to (!retried && shrinks())
}
