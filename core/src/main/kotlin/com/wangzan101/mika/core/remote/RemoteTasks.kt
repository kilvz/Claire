package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest

/** A task submitted by a computer: a null [state] means it has been handed to the task service but not registered yet (it will be shortly). */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: TaskState?,
    val steers: List<RemoteSteer> = emptyList(),
    val runId: String? = null,
    val files: List<TaskFile> = emptyList(),
    /** Irreversible digest of the access token; used only for ownership checks, never appears in responses. On first read, legacy data is assigned to the first authorized client. */
    val owner: String? = null,
)

data class RemoteTaskPage(val tasks: List<RemoteTask>, val nextBefore: String? = null)

/** Reports only messages appended by the computer; joined means it entered the current task's context, not that it was executed successfully. */
data class RemoteSteer(val messageId: String, val state: String)

sealed interface StartOutcome {
    data class Started(val task: RemoteTask) : StartOutcome
    data class Refused(val message: String) : StartOutcome
}

/** Access tokens enter the task layer only as SHA-256 digests; the API, logs and exports never store or expose the original token. */
object RemoteOwnership {
    const val UNSCOPED = "unscoped"
    /** USB tasks are kept along with the local connection method; authentication still uses the token generated at the current boot. */
    const val USB = "usb"
    fun of(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * The task entry point the app side provides to computer connections. [scope] is the sole ownership entry for each authenticated request; both
 * HTTP and MCP must use it, so that the same computer-access token can only read, modify, upload, download or export the tasks it created itself.
 */
interface RemoteTasks {
    val fileAccess: RemoteTaskFiles? get() = null
    fun scope(owner: String): RemoteTasks = this
    fun start(goal: String, conversation: String?, requestId: String? = null, attachments: List<String> = emptyList()): StartOutcome
    fun get(id: String): RemoteTask?
    /** Newest first. Kept for the existing MCP and direct service calls. */
    fun list(limit: Int): List<RemoteTask>
    /** Stable cursor pagination; older implementations without cursor support can still work off the original list. */
    fun page(limit: Int, before: String? = null): RemoteTaskPage = RemoteTaskPage(list(limit))
    /** Called only when the task is currently waiting for an answer to a follow-up question; returns true when it was handed to the task. */
    fun answer(id: String, text: String): Boolean
    /** Atomically confirms the message entered the current task's inbox; it cannot be turned into a new task. Returns the message ID, or null if refused. */
    fun steer(id: String, text: String): String?
    fun cancel(id: String): Boolean
}

/** How task state is described to a computer; it only reads the task's own state and keeps no separate record. */
object RemoteStatus {
    const val STARTING = "starting"
    const val RUNNING = "running"
    const val QUEUED = "queued"
    const val PAUSED = "paused"
    const val WAITING_FOR_ANSWER = "waiting_for_answer"
    const val WAITING_ON_PHONE = "waiting_on_phone"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered remotely. " +
        "Claire has shown its usual notification there; keep waiting or cancel the task."

    fun of(state: TaskState?): String = when (state) {
        null, TaskState.Idle -> STARTING
        is TaskState.Running -> RUNNING
        is TaskState.Held -> when {
            state.reason.needsYou -> WAITING_ON_PHONE
            state.reason.queued -> QUEUED
            else -> PAUSED
        }
        is TaskState.WaitingInput -> WAITING_FOR_ANSWER
        is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> WAITING_ON_PHONE
        is TaskState.Succeeded -> DONE
        is TaskState.Failed -> FAILED
        TaskState.Cancelled -> CANCELLED
    }

    fun finished(status: String) = status == DONE || status == FAILED || status == CANCELLED

    /** What a task looks like right now: status, latest progress note, what it is waiting for, and the result. */
    fun describe(t: RemoteTask): JsonObject = buildJsonObject {
        val s = t.state
        val status = of(s)
        put("task_id", t.id)
        put("status", status)
        put("conversation", t.conversation)
        put("goal", t.goal)
        put("started_at_ms", t.createdAtMs)
        t.runId?.let { put("run_id", it) }
        when (s) {
            is TaskState.Running -> {
                put("step", s.step)
                (s.narration ?: s.reason ?: s.note).takeIf { it.isNotBlank() }?.let { put("progress", it) }
            }
            is TaskState.Held -> {
                put("waiting_for", s.reason.code)
                s.reason.why.takeIf { it.isNotBlank() }?.let { put("progress", it) }
                if (s.reason.needsYou) put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingInput -> {
                put("question", s.prompt)
                s.options?.takeIf { it.isNotEmpty() }?.let { o -> putJsonArray("options") { o.forEach { add(it) } } }
                put("note", "Claire is asking a question. Answer this question remotely or on the phone.")
            }
            is TaskState.WaitingConfirmation -> {
                put("waiting_for", "confirmation")
                put("app", s.request.app)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.WaitingTakeover -> {
                put("waiting_for", "hand_to_user")
                put("reason", s.reason)
                put("message", s.message)
                put("note", ON_PHONE_NOTE)
            }
            is TaskState.Succeeded -> {
                put("answer", s.answer)
                if (s.reviewed) put("verified", s.verified)
                s.note?.let { put("verification_note", it) }
                if (s.unconfirmed.isNotEmpty()) putJsonArray("unconfirmed") { s.unconfirmed.forEach { add(it) } }
            }
            is TaskState.Failed -> {
                put("reason", s.reason)
                put("failure_kind", s.kind.name.lowercase())
            }
            null, TaskState.Idle, TaskState.Cancelled -> Unit
        }
        if (t.files.isNotEmpty()) putJsonArray("files") {
            t.files.forEach { file -> add(buildJsonObject {
                put("id", file.id); put("name", file.name); put("size", file.size)
                file.sha256?.let { put("sha256", it) }
            }) }
        }
        put("answerable", s is TaskState.WaitingInput)
        if (t.steers.isNotEmpty()) putJsonArray("steers") {
            t.steers.forEach { add(buildJsonObject { put("message_id", it.messageId); put("state", it.state) }) }
        }
    }
}
