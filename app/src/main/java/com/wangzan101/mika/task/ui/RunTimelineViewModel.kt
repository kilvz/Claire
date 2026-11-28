package com.wangzan101.mika.task.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.record.RunStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.ui.ToolLabels

enum class TagTone { Ok, Warn, Bad }

/** Tags on the timeline: the conclusions of guards, verification, confirmation, takeover and barriers. */
data class Tag(val text: String, val tone: TagTone)

/** Activities reported by the actual execution entry point; consecutive reads can be merged into one row, while actions stay individually visible. */
data class TimelineActivity(
    val kind: String,
    val label: String,
    val target: String?,
    val effect: String?,
    val count: Int = 1,
    val obsIds: List<Int> = emptyList(),
)

/** Recorded elapsed times. Sub-actions of a batch/script are included in the outer tool's time and are counted only once. */
data class StepTiming(
    val modelMs: Long? = null,
    val toolMs: Long? = null,
    val retryMs: Long = 0,
) {
    val totalMs: Long? get() = if (modelMs != null && toolMs != null) modelMs + toolMs else null
}

/**
 * One row of the timeline. [said] is what the model said in this step, [tool] is the tool's localized label,
 * [detail] is the single argument that best identifies the object (already redacted in the record and shown as is), and [screenshotId] is the picture saved for this step.
 */
data class TimelineStep(
    val step: Int,
    val said: String,
    val tool: String?,
    val detail: String?,
    val result: String?,
    val tags: List<Tag>,
    val screenshotId: Int?,
    val activities: List<TimelineActivity> = emptyList(),
    val timing: StepTiming = StepTiming(),
    /** The full command this step ran, when its arguments contain one (root and system commands); the title only has the same argument mixed in with the tool name. */
    val command: String? = null,
    /** The command's stdout/stderr (or the tool's plain output), with the untrusted markers removed; null when the result has no such text. */
    val output: String? = null,
)

data class TimelineHeader(
    val goal: String,
    val criteria: List<String>,
    val outcome: String,
    /** Tone of the outcome: done (green), not done (red), only partly done (yellow), everything else (grey). */
    val outcomeTone: TileTone = TileTone.Neutral,
    /** This run has not ended yet: outcome, verification and statistics are not all written, so the page keeps reading every second (see [RunTimelineScreen]). */
    val running: Boolean = false,
    val verdict: String,
    val duration: String?,
    val modelCalls: Int,
    /** Step limit of this run; null when unlimited or for old records. */
    val stepLimit: Int? = null,
    /** The persona this run used (name · revision); null when none. */
    val persona: String? = null,
    /** How many steps were used (shown together with [stepLimit]); the final wrap-up round after the steps run out is recorded as limit plus one and is not counted as a step, so the limit is never exceeded. */
    val stepsUsed: Int = 0,
    /** The reported-token budget shared by the whole run; null for old records or when unlimited. */
    val tokenBudget: Long? = null,
    /** Reported input plus output across all metered calls; while running, before there is a totals event, the main loop's usage is shown first. */
    val tokensUsed: Long = 0,
    val usedMemory: List<String> = emptyList(),
    /** The summary of this run; null when the record has no start event (only the duration is shown). */
    val metrics: RunMetrics? = null,
    val confirmedIds: Set<Int>? = null,
)

sealed interface TimelineUi {
    data object Loading : TimelineUi
    /** The record has been cleaned up (only the latest 30 are kept) or the ID is wrong. */
    data object Expired : TimelineUi
    data class Ready(val header: TimelineHeader, val steps: List<TimelineStep>) : TimelineUi

    companion object {
        val EXPIRED_TEXT: String get() = tr(R.string.timeline_expired)
    }
}

