package com.wangzan101.mika.teach

import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.core.learn.teach.TeachEventKind
import com.wangzan101.mika.core.learn.teach.TeachObservation

/** Copies one accessibility event into plain data. It keeps no nodes and writes no logs here. */
object TeachEvents {
    fun capture(event: AccessibilityEvent, displayId: Int): TeachObservation? {
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> TeachEventKind.Click
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> TeachEventKind.LongClick
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> TeachEventKind.Text
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> TeachEventKind.Scroll
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> TeachEventKind.Window
            else -> return null
        }
        val source = try {
            event.source
        } catch (e: Exception) {
            null
        }
        return try {
            val password = event.isPassword || source?.isPassword == true
            val raw = if (password) null else event.text?.joinToString(" ")?.take(200)?.takeIf { it.isNotBlank() }
            TeachObservation(
                atMs = event.eventTime,
                kind = kind,
                packageName = event.packageName?.toString(),
                className = if (password) null else (source?.className ?: event.className)?.toString(),
                title = if (kind == TeachEventKind.Window) raw else null,
                text = raw,
                description = if (password) null else (source?.contentDescription ?: event.contentDescription)?.toString(),
                viewId = if (password) null else source?.viewIdResourceName,
                password = password,
                displayId = displayId,
            )
        } finally {
            // Object pooling was removed in API 33, where recycle() is a no-op; older systems still need it.
            if (Build.VERSION.SDK_INT < 33) {
                try {
                    source?.recycleCompat()
                } catch (e: Exception) {
                    // The node has already been recycled
                }
            }
        }
    }

    /** Object pooling was removed in API 33, but older systems still need explicit recycling. */
    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() = recycle()
}
