package com.wangzan101.mika.voice

import kotlin.math.abs
import kotlin.math.floor

/**
 * When an utterance runs long and the recognition service only decodes the most recent stretch (the decoding window), stitches the successive intermediate results into the full text so far. Pure logic.
 *
 * [carried] is the text already kept, [tail] the stretch still changing; the UI shows the two directly joined. When a new result slides the window further along:
 * 1. First find where the start of the new result falls within the displayed text: compare letters and digits only (punctuation, spaces and case do not count),
 *    align the first [LOOK] characters of the new result at each position and take the position with the most matches (a few characters may have been rewritten); on a tie, take the one closest to the position estimated from timing;
 *    fewer than half of them matching, or fewer than 3, counts as not found, and it falls back to the proportional time estimate.
 * 2. Within the overlapping stretch, the first [HOLD] characters keep the displayed text: the start of the new result is the very front of the window, where the preceding context was cut off, so that part is most prone to mis-decoding and duplication;
 *    the same stretch in the previous result had context before it and is more trustworthy. Only when the two agree on [RUN] consecutive characters further along is the new result adopted
 *    (the two may be offset by up to [SLACK] characters; never switching in the middle of an English word or number). If nothing matches within [REACH] characters, the window's start is replaced wholesale by the new result.
 *
 * Used only for the preview while holding; the final text does not go through here.
 */
internal class PartialStitch {
    /** The text already kept: normally only appended to; when the new result does not line up with what is displayed, or no matching place is found in the overlap, it falls back to the window's start. */
    var carried = ""
        private set

    /** The stretch still changing. */
    var tail = ""
        private set

    /** Where the window start of the previous result falls within [carried] + [tail]. */
    private var anchor = 0
    private var lastStart = 0L
    private var lastDuration = 0L
    private var seen = false

    /** A different utterance started (the previous one is fully settled): start over without aligning to the previous one. */
    fun reset() {
        carried = ""; tail = ""; anchor = 0; lastStart = 0; lastDuration = 0; seen = false
    }

    /** One intermediate result [partial] whose window runs from [startMs] to [durationMs] (missing values reuse the previous result's); empty ones are ignored. */
    fun feed(partial: String, startMs: Long?, durationMs: Long?) {
        if (partial.isEmpty()) return
        val start = startMs ?: lastStart
        if (seen && (start > lastStart || carried.isNotEmpty())) merge(partial, start)
        else { tail = partial; anchor = carried.length }
        seen = true
        lastStart = start
        lastDuration = durationMs ?: lastDuration
    }

    private fun merge(next: String, start: Long) {
        val shown = carried + tail
        val old = letters(shown)
        val new = letters(next)
        val from = old.indexOfFirst { it.index >= anchor }.let { if (it < 0) old.size else it }
        val span = lastDuration - lastStart
        val estimate = if (span > 0) from + (old.size - from) * ((start - lastStart).toDouble() / span) else from.toDouble()

        var at = -1
        var atScore = 0
        var atDistance = 0.0
        val look = minOf(LOOK, new.size)
        for (k in from until old.size) {
            val n = minOf(look, old.size - k)
            if (n < 2) break
            var score = 0
            for (i in 0 until n) if (old[k + i].char == new[i].char) score++
            if (score < minOf(3, n) || 2 * score < n) continue
            val distance = abs(k - estimate)
            if (at < 0 || score > atScore || (score == atScore && distance < atDistance)) { at = k; atScore = score; atDistance = distance }
        }
        if (at < 0) {
            // No match: estimate the window start from the time proportion
            val k = floor(estimate + 0.5).toInt().coerceIn(from, old.size)
            val cut = if (k < old.size) old[k].index else shown.length
            carried = shown.substring(0, cut); tail = next; anchor = cut
            return
        }
        val cut = old[at].index
        for (j in HOLD until HOLD + REACH) {
            for (shift in SHIFTS) {
                val i = at + j + shift
                if (i < at || i + RUN > old.size || j + RUN > new.size) continue
                if ((0 until RUN).any { old[i + it].char != new[j + it].char }) continue
                if (midWord(shown, old[i].index) || midWord(next, new[j].index)) continue
                carried = shown.substring(0, old[i].index); tail = next.substring(new[j].index); anchor = cut
                return
            }
        }
        carried = shown.substring(0, cut); tail = next; anchor = cut
    }

    private class Letter(val index: Int, val char: Char)

    private fun letters(text: String): List<Letter> =
        text.withIndex().filter { it.value.isLetterOrDigit() }.map { Letter(it.index, it.value.lowercaseChar()) }

    /** Whether [index] is in the middle of an English word or number (the previous character is also a Latin letter or digit). */
    private fun midWord(text: String, index: Int): Boolean = index > 0 && latin(text[index]) && latin(text[index - 1])

    private fun latin(c: Char) = c.code < 128 && c.isLetterOrDigit()

    companion object {
        /** How many characters from the start of the new result are used to locate the position. */
        const val LOOK = 12

        /** How many characters at the start of the overlapping stretch keep the displayed text. */
        const val HOLD = 8

        /** Within this many characters after that, look for a matching passage between the two. */
        const val REACH = 12

        /** Switch only after this many consecutive matching characters. */
        const val RUN = 2

        /** The two may be offset by at most this many characters. */
        const val SLACK = 2

        private val SHIFTS = (-SLACK..SLACK).sortedBy { abs(it) }
    }
}
