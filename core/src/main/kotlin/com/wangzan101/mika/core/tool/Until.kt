package com.wangzan101.mika.core.tool

/**
 * How things must look after one step of a batch finishes and before the next step begins; when several are given, all must hold at once. The code only does literal comparisons and understands no meaning:
 * [appears] some element on screen has a name containing this text; [gone] no element on screen contains this text any more; [place] the foreground app package name (phone) or the page address/site (web) contains this text;
 * [stable] the screen settles and stops changing. Wait at most [timeoutMs].
 */
data class Until(
    val appears: String? = null,
    val gone: String? = null,
    val place: String? = null,
    val stable: Boolean = false,
    val timeoutMs: Long = DEFAULT_MS,
) {
    companion object {
        const val DEFAULT_MS = 8_000L
        const val MAX_MS = 20_000L
    }
}

/**
 * The outcome of the wait: [met] the condition holds; when it does not, [stopped] says why waiting ended — [TIMEOUT] the limit was reached, or an unexpected event spotted early:
 * [POPUP] the same app gained another window (dialog) or the page opened a popup, [SYSTEM_DIALOG] a window of another package covered ours (permission or system dialog),
 * [APP_SWITCHED] the foreground switched to another app, [NEW_MESSAGE] the user interjected. [waitedMs] is how long the wait took.
 */
data class UntilOutcome(val met: Boolean, val stopped: String?, val waitedMs: Long) {
    companion object {
        const val TIMEOUT = "timeout"
        const val POPUP = "popup"
        const val SYSTEM_DIALOG = "system_dialog"
        const val APP_SWITCHED = "app_switched"
        const val NEW_MESSAGE = "new_message"
    }
}
