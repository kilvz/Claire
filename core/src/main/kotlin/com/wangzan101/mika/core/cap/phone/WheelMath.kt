package com.wangzan101.mika.core.cap.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The algorithm for wheel pickers (time, date, region and similar one-cell-at-a-time pickers), independent of how they are turned: recognize the value, compute direction and step count, estimate cell height.
 * The port uses it to turn the wheel into place within a single tool call, so the model need not inspect it cell by cell.
 *
 * "Forward" means the wheel's value advances (the item below moves to the middle, and numbers usually increase); whether it actually increases is corrected from the values read back.
 */
object WheelMath {
    private val ONLY_NUMBER = Regex("""^-?\d{1,6}$""")
    private val LEADING_NUMBER = Regex("""^\D{0,3}?(\d{1,6})(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)\d{1,6}(?!\d)""")
    /** Punctuation that separates the value from status words ("selected", ...) and units within an item's text. */
    private val SEPARATORS = Regex("""[,，、:：;；|/()（）\[\]【】<>《》·•]+""")

    /** For comparing text: strip whitespace, convert full-width digits to half-width, ignore case. */
    fun normalize(s: String): String = buildString {
        for (c in s.trim()) when {
            c.isWhitespace() -> Unit
            c in '０'..'９' -> append('0' + (c - '０'))
            else -> append(c.lowercaseChar())
        }
    }

