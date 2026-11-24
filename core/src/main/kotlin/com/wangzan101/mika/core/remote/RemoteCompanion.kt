package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.agent.TaskState

/**
 * The read-only entry points the web console needs. Kept separate from [RemoteTasks]: the task entry points only accept tasks handed over by a
 * computer, whereas here you see the phone's conversations and currently running tasks, read by the app side from the existing conversation storage
 * and task centre without keeping a second copy.
 */
interface RemoteCompanion {
    /** Most recent conversations first; conversations with no content are not listed. */
    fun conversations(limit: Int): List<RemoteConversation>

    /** The turns of one conversation; returns null when the conversation does not exist. When [from] is less than 0, the last segment is taken. */
    fun conversation(id: String, from: Int, limit: Int): RemoteConversationPage?

    /** Tasks that are currently running, queued or waiting for the user, including those started on the phone. */
    fun running(): List<RemoteRunning>

    companion object {
        const val DEFAULT_LIST = 20
        const val MAX_LIST = 50
        const val DEFAULT_PAGE = 8
        const val MAX_PAGE = 10
        /** A turn page's from uses this to mean "take the last segment". */
        const val TAIL = -1
        // At most 10 turns per page, with each turn's fields capped at about 35 KB in total (estimated with CJK at 3 bytes per UTF-16 unit),
        // so the whole page stays within the relay's 1 MiB single frame; truncated fields are marked by truncated.
        const val MAX_USER_TEXT = 2_000
        const val MAX_OUTCOME = 4_000
        const val MAX_NOTE = 500
        const val MAX_STEER = 500
        const val MAX_STEERS = 10
        const val MAX_TITLE = 200
        const val MAX_GOAL = 1_000
        const val MAX_PROGRESS = 500
        const val MAX_RUNNING = 20
    }
}

data class RemoteConversation(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val pinnedAtMs: Long? = null,
    val turnCount: Int = 0,
)

data class RemoteConversationPage(
    val id: String,
    val title: String,
    val total: Int,
    val from: Int,
    val turns: List<RemoteTurn>,
)

/** One conversation turn; overly long fields are truncated and marked by [truncated]. */
data class RemoteTurn(
    val index: Int,
    val userText: String,
    val outcome: String,
    val status: String,
    val note: String? = null,
    val runId: String? = null,
    val steers: List<String> = emptyList(),
    val truncated: Boolean = false,
)

/** A running task, with status following [RemoteStatus]'s vocabulary. */
data class RemoteRunning(
    val conversationId: String,
    val goal: String,
    val status: String,
    val runId: String? = null,
    val step: Int? = null,
    val progress: String? = null,
    val question: String? = null,
    val options: List<String> = emptyList(),
    val needsYou: Boolean = false,
    val truncated: Boolean = false,
)

/** Guards surrogate pairs when truncating text (an emoji is never cut in half); if something was cut, an ellipsis is appended at the end. */
object RemoteText {
    data class Clipped(val text: String, val truncated: Boolean)

    fun clip(text: String, max: Int): Clipped {
        if (text.length <= max) return Clipped(text, false)
        var end = max.coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end -= 1
        return Clipped(text.substring(0, end) + "…", true)
    }
}

/**
 * Translates the phone's task state into the console's vocabulary. A pure function, kept in core: the app only supplies the conversation ID, goal
 * and current state, and how the state is read and how much is truncated lives in exactly one place, so the web side and any future clients do
 * not each say something different.
 */
object RemoteRunningView {
    fun of(conversationId: String, goal: String, state: TaskState, runId: String? = null): RemoteRunning {
        val status = RemoteStatus.of(state)
        var step: Int? = null
        var progress: String? = null
        var question: String? = null
        var options: List<String> = emptyList()
        var needsYou = false
        when (state) {
            is TaskState.Running -> {
                step = state.step
                progress = state.narration ?: state.reason ?: state.note
            }
            is TaskState.Held -> {
                progress = state.reason.why.ifBlank { state.reason.code }
                needsYou = state.reason.needsYou
            }
            is TaskState.WaitingInput -> {
                question = state.prompt
                options = state.options.orEmpty()
                needsYou = true
            }
            is TaskState.WaitingConfirmation -> {
                progress = state.request.app
                needsYou = true
            }
            is TaskState.WaitingTakeover -> {
                progress = state.message.ifBlank { state.reason }
                needsYou = true
            }
            TaskState.Idle -> Unit
            is TaskState.Terminal -> Unit
        }
        val shownGoal = RemoteText.clip(goal, RemoteCompanion.MAX_GOAL)
        val shownProgress = progress?.let { RemoteText.clip(it, RemoteCompanion.MAX_PROGRESS) }
        val shownQuestion = question?.let { RemoteText.clip(it, RemoteCompanion.MAX_PROGRESS) }
        return RemoteRunning(
            conversationId = conversationId,
            goal = shownGoal.text,
            status = status,
            runId = runId,
            step = step,
            progress = shownProgress?.text,
            question = shownQuestion?.text,
            options = options.take(RemoteCompanion.MAX_STEERS),
            needsYou = needsYou,
            truncated = shownGoal.truncated || shownProgress?.truncated == true || shownQuestion?.truncated == true,
        )
    }
}
