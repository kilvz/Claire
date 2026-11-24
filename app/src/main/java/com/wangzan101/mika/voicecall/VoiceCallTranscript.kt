package com.wangzan101.mika.voicecall

import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text aggregation for one voice call: each complete stretch of user speech opens a turn ([onUser]), and everything the assistant says between two
 * user utterances is merged into that turn's answer ([onAssistant]); it writes back to the originating conversation through the same storage entry as chat.
 * With [writeScope] it saves as it goes: when the user starts the next sentence the previous turn is complete, so it is written into the conversation immediately, and an app kill mid-call loses only the turn being spoken;
 * the conversation's end ([close]) fills in anything not written yet. Without it, everything is written at the end in one go.
 *
 * Assembly: the user/assistant transcript callbacks from the session orchestration (finals only) are fed in; [conversationId] is the conversation that started this call
 * (null when opened straight from the capsule with no originating conversation, in which case nothing is written back); [appendTurn] hooks the conversation store's per-turn append.
 *
 * Call record: on connect ([onConnected]) a [ConversationCall] is recorded for the conversation, and on end ([close]) the hangup time, reason, and whether anything was said are filled in;
 * every turn written into the conversation carries [callId], which the chat page uses to draw the call's start and end lines. Calls that never connected are not recorded.
 */
class VoiceCallTranscript(
    private val conversationId: () -> String?,
    private val appendTurn: suspend (conversationId: String, turn: ChatTurn) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** The scope used for save-as-you-go (it must outlive the call; the assembly side supplies an app-level one); null means write only on [close]. */
    private val writeScope: CoroutineScope? = null,
    /** This call's id, recorded in the call record and on every turn. */
    val callId: String = java.util.UUID.randomUUID().toString(),
    /** Write a call record (one with the same id replaces the old one). */
    private val recordCall: suspend (conversationId: String, call: ConversationCall) -> Unit = { _, _ -> },
) {
    /** One question-and-answer turn: what the user asked, what the assistant answered (segments joined by line), when it was asked (ms), and the ids of tasks the front desk dispatched in this turn. */
    data class Turn(val question: String, val answer: String, val at: Long, val tasks: List<String> = emptyList())

    private val lock = Any()
    private val done = mutableListOf<Turn>()
    private var open: Turn? = null
    private var closed = false

    /** Completed turns handed to the writer coroutine (their index in [done]); the writer writes them one by one in order. */
    private val queue = Channel<Pair<String, Int>>(Channel.UNLIMITED)
    private var writer: Job? = null

    /** The first this many entries of [done] have been written (or skipped as not worth writing); both the writer coroutine and [close] only move forward. */
    @Volatile private var written = 0

    /** Connect time; null when never connected. */
    private var connectedAt: Long? = null
    private var startWrite: Job? = null

    /** Connected: record the connect time (a reconnect does not change it) and record an unfinished call for the conversation. */
    fun onConnected(at: Long) {
        val record = synchronized(lock) {
            if (closed || connectedAt != null) return
            connectedAt = at
            ConversationCall(callId, at)
        }
        val id = conversationId() ?: return
        val scope = writeScope ?: return
        startWrite = scope.launch { withContext(NonCancellable) { recordCall(id, record) } }
    }

    /** The user said one complete utterance: close the previous turn and open a new one. Blank input is ignored. */
    fun onUser(text: String) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (closed) return
            open?.let {
                done += it
                handOver(done.lastIndex)
            }
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

    /** A task was dispatched by the front desk this turn: record it on the turn so it can reattach when the conversation is reopened after the write-back. Ignored when there is no current turn. */
    fun onTask(taskId: String) {
        synchronized(lock) {
            if (closed) return
            val current = open ?: return
            if (taskId !in current.tasks) open = current.copy(tasks = current.tasks + taskId)
        }
    }

    /** Hand a finished turn to the writer coroutine (called under the lock); without a write scope or an originating conversation it is kept until [close]. */
    private fun handOver(index: Int) {
        val scope = writeScope ?: return
        val id = conversationId() ?: return
        if (writer == null) {
            writer = scope.launch {
                for ((conversation, i) in queue) {
                    val turn = synchronized(lock) { done[i] }
                    if (worthWriting(turn)) withContext(NonCancellable) { appendTurn(conversation, chatTurn(turn)) }
                    written = i + 1
                }
            }
        }
        queue.trySend(id to index)
    }

    /** All turns up to now, including the one still waiting for an answer. */
    fun turns(): List<Turn> = synchronized(lock) { done + listOfNotNull(open) }

    /**
     * Write back at the end of the conversation: append every turn that has both a question and an answer to the originating conversation, exactly once --
     * repeated closes write nothing more; the wind-up still finishes if the call's coroutine scope is cancelled (NonCancellable),
     * so the assembly side can call it directly in the call's finally block. Empty-question turns (only the assistant spoke, such as a bare greeting) are not written;
     * turns with a question but no answer and no dispatched task (hung up before the reply arrived) are not written either -- a chat bubble with an empty outcome is meaningless;
     * turns with a dispatched task are written regardless, carrying the task ids.
     */
    suspend fun close(endReason: String = ConversationCall.END_HANGUP) {
        val (writing, id) = synchronized(lock) {
            if (closed) return
            closed = true
            queue.close()
            writer to conversationId()
        }
        val endedAt = nowMillis()
        withContext(NonCancellable) {
            // First wait for the writer coroutine to finish what it was given (harmless if it was cancelled: anything unwritten is written below), then fill in the rest
            writing?.join()
            if (id == null) return@withContext
            turns().drop(written).filter(::worthWriting).forEach { appendTurn(id, chatTurn(it)) }
            // Record only if it connected: first wait for the connect record to be written, then fill in the end
            val start = connectedAt ?: return@withContext
            startWrite?.join()
            recordCall(id, ConversationCall(callId, start, endedAt, endReason, spoke = turns().any(::worthWriting)))
        }
    }

    private fun worthWriting(turn: Turn) = turn.question.isNotBlank() && (turn.answer.isNotBlank() || turn.tasks.isNotEmpty())

    private fun chatTurn(turn: Turn) =
        ChatTurn(userText = turn.question, outcome = turn.answer, status = DeskTurn.STATUS, atMs = turn.at, deskTasks = turn.tasks, callId = callId)
}
