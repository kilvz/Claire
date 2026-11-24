package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.screen.uistate.LaunchChoice
import com.wangzan101.mika.core.screen.uistate.LaunchChooser
import com.wangzan101.mika.core.screen.uistate.OpenedElsewhere
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The whole handling of "an app got opened on the user's main screen": watch the main screen for tasks appearing
 * before and after an action, move apps opened on the main screen back to Claire's screen, or wait for the user to
 * finish choosing or handling it by hand there. Fields and methods shared with [AndroidScreen] are accessed through
 * [screen] as a forward reference.
 */
internal class ScreenSpill(private val screen: AndroidScreen) {

    /** Watch for one action: [since] the moment it started, [topBefore] the top task on the main screen then,
     * [agentTopBefore] the top task on Claire's screen then, [userActiveBefore] the user had just operated the main
     * screen before the action started. */
    internal class Watch(val since: Long, val topBefore: Int, val agentTopBefore: Int, val userActiveBefore: Boolean)

    /** The app moved back, and (when the system asks which one to open) the recognized options. */
    internal class Spill(val opened: OpenedElsewhere, val chooser: LaunchChoice?)

    /** Watch for the most recent action; cleared after [AndroidScreen.SPILL_WINDOW_MS]. */
    @Volatile internal var watch: Watch? = null

    /** Up to which moment records have already been judged: the same one is not judged twice. */
    @Volatile internal var consumed = 0L

    /**
     * Tasks moved back from the user's main screen to Claire's screen, and ones opened from them: their being
     * on top counts as opened by the target app, not as being switched away.
     */
    internal val adopted: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    internal val _userPick = MutableStateFlow<AndroidScreen.UserPick?>(null)
    /** Waiting for the user to choose on the main screen; the task pauses on this, and the capsule asks the user to choose. */
    internal val userPick: StateFlow<AndroidScreen.UserPick?> = _userPick.asStateFlow()

    /** What must be reported to the model once the things waiting for the user are done; reported at the next screen read or action. */
    @Volatile internal var pendingReport: Spill? = null

    /** Times and packages of the user's taps on the main screen (recorded only during the short stretch around an action). */
    internal val userTaps = java.util.concurrent.ConcurrentLinkedDeque<MainScreenSpill.Tap>()

    /**
     * Main-screen interaction count when the previous action started; if the count changed between two actions,
     * the user operated the main screen (taps before the action whose exact moment is unavailable).
     */
    @Volatile internal var lastInteractions = -1L
    internal val spillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile internal var tapWatch: Job? = null
    @Volatile internal var tapWatchUntil = 0L

