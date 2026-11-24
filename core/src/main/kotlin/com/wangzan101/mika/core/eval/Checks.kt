package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.safety.verify.Grounding

/** The facts the evaluator collected after one task run; scoring looks only at these. */
data class Observed(
    /** succeeded|unverified|failed|cancelled|confirmation|takeover|timeout|skipped */
    val outcome: String,
    val answer: String,
    /** Material picked out of the evidence ledger according to the answer, one place per read (the same set handed to the verifier at runtime, see [RunFacts.evidence]), with echoes of the Agent's own input removed. */
    val evidence: List<String>,
    /** Confirmation card fields: action / target / content / amount. */
    val confirmation: Map<String, String>?,
    /** Keyed by [Checks.screenKey]. */
    val screenHas: Map<String, Boolean>,
    /** Probes executed after the task finished: name → output. */
    val probes: Map<String, String>,
    val steps: Int,
    val wallMs: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val failReason: String?,
    /** Apps the Agent touched that are not among the task's apps; only recorded, not counted as a failure. */
    val leftScope: List<String> = emptyList(),
    /** The task's original text: words from the task echoed in the answer (app names, arithmetic expressions) do not count as values that had to be read off the screen. */
    val goal: String = "",
    /** The verdict of the pre-completion verification (pass / fail / cannot_verify); null when it was never verified. */
    val verdict: String? = null,
    /** Number of model calls in the main loop (number of ModelReply events; replay and batch-executed steps do not count). */
    val modelCalls: Int = 0,
    /** Total time waiting on the model (sum of each ModelReply.latencyMs). */
    val modelMs: Long = 0,
    /** Total tool execution time (sum of each ToolResult.durationMs). */
    val toolMs: Long = 0,
    /** The part of it spent waiting for the UI to settle (sum of each ToolResult.waitMs). */
    val waitMs: Long = 0,
    /** Number of routes a replay completed / number of routes handed back to the model midway. */
    val replayHits: Int = 0,
    val replayHandBacks: Int = 0,
    /** Number of memories supplied at the start of the task (memory_used events). */
    val memoryUsed: Int = 0,
    /** Names of the personal skills used this time (skill_used events, deduplicated). */
    val skillsUsed: List<String> = emptyList(),
    /** Keyed by [Checks.webKey]. */
    val webHas: Map<String, Boolean> = emptyMap(),
    /** The barrier reported right before the takeover that is also visible on that screen (see [RunFacts.takeoverBarrier]); null when there is none. */
    val takeoverBarrier: String? = null,
    /** Number of times the Agent asked the user (ask_user). */
    val askedUser: Int = 0,
    /** The task's day (the date on the run clock): used to check an answer that spells out "tomorrow" as a concrete date (see [Grounding.check]); null when unknown. */
    val today: java.time.LocalDate? = null,
    /** The outcome judged after the answer was given (the outcome event in the run record: done / partial / not_possible / unknown); null when never judged. Scoring follows it, see [Scoring.recorded]. */
    val judged: String? = null,
    /** The summary of this run (where time went, first feedback, number of round trips, etc., see [com.wangzan101.mika.core.run.record.RunMetrics]); null when there is no run record. */
    val metrics: com.wangzan101.mika.core.run.record.RunMetrics? = null,
)

object Checks {
    fun screenKey(c: Check.ScreenHas): String = "${c.packageName}|${c.deepLink.orEmpty()}|${c.text}"

    fun webKey(c: Check.WebHas): String = "${c.url}|${c.text}"

    fun passes(c: Check, o: Observed): Boolean = when (c) {
        is Check.AnswerContains -> c.all.all { it in o.answer }
        is Check.AnswerRegex -> Regex(c.pattern).containsMatchIn(o.answer)
        is Check.AnswerGrounded -> grounded(o.answer, o.evidence, c.minItems, o.goal, o.today)
        is Check.ScreenHas -> o.screenHas[screenKey(c)] == true
        is Check.MemoryUsed -> o.memoryUsed >= c.min
        is Check.AskedUser -> (o.askedUser > 0) == c.expected
        is Check.WebHas -> o.webHas[webKey(c)] == true
        is Check.ProbeEquals -> o.probes[c.probe]?.trim()?.let { actual ->
            if (c.contains) c.expected.trim() in actual else actual == c.expected.trim()
        } ?: false
        is Check.ConfirmationShows -> o.confirmation?.let { card ->
            listOf("action" to c.action, "target" to c.target, "content" to c.content, "amount" to c.amount)
                .all { (k, want) -> want == null || want in card[k].orEmpty() }
        } ?: false
    }

    /**
     * The answer is grounded: the same literal rule as the verification fallback ([Grounding.check]: quotes, numbers with units, serial numbers and dates are all findable in the material, and the quotes in the basis can be located),
     * and the number of grounded items ([Grounding.Result.items]) is at least [minItems]. Numbers and text in the task's [goal] count as already known.
     * Whether a value belongs to what the answer claims (which row, which page) is not judged by code but by the verification model (in the evaluation, by looking at whether the outcome is succeeded).
     */
    internal fun grounded(answer: String, screens: List<String>, minItems: Int, goal: String = "", today: java.time.LocalDate? = null): Boolean =
        Grounding.check(goal, answer, screens, today).let { it.found != false && it.items >= minItems }
}
