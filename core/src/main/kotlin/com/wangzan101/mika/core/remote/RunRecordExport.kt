package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A computer pulling one run record. Format version [FORMAT]: the header states task_id -> conversation_id -> run_id,
 * `run` is run.json after another pass of redaction, and `events` are the events after another pass of redaction.
 * Screenshot file bodies are not included; media in events keeps only the ID and step number. In progress (not yet finished) is marked [partial].
 */
object RunRecordExport {
    const val FORMAT = 1

    /** The same scheme as the directory names under `files/runs/<id>/` on the phone. */
    val RUN_ID = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

    /** Arguments, tool results and verification details are JSON embedded inside strings, so they go through the page-data redaction path once more. */
    private val JSON_TEXT = setOf("argsJson", "resultJson", "detailJson")

    data class Payload(val json: JsonObject, val jsonl: String)

    fun of(record: RunExportRecord): Payload {
        val events = record.events.map { event ->
            if (event is RunEvent.Media) buildJsonObject {
                put("type", "media"); put("atMs", event.atMs); put("mediaId", event.mediaId); put("step", event.step)
            } else redact(RunEvent.json.parseToJsonElement(RunEvent.encode(event)))
        }
        val summary = record.summary?.let { redact(RunEvent.json.parseToJsonElement(RunEvent.json.encodeToString(RunSummary.serializer(), it))) }
        val partial = record.summary?.endedAtMs == null && record.events.none { it is RunEvent.TaskEnded }
        val header = buildJsonObject {
            put("exportFormat", FORMAT)
            put("partial", partial)
            put("includesScreenshots", false)
            putNullable("task_id", record.taskId)
            putNullable("conversation_id", record.conversationId)
            putNullable("run_id", record.runId)
            put("run", summary ?: JsonNull)
        }
        val json = JsonObject(header + ("events" to JsonArray(events)))
        val jsonl = buildString {
            append(header.toString())
            append('\n')
            events.forEach {
                append(it.toString())
                append('\n')
            }
        }
        return Payload(json, jsonl)
    }

    /** Every string passes through [Redactor.text] again; nested JSON text passes through [Redactor.pageData] again (covers Key, JWT and credential fields). */
    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            if (key in JSON_TEXT && value is JsonPrimitive && value.isString) JsonPrimitive(Redactor.pageData(value.content))
            else redact(value)
        })
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(Redactor.text(element.content)) else element
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }
}

/**
 * Looks up an exportable record by task ID or run record ID. Runs started on the phone are not included.
 * Implementations only read records already written to disk and attach no screenshot bytes.
 */
interface RunExportSource {
    fun scope(owner: String): RunExportSource = this
    fun load(id: String): RunExportLookup
}

sealed interface RunExportLookup {
    /** No such computer task, and no exportable run record either. */
    data object Missing : RunExportLookup

    /** The task still remembers the run ID, but the record file is gone. */
    data class Gone(val taskId: String, val runId: String) : RunExportLookup

    data class Found(val record: RunExportRecord) : RunExportLookup
}

/**
 * [runId] and [summary] are both present or both absent: the task has been handed off but the run record has not started yet, so both are empty and
 * the export is marked partial.
 */
data class RunExportRecord(
    val taskId: String?,
    val conversationId: String?,
    val runId: String?,
    val summary: RunSummary?,
    val events: List<RunEvent>,
) {
    init { require((runId == null) == (summary == null)) }
}
