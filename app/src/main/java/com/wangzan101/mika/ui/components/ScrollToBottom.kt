package com.wangzan101.mika.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * A round frosted-glass "back to bottom" button that floats out once the list is scrolled away from the end.
 */
@Composable
fun ScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = MikaTheme.reduceMotion,
    buttonSize: Dp = MikaSize.IconButton,
    iconSize: Dp = 20.dp,
    tag: String = "scroll-to-bottom",
) {
    val c = MikaTheme.colors
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduceMotion) fadeIn(tween(150)) else fadeIn(tween(180)) + scaleIn(tween(180, easing = MikaMotion.Decelerate), initialScale = 0.85f),
        exit = if (reduceMotion) fadeOut(tween(120)) else fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
    ) {
        val label = stringResource(R.string.chat_scroll_to_bottom)
        GlassSurface(
            modifier = Modifier.size(buttonSize).testTag(tag),
            backdrop = backdrop,
            shape = CircleShape,
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Down, label, Modifier.size(iconSize), tint = c.ink)
            }
        }
    }
}

private const val END_OFFSET = 1_000_000
private const val PRE_JUMP_ITEM_GAP = 25
private const val PRE_JUMP_KEEP_ITEMS = 10
private const val APPROACH_RATE = 8.5f
private const val RAMP_SECONDS = 0.07f

/**
 * Continuously and smoothly scroll back to the real end of the list:
 * without the two-stage jump of "first stop at the top of the last item, then scroll a second time to the bottom"; the whole run accelerates and decelerates smoothly within the same per-frame loop and comes to rest at the bottom edge.
 */
internal suspend fun scrollListToBottom(listState: LazyListState, density: Density, reduceMotion: Boolean) {
    val initialLayout = listState.layoutInfo
    val initialEnd = initialLayout.totalItemsCount - 1
    if (initialEnd < 0 || !listState.canScrollForward) return
    if (reduceMotion) {
        listState.scrollToItem(initialEnd, END_OFFSET)
        return
    }
    val lastVisibleIndex = initialLayout.visibleItemsInfo.lastOrNull()?.index ?: 0
    // Only when the distance is especially large (more than 25 items) jump close in one go first, so a very long history doesn't take several seconds to scroll; normal browsing slides continuously the whole way.
    if (initialEnd - lastVisibleIndex > PRE_JUMP_ITEM_GAP) {
        listState.scrollToItem((initialEnd - PRE_JUMP_KEEP_ITEMS).coerceAtLeast(0))
    }
    val minSpeedPx = with(density) { 420.dp.toPx() }
    val maxSpeedPx = with(density) { 9000.dp.toPx() }
    listState.scroll {
        var lastNanos = withFrameNanos { it }
        var elapsed = 0f
        while (listState.canScrollForward) {
            val now = withFrameNanos { it }
            val dt = ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
            lastNanos = now
            elapsed += dt
            val layout = listState.layoutInfo
            val end = layout.totalItemsCount - 1
            if (end < 0) break
            val viewportBottom = layout.viewportEndOffset - layout.afterContentPadding
            val tail = layout.visibleItemsInfo.firstOrNull { it.index == end }
            val targetRemaining = if (tail != null) {
                (tail.offset + tail.size - viewportBottom).toFloat().coerceAtLeast(0f)
            } else {
                val items = layout.visibleItemsInfo
                val lastVisible = items.lastOrNull()
                val avgSize = if (items.isEmpty()) 200f else items.sumOf { it.size }.toFloat() / items.size
                val hiddenTail = lastVisible?.let { (it.offset + it.size - viewportBottom).coerceAtLeast(0) } ?: 0
                val remainingItems = (end - (lastVisible?.index ?: 0)).coerceAtLeast(1)
                (hiddenTail + remainingItems * avgSize).coerceAtLeast((viewportBottom - layout.viewportStartOffset) * 0.5f)
            }
            if (tail != null && targetRemaining <= 0.5f) {
                if (targetRemaining > 0f) scrollBy(targetRemaining)
                break
            }
            val ramp = (elapsed / RAMP_SECONDS).coerceIn(0.12f, 1f)
            val speed = (targetRemaining * APPROACH_RATE).coerceIn(minSpeedPx, maxSpeedPx) * ramp
            val step = (speed * dt).coerceAtMost(targetRemaining)
            val consumed = scrollBy(step)
            if (consumed <= 0.1f && !listState.canScrollForward) break
        }
    }
}
