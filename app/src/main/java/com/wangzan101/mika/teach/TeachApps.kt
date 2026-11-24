package com.wangzan101.mika.teach

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.wangzan101.mika.task.ConfirmationNotifier

/**
 * Identifies launchers and default browsers by system intents, without brand recognition or hard-coded package names.
 * If any step cannot be resolved it returns an empty set / falls back to the package name: folding therefore fails open and never drops steps by mistake.
 */
internal object TeachApps {
    fun launchers(context: Context): Set<String> = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun defaultBrowsers(context: Context): Set<String> = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("http://") }
        context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun label(context: Context, packageName: String): String =
        runCatching { ConfirmationNotifier.appLabel(context, packageName) }.getOrDefault(packageName)
}
