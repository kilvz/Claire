package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.remote.RemoteSteer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Storage format for terminal states and steer dispositions in the remote receipt store, used only in this receipt layer: renaming or adding values to the domain types ([TaskState], [FailKind]) never changes the words on disk.
 * Anything that cannot be read (corrupt format, a kind written by a newer version) does not throw; it is returned as an unreadable failure, so the desktop still gets a terminal state.
 */
internal object RemoteTaskRecord {
    private val json = Json { ignoreUnknownKeys = true }

    /** Any terminal state that cannot be understood from disk is reported as this. */
    val UNREADABLE: TaskState.Terminal = TaskState.Failed("The saved result of this task could not be read.", FailKind.Environment)

    data class Result(val state: TaskState.Terminal, val runId: String? = null)

    fun encode(state: TaskState.Terminal, runId: String? = null): String = buildJsonObject {
        runId?.let { put("run_id", it) }
        when (state) {
            is TaskState.Succeeded -> {
                put("type", "done"); put("answer", state.answer); put("verified", state.verified)
                state.note?.let { put("note", it) }
                if (state.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { state.unconfirmed.forEach { add(it) } }
                put("reviewed", state.reviewed)
            }
            is TaskState.Failed -> { put("type", "failed"); put("reason", state.reason); put("kind", wire(state.kind)) }
            TaskState.Cancelled -> put("type", "cancelled")
        }
    }.toString()

    fun decode(text: String): Result = runCatching {
        val o = json.parseToJsonElement(text) as JsonObject
        val state = when (o.str("type")) {
            "done" -> TaskState.Succeeded(
                answer = o.str("answer") ?: return Result(UNREADABLE),
                verified = o.bool("verified") ?: true, note = o.str("note"),
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                reviewed = o.bool("reviewed") ?: true,
            )
            "failed" -> TaskState.Failed(o.str("reason").orEmpty(), kind(o.str("kind")))
            "cancelled" -> TaskState.Cancelled
            else -> UNREADABLE
        }
        Result(state, o.str("run_id"))
    }.getOrDefault(Result(UNREADABLE))

    fun encodeSteers(steers: List<RemoteSteer>): String = buildJsonArray {
        steers.forEach { s -> addJsonObject { put("messageId", s.messageId); put("state", s.state) } }
    }.toString()

    /** Broken entries are skipped so they do not take down the whole result. */
    fun decodeSteers(text: String): List<RemoteSteer> = runCatching {
        (json.parseToJsonElement(text) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            RemoteSteer(o.str("messageId") ?: return@mapNotNull null, o.str("state") ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /** On-disk kind names are fixed separately and do not follow enum renames; a newly added kind fails to compile here, forcing a name to be chosen. */
    private fun wire(kind: FailKind): String = when (kind) {
        FailKind.GaveUp -> "gave_up"
        FailKind.Model -> "model"
        FailKind.Guard -> "guard"
        FailKind.StepLimit -> "step_limit"
        FailKind.TokenLimit -> "token_limit"
        FailKind.TimeLimit -> "time_limit"
        FailKind.Environment -> "environment"
        FailKind.Interrupted -> "interrupted"
    }

    /** Also accepts older records that wrote the enum name directly (e.g. `Interrupted`); unrecognized ones (written by a newer version) count as an environment error. */
    private fun kind(raw: String?): FailKind = FailKind.entries.firstOrNull { wire(it) == raw }
        ?: FailKind.entries.firstOrNull { it.name == raw } ?: FailKind.Environment

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
