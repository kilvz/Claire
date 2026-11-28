package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display
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
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_UNRESOLVED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PROFILE_API_VISIBILITY_HIDDEN
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.USER_CURRENT
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sameCopy

// Assistant service: launching apps, links and sharing, system actions, app clones and multiple users, and Claire's own page
/**
 * Calls IActivityTaskManager.startActivityAsUser([ShellService.startActivityAsUser]) with shell's package name, carrying [options] (which display it lands on).
 * True when the system accepted it (result >= 0); when refused, logs the result code ([what] says which launch) and returns false. Throws when the interface is unavailable, and the caller falls back to another route.
 */
internal fun ShellService.startAsShell(intent: Intent, resolvedType: String?, options: Bundle, userId: Int, what: String): Boolean {
    // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
    //  requestCode, flags, profilerInfo, options, userId)
    val result = startActivityAsUser.invoke(
        taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, resolvedType, null, null,
        0, 0, null, options, userId,
    ) as Int
    if (result < 0) Log.w(TAG, "startActivityAsUser($what) -> $result")
    return result >= 0
}

/**
 * Occupancy check before launching: [onMain] true (the user is using it on the main screen) -> [START_IN_USE_ON_MAIN]; [packages] on another of Claire's screens
 * (only that user when [userId] is non-null) -> [START_IN_USE_BY_OTHER]; neither -> null.
 * When task state cannot be read it is [START_ERROR]: better not to launch than to risk disturbing the user.
 */
internal inline fun ShellService.inUseCode(packages: Collection<String>, displayId: Int, userId: Int? = null, onMain: () -> Boolean): Int? = try {
    when {
        onMain() -> START_IN_USE_ON_MAIN
        onOtherOwnDisplay(packages, displayId, userId) -> START_IN_USE_BY_OTHER
        else -> null
    }
} catch (t: Throwable) {
    Log.e(TAG, "cannot inspect main-screen tasks", t)
    START_ERROR
}

/** Whether any of these packages that can take links or system actions has a task on the main screen (rules in [ShellLinks.inUse]). */
internal fun ShellService.linkInUseOnMain(packages: List<String>): Boolean =
    ShellLinks.inUse(packages, tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet())

/**
 * Launches [packageName] in the user [userId] (see [startApp]); occupancy only looks at that copy (package name + user).
 * When [userId] is Claire's own user it launches the old way (USER_CURRENT, the entry point from the package manager); for another user it first looks up that user's entry point,
 * then launches as that user, falling back to `am start --user` when the interface is unavailable.
 */
internal fun ShellService.start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    inUseCode(listOf(packageName), displayId, userId) { inUseOnMainScreen(packageName, userId) }?.let { return it }
    // A task left behind the main screen by the previous task is still there: bring the whole thing back and continue from that page instead of starting a new one from the beginning
    if (resumeKept(displayId, packageName, userId)) return START_OK
    val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    // For another user, try the launcher path first: some systems show a "open with" picker when another copy is launched by an app, while the launcher path does not
    val component = intent.component
    if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
    val ok = try {
        startAsShell(intent, null, options, if (userId == myUser) USER_CURRENT else userId, "$packageName, user $userId")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
        val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
        exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
    }
    if (ok) START_OK else START_ERROR
}

/**
 * Opens the launch page [component] of another user [userId] the way a launcher would: ILauncherApps.startActivityAsUser (the interface behind
 * LauncherApps.startMainActivity), with the same [options] (launching onto Claire's screen). The system checks the caller against that user: shell has cross-user permission,
 * and the package name matches its own uid. Returns false when the interface is absent or refuses (throws), and the caller falls back to startActivityAsUser.
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
 * The intent for opening [packageName]: in Claire's own user it is the entry point the package manager gives; in another user it looks up that user's launch page
 * (the hidden queryIntentActivitiesAsUser, falling back to `cmd package query-activities --user`). null when none is found.
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

/** Whether any of these packages has a task on another of Claire's screens (not [displayId]): that is an app another task is using. Only that user is considered when [userId] is non-null. */
internal fun ShellService.onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
    val others = synchronized(displays) { displays.keys.toSet() } - displayId
    if (others.isEmpty()) return false
    val displayField = TASK_INFO.getField("displayId")
    return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
}

/** Whether the task is the copy of [packageName] in the user [userId] (rules in [sameCopy]). */
internal fun ShellService.isCopy(task: Any, packageName: String, userId: Int?): Boolean =
    sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

