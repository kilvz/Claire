package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The page being viewed on the browser page is lent to a task (the user asks Claire on this page): as soon as the user asks, the page is first "promised" to that conversation ([offer]);
 * when that conversation's task starts, it comes to take the page ([claim]); from then on this tab is the task's current web tab and the task side hooks up ([bind]).
 * Only one loan exists at a time (there is only one visible page). Life and death still belong to the browser page:
 * - The task ends ([end]): the page is given back, and it stays in front of the user;
 * - The visible tab changes ([swapped], the page opened or closed a popup window): the loan follows to the visible one;
 * - The user closes the page ([closing]): the page is handed to the task to keep using (if accepted, the browser page no longer destroys it); only when it is not accepted does the browser page close it;
 * - The user touches the page ([touched]): the task is told (and pauses accordingly).
 * The task side is [Borrower]. [T] is the tab (WebTab in the UI); tests substitute something else. Only call on the main thread.
 */
class PageLoans<T : Any> {
    /** What the task side that borrowed a tab has to do. */
    interface Borrower<T : Any> {
        /** Use it as the current tab (take the loan). Returns false when it cannot be taken. */
        fun take(tab: T): Boolean
        /** Give it back (stop using it). */
        fun giveBack(tab: T)
        /** The loan was replaced by [next]. */
        fun swap(prev: T, next: T)
        /** The user closed the page: adopt [tab] as its own tab and keep using it; returns false when it cannot adopt it. */
        fun adopt(tab: T): Boolean
        /** The user touched this page themselves. */
        fun touched()
    }

    /** One loan: the task [runKey] running in conversation [conversationId] has borrowed [tab]; [touches] counts how many times the user touched it while it is lent. */
    class Loan<T : Any> internal constructor(val conversationId: String, val runKey: String, tab: T) {
        var tab: T = tab
            internal set
        var touches: Int = 0
            internal set
        internal var borrower: Borrower<T>? = null
    }

    private var offered: String? = null
    private var loan: Loan<T>? = null

    private val _lentTo = MutableStateFlow<String?>(null)
    /** The conversation currently borrowing the page; null when nothing is lent. */
    val lentTo: StateFlow<String?> = _lentTo.asStateFlow()

    /** The loan currently out; null when there is none. */
    val current: Loan<T>? get() = loan

    /** The user asked on the page, so the page is promised to the next task of conversation [conversationId] (replacing an earlier promise). */
    fun offer(conversationId: String) {
        offered = conversationId
    }

    /** The conversation that was promised the page and has not taken it yet. */
    val offeredTo: String? get() = offered

    /** The page is gone, so the promise is void. */
    fun withdraw() {
        offered = null
    }

    /**
     * Taken when task [runKey] of conversation [conversationId] starts: the page is lent when it was promised to this conversation, a visible page [tab] exists right now, and it is not lent to anyone else;
     * the promise is used up accordingly. Otherwise null.
     */
    fun claim(conversationId: String, runKey: String, tab: T?): Loan<T>? {
        if (offered != conversationId || tab == null || loan != null) return null
        offered = null
        return Loan(conversationId, runKey, tab).also {
            loan = it
            _lentTo.value = conversationId
        }
    }

    /**
     * The task side is ready: hooks up [borrower] and takes the current tab into the loan. Returns false when the loan has already been reclaimed (the page was closed before this) or when it cannot be taken;
     * a loan that cannot be taken is voided accordingly.
     */
    fun bind(l: Loan<T>, borrower: Borrower<T>): Boolean {
        if (loan !== l) return false
        if (!borrower.take(l.tab)) {
            clear()
            return false
        }
        l.borrower = borrower
        return true
    }

    /** Task [runKey] finished: the loan is given back. Loans borrowed by others are left alone. */
    fun end(runKey: String) {
        val l = loan?.takeIf { it.runKey == runKey } ?: return
        clear()
        l.borrower?.giveBack(l.tab)
    }

    /** The visible tab changed from [prev] to [next]: if the loan is for [prev], it follows. */
    fun swapped(prev: T, next: T) {
        val l = loan?.takeIf { it.tab == prev } ?: return
        l.tab = next
        l.borrower?.swap(prev, next)
    }

    /**
     * The browser page is about to close tab [tab]: returns true when the task that borrowed it adopted it (the caller no longer destroys it or cares about it), otherwise false.
     * Whether adopted or not, this loan ends; a promise that has not been taken is voided as well.
     */
    fun closing(tab: T): Boolean {
        offered = null
        val l = loan?.takeIf { it.tab == tab } ?: return false
        clear()
        val b = l.borrower ?: return false
        if (b.adopt(tab)) return true
        b.giveBack(tab)
        return false
    }

    /** The user touched the visible tab [tab] themselves: the task borrowing it is told. */
    fun touched(tab: T) {
        val l = loan?.takeIf { it.tab == tab } ?: return
        l.touches++
        l.borrower?.touched()
    }

    private fun clear() {
        loan = null
        _lentTo.value = null
    }
}
