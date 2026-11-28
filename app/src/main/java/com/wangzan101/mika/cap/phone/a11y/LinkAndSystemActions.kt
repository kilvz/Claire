package com.wangzan101.mika.cap.phone.a11y

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.ALARM_POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.ALARM_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.IN_USE_BY_OTHER
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.LAUNCH_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SETTLE_CAP_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SYSTEM_ACTION_REQUEST
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.TargetApp
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Open links / settings pages and perform system actions via standard Android intents: launch onto the virtual display, wait for it to reach the foreground, and verify who it landed on.
 * Shared [AndroidScreen] fields and methods are accessed through [screen] (bodies live inside `with(screen)`).
 */
internal class LinkAndSystemActions(private val screen: AndroidScreen) {

    /**
     * Open a link or a settings page. Creates the display on first use like [openApp]; does not open while the user is using that app on the main screen.
     * Settings pages are expected to land in the system settings package, and app-specific ones in that app; landing elsewhere (system chooser, redirect page) still succeeds, with a hint.
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        with(screen) {
            // App-specific ones were checked by the app access boundary; for unspecified ones, pick a non-blocked handler here when the list is non-empty
            val pinned = packageName ?: allowedLinkHandler(action, uri)
            val id = display.get().displayId
            val expected = pinned ?: settingsPackage.takeIf { action != null }
            if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
            val before = ScreenReader.topPackage(windows())
            val beforeTree = treeHash()
            val startedAt = System.nanoTime()
            // When the handler is known up front (app specified, settings page, a link only one app handles): mute before launch
            val early = expected ?: linkHandler(action, uri)
            early?.let { beforeOpen(it, null) }
            val code = shell.startView(id, action, uri, pinned)
            if (code != ShellService.START_OK) early?.let { notOpened(it, null) }
            when (code) {
                ShellService.START_OK -> Unit
                ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
                ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
                ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("link_unresolved")
                ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("invalid_link")
                else -> return@withContext ActionResult.Failed("launch_failed")
            }
            // The link may land in another app, the system chooser, or a new page of the same app, so the pre-launch frame must not be handed out either.
            frameGuardSince = startedAt
            fun landed(): Boolean {
                val top = ScreenReader.topPackage(windows())
                return if (expected != null) top == expected else top != null && (top != before || treeHash() != beforeTree)
            }
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (!landed() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            waitStable(SETTLE_CAP_MS)
            val top = ScreenReader.topPackage(windows())
            val changed = top != before || treeHash() != beforeTree
            if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
            // Only count the app that actually opened the link: do not treat the system chooser or anywhere else as the target
            val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
            if (opened != null) {
                // For links whose handler was unknown up front, reserve after landing; if that fails, do not retract (the shell already checked other displays)
                if (checkOtherTasks) reserve(opened)
                targetPackage = opened
                requestedUser = null
                val task = shell.topTaskId(displayId)
                _target.value = TargetApp(opened, task, userOf(task))
            }
            ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
        }
    }

    /**
     * Perform a system action via standard Android intents (see [SystemIntents]). First check who handles it on Mika's side (capability check, no display needed): none means not_supported;
     * when there are several, pick one directly via [SystemIntents.choose]. Then hand it to the built-in helper to launch onto Mika's display (creating it on first use), watching the main screen before and after as usual.
     * Alarms and timers should be set silently: afterwards read the system's next alarm to verify; if the app did open a screen, report that as-is. Alarm lists and new calendar events wait for that app to reach the top.
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        with(screen) {
            val intent = SystemIntents.toIntent(SystemIntents.spec(action))
            val pm = a11y.packageManager
            val handlers = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                    SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
            }.getOrDefault(emptyList())
            val default = defaultOf(pm, intent)
            // Only this side knows who handles it: skip blocked ones; if every capable handler is blocked, refuse
            val allowedHandlers = permitted(appAccess.current(), handlers) { it.packageName }
            val handler = SystemIntents.choose(allowedHandlers, default)
                ?: return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
            val pkg = handler.packageName
            intent.component = android.content.ComponentName(pkg, handler.className)
            if (checkOtherTasks && !action.silent && !reserve(pkg)) return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
            val alarms = a11y.getSystemService(AlarmManager::class.java)
            val alarmBefore = runCatching { alarms?.nextAlarmClock?.triggerTime }
            val id = display.get().displayId
            val topBefore = ScreenReader.topPackage(windows())
            val beforeTree = treeHash()
            val startedAt = System.nanoTime()
            val w = spill.armSpillWatch()
            // The handling screen may need a permission shell lacks (setting an alarm): prepare a version launched as Mika
            val asMika = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // Silent ones (alarms, timers) do not open a screen, so do not mute them: the clock must ring when it is due
            if (!action.silent) beforeOpen(pkg, null)
            val code = runCatching { shell.startSystemAction(id, intent, asMika) }.getOrDefault(ShellService.START_ERROR)
            if (code != ShellService.START_OK && !action.silent) notOpened(pkg, null)
            when (code) {
                ShellService.START_OK -> Unit
                ShellService.START_IN_USE_ON_MAIN -> return@withContext SystemActionResult(ActionResult.Failed("app_in_use_by_user"), pkg)
                ShellService.START_IN_USE_BY_OTHER -> return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
                ShellService.START_UNRESOLVED -> return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
                else -> return@withContext SystemActionResult(ActionResult.Failed("launch_failed"), pkg)
            }
            if (!action.silent) frameGuardSince = startedAt
            fun top() = ScreenReader.topPackage(windows())
            var alarmAfter = alarmBefore.getOrNull()
            if (action is SystemAction.SetAlarm && alarmBefore.isSuccess) {
                // The clock app usually writes in the background and registers afterwards: wait for the system's next alarm to change, for a bounded time
                val deadline = SystemClock.uptimeMillis() + ALARM_WAIT_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    alarmAfter = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
                    if (alarmAfter != alarmBefore.getOrNull()) break
                    delay(ALARM_POLL_MS)
                }
            } else if (!action.silent) {
                val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
                while (top() != pkg && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            }
            waitStable(SETTLE_CAP_MS)
            val landed = top() == pkg
            // It should have been set silently, yet that app's screen appeared on the display
            val showing = action.silent && landed && topBefore != pkg
            if (!action.silent || showing) {
                val changed = top() != topBefore || treeHash() != beforeTree
                if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
            }
            if (landed && (!action.silent || showing)) {
                targetPackage = pkg
                requestedUser = null
                val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                _target.value = TargetApp(pkg, task, userOf(task))
            }
            val hint = if (!action.silent && !landed) hintNotInFront(top(), pkg) else null
            SystemActionResult(
                ok(hint = hint, spill = spill.takeSpill(w)), handledBy = pkg,
                nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
            )
        }
    }

    /**
     * Who opens a link when the block list is non-empty and the link names no app: use the system default if it is not blocked, otherwise the first unblocked one; if every capable handler is blocked, refuse.
     * Null when the list is empty or no app can handle it (hand it to the system as usual, without an extra lookup).
     */
    private fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = screen.appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = screen.a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = permitted(access, candidates) { it }
        return defaultOf(pm, intent)?.takeIf { it in allowed } ?: allowed.first()
    }

    /** The capable handlers that are not blocked; returned as-is when the list is empty, and throws the first denial when every capable handler is blocked. */
    private fun <T> permitted(access: AppAccessState, handlers: List<T>, pkg: (T) -> String): List<T> {
        if (access.blocked.isEmpty()) return handlers
        val allowed = handlers.filter { access.denial(pkg(it)) == null }
        if (handlers.isNotEmpty() && allowed.isEmpty()) access.denial(pkg(handlers.first()))?.let { throw it }
        return allowed
    }

    /** The default handler package the system assigns to this intent; null when unreadable. */
    private fun defaultOf(pm: PackageManager, intent: Intent): String? =
        runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()

    /** Who opens a link that names no app: the one the system picks directly; null when the user must choose (the system chooser) or it cannot be read. */
    private fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        screen.a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in screen.systemPickers }

    private fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    private fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."
}
