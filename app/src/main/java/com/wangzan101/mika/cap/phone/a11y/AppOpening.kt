package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.LaunchChooser
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Opening apps / sharing / links: launches onto the virtual display, waits for it to reach the foreground,
 * and recognizes the system chooser that asks which app to open.
 * The shared [AndroidScreen] fields and methods are accessed through [screen] by forward reference.
 */
internal class AppOpening(private val screen: AndroidScreen) {

    suspend fun openApp(packageName: String, cold: Boolean): ActionResult = open(packageName, null, cold)

    /**
     * Opens the copy installed in another user [userId] (the caller has already checked it against [appCopies]): the built-in assistant
     * re-checks whether that user is usable by the same rules before launching, and reports copy_not_found if not; occupancy and cold start
     * only consider this copy. When [userId] is Claire's own user this is equivalent to [openApp].
     */
    suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        open(packageName, userId.takeIf { it != AndroidScreen.myUser }, cold)

    /**
     * Waits for other tasks to release [packageName] (the model asked to wait when opening it): released means it is no longer on
     * another display and no other task holds it in the occupancy registry.
     * Only meaningful with parallel tasks; otherwise no other task is competing for it.
     */
    suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        if (!screen.checkOtherTasks) return AppWait.Unsupported
        val user = userId?.takeIf { it != AndroidScreen.myUser }
        return screen.awaitApp(packageName, user, timeoutMs) {
            withContext(Dispatchers.IO) { !onOtherDisplay(packageName, user) && screen.appFree(packageName, user) }
        }
    }

    /** This package (the [user] copy) is currently open on another Claire display (held by another task). */
    internal fun onOtherDisplay(packageName: String, user: Int?): Boolean {
        val other = runCatching { if (user == null) screen.shell.displayOfPackage(packageName) else screen.shell.displayOfPackageInUser(packageName, user) }.getOrDefault(-1)
        return other >= 0 && other != screen.display.current?.displayId
    }

    /** The common part of [openApp] / [openAppCopy]; a null [user] means the original copy. */
    internal suspend fun open(packageName: String, user: Int?, cold: Boolean): ActionResult = withContext(Dispatchers.IO) {
        // The same app cannot be on two displays at once: the system moves the whole task over, effectively taking it from another task. When two copies are installed, each copy is counted separately
        if (screen.checkOtherTasks) {
            if (onOtherDisplay(packageName, user)) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!(if (user == null) screen.reserve(packageName) else screen.reserveCopy(packageName, user))) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        // The top window before opening: only what appears afterwards can be a chooser caused by this launch (opening again without having picked last time shows the same chooser)
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        // Frame freshness starts from this launch: later screenshots and text recognition only accept frames that arrive after it (see FrameFreshness)
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, user)
        fun launch() = when {
            user != null -> screen.shell.startAppInUser(id, packageName, user, cold)
            cold -> screen.shell.restartApp(id, packageName)
            else -> screen.shell.startApp(id, packageName)
        }
        var code = launch()
        val retryUntil = SystemClock.uptimeMillis() + AndroidScreen.IN_USE_RETRY_MS
        while (code == ShellService.START_IN_USE_ON_MAIN && SystemClock.uptimeMillis() < retryUntil) {
            delay(AndroidScreen.IN_USE_POLL_MS)
            code = launch()
        }
        if (code != ShellService.START_OK && code != ShellService.START_OK_COLD_IGNORED) screen.notOpened(packageName, user)
        when (code) {
            ShellService.START_OK, ShellService.START_OK_COLD_IGNORED -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_NOT_FOUND -> return@withContext ActionResult.Failed("app_not_found")
            ShellService.START_USER_NOT_ALLOWED -> return@withContext ActionResult.Failed(AndroidScreen.COPY_NOT_FOUND)
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        // The same app merely returning to an existing static page may produce no new frame, and the old frame is still the current screen; a cold start, another user copy or an app switch must wait for a new frame.
        val needsNewFrame = cold || user != null || before != packageName
        awaitOpened(packageName, before, hint = if (code == ShellService.START_OK_COLD_IGNORED) AndroidScreen.HINT_COLD_IGNORED else null, user = user,
            launchedAt = if (needsNewFrame) startedAt else 0L)
    }

    /**
     * Shares text to [packageName]: like [openApp] it creates the display only on first use and does not open it while the user is using
     * it on the main display, and after opening it likewise waits for it to reach the foreground and recognizes the system chooser. An app
     * that does not accept text shares is cannot_share. Only the share page is opened; whether to send is up to the model tapping on that page.
     */
    suspend fun shareText(packageName: String, text: String): ActionResult = withContext(Dispatchers.IO) {
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShare(id, packageName, text) }.getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    val canShareAudio: Boolean get() = screen.shareAudioFile != null
    val canShareImage: Boolean get() = screen.shareImageFile != null
    val canShareVideo: Boolean get() = screen.shareVideoFile != null

    /** The same open/reserve/wait flow as [shareText]; the audio is first turned into a content URI by [screen.shareAudioFile]. Only the share page is opened, nothing is sent. */
    suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult = withContext(Dispatchers.IO) {
        val share = screen.shareAudioFile?.invoke(path) ?: return@withContext ActionResult.Failed("unsupported")
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShareFile(id, packageName, share.uri.toString(), share.mime, text.orEmpty()) }
            .getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /** Same as [shareAudio], except the image is handed to the target app. */
    suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult = withContext(Dispatchers.IO) {
        val share = screen.shareImageFile?.invoke(path) ?: return@withContext ActionResult.Failed("unsupported")
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShareFile(id, packageName, share.uri.toString(), share.mime, text.orEmpty()) }
            .getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /** 和 [shareAudio] 相同,只是把视频交给目标 App。 */
    suspend fun shareVideo(packageName: String, path: String, text: String?): ActionResult = withContext(Dispatchers.IO) {
        val share = screen.shareVideoFile?.invoke(path) ?: return@withContext ActionResult.Failed("unsupported")
        if (screen.checkOtherTasks) {
            val other = runCatching { screen.shell.displayOfPackage(packageName) }.getOrDefault(-1)
            if (other >= 0 && other != screen.display.current?.displayId) return@withContext AndroidScreen.IN_USE_BY_OTHER
            if (!screen.reserve(packageName)) return@withContext AndroidScreen.IN_USE_BY_OTHER
        }
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        screen.beforeOpen(packageName, null)
        val code = runCatching { screen.shell.startShareFile(id, packageName, share.uri.toString(), share.mime, text.orEmpty()) }
            .getOrDefault(ShellService.START_ERROR)
        if (code != ShellService.START_OK) screen.notOpened(packageName, null)
        when (code) {
            ShellService.START_OK -> Unit
            ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
            ShellService.START_IN_USE_BY_OTHER -> return@withContext AndroidScreen.IN_USE_BY_OTHER
            ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("cannot_share")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(packageName, before, hint = null, sharing = true, launchedAt = startedAt)
    }

    /**
     * Opens Claire's compatibility self-test page on Claire's display ([intent] names the component; used only for the compatibility
     * self-test). That page is exposed only to callers at the shell level, so it is handed to the built-in assistant to launch directly
     * onto this display as shell (the assistant only accepts this one component); a shell launch is not subject to background
     * activity-launch restrictions, whether or not Claire is in the foreground.
     * Afterwards it waits for the page to reach the top of Claire's display like [openApp]; the display is created on first use.
     */
    suspend fun openOwnPage(intent: Intent): ActionResult = withContext(Dispatchers.IO) {
        val before = if (screen.hasDisplay) screen.topWindowPackage().takeIf { it != screen.chooserPackage } else null
        val id = screen.display.get().displayId
        val startedAt = System.nanoTime()
        when (runCatching { screen.shell.startSystemAction(id, intent, null) }.getOrDefault(ShellService.START_ERROR)) {
            ShellService.START_OK -> Unit
            ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("own_page_not_allowed")
            else -> return@withContext ActionResult.Failed("launch_failed")
        }
        awaitOpened(screen.a11y.packageName, before, hint = null, launchedAt = if (before != screen.a11y.packageName) startedAt else 0L)
    }

    /**
     * After the launch is issued: waits for [packageName] to reach the top of Claire's display, or for the system to first ask which to
     * open ([before] is the package of the top window before the launch).
     * [sharing]: this launch is a share (when an app has several share-target pages the system asks which one, and the options do not
     * necessarily contain the app name): a window of a system package replacing the top also counts as the system asking.
     */
    internal suspend fun awaitOpened(
        packageName: String, before: String?, hint: String?, sharing: Boolean = false, user: Int? = null, launchedAt: Long = 0L,
    ): ActionResult {
        val beforeTree = screen.treeHash()
        screen.targetPackage = packageName
        screen.chooserPackage = null
        screen.requestedUser = user
        // From the waiting-for-window stage up to reaching the top, guard every return path (including the system chooser); old frames are not blocked only on the final success path.
        // A static page of the same app need not wait for another new frame, but that must not be used to clear a previous guard that no new frame has released yet.
        if (launchedAt > 0) screen.frameGuardSince = launchedAt
        // Only when it is really still on the original page may the original static frame be used after a failure; once it has switched away or changed pages a new frame is still required.
        fun clearGuardIfUnchanged() {
            val now = screen.foregroundTaskPackage() ?: ScreenReader.topPackage(screen.windows())
            if (now == before && screen.treeHash() == beforeTree) screen.frameGuardSince = null
        }
        // Opened: it is the top task on Claire's display (when the task cannot be queried, fall back to the top window read through accessibility); when a specific copy was requested, the task must also belong to that user
        fun launched(): Boolean = (screen.foregroundTaskPackage()?.let { it == packageName } ?: (ScreenReader.topPackage(screen.windows()) == packageName)) &&
            (user == null || screen.userOf(runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)).let { it < 0 || it == user })
        fun picker(): String? = if (sharing) screen.systemPickerOf(packageName, before) else null
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
        // When the system asks which to open first, the app will not come to the foreground by itself: stop waiting idly as soon as the chooser is seen
        while (!launched() && screen.chooserOf(packageName, before) == null && picker() == null && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
        screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
        val onTop = launched()
        val task = if (onTop) screen.shell.topTaskId(screen.displayId) else -1
        screen._target.value = AndroidScreen.TargetApp(packageName, task, if (task >= 0) screen.userOf(task) else user ?: -1)
        if (!onTop && user != null) {
            // The other copy was requested but the system asks which to open first: tap through the options ourselves, identify which copy opened, and do not leave it to the model to guess
            if (chooserTargets(packageName, before) != null) return resolveCopyChooser(packageName, user, before, hint)
                .also { if (it is ActionResult.Failed) clearGuardIfUnchanged() }
            // What opened directly is the other copy of this app: fail truthfully rather than treat it as the requested copy
            if (screen.foregroundTaskPackage() == packageName) return copyNotOpened(user, screen.userOf(runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)))
                .also { clearGuardIfUnchanged() }
        }
        // The system is asking which to open (the app is installed twice, etc.): count it as opened and let the model choose; once chosen and the app reaches the foreground, later actions treat it as the target as usual.
        // A system window covering the app while it is already on top (permission dialog) does not count
        if (!onTop) screen.chooserOf(packageName, before)?.let { first ->
            // Only ask how many copies are installed the first time this happens, so the other copy can be marked
            if (screen.profiles == null) screen.otherProfiles()
            val choice = screen.chooserOf(packageName, before) ?: first
            screen.chooserPackage = choice.packageName
            return screen.ok().copy(chooser = choice, appSwitched = null, switchedByTarget = false)
        }
        // On a share the system asks which share page of this app to use: this likewise counts as opened; let the model choose
        if (!onTop) picker()?.let { pkg ->
            screen.chooserPackage = pkg
            return screen.ok(hint = AndroidScreen.HINT_SHARE_PICKER).copy(appSwitched = null, switchedByTarget = false)
        }
        // If it did not open, say so truthfully and state what is on top right now; do not report success
        if (!onTop) {
            clearGuardIfUnchanged()
            val now = screen.foregroundTaskPackage() ?: ScreenReader.topPackage(screen.windows())
            // The other copy was requested, yet what is on top is a different copy of this app
            val copy = if (user != null && now == packageName) " (a different copy of the app)" else ""
            return ActionResult.Failed("app_not_in_front", hint = now?.let { "In the foreground now: $it$copy." } ?: "Nothing came to the foreground.")
        }
        // A stable tree only means the window reached the top; the image may not be composited yet: wait for the first frame after this launch,
        // and if none arrives keep blocking old frames so the ghost of the previous screen left in the buffer is not handed to the model as this launch's screen (issue #253)
        if (launchedAt > 0) screen.awaitFrameAfter(launchedAt)
        // Opened, but a window of another package covers it (permission popup, account picker): say truthfully who it is; it still counts as opened
        val cover = ScreenReader.topPackage(screen.windows())?.takeIf { it != packageName }
        return screen.ok(hint = hint).let { if (cover != null) it.copy(coveredBy = cover, appSwitched = null, switchedByTarget = false) else it }
    }

    /**
     * The copy in another user [user] was requested, but the system asks on Claire's display which to open (the options may have
     * identical names, so there is no telling which is which): tap the options one by one, wait for one to open, and read the user that owns
     * the top task; if it is the requested copy, count it as opened. If not, press back once to send it to the background (it may be the
     * task of the copy the user holds; do not finish it), launch again and tap the next one. If none of them matches, fail truthfully with
     * copy_not_opened and say which copy did open; never treat the wrong copy as success.
     */
    internal suspend fun resolveCopyChooser(packageName: String, user: Int, before: String?, hint: String?): ActionResult {
        fun topTask() = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        suspend fun opened(): ActionResult? {
            if (screen.foregroundTaskPackage() != packageName) return null
            val task = topTask()
            if (screen.userOf(task) != user) return null
            screen._target.value = AndroidScreen.TargetApp(packageName, task, user)
            return screen.ok(hint = hint)
        }
        var tried = 0
        var shown: Int? = null
        while (true) {
            val targets = chooserTargets(packageName, before) ?: break
            if (tried >= targets.size) break
            val (x, y) = targets[tried++]
            if (!runCatching { screen.shell.injectTap(screen.displayId, x, y) }.getOrDefault(false)) break
            val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
            while (screen.foregroundTaskPackage() != packageName && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
            screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
            if (screen.foregroundTaskPackage() != packageName) break
            opened()?.let { return it }
            shown = screen.userOf(topTask())
            Log.i(AndroidScreen.TAG, "chooser option $tried opened user $shown, not the requested $user")
            runCatching { screen.shell.injectKey(screen.displayId, KeyEvent.KEYCODE_BACK) }
            screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
            if (tried >= targets.size) break
            if (runCatching { screen.shell.startAppInUser(screen.displayId, packageName, user, false) }.getOrDefault(ShellService.START_ERROR) != ShellService.START_OK) break
            val until = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
            while (chooserTargets(packageName, before) == null && SystemClock.uptimeMillis() < until) {
                // This time the requested copy opened directly
                opened()?.let { return it }
                delay(AndroidScreen.POLL_MS)
            }
        }
        return copyNotOpened(user, shown)
    }

    /** The requested [user] copy did not open; [shown] is the user owning the copy that did open (null / negative when nothing opened or it cannot be read). */
    internal fun copyNotOpened(user: Int, shown: Int?): ActionResult.Failed {
        val what = when {
            shown == null || shown < 0 -> "Nothing from the app came to the front."
            shown == AndroidScreen.myUser -> "The main app opened instead (user $shown)."
            else -> "The copy in user $shown opened instead."
        }
        return ActionResult.Failed(AndroidScreen.COPY_NOT_OPENED, "$what Requested: the copy in user $user.")
    }

    /** When the system asks on Claire's display which to open, the center points of those options (screen pixels, in the same order as the options); null when it is not asking. Recognized the same way as [chooserOf]. */
    internal fun chooserTargets(packageName: String, before: String?): List<Pair<Int, Int>>? {
        val w = ScreenReader.contentWindows(screen.windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !screen.isSystemPackage(pkg)) return null
        val label = screen.appLabel(packageName) ?: return null
        val entries = ScreenReader.read(listOf(w), screen.screenWidth, screen.screenHeight).entries
        val picked = LaunchChooser.optionIndices(label, entries.map { it.element }) ?: return null
        return picked.map { i -> android.graphics.Rect().also(entries[i].node::getBoundsInScreen).let { it.centerX() to it.centerY() } }
    }
}
