package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.device.access.AppAccessPolicy
import com.wangzan101.mika.core.screen.uistate.AfterTaskMarks
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import com.wangzan101.mika.core.screen.uistate.WheelOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What operating phone apps needs (the built-in assistant, accessibility) is not available right now; [hint] is
 * one sentence telling the model and the user how to fix it.
 */
class PhoneUnavailable(val hint: String) : Exception(hint)

/**
 * A phone port that is only connected when needed: [acquire] is called on the first launch_app / open_link, or for
 * system commands and event tools (connect the built-in assistant, wait for accessibility, prepare the virtual
 * screen; the screen is created when these actions need it). Tasks that only use the web or a sandbox need no
 * built-in assistant and no accessibility from start to finish. Before connecting, reading the screen yields an
 * empty screen and other actions report no_app_open, just as after connecting but before opening an app. When
 * connecting fails, this action reports phone_unavailable (with how to fix it) and the next attempt tries again.
 * Which apps are installed is asked only from the package manager ([listApps]), no connection needed; apps in
 * another user profile are asked via [appCopies] only once connected (available only when the built-in assistant is
 * already connected; never connect just for that).
 */
class LazyPhoneScreen(
    private val width: Int,
    private val height: Int,
    private val listApps: suspend () -> List<Pair<String, String>>,
    private val appCopies: suspend () -> List<AppCopy> = { emptyList() },
    override val canShareAudio: Boolean = false,
    override val canShareImage: Boolean = false,
    override val canShareVideo: Boolean = false,
    private val calendarBulkWriter: (suspend (List<SystemAction.AddCalendarEvent>) -> SystemActionResult)? = null,
    private val acquire: suspend () -> ScreenPort,
) : ScreenPort {
    private val lock = Mutex()
    @Volatile private var port: ScreenPort? = null

    /**
     * What happens to each app when the model-marked task ends; at wrap-up the task hands this to the built-in
     * assistant to execute (nothing to handle if the phone was never connected).
     */
    override val afterTask = AfterTaskMarks()

    /** This task has used the phone (already connected). */
    val acquired: Boolean get() = port != null

    private suspend fun built(): ScreenPort? = lock.withLock {
        port ?: try {
            acquire().also { port = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhoneUnavailable) {
            lastProblem = e.hint
            null
        }
    }

    @Volatile private var lastProblem: String? = null

    private fun unavailable() = ActionResult.Failed(PHONE_UNAVAILABLE, lastProblem)

    override val appAccess: AppAccessPolicy get() = port?.appAccess ?: AppAccessPolicy.ALLOW
    override suspend fun checkAppAccess() { port?.checkAppAccess() }

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = built()?.openApp(packageName, cold) ?: unavailable()
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult =
        built()?.openAppCopy(packageName, userId, cold) ?: unavailable()
    // Waits only when another task holds it, and by then the phone is long connected; nothing to wait for if not connected
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait =
        port?.awaitAppFree(packageName, userId, timeoutMs) ?: AppWait.Unsupported
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult =
        built()?.openLink(action, uri, packageName) ?: unavailable()
    override suspend fun shareText(packageName: String, text: String): ActionResult = built()?.shareText(packageName, text) ?: unavailable()
    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult =
        built()?.shareAudio(packageName, path, text) ?: unavailable()
    override suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult =
        built()?.shareImage(packageName, path, text) ?: unavailable()
    override suspend fun shareVideo(packageName: String, path: String, text: String?): ActionResult =
        built()?.shareVideo(packageName, path, text) ?: unavailable()
    override suspend fun systemAction(action: SystemAction): SystemActionResult = built()?.systemAction(action) ?: SystemActionResult(unavailable())
    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        calendarBulkWriter?.invoke(events)
            ?: built()?.bulkCalendarInsert(events)
            ?: SystemActionResult(unavailable())
    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome =
        port?.setWheel(ref, snapshotId, value) ?: WheelOutcome(false, null, null, 0, "none", error = "no_app_open")

    // ---------- Before connecting: no app is open yet ----------

    override suspend fun snapshot(): ScreenSnapshot = port?.snapshot() ?: empty()
    override suspend fun peek(): ScreenSnapshot = port?.peek() ?: empty()
    override suspend fun screenshotPng(): ByteArray? = port?.screenshotPng()
    override suspend fun readVisibleText(): List<VisibleTextLine>? = port?.readVisibleText()
    override suspend fun tapRef(ref: String, snapshotId: String) = port?.tapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun tapXY(x: Int, y: Int) = port?.tapXY(x, y) ?: NO_APP_OPEN
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = port?.doubleTapRef(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun doubleTapXY(x: Int, y: Int) = port?.doubleTapXY(x, y) ?: NO_APP_OPEN
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = port?.typeText(ref, snapshotId, text) ?: NO_APP_OPEN
    override suspend fun clearText(ref: String?, snapshotId: String?) = port?.clearText(ref, snapshotId) ?: NO_APP_OPEN
    override suspend fun typeAt(x: Int, y: Int, text: String) = port?.typeAt(x, y, text) ?: NO_APP_OPEN
    override suspend fun clearAt(x: Int, y: Int) = port?.clearAt(x, y) ?: NO_APP_OPEN
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = port?.scroll(ref, snapshotId, down) ?: NO_APP_OPEN
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = port?.swipe(x1, y1, x2, y2, durationMs) ?: NO_APP_OPEN
    override suspend fun idleScroll(fraction: Double): Boolean = port?.idleScroll(fraction) ?: false
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = port?.gesture(paths, durationMs) ?: NO_APP_OPEN
    override suspend fun back() = port?.back() ?: NO_APP_OPEN
    override suspend fun pressKey(keyCode: Int) = port?.pressKey(keyCode) ?: NO_APP_OPEN
    override suspend fun waitStable(timeoutMs: Long) = port?.waitStable(timeoutMs) ?: true
    override suspend fun awaitChange(timeoutMs: Long): Boolean = port?.awaitChange(timeoutMs) ?: super.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = port?.waitFor(condition, value, timeoutMs) ?: false
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? {
        val screen = built() ?: return null
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.nextEvent(kind, packageName, timeoutMs)
    }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> {
        val screen = built() ?: return emptyList()
        if (kind != "notification") (screen as? AndroidScreen)?.ensureDisplay()
        return screen.watchEvents(kind, packageName, durationMs, maxEvents)
    }
    override suspend fun systemCommand(command: String): SystemCommandResult? {
        val screen = built() as? AndroidScreen ?: return null
        screen.ensureDisplay()
        return screen.systemCommand(command)
    }
    override suspend fun listApps(): List<Pair<String, String>> = port?.listApps() ?: listApps.invoke()
    // Must ask the built-in assistant: never connect just for this before the phone is connected (if it cannot
    // connect, the user is asked to set it up); available only after an app has been opened
    override suspend fun otherProfiles(): Map<String, List<String>> = port?.otherProfiles() ?: emptyMap()
    override suspend fun appCopies(): List<AppCopy> = port?.appCopies() ?: appCopies.invoke()
    override suspend fun checkScreen(): ScreenCheck? = port?.checkScreen()
    override suspend fun isProtected() = port?.isProtected() ?: false
    override suspend fun frameGrid() = port?.frameGrid()

    private fun empty() = ScreenSnapshot(snapshotId(), null, emptyList(), width, height, hasWindow = false)

    companion object {
        const val PHONE_UNAVAILABLE = "phone_unavailable"
        private val NO_APP_OPEN = ActionResult.Failed("no_app_open")
    }
}
