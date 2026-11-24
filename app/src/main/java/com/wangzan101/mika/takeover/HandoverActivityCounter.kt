package com.wangzan101.mika.takeover

import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** What the user did on the main screen at the moment of hand-back: the action count and which page they stopped on (null when unavailable). [url] is the final address when a web takeover is handed back, and null for an app takeover. */
data class HandoverActivity(val inputCount: Int, val stoppedAt: String?, val url: String? = null)

/**
 * Counts the user's taps, typing and scrolling on [packageName] on the main screen during takeover; one swipe fires many scroll events in a row,
 * and those less than [MERGE_MS] apart count as a single one; the same applies to consecutive text changes in the same input field. A burst counts for at most [BURST_CAP_MS],
 * and anything beyond that counts as another action, so a long scroll or typing session is not recorded as just 1. Also records the simple name of the Activity the package most recently switched to, for use when the page title is unavailable.
 * Events come from the coroutine running the accessibility callback while reads come from the hand-back coroutine, hence the locking.
 */
class HandoverActivityCounter(val packageName: String) {
    private var count = 0
    private var scrollBurst: Burst? = null
    private var typingBurst: Burst? = null
    private var typingSource: Any? = null
    private var activity: String? = null

    val inputCount: Int @Synchronized get() = count
    val lastActivity: String? @Synchronized get() = activity

    @Synchronized
    fun onEvent(displayId: Int, eventPackage: CharSequence?, eventType: Int, className: CharSequence?, timeMs: Long, source: Any? = null) {
        if (displayId != Display.DEFAULT_DISPLAY || eventPackage?.toString() != packageName) return
        when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> count++
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Switching to a different input field starts a new one
                if (typingSource != source) typingBurst = null
                typingSource = source
                typingBurst = advance(typingBurst, timeMs)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scrollBurst = advance(scrollBurst, timeMs)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }?.let { activity = it }
        }
    }

    private class Burst(val startAt: Long, var lastAt: Long)

    private fun advance(burst: Burst?, timeMs: Long): Burst {
        if (burst != null && timeMs - burst.lastAt < MERGE_MS && timeMs - burst.startAt < BURST_CAP_MS) {
            burst.lastAt = timeMs
            return burst
        }
        count++
        return Burst(timeMs, timeMs)
    }

    companion object {
        const val MERGE_MS = 1_000L
        const val BURST_CAP_MS = 5_000L
    }
}

/**
 * "Stopped on": the window title is often just the app name, in which case the simple page (Activity) name is used, and the two are only joined when both exist and differ;
 * if the title is unavailable, the simple page name is used.
 */
fun stoppedAt(title: String?, appLabel: String?, activity: String?): String? {
    val t = title?.takeIf { it.isNotBlank() }
    if (t == null || t == appLabel) return activity ?: t
    return t
}
