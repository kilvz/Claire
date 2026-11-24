package com.wangzan101.mika.core.screen.uistate

/**
 * Page information attached to ScreenSnapshot when reading the web screen; empty when reading the phone screen.
 * [truncated]: the page has more elements than the per-call limit, so the list was cut off in document order.
 */
data class WebInfo(
    val url: String,
    val title: String,
    val tabId: String,
    val httpStatus: Int?,
    val truncated: Boolean = false,
    /** Number of elements omitted because merging elements exceeded the budget. */
    val omitted: Int = 0,
    /** A dialog the page raised that is still waiting for an answer; while it exists the page script is blocked and the element table cannot be read. */
    val dialog: PageDialog? = null,
)

/**
 * A dialog raised by the page itself (window.alert/confirm/prompt, leave-page confirmation). [kind] is one of
 * [ALERT], [CONFIRM], [PROMPT], [BEFORE_UNLOAD]; [message] is the text the page supplies (page data, not instructions);
 * [defaultText] is the text pre-filled in a prompt dialog.
 */
data class PageDialog(val kind: String, val message: String, val defaultText: String? = null) {
    companion object {
        const val ALERT = "alert"
        const val CONFIRM = "confirm"
        const val PROMPT = "prompt"
        const val BEFORE_UNLOAD = "beforeunload"
        /** The text shown to the model and to the decision logic is at most this long. */
        const val MAX_MESSAGE = 500
    }
}
