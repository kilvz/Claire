package com.wangzan101.mika.proactive

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord

/** A few fields taken verbatim from a system notification (none cleaned yet); [RawNotice.toRecord] is pure and can be tested on a desktop. */
data class RawNotice(
    val key: String,
    val packageName: String,
    /** The posting time recorded by the system (StatusBarNotification.postTime), not the App-supplied `when`. */
    val postTime: Long,
    val title: String?,
    val bigText: String?,
    val text: String?,
    val messages: List<NoticeMessage>,
    val messagingStyle: Boolean,
    val flags: Int,
    val ongoing: Boolean,
    val clearable: Boolean,
    val category: String?,
    /** The conversation name (group name) the system provides separately in the messaging style; null when absent. */
    val conversationTitle: String? = null,
    /** Whether the system says "this is a group chat" in the messaging style; null when it does not say. */
    val groupConversation: Boolean? = null,
    /** The importance the system assigned to this notification (NotificationManager.IMPORTANCE_*); null when unavailable. */
    val importance: Int? = null,
) {
    /** Text prefers the expanded long text and falls back to the single-line text; a messaging style or a message list makes it a conversation. */
    fun toRecord(): NoticeRecord = NoticeRecord(
        key = key,
        packageName = packageName,
        postedAtMs = postTime,
        title = title.orEmpty(),
        text = bigText?.takeIf { it.isNotBlank() } ?: text.orEmpty(),
        messages = messages,
        conversation = messagingStyle || messages.isNotEmpty(),
        ongoing = ongoing || flags and Notification.FLAG_ONGOING_EVENT != 0,
        groupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
        foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        clearable = clearable,
        category = category,
        // Only trust these two with a messaging style: in other notifications these keys have no agreed meaning
        group = groupConversation.takeIf { messagingStyle || messages.isNotEmpty() },
        conversationTitle = conversationTitle?.takeIf { messagingStyle || messages.isNotEmpty() },
        // Below "default" (low, min) is silent; when the importance is unavailable, treat it as audible
        silent = importance != null && importance < IMPORTANCE_DEFAULT,
    )

    private companion object {
        /** NotificationManager.IMPORTANCE_DEFAULT */
        const val IMPORTANCE_DEFAULT = 3
    }
}

/**
 * StatusBarNotification → [RawNotice]. A notification's extras are stuffed in by other Apps, so reading them can throw anything: the caller
 * ([read]) catches it all and treats an unreadable one as if this notification did not exist.
 */
object NoticeMapper {
    fun read(sbn: StatusBarNotification): RawNotice? = runCatching { readUnsafe(sbn) }.getOrNull()

    private fun readUnsafe(sbn: StatusBarNotification): RawNotice {
        val n = sbn.notification
        val extras = n.extras ?: Bundle.EMPTY
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val messages = style?.messages?.map { m ->
            NoticeMessage(m.person?.name?.toString(), m.text?.toString().orEmpty(), m.timestamp)
        } ?: legacyMessages(extras)
        return RawNotice(
            key = sbn.key,
            packageName = sbn.packageName,
            postTime = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            messages = messages,
            messagingStyle = style != null,
            flags = n.flags,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            category = n.category,
            conversationTitle = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString(),
            groupConversation = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
                else style?.isGroupConversation?.takeIf { it },
        )
    }

    /** When the messaging style cannot be extracted, read each message from EXTRA_MESSAGES directly (key names match the system's Notification.MessagingStyle.Message). */
    @Suppress("DEPRECATION")
    private fun legacyMessages(extras: Bundle): List<NoticeMessage> =
        runCatching { extras.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull().orEmpty().mapNotNull { p ->
            val b = p as? Bundle ?: return@mapNotNull null
            val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
            NoticeMessage(b.getCharSequence("sender")?.toString(), text, b.getLong("time"))
        }
}
