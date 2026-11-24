package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Raises a hand when a task needs the phone but the built-in helper is not running: the conversation page shows a "go to settings" line below that round of Q&A.
 * Web and file tasks never reach here. Created once by [com.wangzan101.mika.cap.phone.PhoneServices] and shared by tasks and the conversation page.
 */
class PhoneSetupPrompt(context: Context) {
    /** The run that found the phone not ready: the conversation it belongs to and (when the run record can be opened) its id; the conversation page uses these to attach the prompt below that round. */
    data class Need(val conversationId: String?, val runId: String?)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _needed = MutableStateFlow<Need?>(null)
    val needed: StateFlow<Need?> = _needed.asStateFlow()

    fun request(conversationId: String?, runId: String?) {
        _needed.value = Need(conversationId, runId)
    }

    fun clear() {
        _needed.value = null
    }

    /** Enter the guide automatically on first launch; once seen (completed or dismissed with "later") it no longer opens automatically. */
    fun guideSeen(): Boolean = prefs.getBoolean(SEEN, false)

    fun markGuideSeen() {
        prefs.edit().putBoolean(SEEN, true).apply()
    }

    private companion object {
        const val PREFS = "phone_setup"
        const val SEEN = "guide_seen"
    }
}
