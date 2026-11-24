package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.agent.TokenEstimate

/** [message] being null means there is no memory to carry this time; [usedIds] are the items actually listed in the block, in the same order as the list. */
data class MemoryBlock(val message: String?, val usedIds: List<String>)

/**
 * The memory block for each task: placed as a single user message after the conversation context and before the
 * task text itself.
 *
 * Two ways of providing it:
 * - [Mode.Relevant]: the pinned ones plus retrieval results above the threshold (approved procedures are listed
 *   separately and capped), total length bounded by [MAX_TOKENS]. The old approach for when there is a lot of memory.
 * - [Mode.Full]: everything without exception (procedures are still bounded by [MAX_PROCEDURES]), total length
 *   bounded by [FULL_MAX_TOKENS].
 *
 * The product default is [Mode.Auto]: if the memories together do not exceed [FULL_MAX_TOKENS], inject all of them
 * (procedures included); only if they exceed it fall back to retrieval.
 * Memories are not numerous to begin with (a few dozen entries, a few thousand characters), while today's model
 * contexts routinely run to hundreds of thousands; injecting everything misses nothing, and the prefix is also fixed,
 * so after hitting the context cache it is cheaper than "different retrieval results every time".
 * But once memories grow to tens of thousands of characters, cramming them all in would eat up the context, so the
 * retrieval fallback is kept.
 */
object MemoryContext {
    const val MAX_TOKENS = 800
    /** Overall cap for full injection; exceeding it means it is time to fall back to retrieval (see [Mode.Auto]). */
    const val FULL_MAX_TOKENS = 4_000
    const val MAX_PINNED = 5
    const val MAX_RELEVANT = 8
    const val MAX_PROCEDURES = 3
    const val MIN_SCORE = 0.5

    /** [Relevant]: only the pinned and the retrieved ones; [Full]: everything (drops the oldest when over budget); [Auto]: everything when it fits, otherwise fall back to [Relevant]. */
    enum class Mode { Relevant, Full, Auto }

    private const val FACTS_HEADER = "Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):"
    const val INFERRED = "inferred"
    private const val INFERRED_MARK = " (your own earlier inference, saved at the user's request; the user did not say this)"
    private const val PROCEDURES_HEADER = "Ways of doing things the user approved (when they conflict with what the user says now, follow what they say now):"

    /**
     * [rewrite]: replaces old tool names mentioned in procedures with the current ones (see LegacyNames.rewrite);
     * left as-is by default.
     * [mode] defaults to keeping the old retrieval behavior; the product path (AgentLoop) passes [Mode.Auto]
     * explicitly.
     */
    fun build(
        items: List<MemoryItem>,
        goal: String,
        lastUserTurn: String?,
        rewrite: (String) -> String = { it },
        mode: Mode = Mode.Relevant,
    ): MemoryBlock {
        val active = items.filter { it.status == MemoryStatus.active }
        if (active.isEmpty()) return MemoryBlock(null, emptyList())
        if (mode == Mode.Full) full(active, rewrite)?.let { return it }
        // Auto: if it fits, give everything without dropping any; if not (memory has grown large), fall back to
        // retrieval instead of cramming tens of thousands of characters into the context
        if (mode == Mode.Auto) fullIfFits(active, rewrite)?.let { return it }

        val query = listOfNotNull(goal, lastUserTurn).joinToString("\n")
        val ranked = MemoryIndex(active).search(query, limit = active.size)
        val score = ranked.associate { it.first.id to it.second }

        // the procedure cap counts pinned ones too
        var procedures = 0
        fun procedureFits(m: MemoryItem) = m.kind != MemoryKind.procedure || procedures < MAX_PROCEDURES

        val pinned = mutableListOf<MemoryItem>()
        for (m in active.filter { it.pinned }
            .sortedWith(compareByDescending<MemoryItem> { score[it.id] ?: 0.0 }.thenByDescending { it.updatedAt })) {
            if (pinned.size == MAX_PINNED) break
            if (!procedureFits(m)) continue
            if (m.kind == MemoryKind.procedure) procedures++
            pinned += m
        }
        val relevant = mutableListOf<MemoryItem>()
        for ((item, s) in ranked) {
            if (s < MIN_SCORE || relevant.size == MAX_RELEVANT) break
            if (item.pinned || !procedureFits(item)) continue
            if (item.kind == MemoryKind.procedure) procedures++
            relevant += item
        }

        // keep priority from highest to lowest: pinned (by score) first, relevant (by score) after; drop from the end
        // when too long
        val keep = (pinned + relevant).toMutableList()
        while (keep.isNotEmpty()) {
            val (message, ids) = render(keep, rewrite)
            if (TokenEstimate.of(message) <= MAX_TOKENS) return MemoryBlock(message, ids)
            keep.removeAt(keep.lastIndex)
        }
        return MemoryBlock(null, emptyList())
    }

    /**
     * Full: pinned first, the rest in descending update time; at most [MAX_PROCEDURES] procedures.
     * When over [FULL_MAX_TOKENS], drop the oldest ordinary entries first, then procedures; if it is still over after
     * dropping everything, return null (hand over to retrieval).
     */
    private fun full(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (allFacts, allProcedures) = ordered(active)
        val facts = allFacts.toMutableList()
        val procedures = allProcedures.toMutableList()
        while (facts.isNotEmpty() || procedures.isNotEmpty()) {
            val (message, ids) = render(facts + procedures, rewrite)
            if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) return MemoryBlock(message, ids)
            if (facts.isNotEmpty()) facts.removeAt(facts.lastIndex) else procedures.removeAt(procedures.lastIndex)
        }
        return null
    }

    /**
     * Full injection only when it fits; returns null when it does not, and the caller falls back to retrieval.
     * Procedures are not cut either: truncating to the first few by update time would drop the one relevant to this
     * task (truncation by relevance happens only in retrieval).
     */
    private fun fullIfFits(active: List<MemoryItem>, rewrite: (String) -> String): MemoryBlock? {
        val (facts, procedures) = ordered(active, maxProcedures = Int.MAX_VALUE)
        val (message, ids) = render(facts + procedures, rewrite)
        return if (TokenEstimate.of(message) <= FULL_MAX_TOKENS) MemoryBlock(message, ids) else null
    }

    private fun ordered(active: List<MemoryItem>, maxProcedures: Int = MAX_PROCEDURES): Pair<List<MemoryItem>, List<MemoryItem>> {
        val sorted = active.sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })
        return sorted.filter { it.kind != MemoryKind.procedure } to
            sorted.filter { it.kind == MemoryKind.procedure }.take(maxProcedures)
    }

    private fun render(items: List<MemoryItem>, rewrite: (String) -> String): Pair<String, List<String>> {
        val (procedures, facts) = items.partition { it.kind == MemoryKind.procedure }
        val sections = listOf(FACTS_HEADER to facts, PROCEDURES_HEADER to procedures).filter { it.second.isNotEmpty() }
        val message = sections.joinToString("\n\n") { (header, list) ->
            header + "\n" + list.withIndex().joinToString("\n") { (i, m) ->
                "${i + 1}. ${if (m.kind == MemoryKind.procedure) rewrite(m.content) else m.content}" +
                    // The entries Claire inferred herself were not said by the user: this section's header says "things
                    // the user told you", so mark them one by one
                    if (m.source.type == INFERRED) INFERRED_MARK else ""
            }
        }
        return message to (facts + procedures).map { it.id }
    }
}
