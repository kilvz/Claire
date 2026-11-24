package com.wangzan101.mika.core.proactive

import kotlinx.serialization.Serializable

/**
 * A piece of notification text that was received. [title] is the name of the conversation (a person or group, or things like "payment received"), [sender] is the person speaking in a group (null if none),
 * and [text] is the sentence itself. All of this text was written by other apps and other people.
 *
 * [keyId] is a hash of the notification key ([NoticeDedup.idOf], which cannot be reversed into the original key), [sig] is a fingerprint of this piece's content, and [styled] says it came from a conversation-style notification:
 * these three are only used to recognise "has this piece of this notification been recorded already", and they make that recognisable after a restart once persisted.
 */
@Serializable
data class NoticeEntry(
    val packageName: String,
    val atMs: Long,
    val title: String,
    val sender: String? = null,
    val text: String,
    val keyId: String = "",
    val sig: Int = 0,
    val styled: Boolean = false,
    /** This piece has been through segmented note-taking (level 1, see [NoticeNotes]); attempts that failed also count as done and are not sent again. */
    val noted: Boolean = false,
    /** Group chat (true), private chat (false), or null when it cannot be told (see [NoticeRecord.group]). */
    val group: Boolean? = null,
)

/**
 * The persisted shape of the notification journal: [sinceMs] is when this copy started recording, [entries] runs oldest to newest;
 * [cutMs] is the time of the latest piece among those pushed out because the count was full (0 if nothing was ever pushed out).
 */
@Serializable
data class NoticeJournalData(val version: Int = 1, val sinceMs: Long, val entries: List<NoticeEntry> = emptyList(), val cutMs: Long = 0)

/**
 * The notification journal (level 0 of the daily message digest): every piece of notification text that comes in is recorded by time (after whitelisting, filtering, dedup, cleaning and splitting).
 * Unlike [NoticeInbox], entries survive the user opening or swiping away the notification — "what did I receive today" reads from here.
 *
 * Pure logic and not thread-safe (it runs on the single serial coroutine in ProactiveRuntime). Whether and when to persist is up to the caller ([data] gives the shape to store,
 * and passing it back to the constructor continues from last time). Notifications are not read while locked (after unlocking only those still in the shade can be caught up), so [sinceMs] records when this copy started recording,
 * and whoever reads it must make clear "from what time, and possibly incomplete".
 *
 * Only the most recent [keepMs] are kept ([expire]; something must call it even when no new notifications arrive), at most [capacity] entries, dropping the oldest when full.
 *
 * The same content in the same notification is recorded only once: conversation-style notifications carry the previous few messages on every repost and are recognised by (who said it, text, time);
 * other notifications (WeChat and QQ repost the same notification for every new message) are recognised by chat name, speaker and body, and nothing is recorded when it matches the previous round of that notification.
 * The identity used ([NoticeEntry.keyId], [NoticeEntry.sig]) is stored with the entry, so after a restart an identical repost of the same notification is not recorded again.
 */
