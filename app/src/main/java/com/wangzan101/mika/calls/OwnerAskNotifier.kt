package com.wangzan101.mika.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity

/**
 * Asking the owner during a call: the question is registered in [questions] (written into the conversation [conversationId] the call belongs to),
 * and a notification with direct reply (RemoteInput, standard API) is posted on the question channel ([CallNotifications.OWNER_ASK_CHANNEL], high importance, pops up).
 * The owner can answer in the conversation or from the notification; the first answer wins and the other side is immediately invalidated (the notification is withdrawn).
 * If the channel is turned off: the question still goes into the conversation (with a line reminding the owner to turn the channel on), and the call tells the other party it will follow up; only when there is not even a conversation is it considered undelivered.
 */
internal class OwnerAskNotifier(
    private val context: Context,
    private val questions: OwnerQuestions,
    private val conversationId: String?,
) : OwnerAsk {
    private var token: String? = null

    override fun ask(question: String, onReply: (String) -> Unit): OwnerReach {
        // Asked another question: quietly replace the old one (the new question's line is written into the conversation)
        token?.let {
            questions.cancel(it)
            dismiss(context)
        }
        token = null
        val ready = CallNotifications(context).ownerAskReady()
        val handle = questions.open(conversationId, question, notified = ready) { reply ->
            dismiss(context)
            onReply(reply)
        }
        token = handle.token
        if (ready && post(context, handle.token, question, conversationId)) return OwnerReach.Notified
        if (conversationId != null) return OwnerReach.InConversation
        cancel()
        return OwnerReach.Unreachable
    }

    /** Call ended: withdraw any unanswered question, add a "withdrawn" line in the conversation, and withdraw the notification too. */
    override fun cancel() {
        val id = token ?: return
        token = null
        questions.withdraw(id)
        dismiss(context)
    }

    companion object {
        const val KEY_REPLY = "owner_reply"
        const val EXTRA_TOKEN = "owner_ask_token"
        const val EXTRA_CONVERSATION = "owner_ask_conversation"
        const val ACTION_REPLY = "com.wangzan101.mika.calls.OWNER_REPLY"
        private const val NOTIFICATION_ID = 90_118

        /** A reply came from the notification: hand it to the asker if the token matches; an empty reply does not count, so re-post the same notification so the owner can fill it in again (the system waits for this notification to update, otherwise the reply box spins forever). */
        fun deliver(context: Context, questions: OwnerQuestions, token: String?, reply: String?, conversationId: String?) {
            if (token == null) return
            if (questions.answer(token, reply.orEmpty())) return
            questions.question(token)?.let { post(context, token, it, conversationId) }
        }

        private fun dismiss(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.let { runCatching { it.cancel(NOTIFICATION_ID) } }
        }

        private fun post(context: Context, token: String, question: String, conversationId: String?): Boolean = runCatching {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.calls_owner_ask_hint)).build()
            // RemoteInput puts the reply into the Intent, so the PendingIntent must be mutable; the Intent names the receiver and is not exported
            val replyIntent = Intent(context, OwnerReplyReceiver::class.java).setAction(ACTION_REPLY)
                .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_CONVERSATION, conversationId)
            val reply = PendingIntent.getBroadcast(
                context, NOTIFICATION_ID, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val action = Notification.Action.Builder(null, context.getString(R.string.calls_owner_ask_reply), reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            // Can be answered directly from the lock screen; the other party is still waiting on the line
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
            // Tapping: open the conversation if there is one (it can also be answered there), otherwise open the AI call page
            val open = if (conversationId != null) TaskService.openApp(context, conversationId)
            else PendingIntent.getActivity(context, NOTIFICATION_ID, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_CALLS, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(context, CallNotifications.OWNER_ASK_CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(context.getString(R.string.calls_owner_ask_title))
                .setContentText(question)
                .setStyle(Notification.BigTextStyle().bigText(question))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(action.build())
                .build()
            val nm = context.getSystemService(NotificationManager::class.java) ?: return false
            nm.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }
}
