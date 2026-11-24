package com.wangzan101.mika.voicecall

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update

/** Upper bound of the call panel's status line buffer: at most this many progress lines are kept, older ones scroll off. */
const val VOICE_LINE_MAX_COUNT = 12

/** A status line is at most this many characters, longer ones are truncated; the panel must be readable at a glance, and details should not surface there anyway. */
const val VOICE_LINE_MAX_CHARS = 40

/** Upper bound of the result text sent back to the real-time model: protocol frames impose a total length limit on tool results, leaving headroom for the outer wrapper. */
const val VOICE_RESULT_MAX_CHARS = 1_800

/**
 * Task entry point for voice requests; at assembly time it is wired to the same task-start mechanism as the chat page (create a new conversation, then hand it to the task service),
 * returning the conversation ID of this task.
 */
fun interface VoiceCallTaskLauncher {
    fun launch(goal: String): String
}

/**
 * One-shot destination routing from voice requests to the existing task entry point. Only one request is served at a time (first come, first served,
 * later ones are rejected immediately and no duplicate task is started); the task's own state is subscribed by conversation ID via [runState]
 * (never the global state flow when tasks run in parallel); progress rolls into short status lines ([lines]); when the task stops to wait for the user
 * (confirmation/answer/takeover/a prerequisite requiring manual action) it prompts "go look at the task page" and hands control back to the model; when the user speaks to interrupt
 * ([onBargeIn]) it cancels tasks that were started but not finished (without claiming to undo operations already in effect); when the task reaches a terminal state the result
 * text is sent back to the real-time model, for the model to restate it once.
 */
