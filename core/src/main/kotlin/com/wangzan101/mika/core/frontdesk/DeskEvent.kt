package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState

/** One change to a background task that the front desk should know about; [taskId] is the task's id. */
sealed interface DeskEvent {
    val taskId: String

    /** It started (including coming off the queue). */
    data class Started(override val taskId: String) : DeskEvent
    /** Queued behind others; [position] starts at 1 and is the number of tasks ahead. */
    data class Queued(override val taskId: String, val position: Int) : DeskEvent
    /** One line on what it is doing, with details stripped and clipped. */
    data class Progress(override val taskId: String, val line: String) : DeskEvent
    /** The task is asking the user a question; [options] lists the choices when non-empty. */
    data class NeedsAnswer(override val taskId: String, val prompt: String, val options: List<String>?) : DeskEvent
    /** The user must act on the phone (confirm, take over, satisfy a prerequisite). */
    data class NeedsAction(override val taskId: String, val kind: ActionKind) : DeskEvent
    /** The background finished and gave an answer; [answer] is the full text (clipped to [DESK_RESULT_MAX_CHARS] when given to the model or read aloud). */
    data class Succeeded(override val taskId: String, val answer: String) : DeskEvent
    data class Failed(override val taskId: String, val reason: String) : DeskEvent
    data class Cancelled(override val taskId: String) : DeskEvent
}

/** Kinds of action the user must take: approve an irreversible action, take over the phone, satisfy a prerequisite. */
enum class ActionKind { Confirm, Takeover, Prerequisite }

object DeskEvents {
    /**
     * Classify a change from [previous] to [next] as one front-desk event; null when nothing changed or the change need not concern the front desk.
     * [previous] null means the task is seen for the first time. Queueing is not inferred from state: only the dispatcher knows the position, so it supplies [DeskEvent.Queued].
     */
    fun classify(taskId: String, previous: TaskState?, next: TaskState): DeskEvent? {
        if (previous == next) return null
        return when (next) {
            TaskState.Idle -> null
            is TaskState.Running -> when {
                previous == null || previous == TaskState.Idle || (previous is TaskState.Held && previous.reason.queued) ->
                    DeskEvent.Started(taskId)
                previous is TaskState.Running -> progressLine(previous.narration, next.narration)?.let { DeskEvent.Progress(taskId, it) }
                else -> null
            }
            is TaskState.WaitingInput -> DeskEvent.NeedsAnswer(taskId, next.prompt, next.options)
            is TaskState.WaitingConfirmation -> DeskEvent.NeedsAction(taskId, ActionKind.Confirm)
            is TaskState.WaitingTakeover -> DeskEvent.NeedsAction(taskId, ActionKind.Takeover)
            is TaskState.Held -> if (next.reason.needsYou) DeskEvent.NeedsAction(taskId, ActionKind.Prerequisite) else null
            is TaskState.Succeeded -> DeskEvent.Succeeded(taskId, next.answer)
            is TaskState.Failed -> DeskEvent.Failed(taskId, next.reason)
            TaskState.Cancelled -> DeskEvent.Cancelled(taskId)
        }
    }

    /** A progress line counts as new only when it has text after cleaning and differs from the previous cleaned line (changes only in coordinates or quoted details do not count). */
    private fun progressLine(before: String?, after: String?): String? {
        val line = deskLine(after) ?: return null
        return line.takeIf { it != deskLine(before) }
    }
}
