package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Text aggregation for one voice call: each complete utterance from the user opens a turn ([onUser]); everything the assistant says between two user utterances
 * is merged into the answer of the current turn ([onAssistant]); when the call ends the whole Q&A is written back to the originating conversation in a batch ([close]),
 * through the same storage entry as the chat.
 *
 * Assembly: the call orchestrator's user/assistant transcript callbacks (final only) feed it; [conversationId] is the conversation that
 * started this call (null when opened directly from the capsule with no originating conversation, in which case nothing is written back); [appendTurn] is the conversation store's per-turn writer.
 */
class VoiceCallTranscript(
    private val conversationId: () -> String?,
    private val appendTurn: suspend (conversationId: String, turn: ChatTurn) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** One Q&A turn: what the user asked, what the assistant answered (multiple segments joined by lines), and when it was asked (milliseconds). */
    data class Turn(val question: String, val answer: String, val at: Long)

    private val lock = Any()
    private val done = mutableListOf<Turn>()
    private var open: Turn? = null
    private var closed = false

    /** The user said one complete utterance: close the previous turn and open a new one. Blank input is ignored. */
    fun onUser(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            open?.let { done += it }
            open = Turn(text.trim(), "", nowMillis())
        }
    }

    /** The assistant said a segment: merge it into the current turn's answer; if the user has not spoken yet (e.g. a greeting) it accumulates into an empty-question turn. Blank input is ignored. */
    fun onAssistant(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            val current = open
            open = if (current == null) Turn("", text.trim(), nowMillis())
            else current.copy(answer = if (current.answer.isEmpty()) text.trim() else current.answer + "\n" + text.trim())
        }
    }

    /** All turns as of now, including the turn still waiting for its answer. */
    fun turns(): List<Turn> = synchronized(lock) { done + listOfNotNull(open) }

    /**
     * Write back when the call ends: write the turns that have both a question and an answer into the originating conversation one by one, exactly once —
     * a repeated close does not write again; the write-back still completes when the call's coroutine scope is cancelled (NonCancellable),
     * so the assembly side can simply call it in the call's final finally block. Empty-question turns (only the assistant spoke, e.g. a pure greeting) are not written;
     * turns with a question but no answer (hung up before the answer arrived) are not written either — a chat bubble with an empty outcome is meaningless.
     */
    suspend fun close() {
        val (snapshot, id) = synchronized(lock) {
            if (closed) return
            closed = true
            turns() to conversationId()
        }
        if (id == null) return
        withContext(NonCancellable) {
            snapshot.filter { it.question.isNotBlank() && it.answer.isNotBlank() }.forEach { turn ->
                appendTurn(id, ChatTurn(userText = turn.question, outcome = turn.answer, status = "succeeded", atMs = turn.at))
            }
        }
    }
}
