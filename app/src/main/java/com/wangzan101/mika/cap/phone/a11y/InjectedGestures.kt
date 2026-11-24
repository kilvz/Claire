package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Isolates overlay touches while our own injected gestures run; the counter and the [isolate] callback are only used on the main thread. */
internal class InjectedGestures(private val isolate: (Boolean) -> Unit) {
    private var depth = 0
    val isolated: Boolean get() = depth > 0

    suspend fun <T> passThrough(block: suspend () -> T): T {
        var entered = false
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                depth++
                // May throw on cancellation right away when returning to the caller thread; from the moment the
                // counter is modified, undo must happen in a matching finally.
                entered = true
                if (depth == 1) isolate(true)
            }
            return withContext(NonCancellable) { block() }
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // One that was cancelled while still queued for the main thread never entered isolation, so it must
            // not decrement other gestures' count.
            if (entered) try {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    depth = (depth - 1).coerceAtLeast(0)
                    if (depth == 0) isolate(false)
                }
            } catch (restoration: Throwable) {
                // A cleanup failure must not mask the cancellation or the gesture/window isolation's original error.
                val original = failure
                if (original == null) throw restoration
                if (original !== restoration) original.addSuppressed(restoration)
            }
        }
    }
}

/**
 * Tries to update every window, then throws the first error once all are done, with the rest attached, so one
 * bad window cannot block the remaining recovery.
 */
internal fun <T> forEachGestureWindow(windows: List<T>, update: (T) -> Unit) {
    var failure: Throwable? = null
    for (window in windows) {
        try {
            update(window)
        } catch (t: Throwable) {
            val first = failure
            if (first == null) failure = t else if (first !== t) first.addSuppressed(t)
        }
    }
    failure?.let { throw it }
}
