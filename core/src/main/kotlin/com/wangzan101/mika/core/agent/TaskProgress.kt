package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Count or boundary progress; when the total is unknown, report only the confirmed count and the latest boundary. */
internal data class ProgressMetric(
    val name: String,
    val done: Int? = null,
    val total: Int? = null,
    val boundary: String? = null,
    val part: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME)
        require(done == null || done >= 0)
        require(total == null || total > 0)
        require(done == null || total == null || done <= total)
        require(boundary == null || boundary.length <= MAX_BOUNDARY)
    }

    val reached: Boolean get() = done != null && total != null && done == total
    fun event() = RunEvent.Progress(name, done, total, boundary)
    fun state() = RunProgress.Task(name, done, total, boundary)
}

/** Multiple goals confirmed one by one; a task may only conclude on a count when a single-goal task-level quantity is reached. */
internal data class CompletionPlan(
    val criteria: List<String> = emptyList(),
    val metric: ProgressMetric? = null,
    val allCriteriaMet: Boolean = false,
    val confirmedIds: Set<Int> = emptySet(),
) {
    val complete: Boolean get() = if (criteria.size > 1) confirmedIds.size == criteria.size
        else allCriteriaMet || confirmedIds.isNotEmpty() || (metric?.reached == true && !metric.part)

    fun state(stale: Boolean): RunProgress.Task? = if (criteria.size > 1) {
        RunProgress.Task("", confirmedIds.size, criteria.size, null, GoalChecklist(criteria, confirmedIds, stale && !complete))
    } else metric?.state()

    fun apply(u: CompletionUpdate): CompletionPlan {
        val items = u.criteria ?: criteria
        require(items.take(criteria.size) == criteria) { "criteria IDs cannot be removed or reassigned" }
        require(u.confirmed.all { it in 1..items.size }) { "unknown criterion ID" }
        val metric = u.metric?.let { change ->
            val old = metric?.takeIf { change.name == null || change.name == it.name }
            ProgressMetric(
                change.name ?: old?.name ?: throw IllegalArgumentException("metric.name is required"),
                change.done ?: old?.done,
                change.total ?: old?.total,
                change.boundary ?: old?.boundary,
                change.part ?: old?.part ?: false,
            )
        } ?: metric
        return CompletionPlan(items, metric, u.allCriteriaMet ?: allCriteriaMet, confirmedIds + u.confirmed)
    }

    fun event(atMs: Long) = RunEvent.Criteria(atMs, criteria, emptyList(), progress = metric?.event(), complete = complete,
        confirmedIds = confirmedIds.takeIf { criteria.size > 1 })
}

internal data class MetricUpdate(val name: String? = null, val done: Int? = null, val total: Int? = null, val boundary: String? = null, val part: Boolean? = null)
internal data class CompletionUpdate(
    val criteria: List<String>? = null,
    val metric: MetricUpdate? = null,
    val allCriteriaMet: Boolean? = null,
    val confirmed: Set<Int> = emptySet(),
)

/** Include pending goals only in normal model requests. Never infer a completed count from operation counts, tool successes, or free text. */
internal class TaskProgressTracker(private val now: () -> Long, private val emit: (RunEvent) -> Unit) {
    var plan = CompletionPlan()
        private set
    private var lastChange = 0

    fun state(step: Int) = plan.state(step - lastChange >= REMINDER_INTERVAL)

    fun pending(step: Int, afterFirstTool: Boolean = false): String? {
        if (afterFirstTool && plan.criteria.isEmpty() && !plan.complete) {
            emit(RunEvent.State(now(), "task_progress_plan_reminder", step.toString()))
            return "No goal checklist: <task_progress>"
        }
        if (plan.criteria.size <= 1 || plan.complete) return null
        val items = plan.criteria.mapIndexedNotNull { i, text ->
            if (i + 1 in plan.confirmedIds) null else "${i + 1}: ${JsonPrimitive(text)}"
        }
        val age = step - lastChange
        val reminder = if (age >= REMINDER_INTERVAL && age % REMINDER_INTERVAL == 0)
            " If any is now verified, report its ID and evidence with this reply." else ""
        return "Pending goal IDs: ${items.joinToString("; ")}.$reminder"
    }

