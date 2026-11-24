package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.wangzan101.mika.core.device.shell.DevtoolsSocket
import com.wangzan101.mika.core.screen.uistate.AppCopies
import com.wangzan101.mika.core.screen.uistate.PressKeys
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.wangzan101.mika.shell.IMikaShell

/**
 * Resident as shell (uid 2000), owning the Agent's virtual displays, running inside the built-in helper process
 * ([HelperMain]). When the process ends, the virtual displays and the apps on them are destroyed with it, so it must
 * stay resident rather than be a one-shot command.
 *
 * Hidden interfaces are always called through reflection; when reflection fails it falls back to a shell command with
 * the same effect.
 */
class ShellService(
    /** Accepts calls only from this uid (Claire). */
    internal val allowedUid: Int,
    /** Directory holding the mute records; a location the shell identity can write to. */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    internal val context: Context = ShellContext.create()
    internal val displays = HashMap<Int, VirtualDisplay>()
    internal var orientationWatch: DisplayOrientationWatch? = null
    internal val requestedOrientations = ConcurrentHashMap<Int, Int>()
    /** Muted package → the PLAY_AUDIO mode before muting ("default" means it was not set), kept under [stateDir] so the next start resumes restoring after the process is killed. */
    internal val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    internal val callAudio = ShellCallAudio(context)
    // Bundle copies the nested PFDs into the reply but never closes the server-side originals. They are closed after
    // the reply is written, otherwise the audio thread never sees EOF when the client closes the pipe.
    internal val returnedCallPipes = ThreadLocal<List<ParcelFileDescriptor>>()
    internal data class FilePolicyState(val session: String, val revision: Long, val roots: List<String>, val recursive: List<Boolean>, val policy: DeviceFilePolicy)
    internal val filePolicyLock = Any()
    @Volatile internal var filePolicy: FilePolicyState? = null
    /** The user Claire runs as: the original app copy is installed here; other users hold another copy (clone, work profile). */
    internal val myUser = allowedUid / PER_USER_RANGE

    // Do not use getSystemService: the DisplayManager obtained that way is bound to the system context, so its package name does not match the shell uid
    @SuppressLint("PrivateApi")
    internal val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // The clipboard must be accessed under the shell package name (the system only honours the "suppress copied overlay" flag for clipboard data from shell); reading bypasses the Settings query in ClipboardManager that does not work in the shell process
    internal val clipboard = ShellClipboard(context)
    internal val clipLock = Any()
    /** No other task's launch may slip in between the occupancy check and the launch: when two tasks open the same app at once, only one can succeed. */
    internal val launchLock = Any()
    /** The user's clipboard before setClipText (null means it really was empty; an unreadable one is never swapped out); [clipSaved] false means there is nothing to restore. */
    internal var savedClip: ClipData? = null
    internal var clipSaved = false
    /** The text Claire put in last; used before restoring to decide whether the clipboard is still Claire's (if the user copied something else meanwhile, leave it alone). */
    internal var ourText: String? = null

    internal val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // When shell lacks ADD_TRUSTED_DISPLAY (some systems do not grant it): drop the flags that only work on a trusted display and create it once more.
            // After the fallback this display has no focus of its own; the self-check page reports "own focus no" and warns that main-screen focus may be affected (Advice.FocusShared).
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t: Throwable) {
                Log.e(TAG, "createDisplay failed", t)
                -1
            }
        }
    }

    override fun resizeDisplay(displayId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Boolean = synchronized(displays) {
        val vd = displays[displayId] ?: return@synchronized false
        if (width !in 1..4096 || height !in 1..4096 || dpi !in 72..1000 || !surface.isValid) return@synchronized false
        val old = android.util.DisplayMetrics().also { vd.display.getRealMetrics(it) }
        val oldMode = vd.display.mode
        val oldSurface = vd.surface
        try {
            vd.resize(width, height, dpi)
            vd.surface = surface
            oldSurface?.release()
            true
        } catch (e: Exception) {
            runCatching { vd.resize(oldMode.physicalWidth, oldMode.physicalHeight, old.densityDpi); vd.surface = oldSurface }
            Log.w(TAG, "resizeDisplay failed", e)
            false
        }
    }

    /** A virtual display in a group of its own never sleeps; see [DisplayAwake]. */
    internal val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager and its per-display user activity method; null when unavailable (logged once, the display still works, only nothing keeps it awake). */
    @get:SuppressLint("PrivateApi")
    internal val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        // Only remove it under the lock; closing the watcher (binder) and releasing the display happen outside it
        val (vd, watch) = synchronized(displays) {
            displays.remove(displayId) to (if (displays.isEmpty()) orientationWatch.also { orientationWatch = null } else null)
        }
        watch?.close()
        if (watch != null) requestedOrientations.clear()
        vd?.release()
    }

    /**
     * First confirm the user is not using this app on the main display: if it has a visible task there, do not launch
     * (return [START_IN_USE_ON_MAIN]), since the system would otherwise move away or interrupt what the user is looking
     * at. Background tasks may be pulled onto the virtual display. Then resolve the launch entry (some activities in a
     * package are trampolines that jump away immediately and leave only an empty task on the virtual display), and call
     * IActivityTaskManager.startActivityAsUser with the shell package name and ActivityOptions.setLaunchDisplayId.
     * Occupancy only considers the original copy (in the user Claire runs as): a copy installed in another user that the
     * user is using on the main display does not block this one.
     */
    override fun startApp(displayId: Int, packageName: String): Int = start(displayId, packageName, myUser)

    /** ILauncherApps (the system service behind LauncherApps); null when unavailable. */
    internal val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps service unavailable", it) }.getOrNull()
    }

    /**
     * End the process and start again (back to the initial page) only when all of this app's tasks are on Claire's
     * displays. If it still has tasks on other displays (user state), never end it; start normally instead and return
     * [START_OK_COLD_IGNORED]. Do not launch while the user is using it on the main display.
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

    /** Opens in another user copy: only copies [appCopyUsers] deems usable are accepted (private space and other people's users are never touched); rules in [AppCopies]. */
    override fun startAppInUser(displayId: Int, packageName: String, userId: Int, cold: Boolean): Int {
        if (userId == myUser) return if (cold) restartApp(displayId, packageName) else startApp(displayId, packageName)
        if (!ownDisplay(displayId)) return START_ERROR
        val usable = try {
            users()?.let { AppCopies.usable(myUser, it) }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot read users", t)
            null
        } ?: return START_ERROR
        if (userId !in usable) {
            Log.w(TAG, "refused to start $packageName in user $userId")
            return START_USER_NOT_ALLOWED
        }
        return if (cold) restart(displayId, packageName, userId) else start(displayId, packageName, userId)
    }

    override fun appCopyUsers(): Bundle? = try {
        users()?.let { users ->
            Bundle().apply {
                putIntArray(KEY_COPY_USERS, AppCopies.usable(myUser, users).toIntArray())
                putString(KEY_COPY_SUMMARY, AppCopies.summary(myUser, users))
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "appCopyUsers failed", t)
        null
    }

    /**
     * Opens a link or settings page. The shell side checks the link once more through [ShellLinks]; returns
     * [START_UNRESOLVED] when nothing resolves. Do not launch when an app that can open it has a task on the main
     * display: a link with NEW_TASK may land in that task and appear before the user. When only one app can open it,
     * target that page directly so the system does not pick another.
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        ShellLinks.refuse(action, uri, packageName)?.let { return it }
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // A web-style link only goes to pages that declare they are openable from links, never an app's internal page
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = activitiesFor(intent, null) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(link) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                    (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * Shares text with [packageName]: ACTION_SEND text/plain with EXTRA_TEXT, delivered only to that package. Returns
     * [START_UNRESOLVED] when it has no page accepting text shares; like [startApp], do not launch while the user is
     * using it on the main display. Target the page directly when there is only one share target; with several, the
     * system asks which one.
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val intent = ShareIntent.of(packageName, text)
        val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, ShareIntent.TYPE, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * Shares one workspace file with [packageName]: ACTION_SEND + EXTRA_STREAM, with the content URI readable by the
     * receiver and delivered only to that package. Returns [START_UNRESOLVED] when it has no page accepting such
     * shares; do not launch while the user is using it on the main display. Only the share page is opened; whether to
     * send is up to the model tapping on that page.
     */
    override fun startShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        val target = Uri.parse(uri)
        val intent = ShareIntent.ofFile(packageName, target, mime, text)
        val candidates = activitiesFor(intent, mime) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(listOf(packageName), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, mime, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(share file) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            exec(*ShareIntent.amArgsFile(displayId, packageName, target, mime, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * System actions (see [SystemIntents]): only those intents are allowed through. Returns [START_UNRESOLVED] when no
     * page accepts it; like [startView], do not launch when the receiving app has a task on the main display. Try
     * launching as shell first; when that fails (the receiving page requires a permission shell lacks, such as setting
     * an alarm), send the [asMika] PendingIntent Claire prepared: it launches as Claire (permissions checked as
     * Claire), while the display and background-start checks follow the sender (shell), and it still lands on this
     * display.
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: android.app.PendingIntent?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        // Claire's own compatibility self-check page: opened directly onto this display as shell
        if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
        if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (ShellLinks.inUse(candidates.map { it.first }.distinct(), onMain)) return START_IN_USE_ON_MAIN
            if (onOtherOwnDisplay(candidates.map { it.first }.distinct(), displayId)) return START_IN_USE_BY_OTHER
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val asShell = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, intent.type, null, null,
                0, 0, null, options.toBundle(), USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser(${intent.action}) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
            false
        }
        if (asShell) return START_OK
        if (asMika == null) return START_ERROR
        // Since Android 14 the sender of a PendingIntent must explicitly allow it to start activities from the background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        return try {
            asMika.send(context, 0, null, null, null, null, options.toBundle())
            START_OK
        } catch (t: Throwable) {
            Log.w(TAG, "sending ${intent.action} as Claire failed", t)
            START_ERROR
        }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun displayOfPackage(packageName: String): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { packageName in packagesOf(it) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackage failed", t)
        -1
    }

    override fun displayOfPackageInUser(packageName: String, userId: Int): Int = try {
        val ours = synchronized(displays) { displays.keys.toSet() }
        val displayField = TASK_INFO.getField("displayId")
        allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }.firstOrNull { it in ours } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfPackageInUser failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.className
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId), the call behind `am display move-stack`.
     * Manually verified on a real device (Android 14): tasks move both ways between the virtual display and the main
     * display. Tasks Claire launches are all root tasks.
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    /**
     * Tasks kept behind the main display when a task ended: task ID → key ([AppsAfterTask.keyOf]). The next time
     * Claire opens the same app copy it is moved back ([resumeKept]). In memory only: after a helper restart it is
     * unknown, so opening goes through the normal launch entry (the system will most likely pull that background task
     * over anyway).
     */
    internal val kept = HashMap<Int, String>()

    /**
     * End of a task: decide each task's fate on this display per [AppsAfterTask], move the kept ones to the back of the
     * main display, then release this display (it carries FLAG_DESTROY_CONTENT_ON_REMOVAL, so whatever was not moved is
     * closed with it). Order: decide fates → move the kept ones → look once more at the audible ones → wait for them to
     * retreat out of sight ([SettleBehind]) → move back to this display anything still visible → release. Claire
     * restores muting after this, so kept apps do not suddenly make noise during the restore.
     *
     * How moving to the back works (all Android-original interfaces): first hide the task with
     * WindowContainerTransaction.setHidden, then moveRootTaskToDisplay to the main display (the system always puts it on
     * top; hidden means it is not shown, takes no focus and does not pause the page the user is using), then reorder to
     * the back and unhide in the same transaction. If hiding or moving itself fails, it is closed with the display as
     * before.
     *
     * Moving and wrapping up run under [launchLock]; waiting for the transition in between (at most
     * [SettleBehind.TIMEOUT_MS]) does not hold the lock, so other tasks can still open apps. Tasks that another task
     * took onto its own display during that time are no longer handled; kept tasks are recorded into [kept] only when
     * wrapping up, and [resumeKept] will not take them while waiting.
     */
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        val sorting = synchronized(launchLock) {
            if (!ownDisplay(displayId)) return null
            sortAndMove(displayId, keep, close)
        }
        val settled = settleBehind(sorting.moved.mapTo(LinkedHashSet()) { it.task.id })
        return synchronized(launchLock) { closeOut(displayId, sorting, settled) }
    }

    /** The first stage of [finishDisplay]: the kept ones ([moved]) have been moved to the main display; [hidden] are those whose unhide failed after moving. */
    internal class Sorting(
        val moved: MutableList<AppsAfterTask.Fate>,
        val closed: MutableList<String>,
        val notes: MutableSet<String>,
        val hidden: Set<Int>,
    )

    /** OK: moved to the back of the main display; HIDDEN: moved over but the unhide failed, so it is still hidden; UNSUPPORTED: this system cannot move it. */
    internal enum class Behind { OK, HIDDEN, UNSUPPORTED }

    internal val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** Records of main-display tasks: starts watching before an Agent action, see [MainScreenWatch]. Created on first use (thread, task stack listener). */
    internal val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    override fun userOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("userId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userOfTask failed", t)
        -1
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // The same allowlist as press_key: even if the caller errs, never emit device-wide keys such as volume, power or Home from here
        if (!PressKeys.isAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        // The system routes keys by input focus and ignores the displayId on the event: untargeted, they go to the main-screen focus and the app on the virtual display never gets the back key.
        // When the top task's uid on the virtual display resolves, target it; if targeting does not go through, fall back to untargeted injection (as in the old path), and only then to a shell command.
        // Log the channel actually used at each level, to help locate back-injection failures on real devices (#622).
        val uid = topTaskUid(displayId)
        var channel = if (uid != null) "targeted" else "nontargeted"
        val ok = if (uid != null) {
            injectPairTo(down, up, displayId, uid) {
                channel = "nontargeted"
                injectPair(down, up, displayId) {
                    channel = "shell"
                    exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
                }
            }
        } else {
            injectPair(down, up, displayId) {
                channel = "shell"
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i(TAG, "injectKey BACK display=$displayId uid=$uid channel=$channel ok=$ok")
        }
        return ok
    }

    /** Ctrl+A / Ctrl+V on its own virtual display, sending the modifier down and up as well; failing to release the modifier does not mark the already-sent text as unsent. */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
        if (!ownDisplay(displayId)) return AsciiTextKeys.NOT_SENT
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return AsciiTextKeys.NOT_SENT
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun event(code: Int, action: Int, modifiers: Int) = KeyEvent(
            now, SystemClock.uptimeMillis(), action, code, 0, modifiers,
            VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        var modifierDown = false
        try {
            if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId)) return AsciiTextKeys.NOT_SENT
            modifierDown = true
            if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId)) return AsciiTextKeys.NOT_SENT
            val up = event(keyCode, KeyEvent.ACTION_UP, meta)
            return if (inject(up, displayId) || inject(up, displayId)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
        } finally {
            if (modifierDown) {
                val up = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
                if (ownDisplay(displayId) && !inject(up, displayId) && !inject(up, displayId)) {
                    Log.w(TAG, "could not release Ctrl on display $displayId")
                }
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        if (displayId <= Display.DEFAULT_DISPLAY || !ownDisplay(displayId) || taskId < 0 || !AsciiTextKeys.supports(text)) {
            return AsciiTextKeys.NOT_SENT
        }
        val (events, targetUid) = runCatching {
            val task = tasksOn(displayId).firstOrNull() ?: return AsciiTextKeys.NOT_SENT
            val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
            if (TASK_INFO.getField("taskId").getInt(task) != taskId || top?.packageName != packageName) {
                return AsciiTextKeys.NOT_SENT
            }
            val uid = if (Build.VERSION.SDK_INT >= 33) {
                val user = TASK_INFO.getField("userId").getInt(task)
                val pm = context.packageManager
                (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                    .invoke(pm, packageName, 0, user) as Int).takeIf { it >= 0 } ?: return AsciiTextKeys.NOT_SENT
            } else null
            val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?.takeIf { it.isNotEmpty() } ?: return AsciiTextKeys.NOT_SENT
            keys to uid
        }.getOrElse { return AsciiTextKeys.NOT_SENT }

        for (event in events) {
            if (!ownDisplay(displayId) || (targetUid == null && topTaskId(displayId) != taskId)) {
                return AsciiTextKeys.UNCONFIRMED
            }
            event.source = InputDevice.SOURCE_KEYBOARD
            val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
                else inject(event, displayId, waitForFinish = true)
            // A failed dispatch can leave a prefix. Never replay it through another input route.
            if (!sent) return AsciiTextKeys.UNCONFIRMED
        }
        return AsciiTextKeys.DISPATCHED
    }

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * One finger stroke: down, move point by point according to [times], up at the last point; each event is injected
     * only when its moment arrives (the event time is that moment), so the app sees exactly the pace Claire planned.
     * Sent only to Claire's own display. Returns false when the down event was not injected (the caller takes another
     * route); if a later point fails to inject it still continues, and the up event is always sent at the end so the
     * finger is never left pressed.
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    /**
     * `appops set <pkg> PLAY_AUDIO deny`; the original mode is recorded on the first mute (and written to
     * the file), and restored when unmuting. Use deny rather than ignore: `ignore` only suppresses the sound, the app
     * still counts as "playing" and still holds audio focus, stealing the sound of the app playing in the foreground
     * (#483/#228); `deny` keeps its audio from starting at all, so it holds no focus. A mode that was never set is
     * restored to allow, not default (see [AudioMuteLedger.restoreMode]). Returns false when the command fails, and
     * then does not record the original mode (no change when the original value cannot be read). Acts on the user
     * Claire runs as (the original copy).
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = audioMuted(packageName, null, muted)

    /** Same as [setAudioMuted] but acting on the copy in user [userId] (clone, work profile); records are kept separately by [AppAudioKey]. */
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        if (userId == myUser) return audioMuted(packageName, null, muted)
        return audioMuted(packageName, otherUser(userId) ?: return false, muted)
    }

    /** At startup, restore packages whose restore did not finish last time (only those in the records). */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    /** "default" (never set) is written as allow, for the reason given in [AudioMuteLedger.restoreMode]. */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
    }

    /**
     * The package (the copy in user [userId]) has a visible task on the main display: the user is using it there.
     * Muting applies per package, so muting it now would also silence what the user is holding; Claire therefore does
     * not mute it. When the main display cannot be inspected, answer "in use": better not to mute than to mute what the
     * user is using.
     */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        try {
            inUseOnMainScreen(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            true
        }

    /**
     * Only touch the user's clipboard when the original can be read: if it cannot, "it was originally empty" and "it
     * has content but cannot be seen" are indistinguishable, and once overwritten it cannot be restored, so return
     * [CLIP_UNREADABLE] and leave the clipboard as it is.
     */
    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            // If the previous restore did not finish because the clipboard was unreadable, restore once more first; if it is still unreadable, do not overwrite content that may now belong to the user.
            if (clipSaved && !restoreClip()) return CLIP_UNREADABLE
            if (!clipSaved) {
                if (!canReadClipboard()) return CLIP_UNREADABLE
                val prior = clipboard.primaryClip
                // An empty read is not necessarily empty: the system may have silently blocked it, while presence and description still report content
                when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                    ShellClip.Read.Unreadable -> {
                        Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                        return CLIP_UNREADABLE
                    }
                    ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                    ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            suppressOverlay(clip)
            markSensitive(clip)
            clipboard.setPrimaryClip(clip)
            ourText = text
            // Android can silently decline the write. Never paste a clipboard we didn't set.
            if (nowState(text) == ShellClip.Now.Ours) CLIP_SET else CLIP_FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip(): Boolean = synchronized(clipLock) {
        if (!clipSaved) return true
        val finished = runCatching {
            val prior = savedClip
            val now = nowState(ourText)
            val action = ShellClip.restore(prior == null, now)
            Log.i(TAG, "restoreClip: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(prior!!))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
            // The write can also be silently refused by the system: read once more; if it is still Claire's or unreadable
            // right now, keep the record and try again next time. When the user has already copied something else, keep
            // the user's new content and the saved copy may be cleared.
            val after = nowState(ourText)
            after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }.getOrDefault(false)
        if (finished) {
            savedClip = null
            clipSaved = false
            ourText = null
        }
        finished
    }

    override fun savedClipText(): String? = synchronized(clipLock) {
        if (!clipSaved) return null
        runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
    }

    override fun restoreClipText(original: String, ours: String): Boolean = synchronized(clipLock) {
        runCatching {
            val now = nowState(ours)
            val action = ShellClip.restore(original.isEmpty(), now)
            Log.i(TAG, "restoreClipText: now=$now action=$action")
            when (action) {
                ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(ClipData.newPlainText("", original)))
                ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
                ShellClip.Restore.Leave -> Unit
            }
            val after = nowState(ours)
            after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
        }.onFailure { Log.w(TAG, "restoreClipText failed", it) }.getOrDefault(false)
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // The helper process does not end with Claire and the Binder may be obtained by others: only Claire may call interface methods
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION &&
            Binder.getCallingUid() != allowedUid
        ) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            returnedCallPipes.get()?.let { pipes ->
                returnedCallPipes.remove()
                pipes.forEach { runCatching { it.close() } }
            }
        }
    }

    override fun setDeviceFilePolicy(protocol: Int, session: String?, revision: Long, roots: Array<out String>?, recursive: BooleanArray?): Bundle = asCallAudioShell {
        synchronized(filePolicyLock) {
            fun reply(status: String) = Bundle().apply { putString("status", status); putInt("protocol", 1); putString("session", session); putLong("revision", revision) }
            if (protocol != 1 || session.isNullOrBlank() || session.length > 64 || revision < 0 || roots == null || recursive == null) return@synchronized reply("invalid")
            val previous = filePolicy?.takeIf { it.session == session }
            val paths = roots.toList()
            val flags = recursive.toList()
            if (previous != null && (revision < previous.revision || (revision == previous.revision && (paths != previous.roots || flags != previous.recursive)))) {
                return@synchronized reply("stale_policy")
            }
            if (previous != null && revision == previous.revision) return@synchronized reply("ok")
            val policy = try { DeviceFilePolicy.create(roots, recursive, myUser) } catch (_: Exception) { return@synchronized reply("invalid") }
            filePolicy = FilePolicyState(session, revision, paths, flags, policy)
            reply("ok")
        }
    }

    override fun listFiles(path: String?, afterName: String?, limit: Int): Bundle =
        deviceFileCall { ShellFiles.list(path, afterName, limit, myUser, it) }

    override fun searchFiles(path: String?, name: String?, extension: String?, modifiedAfter: Long, modifiedBefore: Long, maxDepth: Int, maxEntries: Int, timeoutMs: Int): Bundle =
        deviceFileCall { ShellFiles.search(path, name, extension, modifiedAfter, modifiedBefore, maxDepth, maxEntries, timeoutMs, myUser, it) }

    override fun openFile(path: String?): Bundle = deviceFileCall { ShellFiles.open(path, myUser, it) }

    override fun probeCallAudio(mode: Int): Bundle = asCallAudioShell { callAudio.probe(mode) }

    override fun openCallAudio(mode: Int, owner: IBinder): Bundle = asCallAudioShell {
        callAudio.open(mode, owner).also { result ->
            if (result.getBoolean("ready")) {
                val down = result.getParcelable<ParcelFileDescriptor>("downlink")
                val up = result.getParcelable<ParcelFileDescriptor>("uplink")
                returnedCallPipes.set(listOfNotNull(down, up))
            }
        }
    }

    override fun stopCallAudio(owner: IBinder) = asCallAudioShell { callAudio.stop(owner) }

    override fun flushCallAudio(owner: IBinder): Boolean = asCallAudioShell { callAudio.flush(owner) }

    override fun callAudioStats(owner: IBinder): Bundle = asCallAudioShell { callAudio.stats(owner) }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (!ownDisplay(displayId)) return commandResult(-1, "No task display is active.")
        return exec(command, displayId)
    }

    override fun runCommand(command: String): Bundle = exec(command, null)

    /** The helper process sees the wish Claire hands over (see [setRestoreWish]); after Claire dies it decides from it whether to bring her back up. */
    @Volatile var onRestoreWish: ((Int) -> Unit)? = null

    override fun setRestoreWish(flags: Int) {
        onRestoreWish?.invoke(flags)
    }

    internal val deviceUnlock by lazy { DeviceUnlockInput(context,
        inject = { inject(it, Display.DEFAULT_DISPLAY, waitForFinish = true) },
        injectTo = { event, uid -> injectTo(event, Display.DEFAULT_DISPLAY, uid) },
        command = { exec(*it.toTypedArray()) == 0 }) }

    override fun wakeDevice(): Boolean = deviceUnlock.wake()
    override fun dismissDeviceKeyguard(): Boolean = deviceUnlock.dismiss()
    override fun revealDeviceCredential(): Boolean = deviceUnlock.reveal()
    override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean = deviceUnlock.tap(x, y)
    override fun enterDeviceCredential(kind: Int, credential: ByteArray, clear: Int): Boolean = deviceUnlock.enter(kind, credential, clear)

    override fun openDevtoolsSocket(name: String): ParcelFileDescriptor? {
        val pid = DevtoolsSocket.pidOf(name) ?: return null
        // Only connect to debug sockets of Claire's own process: other apps' web pages do not go through here
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Claire's (uid $uid)")
            return null
        }
        val socket = LocalSocket()
        return try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            ParcelFileDescriptor.dup(socket.fileDescriptor)
        } catch (e: Exception) {
            Log.w(TAG, "devtools socket $name: ${e.message}")
            null
        } finally {
            // What is handed out is a duplicated fd, so close this copy
            runCatching { socket.close() }
        }
    }

    override fun releaseAll() {
        synchronized(filePolicyLock) { filePolicy = null }
        asCallAudioShell { callAudio.stopAll() }
        restoreClip()
        synchronized(muted) { muted.packages() }.forEach { key ->
            AppAudioKey.parse(key)?.let { (pkg, user) -> audioMuted(pkg, user, false) } ?: synchronized(muted) { muted.remove(key) }
        }
        awake.stopAll()
        val (all, watch) = synchronized(displays) {
            displays.values.toList().also { displays.clear() } to orientationWatch.also { orientationWatch = null }
        }
        watch?.close()
        all.forEach { it.release() }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    companion object {
        internal const val TAG = "MikaShell"
        internal const val MAX_TASKS = 100
        internal const val TAP_MS = 50L
        // Upper bounds for one injectStroke stroke: number of points, milliseconds from down to up
        internal const val STROKE_MAX_POINTS = 1_024
        internal const val STROKE_MAX_MS = 10_000L
        internal const val CMD_TIMEOUT_S = 10L
        internal const val COMMAND_OUTPUT_MAX = 64 * 1024
        internal const val READER_JOIN_MS = 1_000L
        internal val MODE_RE = Regex("[a-z_]+")
        internal const val PER_USER_RANGE = 100_000

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3
        /** The cold start was turned into a normal start: the app still has tasks outside Claire's displays. */
        const val START_OK_COLD_IGNORED = 4
        /** startView: no app can open this link. */
        const val START_UNRESOLVED = 5
        /** startView: the link may not be opened (see [ShellLinks]). */
        const val START_INVALID_LINK = 6
        /** This app is being used by another task on another Claire display. */
        const val START_IN_USE_BY_OTHER = 7
        /** startAppInUser: that user copy is not in the usable list (see [AppCopies]). */
        const val START_USER_NOT_ALLOWED = 8

        // Bundle of appCopyUsers
        const val KEY_COPY_USERS = "users"
        const val KEY_COPY_SUMMARY = "summary"
        // UserProperties.PROFILE_API_VISIBILITY_HIDDEN
        internal const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // Results of setClipText
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        internal const val CLIP_LABEL = "Claire"
        // This name is hidden in AppOpsManager
        internal const val OPSTR_READ_CLIPBOARD = "android:read_clipboard"
        internal const val READ_CLIPBOARD_IN_BACKGROUND = "android.permission.READ_CLIPBOARD_IN_BACKGROUND"

        internal val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }
        internal val WCT: Class<*> by lazy { Class.forName("android.window.WindowContainerTransaction") }
        internal const val INJECT_MODE_ASYNC = 0 // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        internal const val INJECT_MODE_WAIT_FOR_RESULT = 1 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
        internal const val INJECT_MODE_WAIT_FOR_FINISH = 2 // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        internal const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
        internal const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        // WindowConfiguration.ACTIVITY_TYPE_*: home, recents, assistant and so on are other types
        internal const val ACTIVITY_TYPE_UNDEFINED = 0
        internal const val ACTIVITY_TYPE_STANDARD = 1

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are hidden constants)
        internal const val FLAG_PUBLIC = 1 shl 0
        internal const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        internal const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        internal const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        internal const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        internal const val FLAG_TRUSTED = 1 shl 10
        internal const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        internal const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        internal const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        internal const val FLAG_OWN_FOCUS = 1 shl 14
        internal const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * Flags used to create the virtual display. When [trusted] is false the items that depend on a trusted display
         * are dropped: on some systems shell lacks ADD_TRUSTED_DISPLAY and including TRUSTED is rejected with a
         * SecurityException; OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED and OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED are likewise
         * trusted-only. The remaining ones do not depend on trust and are kept.
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // With OWN_FOCUS alone, launching an app on the virtual display or the app opening a new page makes it the
            // "topmost focused display" and the main display's focused window is cleared; STEAL_TOP_FOCUS_DISABLED
            // (requires both OWN_FOCUS and TRUSTED) keeps it from stealing the main display's focus
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /**
         * Injection mode. Keys wait until the system has decided whom to deliver to (WAIT_FOR_RESULT): when the
         * display has no window that can take keys, injection reports failure and only then does the caller fall back
         * to a shell command or report the action failed, instead of "sent counts as done"; it waits only for dispatch,
         * not for the app to finish, up to the system's own injection timeout. Touches still do not wait (ASYNC): a
         * stroke must be sent point by point on time, and waiting for dispatch would distort the pace. With
         * [waitForFinish] (main-display unlock input) it waits for the app to finish.
         */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }

        /**
         * A down/up pair: [fallback] (a shell command) is used only when the down event was not injected; falling back
         * after the down was delivered would cause one extra full tap/key press, so then only the up event is sent
         * (retried once on failure).
         */
        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        /**
         * Whether there is a target uid for targeted injection. The targeted overload
         * (`injectInputEvent(event, mode, targetUid)`) exists only since Android 13; earlier versions can only inject
         * untargeted; a missing package name or a uid resolving negative (not installed) also counts as untargetable.
         */
        internal fun resolveTargetUid(sdkInt: Int, packageName: String?, userId: Int, uidLookup: (String, Int) -> Int): Int? =
            if (sdkInt < 33 || packageName == null) null else uidLookup(packageName, userId).takeIf { it >= 0 }

        /** Only a missing permission (SecurityException, e.g. shell lacking ADD_TRUSTED_DISPLAY) is worth retrying without the trusted flags; other failures are reported as before. */
        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2.
        // Measured on a real device (Android 14) with LOCAL: after focusing an input field on the virtual display the IME
        // window appears on the virtual display, main-screen focus is unchanged, and HIDE is unnecessary.
        internal const val IME_POLICY = 0

        // SystemUI convention: clipboard data from shell carrying it shows no "copied" overlay (which would otherwise pop up on the user's main display)
        internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        internal const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /**
         * Whether a task (the packages its pages belong to [taskPackages], its user [taskUser], null when unreadable) is
         * the copy of [packageName] in user [userId]. A null [userId] does not distinguish copies; when the task's user
         * is unreadable it counts as a match (prefer treating it as occupied rather than risk disturbing the user).
         */
        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            packageName in taskPackages && (userId == null || taskUser == null || taskUser == userId)

        /** Parses `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: ignore; time=..." → "ignore"; not set → "default". */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
