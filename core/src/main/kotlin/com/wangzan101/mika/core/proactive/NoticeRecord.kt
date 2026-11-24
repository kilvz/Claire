package com.wangzan101.mika.core.proactive

/**
 * The local record of one notification. All text comes from the app that posted it (anyone can send the user messages) and is treated as data only:
 * links are not resolved, it is never taken as instructions, and formatting is not rendered. It is cleaned through [NoticeFilter.sanitize] before entering [NoticeInbox].
 *
 * [key] is the notification key the system assigned (it carries the tag the app chose and may contain any characters); [postedAtMs] is the post time recorded by the system,
 * not a display time filled in by the app. The fields after that are the notification traits used for filtering.
 */
data class NoticeRecord(
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val messages: List<NoticeMessage> = emptyList(),
    /** A notification with a conversation style (MessagingStyle). */
    val conversation: Boolean = false,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val foregroundService: Boolean = false,
    val clearable: Boolean = true,
    val category: String? = null,
    /**
     * Who said this: present only when the notification itself writes "who" in the body and it differs from the conversation's name (someone in a group).
     * Split out by [NoticeShapes] according to how the app writes it; null when it cannot be split out, for private chats and for notifications that are not chats. For conversation-style notifications: when there is a conversation name,
     * this is the speaker the system put in the title (that of the most recently received message); the sender of each individual message is in [messages].
     */
    val sender: String? = null,
    /** Whether this is a group chat (true) or a private chat with one person (false); null when it cannot be told or it is not a chat. Also decided by [NoticeShapes]. */
    val group: Boolean? = null,
    /**
     * The conversation name (group name) the system separately supplies for conversation-style notifications. When present, the system writes [title] as "conversation name: speaker" — the speaker changes with every message
     * and cannot be used to identify the conversation. [NoticeShapes] replaces [title] with this; null when there is no conversation style or for private chats.
     */
    val conversationTitle: String? = null,
    /**
     * A conversation-style group chat where the system supplied no conversation name: the system then writes [title] as the speaker of the newest message, not the conversation's name.
     * Identifying conversations that way would make each person in the group a separate "conversation" and would merge them with a private chat of the same name; so contacts are not learned from such notifications (see [ContactPool.apply]).
     */
    val titleIsSpeaker: Boolean = false,
    /** The system says this notification makes no sound and does not pop up (importance below "default"); when unknown it is treated as audible. See [LiveUpdates]. */
    val silent: Boolean = false,
) {
    /** The one line shown to people and models: for groups it carries who said it ("Zhang San: see you tomorrow"), otherwise it is just the body. */
    val said: String get() = if (sender.isNullOrBlank()) text else "$sender: $text"
}

/** One message in a conversation style; [sender] may be absent (sent by the user, or not supplied by the app). */
data class NoticeMessage(val sender: String?, val text: String, val atMs: Long)
