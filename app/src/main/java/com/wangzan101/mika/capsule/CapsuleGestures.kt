package com.wangzan101.mika.capsule

import android.util.Log
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.theme.animationsOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.capsule.CapsuleController.Touch
import com.wangzan101.mika.capsule.CapsuleController.Companion.TAG

// Capsule: taps, expand/collapse, and card actions
/** Tap the capsule while a note is flashing on it: if that note says to open Claire, open it; otherwise just clear the note. This tap does not count toward single- or double-tap timing. */
internal fun CapsuleController.tapCapsule() {
    val n = note
    clearNote()
    if (n != null) {
        resetTaps()
        if (n.opensMika) open(null)
        return
    }
    val touch = capsuleTouch
    capsuleTouch = null
    val age = touch?.let { SystemClock.uptimeMillis() - it.at }
    if (touch == null || age == null || age !in 0..taps.timeoutMs) {
        // Screen readers run onClick directly, with no MotionEvent: treat it as a definite single tap, and do not pair it with an earlier capsule touch to form a double tap
        resetTaps()
        expand()
        return
    }
    tap(touch)
}

/** A real touch hits the dot or the capsule: the two taps must land within the system timeout, near the same position, and from the same source; a single tap still waits out the double-tap timeout before expanding. */
internal fun CapsuleController.tap(touch: Touch) {
    if (capturing) return
    when (taps.tap(touch.at, touch.x, touch.y, touch.source)) {
        DoubleTap.Tap.Double -> {
            tapJob?.cancel()
            previewScreen()
        }
        DoubleTap.Tap.First -> {
            tapJob?.cancel()
            tapJob = scope.launch {
                delay(taps.timeoutMs)
                if (taps.settle(SystemClock.uptimeMillis())) expand()
            }
        }
    }
}

/** Expand the card (the idle icon shows the idle card). Every expansion re-reads the idle card for whichever conversation the user is in right now instead of reusing the previous one. */
internal fun CapsuleController.expand() {
    val v = view ?: return
    // Worth reminding: a single tap writes the picked notifications into today's reminder conversation (as one message from Claire, without calling the model), and that conversation becomes the current one;
    // then the card expands as it does when idle, continuing from that conversation. If the write fails (an error occurs), expand as usual. Double tap sends the screen, long press talks, same as when idle
    if (v.kind == CapsuleKind.Notice) {
        scope.launch {
            com.wangzan101.mika.proactive.NoticeHintList.openTalk(context)
            idle = loadIdle()
            open()
            refresh()
        }
        return
    }
    if (v.kind == CapsuleKind.Idle) {
        openIdle()
        return
    }
    open()
    refresh()
    scope.launch {
        val fresh = loadIdle()
        if (fresh != idle) {
            idle = fresh
            refresh()
        }
    }
}

/** Expand the card: first record where the dot currently sits, so the card grows out from there. */
internal fun CapsuleController.open() {
    if (!expanded) anchorY = placedY + (window?.height ?: 0) / 2
    expanded = true
    closing = false
    syncWindowVisibility()
}

/** Return to the conversation in Claire (the same entry point as tapping a notification). */
internal fun CapsuleController.open(conversationId: String?) {
    runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
        .onFailure { Log.w(TAG, "could not open Claire from capsule", it) }
}

/** Tap outside the window: dismiss the zoomed image first, then collapse the card. */
internal fun CapsuleController.outside() {
    when {
        zoomed -> zoom(false)
        expanded -> collapse()
    }
}

internal fun CapsuleController.freshChat(fresh: Boolean) {
    idle = idle?.copy(fresh = fresh)
    refresh()
}

/** Collapse the card: the input row collapses immediately (focus goes back to the app below), and the card actually collapses only once it has shrunk back to the dot's position ([closed]); with animations off, collapse immediately. */
internal fun CapsuleController.collapse() {
    if (!expanded) return
    closeInput()
    if (closing) return
    if (animationsOff(context) || card == null) {
        resetCard()
        refresh()
        return
    }
    closing = true
    syncWindowVisibility()
}

internal fun CapsuleController.closed() {
    if (!closing) return
    resetCard()
    refresh()
}

/** Origin of the card's scale animation: the side the dot is docked to, at the height where the dot's center sits on the card (clamped to the top or bottom edge when outside the card). */
internal fun CapsuleController.cardOrigin(size: Size): TransformOrigin {
    val y = if (size.height > 0f) ((anchorY - cardTop) / size.height).coerceIn(0f, 1f) else 0f
    return TransformOrigin(if (dock.side == DockSide.Right) 1f else 0f, y)
}

internal fun CapsuleController.runFor(key: String): TaskRun? = tasks.runs.value.firstOrNull { it.key == key }
