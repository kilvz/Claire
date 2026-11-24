package com.wangzan101.mika.selfcheck

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.SystemPickers
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.FrameMath
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebScreen
import com.wangzan101.mika.cap.web.WebTabPool
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.DeviceFacts
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckPort
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.shell.IMikaShell
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android implementation of the compatibility self-check. On the phone these checks use the same stack the tasks use: the assistant connection shared within the process ([PhoneServices.sharedShell]),
 * [AgentDisplay] to create the display, [AndroidScreen] to open pages, read the screen, tap, type, scroll and go back; web pages go through the built-in browser's [WebScreen];
 * the sandbox goes through [AndroidSandbox]. It touches only Claire's own self-check page and the system settings (opening them only, never tapping), changes no settings and uses no network.
 * The facts in the report contain only statuses and numbers, never other apps' package names, accounts or user content.
 * [status] is the system status at this moment as the UI sees it (accessibility, notifications, built-in assistant).
 */
class AndroidSelfCheck(context: Context, private val status: () -> SystemStatus) : SelfCheckPort {
    private val app = context.applicationContext
    private val phone = PhoneServices.of(app)

    @Volatile private var connection: ShellBinding? = null
    @Volatile private var shell: IMikaShell? = null
    @Volatile private var display: AgentDisplay? = null
    @Volatile private var screen: AndroidScreen? = null
    @Volatile private var web: Web? = null

    /** The top task on the main screen when the run starts; it is checked again at every stage, and stages where it changed are recorded. */
    private var mainBefore: Int? = null
    private val mainChanged = mutableListOf<String>()

    private class Web(val host: WebHost, val pool: WebTabPool, val screen: WebScreen) {
        fun close() {
            runCatching { pool.closeAll(TASK) }
            runCatching { pool.close() }
            runCatching { host.close() }
        }
    }

