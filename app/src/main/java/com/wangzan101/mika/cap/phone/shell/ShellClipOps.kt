package com.wangzan101.mika.cap.phone.shell

import androidx.core.os.BundleCompat
import android.app.AppOpsManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_LABEL
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.OPSTR_READ_CLIPBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READ_CLIPBOARD_IN_BACKGROUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_IS_SENSITIVE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_SUPPRESS_CLIPBOARD_OVERLAY

// Assistant service: clipboard read/write protection and the device file policy
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

/** Reading counts as possible only if shell has the background clipboard read permission and the clipboard read app-op has not been turned off. */
internal fun ShellService.canReadClipboard(): Boolean = runCatching {
    val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
    val ops = context.getSystemService(AppOpsManager::class.java)
    granted && ops.checkOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
        AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

internal fun ShellService.suppressOverlay(clip: ClipData): ClipData {
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
    clip.description.extras = extras
    return clip
}

/**
 * Marks content Claire pastes (possibly an address, phone number and so on) as sensitive: the system and IME clipboard
 * previews and history will not show the original text. Only what Claire put there is marked; restoring the user's
 * clipboard leaves it as it was.
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
            BundleCompat.getParcelable(response, "fd", ParcelFileDescriptor::class.java)?.close()
            Bundle().apply { putString("status", "policy_changed") }
        } else {
            BundleCompat.getParcelable(response, "fd", ParcelFileDescriptor::class.java)?.let { returnedCallPipes.set(listOf(it)) }
            response
        }
    }
}
