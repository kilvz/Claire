package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.cap.phone.GesturePacing
import com.wangzan101.mika.core.cap.phone.TypingPacing
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.media.ShareableFile
import com.wangzan101.mika.core.screen.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.AppCopy
import com.wangzan101.mika.core.screen.uistate.AppProfiles
import com.wangzan101.mika.core.screen.uistate.LaunchChoice
import com.wangzan101.mika.core.screen.uistate.LaunchChooser
import com.wangzan101.mika.core.screen.uistate.OpenedElsewhere
import com.wangzan101.mika.core.screen.uistate.UnseenField
import com.wangzan101.mika.core.screen.uistate.normalize
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenEvent
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.VisibleTextLine
import com.wangzan101.mika.core.screen.uistate.SystemCommandResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import com.wangzan101.mika.core.screen.uistate.WheelOutcome
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.screen.visual.FrameGrid
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.screen.uistate.TextField
import com.wangzan101.mika.core.screen.uistate.typeAndVerify
import com.wangzan101.mika.core.screen.uistate.relocate
import com.wangzan101.mika.core.screen.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.core.screen.uistate.BackAffordance
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.cap.phone.shell.ShellService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android implementation of [ScreenPort]: reads the accessibility windows of the Agent's virtual display and operates on that screen.
 * After every action it waits for the UI to go quiet, verifies whether the action took effect using the control-tree fingerprint (assisted by the frame), and checks whether the foreground was switched away by another app.
 * The virtual display is created only when needed (the first launch_app, or system commands and event tools): before it exists, reading the screen returns an empty screen and other actions fail with [NO_APP_OPEN]; those actions never create the display themselves.
 * Having a display does not mean an app has been opened: for a task that only runs system commands, the screen is empty.
 */
