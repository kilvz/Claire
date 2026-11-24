package com.wangzan101.mika.core.proactive

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One piece of text the same notification (same key) has shown over time; [atMs] is the post time of that round. */
data class NoticeLine(val title: String, val text: String, val atMs: Long)

/**
 * The inbox: memory only, at most [capacity] entries, dropping the oldest when full; an update to the same notification key keeps only the newest entry (moved to the newest position).
 * Some apps (WeChat, for instance) repost the same notification for every new message, changing only the body, without a conversation-style message list:
 * for such notifications the different texts seen over time are also recorded per key (oldest to newest, at most [historyLimit] pieces, skipping ones identical to the previous piece), so earlier words are not overwritten.
 * Notifications that carry a message list already bring the earlier messages, so nothing extra is recorded for them.
 * Two counters for the day are also kept: received (those that passed the whitelist) and kept (those that passed filtering and dedup and entered the inbox); they restart at zero on a new day.
 */
class NoticeInbox(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val historyLimit: Int = HISTORY_LIMIT,
) {
    /**
     * [records] runs oldest to newest, each entry being the latest state for its key; [day] is the day the counters belong to (epoch day of the local date).
     * [history] gives, per key, the texts seen over time for notifications without a message list (oldest to newest; the last piece is the record's current text).
     */
    data class Snapshot(
        val records: List<NoticeRecord>,
        val day: Long,
        val received: Int,
        val kept: Int,
        val history: Map<String, List<NoticeLine>> = emptyMap(),
    )

    private val records = LinkedHashMap<String, NoticeRecord>()
    private val history = HashMap<String, List<NoticeLine>>()
    private var day = today()
    private var received = 0
    private var kept = 0
    private val mutable = MutableStateFlow(Snapshot(emptyList(), day, 0, 0))
    val state: StateFlow<Snapshot> = mutable.asStateFlow()

    /** A notification that passed the whitelist arrived (regardless of whether it is kept later). */
    @Synchronized fun noteReceived() {
        rollDay()
        received++
        publish()
    }

    /** Puts in a record that has been filtered and deduplicated. */
    @Synchronized fun add(record: NoticeRecord) {
        rollDay()
        kept++
        records.remove(record.key)
        records[record.key] = record
        remember(record)
        while (records.size > capacity) records.remove(records.keys.first())
        dropOrphanHistory()
        publish()
    }

    /** A notification the user has already seen (opened or swiped away). */
    @Synchronized fun remove(key: String) {
        history.remove(key)
        if (records.remove(key) != null) publish()
    }

    /**
     * Reconciles against the notification shade: [active] holds the keys of all notifications currently in the shade, and records not in it are dropped (handled by the user while locked, revoked by the app,
     * or timed out — the listener was unbound during that stretch and no removal callback arrived). Returns how many were dropped. Only a shade that was read successfully may be used for this: failing to read is not "empty".
     */
    @Synchronized fun retainKeys(active: Set<String>): Int {
        val gone = records.keys.filter { it !in active }
        if (gone.isEmpty()) return 0
        gone.forEach { records.remove(it); history.remove(it) }
        publish()
        return gone.size
    }

    /** When an app is no longer selected, its records are dropped along with it. */
    @Synchronized fun removePackages(keep: (String) -> Boolean) {
        if (records.values.removeAll { !keep(it.packageName) }) {
            dropOrphanHistory()
            publish()
        }
    }

    @Synchronized fun clear() {
        records.clear()
        history.clear()
        publish()
    }

    @Synchronized fun snapshot(): Snapshot {
        rollDay()
        publish()
        return mutable.value
    }

    /** Notifications with a message list get nothing extra recorded (and earlier records are dropped); text identical to the previous piece is skipped; only the most recent [historyLimit] pieces are kept. */
    private fun remember(record: NoticeRecord) {
        if (record.messages.isNotEmpty()) {
            history.remove(record.key)
            return
        }
        val before = history[record.key].orEmpty()
        val last = before.lastOrNull()
        // each piece from a group carries who said it (see [NoticeRecord.said])
        if (last != null && last.title == record.title && last.text == record.said) return
        history[record.key] = (before + NoticeLine(record.title, record.said, record.postedAtMs)).takeLast(historyLimit)
    }

    /** Keys whose record is gone (pushed out when full, or removed by package) keep no text history either. */
    private fun dropOrphanHistory() {
        history.keys.retainAll(records.keys)
    }

    private fun today(): Long = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toEpochDay()

    private fun rollDay() {
        val now = today()
        if (now != day) { day = now; received = 0; kept = 0 }
    }

    private fun publish() {
        mutable.value = Snapshot(records.values.toList(), day, received, kept, HashMap(history))
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val HISTORY_LIMIT = 10
    }
}
