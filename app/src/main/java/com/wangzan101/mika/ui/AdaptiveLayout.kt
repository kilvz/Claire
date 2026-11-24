package com.wangzan101.mika.ui

/** Everything uses dp within the window content area; no dependency on device model, screen orientation or Android Rect, which makes boundaries easy to verify. */
internal data class PaneBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

internal data class FoldObstacle(val bounds: PaneBounds, val vertical: Boolean)

internal data class AdaptiveLayout(
    val content: PaneBounds,
    val navigationWidth: Float = 0f,
    val hingeGap: Float = 0f,
    val fixedNavigation: Boolean = false,
) {
    val hasNavigation get() = navigationWidth > 0f
}

/**
 * From 640dp there is a permanent 280dp side pane, and from 840dp the side pane widens to 304dp. Vertical foldables are split at the real hinge.
 * Give the list at least 280dp and the chat 360dp, also covering foldables that are still under 840dp once unfolded.
 * With a horizontal crease, multiple hinges, or any pane too narrow, pick the largest complete region; on equal area prefer the left/top one.
 * Flat creases that are neither separating nor occluding are filtered out by the caller and should not waste usable space.
 */
internal fun adaptiveLayout(width: Float, height: Float, folds: List<FoldObstacle>, navigation: Boolean): AdaptiveLayout {
    val full = PaneBounds(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val relevant = folds.filter { fold ->
        val b = fold.bounds
        if (fold.vertical) b.left < width && b.right > 0 && b.bottom > 0 && b.top < height
        else b.top < height && b.bottom > 0 && b.right > 0 && b.left < width
    }
    if (navigation && relevant.size == 1) {
        val fold = relevant.single()
        val b = fold.bounds
        if (fold.vertical && b.left >= 280f && width - b.right >= 360f && height >= 480f) {
            return AdaptiveLayout(full, b.left, (b.right - b.left).coerceAtLeast(0f), fixedNavigation = true)
        }
    }
    var panes = listOf(full)
    for (fold in relevant) {
        val b = fold.bounds
        panes = panes.flatMap { p ->
            if (b.left >= p.right || b.right <= p.left || b.top >= p.bottom || b.bottom <= p.top) listOf(p)
            else if (fold.vertical) listOf(
                p.copy(right = b.left.coerceIn(p.left, p.right)),
                p.copy(left = b.right.coerceIn(p.left, p.right)),
            ) else listOf(
                p.copy(bottom = b.top.coerceIn(p.top, p.bottom)),
                p.copy(top = b.bottom.coerceIn(p.top, p.bottom)),
            )
        }
    }
    val pane = panes.maxByOrNull { it.width * it.height } ?: full
    val navWidth = when {
        !navigation || pane.height < 480f || pane.width < 640f -> 0f
        pane.width < 840f -> 280f
        else -> 304f
    }
    return AdaptiveLayout(pane, navWidth)
}

internal const val SplitHandleWidth = 16f
internal fun splitNavigationWidth(layout: AdaptiveLayout, requested: Float?): Float =
    if (!layout.hasNavigation || layout.fixedNavigation) layout.navigationWidth
    else (requested ?: layout.navigationWidth).coerceIn(280f, (layout.content.width - 320f - SplitHandleWidth).coerceAtLeast(280f))
