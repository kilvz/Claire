package com.wangzan101.mika.core.safety.guard

/**
 * Decision rules for the operation guards. Every rule looks at the same kinds of facts: which action was performed and which
 * target it acted on (the two combine into the action signature [actionSignature]), the action's actual effect (effect), and
 * which interface state it arrived at afterwards; [OperationGuards] keeps the counts from these facts and this object only
 * turns counts into the level to report. Pure functions.
 *
 * When it runs into a dead end it first nudges: state clearly where it is stuck and give three ways out (take a clearly
 * different route, ask the user, hand over what is already there and say what is missing). Only the last rung of each ladder
 * stops the task, and it is just the final safety net, its threshold far past the nudges; the slowly-circling ones are bounded
 * by this task's step budget (see [lateStop]):
 *
 * | Signal | Nudge | Stop (safety net) |
 * |---|---|---|
 * | Same action + same target repeated (within the latest [LOOP_WINDOW] actions) | 3, 6 | 12 |
 * | Same as above, for diagnostic interface-viewing tools (counted per tool) | 6, 9 | 15 |
 * | Action had no effect (effect is no_effect, or the interface did not change after acting), consecutive | 3, 6, 9 | 12 |
 * | Read again while the page was unchanged, with no new content | 5, 8 | 12 |
 * | Consecutive failures (ways out added from the 4th on) | 2, 4 | 8 |
 * | Unrecoverable failure | 1 | 3 |
 * | Return to the same state (not repeated during a cycle, which the cycle row covers) | 3, 6 | 1/4 of the step budget (at least 10) |
 * | Circling among two or three states (whole rounds) | 3, 4 | 1/5 of the step budget (at least 6) |
 * | N consecutive actions that reached no new state | 8, 16 | 1/2 of the step budget (at least 24) |
 *
 * When the step budget is unknown, [DEFAULT_BUDGET] is used.
 */
internal object GuardRules {
    /** Which rung of the ladder the count has reached: [threshold] is this rung's threshold. */
    data class Rung(val threshold: Int, val severity: Severity)

    /** Repeated actions are counted over the latest this many actions. */
    const val LOOP_WINDOW = 16

    /** Used when the task's step budget is unknown. */
    const val DEFAULT_BUDGET = 60

    /** Ladder for repeated actions: nudge, nudge again, stop. Diagnostic interface-viewing tools repeat a lot by nature, so their thresholds are higher. */
    fun repeatLadder(diagnostic: Boolean): List<Int> = if (diagnostic) listOf(6, 9, 15) else listOf(3, 6, 12)

    /** Ladder for actions with no effect. */
    val NO_EFFECT_LADDER = listOf(3, 6, 9, 12)

    /** Ladder for re-reads while the page is unchanged. */
    val REREAD_LADDER = listOf(5, 8, 12)

    /** Ladder for consecutive failures; unrecoverable failures use [UNRECOVERABLE_LADDER] instead. */
    val FAILURE_LADDER = listOf(2, 4, 8)
    val UNRECOVERABLE_LADDER = listOf(1, 3)

    /** From this consecutive failure on, the nudge includes ways out. */
    const val FAILURE_WAYS_OUT = 4

    /** At most this many states per cycle are considered. */
    const val MAX_CYCLE = 3

    /** Ladder for returning to the same state ([budget] is the task's step budget). */
    fun sameStateLadder(budget: Int?) = listOf(3, 6, lateStop(budget, 4, 10))

    /** Ladder for cycles (whole rounds). */
    fun cycleLadder(budget: Int?) = listOf(3, 4, lateStop(budget, 5, 6))

    /** Ladder for the number of consecutive actions that reached no new state. */
    fun noNewStateLadder(budget: Int?) = listOf(8, 16, lateStop(budget, 2, 24))

    /** Safety-net threshold: 1/[divisor] of the step budget, at least [floor] (it has to be far past the nudges). */
    fun lateStop(budget: Int?, divisor: Int, floor: Int): Int = maxOf(floor, (budget ?: DEFAULT_BUDGET) / divisor)

    /**
     * Which rung of the ascending [ladder] (the last rung is the stop) the count [count] has reached. null when the first rung
     * is not reached yet, or when this rung has already been notified ([warnedAt] is the threshold already notified); at the
     * top rung with [canStop] it is a stop, otherwise a nudge.
     */
    fun climb(count: Int, ladder: List<Int>, warnedAt: Int, canStop: Boolean): Rung? {
        val threshold = ladder.lastOrNull { count >= it } ?: return null
        if (threshold <= warnedAt) return null
        return Rung(threshold, if (threshold == ladder.last() && canStop) Severity.Stop else Severity.Warn)
    }

    /** The tail circles among [period] distinct states: [length] is the matching tail length and [rounds] is how many whole rounds were walked. */
    data class Cycle(val period: Int, val length: Int) {
        val rounds: Int get() = length / period
    }

    /**
     * Whether the tail of the states visited so far (adjacent duplicates removed) circles among 2–[MAX_CYCLE] states:
     * A B A B is 2 rounds of a 2-state cycle, A B C A B C is 2 rounds of a 3-state cycle. null when nothing repeats for at
     * least one step; when several cycle lengths fit, the longest one is taken.
     */
    fun cycle(trail: List<Long>): Cycle? {
        var best: Cycle? = null
        for (p in 2..MAX_CYCLE) {
            if (trail.size < p + 1) continue
            if (trail.takeLast(p).toSet().size != p) continue
            var len = p
            var i = trail.size - 1
            while (i - p >= 0 && trail[i] == trail[i - p]) { len++; i-- }
            if (len > p && (best == null || len > best.length)) best = Cycle(p, len)
        }
        return best
    }

    /** The severity to report for [consecutive] consecutive failures, [unrecoverable] of them unrecoverable; null when none. */
    fun failures(consecutive: Int, unrecoverable: Int): Severity? = when {
        consecutive >= FAILURE_LADDER.last() || unrecoverable >= UNRECOVERABLE_LADDER.last() -> Severity.Stop
        consecutive >= FAILURE_LADDER.first() || unrecoverable >= UNRECOVERABLE_LADDER.first() -> Severity.Warn
        else -> null
    }
}
