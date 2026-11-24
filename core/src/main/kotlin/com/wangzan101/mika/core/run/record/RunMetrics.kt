package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.model.usage.DeepSeekCost
import kotlinx.serialization.Serializable

/**
 * Summary of one run, computed entirely from the events in the run record (a pure function, see [of]); once persisted it is not lost when events are pruned (see [RunMetricsStore]).
 * All times are measured from [RunEvent.TaskStarted]: earlier preparation (loading knowledge, connecting the phone) is not counted.
 *
 * - [totalMs]: from start to [RunEvent.TaskEnded]; for an unfinished run ([ended] false) up to the last event.
 * - [firstFeedbackMs]: from start until the user first saw real progress of this run — the earliest model reply that was actually executed arriving ([RunEvent.ModelReply.repliedAtMs],
 *   or its [RunEvent.atMs] in old records): from then on the UI shows its progress description, the action to take or the answer; or the earliest activity (when replaying without the model, the first step is done).
 *   Dispatched-away light model replies do not count (users cannot see them); when the answer is shown paragraph by paragraph the first characters appear earlier, but that is not recorded and does not count. Null when neither exists.
 * - [modelCalls]: the number of model reply events, i.e. the main loop's request count (including dispatched-away light model replies; [lightCalls] is how many of them were light-model ones);
 *   [callsWithoutUsage] is how many of them had no usage reported by the provider. The tokens are the sum of these replies' usage ([inputTokens] includes [cachedTokens]).
 * - [allCalls] etc.: all metered outer logical calls during the run ([RunEvent.ModelCalls], including side calls and outer retries);
 *   [allHttpCalls] is the number of actual HTTP requests (plus resends inside the adapter), null in old records that lack this field.
 * - [modelMs]: the sum of request time over the replies (from the last request being sent until the reply was fully received); earlier failed requests and backoff are recorded separately in [retryWaitMs].
 * - [observeMs] / [actMs]: execution time of top-level tool calls (those the model calls directly and those from replay), split by the activity kind assigned at the execution entry point: read-only risk counts as observe
 *   (reading the screen, reading web pages, listing elements, screenshots, looking things up, …), everything else counts as act (tap, type, swipe, opening apps, …). For the outer call of a batch of actions or a page script,
 *   reads in the sub-steps count as observe and the rest as act, with no double counting; control calls that have no activity (waiting, asking the user, requesting confirmation, handing to the user, saying it cannot be done) count as neither observe nor act.
 *   [uiWaitMs] is time spent waiting on the UI: the part of a single tool's time spent inside the port waiting for the UI to settle (only when the port can report it; it still counts inside that observe or act),
 *   plus time spent in a batch waiting on the UI conditionally after each step is done — the latter, like calling the wait tool on its own, does not count as act and lands in [otherMs].
 * - [otherMs]: total duration minus model, observe and act; the remainder is waiting for the user, retry backoff, control calls, verification and other overhead.
 * - [steps]: how many rounds the main loop reached (the largest step number among replies that were actually executed); [observeCalls] / [actCalls] are how many observe and act calls were made (including sub-steps).
 * - [outcome]: [RunEvent.TaskEnded.outcome], unfinished when the run did not end; [failKind] is the failure category; [judged] is the outcome judged separately after the answer was handed over.
 */