    override suspend fun facts(): DeviceFacts = withContext(Dispatchers.IO) {
        val mem = ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val m = metrics()
        val st = status()
        val devPageMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager)
        val wirelessMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver)
        DeviceFacts(
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
            mem.totalMem, m.widthPixels, m.heightPixels,
            wirelessDebugging = !wirelessMissing,
            appVersion = BuildConfig.VERSION_NAME, appBuild = BuildConfig.GIT_HASH,
            developerOptionsPage = !devPageMissing,
            phoneControlUnavailable = PhoneSetupFlow.unavailable(devPageMissing, wirelessMissing, st.builtinShell, st.helperRestart),
        )
    }

    override suspend fun check(check: SelfCheck): CheckOutcome = when (check) {
        SelfCheck.DEVICE -> error("judged from facts()")
        SelfCheck.PERMISSIONS -> permissions()
        SelfCheck.HELPER -> helper()
        SelfCheck.VIRTUAL_DISPLAY -> virtualDisplay()
        SelfCheck.APP_LAUNCH -> appLaunch()
        SelfCheck.SCREEN_READING -> screenReading()
        SelfCheck.ACTIONS -> actions()
        SelfCheck.STAYS_ALIVE -> staysAlive()
        SelfCheck.MAIN_SCREEN -> mainScreen()
        SelfCheck.BROWSER -> browser()
        SelfCheck.SANDBOX -> sandbox()
        SelfCheck.APP_COPIES -> appCopies()
    }

    // ---------- screen-off self-check (manual diagnosis) ----------

    /**
     * Manual diagnosis: whether the stack the tasks use (built-in assistant, virtual display, opening system settings, reading the tree, screenshots, tapping) still works after the main screen is off and locked.
     * It uses the real-device ports, but holds a PARTIAL wake lock: it does not turn the main screen on and does not use the task unlock path. Once the monitors are armed, if the main screen turns on, the user comes back to the phone,
     * or a task starts, this self-check is void and honestly reported as "interrupted". Each stage's result is handed to the UI via [report].
     */
    internal suspend fun checkScreenOff(taskBusy: () -> Boolean, report: (Int, CheckOutcome) -> Unit) {
        val displays = app.getSystemService(DisplayManager::class.java)
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        fun screenOff() = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_OFF
        fun lockedAndOff() = screenOff() && keyguard.isDeviceLocked
        val armed = AtomicBoolean()
        val invalidated = AtomicBoolean()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (armed.get()) invalidated.set(true)
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && armed.get() && !lockedAndOff()) invalidated.set(true)
            }
        }
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Claire:ScreenOffSelfCheck")
        fun valid() = !invalidated.get() && !taskBusy() && lockedAndOff()
        val waitStage = R.string.selfcheck_screen_off_lock
        val finalStage = R.string.selfcheck_screen_off_start
        var stage = waitStage
        var registeredReceiver = false
        var registeredListener = false
        var completed = false
        var firstProblem: Pair<Int, CheckOutcome>? = null
        fun reportStep(label: Int, outcome: CheckOutcome) {
            if (outcome.status != CheckStatus.Pass && firstProblem == null) firstProblem = label to outcome
            report(label, outcome)
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registeredReceiver = true
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            registeredListener = true
            lock.acquire(SCREEN_OFF_WAKE_LOCK_MS)
            // Wait for the user to turn the screen off with the power button and lock the device; if a task starts meanwhile, do not begin
            val ready = withTimeoutOrNull(SCREEN_OFF_READY_MS) {
                while (!lockedAndOff() && !taskBusy()) delay(POLL_MS)
                !taskBusy()
            }
            if (ready != true) {
                reportStep(stage, CheckOutcome.fail(
                    if (taskBusy()) tr(R.string.selfcheck_task_running) else tr(R.string.selfcheck_advice_timeout),
                    Advice.TimedOut,
                ))
                return
            }
            armed.set(true)
            reportStep(stage, CheckOutcome.pass(tr(R.string.selfcheck_status_pass)))
            val finished = withTimeoutOrNull(SCREEN_OFF_STAGES_MS) {
                completed = runScreenOffStages(::valid, { stage = it }, ::reportStep)
                true
            }
            if (finished != true && valid()) reportStep(stage, CheckOutcome.fail(tr(R.string.selfcheck_advice_timeout), Advice.TimedOut))
        } catch (e: CancellationException) {
            throw e
        } catch (_: ScreenOffInterrupted) {
            // The main screen state changed: report "interrupted" once, after cleanup has released the virtual display
        } catch (e: Exception) {
            reportStep(stage, CheckOutcome.fail(e.javaClass.simpleName, Advice.Error))
        } finally {
            try {
                release()
                withContext(Dispatchers.Main.immediate) {
                    if (armed.get() && !valid()) {
                        report(finalStage, CheckOutcome.fail(tr(R.string.selfcheck_screen_off_interrupted), Advice.MainScreenChanged))
                    } else {
                        val problem = firstProblem
                        if (problem != null) {
                            val (label, outcome) = problem
                            report(finalStage, outcome.copy(detail = tr(label)))
                        } else if (completed) {
                            report(finalStage, CheckOutcome.pass(tr(R.string.selfcheck_status_pass)))
                        }
                    }
                }
            } finally {
                if (registeredListener) displays.unregisterDisplayListener(listener)
                if (registeredReceiver) app.unregisterReceiver(receiver)
                if (lock.isHeld) lock.release()
            }
        }
    }

    /** Interrupts the running stage as soon as the main screen state changes; the actual "interrupted" report is sent only after the virtual display is released. */
    private class ScreenOffInterrupted : Exception()

    /**
     * The stages of the screen-off self-check, in the same order as the normal self-check: built-in assistant → virtual display → open system settings → read tree and screenshot → tap search.
     * The main screen state is verified before and after every step and the run is interrupted at once if it changes; it stops at the first step that does not pass.
     */
    private suspend fun runScreenOffStages(
        valid: () -> Boolean,
        starting: (Int) -> Unit,
        report: (Int, CheckOutcome) -> Unit,
    ): Boolean {
        fun verifyLock() {
            if (!valid()) throw ScreenOffInterrupted()
        }
        suspend fun step(label: Int, work: suspend () -> CheckOutcome): Boolean {
            starting(label)
            verifyLock()
            val outcome = work()
            verifyLock()
            report(label, outcome)
            return outcome.status == CheckStatus.Pass
        }
        if (!step(R.string.selfcheck_check_helper, ::helper)) return false
        if (!step(R.string.selfcheck_check_display, ::virtualDisplay)) return false
        val sh = checkNotNull(shell)
        val d = checkNotNull(display)
        val settings = SystemPickers.settingsPackage(app.packageManager)
        var deviceScreen: AndroidScreen? = null
        // Open the system settings (open only, no taps): the main screen is off, so Settings must open on the virtual display that was just created
        if (!step(R.string.selfcheck_check_launch) {
            val a11y = ScreenAccessibility.instance.value
            if (a11y == null || settings == null) {
                return@step CheckOutcome.fail("Settings or accessibility unavailable", Advice.LaunchFailed)
            }
            val s = AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() })
            screen = s
            deviceScreen = s
            verifyLock()
            // Open it on the newly created virtual display; do not force-stop Settings
            val opened = s.openApp(settings, cold = false)
            if (opened is ActionResult.Ok && poll(PAGE_WAIT_MS) {
                    verifyLock()
                    topPackage(sh, d.displayId) == settings
                }) CheckOutcome.pass("Settings opened on the virtual display")
            else CheckOutcome.fail("Settings: ${reason(opened)}", Advice.LaunchFailed)
        }) return false
        val s = checkNotNull(deviceScreen)
        val pkg = checkNotNull(settings)
        val treeReadable = step(R.string.selfcheck_check_reading) {
            var readFailure = R.string.selfcheck_screen_off_no_app_window
            val readable = poll(PAGE_WAIT_MS) {
                verifyLock()
                val snapshot = s.peek()
                val settingsWindow = selfCheckWindowContent(snapshot, pkg)
                readFailure = when {
                    !snapshot.hasWindow -> R.string.selfcheck_screen_off_no_app_window
                    settingsWindow == null && snapshot.windows.any {
                        it.type == "application" && it.packageName == null
                    } -> R.string.selfcheck_screen_off_no_root
                    settingsWindow == null -> R.string.selfcheck_screen_off_no_settings_window
                    else -> R.string.selfcheck_screen_off_no_elements
                }
                settingsWindow?.elements?.isNotEmpty() == true
            }
            if (readable) CheckOutcome.pass(tr(R.string.selfcheck_screen_off_tree_readable))
            else CheckOutcome.fail(tr(readFailure), Advice.ReadFailed)
        }
        val frameReadable = step(R.string.selfcheck_screen_off_capture) {
            val frame = awaitFrame(d)
                ?: return@step CheckOutcome.fail(tr(R.string.selfcheck_screen_off_no_frame), Advice.BlankFrames)
            val black = try { FrameMath.isBlack(frame.width, frame.height, frame::getPixel) }
                finally { frame.recycle() }
            if (black) CheckOutcome.fail(tr(R.string.selfcheck_screen_off_black_frame), Advice.BlankFrames)
            else CheckOutcome.pass(tr(R.string.selfcheck_screen_off_frame_readable))
        }
        if (!treeReadable || !frameReadable) return false
        return step(R.string.selfcheck_check_actions) {
            val window = selfCheckWindowContent(s.snapshot(), pkg)
                ?: return@step CheckOutcome.fail("Settings window is no longer readable", Advice.ReadFailed)
            val search = window.elements.firstOrNull { element ->
                element.clickable && !element.editable && !element.secret &&
                    (element.resourceId?.substringAfterLast('/') in SETTINGS_SEARCH_IDS ||
                        element.name in SETTINGS_SEARCH_LABELS)
            } ?: return@step CheckOutcome.partial("Settings search button not identified", Advice.ActionFailed)
            verifyLock()
            if (s.tapRef(search.ref, window.snapshotId) !is ActionResult.Ok) {
                return@step CheckOutcome.fail("Settings search tap failed", Advice.ActionFailed)
            }
            val changed = poll(PAGE_WAIT_MS) {
                verifyLock()
                val target = topPackage(sh, d.displayId)
                target != null && systemPackage(target) &&
                    selfCheckWindowContent(s.peek(), target)?.elements.orEmpty().any { element ->
                        element.editable && !element.secret &&
                            (element.searchField || element.focused) &&
                            window.elements.none { old -> old.editable && old.resourceId == element.resourceId && old.name == element.name }
                    }
            }
            if (changed) CheckOutcome.pass("Tapping Settings search opened a search field")
            else CheckOutcome.fail("Settings search did not open", Advice.ActionFailed)
        }
    }

    // ---------- 2 permissions ----------

    private fun permissions(): CheckOutcome {
        val s = status()
        val battery = runCatching { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) }.getOrNull()
        val detail = "accessibility " + when (s.accessibility) {
            AccessibilityState.On -> "on (bound)"
            AccessibilityState.EnabledNotRunning -> "enabled, not bound"
            AccessibilityState.Off -> "off"
        } + ", notifications " + (if (s.notificationsAllowed) "allowed" else "blocked") +
            ", battery " + when (battery) { true -> "unrestricted"; false -> "restricted"; null -> "unreadable" }
        return when {
            s.accessibility == AccessibilityState.Off -> CheckOutcome.fail(detail, Advice.TurnOnAccessibility)
            s.accessibility == AccessibilityState.EnabledNotRunning -> CheckOutcome.fail(detail, Advice.RestartAccessibility)
            !s.notificationsAllowed -> CheckOutcome.partial(detail, Advice.AllowNotifications)
            battery == false -> CheckOutcome.partial(detail, Advice.AllowBattery)
            else -> CheckOutcome.pass(detail)
        }
    }

    // ---------- 3 built-in assistant ----------

    private suspend fun helper(): CheckOutcome {
        val st = status().builtinShell
        // If it was never paired, do not try to connect (connecting would prompt to go to settings); if it was paired, the watchdog may be starting it, so wait a while
        if (st == BuiltinShellStatus.NotPaired && !phone.shellControl.paired) return CheckOutcome.fail("not set up ($st)", Advice.SetUpPhoneControl)
        val conn = connection ?: phone.sharedShell.acquire().also { connection = it }
        val sh = withTimeoutOrNull(HELPER_WAIT_MS) { conn.shell.filterNotNull().first() }
            ?: return CheckOutcome.fail("not running (${phone.shellControl.status.value})", Advice.SetUpPhoneControl)
        shell = sh
        return withContext(Dispatchers.IO) {
            val uid = sh.identity().getOrNull(0) ?: -1
            val path = when {
                uid == ROOT_UID -> "root"
                phone.shellControl.paired -> "paired wireless debugging"
                else -> "started from a computer (USB)"
            }
            val who = when (uid) { SHELL_UID -> "shell"; ROOT_UID -> "root"; else -> "uid $uid" }
            val times = List(LATENCY_CALLS) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                sh.identity()
                (SystemClock.elapsedRealtimeNanos() - t0) / 1_000
            }.sorted()
            mainBefore = mainTop(sh)
            CheckOutcome.pass("running as $who, $path, round trip ${format(times[times.size / 2] / 1000.0)} ms")
        }
    }

    // ---------- 4 virtual display ----------

    private suspend fun virtualDisplay(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val m = metrics()
        // Creating the display is not interrupted halfway: one that does get created is always recorded and released during cleanup
        val made = withContext(NonCancellable + Dispatchers.IO) {
            runCatching { AgentDisplay(sh, m.widthPixels, m.heightPixels, m.densityDpi, app.getSystemService(DisplayManager::class.java)) }.onSuccess { display = it }
        }
        val d = made.getOrElse { return CheckOutcome.fail("could not be created (${it.javaClass.simpleName})", Advice.DisplayFailed) }
        val flags = runCatching { app.getSystemService(DisplayManager::class.java).getDisplay(d.displayId)?.flags }.getOrNull()
        val own = flags?.let { it and ownFocusFlag() != 0 }
        val detail = "created ${d.width}x${d.height}, own focus " + when (own) { true -> "yes"; false -> "no"; null -> "unknown" }
        return when {
            Build.VERSION.SDK_INT < DeviceFacts.OWN_FOCUS_SDK -> CheckOutcome.partial("$detail (own focus needs Android 14)", Advice.FocusShared)
            own == false -> CheckOutcome.partial(detail, Advice.FocusShared)
            else -> CheckOutcome.pass(detail)
        }
    }

    /** The "own focus" flag that displays expose since Android 14; the constant is not public, so read it by name first and fall back to the original value if that fails. */
    private fun ownFocusFlag(): Int = runCatching { Display::class.java.getField("FLAG_OWN_FOCUS").getInt(null) }.getOrDefault(1 shl 11)

    // ---------- 5 opening pages ----------

    private suspend fun appLaunch(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val a11y = ScreenAccessibility.instance.value ?: return CheckOutcome.fail("accessibility service not connected", Advice.RestartAccessibility)
        val s = screen ?: AndroidScreen(a11y, sh, LazyAgentDisplay(d.width, d.height) { d }.also { it.get() }).also { screen = it }
        val own = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (own !is ActionResult.Ok || topPackage(sh, d.displayId) != app.packageName) {
            return CheckOutcome.fail("Claire's test page did not open on Claire's display (${reason(own)})", Advice.LaunchFailed)
        }
        sampleMain(sh, "test page")
        // Close the self-check page before opening the system settings: afterwards a page is opened from scratch rather than by bringing the old one back to the front
        withContext(Dispatchers.Main) { SelfCheckTargetActivity.finishOpen() }
        val settings = SystemPickers.settingsPackage(app.packageManager)
        val settingsNote = if (settings == null) "no system Settings app found" else when (val r = s.openApp(settings, cold = false)) {
            is ActionResult.Failed -> if (r.reason == "app_in_use_by_user") SETTINGS_IN_USE else "Settings did not open (${r.reason})"
            is ActionResult.Ok -> {
                val onMika = withContext(Dispatchers.IO) { topPackage(sh, d.displayId) } == settings
                val onMain = withContext(Dispatchers.IO) { mainTop(sh)?.let { runCatching { sh.packageOfTask(it) }.getOrNull() } } == settings
                sampleMain(sh, "Settings")
                // Look only, never tap: leave it with a single back press
                s.back()
                when {
                    onMain -> SETTINGS_ON_MAIN
                    !onMika -> "Settings did not come to the front of Claire's display"
                    else -> null
                }
            }
        }
        // The remaining checks run on the self-check page: open it once more and start from scratch
        val again = s.openOwnPage(SelfCheckTargetActivity.intent(app))
        if (again !is ActionResult.Ok || !poll { withContext(Dispatchers.IO) { isTestPageOnTop(sh, d.displayId) } }) {
            return CheckOutcome.fail("Claire's test page did not reopen after Settings (${reason(again)})", Advice.LaunchFailed)
        }
        return when (settingsNote) {
            null -> CheckOutcome.pass("Claire's test page and Settings opened on Claire's display")
            SETTINGS_ON_MAIN -> CheckOutcome.fail("test page ok; $settingsNote", Advice.OpenedOnMainScreen)
            SETTINGS_IN_USE -> CheckOutcome.partial("test page ok; $settingsNote", Advice.SettingsInUse)
            else -> CheckOutcome.partial("test page ok; $settingsNote", Advice.LaunchFailed)
        }
    }

    // ---------- 6 screen reading ----------

    private suspend fun screenReading(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        s.waitStable(SETTLE_MS)
        // Right after a page opens, the tree may not be complete yet: only elements in Claire's own window are accepted, and it waits for a while at most.
        // System overlay windows do not belong to a new task; a full system app, however, becomes the topmost task and cannot be tolerated the same way.
        suspend fun inspect(source: ScreenSnapshot) = readSelfCheckWindow(
            source, app.packageName, withContext(Dispatchers.IO) { topPackage(sh, d.displayId) }, EXPECTED, ::systemPackage,
        ) { key -> find(key) }
        var snap = s.snapshot()
        var inspected = inspect(snap)
        poll(PAGE_WAIT_MS) {
            snap = s.snapshot()
            inspected = inspect(snap)
            inspected.ready
        }
        val result = inspected
        if (!result.ready) {
            val wrongApp = if (result.wrongAppOnTop) "; another app is above Claire" else ""
            return CheckOutcome.fail(
                "missing ${result.missing.joinToString().ifEmpty { "none" }}; ${seen(result.content ?: snap)}$wrongApp",
                Advice.ReadFailed,
            )
        }
        val content = result.content ?: return CheckOutcome.fail("Claire's window is not readable", Advice.ReadFailed)
        val frame = awaitFrame(d) ?: return CheckOutcome.fail("tree ok; no screenshot from the display", Advice.BlankFrames)
        val blank = try {
            val grid = FrameMath.lumaGrid(frame.width, frame.height, frame::getPixel)
            FrameMath.isBlack(frame.width, frame.height, frame::getPixel) || grid.max() - grid.min() < BLANK_SPREAD
        } finally {
            frame.recycle()
        }
        if (blank) return CheckOutcome.fail("tree ok; screenshot is blank or black", Advice.BlankFrames)
        return CheckOutcome.pass("${content.elements.size} Claire-window elements, all expected found; screenshot not blank")
    }

    // ---------- 7 actions ----------

    private suspend fun actions(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        val ok = mutableListOf<String>()
        val bad = mutableListOf<String>()
        fun mark(name: String, passed: Boolean) { if (passed) ok += name else bad += name }
        // Even when a system overlay window exists, act only on Claire's own elements; the ids still belong to the original snapshot, so they can be handed straight to the action port.
        fun own(source: ScreenSnapshot) = ownContent(source) ?: source.copy(elements = emptyList(), windows = emptyList())

        // Tap: the counter increments by one
        var snap = own(s.snapshot())
        val before = snap.find(COUNT)?.number()
        val button = snap.find(BUTTON)
        if (button != null && before != null) {
            s.tapRef(button.ref, snap.snapshotId)
            mark("tap", poll { own(s.peek()).find(COUNT)?.number() == before + 1 })
        } else mark("tap", false)

        // Typing: type once in English and once in Chinese, then read the input field back
        snap = own(s.snapshot())
        mark("type ASCII", snap.find(INPUT)?.let { typed(s, it, snap, ASCII) } == true)
        snap = own(s.snapshot())
        mark("type Chinese", snap.find(INPUT)?.let { typed(s, it, snap, CHINESE) } == true)

        // Scrolling: the visible rows change
        snap = own(s.snapshot())
        val rowsBefore = snap.rows()
        val list = snap.find(LIST)
        if (list != null && rowsBefore.isNotEmpty()) {
            s.scroll(list.ref, snap.snapshotId, down = true)
            mark("scroll", poll { own(s.peek()).rows().let { it.isNotEmpty() && it.first() != rowsBefore.first() } })
        } else mark("scroll", false)

        // Back: the self-check page leaves the top of Claire's display. Right after typing the IME is usually still up, and those presses only hide the IME (that is how Android behaves), so they do not count
        val gone = BackPresses.until(
            tries = BACK_TRIES,
            maxPresses = BACK_MAX_PRESSES,
            keyboardUp = { withContext(Dispatchers.IO) { s.keyboardUp() } },
            press = { s.back() },
            left = { poll { withContext(Dispatchers.IO) { !isTestPageOnTop(sh, d.displayId) } } },
        )
        mark("back", gone)
        sampleMain(sh, "actions")

        val detail = (ok.map { "$it ok" } + bad.map { "$it failed" }).joinToString(", ")
        return when {
            bad.isEmpty() -> CheckOutcome.pass(detail)
            bad == listOf("type Chinese") -> CheckOutcome.partial(detail, Advice.ActionFailed)
            else -> CheckOutcome.fail(detail, Advice.ActionFailed)
        }
    }

    private suspend fun typed(s: AndroidScreen, field: UiElement, snap: ScreenSnapshot, text: String): Boolean {
        s.clearText(field.ref, snap.snapshotId)
        val fresh = ownContent(s.snapshot()) ?: return false
        val f = fresh.find(INPUT) ?: return false
        s.typeText(f.ref, fresh.snapshotId, text)
        return poll { ownContent(s.peek())?.find(INPUT)?.name?.let { SelfCheckText.contains(it, text) } == true }
    }

    // ---------- 8 stays alive ----------

    private suspend fun staysAlive(): CheckOutcome {
        val s = screen ?: return CheckOutcome.fail("no screen", Advice.LaunchFailed)
        val d = display ?: return CheckOutcome.fail("no display", Advice.DisplayFailed)
        if (s.openOwnPage(SelfCheckTargetActivity.intent(app)) !is ActionResult.Ok) {
            return CheckOutcome.fail("Claire's test page did not reopen", Advice.LaunchFailed)
        }
        val start = SystemClock.uptimeMillis()
        var lastChange = start
        var prev = frameHash(d)
        var lastTick = s.peek().find(TICK)?.number()
        var treeLostAt: Long? = null
        while (SystemClock.uptimeMillis() - start < ALIVE_MS) {
            delay(ALIVE_STEP_MS)
            val now = SystemClock.uptimeMillis()
            val h = frameHash(d)
            if (h != null && h != prev) lastChange = now
            prev = h
            if (now - lastChange > STALL_MS) {
                return CheckOutcome.fail("frames stopped after ${(lastChange - start + 500) / 1000} s", Advice.FramesStopped)
            }
            val tick = s.peek().find(TICK)?.number()
            if (tick == null || tick == lastTick) {
                if (treeLostAt == null) treeLostAt = now
            } else treeLostAt = null
            lastTick = tick
            treeLostAt?.let { lost ->
                if (now - lost > STALL_MS) return CheckOutcome.fail("tree stopped updating after ${(lost - start + 500) / 1000} s", Advice.FramesStopped)
            }
        }
        return CheckOutcome.pass("frames and tree kept changing for ${ALIVE_MS / 1000} s")
    }

    // ---------- 9 main screen ----------

    private suspend fun mainScreen(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val before = mainBefore ?: return CheckOutcome.fail("main screen was not readable at the start", Advice.Error)
        sampleMain(sh, "end")
        val who = if (withContext(Dispatchers.IO) { runCatching { sh.packageOfTask(before) }.getOrNull() } == app.packageName) "Claire" else "another app"
        return if (mainChanged.isEmpty()) CheckOutcome.pass("top task unchanged ($who stayed on top)")
        else CheckOutcome.fail("top task changed during: ${mainChanged.joinToString()}", Advice.MainScreenChanged)
    }

    // ---------- 10 built-in browser ----------

    private suspend fun browser(): CheckOutcome {
        if (runCatching { WebViewCompat.getCurrentWebViewPackage(app) == null }.getOrDefault(true)) return CheckOutcome.fail("no WebView on this device", Advice.NoWebView)
        val m = metrics()
        // Built on the main thread like the tasks do; whatever gets built is always recorded and closed during cleanup. Visits are not added to the site list (no registry is passed)
        val built = withContext(NonCancellable + Dispatchers.Main) {
            runCatching {
                val host = WebHost(app, m.widthPixels, m.heightPixels, m.densityDpi)
                try {
                    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
                    val pool = WebTabPool(app, host, assetLoaders = listOf(loader))
                    Web(host, pool, WebScreen(pool, TASK, host, SystemClock::uptimeMillis)).also { web = it }
                } catch (e: Exception) {
                    host.close()
                    throw e
                }
            }
        }
        val w = built.getOrElse { return CheckOutcome.fail("browser could not start (${it.javaClass.simpleName})", Advice.WebFailed) }
        val isArabic = AppLanguage.resources(app).configuration.locales[0].language == "ar"
        val pageUrl = if (isArabic) WEB_PAGE_AR else WEB_PAGE
        val clickedText = if (isArabic) WEB_CLICKED_AR else WEB_CLICKED
        val nav = w.screen.navigate(pageUrl, newTab = false)
        if (!nav.loaded) return CheckOutcome.fail("local page did not load (${nav.netError ?: "not loaded"})", Advice.WebFailed)
        val read = w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text
        if (!SelfCheckText.contains(read, WEB_MARKER)) return CheckOutcome.fail("page loaded but its text was not read", Advice.WebFailed)
        val snap = w.screen.snapshot()
        val button = snap.elements.firstOrNull { it.resourceId == WEB_BUTTON }
            ?: return CheckOutcome.fail("text read; button not found", Advice.WebFailed)
        w.screen.tapRef(button.ref, snap.snapshotId)
        val clicked = poll { SelfCheckText.contains(w.screen.readPage(MAX_CHARS, 0, scrollLazy = false).text, clickedText) }
        return if (clicked) CheckOutcome.pass("local page loaded, text read, button tap verified")
        else CheckOutcome.fail("text read; button tap did not change the page", Advice.WebFailed)
    }

    // ---------- 11 sandbox ----------

    private suspend fun sandbox(): CheckOutcome {
        val state = SandboxState.get(app)
        when (val st = state.status.value) {
            is SandboxStatus.Unsupported -> return CheckOutcome.skipped("not supported on ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this device"}", Advice.SandboxUnsupported)
            is SandboxStatus.Ready -> Unit
            // Do not install the runtime environment just for the self-check (it takes several minutes)
            else -> return CheckOutcome.partial("runtime not prepared (${st.javaClass.simpleName})", Advice.SandboxNotPrepared)
        }
        val box = AndroidSandbox(app, state)
        // Command errors (for example a rejection because the network filter could not be installed) are recorded as they are; cancellation is propagated as usual
        suspend fun run(argv: List<String>): Result<CommandResult> = try {
            Result.success(box.run(SANDBOX_WORKSPACE, argv, null, COMMAND_MS) {})
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val echo = run(listOf("sh", "-c", "echo ok"))
        val echoOk = echo.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "ok" } == true
        val py = run(listOf("python3", "-c", "print(1+1)"))
        val pyOk = py.getOrNull()?.let { it.exitCode == 0 && it.output.trim() == "2" } == true
        fun what(r: Result<CommandResult>) = r.fold(
            { if (it.timedOut) "timed out" else "exit ${it.exitCode}, ${it.durationMs} ms" }, { "error ${it.javaClass.simpleName}" },
        )
        val detail = "echo ${if (echoOk) "ok" else "failed"} (${what(echo)}), python3 ${if (pyOk) "ok" else "failed"} (${what(py)})"
        return when {
            echoOk && pyOk -> CheckOutcome.pass(detail)
            echoOk -> CheckOutcome.partial(detail, Advice.SandboxFailed)
            else -> CheckOutcome.fail(detail, Advice.SandboxFailed)
        }
    }

    // ---------- 12 another copy of the app ----------

    private suspend fun appCopies(): CheckOutcome {
        val sh = shell ?: return CheckOutcome.fail("no helper", Advice.SetUpPhoneControl)
        val users = withContext(Dispatchers.IO) { runCatching { sh.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) }.getOrNull() }
            ?: return CheckOutcome.partial("other users could not be read", Advice.Error)
        return CheckOutcome.pass("${users.size} usable other profile(s)")
    }

    // ---------- cleanup ----------

    override suspend fun release() {
        withContext(NonCancellable) {
            withContext(Dispatchers.Main) {
                runCatching { SelfCheckTargetActivity.finishOpen() }
                web?.close()
                web = null
            }
            withContext(Dispatchers.IO) {
                display?.release()
                display = null
            }
            screen = null
            shell = null
            connection?.let {
                connection = null
                phone.sharedShell.release()
            }
        }
    }

    // ---------- helpers ----------

    private fun metrics(): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
    }

    /** The top task on the main screen; null if it cannot be read. */
    private fun mainTop(sh: IMikaShell): Int? = runCatching { sh.topTaskId(Display.DEFAULT_DISPLAY) }.getOrNull()?.takeIf { it >= 0 }

    private suspend fun sampleMain(sh: IMikaShell, stage: String) {
        val before = mainBefore ?: return
        val now = withContext(Dispatchers.IO) { mainTop(sh) }
        if (now != before && stage !in mainChanged) mainChanged += stage
    }

    private fun topPackage(sh: IMikaShell, displayId: Int): String? = runCatching {
        sh.topTaskId(displayId).takeIf { it >= 0 }?.let(sh::packageOfTask)
    }.getOrNull()

    private fun isTestPageOnTop(sh: IMikaShell, displayId: Int): Boolean =
        runCatching { sh.topActivity(displayId) }.getOrNull()?.contains(SelfCheckTargetActivity::class.java.simpleName) == true

    private suspend fun awaitFrame(d: AgentDisplay): Bitmap? {
        repeat(FRAME_TRIES) {
            d.latestFrameBitmap()?.let { return it }
            delay(POLL_MS)
        }
        return null
    }

    /** Fingerprint of the live frame (the downscaled one); null when there is no frame yet. */
    private fun frameHash(d: AgentDisplay): Int? {
        val bmp = d.frames.value ?: return null
        return runCatching {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            px.contentHashCode()
        }.getOrNull()
    }

    private suspend fun poll(timeoutMs: Long = VERIFY_MS, block: suspend () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (block()) return true
            delay(POLL_MS)
        }
        return block()
    }

    private fun ownContent(source: ScreenSnapshot): ScreenSnapshot? = selfCheckWindowContent(source, app.packageName)

    /**
     * An element on the self-check page: first by resource id (screen reading supplies the short name, and the tail of the fully-qualified form is accepted too), then by the text on the page (elements that have text carry no resource id when read).
     * The text is taken in the app language, just like the self-check page; comparisons are case-insensitive and ignore whitespace (see [SelfCheckText]).
     */
    private fun ScreenSnapshot.find(key: String): UiElement? =
        elements.firstOrNull { e -> e.resourceId?.let { it == key || it.endsWith("/$key") } == true }
            ?: elements.firstOrNull { e ->
                when (key) {
                    TITLE -> SelfCheckText.same(e.name, tr(R.string.selfcheck_target_title))
                    COUNT -> tapsText.matches(e.name)
                    BUTTON -> e.clickable && SelfCheckText.same(e.name, tr(R.string.selfcheck_target_button))
                    INPUT -> e.editable
                    TICK -> tickText.matches(e.name)
                    LIST -> e.scrollable
                    else -> false
                }
            }

    /** The visible rows on the self-check page (top to bottom). */
    private fun ScreenSnapshot.rows(): List<String> =
        elements.filter { rowText.matches(it.name) }.sortedBy { it.centerY }.map { it.name }

    /**
     * The "what was seen" written into the report when screen reading does not find the self-check page: for the self-check page, the package name and the first few elements; for a system UI, only the package name, the count and the kinds or ids of the elements
     * (the top of system settings may show the user's name, so no text is written); for other apps not even the package name is written.
     */
    private fun seen(snap: ScreenSnapshot): String {
        val pkg = snap.packageName
        val n = snap.elements.size
        val first = snap.elements.take(SEEN_ELEMENTS)
        return when {
            pkg == app.packageName -> "saw Claire, $n elements: " + first.joinToString { (it.resourceId ?: it.name).take(SEEN_CHARS) }
            pkg == null -> "saw no readable window, $n elements"
            systemPackage(pkg) -> "saw $pkg, $n elements: " + first.joinToString { (it.resourceId ?: it.role).take(SEEN_CHARS) }
            else -> "saw another app, $n elements"
        }
    }

    private fun systemPackage(pkg: String): Boolean = pkg == SystemPickers.settingsPackage(app.packageManager) || runCatching {
        app.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    /** Text with a number in it ("tapped %d times", "timer %d", "row %d"): fetched in the app language and used to recognise elements. */
    private fun numbered(res: Int) = SelfCheckText.Numbered(tr(res, SENTINEL), SENTINEL.toString())

    private val tapsText by lazy { numbered(R.string.selfcheck_target_taps) }
    private val tickText by lazy { numbered(R.string.selfcheck_target_tick) }
    private val rowText by lazy { numbered(R.string.selfcheck_target_row) }

    private fun UiElement.number(): Int? = Regex("\\d+").findAll(SelfCheckText.digitsToAscii(name)).lastOrNull()?.value?.toIntOrNull()

    private fun reason(r: ActionResult): String = (r as? ActionResult.Failed)?.reason ?: "not on top"

    private fun format(ms: Double) = String.format(java.util.Locale.ROOT, "%.1f", ms)

    private companion object {
        const val TASK = "self-check"
        const val SANDBOX_WORKSPACE = "self-check"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val HELPER_WAIT_MS = 10_000L
        const val LATENCY_CALLS = 5
        const val SETTLE_MS = 2_000L
        const val VERIFY_MS = 2_000L
        const val POLL_MS = 100L
        const val FRAME_TRIES = 20
        const val BLANK_SPREAD = 8
        const val BACK_TRIES = 2
        /** Maximum number of presses in total, including the ones that hide the IME. */
        const val BACK_MAX_PRESSES = 5
        const val ALIVE_MS = 15_000L
        const val ALIVE_STEP_MS = 500L
        /** If the frame has not changed at all for this long, it counts as stopped (the number on the page ticks every 250 ms). */
        const val STALL_MS = 3_000L
        const val COMMAND_MS = 8_000L
        const val MAX_CHARS = 4_000
        const val SETTINGS_IN_USE = "Settings is open on the main screen, so it was not opened"
        const val SETTINGS_ON_MAIN = "Settings opened on the main screen"
        /** Recognising the system settings search entry: by the last segment of the resource id, or by the entry's name (Chinese and English). */
        val SETTINGS_SEARCH_IDS = setOf("search", "search_icon", "search_action_bar_title", "search_button")
        val SETTINGS_SEARCH_LABELS = setOf("Search", "Search settings", "搜索", "搜索设置", "搜尋", "搜尋設定")
        /** Screen-off self-check: the wake lock limit (the main screen is not turned on), the timeout for waiting until the screen is off and locked, and the total budget for the stages. */
        const val SCREEN_OFF_WAKE_LOCK_MS = 90_000L
        const val SCREEN_OFF_READY_MS = 45_000L
        const val SCREEN_OFF_STAGES_MS = 35_000L

        const val COUNT = "selfcheck_count"
        const val BUTTON = "selfcheck_button"
        const val INPUT = "selfcheck_input"
        const val TICK = "selfcheck_tick"
        const val LIST = "selfcheck_list"
        const val TITLE = "selfcheck_title"
        val EXPECTED = listOf(TITLE, COUNT, BUTTON, INPUT, TICK, LIST)
        const val ASCII = "Claire 123"
        const val CHINESE = "你好米卡"
        const val SENTINEL = 987_654_321
        /** For the screen-reading check, how long at most to wait for the self-check page to appear. */
        const val PAGE_WAIT_MS = 5_000L
        const val SEEN_ELEMENTS = 5
        const val SEEN_CHARS = 24

        const val WEB_PAGE = "https://appassets.androidplatform.net/assets/selfcheck/page.html"
        const val WEB_PAGE_AR = "https://appassets.androidplatform.net/assets/selfcheck/page-ar.html"
        const val WEB_MARKER = "SELFCHECK-READ-OK"
        const val WEB_BUTTON = "#selfcheck-button"
        const val WEB_CLICKED = "Clicked 1"
        const val WEB_CLICKED_AR = "عدد النقرات: 1"
    }
}

