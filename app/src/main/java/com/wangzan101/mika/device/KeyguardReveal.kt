package com.wangzan101.mika.device

/** A biometric-first lock screen (with neither a password field nor a numeric keypad) needs one fallback tap on "unlock with password". */
internal object KeyguardReveal {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height.toLong()
    }

    data class Candidate(val bounds: Bounds, val clickable: Boolean, val digit: Boolean)

    /**
     * Picks from the SystemUI window candidates the large-area click target most likely to "switch to credential input":
     * digit keys and non-clickable nodes are excluded, only large targets covering at least [minAreaFraction] of the window are kept, and the largest of them is chosen.
     * Large buttons such as "Use password" get selected, while small text links such as "Cancel" and "Emergency call" are excluded.
     * Returns null when no target qualifies — better to leave it alone than to tap blindly.
     */
    fun fallbackTarget(candidates: List<Candidate>, window: Bounds, minAreaFraction: Float): Bounds? {
        if (!valid(window)) return null
        val minArea = window.area * minAreaFraction
        return candidates
            .filter { it.clickable && !it.digit && valid(it.bounds) && inside(it.bounds, window) }
            .filter { it.bounds.area >= minArea }
            .maxByOrNull { it.bounds.area }
            ?.bounds
    }

    fun center(bounds: Bounds): Pair<Int, Int> = (bounds.left + bounds.right) / 2 to (bounds.top + bounds.bottom) / 2

    private fun valid(b: Bounds): Boolean = b.right > b.left && b.bottom > b.top

    private fun inside(b: Bounds, w: Bounds): Boolean = b.left >= w.left && b.top >= w.top &&
        b.right <= w.right && b.bottom <= w.bottom
}