class AndroidScreen(
    internal val a11y: ScreenAccessibility,
    internal val shell: IMikaShell,
    internal val display: LazyAgentDisplay,
    /** True while the clipboard is temporarily replaced with Claire's text, false after it is restored; the task service records this and restores it on the next start after an unexpected process exit. */
    internal val clipHeld: (Boolean) -> Unit = {},
    /** The clipboard is global: when several tasks run at once, the paste fallback shares one lock. */
    internal val clipLock: Lock = ReentrantLock(),
    /** True for parallel tasks: before opening an app, check whether it is in use by another task on another Claire screen. */
    internal val checkOtherTasks: Boolean = false,
    /** For parallel tasks, reserves this package for the current task; returns false when another task already holds it (and is still using it). */
    internal val reserve: (String) -> Boolean = { true },
    /** Like [reserve], but reserves the copy installed under another user (user id): reserved separately from the original so two tasks can each use one. */
    internal val reserveCopy: (String, Int) -> Boolean = { _, _ -> true },
    /** After a successful clipboard swap, hands over the user's original text and the text Claire put in, for an encrypted record (used to restore after the process is killed). */
    internal val clipSaved: (original: String, ours: String) -> Unit = { _, _ -> },
    /** The app is currently in the user's hands (being taken over): whatever opens on the main screen then is the user's business, so it is left alone. */
    internal val handedOver: () -> Boolean = { false },
    /** Gesture pacing: pause before touching down, press duration, landing-point jitter, and the curve and speed of swipes (see [GesturePacing]). */
    internal val pacing: GesturePacing = GesturePacing.human(),
    /** Typing pace inside the app with this package name (see [TypingPacing]); by default the whole text is written at once. */
    internal val typing: (String?) -> TypingPacing = { TypingPacing.Instant },
    /** Before a launch is issued: what is being opened is [packageName] (the copy installed for user [user]; null means the original). The task service mutes audio first based on this, so nothing plays at the start. */
    internal val beforeOpen: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    /** The launch was rejected or nothing opened: undoes what [beforeOpen] did in advance. */
    internal val notOpened: (packageName: String, user: Int?) -> Unit = { _, _ -> },
    override val appAccess: com.wangzan101.mika.core.device.access.AppAccessPolicy = com.wangzan101.mika.core.device.access.AppAccessPolicy.ALLOW,
    /** Turns audio in the workspace into a shareable content URI; when null, this screen does not support sharing audio. */
    internal val shareAudioFile: (suspend (path: String) -> ShareableFile?)? = null,
    /** Turns images in the workspace into a shareable content URI; when null, this screen does not support sharing images. */
    internal val shareImageFile: (suspend (path: String) -> ShareableFile?)? = null,
    /** For parallel tasks: checks the reservation registry for whether the current task can reserve this package right now (the [user] copy; null means the original). Check only, no reservation. */
    internal val appFree: (packageName: String, user: Int?) -> Boolean = { _, _ -> true },
    /**
     * For parallel tasks: waits up to that long for another task to release this package; [free] is the complete check (it is not on another screen and nobody holds it in the registry).
     * The task service uses this to record 'who is waiting for whom', detect mutual waits, and while waiting show 'waiting for another task to finish with an app' in the UI.
     */
    internal val awaitApp: suspend (packageName: String, user: Int?, timeoutMs: Long, free: suspend () -> Boolean) -> AppWait =
        { _, _, _, _ -> AppWait.Unsupported },
) : ScreenPort {
    /** -1 when the display has not been created yet; only use after confirming the display exists. */
    internal val displayId get() = display.current?.displayId ?: -1
    /** The display has been created (which does not mean an app has been opened). */
    internal val hasDisplay get() = display.current != null
    internal val screenWidth get() = display.current?.width ?: display.width
    internal val screenHeight get() = display.current?.height ?: display.height
    internal val ids = NodeIds()

    /** The package the most recent successful launch_app opened, used to detect being switched away. */
    @Volatile internal var targetPackage: String? = null

    /** The package of the window where the system asked 'which app to open' during the most recent app launch: while it is in the foreground, that does not count as being switched away. */
    @Volatile internal var chooserPackage: String? = null

    /**
     * The app the Agent is using and its task id (-1 when it was not on top at open time); the task service uses this to mute audio and watch whether it gets moved away.
     * [userId] is the user that task belongs to (which copy it is when the app is installed twice); -1 when it cannot be read.
     */
    data class TargetApp(val packageName: String, val taskId: Int, val userId: Int = -1)

    internal val _target = MutableStateFlow<TargetApp?>(null)
    val target: StateFlow<TargetApp?> = _target.asStateFlow()

    internal val systemPickers: Set<String> by lazy { SystemPickers.resolve(a11y.packageManager) }

    /** The package that hosts system settings (resolved via Settings.ACTION_SETTINGS): settings pages the target app opens in its own task do not count as being switched away. */
    internal val settingsPackage: String? by lazy { SystemPickers.settingsPackage(a11y.packageManager) }

    // ---------- Screen reading / screenshots / events ----------

    /** Sub-object for screen reading / screenshots / events, see [ScreenCapture]. */
    internal val capture = ScreenCapture(this)

    internal fun windows() = capture.windows()
    internal fun read() = capture.read()
    internal fun treeHash() = capture.treeHash()

    suspend fun accessPackages(): List<String?> = capture.accessPackages()
    suspend fun ensureDisplay() { capture.ensureDisplay() }

    override suspend fun snapshot(): ScreenSnapshot = capture.snapshot()
    override suspend fun peek(): ScreenSnapshot = capture.peek()
    internal suspend fun smsSnapshot(register: Boolean): ScreenSnapshot = capture.smsSnapshot(register)
    internal fun foregroundTaskPackage(): String? = capture.foregroundTaskPackage()
    fun foregroundApps(): Flow<TargetApp> = capture.foregroundApps()
    override suspend fun screenshotPng(): ByteArray? = capture.screenshotPng()
    internal fun frameServable(): Boolean = capture.frameServable()
    internal suspend fun awaitFrameAfter(startedAt: Long) = capture.awaitFrameAfter(startedAt)
    override suspend fun readVisibleText(): List<VisibleTextLine>? = capture.readVisibleText()
    override suspend fun frameGrid(): FrameGrid? = capture.frameGrid()
    override suspend fun checkScreen(): ScreenCheck = capture.checkScreen()
    override suspend fun waitStable(timeoutMs: Long): Boolean = capture.waitStable(timeoutMs)
    override suspend fun awaitChange(timeoutMs: Long): Boolean = capture.awaitChange(timeoutMs)
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long): Boolean = capture.waitFor(condition, value, timeoutMs)
    internal fun containsText(node: com.wangzan101.mika.core.screen.uistate.UiTreeNode, text: String): Boolean = capture.containsText(node, text)
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long): ScreenEvent? = capture.nextEvent(kind, packageName, timeoutMs)
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int): List<ScreenEvent> = capture.watchEvents(kind, packageName, durationMs, maxEvents)


    /** Monotonic clock time (nanoseconds) at which the most recent launch started, compared against frame timestamps from the virtual-display producer. */
    @Volatile internal var frameGuardSince: Long? = null


    // ---------- Actions ----------

    /** Tap / swipe / scroll / back and other actions, see [ScreenActions]. */
    internal val actions = ScreenActions(this)

    override suspend fun systemCommand(command: String): SystemCommandResult? = actions.systemCommand(command)
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = actions.tapRef(ref, snapshotId)
    override suspend fun tapXY(x: Int, y: Int): ActionResult = actions.tapXY(x, y)
    override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = actions.doubleTapRef(ref, snapshotId)
    override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = actions.doubleTapXY(x, y)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = actions.swipe(x1, y1, x2, y2, durationMs)
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long): ActionResult = actions.gesture(paths, durationMs)
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = actions.scroll(ref, snapshotId, down)
    override suspend fun idleScroll(fraction: Double): Boolean = actions.idleScroll(fraction)
    override suspend fun back(): ActionResult = actions.back()
    override suspend fun pressKey(keyCode: Int): ActionResult = actions.pressKey(keyCode)


    /** Typing / pasting / clearing, see [TextInput]. */
    internal val textInput = TextInput(this)

    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = textInput.typeText(ref, snapshotId, text)

    override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult = textInput.clearText(ref, snapshotId)

    override suspend fun typeAt(x: Int, y: Int, text: String): ActionResult = textInput.typeAt(x, y, text)

    override suspend fun clearAt(x: Int, y: Int): ActionResult = textInput.clearAt(x, y)

    fun keyboardUp(): Boolean = textInput.keyboardUp()


    /** SMS login, see [SmsLogin]. */
    internal val smsLogin = SmsLogin(this)

    suspend fun prepareSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.prepareSmsLogin(p, authorize)
    suspend fun submitSmsLogin(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.submitSmsLogin(p, code, authorize)
    suspend fun smsLoginOutcome(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray): com.wangzan101.mika.core.cap.phone.sms.SmsLoginOutcome = smsLogin.smsLoginOutcome(p, code)
    suspend fun fillSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, code: CharArray, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.fillSmsLoginCode(p, code, authorize)
    suspend fun requestSmsLoginCode(p: com.wangzan101.mika.core.cap.phone.sms.SmsLoginProfile, authorize: (() -> Boolean) -> Boolean): Boolean = smsLogin.requestSmsLoginCode(p, authorize)


    /**
     * Launches onto the virtual display; with [cold], first kills the process so the app returns to its initial page. If the user is using this app on the main screen, neither launch nor kill it,
     * retry after a short wait, and if that still fails, leave it to the Agent to pause or ask the user to take over. The virtual display is created here on first use.
     */
    // ---------- Opening / sharing / links ----------

    /** Opening apps / sharing / links, see [AppOpening]. */
    internal val opening = AppOpening(this)

    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult = opening.openApp(packageName, cold)
    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult = opening.openAppCopy(packageName, userId, cold)
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait = opening.awaitAppFree(packageName, userId, timeoutMs)
    override suspend fun shareText(packageName: String, text: String): ActionResult = opening.shareText(packageName, text)
    override suspend fun shareAudio(packageName: String, path: String, text: String?): ActionResult = opening.shareAudio(packageName, path, text)
    override val canShareAudio: Boolean get() = opening.canShareAudio
    override suspend fun shareImage(packageName: String, path: String, text: String?): ActionResult = opening.shareImage(packageName, path, text)
    override val canShareImage: Boolean get() = opening.canShareImage
    suspend fun openOwnPage(intent: Intent): ActionResult = opening.openOwnPage(intent)


    /**
     * Opens a link or a settings page. Like [openApp], the display is created only on first use; if the user is using that app on the main screen, it is not opened.
     * A settings page is expected to land in the package that hosts system settings, and an app-specific one in that app; landing elsewhere (a system chooser, a redirect page) still succeeds, with a hint.
     */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        // When an app is specified, the app-access boundary has already checked it; when not, pick a handler that is not blocked here if the deny list is non-empty
        val pinned = packageName ?: allowedLinkHandler(action, uri)
        val id = display.get().displayId
        val expected = pinned ?: settingsPackage.takeIf { action != null }
        if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
        val before = ScreenReader.topPackage(windows())
        val beforeTree = treeHash()
        val startedAt = System.nanoTime()
        // When the opener is known in advance (app specified, settings page, or a link only one app handles): mute audio before launching
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
        // A link may land in another app, the system chooser, or a new page of the same app, so the pre-launch frame must not be served either.
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
        // Only accept the app that actually opened the link: if it landed in the system chooser or elsewhere, do not treat that as the target
        val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
        if (opened != null) {
            // For a link whose opener was not known in advance, reserve after it lands; if the reservation fails, do not undo (the shell side already checked other screens)
            if (checkOtherTasks) reserve(opened)
            targetPackage = opened
            requestedUser = null
            val task = shell.topTaskId(displayId)
            _target.value = TargetApp(opened, task, userOf(task))
        }
        ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
    }

    /**
     * Who opens a link when the deny list is non-empty and no app is specified: use the system default if it is not blocked, otherwise the first unblocked candidate; if every capable app is blocked, refuse.
     * Null when the list is empty or no app can handle it (hand it to the system as usual without an extra check).
     */
    internal fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = candidates.filter { access.denial(it) == null }
        if (allowed.isEmpty()) access.denial(candidates.first())?.let { throw it }
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return default?.takeIf { it in allowed } ?: allowed.first()
    }

    /** Who opens a link when no app is specified: the one the system picks directly; null when the user must choose (it lands in the system chooser) or it cannot be read. */
    internal fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in systemPickers }

    internal fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    /**
     * After a task is moved back to this screen, wait for its window to appear in the accessibility window list before letting the Agent read the screen;
     * otherwise the immediately following observe may read an empty screen. Returns anyway if the wait times out; the Agent's own screen read decides.
     */
    suspend fun awaitContent(): Boolean = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext false
        val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
        while (ScreenReader.topPackage(windows()) == null && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
        waitStable(SETTLE_CAP_MS)
        ScreenReader.topPackage(windows()) != null
    }

    override suspend fun listApps(): List<Pair<String, String>> = withContext(Dispatchers.IO) { launchableApps(a11y.packageManager, a11y.packageName) }

    // ---------- System actions ----------

    /**
     * Performs system actions through standard Android intents (see [SystemIntents]). First check on Claire's side who handles it (capability detection, no display needed): none means not_supported;
     * with several, [SystemIntents.choose] picks one explicitly. Then hand it to the built-in helper to launch onto Claire's screen (creating the display on first use), watching the main screen before and after as usual.
     * Alarms and timers are supposed to be set silently: afterwards read the system's next alarm to verify; if the app opened a UI anyway, report that truthfully. The alarm list and new calendar events require waiting for that app to come to the top.
     */
    override suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        val intent = SystemIntents.toIntent(SystemIntents.spec(action))
        val pm = a11y.packageManager
        val handlers = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }
        }.getOrDefault(emptyList())
        val default = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        // Only this place knows who handles it: skip blocked ones; if every capable app is blocked, refuse
        val access = appAccess.current()
        val allowedHandlers = if (access.blocked.isEmpty()) handlers else handlers.filter { access.denial(it.packageName) == null }
        if (handlers.isNotEmpty() && allowedHandlers.isEmpty()) access.denial(handlers.first().packageName)?.let { throw it }
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
        // The shell may lack the permission the handling page requires (setting an alarm): prepare one launched as Claire
        val asMika = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Silent operations (alarms, timers) open no UI and are not muted: the clock must ring when due
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
            // The clock app usually writes in the background and registers later: wait for the system's next alarm to change, at most for a while
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
        // This was supposed to be silent, yet that app's UI appeared on screen
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

    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult =
        withContext(Dispatchers.IO) { CalendarBulkWriter.insert(a11y, events) }

    internal fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."

    // ---------- Wheel pickers ----------

    override suspend fun setWheel(ref: String, snapshotId: String, value: String): WheelOutcome = withContext(Dispatchers.IO) {
        if (!hasDisplay) return@withContext WheelOutcome(false, null, null, 0, "none", error = "no_app_open")
        val (node, _) = resolve(ref, snapshotId) ?: return@withContext WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val driver = WheelDriver(
            screenWidth, screenHeight,
            quiet = { q, cap -> withEventClock { it.awaitQuiet(q, cap) } },
            // Slow drag: when paced, decelerate to a stop at the end and release without flinging so it does not overshoot; when a slow drag does not move it, use a quick swipe instead
            drag = { points, slow -> drag(points, if (slow) WHEEL_DRAG_MS else WHEEL_SWIPE_MS, bow = false) },
            ocr = { readVisibleText() },
            imeShown = { textInput.imeBox() != null },
            back = { runCatching { shell.injectKey(displayId, KeyEvent.KEYCODE_BACK) }.getOrDefault(false) },
        )
        driver.set(node, value)
    }

    // ---------- Which app to open ----------

    /** Package of the topmost readable window (any type: system choosers may be app windows or system windows). */
    internal fun topWindowPackage(): String? =
        ScreenReader.contentWindows(windows()).firstNotNullOfOrNull { it.root?.packageName?.toString() }

    /**
     * After opening [packageName], the system is asking which app to open: the top window became a window of another system package ([before] is the top package before opening) and the window's options mention this app's name.
     * Otherwise null. Only system packages count: a window of another app mentioning the name does not.
     */
    internal fun chooserOf(packageName: String, before: String?): LaunchChoice? {
        val w = ScreenReader.contentWindows(windows()).firstOrNull { it.root != null } ?: return null
        val pkg = w.root?.packageName?.toString() ?: return null
        if (pkg == packageName || pkg == before || !isSystemPackage(pkg)) return null
        val label = appLabel(packageName) ?: return null
        val elements = ScreenReader.read(listOf(w), screenWidth, screenHeight).entries.map { it.element }
        val options = LaunchChooser.options(label, elements, profiles?.get(packageName).orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** The topmost readable window changed to a system package (not [packageName] and not the pre-launch [before]): that package; otherwise null. */
    internal fun systemPickerOf(packageName: String, before: String?): String? {
        val pkg = topWindowPackage() ?: return null
        return pkg.takeIf { it != packageName && it != before && isSystemPackage(it) }
    }

    internal val systemPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Whether the package is a system package; for packages Claire cannot see itself (package visibility since Android 11), ask the built-in helper. */
    internal fun isSystemPackage(pkg: String): Boolean = pkg in systemPickers || systemPackages.getOrPut(pkg) {
        val flags = runCatching { a11y.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull()
        if (flags != null) flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        else runCatching { shell.isSystemPackage(pkg) }.getOrDefault(false)
    }

    internal fun appLabel(pkg: String): String? = runCatching {
        val pm = a11y.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Apps also installed under other users (package name to names of those users); ask the built-in helper the first time, then reuse this. If it cannot be read, do not cache it and ask again next time. */
    @Volatile internal var profiles: Map<String, List<String>>? = null

    override suspend fun otherProfiles(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        profiles ?: readProfiles()?.also { profiles = it }.orEmpty()
    }

    internal fun readProfiles(): Map<String, List<String>>? {
        val me = myUser
        val users = AppProfiles.parseUsers(runCatching { shell.listUsers() }.getOrNull() ?: return null).filter { it.id != me && it.running }
        return AppProfiles.alsoIn(users.associateWith { u ->
            AppProfiles.parsePackages(runCatching { shell.listPackages(u.id) }.getOrNull() ?: return null)
        })
    }

    /** Other copies of apps that can be opened (rules in [AppCopies]; the built-in helper decides which users are usable); asked fresh every time, empty when unavailable. */
    override suspend fun appCopies(): List<AppCopy> = withContext(Dispatchers.IO) {
        runCatching { AppCopyReader.read(shell, listApps()) }.getOrNull().orEmpty()
            .also { copies -> copiedPackages = copiedPackages + copies.map { it.packageName } }
    }

    /** Packages for which another copy has been listed: whether it switched to another copy ([switchedTo]) is only asked for these. */
    @Volatile internal var copiedPackages: Set<String> = emptySet()

    /** The user the task belongs to; -1 when the task does not exist or cannot be read. */
    internal fun userOf(task: Int): Int = if (task < 0) -1 else runCatching { shell.userOfTask(task) }.getOrDefault(-1)

    // ---------- Verification ----------

    /**
     * Runs the action, waits for the UI to go quiet, then compares fingerprints before and after; next checks whether the foreground was switched away. Watch the main screen around the action: if the action opened the app onto the user's main screen, move it back,
     * and report opened in the result (see [takeSpill]). If the previous action opened something on the main screen only after a delay, do not perform this action and first say the screen changed.
     */
    internal suspend fun verified(
        relocatedTo: String? = null,
        fallback: (suspend (EventClock) -> Boolean)? = null,
        retryWhenUnchanged: Boolean = true,
        action: suspend () -> Boolean,
    ): ActionResult {
        spill.lateSpill()?.let { return ActionResult.Failed("opened_elsewhere", spill.hintLateSpill(it.opened)) }
        val beforeTree = treeHash()
        val beforeFrame = frameSignature()
        // Only actions that may need a fallback tap need the foreground package and IME bounds remembered beforehand; other actions do not read the windows twice for nothing
        val backup = fallback?.takeIf { retryWhenUnchanged }
        val beforePkg = backup?.let { ScreenReader.topPackage(windows()) }
        val beforeIme = if (backup != null) textInput.imeBounds() else null
        val w = spill.armSpillWatch()
        val changed = withEventClock { clock ->
            clock.mark()
            if (action()) {
                val retry: (suspend () -> Boolean)? = if (backup != null) {
                    {
                        if (!handedOver() && ScreenReader.topPackage(windows()) == beforePkg && textInput.imeBounds() == beforeIme) {
                            backup(clock)
                        } else false
                    }
                } else null
                awaitChange(clock, beforeTree, beforeFrame, retry)
            } else if (fallback != null) {
                if (fallback(clock)) awaitChange(clock, beforeTree, beforeFrame) else null
            } else {
                null
            }
        } ?: return ActionResult.Failed("action_failed")
        // Something opened from a task that was moved back counts as well
        val profile = spill.adoptFromAdopted(w)
        val spilled = spill.takeSpill(w)
        return ok(changed = changed || spilled?.opened?.pulledBack == true, relocatedTo = relocatedTo, spill = spilled, inProfile = profile)
    }

    // ---------- Opened onto the user's main screen ----------

    /**
     * One occurrence of the system asking which app to open on the user's main screen and waiting for the user to choose: [taskId] is the task of the window that asked, [packageName] its package, [app] which app is being asked about,
     * [topBefore] the topmost task on the main screen before it appeared, [agentTop] the topmost task on Claire's screen at that time, [since] when it appeared.
     * The chooser is never moved: moving it requires rebuilding it on the new screen, and some close themselves when rebuilt; Claire also does not reopen it on behalf of another app (the opener must be the original app).
     */
    class UserPick(val taskId: Int, val packageName: String, val app: String?, val topBefore: Int, val agentTop: Int, val since: Long)

    /** After the user has chosen: the chosen app needs hands-on handling by the user ([NeedsUser], stays on the main screen); everything else is already handled (reported in the next screen read or action). */
    sealed interface PickResult {
        data object Settled : PickResult
        class NeedsUser(val taskId: Int, val packageName: String, val app: String?, val userId: Int) : PickResult
    }

    /** Sub-object for 'an app was opened onto the user's main screen': move back / wait for the user's choice, see [ScreenSpill]. */
    internal val spill = ScreenSpill(this)

    /** Currently waiting for the user to choose on the main screen; the task pauses on this and the capsule asks the user to choose. */
    val userPick: StateFlow<UserPick?> get() = spill.userPick

    /**
     * Called by a task before its next screen-changing action: waits for the user to finish choosing on the main screen (watching the main screen until then). After the choice, ordinary pages opened on the main screen are moved back to Claire's screen
     * (only moved; the original app still opened it); pages needing hands-on handling by the user (password entry, unreadable window, such as payments or fingerprints) stay on the main screen and return [PickResult.NeedsUser];
     * ones opened on Claire's screen count as well; if the user closed the prompt, report that truthfully. Null when not waiting.
     */
    suspend fun awaitUserPick(): PickResult? = spill.awaitUserPick()

    /** The user finished handling ([PickResult.NeedsUser]) and tapped continue: if it is still on the main screen, move it back to Claire's screen and continue; if it was closed, report that truthfully. */
    suspend fun afterUserStep(r: PickResult.NeedsUser) = spill.afterUserStep(r)

    /** Waits for the UI to settle, then compares fingerprints (rules in [ChangeWait]); returns true as soon as the change is confirmed. */
    internal suspend fun awaitChange(
        clock: EventClock,
        beforeTree: Long,
        beforeFrame: IntArray?,
        fallback: (suspend () -> Boolean)? = null,
    ): Boolean =
        ChangeWait.awaitStableChange(
            now = SystemClock::uptimeMillis,
            sleep = { delay(it) },
            frame = ::frameSignature,
            quietForMs = clock::quietForMs,
            treeChanged = { treeHash() != beforeTree },
            beforeFrame = beforeFrame,
            fallback = fallback,
        )

    /** When the foreground is not the target app, nor a system page or system chooser it launched, returns the current foreground package and whether the target app opened it. */
    internal fun switchedTo(): AppSwitch.Switch? {
        val target = _target.value
        return AppSwitch.switchedTo(
            targetPackage,
            ScreenReader.topPackage(windows()),
            // What was moved back from the user's main screen (and what was opened from those) was also opened along the target app's path
            sameTask = {
                val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                top >= 0 && (target != null && target.taskId == top || top in spill.adopted)
            },
            partOfTask = chooserPackage?.let { systemPickers + it } ?: systemPickers,
            settingsPage = { it == settingsPackage },
            // The same app installed twice: the top changing to the copy under another user (not the target task, nor something moved back) also counts as being switched away
            // Only ask about the task when the target is another copy or this app has another copy listed (apps installed once do not get two extra task lookups per action)
            otherCopy = {
                target != null && target.userId >= 0 && (target.userId != myUser || target.packageName in copiedPackages) && run {
                    val top = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                    top >= 0 && top != target.taskId && top !in spill.adopted && userOf(top).let { it >= 0 && it != target.userId }
                }
            },
        )
    }

    /**
     * The target app had not reached the foreground at open time (the system asked which app to open first, or startup was slow) and later came to the top: record its task id
     * so the task service can mute audio accordingly and watch whether it gets moved away.
     */
    internal suspend fun adoptTargetTask(): String? {
        val t = _target.value ?: return null
        if (t.taskId >= 0 || foregroundTaskPackage() != t.packageName) return null
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return null
        val user = userOf(task)
        _target.value = TargetApp(t.packageName, task, user)
        // The system asked which app to open (installed twice): report which copy was opened
        return spill.inProfileOf(t.packageName, user)
    }

    /** Action result that includes 'whether it was switched away'; [spill] is the app this action opened onto the user's main screen, [inProfile] see [ActionResult.Ok.inProfile]. */
    internal suspend fun ok(
        changed: Boolean? = null, relocatedTo: String? = null, verification: Verification? = null,
        hint: String? = null, secret: Boolean = false, spill: ScreenSpill.Spill? = null, inProfile: String? = null,
    ): ActionResult.Ok {
        val adopted = adoptTargetTask()
        val shown = shownCopy()
        val profile = adopted ?: inProfile ?: shown?.first
        val sw = switchedTo()
        return ActionResult.Ok(
            changed = changed, appSwitched = sw?.packageName, switchedByTarget = sw?.byTarget == true,
            relocated = relocatedTo != null, relocatedTo = relocatedTo, verification = verification, hint = hint, secret = secret,
            chooser = spill?.chooser, opened = spill?.opened, inProfile = profile,
            profileUser = shown?.second, requestedUser = requestedUser.takeIf { shown != null },
        )
    }

    /** The user whose copy was requested when opening the app (the other copy); null when the original was requested or this is not an app open. */
    @Volatile internal var requestedUser: Int? = null

    /**
     * When the target app is installed twice (another copy was listed, the other copy was requested, or the other copy was opened), which copy is currently on Claire's screen: (inProfile, owning user).
     * Null when only one copy is installed, when it is not on top, or when it cannot be read; apps installed once are not asked about further.
     */
    internal fun shownCopy(): Pair<String, Int>? {
        val t = _target.value ?: return null
        if (requestedUser == null && t.packageName !in copiedPackages && (t.userId < 0 || t.userId == myUser)) return null
        if (foregroundTaskPackage() != t.packageName) return null
        val user = userOf(runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)).takeIf { it >= 0 } ?: return null
        return (if (user == myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE) to user
    }

    // ---------- Ref resolution ----------

    /**
     * Resolves a ref into an actionable node; when relocation was involved, the second value is the current name of the recovered element, otherwise null.
     * If the node from the latest snapshot still exists and is unchanged, use it directly; if the snapshot is stale or the node changed, find the unique match in the current UI by the original element's traits.
     */
    internal fun resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
        val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
            is NodeIds.Lookup.Unknown -> return null
            is NodeIds.Lookup.Current -> {
                if (unchanged(l.entry)) return l.entry.node to null
                l.entry to l.snapshot
            }
            is NodeIds.Lookup.Old -> l.entry to l.snapshot
        }
        val now = read()
        return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(screenWidth, screenHeight))) {
            is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
            else -> null
        }
    }

    /** Whether the node still exists and its type, name, resource ID, and position match the snapshot (after list reuse, the same node may hold different content). */
    internal fun unchanged(entry: NodeEntry): Boolean {
        val n = entry.node
        if (!n.refresh()) return false
        val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
        return now == entry.locator.copy(context = null)
    }

    internal sealed interface Field {
        /** [tapped]: the given element is not itself an input field; the real field appeared (or got focus) only after tapping it. */
        class Found(val node: AccessibilityNodeInfo, val tapped: Boolean = false, val relocatedTo: String? = null) : Field
        class Missing(val reason: String) : Field
    }

    /**
     * Finds the field to type into. With a ref: the element itself or an input field inside it; if neither, and it looks like an input entry point (the 'fake search box' on a home page:
     * clickable, not a button, with a placeholder or a name that looks like search), tap it first; only if the tap succeeds and a field that was not there before now holds focus, use that field.
     * Without a ref: the field that currently holds input focus.
     */
    internal suspend fun findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
        if (ref != null && snapshotId != null) {
            val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
            val editable = if (ScreenReader.canSetText(node)) node
                else descendant(node) { ScreenReader.canSetText(it) && visibleInputTarget(it) }
            if (editable != null) {
                if (!editable.refresh() || !visibleInputTarget(editable)) return Field.Missing(EDITOR_NOT_VISIBLE)
                return Field.Found(editable, relocatedTo = relocatedTo)
            }
            if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
            val focusedBefore = focusedEditable()
            val clicked = withEventClock { clock ->
                clock.mark()
                click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
            }
            val now = focusedField()
            return when {
                clicked && now is Field.Found && now.node != focusedBefore ->
                    Field.Found(now.node, tapped = true, relocatedTo = relocatedTo)
                clicked && now is Field.Missing && now.reason == EDITOR_NOT_VISIBLE -> now
                else -> Field.Missing("not_editable")
            }
        }
        return focusedField()
    }

    /** Traits used to recognize 'the same input field': type, resource ID, position and size. */
    internal data class FieldShape(
        val pkg: String?, val windowId: Int, val cls: String?, val rid: String?, val rect: Rect,
    ) {
        companion object {
            fun of(n: AccessibilityNodeInfo) =
                FieldShape(n.packageName?.toString(), n.windowId, n.className?.toString(), n.viewIdResourceName,
                    Rect().also(n::getBoundsInScreen))
        }
    }

    internal fun sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
        val b = FieldShape.of(n)
        if (a.pkg != b.pkg || a.windowId != b.windowId || a.cls != b.cls || a.rid != b.rid) return false
        // Size must be nearly identical; position may shift a little as the whole page moves (pages often move up when typing), using the same limit as ref recovery
        val short = min(screenWidth, screenHeight)
        val sizeTol = short * SAME_FIELD_SIZE
        val moveTol = short * RELOCATE_MAX_SHIFT
        return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
            kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
    }

    /** Looks like an input entry point: clickable, not a button class, and has a placeholder or a name that looks like search. */
    internal fun looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(n) { it.isClickable } ?: return false
        val cls = target.className?.toString().orEmpty()
        if (cls.endsWith("Button")) return false
        if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
        val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
        return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * Something on this screen holds input focus (even if it is not an input field in the tree, such as a custom-drawn app UI holding focus as a whole), or the IME is showing.
     * When neither is true, key events have no field to go to: input fields invisible to the tree also only receive a paste while holding focus.
     */
    internal fun somethingHoldsInputFocus(): Boolean =
        textInput.imeBounds() != null || focusedInput() != null

    internal fun focusedInput(): AccessibilityNodeInfo? {
        val content = ScreenReader.contentWindows(windows())
        val focusedWindow = content.firstOrNull { it.isFocused }
        if (focusedWindow != null) return focusedWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return content.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    }

    internal fun visibleInputTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && ScreenReader.locatorOf(node, screenWidth, screenHeight) != null

    /** Keep a known hidden native editor distinct from a custom editor absent from the tree. */
    internal fun focusedField(): Field {
        val focus = focusedInput() ?: return Field.Missing(NO_FOCUS)
        val editor = if (ScreenReader.canSetText(focus)) focus
            else descendant(focus) { it.isFocused && ScreenReader.canSetText(it) } ?: return Field.Missing(NO_FOCUS)
        return if (editor.refresh() && visibleInputTarget(editor)) Field.Found(editor) else Field.Missing(EDITOR_NOT_VISIBLE)
    }

    internal fun focusedEditable(): AccessibilityNodeInfo? = (focusedField() as? Field.Found)?.node

    internal fun blockedFocusedEditor(): Boolean = (focusedField() as? Field.Missing)?.reason == EDITOR_NOT_VISIBLE

    // ---------- Low-level operations ----------

    internal fun isClickableTarget(node: AccessibilityNodeInfo): Boolean =
        node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

    /** When a coordinate tap has no effect, look up the smallest clickable control at that point and fall back to an accessibility click; do not add a tap when it hits an outward-facing/irreversible button or a large container. */
    internal fun clickNodeAt(x: Int, y: Int, clock: EventClock): Boolean {
        if (!hasDisplay || handedOver() || textInput.imeBounds()?.contains(x, y) == true) return false
        val window = ScreenReader.contentWindows(windows()).firstOrNull {
            Rect().also(it::getBoundsInScreen).contains(x, y)
        }?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        val entries = ScreenReader.read(listOf(window), screenWidth, screenHeight).entries
        val rect = Rect()
        val hitEntries = entries.filter { entry ->
            entry.node.getBoundsInScreen(rect)
            rect.intersect(0, 0, screenWidth, screenHeight) && rect.contains(x, y)
        }
        if (hitEntries.isEmpty()) return false
        if (hitEntries.any { ClickFallback.mayBeOutward(it.element.name, it.locator.resourceId, clickable = it.element.clickable) }) return false
        val seen = HashSet<AccessibilityNodeInfo>()
        val candidates = mutableListOf<ClickFallback.Candidate<AccessibilityNodeInfo>>()
        for (entry in hitEntries) {
            val target = ancestorOrSelf(entry.node) { isClickableTarget(it) } ?: continue
            if (!seen.add(target) || !target.isVisibleToUser) continue
            target.getBoundsInScreen(rect)
            if (!rect.intersect(0, 0, screenWidth, screenHeight) || rect.isEmpty) continue
            candidates += ClickFallback.Candidate(
                item = target,
                left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
                label = ScreenReader.labelOf(target).ifBlank { entry.element.name.ifBlank { null } },
                resourceId = target.viewIdResourceName ?: entry.locator.resourceId,
                scrollable = target.isScrollable,
                webView = target.className == WEB_VIEW,
            )
        }
        val picked = ClickFallback.pickTarget(x, y, screenWidth, screenHeight, candidates) ?: return false
        clock.mark()
        return picked.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Prefer clicking the node (or its nearest clickable ancestor); if that fails, gesture at its center, and finally inject a touch. */
    internal suspend fun click(node: AccessibilityNodeInfo): Boolean {
        val target = ancestorOrSelf(node) { isClickableTarget(it) }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
    }

    internal suspend fun tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

    /** Taps once at this point: pause briefly before touching down, then hold for a random short moment. If the accessibility gesture fails, the built-in helper injects the touch. */
    internal suspend fun touch(at: Pair<Int, Int>): Boolean {
        val (x, y) = at
        delay(pacing.approach())
        val press = pacing.press()
        if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
        if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
        return shell.injectTap(displayId, x, y)
    }

    /**
     * Slides one finger along [points]. When paced, [GesturePacing.stroke] schedules the time of each point and the built-in helper injects them point by point (the app sees real fast-then-slow speed);
     * when the helper cannot inject, fall back to an accessibility gesture along the same curve (the system follows the path at constant speed, lacking only the pacing). Without pacing it works as before: an accessibility gesture follows the polyline at constant speed for the given duration.
     */
    internal suspend fun drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
        if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
        delay(pacing.approach())
        val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
        if (injectStroke(stroke.points)) return true
        return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
    }

    internal fun injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
        shell.injectStroke(
            displayId,
            IntArray(points.size) { points[it].x.roundToInt() },
            IntArray(points.size) { points[it].y.roundToInt() },
            LongArray(points.size) { points[it].t },
        )
    }.getOrDefault(false)

    internal fun pathOf(points: List<Pair<Int, Int>>) = Path().apply {
        moveTo(points.first().first.toFloat(), points.first().second.toFloat())
        points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
    }

    /**
     * Editable areas in web pages (contenteditable) only accept real input: SET_TEXT changes only the page's text, the editor receives no input events,
     * and the app treats it as no input ('Done' stays disabled, nothing is saved on exit). For these fields, select the existing content and paste, or press delete instead.
     */
    internal fun inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

    /**
     * Selects the existing content, then pastes (or presses delete to clear). Returning null means the clipboard cannot be used safely and no text was changed;
     * other results use the three states of [AsciiTextKeys]. If selection fails, no delete or paste is sent, avoiding deleting a single character or inserting at the cursor.
     */
    internal fun inputText(node: AccessibilityNodeInfo, text: String): Int? {
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
            // A standard action returning false may still have had an effect; treat it as uncertain and do not send the shortcut again.
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
            // The app reads the clipboard asynchronously: wait for the text to change before restoring, at most for a while
            val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (node.refresh() && node.text?.toString().orEmpty() != before) break
                Thread.sleep(POLL_MS)
            }
            AsciiTextKeys.DISPATCHED
        }
    }

    /** Runs synchronously while holding the global clipboard lock; after setting, back up as far as possible regardless of the result, and clear the held flag and encrypted backup only once restoration is confirmed. */
    internal fun withClipboardText(text: String, action: () -> Int): Int? {
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

    internal fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.refresh() || !visibleInputTarget(node)) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    internal inline fun ancestorOrSelf(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth <= MAX_ANCESTORS) {
            if (test(n)) return n
            n = n.parent
            depth++
        }
        return null
    }

    internal fun descendant(node: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(node) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 500) {
            val n = queue.removeFirst()
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        return null
    }

    // ---------- Frames ----------

    /** Coarse luma grid of the preview frame; null when there is no frame. */
    internal fun frameSignature(): IntArray? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.lumaGrid(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    /** Whether the frame is entirely black; secure windows and virtual-display composition failures can both produce such frames, so this alone cannot determine the cause. */
    internal fun frameBlack(): Boolean? {
        val bmp = display.current?.frames?.value ?: return null
        return runCatching { FrameMath.isBlack(bmp.width, bmp.height, bmp::getPixel) }.getOrNull()
    }

    // ---------- Event clock ----------

    /** Records the time of the most recent UI event on this screen. */
    internal class EventClock {
        internal val last = AtomicLong(SystemClock.uptimeMillis())
        fun mark() = last.set(SystemClock.uptimeMillis())
        fun quietForMs() = SystemClock.uptimeMillis() - last.get()

        /** Waits until there have been no UI events for [quietMs] in a row; returns true if that happens within [capMs]. */
        suspend fun awaitQuiet(quietMs: Long, capMs: Long): Boolean {
            val deadline = SystemClock.uptimeMillis() + capMs.coerceAtLeast(0)
            while (true) {
                val now = SystemClock.uptimeMillis()
                val quietFor = now - last.get()
                if (quietFor >= quietMs) return true
                if (now >= deadline) return false
                delay(min(quietMs - quietFor, deadline - now).coerceIn(10, 100))
            }
        }
    }

    /** Runs [block] while subscribed to this screen's accessibility events; the subscription is established before block so the first batch of events caused by the action is not missed. */
    internal suspend fun <T> withEventClock(block: suspend (EventClock) -> T): T = coroutineScope {
        val clock = EventClock()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            a11y.eventsFor(displayId).collect { if (it.eventType and IGNORED_EVENTS == 0) clock.mark() }
        }
        try {
            block(clock)
        } finally {
            job.cancel()
        }
    }

    internal companion object {
        const val TAG = "MikaScreen"
        /** How long after an action starts a task appearing on the main screen still counts as opened by that action. */
        const val SPILL_WINDOW_MS = 6_000L
        /**
         * A window counts as a full-screen page only when both its width and height are at least this fraction of the main screen; anything smaller is a dialog, bottom sheet, etc.
         * The threshold is kept loose: the status bar, navigation bar, and cutouts make even a full-screen app's window smaller than the screen, and in landscape the navigation bar is on the side.
         */
        const val FULL_SCREEN_SHARE = 0.8f
        /** A window that just appeared may still be in its opening animation: wait this long and look again to confirm whether it consistently fails to be full screen. */
        const val POPUP_SETTLE_MS = 300L
        const val MAX_USER_TAPS = 32
        /** After the user chooses in the main-screen chooser, the maximum time to wait for the chosen app to come up; and how often to check while waiting for the user to choose. */
        const val PICK_WAIT_MS = 3_000L
        const val PICK_POLL_MS = 300L
        /** Anything that appeared this long before the chooser closed also counts as chosen (whether the chooser has closed is checked at intervals). */
        const val PICK_SLACK_MS = 1_000L
        /** After the chosen app comes up, wait at most this long to read its window and see whether it needs hands-on handling by the user. */
        const val PICK_READ_MS = 2_000L
        val NO_APP_OPEN = ActionResult.Failed("no_app_open")
        const val NO_FOCUS = "no_focus"
        const val EDITOR_NOT_VISIBLE = "editor_not_visible"
        const val PER_USER_RANGE = 100_000
        /** The user Claire runs as: the original copy of an app lives here. */
        val myUser: Int get() = AppCopyReader.myUser
        const val COPY_NOT_FOUND = "copy_not_found"
        const val COPY_NOT_OPENED = "copy_not_opened"
        const val PASTE_UNAVAILABLE = "paste_unavailable"
        const val INPUT_NOT_SENT = "input_not_sent"
        const val HINT_PASTE_BLOCKED = "The clipboard could not be used safely right now. " +
            "Call hand_to_user so the user can type in this field."
        val IN_USE_BY_OTHER = ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK)

        const val QUIET_MS = 500L

        /** The scrollable content gently scrolled during idling must cover at least this fraction of the screen. */
        const val IDLE_MIN_AREA = 0.5

        /** A gentle idle scroll must move at least this many pixels (less is imperceptible). */
        const val IDLE_MIN_PX = 8

        /** Duration of a gentle idle scroll: slow enough to count as a slow drag, lifting the finger only after it has settled. */
        const val IDLE_SCROLL_MS = 900L
        const val CHECK_CAP_MS = 1_500L
        const val SECOND_LOOK_QUIET_MS = 300L
        const val SECOND_LOOK_CAP_MS = 800L
        const val SETTLE_CAP_MS = 2_000L
        const val READBACK_QUIET_MS = 200L
        const val READBACK_CAP_MS = 800L
        const val LAUNCH_WAIT_MS = 5_000L
        const val IN_USE_RETRY_MS = 3_000L
        const val IN_USE_POLL_MS = 500L
        const val POLL_MS = 100L
        /** After setting an alarm, wait this long at most for the system's next alarm to change, polling at this interval. */
        const val ALARM_WAIT_MS = 3_000L
        const val ALARM_POLL_MS = 150L
        const val SYSTEM_ACTION_REQUEST = 0x5A
        /** Duration of dragging a wheel: no shorter than the slow-drag duration in the pacing, so it has already stopped when the finger lifts. */
        const val WHEEL_DRAG_MS = 1_000L
        /** Duration of the quick swipe used when a slow drag does not move the wheel (like a hand flicking a wheel). */
        const val WHEEL_SWIPE_MS = 400L
        const val TYPE_ATTEMPTS = 2
        const val WEB_VIEW = "android.webkit.WebView"
        const val PASTE_WAIT_MS = 1_500L
        const val PASTE_MENU_MIN_HOLD_MS = 800L
        const val PASTE_MENU_WAIT_MS = 3_000L
        const val HINT_PASTE_UNAVAILABLE = "This field only receives real input (it needs a paste), but the clipboard could not be used safely, " +
            "so the text may only be displayed and the app may not have received it. Check that the app reacted (e.g. its save button became enabled) " +
            "before relying on it; if it did not, call hand_to_user so the user can type it."
        const val HINT_TAPPED_TO_FOCUS = "The element you gave is not a text field itself, so it was tapped first and the text went into the field that got focus after the tap (often a separate search page). Check which field holds your text before submitting."
        const val HINT_FIELD_REPLACED = "Tapping the field opened a new input in its place (often a separate search page), so the text went into the field that holds focus now. Check which field holds your text before submitting."
        const val HINT_FOCUS_UNCONFIRMED = "The field did not confirm input focus after both focus and tap, so the text was written directly. Trust the readback verification, not the focus state."
        const val FOCUS_WAIT_MS = 2_000L
        const val SAME_FIELD_SIZE = 0.03
        val SEARCH_WORDS = listOf("搜索", "搜一搜", "查找", "search")
        const val HINT_READBACK_FROZEN = "The field keeps reporting the same text, so what was typed could not be read back; " +
            "but new text containing it appeared on the screen after typing (e.g. suggestions), so the app seems to have received it. Observe to check before submitting."
        const val HINT_SHARE_PICKER = "The system is asking which of the app's share screens to use (the screen lists them). " +
            "Tap the one the task needs (for a message, the one that sends to a contact or chat); do not share again."
        const val HINT_COLD_IGNORED = "coldIgnored: app has a task outside Claire's screen, so it was not restarted (that would wipe the user's state there). It was opened as-is; navigate back to its start screen yourself if needed."
        const val MAX_ANCESTORS = 8

        // Hover, touch exploration, gesture detection, and accessibility focus are not changes in screen content
        const val IGNORED_EVENTS = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER or AccessibilityEvent.TYPE_VIEW_HOVER_EXIT or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START or AccessibilityEvent.TYPE_GESTURE_DETECTION_END or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
    }
}

/** Apps that can be opened from the launcher (package name, display name), excluding Claire itself; use only the package manager, not the built-in helper or accessibility. */
fun launchableApps(pm: android.content.pm.PackageManager, ownPackage: String): List<Pair<String, String>> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ownPackage }
        .distinctBy { it.first }
        .sortedBy { it.second }
}
