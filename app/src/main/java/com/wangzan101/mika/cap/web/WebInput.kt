package com.wangzan101.mika.cap.web

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Real input into a web tab: touches through dispatchTouchEvent, text through the input connection's commitText, keys through dispatchKeyEvent,
 * so the page receives trusted events (isTrusted), just like a human operating it. Scripted clicks or script assignment are not used as a substitute.
 * The input connection is taken directly from the WebView, bypassing the system IME: no keyboard pops up on the main screen. Coordinates are WebView view pixels.
 */
class WebInput(private val tab: WebTab) {
    private val view get() = tab.view

    suspend fun tap(xPx: Int, yPx: Int) {
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, xPx.toFloat(), yPx.toFloat())
        delay(TAP_HOLD_MS)
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xPx.toFloat(), yPx.toFloat())
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300) {
        val steps = max(2, (durationMs / MOVE_INTERVAL_MS).toInt())
        val down = SystemClock.uptimeMillis()
        touch(down, down, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat())
        for (i in 1..steps) {
            delay(durationMs / steps)
            val f = i.toFloat() / steps
            touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat())
    }

    /** Hands the whole text to the currently focused web input field; returns false if no input connection is available (no editable focus). */
    suspend fun commitText(text: String): Boolean = withConnection { it.commitText(text, 1) }

    suspend fun key(keyCode: Int) = key(keyCode, 0)

    /** Select all (Ctrl+A) and then delete; some editors do not honor the key combo, so also select all and clear through the input connection. */
    suspend fun selectAllAndDelete() {
        key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        key(KeyEvent.KEYCODE_DEL, 0)
    }

    /** Select all in the input connection and write an empty string: the fallback when the key combo did not clear it. */
    suspend fun selectAllAndCommitEmpty(): Boolean = withConnection {
        it.performContextMenuAction(android.R.id.selectAll)
        it.commitText("", 1)
    }

    /** [meta] is the KeyEvent modifier state (META_CTRL_ON and the like), used for key combos. */
    suspend fun key(keyCode: Int, meta: Int) {
        val down = SystemClock.uptimeMillis()
        withContext(Dispatchers.Main) {
            view.dispatchKeyEvent(keyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, meta))
            view.dispatchKeyEvent(keyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, meta))
        }
    }

    private fun keyEvent(down: Long, at: Long, action: Int, code: Int, meta: Int) =
        KeyEvent(down, at, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    // Mark it as a finger: without a tool type the page sees an empty pointerType, while a real finger is touch
    private suspend fun touch(down: Long, at: Long, action: Int, x: Float, y: Float) = withContext(Dispatchers.Main) {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
        val e = MotionEvent.obtain(down, at, action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            view.dispatchTouchEvent(e)
        } finally {
            e.recycle()
        }
    }

    // The browser engine's input connection has its own thread: if it has a handler, call on that thread, otherwise call on the main thread
    private suspend fun withConnection(block: (InputConnection) -> Boolean): Boolean {
        val ic = withContext(Dispatchers.Main) {
            if (!view.hasFocus()) view.requestFocus()
            view.onCreateInputConnection(EditorInfo())
        } ?: return false
        val handler = ic.handler
        if (handler == null || handler.looper == Looper.getMainLooper()) return withContext(Dispatchers.Main) { block(ic) }
        return suspendCancellableCoroutine { c -> handler.post { c.resume(runCatching { block(ic) }.getOrDefault(false)) } }
    }

    private companion object {
        const val TAP_HOLD_MS = 50L
        const val MOVE_INTERVAL_MS = 16L
    }
}
