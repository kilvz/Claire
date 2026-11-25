package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.FLAG_OWN_DISPLAY_GROUP
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.displayFlags
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.IME_POLICY

// Assistant service: virtual display creation, keep-awake, orientation sync and windowing mode
internal fun ShellService.createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
    val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
    val vd = displayManager.createVirtualDisplay("mika-agent", width, height, dpi, surface, flags)
    val id = vd.display.displayId
    synchronized(displays) { displays[id] = vd; displayDpi[id] = dpi }
    setFullscreenWindowingMode(id)
    ensureOrientationWatch()
    setImePolicy(id)
    if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
    return id
}

internal fun ShellService.pokeDisplay(displayId: Int): Boolean {
    val (power, method) = userActivity ?: return false
    return try {
        method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "userActivity failed, display keep-awake off", t)
        false
    }
}

/** The display group this display belongs to (DisplayInfo.displayGroupId, a hidden field); null when it cannot be read. */
@SuppressLint("PrivateApi")
internal fun ShellService.displayGroup(displayId: Int): Int? = runCatching {
    val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
    val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
    info.javaClass.getField("displayGroupId").getInt(info)
}.getOrNull()

/** Launches only onto virtual displays this service created itself, so a wrong display ID from the caller (the main display, say) cannot disturb the user. */
internal fun ShellService.ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

/**
 * STEAL_TOP_FOCUS_DISABLED preserves main-screen focus, but some systems then stop honouring rotation requests from
 * background activities. While a Claire display exists, start an orientation watcher that corrects only the
 * orientation of this display and keeps the original focus isolation. The watcher is created outside the lock
 * (registration goes over binder); when two display creations race, only one is kept and the extra one is closed.
 */
internal fun ShellService.ensureOrientationWatch() {
    if (Build.VERSION.SDK_INT < 34 || synchronized(displays) { orientationWatch != null }) return
    val watch = runCatching {
        DisplayOrientationWatch(
            taskManager,
            active = { synchronized(displays) { displays.isNotEmpty() } },
            onRequestedOrientation = { taskId, orientation -> requestedOrientations[taskId] = orientation },
            onTaskRemoved = { taskId -> requestedOrientations.remove(taskId) },
            sync = ::syncDisplayOrientations,
        )
    }.onFailure { Log.w(TAG, "display orientation watcher unavailable", it) }.getOrNull() ?: return
    val spare = synchronized(displays) {
        if (orientationWatch == null && displays.isNotEmpty()) { orientationWatch = watch; null } else watch
    }
    spare?.close()
}

/**
 * Corrects each display's orientation to the app window proportions of its top task. Only a snapshot of the displays
 * is taken under the lock; task queries, field reads, freezeDisplayRotation and the `wm` fallback command all run
 * outside the lock, so they do not hold up display creation, release or launch.
 */
@SuppressLint("PrivateApi")
internal fun ShellService.syncDisplayOrientations() {
    val ours = synchronized(displays) { displays.toMap() }
    if (ours.isEmpty()) return
    val top = allTasks().groupBy { TASK_INFO.getField("displayId").getInt(it) }
    for ((id, vd) in ours) runCatching {
        val task = top[id]?.firstOrNull() ?: return@runCatching
        val taskId = TASK_INFO.getField("taskId").getInt(task)
        val compat = runCatching { TASK_INFO.getField("appCompatTaskInfo").get(task) }.getOrNull() ?: task
        val cls = compat.javaClass
        // Android 15 has an explicit letterbox flag as well; on 14 the width and height are only filled in some letterbox modes.
        if (runCatching { cls.getField("topActivityBoundsLetterboxed").getBoolean(compat) }.getOrDefault(true).not()) return@runCatching
        val width = runCatching { cls.getField("topActivityLetterboxWidth").getInt(compat) }.getOrDefault(0)
        val height = runCatching { cls.getField("topActivityLetterboxHeight").getInt(compat) }.getOrDefault(0)
        val display = vd.display
        val mode = display.mode
        val rotation = DisplayOrientation.rotation(
            mode.physicalWidth, mode.physicalHeight, display.rotation, width, height,
            requestedOrientations[taskId] ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        ) ?: return@runCatching
        // The display may already have been released after the snapshot
        if (!ownDisplay(id)) return@runCatching
        val ok = runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            val method = wm.javaClass.methods.first { it.name == "freezeDisplayRotation" }
            if (method.parameterTypes.size == 3) method.invoke(wm, id, rotation, "mika") else method.invoke(wm, id, rotation)
        }.isSuccess
        if (!ok) exec("wm", "user-rotation", "-d", "$id", "lock", "$rotation")
    }.onFailure { Log.w(TAG, "orientation sync failed on display $id", it) }
}

/**
 * Sets only the freshly created Claire display, never the main display. Some systems default a new external/virtual
 * display to freeform, which shrinks apps into small windows and also makes accessibility coordinates subject to
 * window scaling. Explicitly use fullscreen mode after creating the display and before launching any page; later app
 * launches, page transitions and tasks moved back from the main display all follow this display's policy.
 */
@SuppressLint("PrivateApi")
internal fun ShellService.setFullscreenWindowingMode(displayId: Int) {
    runCatching {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (hidden constant)
        wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
    }.onFailure {
        Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
        if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
            Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
        }
    }
}

/** IWindowManager.setDisplayImePolicy: the IME does not pop up on the user's main display. See IME_POLICY for which policy is chosen. */
@SuppressLint("PrivateApi")
internal fun ShellService.setImePolicy(displayId: Int) {
    runCatching {
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, IME_POLICY)
    }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
}
