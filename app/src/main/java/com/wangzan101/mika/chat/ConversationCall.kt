package com.wangzan101.mika.chat

import kotlinx.serialization.Serializable

/**
 * A voice call within a conversation: when it connected, when and why it hung up, and whether anything was said.
 * Every turn spoken during the call is stored in the conversation as usual ([com.wangzan101.mika.core.agent.Turn.callId] points back to the [id] here),
 * but this record is only for the UI: the chat page draws "Voice call · 9:41" and "Call ended · 3:12" around those turns,
 * and the "Call history" entry in the top-right corner of the call page lists them. A call with nothing said is still recorded, drawn as a single line in the chat page.
 *
 * [endMs] is null when the call is still in progress, or when the app was killed mid-call before the hangup could be recorded.
 * [endReason] is a wire value such as [END_IDLE]; anything unrecognized is treated as a normal hangup.
 */
@Serializable
data class ConversationCall(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val endReason: String? = null,
    /** Whether any turn from this call was stored in the conversation; if not, the chat page draws just a single "No speech" line. */
    val spoke: Boolean = false,
) {
    val durationMs: Long? get() = endMs?.let { (it - startMs).coerceAtLeast(0) }

    companion object {
        const val END_HANGUP = "hangup"
        const val END_IDLE = "idle"
    }
}

/** Records are ordered by connect time; a new record with the same id replaces the old one (one written on connect, completed with the end on hangup). */
internal fun List<ConversationCall>.upsert(call: ConversationCall): List<ConversationCall> =
    (filterNot { it.id == call.id } + call).sortedBy { it.startMs }
