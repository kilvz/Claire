package com.wangzan101.mika.proactive

/**
 * The "request slot" shared by triage and summarization: only one proactive background request is sent at a time.
 *
 * If each side tracked its own in-flight request, they would each send one at the same moment whenever their timers happen to coincide: that occupies an extra connection, and each side checks its budget separately. So whoever wants to send first looks at whether this slot
 * is held by the other side, and waits if it is; when the other side finishes (or is cancelled) it nudges the waiters to check again ([wakeOthers]) — nothing else will do the nudging.
 * No preemption: a request already in flight runs to completion. Whose turn it is after one request finishes: triage has priority (reminders are what the user sees) — when triage finishes it first checks whether it still has anything
 * to send, then wakes summarization; when summarization finishes it wakes triage first, then checks itself. If the slot is free and both sides come due at the same time, whoever sees it first sends first; no further ordering is arranged.
 *
 * Not thread-safe: it is used only on the single serial coroutine of ProactiveRuntime. When each side is used on its own (tests, or only one side enabled), each side has its own slot and they are independent of each other.
 */
internal class RequestSlot {
    private var holder: Any? = null
    private val wakers = LinkedHashMap<Any, () -> Unit>()

    /** The slot is held by someone else. */
    fun heldByOther(who: Any): Boolean = holder != null && holder !== who

    fun take(who: Any) {
        check(!heldByOther(who)) { "another background request is in flight" }
        holder = who
    }

    /** Release it (leaves it alone if this caller is not the holder). */
    fun release(who: Any) {
        if (holder === who) holder = null
    }

    /** Registers [wake]: after someone else releases the slot, [who] is nudged to check again. */
    fun listen(who: Any, wake: () -> Unit) {
        wakers[who] = wake
    }

    fun unlisten(who: Any) {
        wakers.remove(who)
    }

    /** Nudge every waiter other than [who] to check again. */
    fun wakeOthers(who: Any) {
        wakers.filterKeys { it !== who }.values.toList().forEach { it() }
    }
}
