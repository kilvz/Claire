package com.wangzan101.mika.frontdesk

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.ActionKind
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MarkdownModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** A front desk notification to send: tapping it opens [conversationId]; when [needsYou] is true it uses the "needs your attention" channel. */
data class DeskNotice(val conversationId: String, val title: String, val text: String, val needsYou: Boolean)

/**
 * Front desk fallback when no entry point is online: endings are written back to the conversation as a front desk line
 * (`status = desk_report`); whether an additional notification is sent is decided by [ReportPolicy.notifies] (send for
 * endings and unmet prerequisites; no send for start, queue, progress; answer/confirm/takeover requests are already
 * reminded by the task's own notification with buttons, so nothing is sent here).
 * Which lines are written: only endings — success writes the full result text from the backend, failure writes a short
 * "this one: didn't work…" line. Start, queue, progress, and needs-your-attention write no line (the chat page's progress
 * bar and question cards rely on the task's live state, not on lines in the conversation). When the run the front desk is
 * following ends, the task no longer posts its own "task complete" ([DeskRegistry.reportsEnding]), so a finished task
 * produces exactly this one line.
 * This path is only reached when no entry point is attached to the conversation (the user is not watching it), so it
 * always sends, whether or not Claire is in the foreground.
 */
class DeskNotifier(
    private val conversations: ConversationStore,
    private val notify: (DeskNotice) -> Unit,
) : DeskSink {
    override suspend fun record(conversationId: String, report: DeskReport) {
        val event = report.event
        if (event is DeskEvent.Queued) return
        val line = line(event)
        val outcome = rowText(report)
        if (outcome != null) withContext(Dispatchers.IO) {
            conversations.append(conversationId, Turn(userText = "", outcome = outcome, status = STATUS,
                runId = DeskTurn.RUN_PREFIX + UUID.randomUUID(), deskTasks = listOf(event.taskId)))
        }
        // Which ones notify is decided by ReportPolicy
        if (!ReportPolicy.notifies(event)) return
        val needsYou = event is DeskEvent.NeedsAnswer || event is DeskEvent.NeedsAction
        // Notifications do not render Markdown: strip the markers from the body; the line written back to the conversation keeps the original text, and the chat page renders it as usual
        val text = MarkdownModel.toPlainText(line).ifBlank { line }
        notify(DeskNotice(conversationId, report.title, text, needsYou))
    }

    companion object {
        /** Status of the line the front desk fallback writes back to the conversation. */
        const val STATUS = DeskTurn.REPORT_STATUS
        private const val TAG = "MikaDesk"

        /**
         * The line an ending report writes back to the conversation: on success it is the full result text (falling
         * back to "this one: done" when the result is empty); on failure or cancellation it is a short "this one: …"
         * line; start, queue, progress, and needs-your-attention write no line and return null.
         */
        fun rowText(report: DeskReport): String? = when (val event = report.event) {
            is DeskEvent.Succeeded -> event.answer.trim().ifBlank { tr(R.string.desk_report_line, report.title, line(event)) }
            is DeskEvent.Failed, is DeskEvent.Cancelled -> tr(R.string.desk_report_line, report.title, line(event))
            else -> null
        }

        /** One line for this change to a task (without the task name), in the UI language. */
        fun line(event: DeskEvent): String = when (event) {
            is DeskEvent.Started -> tr(R.string.desk_report_started)
            is DeskEvent.Queued -> trPlural(R.plurals.queue_ahead, event.position)
            is DeskEvent.Progress -> event.line
            is DeskEvent.NeedsAnswer -> tr(R.string.desk_report_needs_answer, event.prompt)
            is DeskEvent.NeedsAction -> tr(when (event.kind) {
                ActionKind.Confirm -> R.string.desk_report_needs_confirm
                ActionKind.Takeover -> R.string.desk_report_needs_takeover
                ActionKind.Prerequisite -> R.string.desk_report_needs_prerequisite
            })
            is DeskEvent.Succeeded -> tr(R.string.desk_report_succeeded, event.answer)
            is DeskEvent.Failed -> tr(R.string.desk_report_failed, event.reason)
            is DeskEvent.Cancelled -> tr(R.string.desk_report_cancelled)
        }

        /** Send on the task notification channel: needs-your-attention goes to "needs your attention", the rest to the result channel; tapping opens that conversation. */
        fun post(context: Context, notice: DeskNotice) {
            try {
                TaskService.ensureChannels(context)
                val channel = if (notice.needsYou) TaskService.CHANNEL_ATTENTION else TaskService.CHANNEL_RESULT
                context.getSystemService(NotificationManager::class.java).notify(TaskService.nextResultId(),
                    Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(notice.title)
                        .setContentText(notice.text)
                        .setStyle(Notification.BigTextStyle().bigText(notice.text))
                        .setContentIntent(TaskService.openApp(context, notice.conversationId))
                        .setAutoCancel(true)
                        .build())
            } catch (e: Exception) {
                // If the notification cannot be posted (no notification permission, etc.), the report has already been written back to the conversation, so it is still visible when opened.
                Log.w(TAG, "could not post desk notice", e)
            }
        }
    }
}
