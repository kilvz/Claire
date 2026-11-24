package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.Verb

/**
 * Detection of item-by-item work: when the motif "open details → action → back" in the recent verb sequence repeats
 * completely [REPEATS] times, remind once to use do_each.
 * Only the shape is looked at, not the content; failures and non-screen verbs break the sequence; once the model
 * uses do_each itself the detector is disarmed. A pure state machine, one instance per task.
 */
internal class EachLoopDetector {
    private val seq = ArrayDeque<Verb>()
    private var fired = false
    private var disarmed = false

    fun reset() = seq.clear()

    /** Feed the verbs of one round ([ok] is whether that round succeeded); returns true when it should remind (at most once per task). */
    fun feed(verb: Verb?, ok: Boolean): Boolean {
        if (verb == Verb.DoEach) disarmed = true
        if (verb == null || !ok || verb !in COUNTED) { reset(); return false }
        seq.addLast(verb)
        while (seq.size > WINDOW) seq.removeFirst()
        if (fired || disarmed) return false
        if (!repeating()) return false
        fired = true
        return true
    }

    /** The most recent [REPEATS]×l verbs have period l, and the motif contains exactly one Back and at least one Tap (the signature of open details → action → back). */
    private fun repeating(): Boolean {
        for (l in 2..MAX_MOTIF) {
            if (seq.size < REPEATS * l) continue
            val window = seq.toList().takeLast(REPEATS * l)
            val motif = window.take(l)
            if (window.chunked(l).any { it != motif }) continue
            if (motif.count { it == Verb.Back } == 1 && Verb.Tap in motif) return true
        }
        return false
    }

    companion object {
        const val REPEATS = 3
        const val MAX_MOTIF = 5
        const val WINDOW = REPEATS * MAX_MOTIF
        private val COUNTED = setOf(
            Verb.Observe, Verb.ListElements, Verb.Tap, Verb.TypeText, Verb.ClearText,
            Verb.Scroll, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.Wait, Verb.WaitFor,
        )
    }
}