class NoticeJournal(
    data: NoticeJournalData? = null,
    private val capacity: Int = CAPACITY,
    private val keepMs: Long = KEEP_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<NoticeEntry>()

    /** When this copy started recording (when it was first created, or the time of the last [clear]). */
    var sinceMs: Long = data?.sinceMs ?: clock()
        private set

    /** The time of the latest piece among those pushed out when the count was full; 0 if nothing was ever pushed out. */
    private var cutMs: Long = data?.cutMs ?: 0L

    /** From when this copy is complete: the time it started recording, or, if old entries were pushed out when full, the point after those pushed out. */
    val completeSinceMs: Long get() = maxOf(sinceMs, cutMs)

    /**
     * Whether the data read in contained entries past the retention period (already removed from memory when this was created). If so, they are still in the file:
     * the caller must persist right away, otherwise [expire] will never report "something changed" again and the expired original text sits in the file forever.
     */
    val expiredOnLoad: Boolean

    init {
        expiredOnLoad = if (data != null) {
            entries.addAll(data.entries)
            expire()
        } else false
    }

    val size: Int get() = entries.size

    fun data(): NoticeJournalData = NoticeJournalData(sinceMs = sinceMs, entries = entries.toList(), cutMs = cutMs)

    /** Records the new content of a record that has been cleaned and split. Returns how many pieces were newly recorded. */
    fun add(record: NoticeRecord): Int {
        val keyId = NoticeDedup.idOf(record.key)
        var added = 0
        if (record.messages.isNotEmpty()) {
            val seen = entries.filterTo(ArrayList()) { it.styled && it.keyId == keyId }
            for (m in record.messages) {
                if (m.text.isBlank()) continue
                val sig = listOf(m.sender, m.text, m.atMs).hashCode()
                // the fingerprint is only 32 bits, so different sentences can collide: on a collision the original text is compared again (the journal keeps the original), and only a true match counts as already recorded
                if (seen.any { it.sig == sig && it.sender == m.sender && it.text == m.text }) continue
                entries += NoticeEntry(
                    // the time a message carries is filled in by the posting app: if absent use the notification's post time, and times later than the post (mistakes, or deliberately set in the future) also use the post time,
                    // otherwise it would never expire and would be counted into some future day
                    record.packageName, m.atMs.takeIf { NoticeDedup.trusted(it, record.postedAtMs) }?.coerceAtMost(record.postedAtMs) ?: record.postedAtMs, record.title, m.sender, m.text,
                    keyId = keyId, sig = sig, styled = true, group = record.group,
                )
                seen += entries.last()
                added++
            }
        } else if (record.title.isNotBlank() || record.text.isNotBlank()) {
            val sig = listOf(record.title, record.sender, record.text).hashCode()
            val last = entries.lastOrNull { !it.styled && it.keyId == keyId }
            // identical to the previous round of this notification means nothing is recorded (apps that repost the same notification verbatim for every new message). "Identical" requires the original text to really match (the fingerprint is only 32 bits),
            // and requires it to be recent: more than half a day apart is a new occurrence (the same sentence sent again the next day) and must be recorded, or it would be missing from that day's digest
            val repeat = last != null && last.sig == sig && last.title == record.title && last.sender == record.sender &&
                last.text == record.text && record.postedAtMs - last.atMs < LearnTuning.REPEAT_WINDOW_MS
            if (!repeat) {
                entries += NoticeEntry(
                    record.packageName, record.postedAtMs, record.title, record.sender, record.text, keyId = keyId, sig = sig, group = record.group,
                )
                added++
            }
        }
        if (added > 0) {
            expire()
            while (entries.size > capacity) cutMs = maxOf(cutMs, entries.removeFirst().atMs)
        }
        return added
    }

    /** Entries between [fromMs] (inclusive) and [toMs] (exclusive), oldest to newest. */
    fun between(fromMs: Long, toMs: Long): List<NoticeEntry> = entries.filter { it.atMs in fromMs until toMs }.sortedBy { it.atMs }

    /**
     * Which notifications a segmented note ([DigestNote]) was written from: it only recorded the app, the chat name and the start and end times, not which pieces — so what is returned is
     * this app's notifications between [fromMs] and [toMs] (both inclusive); when [title] is not null only that conversation's are returned (comparing chat names derived from titles the same way as when the sentence was stored,
     * see [ContactIdentity.displayName]). It may include a few more pieces than were actually used (ones not cited within that span). Oldest to newest.
     */
    fun around(packageName: String, title: String?, fromMs: Long, toMs: Long): List<NoticeEntry> = entries
        .filter { it.packageName == packageName && it.atMs in fromMs..toMs && (title == null || ContactIdentity.displayName(it.title) == title) }
        .sortedBy { it.atMs }

    // ---- For segmented notes (level 1): which pieces have not been noted yet ----

    /** How many pieces have not been noted yet. */
    val pendingCount: Int get() = entries.count { !it.noted }

    /**
     * Which app should be noted now: only apps accepted by [eligible] that have unnoted pieces are considered; an app is due once it has accumulated [dueCount] pieces, or its oldest piece is older than [dueAgeMs].
     * When several are due, the one with the oldest piece goes first. Null when nothing is due. No timers: the caller asks whenever something happens.
     */
    fun due(nowMs: Long, eligible: (String) -> Boolean, dueCount: Int, dueAgeMs: Long): String? = entries
        .filter { !it.noted && eligible(it.packageName) }
        .groupBy { it.packageName }
        .filter { (_, list) -> list.size >= dueCount || nowMs - list.minOf { it.atMs } >= dueAgeMs }
        .minByOrNull { (_, list) -> list.minOf { it.atMs } }
        ?.key

    /** Whether there are unnoted pieces between [fromMs] (inclusive) and [toMs] (exclusive) among apps accepted by [eligible]: if so, the daily digest for that span should wait. */
    fun hasPending(fromMs: Long, toMs: Long, eligible: (String) -> Boolean): Boolean =
        entries.any { !it.noted && it.atMs in fromMs until toMs && eligible(it.packageName) }

    /** This app's unnoted pieces, oldest to newest, at most [limit]. */
    fun pending(packageName: String, limit: Int): List<NoticeEntry> =
        entries.filter { !it.noted && it.packageName == packageName }.sortedBy { it.atMs }.take(limit)

    /** The [done] pieces have been through segmented note-taking (whether or not it succeeded). Returns true if anything changed. */
    fun markNoted(done: List<NoticeEntry>): Boolean {
        if (done.isEmpty()) return false
        val ids = done.mapTo(HashSet()) { identity(it) }
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            if (!e.noted && identity(e) in ids) {
                entries[i] = e.copy(noted = true)
                changed = true
            }
        }
        return changed
    }

    private fun identity(e: NoticeEntry) = listOf(e.packageName, e.keyId, e.sig, e.atMs, e.styled)

    /** Drops entries past the retention period. Returns true if anything was dropped. Not only called when a new notification arrives: entries must also be dropped when nothing new comes in. */
    fun expire(): Boolean = removeBefore(clock() - keepMs)

    /** Drops everything before [beforeMs] (entries past retention, or a day that has already been fully noted). Returns true if anything was dropped. */
    fun removeBefore(beforeMs: Long): Boolean = entries.removeAll { it.atMs < beforeMs }

    /** When an app is no longer noted (deselected, disabled, not noted from, or its data cleared), its entries are dropped too. Returns true if anything was dropped. */
    fun removePackages(keep: (String) -> Boolean): Boolean = entries.removeAll { !keep(it.packageName) }

    /**
     * This key ([keyId] is the hash of the notification key) turned out to be a repeatedly reposted status display (see [LiveUpdates]): drop the pieces recorded since [sinceMs] that have not been noted yet.
     * Earlier ones (previous normal notifications on the same key) and ones already noted stay put (the sentences have already been written and keep the original text as their evidence). Returns true if anything was dropped.
     */
    fun removeKey(keyId: String, sinceMs: Long, untilMs: Long = Long.MAX_VALUE): Boolean =
        entries.removeAll { it.keyId == keyId && !it.noted && it.atMs in sinceMs..untilMs }

    /** Which apps' notification originals are currently held (package names only, no content). */
    fun packages(): Set<String> = entries.mapTo(LinkedHashSet()) { it.packageName }

    /** Clears everything and starts recording again from now. */
    fun clear() {
        entries.clear()
        sinceMs = clock()
        cutMs = 0L
    }

    companion object {
        const val CAPACITY = 2_000
        /** How long originals are kept: today and yesterday. */
        const val KEEP_MS = 48 * 3_600_000L
    }
}
