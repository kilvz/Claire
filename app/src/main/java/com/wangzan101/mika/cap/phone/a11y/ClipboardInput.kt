package com.wangzan101.mika.cap.phone.a11y

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.PASTE_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.WEB_VIEW
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.ShellService

// Write text into an input field: SET_TEXT directly, or select the existing content and paste via the clipboard. All extensions on [AndroidScreen], same package.

/**
 * Contenteditable regions in web pages only accept real input: SET_TEXT only changes the text on the page, the editor never gets an input event,
 * and the app treats it as no input ("Done" stays dim, leaving does not save). For such fields, select the existing content and paste, or press Delete.
 */
internal fun AndroidScreen.inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

/**
 * Select the existing content and paste (or clear with Delete). Returns null when the clipboard cannot be used safely and no text was changed;
 * other results use the three states of [AsciiTextKeys]. When selection fails, do not send Delete or Paste, to avoid deleting one character or inserting at the cursor.
 */
internal fun AndroidScreen.inputText(node: AccessibilityNodeInfo, text: String): Int? {
    if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
    val before = node.text?.toString().orEmpty()
    fun selectAll(): Boolean {
        if (!node.refresh() || !visibleInputTarget(node) || node.text?.toString().orEmpty() != before) return false
        if (before.isEmpty()) return true
        val range = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
    }
    if (text.isEmpty()) {
        if (before.isEmpty()) return AsciiTextKeys.DISPATCHED
        if (!selectAll()) return AsciiTextKeys.NOT_SENT
        if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
        return if (shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
    }
    return withClipboardText(text) {
        if (!selectAll()) return@withClipboardText AsciiTextKeys.NOT_SENT
        if (!node.refresh() || !visibleInputTarget(node)) return@withClipboardText AsciiTextKeys.NOT_SENT
        // A standard action may take effect even when it returns false; treat it as uncertain and do not resend the shortcut.
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
        // The app reads the clipboard asynchronously: wait for the text to change before restoring, for a bounded time
        val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (node.refresh() && node.text?.toString().orEmpty() != before) break
            Thread.sleep(POLL_MS)
        }
        AsciiTextKeys.DISPATCHED
    }
}

/** Runs synchronously while holding the global clipboard lock; after setting, always keep a backup where possible, and clear the hold flag and encrypted backup only once restoration is confirmed. */
internal fun AndroidScreen.withClipboardText(text: String, action: () -> Int): Int? {
    clipLock.lock()
    try {
        clipHeld(true)
        val set = runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED)
        runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
        if (set != ShellService.CLIP_SET) return null
        return action()
    } finally {
        val restored = runCatching { shell.restoreClip() }.getOrDefault(false)
        try {
            if (restored) clipHeld(false)
        } finally {
            clipLock.unlock()
        }
    }
}

internal fun AndroidScreen.setText(node: AccessibilityNodeInfo, text: String): Boolean {
    if (!node.refresh() || !visibleInputTarget(node)) return false
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}