    /**
     * The integer in displayed text: "09" → 9, "9 h" → 9, "week 3" → 3, "year 2026" → 2026;
     * accessibility names often carry status words around the value ("Selected, 21", "21 (selected)"), and when the whole string holds only one number, that is it.
     * With several numbers, take the first one near the start (null when more than three characters precede it); null when there is no number.
     */
    fun number(s: String?): Int? {
        val t = s?.let(::normalize)?.ifEmpty { null } ?: return null
        if (ONLY_NUMBER.matches(t)) return t.toIntOrNull()
        DIGITS.findAll(t).toList().singleOrNull()?.let { return it.value.toIntOrNull() }
        return LEADING_NUMBER.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The segments split by punctuation (already passed through [normalize]): "Selected, 21" → [selected, 21]. */
    fun segments(s: String): List<String> = normalize(s).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * The value reported to the model: when the name carries a status word, keep only the value segment ("Selected, 21" → "21");
     * if there is not exactly one segment containing a number, return it as-is (for text values we do not guess which segment is the status word).
     */
    fun clean(label: String?): String? {
        val t = label?.trim()?.ifEmpty { null } ?: return null
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        return parts.filter { p -> p.any { it.isDigit() || it in '０'..'９' } }.singleOrNull() ?: t
    }

    /** The target itself is a number ("9", "09", or a full-width "2026"), not text that contains a number. */
    fun numericTarget(target: String): Int? = normalize(target).takeIf(ONLY_NUMBER::matches)?.toIntOrNull()

    /**
     * Whether the wheel's currently displayed [shown] is the wanted [target]: when the target is purely numeric, compare as numbers ("09" equals "9", "40 min" equals "40",
     * "Selected, 21" equals "21"); otherwise the text is identical, or the displayed text (or one of its punctuation-separated segments) starts/ends with the target ("Beijing City" for "Beijing").
     */
    fun matches(shown: String?, target: String): Boolean {
        val s = shown?.let(::normalize)?.ifEmpty { null } ?: return false
        val t = normalize(target).ifEmpty { return false }
        numericTarget(target)?.let { return number(s) == it }
        fun affix(x: String) = x == t || x.startsWith(t) || x.endsWith(t)
        return affix(s) || segments(s).any(::affix)
    }

    /**
     * Estimates the cycle length from the items visible top to bottom: when the numbers decrease exactly once going down (...58 59 00 01...), cycle length = max before the break − min after the break + 1;
     * null when no cycle is visible (all increasing, not numeric, or too few items).
     */
    fun modulus(visible: List<String>): Int? {
        val n = visible.map { number(it) ?: return null }
        if (n.size < 2) return null
        val drops = (1 until n.size).filter { n[it] < n[it - 1] }
        if (drops.size != 1) return null
        val i = drops.single()
        return (n.subList(0, i).max() - n.subList(i, n.size).min() + 1).takeIf { it >= 2 }
    }

    /** How many cells to move from [current] to [target] toward larger numbers (negative goes toward smaller): when the cycle length [modulus] is known, take the nearer direction. */
    fun distance(current: Int, target: Int, modulus: Int?): Int {
        val d = target - current
        if (modulus == null || modulus < 2) return d
        val m = ((d % modulus) + modulus) % modulus
        return if (m <= modulus / 2) m else m - modulus
    }

    /** How many cells the number moved from [before] to [after] (with a known cycle length, 59→0 is +1, not −59); null when not numeric. */
    fun moved(before: String?, after: String?, modulus: Int?): Int? {
        val b = number(before) ?: return null
        val a = number(after) ?: return null
        return distance(b, a, modulus)
    }

    /** How tall one cell is: the median of the gaps between adjacent items' centers; null with fewer than two items or when the gaps are too small. */
    fun itemHeight(centers: List<Int>): Int? {
        val sorted = centers.sorted()
        val gaps = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    /**
     * The selected item: if exactly one item is marked selected ([Item.selected], accessibility's isSelected), it is that one; when every item carries its own height,
     * it is the item whose vertical extent covers the center line [centerY] (-1 when none covers it: the middle cell was not reported, and the neighbours are not substituted for it);
     * otherwise find the one on the center line via [selected].
     */
    fun selectedIndex(items: List<Item>, centerY: Int, itemHeight: Int?): Int {
        items.indices.filter { items[it].selected }.singleOrNull()?.let { return it }
        if (items.isNotEmpty() && items.all { it.height > 0 }) {
            return items.indices.filter { abs(items[it].centerY - centerY) * 2 <= items[it].height }
                .minByOrNull { abs(items[it].centerY - centerY) } ?: -1
        }
        return selected(items.map { it.centerY }, centerY, itemHeight)
    }

    /**
     * The text of the middle cell when the cell on the center line was not reported (some wheels only report the two neighbours above and below) and both adjacent items are numbers:
     * a difference of 2 means the number in between; a bigger difference only counts across a cycle wrap (0 between 59 and 1, 23 between 22 and 0, 1 between 12 and 2,
     * 12 between 11 and 1, 0 between 1 and 59). With a 1–12 hour hand whose 12 cell is not reported the neighbours are 11 and 1, and it must not be inferred as 0 by "decrement the smaller side"
     * (0 is not on this wheel); reversed order (1 above, 11 below) is also 12. The spelling follows the upper item (units; if either neighbour is zero-padded, pad as well); null when it cannot be inferred.
     */
    fun between(above: String, below: String): String? {
        val a = number(above) ?: return null
        val b = number(below) ?: return null
        val mid = when {
            abs(b - a) == 2 -> (a + b) / 2
            // 1–12: between 11 and 1 is 12. This must precede "decrement the smaller side", otherwise it would infer 0
            (a == 11 && b == 1) || (a == 1 && b == 11) -> 12
            b < a && b <= 2 -> if (b >= 1) b - 1 else a + 1
            a < b && a <= 2 -> if (a >= 1) a - 1 else b + 1
            else -> return null
        }
        if (mid < 0) return null
        val token = DIGITS.find(above.trim())?.value ?: return null
        val other = DIGITS.find(below.trim())?.value.orEmpty()
        // A zero-padded wheel (either neighbour starts with 0, such as 09, 01) is padded to the same width
        val padded = listOf(token, other).any { it.length > 1 && it.startsWith('0') }
        return above.trim().replaceFirst(token, if (padded) mid.toString().padStart(token.length, '0') else mid.toString())
    }

    /**
     * Among the items top to bottom, the run that forms one column with the item at [anchor] and has similar spacing: titles and buttons above and below the wheel in the same block are excluded.
     * Cell height is computed from its spacing to the adjacent items (the smaller one). With fewer than three items, or when cell height cannot be estimated, returns them unchanged.
     */
    fun column(items: List<Item>, anchor: Int): List<Item> {
        if (items.size < 3 || anchor !in items.indices) return items
        val near = listOfNotNull(
            items.getOrNull(anchor - 1)?.let { items[anchor].centerY - it.centerY },
            items.getOrNull(anchor + 1)?.let { it.centerY - items[anchor].centerY },
        ).filter { it > 2 }
        val h = near.minOrNull() ?: return items
        fun even(gap: Int) = gap > h * 0.4 && gap < h * 1.8
        var lo = anchor
        var hi = anchor
        while (lo > 0 && even(items[lo].centerY - items[lo - 1].centerY)) lo--
        while (hi < items.lastIndex && even(items[hi + 1].centerY - items[hi].centerY)) hi++
        return items.subList(lo, hi + 1)
    }

    /** Index of the item on the center line [centerY]: the one whose center is closest to the line; -1 when it is more than most of a cell away (nothing is aligned with the center line). */
    fun selected(centers: List<Int>, centerY: Int, itemHeight: Int?): Int {
        val best = centers.indices.minByOrNull { abs(centers[it] - centerY) } ?: return -1
        if (itemHeight != null && abs(centers[best] - centerY) > itemHeight * 0.75) return -1
        return best
    }

    /**
     * How many cells to turn forward to get there (negative goes backward): when the target is among the visible items, compute the position difference (below is forward); when both the target and the selected value are numbers, compute the numeric difference
     * (with a visible cycle go the nearer side, otherwise use the previously observed [knownModulus]; when [increasingForward] is false, forward means decreasing);
     * null when neither works (text list, target not in view).
     */
    fun stepsTo(visible: List<String>, selectedIndex: Int, target: String, increasingForward: Boolean = true, knownModulus: Int? = null): Int? {
        if (selectedIndex !in visible.indices) return null
        visible.indexOfFirst { matches(it, target) }.takeIf { it >= 0 }?.let { return it - selectedIndex }
        val t = numericTarget(target) ?: return null
        val cur = number(visible[selectedIndex]) ?: return null
        val d = distance(cur, t, modulus(visible) ?: knownModulus)
        return if (increasingForward) d else -d
    }

    /**
     * The drag distance (pixels; positive means dragging the finger up = forward) needed to turn [steps] cells, one cell being [itemHeight]; a single drag does not exceed [maxDrag], and anything beyond is split into several drags.
     * Returns the distance of each drag.
     */
    fun drags(steps: Int, itemHeight: Int, maxDrag: Int): List<Int> {
        if (steps == 0 || itemHeight <= 0) return emptyList()
        val perDrag = (maxDrag / itemHeight).coerceAtLeast(1)
        val sign = if (steps > 0) 1 else -1
        val out = mutableListOf<Int>()
        var left = abs(steps)
        while (left > 0) {
            val n = minOf(left, perDrag)
            out += sign * n * itemHeight
            left -= n
        }
        return out
    }

    /** One visible item in the wheel: its text and center (pixels); [selected] is marked selected by accessibility; [height] is its own height (0 when unknown). */
    data class Item(val text: String, val centerX: Int, val centerY: Int, val selected: Boolean = false, val height: Int = 0)

    /**
     * Whether a vertically scrollable container looks like a wheel, preferring to miss one over misidentifying it: three to nine items, each a short value (at most [MAX_ITEM_CHARS] characters) and all distinct,
     * all centered horizontally in the container (deviating by less than 15% of the width), evenly spaced from top to bottom (the largest gap under 1.5× the smallest).
     * Left-aligned short labels like those on a settings page, and chat lists, do not look like one.
     */
    fun looksLikeWheel(items: List<Item>, left: Int, right: Int): Boolean {
        if (items.size !in 3..9 || right <= left) return false
        val texts = items.map { normalize(it.text) }
        if (texts.any { it.isEmpty() || it.length > MAX_ITEM_CHARS } || texts.toSet().size != texts.size) return false
        val mid = (left + right) / 2.0
        if (items.any { abs(it.centerX - mid) > (right - left) * 0.15 }) return false
        val ys = items.map { it.centerY }
        if (ys.zipWithNext().any { (a, b) -> b <= a }) return false
        val gaps = ys.zipWithNext { a, b -> b - a }
        return gaps.max() < gaps.min() * 1.5
    }

    private const val MAX_ITEM_CHARS = 8

    /** Dragging [dragged] pixels actually turned [moved] cells: how tall one cell really is; null when nothing turned. */
    fun calibrated(dragged: Int, moved: Int): Int? =
        if (moved == 0) null else abs((dragged.toDouble() / moved).roundToInt()).takeIf { it > 2 }
}

/**
 * The next step when turning a wheel via accessibility "scroll forward/backward": after each batch the values read back are fed in, and it says which way to scroll how many times, or to stop.
 * One action does not necessarily turn one cell (a list scrolls a whole page at a time), so everything is recomputed from the values read back after each batch:
 * - Numeric target: direction and action count come from the numeric difference; on the first read-back it checks whether "forward" increases or decreases the value; if the value stops changing, this side has hit its end and the target is out of range, so stop;
 *   if it keeps crossing the target back and forth (one action turns more than one cell, so it cannot stop precisely), it also stops and hands over to dragging for calibration.
 * - Text target: if in view, use the position difference; otherwise scan forward one action at a time, reverse when hitting the end, and stop once both ends have been reached.
 */
class WheelSteps(private val target: String, private val cap: Int = 80, private val burst: Int = 5) {
    sealed interface Step {
        data object Done : Step
        /** Scroll [count] times toward the [forward] side. */
        data class Act(val forward: Boolean, val count: Int) : Step
        /**
         * Stop: [reason] is at_end (hit the end; the target is not on this side), not_moving (a cycling wheel does not move when scrolled), cap (too many scrolls have been performed),
         * overshoot (cannot stop precisely), unknown_value (value cannot be read).
         */
        data class Stop(val reason: String) : Step
    }

    /** How many scroll actions have been performed so far. */
    var actions: Int = 0
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var increasingForward = true
    private var calibrated = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastAct: Step.Act? = null
    private var reversals = 0
    private var scanForward = true
    private var scanReversed = false

    /** After the last batch: the currently selected value [current] and the visible items [visible] (top to bottom). On the first call nothing has been scrolled yet. */
    fun next(current: String?, visible: List<String> = emptyList()): Step {
        if (WheelMath.matches(current, target)) return Step.Done
        if (current == null) return Step.Stop("unknown_value")
        WheelMath.modulus(visible)?.let { modulus = it }
        val prev = lastAct
        val unchanged = prev != null && current == last
        if (actions >= cap) return Step.Stop("cap")
        if (numeric != null && WheelMath.number(current) != null) {
            if (prev != null) {
                // The value did not move: a wheel with a visible cycle has no end, so this action simply had no effect; it is not the end of the range
                if (unchanged) return Step.Stop(if (modulus != null) "not_moving" else "at_end")
                val moved = WheelMath.moved(last, current, modulus)
                if (!calibrated && moved != null && moved != 0) {
                    calibrated = true
                    if ((moved > 0) != prev.forward) increasingForward = false
                }
            }
            val d = WheelMath.distance(WheelMath.number(current)!!, numeric, modulus).let { if (increasingForward) it else -it }
            val forward = d > 0
            val turned = prev != null && forward != prev.forward
            if (turned && ++reversals > 3) return Step.Stop("overshoot")
            // Just overshot the target and are heading back: go one action at a time
            val n = if (turned) 1 else minOf(abs(d), burst)
            return act(forward, n.coerceAtMost(cap - actions), current)
        }
        val sel = visible.indexOfFirst { it == current }
        val at = visible.indexOfFirst { WheelMath.matches(it, target) }
        if (sel >= 0 && at >= 0) return act(at > sel, minOf(abs(at - sel), burst, cap - actions), current)
        if (unchanged) {
            if (scanReversed) return Step.Stop("at_end")
            scanReversed = true
            scanForward = !scanForward
        }
        return act(scanForward, 1, current)
    }

    private fun act(forward: Boolean, count: Int, current: String?): Step {
        val a = Step.Act(forward, count.coerceAtLeast(1))
        last = current
        lastAct = a
        actions += a.count
        return a
    }
}

/**
 * The next step when turning a wheel by dragging (app-drawn wheels that cannot be scrolled via accessibility): after each drag the column read back is fed in, and it says how many more cells to drag, how to drag, or to stop.
 * - Step counts come from [WheelMath.stepsTo] (numbers by numeric difference, going the nearer side when a cycle is visible, remembering cycle lengths seen before); on the first turn it checks whether "dragging up"
 *   increases or decreases the value, and calibrates cell height from the number of cells actually turned.
 * - A drag during which the value does not move does not mean the end: at_end only counts when no cycle is visible and there are no more items toward the desired side; otherwise the action simply had no effect,
 *   so the next one changes speed (dragging farther when only one cell short), and [maxStill] consecutive no-move results report not_moving.
 * - Text lists with the target not in view: it scans one page of visible items at a time toward one side, reverses at the end, and stops once both ends have been reached.
 */
class WheelDrags(private val target: String, rowHeight: Int, private val maxMoves: Int = 5, private val maxStill: Int = 3) {
    sealed interface Step {
        data object Done : Step
        /** Drag over [steps] cells (positive = finger up = forward), with the distance multiplied by [boost]; [slow] drags slowly without a fling, otherwise it is a quick flick. */
        data class Drag(val steps: Int, val boost: Double, val slow: Boolean) : Step
        /** Stop: [reason] is at_end, not_moving, not_reached (several rounds and still not there, or the numeric math says it arrived but that value does not exist), unknown_value. */
        data class Stop(val reason: String) : Step
    }

    /** How tall one cell is (pixels): first estimated from the visible items, then calibrated from the number of cells actually turned. */
    var itemHeight: Int = rowHeight.coerceAtLeast(1)
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var started = false
    private var increasingForward = true
    private var directionKnown = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastDrag: Step.Drag? = null
    private var lastScanning = false
    private var scanForward = true
    private var scanReversed = false
    private var moves = 0
    private var still = 0

    /**
     * After the last drag: the column's items top to bottom [items], their centers' y coordinates [centers], the selected index [selected] (-1 when not found),
     * and the pixels the finger actually travelled [dragged] (positive upward; pass 0 on the first call, before any drag).
     */
    fun next(items: List<String>, selected: Int, centers: List<Int> = emptyList(), dragged: Int = 0): Step {
        val current = items.getOrNull(selected)
        if (WheelMath.matches(current, target)) return Step.Done
        if (items.isEmpty()) return Step.Stop("unknown_value")
        if (!started) {
            started = true
            WheelMath.itemHeight(centers)?.let { itemHeight = it }
        }
        WheelMath.modulus(items)?.let { modulus = it }
        val prev = lastDrag
        if (prev != null) {
            if (current != last) {
                still = 0
                moves++
                val moved = WheelMath.moved(last, current, modulus)
                if (moved != null && moved != 0) {
                    if (!directionKnown && numeric != null) {
                        directionKnown = true
                        if ((moved > 0) != (dragged > 0)) increasingForward = false
                    }
                    WheelMath.calibrated(dragged, moved)?.let { itemHeight = it }
                }
            } else if (atEnd(items, selected, forward = prev.steps > 0)) {
                if (!lastScanning || scanReversed) return Step.Stop("at_end")
                scanReversed = true
                scanForward = !scanForward
            } else {
                still++
            }
        }
        if (still >= maxStill) return Step.Stop("not_moving")
        if (moves >= maxMoves) return Step.Stop("not_reached")
        val planned = WheelMath.stepsTo(items, selected, target, increasingForward, modulus)
        // The numbers say we have arrived but it does not match: this value is not on the wheel (e.g. looking for 75 in 0–59)
        if (planned == 0) return Step.Stop("not_reached")
        lastScanning = planned == null
        val steps = planned ?: ((items.size - 1).coerceAtLeast(1) * if (scanForward) 1 else -1)
        last = current
        // The last action had no effect: retry at a different speed; when only one cell short the drag is short, so drag farther (large drags get no boost, lest they overshoot the moment they take effect and skew calibration)
        val boost = if (abs(steps) <= 1) 1.0 + BOOST * still else 1.0
        return Step.Drag(steps, boost, slow = still % 2 == 0).also { lastDrag = it }
    }

    /** No cycle is visible, and there are no more items toward the desired side (forward is downward). */
    private fun atEnd(items: List<String>, selected: Int, forward: Boolean): Boolean {
        if (modulus != null || items.size < 2 || selected !in items.indices) return false
        return if (forward) selected == items.lastIndex else selected == 0
    }

    private companion object {
        const val BOOST = 0.6
    }
}
