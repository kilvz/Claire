package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.wangzan101.mika.core.screen.uistate.AppCopies
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PER_USER_RANGE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_OK
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_IN_USE_ON_MAIN
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_NOT_FOUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_ERROR
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_OK_COLD_IGNORED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_INVALID_LINK
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_IN_USE_BY_OTHER
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PROFILE_API_VISIBILITY_HIDDEN
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.USER_CURRENT
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sameCopy

// Assistant service: launching apps, app clones and multiple users, and Claire's own page
/**
 * Launches [packageName] in the user copy [userId] (see [startApp]); occupancy is checked per copy only (package name
 * + user). When [userId] is Claire's own user, launch the original way (USER_CURRENT, the entry point the package
 * manager provides); for another user, first look up the launch entry in that user, then launch as that user, falling
 * back to `am start --user` when the interface is unavailable.
 */
internal fun ShellService.start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    try {
        if (inUseOnMainScreen(packageName, userId)) return START_IN_USE_ON_MAIN
        if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
    } catch (t: Throwable) {
        // When the main-screen state cannot be determined, better not to launch than to risk disturbing the user
        Log.e(TAG, "cannot inspect main-screen tasks", t)
        return START_ERROR
    }
    // The task left behind by the previous task is still behind the main display: move the whole thing back and continue on that page instead of opening a new one from scratch
    if (resumeKept(displayId, packageName, userId)) return START_OK
    val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    // A copy in another user first tries the launcher-icon path: some systems show an "open with" chooser for a programmatic launch of another copy, while the launcher path does not
    val component = intent.component
    if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
    val ok = try {
        val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
        // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
        //  requestCode, flags, profilerInfo, options, userId)
        val result = start.invoke(
            taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
            0, 0, null, options, if (userId == myUser) USER_CURRENT else userId,
        ) as Int
        if (result < 0) Log.w(TAG, "startActivityAsUser($packageName, user $userId) -> $result")
        result >= 0
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
        val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
        exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
    }
    if (ok) START_OK else START_ERROR
}

/**
 * Opens the launch page [component] in another user copy [userId] the way a launcher would:
 * ILauncherApps.startActivityAsUser (the interface behind LauncherApps.startMainActivity), with the same [options]
 * (launch onto Claire's display). The system checks whether the caller may touch that user: shell has cross-user
 * permission, and the package name matches its own uid. Returns false when the interface is missing or rejected
 * (throws), and the caller switches to startActivityAsUser.
 */
internal fun ShellService.startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
    val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
    val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
    // Since Android 11: (caller, callingPackage, callingFeatureId, component, sourceBounds, opts, user); earlier versions have no callingFeatureId
    val m = la.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 && ComponentName::class.java in it.parameterTypes }
    val args: Array<Any?> = if (m.parameterTypes.size == 7) arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
    else arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
    m.invoke(la, *args)
    true
} catch (t: Throwable) {
    Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} in user $userId failed", t)
    false
}

/**
 * The intent used to open [packageName]: in Claire's own user it is the entry point the package manager provides; in
 * another user the launch page of that user copy is looked up (the hidden interface queryIntentActivitiesAsUser, or
 * else `cmd package query-activities --user`). Null when it cannot be found.
 */
@SuppressLint("PrivateApi")
internal fun ShellService.launchIntent(packageName: String, userId: Int): Intent? {
    if (userId == myUser) return context.packageManager.getLaunchIntentForPackage(packageName)
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
    val found = try {
        val pm = context.packageManager
        val m = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        (m.invoke(pm, query, 0, userId) as List<*>).filterIsInstance<ResolveInfo>().map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivitiesAsUser failed, asking the package service", t)
        val args = ActivityQuery.args(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER, packageName = packageName, user = "$userId")
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
    }
    val (pkg, cls) = found?.firstOrNull { it.first == packageName } ?: return null
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
}

/** One of these packages has a task on another Claire display (not [displayId]): another task is using that app. When [userId] is non-null only that user copy is considered. */
internal fun ShellService.onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
    val others = synchronized(displays) { displays.keys.toSet() } - displayId
    if (others.isEmpty()) return false
    val displayField = TASK_INFO.getField("displayId")
    return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
}

/** Whether the task is the copy of [packageName] in user [userId] (rules in [sameCopy]). */
internal fun ShellService.isCopy(task: Any, packageName: String, userId: Int?): Boolean =
    sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

