package com.wangzan101.mika.capsule

import com.wangzan101.mika.cap.phone.a11y.OverlayArea
import kotlin.math.roundToInt

enum class DockSide { Left, Right }

/** Where the capsule is docked: [side] is the edge it hugs, [fraction] is its height as a ratio of the placement range (0 topmost, 1 bottom-most); stored as a ratio so it is placed back in the same spot after rotation. */
data class CapsuleDock(val side: DockSide = DockSide.Right, val fraction: Float = 0f)

/**
 * The range in which the capsule window can be placed, all in window coordinates (pixels; the window includes the margin around it reserved for the shadow): when docked, the window's left edge is at [left], or its right edge at [right];
 * vertically between [top]..[bottom] (avoiding the status bar, the notch and the bottom gesture bar). While dragging, the window can go horizontally all the way to [edgeLeft]..[edgeRight]
 * (so the visible capsule touches the screen edge).
 */
data class DockArea(
    val width: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val edgeLeft: Int, val edgeRight: Int,
) {
    /**
     * When the IME is shown (its top at [imeTop], in screen coordinates) the placement range shrinks to end above it, with the visible part [gap] away from it; [room] is the shadow margin.
     * Unchanged when the IME is not shown ([imeTop] is null).
     */
    fun above(imeTop: Int?, gap: Int, room: Int): DockArea =
        if (imeTop == null) this else copy(bottom = minOf(bottom, imeTop - gap + room).coerceAtLeast(top))

    companion object {
        /**
         * Computed from the screen and the system bars: when docked, the visible capsule is [margin] away from the screen's left/right edges and [gap] away from the status bar and the bottom gesture bar;
         * [room] is the ring of margin inside the window reserved for the shadow.
         */
        fun of(area: OverlayArea, margin: Int, gap: Int, room: Int) = DockArea(
            width = area.width,
            left = area.insetLeft + margin - room,
            top = area.insetTop + gap - room,
            right = area.width - area.insetRight - margin + room,
            bottom = area.height - area.insetBottom - gap + room,
            edgeLeft = area.insetLeft - room,
            edgeRight = area.width - area.insetRight + room,
        )
    }
}

/** Algorithms for dragging, edge snapping and placing the height by ratio (pure functions, in pixels). */
object CapsuleDocking {
    /** The top edge when a window of height [h] is placed at [fraction]. */
    fun y(fraction: Float, area: DockArea, h: Int): Int = area.top + (fraction.coerceIn(0f, 1f) * range(area, h)).roundToInt()

    /**
     * The top edge used to place the card during this session of the IME being shown: the smaller the top edge, the taller the keyboard (a pinyin candidate row, an extra toolbar row).
     * When it grows taller, follow immediately so the card's bottom edge is not covered by the IME; when it shrinks, follow only if it shrinks by more than [jump] at once (such as switching to an emoji panel),
     * and do not follow a candidate row appearing or disappearing, so the card does not keep growing and shrinking. Cleared when the IME is hidden ([top] is null) and re-recorded next time.
     */
    fun tallestImeTop(seen: Int?, top: Int?, jump: Int): Int? = when {
        top == null -> null
        seen == null -> top
        top <= seen -> top
        top - seen > jump -> top
        else -> seen
    }