    fun accept(raw: String, step: Int): String {
        val parsed = CompletionPlanBlock.extract(raw)
        acceptParsed(parsed, step)
        return parsed.text
    }

    /** Handles an already-parsed progress block (it may come from a text block, or from a `task_progress` call mistakenly written as a tool invocation). Returns whether it was valid. */
    fun acceptParsed(parsed: CompletionPlanBlock.Extracted, step: Int): Boolean {
        var valid = parsed.errors.isEmpty()
        fun invalid(reason: String) {
            valid = false
            emit(RunEvent.State(now(), "task_progress_invalid", RunEvent.clip(reason)))
        }
        parsed.errors.forEach(::invalid)
        for (update in parsed.updates) {
            val next = try { plan.apply(update) } catch (e: IllegalArgumentException) {
                invalid(e.message ?: "invalid progress")
                continue
            }
            if (next == plan) continue
            if (next.confirmedIds != plan.confirmedIds || next.criteria != plan.criteria) lastChange = step
            plan = next
            emit(next.event(now()))
        }
        return valid
    }

    private companion object { const val REMINDER_INTERVAL = 3 }
}

/**
 * The `<task_progress>` JSON block the main model emits alongside real tool calls or its final answer. It is not a tool and adds
 * no request or schema; after the code strips it out, the UI and subsequent history see only the ordinary prose outside it.
 */
internal object CompletionPlanBlock {
    private const val OPEN = "<task_progress>"
    private val BLOCK = Regex("$OPEN(.*?)</task_progress>", RegexOption.DOT_MATCHES_ALL)
    private val ROOT_KEYS = setOf("criteria", "metric", "complete", "confirmed")
    private val METRIC_KEYS = setOf("name", "done", "total", "boundary", "scope")

    class Extracted(val text: String, val updates: List<CompletionUpdate>, val errors: List<String>, val stage: Int?)

    /** Streamed text may also stop midway through the opening marker; neither complete blocks nor a trailing unfinished block are displayed. */
    fun visible(text: String): String {
        val complete = BLOCK.replace(text, "")
        val incomplete = incompleteStart(complete)
        return if (incomplete < 0) complete else complete.substring(0, incomplete)
    }

    private fun incompleteStart(text: String): Int {
        val open = text.indexOf(OPEN)
        if (open >= 0) return open
        val last = text.lastIndexOf('<')
        if (last < 0) return -1
        val suffix = text.substring(last)
        return if (suffix.length >= 2 && OPEN.startsWith(suffix)) last else -1
    }

    fun extract(text: String): Extracted = decode(text, BLOCK.findAll(text).map { it.groupValues[1].trim() })

    fun fromCall(body: String): Extracted = decode("", sequenceOf(body))

