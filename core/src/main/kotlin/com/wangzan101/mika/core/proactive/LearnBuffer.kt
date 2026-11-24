package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user did with this system notification: something we observed from the system ourselves (the listener's removal reason), not something written in the notification. */
enum class LearnOutcome {
    /** Still in the notification shade, or cleared along with "clear all" (this says nothing). */
    None,
    /** Opened. */
    Opened,
    /** Dismissed on its own. */
    Dismissed,
}

/**
 * One notification kept pending summarization. [title] and [text] are the cleaned notification text (written by third parties, not trustworthy) and are cleared once summarization is done ([state] is Done),
 * leaving only the topics it was classified into ([topics]), so that an open/dismiss arriving later can still count toward those topics.
 * [sig] is the fingerprint of this key's text at that time (the first 8 bytes of SHA-256; the text is cleared after summarization, so comparison can only rely on it); identical text under the same key
 * is recorded only once within [LearnTuning.REPEAT_WINDOW_MS].
 */
data class LearnEvent(
    val seq: Long,
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val outcome: LearnOutcome = LearnOutcome.None,
    val state: State = State.Pending,
    val topics: Set<String> = emptySet(),
    val sig: Long = 0,
    /** Whether this notification came from a group chat (true) or a one-to-one chat (false); null when it cannot be told (see [NoticeRecord.group]). This is derived by us from how the notification is written, not stated by the model. */
    val group: Boolean? = null,
    /** When this text most recently appeared (being re-posted verbatim counts too): only an appearance after a long enough gap counts as a new occurrence (see [LearnTuning.REPEAT_WINDOW_MS]). */
    val seenAtMs: Long = postedAtMs,
    /** [title] is the speaker rather than the conversation name (see [NoticeRecord.titleIsSpeaker]): it is not used to identify contacts. */
    val titleIsSpeaker: Boolean = false,
) {
    enum class State { Pending, Sending, Done }
}

/**
 * The event buffer used for summarization: in memory only (it is lost with the process, which is acceptable: summarization is about accumulating statistics anyway), managed separately from the inbox —
 * when records in the inbox are opened or dismissed, the events here remain. At most [capacity] entries; when full, the oldest already-summarized ones are dropped first, then the oldest pending ones (those being sent are never dropped).
 * Not thread-safe: everything is called on the single serial coroutine of ProactiveRuntime; the UI only reads [state].
 */
class LearnBuffer(private val capacity: Int = LearnTuning.BUFFER_CAP) {
    /** The numbers for the UI: how many entries are kept in total, and how many of them are pending summarization. */
    data class Summary(val total: Int = 0, val pending: Int = 0)

    private val events = ArrayList<LearnEvent>()
    /** Keys just recognized as status displays whose event has already been removed (see [forgetKey]): when such a key is opened or dismissed, no backward lookup is done. */
    private val withdrawn = HashSet<String>()
    private var seq = 0L
    private val mutable = MutableStateFlow(Summary())
    val state: StateFlow<Summary> = mutable.asStateFlow()

    /**
     * The inbox accepted one record (already cleaned). The same title and body under the same key are recorded only once within [LearnTuning.REPEAT_WINDOW_MS]
     * (a longer gap makes it a new occurrence, e.g. the same sentence arriving again the next day); when both are empty nothing is recorded. Returns true when it was recorded.
     *
     * [newMessages] are the messages seen for the first time this round in this notification's message list (for conversation-style notifications; see [NoticeDedup.admit]): when present,
     * the sentence used for summarization is those messages (oldest to newest, keeping the newest when they do not fit), not the notification body — the body is often empty or holds only the last sentence,
     * and going by the body alone would leave the earlier sentences accumulated while the screen was locked unsummarized. Detection and the notification text also look at the message list.
     */
    fun capture(record: NoticeRecord, newMessages: List<NoticeMessage> = emptyList()): Boolean {
        val title = NoticeFilter.clean(record.title, LearnTuning.TITLE_MAX)
        val text = NoticeFilter.clean(if (newMessages.isEmpty()) record.said else lines(newMessages), LearnTuning.TEXT_MAX)
        if (title.isEmpty() && text.isEmpty()) return false
        val sig = fingerprint(title + "\u0000" + text)
        // Same text: one appearing again soon after its last appearance is only a re-post (an unattended notification gets re-posted by the app over and over and must not count every half hour), so just record "it appeared again just now";
        // only one appearing again after it has been quiet long enough is a new occurrence
        val i = events.indexOfLast { it.key == record.key && it.sig == sig }
        if (i >= 0 && kotlin.math.abs(record.postedAtMs - events[i].seenAtMs) < LearnTuning.REPEAT_WINDOW_MS) {
            events[i] = events[i].copy(seenAtMs = maxOf(events[i].seenAtMs, record.postedAtMs))
            return false
        }
        withdrawn.remove(record.key)
        events += LearnEvent(
            ++seq, record.key, record.packageName, record.postedAtMs, title, text, sig = sig, group = record.group,
            titleIsSpeaker = record.titleIsSpeaker,
        )
        evict()
        publish()
        return true
    }

    /** Merges several messages into one text (with who said it, oldest to newest): at most [LearnTuning.NEW_LINES_MAX] messages and no more than [LearnTuning.TEXT_MAX] characters in total, keeping the newest when they do not fit. */
    private fun lines(messages: List<NoticeMessage>): String {
        val picked = ArrayList<String>()
        var chars = 0
        for (m in messages.asReversed()) {
            if (picked.size >= LearnTuning.NEW_LINES_MAX) break
            val line = NoticeFilter.clean(listOfNotNull(m.sender, m.text).joinToString(": "), LearnTuning.TEXT_MAX)
            if (line.isEmpty()) continue
            val cost = line.length + if (picked.isEmpty()) 0 else LINE_BREAK.length
            if (picked.isNotEmpty() && chars + cost > LearnTuning.TEXT_MAX) break
            picked += line
            chars += cost
        }
        return picked.asReversed().joinToString(LINE_BREAK)
    }

    /**
     * The system says this notification was opened/dismissed: recorded on the newest event for this key (what the user tapped is the text currently in the notification shade), only once.
     * If that one has already been summarized, returns the topics it was classified into (the caller adds them to the topic pool); otherwise returns empty.
     */
    fun outcome(key: String, outcome: LearnOutcome): Set<String> {
        // the one handled was a status display: its event has already been removed, so searching backwards would only find an earlier, unrelated notification under the same key and attribute this open/dismiss to its topic
        if (withdrawn.remove(key)) return emptySet()
        if (outcome == LearnOutcome.None) return emptySet()
        val i = events.indexOfLast { it.key == key }
        if (i < 0) return emptySet()
        val e = events[i]
        if (e.outcome != LearnOutcome.None) return emptySet()
        events[i] = e.copy(outcome = outcome)
        return if (e.state == LearnEvent.State.Done) e.topics else emptySet()
    }

    /**
     * Which app should be sent now: only pending entries from apps that [eligible] accepts are considered; an app is due once it has accumulated [LearnTuning.DUE_COUNT] entries
     * or its oldest entry is older than [LearnTuning.DUE_AGE_MS]. When several are due, the one with the oldest entry goes first. Null when nothing is due.
     */
    fun due(nowMs: Long, eligible: (String) -> Boolean): String? = events
        .filter { it.state == LearnEvent.State.Pending && eligible(it.packageName) }
        .groupBy { it.packageName }
        .filter { (_, list) -> list.size >= LearnTuning.DUE_COUNT || nowMs - list.minOf { it.postedAtMs } >= LearnTuning.DUE_AGE_MS }
        .minByOrNull { (_, list) -> list.minOf { it.postedAtMs } }
        ?.key

    val pendingCount: Int get() = events.count { it.state == LearnEvent.State.Pending }

    /**
     * This key is actually a status display that keeps being re-posted (see [LiveUpdates]): the entries captured from [sinceMs] onward that have not been sent for summarization yet are removed.
     * Earlier ones and the batch currently being sent are left alone (the caller cancels the in-flight batch first). Returns true when anything was removed.
     * An open/dismiss of this notification afterwards does not count as feedback for any topic (see [outcome]) until a new entry is captured under this key.
     */
    fun forgetKey(key: String, sinceMs: Long): Boolean {
        withdrawn += key
        // only remove what was captured during this round of re-posts: many apps share one notification key across all pushes, so an earlier normal notification under the same key must not be lost with them
        val gone = events.removeAll { it.key == key && it.state == LearnEvent.State.Pending && it.postedAtMs >= sinceMs }
        if (gone) publish()
        return gone
    }

    /** Starts a batch: up to [LearnTuning.BATCH_MAX] of this app's oldest pending entries are marked as being sent. */
    fun begin(packageName: String): List<LearnEvent> {
        check(events.none { it.state == LearnEvent.State.Sending }) { "a batch is already in flight" }
        val picked = events.filter { it.state == LearnEvent.State.Pending && it.packageName == packageName }
            .sortedWith(compareBy({ it.postedAtMs }, { it.seq }))
            .take(LearnTuning.BATCH_MAX)
            .map { it.seq }.toSet()
        events.replaceAll { if (it.seq in picked) it.copy(state = LearnEvent.State.Sending) else it }
        publish()
        return sending()
    }

    /** The current state of the batch being sent (opens/dismisses may have arrived after it was sent out), in the order they were sent. */
    fun sending(): List<LearnEvent> = events.filter { it.state == LearnEvent.State.Sending }
        .sortedWith(compareBy({ it.postedAtMs }, { it.seq }))

    /** The batch was not sent (cancelled by screen lock, budget exhausted, the switch changed): the entries go back to pending summarization. */
    fun abort() {
        events.replaceAll { if (it.state == LearnEvent.State.Sending) it.copy(state = LearnEvent.State.Pending) else it }
        publish()
    }

    /** The batch failed (timeout, model error, unreadable reply): drop it without retrying. Returns how many entries were dropped. */
    fun fail(): Int {
        val before = events.size
        events.removeAll { it.state == LearnEvent.State.Sending }
        publish()
        return before - events.size
    }

    /** The batch was summarized: the topics each entry was classified into are recorded by seq, and the text is cleared. */
    fun finish(topicsBySeq: Map<Long, Set<String>>) {
        events.replaceAll {
            if (it.state != LearnEvent.State.Sending) it
            else it.copy(state = LearnEvent.State.Done, title = "", text = "", topics = topicsBySeq[it.seq].orEmpty())
        }
        publish()
    }

    /** Topic [from] was merged into [into] by the user: events pointing at it are pointed at the other one (a later open/dismiss counts toward that one). */
    fun remapTopic(from: String, into: String) {
        events.replaceAll { if (from in it.topics) it.copy(topics = it.topics - from + into) else it }
    }

    /** A topic was deleted: events no longer point at it. */
    fun forgetTopics(ids: Set<String>) {
        if (ids.isEmpty()) return
        events.replaceAll { if (it.topics.any(ids::contains)) it.copy(topics = it.topics - ids) else it }
    }

    /** Events of apps no longer collected (unchecked, not summarized from) are removed too; the batch being sent is left alone (the caller cancels it first). */
    fun purge(keep: (String) -> Boolean) {
        if (events.removeAll { it.state != LearnEvent.State.Sending && !keep(it.packageName) }) publish()
    }

    fun clear() {
        events.clear()
        publish()
    }

    private fun evict() {
        while (events.size > capacity) {
            val i = events.indexOfFirst { it.state == LearnEvent.State.Done }
                .takeIf { it >= 0 } ?: events.indexOfFirst { it.state == LearnEvent.State.Pending }
            if (i < 0) break
            events.removeAt(i)
        }
    }

    private fun publish() {
        mutable.value = Summary(events.size, pendingCount)
    }

    private companion object {
        /** The separator between two messages when merging into one text (cleaning collapses line breaks into spaces, so a line break is not used). */
        const val LINE_BREAK = " / "

        /** Text fingerprint: the first 8 bytes of SHA-256 (with a 32-bit hashCode, two different strings such as "Aa" and "BB" would collide). Used in memory only. */
        fun fingerprint(s: String): Long {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            var out = 0L
            for (i in 0 until 8) out = (out shl 8) or (d[i].toLong() and 0xff)
            return out
        }
    }
}