/** [restartApp] acts on the copy in the user [userId]: only that copy's tasks are considered and only that user's process is ended; a copy installed in another user is untouched. */
internal fun ShellService.restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    // Never touch a screen we did not create (including the main screen), and never end its process first
    if (!ownDisplay(displayId)) return START_ERROR
    val decision = try {
        // Another task is using it on another of Claire's screens: never end its process
        if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        // Starting from scratch: the copy left behind the main screen by the previous task is Claire's own, so close it first; it does not count as the user's state
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
 * The users of this phone, read from Android's own UserInfo / UserProperties (hidden interfaces by reflection): flags, type, profile group, whether running, quiet mode,
 * whether media is shared with the current user, and whether hidden from other apps. null when the user list cannot be read; a field that cannot be read is left empty (counts as not satisfied, see [AppCopies]).
 */
@SuppressLint("PrivateApi")
internal fun ShellService.users(): List<AppCopies.User>? {
    val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
        ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
    val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
        ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
    val info = Class.forName("android.content.pm.UserInfo")
    // The names the system gives its own flags (the same UserInfo.flagsToString used by dumpsys user, including vendor flags); when reflection fails, read dumpsys user
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
            // parentId in dumpsys is the profile group (written only when not self and not "no group"); carry it when read
            parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
        )
    }
}

/** Splits the result of flagsToString (such as "CLONED_PROFILE|FULL|INITIALIZED", with braces on some versions) into names. */
internal fun ShellService.flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

/**
 * Opens Claire's compatibility self-check page ([SystemIntents.OWN_PAGE_CLASS]) on this display as shell, by the same route as [startApp]
 * (startActivityAsUser with setLaunchDisplayId, falling back to `am start --display`). That page is open only to callers holding INTERACT_ACROSS_USERS_FULL
 * (shell has it, ordinary apps do not); shell launches are not subject to background-activity-start limits. The package name must be Claire's own; no other component is opened.
 */
internal fun ShellService.startOwnPage(displayId: Int, intent: Intent): Int {
    val component = intent.component ?: return START_INVALID_LINK
    val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
    // Only Claire can reach this service; check the package name again (when Claire's package name cannot be read, only the class name is trusted)
    if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
    // Open a fresh page every time: the old page and its task are cleared, and the new one is always on top of Claire's screen
    val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(page, null, options, USER_CURRENT, "own page")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
    }
    return if (ok) START_OK else START_ERROR
}

/**
 * Pages that can take [intent], as (package name, fully qualified class name). When the system interface cannot look it up from this side it asks the system service (see [ActivityQuery]); null when neither works.
 * [type] is the intent's data type (given separately on the command line).
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

/** The implementation of [ShellService.startView]. */
internal fun ShellService.launchView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    ShellLinks.refuse(action, uri, packageName)?.let { return it }
    val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    uri?.let { intent.data = Uri.parse(it) }
    packageName?.let { intent.setPackage(it) }
    // Web-style links go only to pages that declare they open from links; app-internal pages are not touched
    if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
    val candidates = activitiesFor(intent, null) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    val handlers = candidates.map { it.first }.distinct()
    inUseCode(handlers, displayId) { linkInUseOnMain(handlers) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, null, options, USER_CURRENT, "link")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
            ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
        exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** The implementation of [ShellService.startShare]. */
internal fun ShellService.launchShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    val intent = ShareIntent.of(packageName, text)
    val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    inUseCode(listOf(packageName), displayId) { inUseOnMainScreen(packageName) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, ShareIntent.TYPE, options, USER_CURRENT, "share")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** The implementation of [ShellService.startShareFile]. */
internal fun ShellService.launchShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    val target = Uri.parse(uri)
    val intent = ShareIntent.ofFile(packageName, target, mime, text)
    val candidates = activitiesFor(intent, mime) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    inUseCode(listOf(packageName), displayId) { inUseOnMainScreen(packageName) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, mime, options, USER_CURRENT, "share file")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec(*ShareIntent.amArgsFile(displayId, packageName, target, mime, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** The implementation of [ShellService.startSystemAction]. */
internal fun ShellService.launchSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    // Claire's own compatibility self-check page: opened directly onto this display as shell
    if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
    if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    val handlers = candidates.map { it.first }.distinct()
    inUseCode(handlers, displayId) { linkInUseOnMain(handlers) }?.let { return it }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
    val asShell = try {
        startAsShell(intent, intent.type, options.toBundle(), USER_CURRENT, "${intent.action}")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
        false
    }
    if (asShell) return START_OK
    if (asMika == null) return START_ERROR
    // Since Android 14 the sender of a PendingIntent must explicitly allow it to start activities from the background
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // ALLOWED is deprecated on Android 16; ALLOW_ALWAYS keeps the background start permitted there.
        @Suppress("DEPRECATION")
        val startMode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        options.setPendingIntentBackgroundActivityStartMode(startMode)
    }
    return try {
        asMika.send(context, 0, null, null, null, null, options.toBundle())
        START_OK
    } catch (t: Throwable) {
        Log.w(TAG, "sending ${intent.action} as Claire failed", t)
        START_ERROR
    }
}
