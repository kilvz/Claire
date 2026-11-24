package com.wangzan101.mika.core.proactive

/**
 * Shapes a chat notification into the form everything downstream understands: [NoticeRecord.title] is the conversation's name (a person or a group), [NoticeRecord.sender] is the person speaking in a group,
 * [NoticeRecord.text] is just the sentence and [NoticeRecord.group] says whether it is a group. The unread count is not content and is stripped. Pure functions; run once after cleaning
 * ([NoticeFilter.sanitize]) and before the inbox, and triage, hints, learning and contacts all work on the shaped record.
 *
 * For conversation-style notifications (any app): the conversation name and whether it is a group come from the system ([styled]), regardless of which app it is.
 *
 * For apps that do not use the system conversation style, a few chat apps squeeze "which conversation, who said it, which message number" into the title and body, so they are split here according to each app's format.
 * Only the apps whose format has actually been seen on a device are split; other apps are not guessed at — a wrong guess would split a "speaker" out of a notification that is not a chat.
 *
 * Each app's format (structure only, not content):
 * - QQ groups: seen on a device, title "group name (13 new messages)", body "someone: the sentence".
 * - QQ private chats: **never seen on a device**, guessed as "the title is the other person and the body has no 'someone: '".
 * - WeChat private chats: seen on a device, the title is the other person, the body is "[3 messages]someone: the sentence", where "someone" is the title.
 * - WeChat groups: **never seen on a device**, guessed as "the title is the group name and the 'someone' in the body is not the title".
 * The two guessed formats need verifying on a device; until then, a wrong split mislabels that entry's speaker and its group/private status.
 * Conversation-style notifications and other apps' notifications are left untouched. If an app changes its format or an unseen format shows up, nothing is split and the record is left as is, with no guessing.
 *
 * Known misjudgement: in a private chat, a message that itself looks like "time: tomorrow" (QQ private-chat bodies have no prefix) is taken as said by someone called "time" in a group.
 * This only affects who that one entry is displayed as being from, not which conversation it counts toward.
 */
object NoticeShapes {
    private val QQ = setOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.qqlite", "com.tencent.mobileqqi")
    private const val WECHAT = "com.tencent.mm"

    /** The "[3 messages]" at the start of a WeChat body. */
    private val UNREAD_PREFIX = Regex("^\\[[0-9]+条\\]\\s*")
    /** "someone: the sentence": the name is short and contains no colon. */
    private val SAID = Regex("^([^:：]{1,40}): (.+)$")

    fun apply(r: NoticeRecord): NoticeRecord = when {
        // conversation-style notifications already tell who said what; the conversation's name needs replacing with the one the system gave
        r.conversation || r.messages.isNotEmpty() -> styled(r)
        r.packageName in QQ -> chat(r, ContactIdentity.withoutUnreadCount(r.title), r.text, privateWithoutPrefix = true)
        r.packageName == WECHAT -> chat(r, r.title, UNREAD_PREFIX.replace(r.text, ""), privateWithoutPrefix = false)
        else -> r
    }

    /**
     * A conversation-style group chat: the system writes the notification title as "conversation name: speaker" (the speaker from the newest message), with the conversation name in a separate field.
     * Taking the title as the conversation name would make every speaker in the same group a separate "conversation". When there is a conversation name it becomes the title, and the speaker from the title goes into [NoticeRecord.sender]
     * (so the line used by hints and triage carries who said it).
     *
     * Whether it is a group: whatever the system says wins; when the system says nothing but there is a conversation name, it is treated as a group (older formats only set a conversation name for groups). When the system says it is not a group yet there is a conversation name, it cannot be told —
     * older formats set only a conversation name without saying group, and newer formats may also name private chats — so that is left to the model rather than declaring it a private chat.
     *
     * No conversation name: a private chat's title is already the other person, so it is left alone. For a group chat (the system says group), the title is the newest message's speaker and cannot identify the conversation, so a flag is recorded.
     */
    private fun styled(r: NoticeRecord): NoticeRecord {
        val name = r.conversationTitle?.trim().orEmpty()
        if (name.isEmpty()) return if (r.group == true) r.copy(titleIsSpeaker = true) else r
        val rest = r.title.trim().removePrefix(name).trimStart()
        val speaker = if (rest.startsWith(":") || rest.startsWith("：")) rest.drop(1).trim() else ""
        // a speaker identical to the conversation name (a private chat using the other person's name as the conversation name) is not recorded separately, same as in [chat]
        return r.copy(title = name, sender = speaker.takeIf { it.isNotEmpty() && it != name }, group = if (r.group == false) null else true)
    }

    /**
     * [title] and [text] have the unread count removed. When the body is "someone: the sentence": if that someone is the title it is a private chat, otherwise it is someone in a group.
     * When the body does not look like that: apps with [privateWithoutPrefix] (QQ) are treated as private chats; for others it cannot be told.
     */
    private fun chat(r: NoticeRecord, title: String, text: String, privateWithoutPrefix: Boolean): NoticeRecord {
        val m = if (title.isBlank()) null else SAID.matchEntire(text)
        val name = m?.groupValues?.get(1)?.trim().orEmpty()
        return when {
            m == null || name.isEmpty() -> r.copy(title = title, text = text, sender = null, group = if (privateWithoutPrefix && title.isNotBlank()) false else null)
            name == title.trim() -> r.copy(title = title, text = m.groupValues[2].trim(), sender = null, group = false)
            else -> r.copy(title = title, text = m.groupValues[2].trim(), sender = name, group = true)
        }
    }
}
