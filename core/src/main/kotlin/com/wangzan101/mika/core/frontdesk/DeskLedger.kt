package com.wangzan101.mika.core.frontdesk

/** One task dispatched by the front desk: [words] is the user's original words, [at] the dispatch time. */
data class DeskTaskEntry(val taskId: String, val title: String, val words: String, val at: Long)

/** Tasks dispatched by the front desk in this conversation, in dispatch order; used to prevent duplicates and verify task ids. Thread-safe. */
class DeskLedger {
    /** Each task with its dedupe key (which task the front desk means; the original words by default). */
    private val entries = mutableListOf<Pair<DeskTaskEntry, String>>()

    @Synchronized
    fun add(entry: DeskTaskEntry, asked: String = entry.words) {
        entries += entry to asked.trim()
    }

    @Synchronized
    fun all(): List<DeskTaskEntry> = entries.map { it.first }

    @Synchronized
    fun get(taskId: String): DeskTaskEntry? = entries.firstOrNull { it.first.taskId == taskId }?.first

    /** The latest task dispatched at or after [since] with the same dedupe key (ignoring surrounding whitespace); null when none. */
    @Synchronized
    fun find(asked: String, since: Long): DeskTaskEntry? {
        val key = asked.trim()
        return entries.lastOrNull { (entry, k) -> entry.at >= since && k == key }?.first
    }
}
