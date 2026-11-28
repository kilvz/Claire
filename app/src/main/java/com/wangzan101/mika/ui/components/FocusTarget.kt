package com.wangzan101.mika.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.delay

/** One in-page focus request: [id] is the caller-agreed item key (see `SettingsFocusKey` in settings) and [nonce] makes repeated taps on the same item trigger again. */
data class FocusTarget(val id: String, val nonce: Long)

/**
 * Coordinates focusing and highlighting for "jump to an item on a page" (currently used by settings search): [request] is written before navigating,
 * and the target row consumes it through [consume] once it enters composition and takes over the animation, so a not-yet-loaded block (such as asynchronously read content) is not cleared too early.
 */
class FocusTargetHost {
    var current by mutableStateOf<FocusTarget?>(null)
        private set
    private var seq = 0L

    fun request(id: String) {
        current = FocusTarget(id, ++seq)
    }

    fun clear() {
        current = null
    }

    fun consume(target: FocusTarget) {
        if (current == target) current = null
    }
}

val LocalFocusTarget = staticCompositionLocalOf<FocusTargetHost?> { null }

/**
 * Marks a row or group of settings as searchable: when [LocalFocusTarget] points at [id],
 * it scrolls into view automatically (with a little margin so it does not hug the edge), then fades in an accent highlight and slowly fades it out.
 * Attach it to a row or inner container inside a card, not to the outside of a solid-backed `GroupCard`, or the card background will cover it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusHighlightTarget(id: String): Modifier {
    val host = LocalFocusTarget.current ?: return this
    val pending = host.current?.takeIf { it.id == id }
    var activeNonce by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pending) {
        if (pending != null) {
            // Record the nonce before consuming: do not share a LaunchedEffect keyed on pending with the animation, or consuming would cancel it immediately
            activeNonce = pending.nonce
            host.consume(pending)
        }
    }
    val highlight = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val reduceMotion = MikaTheme.reduceMotion
    LaunchedEffect(activeNonce) {
        if (activeNonce == 0L) return@LaunchedEffect
        // Wait two frames so first measurement and the card footnote that a compact list expands one frame later are laid out before scrolling
        withFrameNanos { }
        withFrameNanos { }
        val padPx = with(density) { 72.dp.toPx() }
        val rect = if (size.width > 0 && size.height > 0) {
            Rect(0f, -padPx, size.width.toFloat(), size.height.toFloat() + padPx)
        } else null
        requester.bringIntoView(rect)
        if (reduceMotion) {
            // System animations are off: light up directly, then switch off after a pause with no fades
            highlight.snapTo(1f)
            delay(650L)
            highlight.snapTo(0f)
        } else {
            highlight.snapTo(0f)
            highlight.animateTo(1f, tween(durationMillis = 180, easing = FastOutSlowInEasing))
            delay(650L)
            highlight.animateTo(0f, tween(durationMillis = 600, easing = FastOutSlowInEasing))
        }
    }
    val colors = MikaTheme.colors
    val peakAlpha = if (colors.isDark) 0.28f else 0.16f
    return this
        .onSizeChanged { size = it }
        .bringIntoViewRequester(requester)
        .drawBehind {
            val a = highlight.value
            if (a > 0f) drawRect(colors.accent.copy(alpha = peakAlpha * a))
        }
}
