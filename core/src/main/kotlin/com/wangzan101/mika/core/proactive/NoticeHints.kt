package com.wangzan101.mika.core.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One "worth surfacing": the shape of the record when it is judged [TriageDecision.Surface], plus the reason the model wrote. All text comes from notifications (and the model) and is only displayed as plain text.
 * [seen] means the user opened the hints and this one has already been written into the hints conversation.
 */
data class NoticeHint(
    val key: String,
    val packageName: String,
    val postedAtMs: Long,
    val title: String,
    val text: String,
    val reason: String,
    val seen: Boolean = false,
)

/** The user's reaction to hints, counted per app (memory only; for now just displayed and not affecting any behaviour). */
data class HintFeedback(
    /** Opened while the hint was still there (so it was seen). */
    val seen: Int = 0,
    /** The user opened or swiped away the notification in the notification shade before opening the hints for it. */
    val dismissedUnseen: Int = 0,
)

/**
 * The "worth surfacing, not yet seen" ones: pure logic, memory only, and nothing outside notifications is touched.
 *
 * [sync] feeds in the inbox's records and the triage results (only those whose post time matches and that were judged worth surfacing count); [list] holds the hints still in the inbox (newest first, at most [limit]),
 * and [unseen] is how many of them have not been seen (the number on the pill).
 * - Opening the hints ([markSeen]): the ones written into the hints conversation are marked seen and the count drops to zero; they stay in [list] (the counts on the proactive page use them).
 * - When a record disappears from the inbox (the user opened or swiped away the notification, the app is no longer selected, the feature is turned off) its hint goes too; ones the user opened or swiped away are first noted through [dismissed].
 * - With triage off (triageOn false in [sync]): the list is cleared; the record of what has been seen up to which point stays, so previously seen hints do not reappear when it is turned back on.
 * - The feedback counts ([State.feedback]) live with the process and are not cleared when the feature is turned off (for now only two numbers are shown on the proactive page).
 * - The same key judged worth surfacing again: once seen, it only counts as new if the notification's post time is newer than the one that was seen.
 * - The notification was updated and this new round is not judged yet (or judging failed): the old entry is kept for now; if it ends up not being worth surfacing, it is removed.
 * - Above [limit] entries the oldest are pushed out, not counted, and never reappear afterwards (unless that notification is updated again and judged worth surfacing again).
 */
class NoticeHints(private val limit: Int = LIMIT) {
    data class State(val list: List<NoticeHint> = emptyList(), val feedback: Map<String, HintFeedback> = emptyMap()) {
        val unseen: Int get() = list.count { !it.seen }
        val seenTotal: Int get() = feedback.values.sumOf { it.seen }
        val dismissedUnseenTotal: Int get() = feedback.values.sumOf { it.dismissedUnseen }
    }

    private val items = HashMap<String, NoticeHint>()
    /** For each key, the post time that was seen: the same key with a time no newer than this no longer counts as a new hint. */
    private val acked = HashMap<String, Long>()
    private val feedback = HashMap<String, HintFeedback>()
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /** The inbox and the triage results changed: [marks] is keyed by notification key, and only counts when the post time matches the record. */
    @Synchronized fun sync(records: List<NoticeRecord>, marks: Map<String, TriageMark>, triageOn: Boolean) {
        val byKey = records.associateBy { it.key }
        items.keys.retainAll(byKey.keys)
        acked.keys.retainAll(byKey.keys)
        if (!triageOn) {
            items.clear()
            publish()
            return
        }
        for (r in records) {
            val mark = marks[r.key]?.takeIf { it.postedAtMs == r.postedAtMs }
            val verdict = (mark as? TriageMark.Done)?.verdict
            val old = items[r.key]
            if (verdict == null) continue // not judged yet or failed: keep the previous entry (if any) for now
            if (verdict.decision != TriageDecision.Surface) {
                items.remove(r.key)
                continue
            }
            if (old != null && old.postedAtMs >= r.postedAtMs) continue
            if ((acked[r.key] ?: Long.MIN_VALUE) >= r.postedAtMs) continue
            items[r.key] = NoticeHint(r.key, r.packageName, r.postedAtMs, r.title, r.said, verdict.reason)
        }
        trim()
        publish()
    }

    /** The user opened or swiped away this notification in the notification shade (the inbox then drops it): one not seen yet gets a "handled unseen" count. */
    @Synchronized fun dismissed(key: String) {
        val h = items.remove(key) ?: return
        if (!h.seen) bump(h.packageName) { it.copy(dismissedUnseen = it.dismissedUnseen + 1) }
        acked.remove(key)
        publish()
    }

    /** This notification should not have been accepted at all (it turned out to be a repeatedly reposted status display, see [LiveUpdates]): the hint is removed. This was not the user handling it, so no "handled unseen" count. */
    @Synchronized fun withdraw(key: String) {
        if (items.remove(key) == null) return
        acked.remove(key)
        publish()
    }

    /**
     * The user opened the hints and the [shown] ones have been written into the hints conversation: mark them seen. Only entries whose key and post time both match count —
     * if the same notification was updated again while writing, or a new hint arrived, those have not been shown to the user yet and still count as unseen.
     */
    @Synchronized fun markSeen(shown: List<NoticeHint>) {
        var changed = false
        for (s in shown) {
            val h = items[s.key] ?: continue
            if (h.seen || h.postedAtMs != s.postedAtMs) continue
            items[s.key] = h.copy(seen = true)
            acked[s.key] = maxOf(acked[s.key] ?: Long.MIN_VALUE, h.postedAtMs)
            bump(h.packageName) { it.copy(seen = it.seen + 1) }
            changed = true
        }
        if (changed) publish()
    }

    /** Keeps only the newest [limit] entries (by post time); the pushed-out ones are treated as expired and marked as handled (they never reappear unless that notification is updated again). */
    private fun trim() {
        if (items.size <= limit) return
        items.values.sortedByDescending { it.postedAtMs }.drop(limit).forEach {
            items.remove(it.key)
            acked[it.key] = maxOf(acked[it.key] ?: Long.MIN_VALUE, it.postedAtMs)
        }
    }

    private inline fun bump(pkg: String, f: (HintFeedback) -> HintFeedback) {
        feedback[pkg] = f(feedback[pkg] ?: HintFeedback())
    }

    private fun publish() {
        mutable.value = State(items.values.sortedByDescending { it.postedAtMs }, HashMap(feedback))
    }

    companion object {
        const val LIMIT = 50
    }
}
