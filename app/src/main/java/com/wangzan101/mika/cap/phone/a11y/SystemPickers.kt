package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings

/**
 * Packages of the system UIs a target app brings up (permission dialogs, choosers, file and photo pickers): their
 * appearing in the foreground does not count as the app being switched away.
 * Resolved from standard Intents; package names are not hardcoded.
 */
internal object SystemPickers {
    /** Package of the system-provided chooser (ResolverActivity and the like). */
    const val SYSTEM_PACKAGE = "android"
    private const val ACTION_REQUEST_PERMISSIONS = "android.content.pm.action.REQUEST_PERMISSIONS"

    fun intents(): List<Intent> = buildList {
        add(Intent(ACTION_REQUEST_PERMISSIONS))
        add(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain"), null))
        add(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Intent(MediaStore.ACTION_PICK_IMAGES))
    }

    fun settingsPackage(pm: PackageManager): String? =
        runCatching { pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName }.getOrNull()

    fun resolve(pm: PackageManager): Set<String> =
        intents().mapNotNull { runCatching { pm.resolveActivity(it, 0)?.activityInfo?.packageName }.getOrNull() }.toSet() + SYSTEM_PACKAGE
}
