package com.wangzan101.mika.core.frontdesk

/**
 * Verification of the user's original words (words) in front-desk tools, supplied by the entry point (chat page, call). The passage the front desk gives is used only for verification: it must fall inside a sentence the user actually said,
 * and the whole sentence is what goes to the background — picking a fragment cannot change the user's meaning ("don't delete those photos" picked as "delete those photos" still hands over the whole sentence).
 * One message can do several things: each task carries the whole sentence, and the front desk's titles and notes say which part each handles.
 */
interface DeskWords {
    /** Returns the whole sentence the user said that contains [fragment] (this turn or earlier); null when none matches. Used for dispatch, adding and answering. */
    fun said(fragment: String): String?

    /** Accepts only this turn: returns the whole sentence when [fragment] falls inside it, otherwise null. Used for cancelling: stopping a task must be what the user wants now. */
    fun saidNow(fragment: String): String?

    companion object {
        /** This turn did not start with the user (the front desk reported): nothing is accepted, so earlier user words cannot dispatch, add, answer or cancel. */
        val None: DeskWords = object : DeskWords {
            override fun said(fragment: String): String? = null
            override fun saidNow(fragment: String): String? = null
        }
    }
}