/** Read-only timeline of one run. [load] reads from disk and must be called on a background thread. */
class RunTimelineViewModel(
    private val store: RunStore, val runId: String,
    private val toolLabels: ToolLabels = ToolLabels(knownTools()),
    private val resolveMemory: (List<String>) -> List<String> = { emptyList() },
    /**
     * The task of this run is still running right now. A run with no end record whose task is also gone (the process was killed, the record was half written) counts as ended,
     * and the page does not keep re-reading it every second.
     */
    private val alive: (runId: String) -> Boolean = { false },
) {
    fun load(): TimelineUi {
        val summary = store.list().firstOrNull { it.id == runId }
        val events = store.events(runId)
        if (summary == null && events.isEmpty()) return TimelineUi.Expired
        val started = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val startMs = summary?.startedAtMs ?: events.first().atMs
        val endMs = summary?.endedAtMs ?: ended?.atMs
        val metrics = RunMetrics.of(events, runId)
        val criteriaEvents = events.filterIsInstance<RunEvent.Criteria>()
        val goals = criteriaEvents.lastOrNull { it.confirmedIds != null }
        val header = TimelineHeader(
            goal = summary?.goal ?: started?.goal.orEmpty(),
            criteria = goals?.criteria ?: summary?.criteria?.takeIf { it.isNotEmpty() } ?: criteriaEvents.lastOrNull()?.criteria.orEmpty(),
            confirmedIds = goals?.confirmedIds,
            outcome = outcomeLabel(summary?.outcome ?: ended?.outcome,
                summary?.judged ?: events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome),
            outcomeTone = outcomeTone(summary?.outcome ?: ended?.outcome,
                summary?.judged ?: events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
                running = ended == null && summary?.endedAtMs == null && alive(runId)),
            // An end event or an end time, either one, means it has finished; when neither exists the task must really be running, and the UI uses that to decide whether to re-read every second
            running = ended == null && summary?.endedAtMs == null && alive(runId),
            verdict = verdictLabel(summary?.verdict ?: events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict),
            duration = endMs?.let { duration(it - startMs) },
            modelCalls = events.count { it is RunEvent.ModelReply },
            stepLimit = started?.stepLimit,
            persona = events.filterIsInstance<RunEvent.State>().lastOrNull { it.name == "persona" }?.detail,
            stepsUsed = metrics?.steps?.let { n -> started?.stepLimit?.let { minOf(n, it) } ?: n } ?: 0,
            tokenBudget = started?.tokenBudget,
            tokensUsed = metrics?.let { m ->
                if (m.allInputTokens != null || m.allOutputTokens != null) (m.allInputTokens ?: 0) + (m.allOutputTokens ?: 0) else m.tokens
            } ?: 0,
            usedMemory = resolveMemory(events.filterIsInstance<RunEvent.MemoryUsed>().lastOrNull()?.ids.orEmpty()),
            metrics = metrics,
        )
        return TimelineUi.Ready(header, steps(events, toolLabels::label))
    }

    fun screen(screenshotId: Int): File? = store.screen(runId, screenshotId)

    fun usageLog(): String = store.usageLog(runId)

    companion object {
        /** How much command output a row keeps for display; native results can be 100k characters, far more than a phone screen can render at once. */
        const val OUTPUT_MAX = 20_000

        /** Groups by step number; events without a step number (verification, confirmation, takeover, barrier) hang on the most recent step before them. */
        internal fun steps(events: List<RunEvent>, toolLabel: (String) -> String = { it }): List<TimelineStep> {
            val rows = LinkedHashMap<Int, TimelineStep>()
            val modelTimes = mutableMapOf<Int, Long>()
            val retryTimes = mutableMapOf<Int, Long>()
            val toolTimes = mutableMapOf<Int, MutableList<RunEvent.ToolResult>>()
            var current: Int? = null
            fun at(step: Int) = rows.getOrPut(step) { TimelineStep(step, "", null, null, null, emptyList(), null) }.also { current = step }
            fun tag(t: Tag) { current?.let { s -> rows[s] = rows.getValue(s).let { it.copy(tags = it.tags + t) } } }
            for (e in events) when (e) {
                // A lightweight-model reply that was reassigned was not executed, so it does not enter the steps; the call count still includes it
                is RunEvent.ModelReply -> {
                    val elapsed = e.latencyMs.coerceAtLeast(0) + e.retryWaitMs.coerceAtLeast(0)
                    if (elapsed > 0) modelTimes[e.step] = modelTimes.getOrDefault(e.step, 0) + elapsed
                    if (e.retryWaitMs > 0) retryTimes[e.step] = retryTimes.getOrDefault(e.step, 0) + e.retryWaitMs
                    if (e.acted) {
                        val row = at(e.step)
                        val said = listOf(row.said, e.text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                        rows[e.step] = row.copy(
                            said = said,
                            tool = e.tool?.let(toolLabel) ?: row.tool,
                            detail = e.argsJson?.let(::detailOf) ?: row.detail,
                            command = e.argsJson?.let(::commandOf) ?: row.command,
                        )
                    }
                }
                is RunEvent.ToolResult -> {
                    toolTimes.getOrPut(e.step) { mutableListOf() } += e
                    val row = at(e.step)
                    rows[e.step] = row.copy(
                        result = resultLabel(e.resultJson, e.changed),
                        tags = row.tags + guardTags(e.resultJson),
                        screenshotId = row.screenshotId ?: e.screenshotId,
                        output = e.resultJson.let(::outputOf) ?: row.output,
                    )
                }
                is RunEvent.Media -> at(e.step).let { rows[e.step] = it.copy(screenshotId = it.screenshotId ?: e.mediaId) }
                is RunEvent.Activity -> {
                    val row = at(e.step)
                    // Calls via MCP form their own kind of row: they name the connector and do not expose the MCP tool name to the user
                    val mcp = e.mcpConnector?.let(StatusCopy::calledMcp)
                    val next = TimelineActivity(
                        if (mcp != null) "mcp" else e.kind, mcp ?: toolLabel(e.label),
                        RunEvent.displayPlace(e.target), e.effect, obsIds = e.obsIds,
                    )
                    val last = row.activities.lastOrNull()
                    val activities = if (next.kind == "observe" && last?.kind == "observe" && last.target == e.target) {
                        row.activities.dropLast(1) + last.copy(count = last.count + 1, obsIds = (last.obsIds + e.obsIds).distinct())
                    } else row.activities + next
                    rows[e.step] = row.copy(activities = activities)
                }
                is RunEvent.Verifier -> tag(verifierTag(e.verdict))
                is RunEvent.Confirmation -> tag(
                    when (e.decision) {
                        "approved" -> Tag(tr(R.string.tag_approved), TagTone.Ok)
                        "declined", "declined_before" -> Tag(tr(R.string.tag_declined), TagTone.Bad)
                        else -> Tag(tr(R.string.tag_needs_ok), TagTone.Warn)
                    },
                )
                is RunEvent.Takeover -> tag(Tag(tr(R.string.tag_takeover), TagTone.Warn))
                is RunEvent.Barrier -> tag(Tag(tr(R.string.tag_barrier), TagTone.Warn))
                else -> Unit
            }
            return rows.values.map { row ->
                val results = toolTimes[row.step].orEmpty()
                // do_steps and composite tools record sub-actions too; the outer durationMs already includes them.
                val outer = results.filter { it.source == "model" && it.durationMs > 0 }
                val measured = (outer.ifEmpty { results.filter { it.durationMs > 0 } }).sumOf { it.durationMs }
                row.copy(
                    said = row.said,
                    timing = StepTiming(modelTimes[row.step], measured.takeIf { it > 0 }, retryTimes[row.step] ?: 0),
                )
            }
        }

        internal fun timingLabel(t: StepTiming): String {
            if (t.modelMs == null && t.toolMs == null) return tr(R.string.timing_unrecorded)
            return buildList {
                t.totalMs?.let { add(tr(R.string.timing_total, shortDuration(it))) }
                t.modelMs?.let { add(tr(R.string.timing_model, shortDuration(it))) }
                t.toolMs?.let { add(tr(R.string.timing_tool, shortDuration(it))) }
                if (t.retryMs > 0) add(tr(R.string.timing_retry, shortDuration(t.retryMs)))
            }.joinToString(" · ")
        }

        private fun shortDuration(ms: Long): String = when {
            ms < 1_000 -> tr(R.string.duration_ms, ms)
            ms < 60_000 -> tr(R.string.duration_seconds_decimal, "%.1f".format(java.util.Locale.ROOT, ms / 1_000.0))
            else -> duration(ms)
        }

        private val DETAIL_KEYS = listOf("command", "text", "package", "query", "key", "direction", "ref", "answer", "reason")

        private fun detailOf(argsJson: String): String? {
            val o = parse(argsJson) ?: return null
            // Kept whole: the collapsed row clips it with maxLines, and expanding shows the complete title
            return DETAIL_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        }

        /** The complete command from the model's arguments, never clipped: the timeline shows it in full when the step is expanded. */
        private fun commandOf(argsJson: String): String? {
            val o = parse(argsJson) ?: return null
            return COMMAND_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        }

        private val COMMAND_KEYS = listOf("command")

        /**
         * The command's output: root and system commands return `stdout`/`stderr` (wrapped as untrusted data), other shell-like tools use `output`.
         * Only plain text fields are taken, so other tools' JSON (element lists, media metadata) is not dumped into the timeline.
         */
        internal fun outputOf(resultJson: String): String? {
            val o = parse(resultJson) ?: return null
            fun field(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            // stderr first: when a command fails, the reason matters more than a long stdout and must not be cut off
            val parts = listOfNotNull(field("stderr"), field("stdout"), field("output")).distinct()
            if (parts.isEmpty()) return null
            val text = parts.joinToString("\n\n") { plain(it) }
            return if (text.length <= OUTPUT_MAX) text else text.take(OUTPUT_MAX) + "\n…"
        }

        /** Removes the untrusted-data markers the record wraps around command output. */
        private fun plain(text: String): String =
            text.removePrefix("<untrusted>").removeSuffix("</untrusted>").trim()

        // The result may be truncated into invalid JSON: ok / error are found only in their top-level key form (quotes inside element text are escaped and do not falsely match)
        private val NOT_OK = Regex(""""ok"\s*:\s*false""")
        private val NOT_COUNTED = Regex(""""counts_as_failure"\s*:\s*false""")
        private val ERROR = Regex(""""error"\s*:\s*"([a-z_]+)"""")

        /** Playback matches results by actual call and then reuses the same error labels instead of guessing the failure cause from the turn or the model's plan. */
        internal fun failureLabel(json: String): String? {
            val parsed = parse(json)
            if (parsed != null && parsed["ok"] != JsonPrimitive(false)) return null
            if (parsed == null && !NOT_OK.containsMatchIn(json)) return null
            val code = (parsed?.get("error") as? JsonPrimitive)?.contentOrNull
                ?: ERROR.find(json)?.groupValues?.get(1)
            val message = (parsed?.get("message") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val reason = ERROR_LABELS[code] ?: message?.let { com.wangzan101.mika.core.run.record.Redactor.text(it).take(240) }
                ?: code?.takeIf { it.isNotBlank() }?.take(100)
            return reason?.let { tr(R.string.result_error_detail, it) }
        }

        private fun resultLabel(json: String, changed: Boolean?): String = when {
            // A seizure by the rules whose result states it does not count as a failure (when writing memory) is not an error: only the seizure and the reason are stated, without "error", so this step is not marked failed either.
            // The same error code from other tools (saving a skill, editing a profile) still counts as a failure and still says error
            NOT_OK.containsMatchIn(json) -> ERROR.find(json)?.groupValues?.get(1).let { code ->
                ChatCopy.declinedLabel(code)?.takeIf { NOT_COUNTED.containsMatchIn(json) }
                    ?: ERROR_LABELS[code]?.let { tr(R.string.result_error_detail, it) } ?: tr(R.string.result_error)
            }
            changed == true -> tr(R.string.result_effective)
            changed == false -> tr(R.string.result_unchanged)
            else -> tr(R.string.result_completed)
        }

        private fun guardTags(json: String): List<Tag> = guardObjects(json).mapNotNull { o ->
            val severity = (o["severity"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val what = (o["code"] as? JsonPrimitive)?.contentOrNull?.let { GUARD_LABELS[it] }
            val head = if (severity == "stop") tr(R.string.guard_stop) else tr(R.string.guard_warn)
            Tag(if (what == null) head else tr(R.string.guard_with_what, head, what), if (severity == "stop") TagTone.Bad else TagTone.Warn)
        }

        /**
         * Each item of the guard array in the result. Complete JSON is parsed directly; when truncated, complete objects are taken one by one starting from `"guard":[`,
         * and the incomplete one at the truncation point is dropped.
         */
        private fun guardObjects(json: String): List<JsonObject> {
            parse(json)?.let { o -> return (o["guard"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }
            val start = GUARD_START.findAll(json).lastOrNull()?.range?.last?.plus(1) ?: return emptyList()
            val out = mutableListOf<JsonObject>()
            var depth = 0
            var inString = false
            var escaped = false
            var from = -1
            for (i in start until json.length) {
                val c = json[i]
                when {
                    escaped -> escaped = false
                    inString -> if (c == '\\') escaped = true else if (c == '"') inString = false
                    c == '"' -> inString = true
                    c == '{' -> { if (depth == 0) from = i; depth++ }
                    c == '}' -> { depth--; if (depth == 0) parse(json.substring(from, i + 1))?.let(out::add) }
                    c == ']' && depth == 0 -> break
                }
            }
            return out
        }

        private val GUARD_START = Regex(""""guard"\s*:\s*\[""")

        private fun parse(s: String): JsonObject? = try {
            Json.parseToJsonElement(s) as? JsonObject
        } catch (e: SerializationException) {
            null
        }

        private val GUARD_LABELS get() = mapOf(
            "repeating_action" to tr(R.string.guard_repeating),
            "no_progress" to tr(R.string.guard_no_progress),
            "too_many_failures" to tr(R.string.guard_failures),
        )

        private val ERROR_LABELS get() = mapOf(
            "ref_expired" to tr(R.string.err_ref_expired),
            "no_app_open" to tr(R.string.err_no_app),
            "invalid_arguments" to tr(R.string.err_invalid_args),
            "operation_failed" to tr(R.string.err_op_failed),
            "unknown_tool" to tr(R.string.err_unknown_tool),
            "protected_content" to tr(R.string.err_protected),
            "confirmation_required" to tr(R.string.err_confirm_required),
            "confirmation_mismatch" to tr(R.string.err_confirm_mismatch),
            "confirmation_declined" to tr(R.string.err_confirm_declined),
            "action_not_applied" to tr(R.string.err_not_applied),
            "verification_failed" to tr(R.string.verify_failed),
            "parallel_tool_call_skipped" to tr(R.string.err_parallel_skipped),
            "screenshot_unavailable" to tr(R.string.err_no_screenshot),
            "display_capture_unavailable" to tr(R.string.err_no_screenshot),
            "vision_unavailable" to tr(R.string.err_vision),
            "payment_requires_takeover" to tr(R.string.err_payment),
            "finish_missing_evidence" to tr(R.string.err_no_evidence),
            "double_click_unsupported" to tr(R.string.err_double_click),
            "app_blocked" to tr(R.string.err_app_blocked),
            "app_access_unverified" to tr(R.string.err_app_unverified),
            "app_access_unavailable" to tr(R.string.err_app_unavailable),
            "app_access_protected" to tr(R.string.err_app_protected),
        )

        private fun verifierTag(verdict: String) = when (verdict) {
            "pass" -> Tag(tr(R.string.verify_passed), TagTone.Ok)
            "fail" -> Tag(tr(R.string.verify_failed), TagTone.Bad)
            else -> Tag(tr(R.string.verify_unverified), TagTone.Warn)
        }

        private fun verdictLabel(verdict: String?) = if (verdict == null) tr(R.string.verify_none) else verifierTag(verdict).text

        /** Tone of the outcome: done is green, not done is red, only partly done / not possible is yellow, and running or unverified is grey. */
        private fun outcomeTone(outcome: String?, judged: String?, running: Boolean): TileTone = when {
            running -> TileTone.Neutral
            outcome == "failed" || outcome == "error" -> TileTone.Bad
            judged == "partial" || judged == "not_possible" -> TileTone.Warn
            outcome == "succeeded" -> TileTone.Ok
            else -> TileTone.Neutral
        }

        /** [judged]: the outcome judged after the answer was delivered; when the answer honestly says it was not done or only partly done, this is what is written. */
        private fun outcomeLabel(outcome: String?, judged: String?) = when {
            outcome != "succeeded" && outcome != "unverified" -> outcomeLabel(outcome)
            judged == "partial" -> tr(R.string.outcome_partial)
            judged == "not_possible" -> tr(R.string.outcome_not_possible)
            else -> outcomeLabel(outcome)
        }

        private fun outcomeLabel(outcome: String?) = when (outcome) {
            "succeeded" -> tr(R.string.outcome_succeeded)
            "unverified" -> tr(R.string.outcome_unverified)
            "failed" -> tr(R.string.outcome_failed)
            "cancelled" -> tr(R.string.steer_cancelled)
            "error" -> tr(R.string.result_error)
            null -> tr(R.string.outcome_unfinished)
            else -> outcome
        }

        internal fun duration(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s < 60) tr(R.string.duration_s, s) else tr(R.string.duration_m_s, s / 60, s % 60)
        }
    }
}