    /** Navigation evidence that the user returned to the main screen's launcher; launcher apps are resolved via HOME, not hardcoded. */
    internal val homeApps: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching { screen.a11y.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList()).toSet()
    }

    /** Not counted as opened on the main screen: Claire itself, launcher apps. */
    internal val notSpills: Set<String> by lazy { homeApps + screen.a11y.packageName }

    /**
     * Before an action: ask the built-in assistant to watch the main screen and record the top task of both
     * screens right now; null (do not watch) when there is no screen, a takeover is in progress, or the
     * assistant does not support it.
     */
    internal fun armSpillWatch(): Watch? {
        if (!screen.hasDisplay || screen.handedOver()) return null
        val count = ScreenAccessibility.mainInteractionCount()
        val userActiveBefore = lastInteractions >= 0 && count != lastInteractions
        lastInteractions = count
        val since = SystemClock.uptimeMillis()
        watchUserTaps(since + AndroidScreen.SPILL_WINDOW_MS)
        val top = runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }.getOrNull() ?: return null
        val agentTop = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        return Watch(since, top, agentTop, userActiveBefore).also { watch = it }
    }

    /** Records the user's taps on the main screen while watching (Claire's own overlays excluded); stops itself when the time is up. */
    internal fun watchUserTaps(until: Long) {
        tapWatchUntil = maxOf(tapWatchUntil, until)
        if (tapWatch?.isActive == true) return
        tapWatch = spillScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
                screen.a11y.eventsFor(Display.DEFAULT_DISPLAY).collect { e ->
                    val systemTouch = e.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    if (MainScreenSpill.isUserTap(e.eventType) &&
                        // A click on Claire's own overlay control is not the user opening another app; system
                        // touch events carry no such package-name ambiguity
                        (systemTouch || e.packageName?.toString() != screen.a11y.packageName)
                    ) {
                        // System touch events carry the real time it happened; entry points that emit no
                        // view-click events, such as the notification shade or the launcher, must not be missed
                        // either
                        val at = if (systemTouch) e.eventTime else SystemClock.uptimeMillis()
                        userTaps.addLast(MainScreenSpill.Tap(at, e.packageName?.toString()))
                        while (userTaps.size > AndroidScreen.MAX_USER_TAPS) userTaps.pollFirst()
                    }
                }
            }
            while (true) {
                val left = tapWatchUntil - SystemClock.uptimeMillis()
                if (left <= 0) break
                delay(left)
            }
            collecting.cancel()
        }
    }

    /**
     * After an action: ask the built-in assistant for the tasks that came up on the main screen since this action
     * started and judge them by [MainScreenSpill]; ones opened on the main screen are moved back to Claire's screen,
     * ones opened inside the app the user is using are only reported, not moved. null if none.
     */
    internal suspend fun takeSpill(w: Watch?): Spill? {
        if (w == null || screen.handedOver()) return null
        val b = runCatching { screen.shell.mainArrivals(maxOf(w.since, consumed + 1)) }.getOrNull() ?: return null
        val arrivals = parseArrivals(b)
        arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
        var last: Spill? = null
        for (v in MainScreenSpill.decide(arrivals, w.since, AndroidScreen.SPILL_WINDOW_MS, w.topBefore, notSpills, userTaps.toList(), homeApps, w.userActiveBefore)) {
            val a = v.arrival
            val pkg = a.packageName ?: continue
            // Opened on the user's main screen, which is not the screen the app-access boundary watches: do not move or read it when it is denied
            screen.appAccess.current().denial(pkg)?.let { throw it }
            last = when (v) {
                // When the system asks which one to open, handle it as a chooser first; popups that need the
                // user's own hands (sign-in verification, account confirmation and the like) stay on the main
                // screen, not moved to Claire's screen
                is MainScreenSpill.PullBack -> leaveForUser(a, pkg, w) ?: leavePopup(a, pkg) ?: pullBack(a, pkg) ?: last
                is MainScreenSpill.InUse -> Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId)), null)
            }
        }
        if (last != null) Log.i(AndroidScreen.TAG, "opened on the main screen after an action: ${last.opened}")
        return last
    }

    /**
     * Asks again while the watch from the previous action has not expired (that action opened it a while later);
     * clears it once expired.
     * Anything to say after the user finished choosing or handling it on the main screen is said first here.
     */
    internal suspend fun lateSpill(): Spill? {
        pendingReport?.let { pendingReport = null; return it }
        val w = watch ?: return null
        val spill = takeSpill(w)
        if (SystemClock.uptimeMillis() > w.since + AndroidScreen.SPILL_WINDOW_MS) watch = null
        return spill
    }

    /**
     * What came to the main screen is a popup that needs the user's own hands (sign-in verification, account
     * confirmation, payment confirmation and the like): do not move it, leave it on the user's screen.
     * Criteria: a page of a system account-login service (a package with registered account authenticators and no
     * launcher icon), the top window is not full screen (a dialog, a bottom sheet), the window has a password field,
     * or the page itself is excluded from recents (excludeFromRecents).
     * Such a window moved to Claire's screen would disappear from in front of the user, and the user could not tap
     * confirm on the main screen. null if none of these.
     * noHistory does not count: many apps' splash pages carry it, and counting it would treat ordinary apps as popups.
     */
    internal suspend fun leavePopup(a: MainScreenSpill.Arrival, pkg: String): Spill? =
        if (needsUserWindow(a, pkg)) popup(a, pkg) else null

    /**
     * Whether this task's top window on the main screen is a popup that needs the user's own hands (see
     * [leavePopup]). Waits at most two extra [AndroidScreen.POPUP_SETTLE_MS].
     */
    internal suspend fun needsUserWindow(a: MainScreenSpill.Arrival, pkg: String): Boolean {
        if (isAuthenticatorUi(pkg)) return true
        val activity = a.topActivity?.let {
            runCatching { screen.a11y.packageManager.getActivityInfo(ComponentName(pkg, it), 0) }.getOrNull()
        }
        if (activity != null && activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0) return true
        val metrics = screen.a11y.resources.displayMetrics
        // A window that just appeared may still be in its opening animation (drawn smaller than full screen);
        // wait for it to settle and look again, so an ordinary app is not taken for a popup
        var window = topWindowOf(pkg)
        if (window == null) {
            delay(AndroidScreen.POPUP_SETTLE_MS)
            window = topWindowOf(pkg) ?: return false
        }
        if (isNarrow(window, metrics)) {
            delay(AndroidScreen.POPUP_SETTLE_MS)
            val settled = topWindowOf(pkg) ?: return false
            if (isNarrow(settled, metrics)) return true
            window = settled
        }
        return ScreenReader.read(listOf(window), metrics.widthPixels, metrics.heightPixels).passwordField
    }

    internal fun topWindowOf(pkg: String): AccessibilityWindowInfo? =
        ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg }

    /**
     * Whether this package is a system account-login service: it has registered account authenticators
     * (AccountManager.authenticatorTypes, platform data) and no launcher icon of its own (no LAUNCHER entry). Such a
     * package has no home page of its own; what it brings to the main screen is only add-account, login, verification
     * and code-entry pages, which are the user's own business, so even a full-screen one without a password field must
     * not be moved to Claire's screen. Authenticator packages that do have a launcher icon (chat apps that sync
     * contacts, mail apps, etc.) do not count, and their session, share and compose pages are moved back as usual. The
     * authenticator table is read fresh every time: newly installed authenticators are recognized too; this step only
     * runs when a page comes to the main screen.
     */
    internal fun isAuthenticatorUi(pkg: String): Boolean {
        val authenticators = runCatching { AccountManager.get(screen.a11y).authenticatorTypes.mapNotNull { it.packageName }.toSet() }
            .getOrDefault(emptySet())
        if (pkg !in authenticators) return false
        // A package with a launcher icon is definitely visible to Claire (LAUNCHER was queried in the
        // manifest); only when it cannot be found does it count as absent
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val hasLauncher = runCatching { screen.a11y.packageManager.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
        return MainScreenSpill.authenticatorUi(pkg, authenticators, hasLauncher)
    }

    /** The window does not cover the whole screen: dialogs, bottom sheets, floating windows and the like. */
    internal fun isNarrow(window: AccessibilityWindowInfo, metrics: android.util.DisplayMetrics): Boolean {
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        return bounds.width() < metrics.widthPixels * AndroidScreen.FULL_SCREEN_SHARE || bounds.height() < metrics.heightPixels * AndroidScreen.FULL_SCREEN_SHARE
    }

    internal suspend fun popup(a: MainScreenSpill.Arrival, pkg: String): Spill =
        Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = false, inProfile = inProfileOf(pkg, a.userId), needsUser = true), null)

    /**
     * What was opened on the main screen is the system window asking "open with which one" (the system picker, or a
     * system package's window whose options match an app installed twice; each system's chooser is not necessarily the
     * stock one): do not move it, leave it for the user to choose on their own screen, and record it so the task waits.
     * null if not.
     */
    internal suspend fun leaveForUser(a: MainScreenSpill.Arrival, pkg: String, w: Watch): Spill? {
        val system = screen.isSystemPackage(pkg)
        val choice = if (system) mainChooser(pkg) else null
        if (MainScreenSpill.handling(system, pkg in screen.systemPickers, choice != null) != MainScreenSpill.Handling.LeaveForUser) return null
        val app = choice?.options?.firstOrNull()?.label ?: screen.appLabel(pkg)
        _userPick.value = AndroidScreen.UserPick(a.taskId, pkg, app, w.topBefore, w.agentTopBefore, a.at)
        return Spill(OpenedElsewhere(pkg, app, pulledBack = false, picker = true), choice)
    }

    /**
     * Moves the task opened on the main screen to Claire's screen (move only, never reopening it on its
     * behalf), and waits for it to come to the top; null if it cannot be moved.
     */
    internal suspend fun pullBack(a: MainScreenSpill.Arrival, pkg: String, byUser: Boolean = false): Spill? {
        if (!runCatching { screen.shell.moveTask(a.taskId, screen.displayId) }.getOrDefault(false)) {
            Log.w(AndroidScreen.TAG, "could not move task ${a.taskId} ($pkg) from the main screen")
            return null
        }
        adopted += a.taskId
        // Which app it will open is unknown in advance: reserve after moving it back; if the reservation fails, do not undo the move
        if (screen.checkOtherTasks) screen.reserve(pkg)
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.LAUNCH_WAIT_MS
        while (runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1) != a.taskId && SystemClock.uptimeMillis() < deadline) delay(AndroidScreen.POLL_MS)
        screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
        val profile = if (screen.isSystemPackage(pkg)) null else inProfileOf(pkg, a.userId)
        return Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = true, inProfile = profile, byUser = byUser), null)
    }

    /**
     * Whether this system package's window on the main screen is asking which one to open: which app it is asking
     * about is unknown in advance, so the names of apps installed twice are matched against the window's options
     * (having the same app installed twice is exactly when it asks this way); only this system window is read, null
     * when nothing matches.
     */
    internal suspend fun mainChooser(pkg: String): LaunchChoice? {
        val w = ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).firstOrNull { it.root?.packageName?.toString() == pkg } ?: return null
        val elements = ScreenReader.read(listOf(w), screen.display.width, screen.display.height).entries.map { it.element }
        for ((app, names) in screen.otherProfiles()) {
            val label = screen.appLabel(app) ?: continue
            LaunchChooser.options(label, elements, names)?.let { return LaunchChoice(pkg, it) }
        }
        return null
    }

    /**
     * Called by the task before the next action that changes the screen: waits for the user to finish choosing on the
     * main screen (watching the main screen until they do). After choosing, ordinary pages opened on the main screen
     * are moved back to Claire's screen (move only; the app that opened them is still the original one); ones that need
     * the user's own hands (a password to type, an unreadable window, e.g. payments or fingerprint) stay on the main
     * screen and [AndroidScreen.PickResult.NeedsUser] is returned; ones opened on Claire's screen also count; if the
     * user dismissed the question, say so truthfully. null when not waiting.
     */
    suspend fun awaitUserPick(): AndroidScreen.PickResult? = withContext(Dispatchers.IO) {
        val p = _userPick.value ?: return@withContext null
        try {
            while (runCatching { screen.shell.displayOfTask(p.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY) {
                runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }
                delay(AndroidScreen.PICK_POLL_MS)
            }
            val gone = SystemClock.uptimeMillis()
            runCatching { screen.shell.watchMainScreen(AndroidScreen.SPILL_WINDOW_MS.toInt()) }
            val deadline = gone + AndroidScreen.PICK_WAIT_MS
            while (true) {
                val arrivals = runCatching { screen.shell.mainArrivals(p.since) }.getOrNull()?.let(::parseArrivals).orEmpty()
                val opened = MainScreenSpill.picked(arrivals, p.taskId, gone, AndroidScreen.PICK_SLACK_MS, AndroidScreen.PICK_WAIT_MS, p.topBefore, notSpills, homeApps)
                arrivals.maxOfOrNull { it.at }?.let { consumed = maxOf(consumed, it) }
                if (opened != null) {
                    val pkg = opened.packageName!!
                    if (needsUser(pkg) || needsUserWindow(opened, pkg)) {
                        return@withContext AndroidScreen.PickResult.NeedsUser(opened.taskId, pkg, screen.appLabel(pkg) ?: p.app, opened.userId)
                    }
                    pendingReport = pullBack(opened, pkg)
                    return@withContext AndroidScreen.PickResult.Settled
                }
                // The chosen app opened on Claire's screen (it was opened from over there)
                val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
                if (top >= 0 && top != p.agentTop && top !in adopted) {
                    adopted += top
                    screen.waitStable(AndroidScreen.SETTLE_CAP_MS)
                    val pkg = runCatching { screen.shell.packageOfTask(top) }.getOrNull() ?: p.packageName
                    val profile = inProfileOf(pkg, runCatching { screen.shell.userOfTask(top) }.getOrDefault(-1))
                    pendingReport = Spill(OpenedElsewhere(pkg, screen.appLabel(pkg), pulledBack = true, inProfile = profile), null)
                    return@withContext AndroidScreen.PickResult.Settled
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                delay(AndroidScreen.POLL_MS)
            }
            pendingReport = Spill(OpenedElsewhere(p.packageName, p.app, pulledBack = false, picker = true, closed = true), null)
            AndroidScreen.PickResult.Settled
        } finally {
            _userPick.value = null
        }
    }

    /**
     * The user finished ([AndroidScreen.PickResult.NeedsUser]) and tapped continue: if it is still on the main
     * screen, move it back to Claire's screen and carry on; if it has closed, say so truthfully.
     */
    suspend fun afterUserStep(r: AndroidScreen.PickResult.NeedsUser) = withContext(Dispatchers.IO) {
        val still = runCatching { screen.shell.displayOfTask(r.taskId) }.getOrDefault(-1) == Display.DEFAULT_DISPLAY
        val moved = if (still) pullBack(MainScreenSpill.Arrival(r.taskId, r.packageName, r.userId, 0, Display.DEFAULT_DISPLAY, false, true, Display.DEFAULT_DISPLAY), r.packageName, byUser = true) else null
        pendingReport = moved ?: Spill(OpenedElsewhere(r.packageName, r.app, pulledBack = false, byUser = true), null)
    }

    /**
     * This app's window on the main screen needs the user's own hands: it has a password field, or nothing can be read
     * from the tree and taking a main-screen screenshot makes the system report a secure window (the captured picture
     * is not kept, it is recycled at once).
     * Only checks whether such a thing is there, never reads what is written in it.
     */
    internal suspend fun needsUser(pkg: String): Boolean {
        // Reading a window on the user's main screen, which the app-access boundary cannot see: do not read it when it is denied
        screen.appAccess.current().denial(pkg)?.let { throw it }
        // A window that just came up takes a while to become readable: conclude once content can be read (or a
        // password field is seen); if still unreadable at the time limit, check whether it is a secure window
        val deadline = SystemClock.uptimeMillis() + AndroidScreen.PICK_READ_MS
        while (true) {
            val mine = ScreenReader.contentWindows(screen.a11y.windowsOn(Display.DEFAULT_DISPLAY)).filter { it.root?.packageName?.toString() == pkg }
            val r = mine.takeIf { it.isNotEmpty() }?.let { ScreenReader.read(it, screen.display.width, screen.display.height) }
            val readable = r?.entries?.any { it.element.meaningful } == true
            if (r?.passwordField == true || readable) return MainScreenSpill.needsUser(r?.passwordField == true, readable, secureWindow = false)
            if (SystemClock.uptimeMillis() >= deadline) {
                val shot = runCatching { screen.a11y.takeMainScreenshot() }.getOrNull()
                (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                return MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = shot is ScreenAccessibility.MainShot.Secure)
            }
            delay(AndroidScreen.POLL_MS * 2)
        }
    }

    /**
     * If the task on top of Claire's screen before the action was one moved back (or one opened from it), and after
     * the action the top has changed to another task: that one was opened along the same path too, count it as well.
     * For an app installed twice, reports which copy was opened.
     */
    internal suspend fun adoptFromAdopted(w: Watch?): String? {
        if (w == null || w.agentTopBefore !in adopted) return null
        val top = runCatching { screen.shell.topTaskId(screen.displayId) }.getOrDefault(-1)
        if (top < 0 || top == w.agentTopBefore || top in adopted || top == screen._target.value?.taskId) return null
        adopted += top
        val pkg = runCatching { screen.shell.packageOfTask(top) }.getOrNull() ?: return null
        return inProfileOf(pkg, runCatching { screen.shell.userOfTask(top) }.getOrDefault(-1))
    }

    /**
     * Which copy of an app installed twice was opened: the same user as Claire means the original copy; null
     * when installed only once or undeterminable.
     */
    internal suspend fun inProfileOf(pkg: String, userId: Int): String? {
        if (userId < 0 || pkg !in screen.otherProfiles()) return null
        return if (userId == AndroidScreen.myUser) OpenedElsewhere.MAIN_PROFILE else OpenedElsewhere.OTHER_PROFILE
    }

    internal fun parseArrivals(b: Bundle): List<MainScreenSpill.Arrival> {
        val tasks = b.getIntArray(MainScreenWatch.KEY_TASK) ?: return emptyList()
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val users = b.getIntArray(MainScreenWatch.KEY_USER)
        val at = b.getLongArray(MainScreenWatch.KEY_AT)
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)
        val activity = b.getStringArray(MainScreenWatch.KEY_ACTIVITY)
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)
        if (packages == null || users == null || at == null || from == null || same == null || standard == null || now == null) return emptyList()
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i], activity?.getOrNull(i))
        }
    }

    internal fun hintLateSpill(o: OpenedElsewhere): String {
        val name = "${o.app ?: o.packageName} (${o.packageName})"
        val copy = when (o.inProfile) {
            OpenedElsewhere.OTHER_PROFILE -> " It is the other copy of the app (clone or other profile): if the user did not ask for that copy, go back and pick the other option."
            OpenedElsewhere.MAIN_PROFILE -> " It is the main copy of the app."
            else -> ""
        }
        return when {
            o.closed -> "The user closed the system's question on their screen without picking, so nothing was opened. Decide what to do next; do not simply repeat the action."
            o.byUser && !o.pulledBack -> "$name needed the user; they handled it on their own screen and tapped continue, and it has closed. Observe before acting."
            o.byUser -> "$name needed the user; they handled it on their own screen and tapped continue. It is now in front on Claire's screen. Observe before acting."
            o.needsUser && o.onUserScreen -> "It was $name: a popup on the user's screen that needs them (a sign-in, verification or confirmation step). " +
                "It was left there on purpose; do not repeat the action. Call hand_to_user so they can finish it, then observe before acting."
            o.pulledBack -> "It was $name, now in front on Claire's screen.$copy Observe before acting."
            else -> "It was $name, which the user is using on their own screen, so it was left there. " +
                "Do not repeat the previous action: call hand_to_user with reason other so the user can do that step there, or wait and try again later."
        }
    }
}
