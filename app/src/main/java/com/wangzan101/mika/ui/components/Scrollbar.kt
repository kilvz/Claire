package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.delay

/**
 * Draws a thin scrollbar for a vertical lazy list: pinned to the right edge, its length reflects how much of the whole list is visible and its position reflects where you have scrolled.
 * Nothing is drawn when the list fits; it appears while scrolling and right after the content changes, then fades out after a moment at rest. It is drawn on top of the content and takes no layout space.
 * The total length is estimated from the average height of the items shown so far, which is accurate enough when item heights don't vary much.
 */
@Composable
fun Modifier.lazyListScrollbar(
    state: LazyListState,
    color: Color = MikaTheme.colors.ink3,
    width: Dp = 3.dp,
    edge: Dp = 2.dp,
): Modifier {
    val scrolling = state.isScrollInProgress
    val count = state.layoutInfo.totalItemsCount
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(scrolling, count) {
        if (scrolling) {
            shown = true
        } else {
            delay(IDLE_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) THUMB_ALPHA else 0f, tween(if (shown) 120 else 400), label = "scrollbar")
    return drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val info = state.layoutInfo
        val items = info.visibleItemsInfo
        if (items.isEmpty()) return@drawWithContent
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val average = items.sumOf { it.size }.toFloat() / items.size
        val total = average * info.totalItemsCount
        if (viewport <= 0f || total <= viewport + 1f) return@drawWithContent
        val w = width.toPx()
        val thumb = (viewport * viewport / total).coerceAtLeast(MIN_THUMB.toPx()).coerceAtMost(viewport)
        val scrolled = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
        val y = (scrolled / (total - viewport)).coerceIn(0f, 1f) * (viewport - thumb)
        drawRoundRect(
            color, Offset(size.width - w - edge.toPx(), y), Size(w, thumb), CornerRadius(w / 2), alpha = alpha,
        )
    }
}

private const val IDLE_MS = 900L
private const val THUMB_ALPHA = 0.55f
private val MIN_THUMB = 24.dp
