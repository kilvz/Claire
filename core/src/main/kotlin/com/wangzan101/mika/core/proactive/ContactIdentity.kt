package com.wangzan101.mika.core.proactive

import com.wangzan101.mika.core.learn.memory.SensitiveFilter

/**
 * How contact identity is computed. [Title] is used; the other two are kept so that ids computed by them in the past (stored contacts, the "do not record again" list) can still be recognized.
 *
 * Why the notification key is not used: the key is decided by the app posting the notification and is not guaranteed to be one per conversation. Both kinds have been seen on real devices: some apps use one fixed key per conversation;
 * others increment the notification id, so the same group gets a different key after a while. There is no way to know which kind an untested app is, so it is not used to identify conversations.
 */
enum class ContactIdStrategy(val prefix: String) {
    /** Key only (no longer used): this requires one key per conversation, which does not hold. */
    Key("k"),
    /**
     * Conversation name only (currently used): within the same app, identical names mean the same contact. The name is the conversation name the notification provides with the unread count removed
     * (group chats with a conversation style use the conversation name the system provides separately, see [NoticeShapes]).
     * Cost: two conversations with exactly the same name in one app merge into one; a stranger changing their nickname to match some contact also merges into it.
     * A contact enters a task with only its name and relationship, not message content, so this cost is acceptable. If the user changes the other party's alias, it is treated as a new contact computed from scratch.
     */
    Title("t"),
    /** Both key and conversation name (formerly used): once the key changes it becomes another contact, see above. */
    KeyAndTitle("b"),
}

/**
 * A contact's identity is computed by us rather than named by the model: a pure function using only three things (package name, notification key, title).
 * The id is the leading hexadecimal digits of SHA-256 (the same approach as [NoticeDedup.idOf]), prefixed with the strategy's letter: the id contains no name and the key cannot be inferred from it,
 * and old ids (including those on the "do not record again" list) will not collide with new ones when the strategy changes.
 *
 * Known limitations: individual members of a group chat are not recorded separately; a group is a single contact of type "group". Apps that do not use the system conversation style and put varying things in the title
 * (forms other than the unread counts recognized here) will still split one conversation apart: each new form seen is added on ([withoutUnreadCount]).
 */
object ContactIdentity {
    /** The computed identity; [name] is the display name this notification can provide (cleaned and passing the sensitive-information check), or null when it can provide none. */
    data class Identity(val id: String, val name: String?)

    // Seen on real devices: some apps title conversations "group name (2 new messages)", so the title changes whenever the count changes. Larger counts are written as "99+" or "99 or more"
    private val UNREAD_COUNT = Regex("\\s*[(（]\\s*[0-9]+\\+?\\s*条(?:以上)?新消息\\s*[)）]$")
    // The form recognized previously (without "99+"): used only to recompute old ids, must not be changed
    private val LEGACY_UNREAD_COUNT = Regex("\\s*[(（]\\s*[0-9]+\\s*条(?:以上)?新消息\\s*[)）]$")

    /**
     * Removes the unread count at the end of the title (forms such as "(2 new messages)"): it is not part of the name. Without removing it, each additional unread message turns the same conversation into another contact,
     * the day count never accumulates, and anything the user deleted comes back with a different count. Only endings carrying "new messages" are recognized; parentheses and digits elsewhere in the name are left untouched.
     */
    internal fun withoutUnreadCount(cleanTitle: String): String = UNREAD_COUNT.replace(cleanTitle, "")

    /**
     * Conversation name: cleaned, trailing unread count removed, truncated to [LearnTuning.CONTACT_NAME_MAX]. Both the display name and the identity come from this single string —
     * a stored contact only has a display name, and the same identity as a new notification must be computable from it (this is what merging earlier entries relies on, see [ContactPool.adoptIdentities]).
     */
    internal fun conversationName(title: String): String =
        NoticeFilter.cap(withoutUnreadCount(NoticeFilter.clean(title, 1_000)), LearnTuning.CONTACT_NAME_MAX).trim()

    // Seen on real devices: some apps end the conversation name with a number in parentheses ("group name (1,234)"), and the number for the same group changes after a while
    private val TRAILING_COUNT = Regex("\\s*[(（]\\s*[0-9][0-9,，.]*\\+?\\s*[)）]$")

    /**
     * The name used to identify conversations: cleaned like [conversationName], unread count removed, then the number in trailing parentheses removed, and only then truncated; comparisons ignore case, full-width/half-width and whitespace.
     * That number stays in the display name (it may be part of the name, "project group (2)"); it is simply not used to separate conversations: identifying with it would turn the same group into another contact whenever the number changes.
     * Cost: "project group (2)" and "project group (3)" merge into one.
     * Remove the number before truncating: in the other order, when the name plus that number just exceeds the limit, half of the parentheses gets cut off and it can no longer be recognized, so a change of number still yields another contact.
     * A stored display name was truncated with that number included: when the name together with the number stays under the limit, the value computed from it matches the one computed from the notification; the same holds when the name itself already exceeds the limit
     * (the same leading segment is cut). Only the in-between case (name under the limit, over it once the number is added) differs: the parentheses in the display name were cut in half. Such entries stored earlier
     * do not merge onto the new notification's entry when identities change and are left to fade away.
     */
    internal fun identityName(title: String): String {
        val full = withoutUnreadCount(NoticeFilter.clean(title, 1_000))
        return TopicPool.normalize(NoticeFilter.cap(TRAILING_COUNT.replace(full, ""), LearnTuning.CONTACT_NAME_MAX).trim())
    }

    /** Null when no identity can be computed (the conversation name is empty under a strategy that depends on it). */
    fun of(packageName: String, key: String, title: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): Identity? {
        val name = displayName(title)
        val material = when (strategy) {
            ContactIdStrategy.Key -> key
            ContactIdStrategy.Title -> identityName(title).ifEmpty { return null }
            // the previous algorithm is kept as is: ids computed by it still exist on the "do not record again" list
            ContactIdStrategy.KeyAndTitle -> key + "\n" +
                TopicPool.normalize(LEGACY_UNREAD_COUNT.replace(NoticeFilter.clean(title, LearnTuning.TITLE_MAX), "")).ifEmpty { return null }
        }
        return Identity(strategy.prefix + NoticeDedup.idOf("contact\u0000$packageName\u0000$material"), name)
    }

    /** Which id a stored contact should have under [strategy]: only the display name is available, so it can only be computed for a strategy that looks at the conversation name alone. */
    internal fun ofStored(packageName: String, name: String, strategy: ContactIdStrategy = LearnTuning.CONTACT_ID): String? =
        if (strategy == ContactIdStrategy.Title) of(packageName, "", name, strategy)?.id else null

    /**
     * Display name: cleaned the way notification text is, truncated to [LearnTuning.CONTACT_NAME_MAX]. Empty yields null; as with new topic names, values that look like a URL, contain a long digit run,
     * or fail [SensitiveFilter] also yield null (checked against the untruncated whole): a sender whose title is just a phone number, or a push notification carrying a tracking number, is not stored as a name.
     * Therefore a sender that only displays as a number never becomes a contact (one that yields no name on first sight is not recorded).
     */
    fun displayName(title: String): String? {
        val full = withoutUnreadCount(NoticeFilter.clean(title, 1_000))
        if (full.isEmpty()) return null
        val normalized = LearnText.nfkc(full)
        if (LearnText.urlLike(normalized) || LearnText.longDigits(normalized) || LearnText.sensitive(full)) return null
        return conversationName(title).ifEmpty { null }
    }
}