    /** The height ratio for a top edge at [y]. */
    fun fraction(y: Int, area: DockArea, h: Int): Float {
        val r = range(area, h)
        return if (r == 0) 0f else ((y - area.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** If a window of height [h] (the card is taller than the capsule) does not fit downwards from [y], move it up, without crossing the status bar or the bottom. */
    fun fitY(y: Int, area: DockArea, h: Int): Int = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))

    /** The window's left edge during dragging: at most far enough for the visible capsule to touch the screen edge. */
    fun dragX(x: Int, area: DockArea, w: Int): Int = x.coerceIn(area.edgeLeft, maxOf(area.edgeLeft, area.edgeRight - w))

    /** The window's left edge when docked on [side]. */
    fun dockedX(side: DockSide, area: DockArea, w: Int): Int = if (side == DockSide.Left) area.left else area.right - w

    /** The distance to that edge when docked on [side] (the x when the window is aligned by its left or right edge). */
    fun edgeOffset(side: DockSide, area: DockArea): Int = if (side == DockSide.Left) area.left else area.width - area.right

    /**
     * Release: [x] is the window's left edge, [y] its top edge. If the center line is in the left half of the screen, dock left; otherwise dock right.
     * The height ratio is always recorded against [fractionArea], the range not shrunk for the IME; [area] is the range actually draggable at this moment (possibly already shrunk above the IME).
     * This way, when a later place also restores against the full area, the first height obtained is exactly this release height, and clamping it to the current IME range afterwards cannot shift it a second time.
     */
    fun release(x: Int, y: Int, w: Int, h: Int, area: DockArea, fractionArea: DockArea = area): CapsuleDock {
        val side = if (x + w / 2 < area.width / 2) DockSide.Left else DockSide.Right
        return CapsuleDock(side, fraction(fitY(y, area, h), fractionArea, h))
    }

    private fun range(area: DockArea, h: Int) = maxOf(0, area.bottom - area.top - h)
}

/**
 * Where the expanded card is placed and how tall it is (pure functions, in pixels): the card is wide, so it is centered horizontally and only dragged vertically. A conversation card is a fixed [maxHeight] tall; a task card is as tall as its content,
 * at most [maxHeight] as well, with anything beyond scrolling inside. The position is recorded by the card's top edge: its ratio within the placement range ([release]), recorded separately from the capsule;
 * the range the top edge can occupy is computed for the tallest card (window height [full], i.e. [maxHeight] plus the top and bottom shadow margins), so cards of different heights align by the same top ratio,
 * and swapping cards keeps the top edge in place while the card grows or shrinks downwards; when it does not fit downwards (bottom gesture bar, IME) it moves up just enough to fit. Restored by ratio after rotation.
 */
object CardDocking {
    /** The maximum fraction of the screen height the card may occupy. */
    const val SHARE = 0.58f
    /** The card's top ratio when nothing has been recorded: the tallest card placed in the middle of the placement range. */
    const val DEFAULT_FRACTION = 0.5f

    /**
     * How tall the visible card may be at most (which is how tall a conversation card is): [SHARE] of the screen height [screenHeight], but no taller than fits inside [area]
     * (reserving the shadow margin [room] at top and bottom); when the IME is shown [area] has already shrunk to end above it, so the card becomes shorter along with it, the middle section shrinks, and the input field stays visible.
     */
    fun maxHeight(screenHeight: Int, area: DockArea, room: Int): Int =
        minOf((screenHeight * SHARE).roundToInt(), area.bottom - area.top - 2 * room).coerceAtLeast(0)

    /**
     * The top edge of a card window of height [h] placed at ratio [fraction]: first determine the top edge within [base], the range not shrunk for the IME, using the tallest card (window height [full]),
     * then move it into the current range [area] (upwards if it does not fit downwards).
     */
    fun y(fraction: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(base.top + (fraction.coerceIn(0f, 1f) * topRange(base, full)).roundToInt(), area, h)

    /** The window's top edge while dragging by the title bar: the finger moved by [dy]; the top edge does not leave the range available in [base], and the whole card does not leave [area]. */
    fun dragY(startY: Int, dy: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(minOf(startY + dy.roundToInt(), base.top + topRange(base, full)), area, h)

    /** The top ratio recorded on release (computed against [base], the range not shrunk for the IME, so it stays put once the IME is hidden). */
    fun release(y: Int, base: DockArea, full: Int): Float {
        val r = topRange(base, full)
        return if (r == 0) 0f else ((y - base.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** How far the top edge can move: the tallest card from top to bottom. */
    private fun topRange(base: DockArea, full: Int) = maxOf(0, base.bottom - base.top - full)
}
