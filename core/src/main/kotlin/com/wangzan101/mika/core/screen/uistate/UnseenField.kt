package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.prompt.Feedback

/**
 * Typing into an input field that is invisible in the tree (the app draws its own UI, so the tree has no field, but it may still hold focus):
 * the text is pasted in via the clipboard; the text in the field cannot be read back, so the only check is comparing the on-screen text (OCR) before and after typing.
 * The keyboard does not need to be up: the select-all and paste keys go to the window on this screen that holds focus, and a focused field pastes without an IME.
 */
object UnseenField {
    private const val NO_FOCUS = "no_focus"
    /** Typed at a point and nothing on screen changed after pasting: that point is not a field that accepts typing. */
    const val POINT_NOT_TYPED = "point_not_typed"

    /**
     * What to report after pasting. [keyboardUp]: an IME was up on this screen before pasting (focus really is on some field).
     * If no IME was up and the on-screen text did not change at all (MISMATCH): focus is most likely not on a field, so report no_focus and ask the model to tap the field first instead of blindly re-pasting;
     * when the IME was up, hand back the verification result as usual (a failed insertion is reported by the input tool).
     * [atPoint]: the point given by the model was tapped before pasting; if nothing changed at all there, that point did not take the text (whether or not the IME was up), so report [POINT_NOT_TYPED] and ask the model to find the field again, with no automatic retry.
     */
    fun typed(
        text: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean, keyboardUp: Boolean, atPoint: Boolean = false,
    ): ActionResult {
        val v = verify(text, before, after, changed)
        if (v == Verification.MISMATCH && atPoint) return ActionResult.Failed(POINT_NOT_TYPED, Feedback["type.point_not_typed"])
        if (v == Verification.MISMATCH && !keyboardUp) return ActionResult.Failed(NO_FOCUS, Feedback["type.unseen_not_focused"])
        return ActionResult.Ok(changed = changed, verification = v, hint = Feedback["type.unseen_pasted"])
    }

    /** What to report after select-all plus the delete key: with no IME up and no UI change this is the same as [typed], asking the model to tap the field first; otherwise hand the result back as-is, with a hint on how the clearing was done. [atPoint] as in [typed]. */
    fun cleared(r: ActionResult, keyboardUp: Boolean, atPoint: Boolean = false): ActionResult = when {
        r !is ActionResult.Ok -> r
        r.changed == false && atPoint -> ActionResult.Failed(POINT_NOT_TYPED, Feedback["clear.point_not_cleared"])
        r.changed == false && !keyboardUp -> ActionResult.Failed(NO_FOCUS, Feedback["clear.unseen_not_focused"])
        else -> r.copy(hint = Feedback["clear.unseen_cleared"])
    }

    /** Tapping that point switched to [pkg] (a link or button was hit): nothing was typed, so ask the model to go back and find the field again. */
    fun tapSwitched(pkg: String): ActionResult = ActionResult.Failed(POINT_NOT_TYPED, Feedback.format("type.point_switched", "pkg" to pkg))

    /** When the text to verify (after stripping whitespace and punctuation) is longer than this, only the first and last [PROBE] characters are checked: OCR often misreads a few characters in the middle of long sentences. */
    private const val LONG = 16
    private const val PROBE = 6

    /**
     * Verification result: [Verification.MATCH] when this text appears one more time on screen after typing than before; MISMATCH (nothing was inserted) when text was read on both sides, it is identical, and neither the UI nor the visuals changed;
     * anything else (text unreadable on either read, or something changed but the text is not found) is UNKNOWN, with no guessing. [changed]: the UI or visuals changed between before and after typing.
     */
    fun verify(typed: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean): Verification {
        val want = key(typed)
        if (after != null && before != null && want.isNotEmpty()) {
            val probes = if (want.length <= LONG) listOf(want) else listOf(want.take(PROBE), want.takeLast(PROBE))
            val a = joined(after); val b = joined(before)
            if (probes.all { count(a, it) > count(b, it) }) return Verification.MATCH
        }
        if (!changed && after != null && before != null && joined(after) == joined(before)) return Verification.MISMATCH
        return Verification.UNKNOWN
    }

    /** Drops lines whose center falls inside [box] (0–999 [l,t,r,b], e.g. the IME window): the keys and candidate words on the keyboard do not count as text in the field. */
    fun outside(lines: List<VisibleTextLine>, box: List<Int>?): List<VisibleTextLine> {
        if (box == null || box.size != 4) return lines
        return lines.filterNot { l ->
            l.bounds.size == 4 && (l.bounds[0] + l.bounds[2]) / 2 in box[0]..box[2] && (l.bounds[1] + l.bounds[3]) / 2 in box[1]..box[3]
        }
    }

    /** Joins top-to-bottom, then left-to-right; OCR splits one sentence into several lines and misreads punctuation and spaces, so only letters, digits and Han characters are compared. */
    private fun joined(lines: List<VisibleTextLine>): String =
        lines.sortedWith(compareBy({ it.bounds.getOrElse(1) { 0 } }, { it.bounds.getOrElse(0) { 0 } })).joinToString("") { key(it.text) }

    private fun key(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }
}