class VoiceCallRoute(
    private val launcher: VoiceCallTaskLauncher,
    private val runState: (String) -> StateFlow<TaskState>?,
    private val sendResult: (callId: String, text: String) -> Unit,
    private val cancelTask: (conversationId: String) -> Unit = {},
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** The panel's scrolling status lines, at most [VOICE_LINE_MAX_COUNT], with consecutive duplicates kept only once. */
    val lines: StateFlow<List<String>> = _lines

    private val _busy = MutableStateFlow(false)

    /** Whether a request is currently executing (between claiming and reaching a terminal state / handing off); the call page uses this to switch the ball into task mode. */
    val busy: StateFlow<Boolean> = _busy

    private val lock = Any()
    private var claimed = false
    private var activeConversationId: String? = null
    private var bargeInGeneration = 0
    private var lastCancelledId: String? = null

    /**
     * Hand a voice request that passed the safety gate to the task system for execution, suspending until the task reaches a terminal state (or is handed off) and only then returning;
     * each accepted callId gets exactly one result. The assembly side launches this in the call's coroutine scope; do not await it directly in the event loop.
     */
    suspend fun execute(callId: String, text: String) {
        val claimGeneration = synchronized(lock) {
            if (claimed) null else {
                claimed = true
                bargeInGeneration
            }
        }
        if (claimGeneration == null) {
            sendResult(callId, RESULT_BUSY)
            return
        }
        _busy.value = true
        try {
            val conversationId = try {
                launcher.launch(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sendResult(callId, RESULT_START_FAILURE)
                return
            }
            synchronized(lock) { activeConversationId = conversationId }
            val flow = awaitRunState(conversationId)
            if (flow == null) {
                sendResult(callId, RESULT_START_FAILURE)
                return
            }
            // An interruption that happened after claiming but before the state flow was registered: cancel as soon as the task appears (onBargeIn already cancelled by conversation ID before registration; this is an idempotent fallback)
            if (synchronized(lock) { bargeInGeneration } != claimGeneration) {
                cancelOnce(conversationId)
            }
            var handedOff = false
            flow.takeWhile { state ->
                if (state is TaskState.Terminal) return@takeWhile false
                state.toVoiceLine()?.let(::appendLine)
                if (state.needsUserOnPhone()) {
                    handedOff = true
                    return@takeWhile false
                }
                true
            }.collect {}
            if (handedOff) {
                sendResult(callId, RESULT_NEEDS_USER)
                return
            }
            val end = flow.value
            end.toVoiceLine()?.let(::appendLine)
            when (end) {
                is TaskState.Succeeded -> sendResult(callId, end.answer.ifBlank { RESULT_DONE_FALLBACK }.capResult())
                is TaskState.Failed -> sendResult(callId, end.reason.ifBlank { RESULT_FAILED_FALLBACK }.capResult())
                TaskState.Cancelled -> sendResult(callId, RESULT_CANCELLED)
                else -> sendResult(callId, RESULT_START_FAILURE) // takeWhile only stops at a terminal state or a hand-off, so this is theoretically unreachable
            }
        } finally {
            synchronized(lock) {
                claimed = false
                activeConversationId = null
            }
            _busy.value = false
        }
    }

    /** User interrupts by speaking: cancel tasks that were started but have not finished running; does nothing when no task is running. */
    fun onBargeIn() {
        val id = synchronized(lock) {
            bargeInGeneration++
            activeConversationId
        } ?: return
        cancelOnce(id)
    }

    /** Cancel a given task only once: both the generation check before registration and onBargeIn may target it. */
    private fun cancelOnce(conversationId: String) {
        val shouldCancel = synchronized(lock) {
            if (lastCancelledId == conversationId) false else {
                lastCancelledId = conversationId
                true
            }
        }
        if (shouldCancel) cancelTask(conversationId)
    }

    private fun appendLine(line: String) {
        _lines.update { current ->
            if (current.lastOrNull() == line) current else (current + line).takeLast(VOICE_LINE_MAX_COUNT)
        }
    }

    /** The task service registers a run asynchronously: runState may not find it right after launch, so poll until it appears. */
    private suspend fun awaitRunState(conversationId: String): StateFlow<TaskState>? {
        var waited = 0L
        while (true) {
            runState(conversationId)?.let { return it }
            if (waited >= RUN_STATE_TIMEOUT_MS) return null
            delay(RUN_STATE_POLL_MS)
            waited += RUN_STATE_POLL_MS
        }
    }

    private fun String.capResult(): String = take(VOICE_RESULT_MAX_CHARS)

    companion object {
        private const val RUN_STATE_TIMEOUT_MS = 5_000L
        private const val RUN_STATE_POLL_MS = 100L

        // Fixed text sent back to the real-time model (model-side instruction style, in English to match the existing replies in the protocol layer; the task's own answer/reason is passed through as-is)
        const val RESULT_BUSY = "Another request is still running. Tell the user the previous request is still being handled; they can ask again when it finishes."
        const val RESULT_START_FAILURE = "The task could not be started. Apologize briefly and suggest the user start it from the task page instead."
        const val RESULT_NEEDS_USER = "The task started but needs the user to act on the phone (approve, answer, or take over). Tell the user to open the task page to continue."
        const val RESULT_CANCELLED = "The task was cancelled because the user interrupted."
        const val RESULT_DONE_FALLBACK = "The task finished."
        const val RESULT_FAILED_FALLBACK = "The task did not finish."
    }
}

/** States that need the user to do something on the phone: confirm, answer, take over, or a prerequisite wait marked with needsYou; queued-waiting-to-start does not count. */
private fun TaskState.needsUserOnPhone(): Boolean = when (this) {
    is TaskState.Held -> reason.needsYou
    is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover, is TaskState.WaitingInput -> true
    else -> false
}

/**
 * Task state → one short status line for the call panel (within [VOICE_LINE_MAX_CHARS] characters).
 * While running, use the model's progress narration, stripping coordinates (tap/swipe positions) and quoted titles (schedule names, contact names,
 * message contents and similar details do not belong on the call panel); if nothing is left after stripping, use a generic line. Idle has nothing to say, so return null.
 */
fun TaskState.toVoiceLine(): String? = when (this) {
    TaskState.Idle -> null
    is TaskState.Running -> voiceLineOf(narration ?: reason ?: note)
    is TaskState.Held -> if (reason.needsYou) tr(R.string.voicecall_see_task_page) else tr(R.string.voicecall_task_paused)
    is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover, is TaskState.WaitingInput ->
        tr(R.string.voicecall_see_task_page)
    is TaskState.Succeeded -> tr(R.string.voicecall_task_done)
    is TaskState.Failed -> tr(R.string.voicecall_task_failed)
    TaskState.Cancelled -> tr(R.string.voicecall_task_cancelled)
}

private fun voiceLineOf(raw: String): String {
    val cleaned = stripVoiceLineDetails(raw)
    return if (cleaned.isBlank()) tr(R.string.voicecall_task_running) else cleaned.take(VOICE_LINE_MAX_CHARS)
}

/** Coordinate shapes: the [x,y] and (x,y) forms common in UI trees and tap positions, plus their full-width variants; bounds are two adjacent pairs, stripped pair by pair. */
private val COORDINATE_PATTERN = Regex("""[\[\(（【]\s*\d+\s*[,，、]\s*\d+\s*[\]\)）】]""")

/** A quoted stretch: details such as schedule titles, contact names and message contents are usually quoted, so the whole stretch is stripped. 【】 is also stripped as a quoted stretch (the non-numeric form). */
private val QUOTED_PATTERN = Regex("""「[^」]*」|『[^』]*』|《[^》]*》|【[^】]*】|"[^"]*"|“[^”]*”|‘[^’]*’|'[^']*'""")

private val WHITESPACE_RUN = Regex("""\s+""")

/** Strip coordinates and quoted titles and other details, and collapse the whitespace left behind. */
fun stripVoiceLineDetails(text: String): String =
    WHITESPACE_RUN.replace(COORDINATE_PATTERN.replace(QUOTED_PATTERN.replace(text, ""), ""), " ").trim()