internal data class SelfCheckWindowRead(
    val content: ScreenSnapshot?,
    val missing: List<String>,
    val wrongAppOnTop: Boolean,
) {
    val ready: Boolean get() = content != null && missing.isEmpty() && !wrongAppOnTop
}

/**
 * Narrows the merged screen-reading result down to Claire's own application window, then checks the elements. Only while Claire is still the topmost task
 * does a system app's overlay window leave the self-check unaffected; a full system app has its own top-level task and still counts as switched away.
 */
internal fun readSelfCheckWindow(
    screen: ScreenSnapshot,
    packageName: String,
    topTaskPackage: String?,
    expected: List<String>,
    isSystemPackage: (String) -> Boolean,
    find: ScreenSnapshot.(String) -> UiElement?,
): SelfCheckWindowRead {
    val content = selfCheckWindowContent(screen, packageName)
    val missing = expected.filter { key -> content?.let { it.find(key) } == null }
    val top = screen.packageName
    val wrongApp = when {
        topTaskPackage != null && topTaskPackage != packageName -> true
        top == null || top == packageName -> false
        !isSystemPackage(top) -> true
        // When the task ownership cannot be read, a system window must not be treated as an overlay; keep it as a failure.
        else -> topTaskPackage != packageName
    }
    return SelfCheckWindowRead(content, missing, wrongApp)
}

/** The original snapshot's ids stay unchanged; only the elements and the window tree are narrowed to the given app's application windows. */
internal fun selfCheckWindowContent(screen: ScreenSnapshot, packageName: String): ScreenSnapshot? {
    val ownWindows = screen.windows.filter { it.type == "application" && it.packageName == packageName }
    if (ownWindows.isEmpty()) return null
    val refs = buildSet {
        fun visit(nodes: List<UiTreeNode>) {
            for (node in nodes) {
                node.ref?.let(::add)
                visit(node.children)
            }
        }
        ownWindows.forEach { visit(it.nodes) }
    }
    return screen.copy(
        packageName = packageName,
        elements = screen.elements.filter { it.ref in refs },
        windows = ownWindows,
    )
}
