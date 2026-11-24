package com.wangzan101.mika.cap.web

/**
 * Upper limit shared by the web tabs of all tasks in the process (at most [max] in total): each task has one tab pool, and each pool joins as a [Member].
 * "Recently used" uses the same clock here, so the least recently used tab can be compared across tasks. Member methods are all called on the main thread.
 */
class TabBudget(val max: Int = DEFAULT_MAX) {
    interface Member {
        fun tabCount(): Int
        /** The least recently used tab that can be closed (id, time last used); locked ones (being taken over) and [except] do not count. Null if there is none. */
        fun oldestSpare(except: String?): Pair<String, Long>?
        /** Close it because of the global limit (emits a "limit" event). */
        fun evictForBudget(tabId: String)
    }

    private val members = mutableListOf<Member>()
    private var clock = 0L

    @Synchronized fun tick(): Long = ++clock

    @Synchronized fun join(m: Member) {
        if (members.none { it === m }) members += m
    }

    @Synchronized fun leave(m: Member) {
        members.removeAll { it === m }
    }

    fun total(): Int = snapshot().sumOf { it.tabCount() }

    fun full(): Boolean = total() >= max

    /** Closes the least recently used closable tab across all tasks; returns false if there is nothing to close. */
    fun evictOldest(except: String?): Boolean {
        val victim = snapshot().mapNotNull { m -> m.oldestSpare(except)?.let { m to it } }.minByOrNull { it.second.second } ?: return false
        victim.first.evictForBudget(victim.second.first)
        return true
    }

    @Synchronized private fun snapshot() = members.toList()

    companion object {
        const val DEFAULT_MAX = 8
    }
}
