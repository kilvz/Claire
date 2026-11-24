package com.wangzan101.mika.chat

/**
 * Auto-archive: conversations with no new messages for too long are moved into the archive (like manual archiving, they can be restored from the archive view at any time).
 * Off by default; the number of days is chosen in Settings > My data > Auto-archive conversations.
 */
object AutoArchive {
    /** The selectable "archive after this long without new messages" day counts; 0 means off. */
    val DAYS = listOf(0, 7, 30, 90, 180)

    /** How long without new messages counts as "too long": counted back from this moment [days] days ago. */
    fun cutoff(days: Int, now: Long): Long = now - days * DAY_MS

    /**
     * Picks from [all] the ones to archive this round: not archived yet, not pinned, last changed before [cutoff],
     * and not manually unarchived recently (a manually restored conversation only counts as quiet again if it was restored before [cutoff]).
     * Entries in [keep] are left alone (running tasks, the conversation the user is viewing right now).
     */
    fun candidates(all: List<ConversationSummary>, cutoff: Long, keep: Set<String> = emptySet()): List<String> =
        all.asSequence()
            .filter { it.archivedAt == null && it.pinnedAt == null }
            .filter { it.updatedAt < cutoff }
            .filter { it.restoredAt == null || it.restoredAt < cutoff }
            .filter { it.id !in keep }
            .map { it.id }
            .toList()

    /**
     * Runs once according to the settings and returns how many were archived. Judged by message time; old records lacking a time fall back to the file time;
     * when a new turn is written afterwards, it is automatically unarchived just as with manual archiving (see [ConversationStore.append]).
     */
    fun run(
        store: ConversationStore,
        days: Int,
        running: Collection<String> = emptyList(),
        currentId: String? = null,
        now: Long = System.currentTimeMillis(),
        isRunning: (String) -> Boolean = { false },
    ): Int {
        if (days <= 0) return 0
        val keep = running.toSet() + listOfNotNull(currentId)
        val cutoff = cutoff(days, now)
        return store.autoArchiveCandidates().asSequence()
            .filter { it.id !in keep }
            .count { store.archiveIfStillEligible(it, cutoff, at = now, isRunning = isRunning) }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
