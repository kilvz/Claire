package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState

/**
 * Which task events the front desk says on its own: start, queueing, asking the user to answer or act, and ending are always said (a task dispatched and started immediately does not report "started"; [FrontDesk] filters that out); a cancellation by the user is not;
 * progress is not said within [progressQuietMs] after the same task's last report, to avoid nagging; when the user asks about progress the front desk answers from the task board, not through this gate.
 * [shouldReport] returning true counts as reported and restarts the timer.
 */
class ReportPolicy(private val clock: () -> Long, private val progressQuietMs: Long = 90_000) {
    private val lastReportAt = HashMap<String, Long>()

    @Synchronized
    fun shouldReport(event: DeskEvent): Boolean {
        val report = when (event) {
            // Same as saysEnding: a cancellation by the user is not said
            is DeskEvent.Cancelled -> false
            is DeskEvent.Progress -> quietPassed(event.taskId)
            else -> true
        }
        when (event) {
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> forget(event.taskId)
            else -> if (report) lastReportAt[event.taskId] = clock()
        }
        return report
    }

    private fun quietPassed(taskId: String): Boolean {
        val last = lastReportAt[taskId] ?: return true
        return clock() - last >= progressQuietMs
    }

    /** Finished tasks never report progress again, so their record is cleared. */
    private fun forget(taskId: String) {
        lastReportAt -= taskId
    }

    companion object {
        /**
         * When no entry point is attached (nobody is watching this conversation) and reports can only use the fallback, whether this one becomes a notification: ended tasks and unsatisfied prerequisites are sent;
         * start, queueing and progress are not, to avoid disturbing the user (the fallback decides which lines to write into the conversation). Needs-answer, confirm and takeover also only write back: the task itself posts the needs-you notification
         * with a reply box and confirm/takeover buttons, so a front-desk notification would duplicate it; a prerequisite task posts no reminder of its own, so the front desk sends it.
         */
        fun notifies(event: DeskEvent): Boolean = when (event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress -> false
            is DeskEvent.NeedsAnswer -> false
            is DeskEvent.NeedsAction -> event.kind == ActionKind.Prerequisite
            is DeskEvent.Succeeded, is DeskEvent.Failed, is DeskEvent.Cancelled -> true
        }

        /** Whether the front desk will say this ending when a followed task ends with [end]: not for a user cancellation, yes otherwise (same as [shouldReport]). */
        fun saysEnding(end: TaskState.Terminal): Boolean = end != TaskState.Cancelled
    }
}
