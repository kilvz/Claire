package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.PageDialog

/** The reply to a page dialog: the browser waits for it before it lets the page run on. Each implementation is invoked only once. */
interface DialogReply {
    /** Accept; [text] is used only by prompt dialogs. */
    fun accept(text: String?)
    fun dismiss()
}

sealed interface DialogAnswer {
    /** No dialog is awaiting a reply right now. */
    data object NoDialog : DialogAnswer
    /** [textIgnored]: text was supplied, but this dialog is not a prompt, so the text was not used. */
    data class Answered(val dialog: PageDialog, val accepted: Boolean, val textIgnored: Boolean) : DialogAnswer
}

/**
 * A page dialog on one tab that waits for a reply (alert, confirm, prompt, leave-page confirmation).
 * When a dialog appears it is only recorded, not clicked on the user's behalf: it keeps hanging even while the tab is in the background or the model does not deal with it in this step, and the next screen read still shows it, until [answer] or [close].
 * Recording it occupies no thread: on the browser side only this page's scripts are paused, and the UI thread carries on as usual.
 * A page has only one dialog at a time; should another one arrive (from a different frame), the old one is answered as cancelled and only the new one is kept. Thread-safe.
 */
class PageDialogs {
    private var pending: Pair<PageDialog, DialogReply>? = null

    /** The dialog awaiting a reply right now; null if there is none. */
    val current: PageDialog? @Synchronized get() = pending?.first

    fun open(dialog: PageDialog, reply: DialogReply) {
        val old = synchronized(this) { pending.also { pending = dialog to reply } }
        old?.second?.dismiss()
    }

    /** Answer it and clear it; does nothing when no dialog is awaiting a reply. */
    fun answer(accept: Boolean, text: String?): DialogAnswer {
        val (dialog, reply) = synchronized(this) { pending.also { pending = null } } ?: return DialogAnswer.NoDialog
        val prompt = dialog.kind == PageDialog.PROMPT
        if (!accept) reply.dismiss()
        else reply.accept(if (prompt) text ?: dialog.defaultText.orEmpty() else null)
        return DialogAnswer.Answered(dialog, accept, textIgnored = text != null && (!prompt || !accept))
    }

    /** When the tab is closed or the page navigates away: answer as cancelled so the page does not stay stuck. */
    fun close() {
        val old = synchronized(this) { pending.also { pending = null } }
        old?.second?.dismiss()
    }
}
