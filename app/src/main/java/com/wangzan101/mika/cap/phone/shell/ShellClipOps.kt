package com.wangzan101.mika.cap.phone.shell

import android.app.AppOpsManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_FAILED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_LABEL
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_SET
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_UNREADABLE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.OPSTR_READ_CLIPBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READ_CLIPBOARD_IN_BACKGROUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_IS_SENSITIVE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_SUPPRESS_CLIPBOARD_OVERLAY

// Assistant service: clipboard read/write protection and the device file policy
/** The implementation of [ShellService.setClipText]. */
internal fun ShellService.putClipText(text: String): Int = synchronized(clipLock) {
    try {
        // If the previous restore did not finish because the clipboard could not be read, try again first; still unreadable means we must not overwrite content that may now belong to the user.
        if (clipSaved && !restoreSavedClip()) return CLIP_UNREADABLE
        if (!clipSaved) {
            if (!canReadClipboard()) return CLIP_UNREADABLE
            val prior = clipboard.primaryClip
            // Reading empty does not necessarily mean empty: the system may silently block the read, while has/hasDescription still say content is present
            when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                ShellClip.Read.Unreadable -> {
                    Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                    return CLIP_UNREADABLE
                }
                ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
            }
            savedClip = prior
            clipSaved = true
        }
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        suppressOverlay(clip)
        markSensitive(clip)
        clipboard.setPrimaryClip(clip)
        ourText = text
        // Android can silently decline the write. Never paste a clipboard we didn't set.
        if (nowState(text) == ShellClip.Now.Ours) CLIP_SET else CLIP_FAILED
    } catch (t: Throwable) {
        Log.w(TAG, "setClipText failed", t)
        CLIP_FAILED
    }
}

/** The implementation of [ShellService.restoreClip]: clear the saved clipboard only once the restore finished ([applyRestore] true), otherwise keep trying next time. */
internal fun ShellService.restoreSavedClip(): Boolean = synchronized(clipLock) {
    if (!clipSaved) return true
    val prior = savedClip
    val finished = applyRestore("restoreClip", ourText, prior?.let { { it } })
    if (finished) {
        savedClip = null
        clipSaved = false
        ourText = null
    }
    finished
}

/** The implementation of [ShellService.savedClipText]: the saved text; null when nothing is pending, an empty string when it was empty or its text cannot be read. */
internal fun ShellService.savedClipTextNow(): String? = synchronized(clipLock) {
    if (!clipSaved) return null
    runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
}

/** The implementation of [ShellService.restoreClipText]: restore to the [original] recorded by the caller (an empty string means it was empty). */
internal fun ShellService.restoreClipTextTo(original: String, ours: String): Boolean = synchronized(clipLock) {
    applyRestore("restoreClipText", ours, original.takeIf { it.isNotEmpty() }?.let { { ClipData.newPlainText("", it) } })
}

/**
 * Restores the clipboard once: decides put back / clear / leave ([ShellClip.restore]) by whether it is Claire's [ours] right now and whether it had content before ([putBack] null means it was empty).
 * The write may also be silently declined by the system: read once more afterwards, and one that is still Claire's or unreadable counts as unfinished, so the caller keeps trying next time;
 * when the user has already copied something else, the user's new content is kept and the restore counts as finished. [what] is only for logs. The caller holds clipLock.
 */
internal fun ShellService.applyRestore(what: String, ours: String?, putBack: (() -> ClipData)?): Boolean = runCatching {
    val now = nowState(ours)
    val action = ShellClip.restore(putBack == null, now)
    Log.i(TAG, "$what: now=$now action=$action")
    when (action) {
        ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(putBack!!()))
        ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
        ShellClip.Restore.Leave -> Unit
    }
    val after = nowState(ours)
    after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
}.onFailure { Log.w(TAG, "$what failed", it) }.getOrDefault(false)

/** Whether the clipboard right now holds what Claire put there ([ours]); unreadable (has content but cannot be fetched) is never treated as empty. */
internal fun ShellService.nowState(ours: String?): ShellClip.Now {
    val now = clipboard.primaryClip
    return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
        ShellClip.Read.Empty -> ShellClip.Now.Empty
        ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
        ShellClip.Read.Has -> {
            val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                now.getItemAt(0).text?.toString() == ours
            if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
        }
    }
}

/** Reading works only when shell has the background clipboard permission and the clipboard-read app-op has not been turned off. */
internal fun ShellService.canReadClipboard(): Boolean = runCatching {
    val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
    val ops = context.getSystemService(AppOpsManager::class.java)
    granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
        AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

internal fun ShellService.suppressOverlay(clip: ClipData): ClipData {
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
    clip.description.extras = extras
    return clip
}

/**
 * Marks content Claire pastes (addresses, phone numbers and the like) as sensitive, so system and keyboard clipboard previews and history do not show the original text.
 * Only Claire's own content is marked; a restored user clipboard stays as it was.
 */
internal fun ShellService.markSensitive(clip: ClipData): ClipData {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_IS_SENSITIVE, true)
    clip.description.extras = extras
    return clip
}

internal inline fun ShellService.deviceFileCall(block: (DeviceFilePolicy) -> Bundle): Bundle = asCallAudioShell {
    val snapshot = filePolicy ?: return@asCallAudioShell Bundle().apply { putString("status", "policy_unsynced") }
    val response = block(snapshot.policy)
    synchronized(filePolicyLock) {
        if (filePolicy !== snapshot) {
            response.getParcelable<ParcelFileDescriptor>("fd")?.close()
            Bundle().apply { putString("status", "policy_changed") }
        } else {
            response.getParcelable<ParcelFileDescriptor>("fd")?.let { returnedCallPipes.set(listOf(it)) }
            response
        }
    }
}
