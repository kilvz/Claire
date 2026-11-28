package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.KeyCharacterMap
import android.view.Surface
import com.wangzan101.mika.core.device.shell.DevtoolsSocket
import com.wangzan101.mika.core.screen.uistate.AppCopies
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import com.wangzan101.mika.shell.IMikaShell

/**
 * Runs as shell (uid 2000) and owns the Agent's virtual display, inside the built-in helper process ([HelperMain]).
 * When the process ends, the virtual display and the apps on it are destroyed too, so it must stay resident rather than run one-shot commands.
 *
 * Hidden APIs are always called via reflection; when reflection fails, fall back to a shell command with the same effect.
 */
class ShellService(
    /** Accepts calls only from this uid (Mika). */
    internal val allowedUid: Int,
    /** Directory for the mute records; a location the shell identity can write. */
    stateDir: File = File("/data/local/tmp"),
) : IMikaShell.Stub() {
    internal val context: Context = ShellContext.create()
    internal val displays = HashMap<Int, VirtualDisplay>()
    internal var orientationWatch: DisplayOrientationWatch? = null
    internal val requestedOrientations = ConcurrentHashMap<Int, Int>()
    /** Muted package → PLAY_AUDIO mode before muting ("default" means it was unset), stored in [stateDir] so the next start resumes restoring after the process is killed. */
    internal val muted = AudioMuteLedger(File(stateDir, "mika_audio_mute_$allowedUid"))
    internal val callAudio = ShellCallAudio(context)
    // The Bundle copies the nested PFDs into reply but does not close the originals on the server side. Close them after
    // writing reply, or when the client closes the pipe the audio thread never sees EOF.
    internal val returnedCallPipes = ThreadLocal<List<ParcelFileDescriptor>>()
    internal data class FilePolicyState(val session: String, val revision: Long, val roots: List<String>, val recursive: List<Boolean>, val policy: DeviceFilePolicy)
    internal val filePolicyLock = Any()
    @Volatile internal var filePolicy: FilePolicyState? = null
    /** The user Mika runs as: the original copy of the app is installed here; copies in other users are separate (clones, work profile). */
    internal val myUser = allowedUid / PER_USER_RANGE

    // Do not use getSystemService: the DisplayManager obtained that way is bound to the system context, whose package name does not match the shell uid
    @SuppressLint("PrivateApi")
    internal val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // The clipboard must be accessed under the shell package name (the system only honors the "suppress the copied overlay" flag for clipboards from shell); reads bypass the Settings lookup inside ClipboardManager that does not work from the shell process
    internal val clipboard = ShellClipboard(context)
    internal val clipLock = Any()
    /** No other task launch may slip between the in-use check and the launch: when two tasks open the same app at once only one can succeed. */
    internal val launchLock = Any()
    /** The user's clipboard before setClipText (null means it really was empty; an unreadable clipboard is never swapped out); [clipSaved] false means there is nothing to restore. */
    internal var savedClip: ClipData? = null
    internal var clipSaved = false
    /** The last text Mika put in; used before restoring to tell whether the clipboard is still Mika's (if the user copied something else meanwhile, leave it alone). */
    internal var ourText: String? = null

    internal val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    /** The 12-parameter version of IActivityTaskManager.startActivityAsUser; looked up once and kept (Binder classes have hundreds of methods). When missing, every use throws and callers fall back to another route. */
    internal val startActivityAsUser: Method by lazy {
        taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // When shell lacks ADD_TRUSTED_DISPLAY (some systems do not grant it): drop the flags that only work on trusted displays and create it again.
            // After the fallback this display has no focus of its own; the self-check reads "own focus no" from its flags and warns that main-screen focus may be affected (Advice.FocusShared).
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

    /** A virtual display in its own group never sleeps; see [DisplayAwake]. */
    internal val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "mika-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager and its per-display user-activity method; null when unavailable (logged once; the display still works, it just has nothing to keep it awake). */
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
        // Only detach inside the lock; close the listener (binder) and release the display outside it
        val (vd, watch) = synchronized(displays) {
            displays.remove(displayId) to (if (displays.isEmpty()) orientationWatch.also { orientationWatch = null } else null)
        }
        watch?.close()
        if (watch != null) requestedOrientations.clear()
        vd?.release()
    }

    /**
     * First make sure the user is not using this app on the main screen: if it has a visible task there, do not launch (return [START_IN_USE_ON_MAIN]),
     * otherwise the system would move or interrupt the screen the user is looking at. Background tasks can be pulled onto the virtual display.
     * Then resolve the launch entry point (some activities in a package are trampolines: launching them directly jumps away at once and leaves only an empty task on the virtual display),
     * and call IActivityTaskManager.startActivityAsUser under the shell package name with ActivityOptions.setLaunchDisplayId.
     * The in-use check only looks at the original copy (in Mika's user): a copy the user is using on the main screen in another user does not block this one.
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
     * Only kill and relaunch (back to the initial page) when every task of this app is on Mika's display.
     * If it still has tasks on another display (the user's state), never kill it; do a normal launch instead and return [START_OK_COLD_IGNORED];
     * when the user is using it on the main screen, do not launch.
     */
    override fun restartApp(displayId: Int, packageName: String): Int = restart(displayId, packageName, myUser)

    /** Open in another user copy: only the copies [appCopyUsers] reports as usable (private space and other people's users are never touched); rules in [AppCopies]. */
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
     * Open a link or a settings page. The shell checks the link once more against [ShellLinks]; return [START_UNRESOLVED] when it cannot be resolved.
     * Do not launch when an app that can open it has a task on the main screen: a link with NEW_TASK may land in that task and appear in front of the user.
     * When only one app can open it, target that screen directly so the system does not pick another.
     */
    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int =
        launchView(displayId, action, uri, packageName)

    /**
     * Share text with [packageName]: ACTION_SEND text/plain with EXTRA_TEXT, handed only to this package. Returns [START_UNRESOLVED] when it has no screen that accepts text shares;
     * like [startApp], do not launch while the user is using it on the main screen. Target it directly when only one share screen exists, or let the system ask which one when there are several.
     */
    override fun startShare(displayId: Int, packageName: String, text: String): Int = launchShare(displayId, packageName, text)

    /**
     * Share a workspace file with [packageName]: ACTION_SEND + EXTRA_STREAM, with a content URI the other side may read, handed only to this package.
     * Returns [START_UNRESOLVED] when it has no screen that accepts this kind of share; do not launch while the user is using it on the main screen. Only the share screen is opened; whether to send is up to the model on that screen.
     */
    override fun startShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int =
        launchShareFile(displayId, packageName, uri, mime, text)

    /**
     * System actions (see [SystemIntents]): only those intents are allowed. Returns [START_UNRESOLVED] when no screen accepts it; like [startView],
     * do not launch when the handling app has a task on the main screen. Launch as shell first; when that fails (the handling screen needs a permission shell lacks, such as setting an alarm),
     * send the [asMika] prepared by Mika: launch as Mika (permissions checked for Mika), with display and background-launch checked for the sending side (shell), still landing on this display.
     */
    override fun startSystemAction(displayId: Int, intent: Intent, asMika: android.app.PendingIntent?): Int =
        launchSystemAction(displayId, intent, asMika)

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

    override fun findTaskForPackage(packageName: String, userId: Int, displayId: Int): IntArray? = try {
        val user = TASK_INFO.getField("userId")
        val display = TASK_INFO.getField("displayId")
        val appUser = userId.takeIf { it >= 0 } ?: myUser
        val current = allTasks().asSequence()
            .filter { user.getInt(it) == appUser && packageName in packagesOf(it) }
            .minByOrNull {
                when (display.getInt(it)) {
                    displayId -> 0
                    Display.DEFAULT_DISPLAY -> 1
                    else -> 2
                }
            }
        if (current == null) intArrayOf()
        else intArrayOf(TASK_INFO.getField("taskId").getInt(current), display.getInt(current))
    } catch (e: Exception) {
        Log.w(TAG, "findTaskForPackage failed", e)
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
     * Verified manually on a device (Android 14): tasks move both ways between the virtual display and the main screen. Every task Mika starts is a root task.
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
     * Tasks left behind on the main screen when a task ends: task id → key ([AppsAfterTask.keyOf]). Moved back ([resumeKept]) the next time Mika opens the same app.
     * In-memory only: after the helper restarts it no longer recognizes them, and opening goes through the launch entry point as usual (the system will most likely pull this background task over too).
     */
    internal val kept = HashMap<Int, String>()

    /**
     * End of task: decide each task's fate on this display via [AppsAfterTask], move the kept ones to the very back of the main screen, then release this display (it carries
     * FLAG_DESTROY_CONTENT_ON_REMOVAL, so whatever was not moved closes with it). Order: decide fates → move the kept ones → look again at the ones making sound → wait for them to go out of sight ([SettleBehind])
     * → move any still visible back to this display → release. Mika restores muting after this, so kept apps do not suddenly make sound while restoring.
     *
     * Moving to the back (all stock Android APIs): first hide the task with WindowContainerTransaction.setHidden,
     * then moveRootTaskToDisplay it to the main screen (the system always puts it on top; hidden means it is not shown, takes no focus, and does not pause the screen the user is on),
     * then reorder it to the back in the same transaction and unhide. If hiding or moving fails, close it with the display as usual.
     *
     * Moving and wrapping up happen under [launchLock]; waiting for the transition in between (at most [SettleBehind.TIMEOUT_MS]) does not hold the lock, so other tasks can still open apps.
     * Anything another task took onto its display during this time is no longer handled; kept tasks are recorded in [kept] only at wrap-up, and [resumeKept] will not take them while waiting.
     */
    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
        val sorting = synchronized(launchLock) {
            if (!ownDisplay(displayId)) return null
            sortAndMove(displayId, keep, close)
        }
        val settled = settleBehind(sorting.moved.mapTo(LinkedHashSet()) { it.task.id })
        return synchronized(launchLock) { closeOut(displayId, sorting, settled) }
    }

    /** First stage of [finishDisplay]: the kept ones ([moved]) are already on the main screen; [hidden] are those whose unhide failed after moving. */
    internal class Sorting(
        val moved: MutableList<AppsAfterTask.Fate>,
        val closed: MutableList<String>,
        val notes: MutableSet<String>,
        val hidden: Set<Int>,
    )

    /** OK: moved to the back of the main screen; HIDDEN: moved but the unhide failed, so it is still hidden; UNSUPPORTED: this system cannot move it. */
    internal enum class Behind { OK, HIDDEN, UNSUPPORTED }

    internal val ownPackages: Set<String> by lazy {
        runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toSet() }.getOrNull().orEmpty()
    }

    /** Record of main-screen tasks: starts watching before an Agent action, see [MainScreenWatch]. Created on first use (thread, task-stack listener). */
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

    // Key, text and touch injection live in ShellInputOps.kt
    override fun injectKey(displayId: Int, keyCode: Int): Boolean = sendKey(displayId, keyCode)

    /** Ctrl+A / Ctrl+V on our own virtual display, sending modifier down and up together; a failed modifier release does not mark already-sent body text as unsent. */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = sendCtrlKey(displayId, keyCode)

    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int = sendAsciiText(displayId, taskId, packageName, text)

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = sendTap(displayId, x, y)

    /**
     * One finger stroke: down, move point by point per [times], up at the last point; each event is injected when its moment arrives (the event time is that moment),
     * so the app sees exactly the speed Mika planned. Sent only to Mika's own display. Returns false when the down event fails to inject (the caller takes another route);
     * after the down event, a failed point is skipped and the stroke continues, and the up event is always sent so the finger is never left pressed.
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean = sendStroke(displayId, xs, ys, times)

    override fun listUsers(): String? = sh("pm", "list", "users").takeIf { it.first == 0 }?.second

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * `appops set <pkg> PLAY_AUDIO deny`; on first mute record the original mode (and write it to disk), and restore it when unmuting.
     * Use deny rather than ignore: `ignore` only lowers the volume, and the app stays "playing" and keeps audio focus, stealing sound from the app playing in the foreground (#483/#228);
     * `deny` stops its audio from starting, so it takes no focus either. Restore unset modes to allow, not default (see [AudioMuteLedger.restoreMode]).
     * Returns false when the command fails and records no original mode (if the original value cannot be read, do not change it). Applies to the user Mika runs as (the original copy).
     */
    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = audioMuted(packageName, null, muted)

    /** Like [setAudioMuted], but for the copy in the [userId] user (clones, work profile); records are kept separately per [AppAudioKey]. */
    override fun setAudioMutedInUser(packageName: String, userId: Int, muted: Boolean): Boolean {
        if (userId == myUser) return audioMuted(packageName, null, muted)
        return audioMuted(packageName, otherUser(userId) ?: return false, muted)
    }

    /** Restore packages that could not be restored last time on startup (only those in the records). */
    fun recoverMuted() {
        synchronized(muted) { muted.restoreAll(::writeRecorded) }
    }

    override fun getAudioMode(packageName: String): String? = audioMode(packageName, null)

    override fun getAudioModeInUser(packageName: String, userId: Int): String? {
        if (userId == myUser) return audioMode(packageName, null)
        return audioMode(packageName, otherUser(userId) ?: return null)
    }

    /** "default" (never set) is written as allow, for the same reason as [AudioMuteLedger.restoreMode]. */
    override fun setAudioMode(packageName: String, mode: String): Boolean = audioMode(packageName, null, mode)

    override fun setAudioModeInUser(packageName: String, userId: Int, mode: String): Boolean {
        if (userId == myUser) return audioMode(packageName, null, mode)
        return audioMode(packageName, otherUser(userId) ?: return false, mode)
    }

    /**
     * The package (in the [userId] user) has a visible task on the main screen: the user is using it there. Muting applies per package, so muting it would also silence the copy in the user's hands;
     * Mika uses this to avoid muting it. When the main screen cannot be inspected, answer "in use"; better not to mute than to mute what the user is using.
     */
    override fun visibleOnMainScreen(packageName: String, userId: Int): Boolean =
        try {
            inUseOnMainScreen(packageName, userId)
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            true
        }

    /**
     * Only touch the user's clipboard when the original can be read: otherwise "was empty" and "has content but unreadable" cannot be told apart,
     * and once overwritten it cannot be restored; in that case return [CLIP_UNREADABLE] and leave the clipboard as it is.
     */
    override fun setClipText(text: String): Int = putClipText(text)

    override fun restoreClip(): Boolean = restoreSavedClip()

    override fun savedClipText(): String? = savedClipTextNow()

    override fun restoreClipText(original: String, ours: String): Boolean = restoreClipTextTo(original, ours)

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // The built-in helper process does not end with Mika, and the Binder may be obtained by others: only Mika may call interface methods
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

    /** The helper process watches the restore wish handed over by Mika (see [setRestoreWish]); after Mika dies it decides from this whether to bring it back up. */
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
        // Connect only to the debug socket of Mika's own process: other apps' web pages do not go through here
        val uid = runCatching { DevtoolsSocket.procUid(File("/proc/$pid/status").readText()) }.getOrNull()
        if (uid != allowedUid) {
            Log.w(TAG, "devtools socket refused: pid $pid is not Mika's (uid $uid)")
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
            // What is handed out is a duplicated fd; close this copy
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
        // Upper bounds for one injectStroke stroke: point count, milliseconds from down to up
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
        /** Cold launch changed into a normal launch: the app still has tasks outside Mika's display. */
        const val START_OK_COLD_IGNORED = 4
        /** startView: no app can open this link. */
        const val START_UNRESOLVED = 5
        /** startView: the link is not allowed to open (see [ShellLinks]). */
        const val START_INVALID_LINK = 6
        /** This app is in use by another task on another of Mika's displays. */
        const val START_IN_USE_BY_OTHER = 7
        /** startAppInUser: that user is not among the usable copies (see [AppCopies]). */
        const val START_USER_NOT_ALLOWED = 8

        // Bundle returned by appCopyUsers
        const val KEY_COPY_USERS = "users"
        const val KEY_COPY_SUMMARY = "summary"
        // UserProperties.PROFILE_API_VISIBILITY_HIDDEN
        internal const val PROFILE_API_VISIBILITY_HIDDEN = 1

        // setClipText results
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        internal const val CLIP_LABEL = "Mika"
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
        // WindowConfiguration.ACTIVITY_TYPE_*: launcher, recents, assistant, etc. are other types
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
         * Flags for creating the virtual display. When [trusted] is false, drop the flags that depend on a trusted display: on some systems shell lacks
         * ADD_TRUSTED_DISPLAY, and passing TRUSTED is rejected by the system with SecurityException; OWN_DISPLAY_GROUP, ALWAYS_UNLOCKED
         * and OWN_FOCUS/STEAL_TOP_FOCUS_DISABLED are likewise only available on trusted displays. The rest do not depend on trust and stay as they are.
         */
        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // With OWN_FOCUS alone, launching an app on the virtual display or the app opening a new screen makes it the "top focused display"
            // and clears the main screen's focused window; STEAL_TOP_FOCUS_DISABLED (requires both OWN_FOCUS and TRUSTED) keeps it from stealing main-screen focus
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        /**
         * Injection mode. Keys wait until the system decides where to deliver (WAIT_FOR_RESULT): when no window on the display can accept keys the injection reports failure,
         * and only then does the caller fall back to a shell command or report action failure, instead of "sent means succeeded"; it waits only for dispatch, not for the app to finish,
         * up to the system's own injection timeout. Touch still does not wait (ASYNC): a stroke must be sent point by point on schedule, and waiting for dispatch would distort the pacing. [waitForFinish] ones (main-screen unlock input) wait for the app to finish.
         */
        internal fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
            waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
            key -> INJECT_MODE_WAIT_FOR_RESULT
            else -> INJECT_MODE_ASYNC
        }

        /**
         * A down/up pair: fall back to [fallback] (shell command) only when the down event itself fails to inject; falling back after the down was delivered would
         * cause one extra full tap/key press, so in that case only resend the up (retried once on failure).
         */
        internal inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
            if (!down()) return fallback()
            return up() || up()
        }

        /**
         * Whether there is a target uid for targeted injection. Targeted injection only exists since Android 13 (`injectInputEvent(event, mode, targetUid)`),
         * earlier versions can only use untargeted injection; a missing package name or a negative uid (not installed) also counts as untargetable.
         */
        internal fun resolveTargetUid(sdkInt: Int, packageName: String?, userId: Int, uidLookup: (String, Int) -> Int): Int? =
            if (sdkInt < 33 || packageName == null) null else uidLookup(packageName, userId).takeIf { it >= 0 }

        /** Only a missing permission (SecurityException, e.g. shell lacks ADD_TRUSTED_DISPLAY) is worth dropping the trusted flags and retrying; other failures are reported as usual. */
        internal fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

        // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0, _FALLBACK_DISPLAY = 1, _HIDE = 2。
        // Measured on a device (Android 14) with LOCAL: after focusing an input field on the virtual display, the IME window appears on the virtual display and main-screen focus is unchanged, so HIDE is not needed.
        internal const val IME_POLICY = 0

        // SystemUI convention: a clipboard from shell carrying this does not show the "copied" overlay (which would otherwise pop up on the user's main screen)
        internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE" // ClipDescription.EXTRA_IS_SENSITIVE(API 33)
        internal const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        /**
         * Whether a task (its screen's package [taskPackages], its user [taskUser], null when unreadable) is the copy of [packageName] in the [userId] user.
         * When [userId] is null, any copy counts; when the task's user cannot be read it counts (better to treat it as in use than risk disturbing the user).
         */
        internal fun sameCopy(taskPackages: Collection<String>, taskUser: Int?, packageName: String, userId: Int?): Boolean =
            packageName in taskPackages && (userId == null || taskUser == null || taskUser == userId)

        /** Parses `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: ignore; time=..." → "ignore"; unset → "default". */
        internal fun parsePlayAudioMode(output: String): String = AudioMuteLedger.parseSetMode(output) ?: "default"
    }
}
