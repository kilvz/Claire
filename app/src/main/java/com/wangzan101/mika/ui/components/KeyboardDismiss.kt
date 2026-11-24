package com.wangzan101.mika.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager

/** An input area registered in a keyboard-dismiss area: where it is and whether it currently has focus. */
private class KeyboardKeeper {
    val bounds = HashMap<Any, Rect>()
    val focused = HashSet<Any>()
    var area: LayoutCoordinates? = null
    fun contains(at: Offset) = bounds.values.any { it.contains(at) }
}

private val LocalKeyboardKeeper = staticCompositionLocalOf<KeyboardKeeper?> { null }

/**
 * Tapping or dragging outside an input area clears focus and hides the keyboard. The gesture itself isn't intercepted, so buttons outside still get taps and lists still scroll.
 * It only acts when a registered input area ([keepsKeyboard]) has focus; normally it doesn't affect operations like selecting text.
 * A dialog is a separate window, so if it contains a text field it needs another layer of this.
 */
@Composable
fun KeyboardDismissArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val keeper = remember { KeyboardKeeper() }
    val focus = LocalFocusManager.current
    Box(
        modifier.onGloballyPositioned { keeper.area = it }.pointerInput(keeper) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (keeper.focused.isEmpty()) return@awaitEachGesture
                val at = keeper.area?.takeIf { it.isAttached }?.localToRoot(down.position) ?: return@awaitEachGesture
                if (keeper.contains(at)) return@awaitEachGesture
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        focus.clearFocus()
                        break
                    }
                }
            }
        },
    ) {
        CompositionLocalProvider(LocalKeyboardKeeper provides keeper, content = content)
    }
}

/** Marks an input area (a text field, or a whole input bar together with the buttons beside it): tapping inside doesn't hide the keyboard. */
@Composable
fun Modifier.keepsKeyboard(): Modifier {
    val keeper = LocalKeyboardKeeper.current ?: return this
    val key = remember { Any() }
    DisposableEffect(keeper) {
        onDispose { keeper.bounds.remove(key); keeper.focused.remove(key) }
    }
    return this
        .onGloballyPositioned { keeper.bounds[key] = it.boundsInRoot() }
        .onFocusChanged { if (it.hasFocus) keeper.focused.add(key) else keeper.focused.remove(key) }
}
