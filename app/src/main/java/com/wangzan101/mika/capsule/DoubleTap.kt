package com.wangzan101.mika.capsule

/**
 * Single and double taps on the capsule (pure logic; clock, position, and input source come from the caller): a single tap expands the card, a double tap captures the screen into the card preview.
 * Pressing once ([tap]) is not yet counted as a single tap: a second tap from the same input source within [slopPx] and within [timeoutMs] makes it a double tap;
 * once the timeout passes without a second tap ([settle]) it is a single tap. A distant position, a different source, or a clock that goes backwards makes this one a new first tap.
 */
class DoubleTap(val timeoutMs: Long, private val slopPx: Float) {
    enum class Tap {
        /** The first tap: wait [timeoutMs] and then [settle]. */
        First,
        Double,
    }

    private data class Point(val at: Long, val x: Float, val y: Float, val source: Int)

    private var first: Point? = null

    fun tap(now: Long, x: Float, y: Float, source: Int): Tap {
        val previous = first
        val elapsed = previous?.let { now - it.at }
        val samePlace = previous != null && run {
            val dx = x - previous.x
            val dy = y - previous.y
            dx * dx + dy * dy <= slopPx * slopPx
        }
        return if (previous != null && elapsed != null && elapsed in 0..timeoutMs && samePlace && source == previous.source) {
            first = null
            Tap.Double
        } else {
            first = Point(now, x, y, source)
            Tap.First
        }
    }

    /** The timeout after the first tap has passed: if it is still waiting for a second tap it is a single tap (returns true, only once); false when it already became a double tap or the timeout has not passed. */
    fun settle(now: Long): Boolean {
        val previous = first ?: return false
        if (now - previous.at < timeoutMs) return false
        first = null
        return true
    }

    /** Stop waiting (clearing the note, dragging, long press, the capsule collapsing or turning into something else). */
    fun reset() {
        first = null
    }
}