/** [restartApp] acting on the copy in user [userId]: only this copy's tasks are considered and only processes in this user are ended; another copy installed in a different user is left alone. */
internal fun ShellService.restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    // Never touch a display it did not create (including the main display), and never end the process first
    if (!ownDisplay(displayId)) return START_ERROR
    val decision = try {
        // Another task is using it on another Claire display: never end its process
        if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        // Starting from scratch: the copy the previous task left behind the main display was left by Claire herself, so close it first; it is not user state
        dropKept(packageName, userId)
        val displayField = TASK_INFO.getField("displayId")
        val taskDisplays = allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }
        ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, userId))
    } catch (t: Throwable) {
        Log.e(TAG, "cannot inspect tasks", t)
        return START_ERROR
    }
    if (launchIntent(packageName, userId) == null) return START_NOT_FOUND
    when (decision) {
        ColdStart.IN_USE -> START_IN_USE_ON_MAIN
        ColdStart.WARM -> start(displayId, packageName, userId).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
        ColdStart.RESTART -> {
            val user = if (userId == myUser) "current" else "$userId"
            if (exec("am", "force-stop", "--user", user, packageName) != 0) return START_ERROR
            start(displayId, packageName, userId)
        }
    }
}

/**
 * The users on this phone, read through Android's original UserInfo / UserProperties (hidden interfaces via
 * reflection): flags, type, profile group, whether running, quiet mode, whether media is shared with the current user,
 * and whether hidden from other apps. A null user list means it cannot be read; individually unreadable items are left
 * empty (counted as not satisfied, see [AppCopies]).
 */
@SuppressLint("PrivateApi")
internal fun ShellService.users(): List<AppCopies.User>? {
    val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
        ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
    val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
        ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
    val info = Class.forName("android.content.pm.UserInfo")
    // The names the system itself gives the flags (the same UserInfo.flagsToString dumpsys user uses, including vendor-defined flags); when reflection fails, read dumpsys user
    val flagsToString = runCatching { info.getMethod("flagsToString", Int::class.javaPrimitiveType) }.getOrNull()
    val dumped by lazy { sh("dumpsys", "user").takeIf { it.first == 0 }?.second?.let(AppCopies::parseDumpsys).orEmpty() }
    return list.filterNotNull().map { u ->
        val id = info.getField("id").getInt(u)
        val flags = info.getField("flags").getInt(u)
        val handle = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
        val props = runCatching { um.javaClass.getMethod("getUserProperties", UserHandle::class.java).invoke(um, handle) }.getOrNull()
        fun prop(name: String): Any? = props?.let { p -> runCatching { p.javaClass.getMethod(name).invoke(p) }.getOrNull() }
        val names = flagsToString?.let { m -> runCatching { (m.invoke(null, flags) as? String)?.let(::flagNames) }.getOrNull() }
            ?: dumped[id]?.flagNames
        val group = runCatching { info.getField("profileGroupId").getInt(u) }.getOrDefault(AppCopies.NO_PROFILE_GROUP)
        val running = runCatching { um.isUserRunning(handle) }.getOrDefault(false)
        AppCopies.User(
            id = id,
            flags = flags,
            type = runCatching { info.getField("userType").get(u) as? String }.getOrNull(),
            profileGroupId = group,
            running = running,
            unlocked = running && runCatching { um.isUserUnlocked(handle) }.getOrDefault(false),
            quiet = runCatching { um.isQuietModeEnabled(handle) }.getOrDefault(false),
            mediaShared = prop("isMediaSharedWithParent") as? Boolean,
            hidden = (prop("getProfileApiVisibility") as? Int)?.let { it == PROFILE_API_VISIBILITY_HIDDEN },
            flagNames = names,
            // parentId in dumpsys is the profile group (written only when it is neither itself nor "not in a group"); include it when read
            parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
        )
    }
}

/** Splits the flagsToString result (such as "CLONED_PROFILE|FULL|INITIALIZED"; some versions add braces) into names. */
internal fun ShellService.flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

/**
 * Opens Claire's compatibility self-check page ([SystemIntents.OWN_PAGE_CLASS]) on this display as shell, the same
 * path as [startApp] (startActivityAsUser with setLaunchDisplayId, falling back to `am start --display`). That page is
 * open only to callers holding INTERACT_ACROSS_USERS_FULL (shell has it, ordinary apps do not), and shell launches are
 * not restricted by background activity start limits. The package name must be Claire's own; no other component is
 * ever opened.
 */
internal fun ShellService.startOwnPage(displayId: Int, intent: Intent): Int {
    val component = intent.component ?: return START_INVALID_LINK
    val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
    // Only Claire can reach this service; check the package name once more (when Claire's package names cannot be queried, only the class name is checked)
    if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
    // Always open a fresh page: the old page and its task are cleared, so the new one is guaranteed to be on top of Claire's display
    val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
        val result = start.invoke(
            taskManager, null, ShellContext.SHELL_PACKAGE, null, page, null, null, null,
            0, 0, null, options, USER_CURRENT,
        ) as Int
        if (result < 0) Log.w(TAG, "startActivityAsUser(own page) -> $result")
        result >= 0
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
    }
    return if (ok) START_OK else START_ERROR
}

/**
 * The pages that can handle [intent], as (package name, fully qualified class name). When the system interface cannot
 * be used here, ask the system service instead (see [ActivityQuery]); null when neither works. [type] is the intent's
 * data type (it must be given separately on the command line).
 */
internal fun ShellService.activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
} catch (t: Throwable) {
    Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
    val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
    sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
        .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
}

internal fun ShellService.packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
    (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
}