@Serializable
data class RunMetrics(
    val runId: String = "",
    val goal: String = "",
    val startedAtMs: Long = 0,
    val totalMs: Long = 0,
    val ended: Boolean = false,
    val firstFeedbackMs: Long? = null,
    val modelCalls: Int = 0,
    val lightCalls: Int = 0,
    val callsWithoutUsage: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val allCalls: Int? = null,
    val allHttpCalls: Int? = null,
    val allInputTokens: Long? = null,
    val allOutputTokens: Long? = null,
    val modelMs: Long = 0,
    val retryWaitMs: Long = 0,
    val observeMs: Long = 0,
    val actMs: Long = 0,
    val uiWaitMs: Long = 0,
    val observeCalls: Int = 0,
    val actCalls: Int = 0,
    val steps: Int = 0,
    val outcome: String = UNFINISHED,
    val failKind: String? = null,
    val judged: String? = null,
    val deepSeekCost: DeepSeekCost? = null,
) {
    val otherMs: Long get() = (totalMs - modelMs - observeMs - actMs).coerceAtLeast(0)

    /** Input plus output (cache hits are included in input). */
    val tokens: Long get() = inputTokens + outputTokens

    /** Succeeded: it reported success (including success that could not be verified), and the judgment after handing over the answer did not find it only partly done or impossible. */
    val succeeded: Boolean get() = outcome in SUCCESS && judged !in NOT_DONE

    /**
     * Accumulates in event order; [build] may be called at any time. The run record's writer thread uses it to compute while writing, without re-reading the whole record at wrap-up.
     * Only a few numbers per tool result are kept, never the original text.
     */
    class Builder(private val runId: String = "") {
        private class Tool(val step: Int, val source: String, val callId: String?, val durationMs: Long, val waitMs: Long)

        private var goal = ""
        private var started: Long? = null
        private var last = 0L
        private var ended: RunEvent.TaskEnded? = null
        private var firstFeedback: Long? = null
        private var calls = 0
        private var light = 0
        private var noUsage = 0
        private var input = 0L
        private var output = 0L
        private var cached = 0L
        private var all: RunEvent.ModelCalls? = null
        private var modelMs = 0L
        private var retryMs = 0L
        private var steps = 0
        private var judged: String? = null
        private val tools = mutableListOf<Tool>()
        /** (source, step, call id) → activity kind (observe / act). */
        private val kinds = HashMap<Triple<String, Int, String?>, String>()

        fun add(e: RunEvent) {
            last = maxOf(last, e.atMs)
            when (e) {
                is RunEvent.TaskStarted -> if (started == null) { started = e.atMs; goal = e.goal.take(GOAL_MAX) }
                is RunEvent.ModelReply -> {
                    calls++
                    if (e.role == ROLE_LIGHT || !e.acted) light++
                    if (e.inputTokens == null && e.outputTokens == null) noUsage++
                    input += e.inputTokens ?: 0
                    output += e.outputTokens ?: 0
                    cached += e.cachedTokens ?: 0
                    modelMs += e.latencyMs.coerceAtLeast(0)
                    retryMs += e.retryWaitMs.coerceAtLeast(0)
                    if (e.acted) {
                        steps = maxOf(steps, e.step)
                        feedback(e.repliedAtMs ?: e.atMs)
                    }
                }
                is RunEvent.ToolResult -> tools += Tool(e.step, e.source, e.callId, e.durationMs.coerceAtLeast(0), e.waitMs.coerceAtLeast(0))
                is RunEvent.Activity -> {
                    kinds[Triple(e.source, e.step, e.callId)] = e.kind
                    feedback(e.atMs)
                }
                is RunEvent.TaskEnded -> if (ended == null) ended = e
                is RunEvent.ModelCalls -> all = e
                is RunEvent.OutcomeJudged -> judged = e.outcome
                else -> Unit
            }
        }

        private fun feedback(at: Long) {
            if (firstFeedback == null || at < firstFeedback!!) firstFeedback = at
        }

        /** Null when there is no start event (the record was opened but the run never got going). */
        fun build(): RunMetrics? {
            val start = started ?: return null
            val end = ended
            val kindOf = { t: Tool -> kinds[Triple(t.source, t.step, t.callId)] }
            val (top, nested) = tools.partition { it.source !in NESTED }
            var observe = 0L
            var act = 0L
            for (t in top) {
                val kind = kindOf(t)
                val inner = nested.filter { it.step == t.step }
                // The outer call's duration already includes its sub-steps: reads in sub-steps count as observe, the remainder counts as act
                val innerObserve = inner.filter { kindOf(it) == OBSERVE }.sumOf { it.durationMs }.coerceAtMost(t.durationMs)
                when {
                    kind == OBSERVE -> observe += t.durationMs
                    // The waitMs recorded on the outer call is conditional UI waiting after the sub-steps; it is not inside the sub-steps and does not count as act
                    inner.isNotEmpty() -> { observe += innerObserve; act += (t.durationMs - innerObserve - t.waitMs).coerceAtLeast(0) }
                    // Pauses added for pacing (waitMs) count as waiting, not as act
                    kind != null -> act += (t.durationMs - t.waitMs).coerceAtLeast(0)
                    else -> Unit
                }
            }
            val counted = kinds.values
            return RunMetrics(
                runId = runId,
                goal = goal,
                startedAtMs = start,
                totalMs = ((end?.atMs ?: last) - start).coerceAtLeast(0),
                ended = end != null,
                firstFeedbackMs = firstFeedback?.let { (it - start).coerceAtLeast(0) },
                modelCalls = calls,
                lightCalls = light,
                callsWithoutUsage = noUsage,
                inputTokens = input,
                outputTokens = output,
                cachedTokens = cached,
                allCalls = all?.calls,
                allHttpCalls = all?.httpCalls,
                allInputTokens = all?.inputTokens,
                allOutputTokens = all?.outputTokens,
                modelMs = modelMs,
                retryWaitMs = retryMs,
                observeMs = observe,
                actMs = act,
                uiWaitMs = top.sumOf { it.waitMs },
                observeCalls = counted.count { it == OBSERVE },
                actCalls = counted.count { it == ACT },
                steps = steps,
                outcome = end?.outcome ?: UNFINISHED,
                failKind = end?.kind,
                judged = judged,
                deepSeekCost = all?.deepSeekCost,
            )
        }
    }

    companion object {
        const val UNFINISHED = "unfinished"
        const val GOAL_MAX = 80
        private const val OBSERVE = "observe"
        private const val ACT = "act"
        private const val ROLE_LIGHT = "light"
        /** Sub-steps wrapped inside an outer call: one step of a batch of actions, one action of a page script. */
        private val NESTED = setOf("batch", "script")
        private val SUCCESS = setOf("succeeded", "unverified")
        private val NOT_DONE = setOf("partial", "not_possible")

        /** The summary of one run; null when there is no start event. */
        fun of(events: List<RunEvent>, runId: String = ""): RunMetrics? = Builder(runId).apply { events.forEach(::add) }.build()
    }
}