    private fun decode(text: String, bodies: Sequence<String>): Extracted {
        val updates = mutableListOf<CompletionUpdate>()
        val errors = mutableListOf<String>()
        val stages = mutableSetOf<Int>()
        bodies.forEach { body ->
            if (body.length > MAX_BLOCK) {
                errors += "task_progress block is too long"
                return@forEach
            }
            try {
                val root = Json.parseToJsonElement(body) as? JsonObject ?: throw IllegalArgumentException("task_progress must be a JSON object")
                val progress = root - "stage"
                if (progress.isNotEmpty() || "stage" !in root) updates += parse(JsonObject(progress))
                if ("stage" in root) {
                    val ordinal = (root["stage"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    require(ordinal != null && ordinal > 0) { "invalid stage ordinal" }
                    stages += ordinal
                }
            } catch (e: Exception) {
                errors += e.message ?: "invalid task_progress block"
            }
        }
        if (incompleteStart(BLOCK.replace(text, "")) >= 0) errors += "unclosed task_progress block"
        return Extracted(visible(text).trim(), updates, errors, stages.singleOrNull())
    }

    private fun parse(root: JsonObject): CompletionUpdate {
        require(root.keys.all { it in ROOT_KEYS }) { "unknown task_progress field" }
        val criteria = root["criteria"]?.let { value ->
            val items = value as? JsonArray ?: throw IllegalArgumentException("criteria must be an array")
            require(items.size in 1..MAX_CRITERIA) { "criteria must contain 1..$MAX_CRITERIA items" }
            items.map { item ->
                val text = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    ?: throw IllegalArgumentException("criterion must be a string")
                require(text.isNotEmpty() && text.length <= MAX_CRITERION) { "criterion is empty or too long" }
                text
            }
        }
        val confirmed = root["confirmed"]?.let { value ->
            val entries = value as? JsonArray ?: throw IllegalArgumentException("confirmed must be an array")
            require(entries.size <= MAX_CRITERIA) { "too many confirmations" }
            entries.map { entry ->
                val obj = entry as? JsonObject ?: throw IllegalArgumentException("invalid confirmation")
                require(obj.keys == setOf("id", "evidence")) { "confirmation needs id and evidence" }
                require((obj.string("evidence")?.trim()?.length ?: 0) in 1..MAX_CRITERION) { "missing or oversized evidence" }
                obj.int("id") ?: throw IllegalArgumentException("missing criterion ID")
            }.toSet()
        }
        val metric = root["metric"]?.let { value ->
            val obj = value as? JsonObject ?: throw IllegalArgumentException("metric must be an object")
            require(obj.keys.all { it in METRIC_KEYS }) { "unknown metric field" }
            require(obj.isNotEmpty()) { "metric must not be empty" }
            MetricUpdate(
                obj.string("name")?.also { require(it.isNotBlank() && it.length <= MAX_NAME) { "invalid metric.name" } },
                obj.int("done")?.also { require(it >= 0) { "metric.done must be non-negative" } },
                obj.int("total")?.also { require(it > 0) { "metric.total must be positive" } },
                obj.string("boundary")?.also { require(it.length <= MAX_BOUNDARY) { "metric.boundary is too long" } },
                obj.string("scope")?.let { require(it == "part" || it == "task") { "unknown metric scope" }; it == "part" },
            ).also { change ->
                if (change.done != null && change.total != null) require(change.done <= change.total) { "metric.done exceeds total" }
            }
        }
        val complete = (root["complete"] as? JsonPrimitive)?.booleanOrNull
        if (root.containsKey("complete")) require(complete != null) { "complete must be boolean" }
        require(criteria != null || metric != null || complete != null || !confirmed.isNullOrEmpty()) { "empty task_progress update" }
        return CompletionUpdate(criteria, metric, complete, confirmed.orEmpty())
    }

    private fun JsonObject.string(key: String): String? = this[key]?.let {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw IllegalArgumentException("$key must be a string")
    }

    private fun JsonObject.int(key: String): Int? = this[key]?.let {
        (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("$key must be an integer")
    }

    private const val MAX_BLOCK = 8_000
    private const val MAX_CRITERIA = 12
    private const val MAX_CRITERION = 300
}

private const val MAX_NAME = 80
private const val MAX_BOUNDARY = 200

/** Collection stops after consecutive runs with no new data. Only real observations reset the counter; progress notes written by the model cannot. */
internal class NewDataTracker {
    private val seen = mutableSetOf<Long>()
    var stale = 0
        private set

    /** Returns true when the consecutive no-new-data limit is reached. Failed calls and calls without text neither interrupt nor increment the count. */
    fun after(verb: Verb?, exec: ToolExecution): Boolean {
        if (verb !in COLLECTION_VERBS || exec.content["ok"] != JsonPrimitive(true)) return false
        val reads = exec.observations.filter { it.kind != ObsKind.Fact && it.text.isNotBlank() }
        if (reads.isEmpty()) return false
        val fresh = reads.map { seen.add(fingerprint(it)) }.any { it }
        if (fresh) {
            stale = 0
            return false
        }
        stale++
        return stale >= LIMIT
    }

    private fun fingerprint(o: Observation): Long {
        var h = -0x340d631b7bdddcdbL
        val value = listOf(o.kind.wire, o.place?.kind?.wire.orEmpty(), o.place?.name.orEmpty(), o.text).joinToString("\u0000")
        for (b in value.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        return h
    }

    companion object {
        const val LIMIT = 3
        private val COLLECTION_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Query, Verb.Collect, Verb.PhoneExtract,
            Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand, Verb.Scroll, Verb.Swipe,
        )
    }
}