/**
 * Aggregate over multiple runs ([of]): success rate, median duration and first feedback, average round trips and tokens, and where the time went (each part's sum as a share of the total duration sum).
 * The first feedback median is taken only over runs that have this figure; null when no run has it.
 */
data class RunStats(
    val runs: Int,
    val succeeded: Int,
    val medianTotalMs: Long,
    val medianFirstFeedbackMs: Long?,
    val avgModelCalls: Double,
    val avgTokens: Double,
    val totalMs: Long,
    val modelMs: Long,
    val observeMs: Long,
    val actMs: Long,
    val otherMs: Long,
) {
    val successRate: Double get() = if (runs == 0) 0.0 else succeeded.toDouble() / runs

    companion object {
        fun of(runs: List<RunMetrics>): RunStats = RunStats(
            runs = runs.size,
            succeeded = runs.count { it.succeeded },
            medianTotalMs = median(runs.map { it.totalMs }) ?: 0,
            medianFirstFeedbackMs = median(runs.mapNotNull { it.firstFeedbackMs }),
            avgModelCalls = if (runs.isEmpty()) 0.0 else runs.sumOf { it.modelCalls }.toDouble() / runs.size,
            avgTokens = if (runs.isEmpty()) 0.0 else runs.sumOf { it.tokens }.toDouble() / runs.size,
            totalMs = runs.sumOf { it.totalMs },
            modelMs = runs.sumOf { it.modelMs },
            observeMs = runs.sumOf { it.observeMs },
            actMs = runs.sumOf { it.actMs },
            otherMs = runs.sumOf { it.otherMs },
        )

        /** Median; for an even count, the average of the two middle values (rounded down). Null for an empty list. */
        fun median(xs: List<Long>): Long? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
