package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ComponentName
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.wangzan101.mika.cap.phone.shell.ShellService.Behind
import com.wangzan101.mika.cap.phone.shell.ShellService.Sorting
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.MAX_TASKS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PER_USER_RANGE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.WCT
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.ACTIVITY_TYPE_UNDEFINED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.ACTIVITY_TYPE_STANDARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.resolveTargetUid
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sameCopy

// Assistant service: task stack queries, moving between displays, keeping in the background, and closing out
/** The package has a visible task on the main screen, or is itself the top task on the main screen. When [userId] is not null, only tasks in that user are considered (see [sameCopy]). */
internal fun ShellService.inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean {
    val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
    return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
        isCopy(task, packageName, userId) && (visibleField?.getBoolean(task) ?: (index == 0))
    }
}

/**
 * IActivityTaskManager.getTasks: sorted by recency of use; the shell holds REAL_GET_TASKS, so it can see the tasks of every app.
 * API 33+ has an overload that takes a displayId, which is preferred; earlier versions fetch everything and then filter by TaskInfo.displayId.
 */
internal fun ShellService.tasksOn(displayId: Int): List<Any> {
    val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
    val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
    val tasks = if (withDisplay != null) {
        withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
    } else {
        methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
    } as List<*>
    val displayField = TASK_INFO.getField("displayId")
    return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
}

/** Tasks on all displays: passing INVALID_DISPLAY to the overload that takes a display id means no filtering by display. */
internal fun ShellService.allTasks(): List<Any> {
    val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
    val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
    val tasks = if (withDisplay != null) {
        withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
    } else {
        methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
    } as List<*>
    return tasks.filterNotNull()
}

internal fun ShellService.task(taskId: Int): Any? {
    val idField = TASK_INFO.getField("taskId")
    return allTasks().firstOrNull { idField.getInt(it) == taskId }
}

internal fun ShellService.sortAndMove(displayId: Int, keep: Array<String>?, close: Array<String>?): Sorting {
    val closed = ArrayList<String>()
    val notes = LinkedHashSet<String>()
    // Tasks already moved behind the main screen; they are recorded even if an error occurs midway, so the next launch can pick up where this left off
    val moved = ArrayList<AppsAfterTask.Fate>()
    val hidden = HashSet<Int>()
    try {
        val playing = playingUids()
        if (playing == null) notes += AppsAfterTask.NOTE_PLAYBACK_UNKNOWN
        val rows = tasksOn(displayId)
        val idField = TASK_INFO.getField("taskId")
        val byId = rows.associateBy { idField.getInt(it) }
        val fates = AppsAfterTask.decide(rows.map { afterTaskRow(it, playing) },
            AppsAfterTask.normalize(keep, myUser), AppsAfterTask.normalize(close, myUser))
        var supported = true
        for (f in fates) {
            if (!f.keep) { closed += AppsAfterTask.closedEntry(f); continue }
            if (!supported) { closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported)); continue }
            when (keepBehind(byId.getValue(f.task.id))) {
                Behind.OK -> moved += f
                Behind.HIDDEN -> { moved += f; hidden += f.task.id }
                Behind.UNSUPPORTED -> {
                    supported = false
                    notes += AppsAfterTask.NOTE_MOVE_UNSUPPORTED
                    closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.MoveUnsupported))
                }
            }
        }
        // Tasks that started playing audio after being moved (not marked keep): move them back to this display and close them with it; if they cannot be moved back, close them directly
        val playingNow = if (moved.isEmpty()) null else playingUids()
        val nowLoud = moved.map { f -> AppsAfterTask.recheck(f, task(f.task.id)?.let { playingIn(it, playingNow) }) }.filter { !it.keep }
        for (f in nowLoud) {
            sendBackToClose(f.task.id, displayId)
            moved.removeAll { it.task.id == f.task.id }
            closed += AppsAfterTask.closedEntry(f)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "finishDisplay: cannot inspect tasks, closing the rest with the display", t)
        notes += "error ${t.javaClass.simpleName}"
    }
    return Sorting(moved, closed, notes, hidden)
}

/**
 * The last stage of [finishDisplay], inside the lock: decide again according to [SettleBehind.closeOut]. Tasks still visible when the timeout expires are moved back to this display and closed with it
 * (the same path as tasks that started playing audio after being moved; they are not left in front of the user) and recorded as not kept ([AppsAfterTask.Why.StillInFront]); tasks no longer on the main screen are not recorded;
 * the ones that are kept are recorded and the display is released. If it could not wait (the task list cannot be read, [settled] is null), they are kept anyway and the result states that they were not verified.
 */
internal fun ShellService.closeOut(displayId: Int, sorting: Sorting, settled: SettleBehind.Plan.Done?): Bundle {
    val (moved, closed, notes) = Triple(sorting.moved, sorting.closed, sorting.notes)
    if (settled == null) notes += AppsAfterTask.NOTE_SETTLE_UNKNOWN
    else try {
        // Do not hold the lock while waiting: look again after returning to the lock; a task that another task took over in the meantime, or that has already settled, is left untouched
        val ids = moved.mapTo(LinkedHashSet()) { it.task.id }
        val final = SettleBehind.closeOut(ids, settled.fallback, seenOnMain(ids))
        for (f in moved.filter { it.task.id in final.fallback }) {
            Log.w(TAG, "task ${f.task.id} is still in front on the main screen, closing it with display $displayId")
            sendBackToClose(f.task.id, displayId)
            closed += AppsAfterTask.closedEntry(f.copy(keep = false, why = AppsAfterTask.Why.StillInFront))
        }
        if (final.fallback.isNotEmpty()) notes += AppsAfterTask.NOTE_STILL_IN_FRONT
        if (final.gone.isNotEmpty()) Log.i(TAG, "tasks ${final.gone} left the main screen while settling; not keeping them")
        moved.removeAll { it.task.id !in final.kept }
    } catch (t: Throwable) {
        Log.e(TAG, "finishDisplay: cannot recheck the main screen", t)
        notes += "error ${t.javaClass.simpleName}"
    }
    // Failed unhides: the task stays on the main screen but is invisible in recents; the next time the same app is opened, resumeKept moves it back and completes the unhide
    val hidden = moved.count { it.task.id in sorting.hidden }
    if (hidden > 0) notes += "$hidden ${AppsAfterTask.NOTE_LEFT_HIDDEN}"
    synchronized(kept) { moved.forEach { kept[it.task.id] = it.key } }
    releaseDisplay(displayId)
    return Bundle().apply {
        putStringArray(AppsAfterTask.KEY_KEPT, moved.map { it.key }.toTypedArray())
        putStringArray(AppsAfterTask.KEY_CLOSED, closed.toTypedArray())
        putString(AppsAfterTask.KEY_NOTE, notes.joinToString("; ").ifEmpty { null })
    }
}

/** Move tasks that are not kept back to Claire's display [displayId] and close them with it; if they cannot be moved back, close them directly. */
internal fun ShellService.sendBackToClose(taskId: Int, displayId: Int) {
    if (!moveRoot(taskId, displayId)) removeTask(taskId)
}

/** One task row for [AppsAfterTask.decide]: its key, whether it is a standard app page, whether it belongs to Claire itself, and whether it is playing audio right now. */
internal fun ShellService.afterTaskRow(t: Any, playing: Map<Int, Set<String>>?): AppsAfterTask.Task {
    val user = runCatching { TASK_INFO.getField("userId").getInt(t) }.getOrDefault(myUser)
    val pkgs = packagesOf(t).distinct()
    val type = runCatching { TASK_INFO.getField("topActivityType").getInt(t) }.getOrNull()
    return AppsAfterTask.Task(
        id = TASK_INFO.getField("taskId").getInt(t),
        keys = pkgs.map { AppsAfterTask.keyOf(it, user, myUser) },
        standard = type == null || type == ACTIVITY_TYPE_UNDEFINED || type == ACTIVITY_TYPE_STANDARD,
        own = pkgs.any { it in ownPackages },
        playing = playingIn(t, playing),
        muted = synchronized(muted) { pkgs.any { muted.contains(AppsAfterTask.keyOf(it, user, myUser)) } },
    )
}

/** Whether the app of this task (in the same user) is playing audio right now; null if it cannot be read. */
internal fun ShellService.playingIn(task: Any, playing: Map<Int, Set<String>>?): Boolean? {
    playing ?: return null
    val user = runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull()
    val pkgs = packagesOf(task)
    return playing.any { (uid, owners) -> (user == null || uid / PER_USER_RANGE == user) && pkgs.any { it in owners } }
}

/**
 * uids playing right now → packages of that uid. First ask the audio service (AudioManager.getActivePlaybackConfigurations,
 * which only reports uids when MODIFY_AUDIO_ROUTING is held); when the returned uids are redacted, read `dumpsys audio` instead. Only entries whose player state is started count
 * (apps muted by Claire still count as playing: muting only silences them, and they sound again once restored). Null if neither can be read.
 */
internal fun ShellService.playingUids(): Map<Int, Set<String>>? {
    val uids = runCatching {
        val configs = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations
        val uidOf = AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
        val stateOf = AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
        val rows = configs.map { (uidOf.invoke(it) as Int) to (stateOf.invoke(it) as Int) }
        if (rows.any { it.first < 0 }) null
        else rows.filter { it.second == AppsAfterTask.PLAYER_STATE_STARTED }.map { it.first }.toSet()
    }.onFailure { Log.w(TAG, "playback configurations unavailable", it) }.getOrNull()
        ?: sh("dumpsys", "audio").takeIf { it.first == 0 }?.second?.let(AppsAfterTask::playingUids)
        ?: return null
    return uids.associateWith { uid ->
        runCatching { context.packageManager.getPackagesForUid(uid)?.toSet() }.getOrNull().orEmpty()
    }
}

/**
 * Move task [row] from Claire's display to the back of the main screen. Once the move succeeds, the task is not deleted even if reorder/unhide fails: tasks still visible are handed to [settleBehind] to wait for the transition
 * and then ordered to the back again; tasks that stay hidden return [Behind.HIDDEN], which is stated in the result, and the unhide is completed the next time [resumeKept] moves them back.
 */
internal fun ShellService.keepBehind(row: Any): Behind {
    val id = TASK_INFO.getField("taskId").getInt(row)
    val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: return Behind.UNSUPPORTED
    if (!transact { wct -> wctCall(wct, "setHidden", token, true) }) return Behind.UNSUPPORTED
    if (!moveRoot(id, Display.DEFAULT_DISPLAY)) {
        transact { wct -> wctCall(wct, "setHidden", token, false) }
        return Behind.UNSUPPORTED
    }
    // Reorder while hidden and then unhide; otherwise the system does not move it. Both steps go in the same transaction.
    val placed = transact { wct ->
        wctCall(wct, "reorder", token, false)
        wctCall(wct, "setHidden", token, false)
    }
    if (placed) return Behind.OK
    // Do not leave it hidden; if it ends up on top after the unhide, hand it to settleBehind to order it back again.
    if (transact { wct -> wctCall(wct, "setHidden", token, false) }) return Behind.OK
    Log.w(TAG, "task $id is still hidden on the main screen; resumeKept will unhide it")
    return Behind.HIDDEN
}

/** Tasks among [ids] that are currently on the main screen, and whether they are visible; when isVisible cannot be read, fall back to "is it the top task on the main screen" (tasks there are sorted by recency of use, so this is only a fallback). */
internal fun ShellService.seenOnMain(ids: Set<Int>): List<SettleBehind.Seen> {
    if (ids.isEmpty()) return emptyList()
    val idField = TASK_INFO.getField("taskId")
    val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
    return tasksOn(Display.DEFAULT_DISPLAY).withIndex().mapNotNull { (index, row) ->
        val id = idField.getInt(row)
        if (id !in ids) null else SettleBehind.Seen(id, onMain = true, visible = visibleField?.getBoolean(row) ?: (index == 0))
    }
}

/**
 * Wait for the tasks [moved] to the main screen to settle where the user cannot see them; the rules are in [SettleBehind]. Not called inside [launchLock]: it waits up to [SettleBehind.TIMEOUT_MS].
 * Returns null when the task list cannot be read (the caller keeps them anyway and states in the result that they were not verified).
 */
internal fun ShellService.settleBehind(moved: Set<Int>): SettleBehind.Plan.Done? = try {
    val start = SystemClock.uptimeMillis()
    var lastReorder: Long? = null
    var watching = moved
    val gone = LinkedHashSet<Int>()
    var done: SettleBehind.Plan.Done? = null
    while (done == null) {
        val now = SystemClock.uptimeMillis()
        when (val plan = SettleBehind.plan(watching, seenOnMain(watching), now - start, lastReorder?.let { now - it })) {
            is SettleBehind.Plan.Done -> done = plan.copy(gone = plan.gone + gone)
            is SettleBehind.Plan.Wait -> {
                gone += plan.gone
                watching = watching - plan.gone
                if (plan.reorder.isNotEmpty()) {
                    lastReorder = now
                    sendToBack(plan.reorder)
                }
                Thread.sleep(SettleBehind.POLL_MS)
            }
        }
    }
    if (done.fallback.isNotEmpty()) Log.w(TAG, "moved tasks ${done.fallback} are still visible on the main screen after ${SettleBehind.TIMEOUT_MS}ms")
    done
} catch (t: Throwable) {
    Log.e(TAG, "cannot watch moved tasks settle on the main screen", t)
    null
}

/** Order these tasks on the main screen to the back again (tasks whose transition has not finished are not treated as failures; they are checked again in the next round). */
internal fun ShellService.sendToBack(ids: Set<Int>) {
    val idField = TASK_INFO.getField("taskId")
    for (row in tasksOn(Display.DEFAULT_DISPLAY)) {
        val id = idField.getInt(row)
        if (id !in ids) continue
        Log.w(TAG, "moved task $id is still visible on the main screen, sending it back")
        val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull() ?: continue
        transact { wct -> wctCall(wct, "reorder", token, false) }
    }
}

/** IActivityTaskManager.moveRootTaskToDisplay; falls back to `am display move-stack` (the same call) when the interface is unavailable. */
internal fun ShellService.moveRoot(taskId: Int, displayId: Int): Boolean = moveTask(taskId, displayId)

/** IActivityTaskManager.removeTask (closes the task, the same as swiping it away in recents); falls back to `am stack remove` if that fails. */
internal fun ShellService.removeTask(taskId: Int): Boolean = try {
    taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "removeTask unavailable, falling back to am", t)
    exec("am", "stack", "remove", "$taskId") == 0
}

/** Build a WindowContainerTransaction and hand it to the system through IWindowOrganizerController.applyTransaction; returns false if any step fails. */
@SuppressLint("PrivateApi")
internal fun ShellService.transact(build: (Any) -> Unit): Boolean = try {
    val wct = WCT.getConstructor().newInstance()
    build(wct)
    val controller = taskManager.javaClass.getMethod("getWindowOrganizerController").invoke(taskManager)!!
    controller.javaClass.methods.first { it.name == "applyTransaction" && it.parameterTypes.size == 1 }.invoke(controller, wct)
    true
} catch (t: Throwable) {
    Log.w(TAG, "window container transaction failed", t)
    false
}

@SuppressLint("PrivateApi")
internal fun ShellService.wctCall(wct: Any, method: String, token: Any, flag: Boolean) {
    WCT.getMethod(method, Class.forName("android.window.WindowContainerToken"), Boolean::class.javaPrimitiveType).invoke(wct, token, flag)
}

/** Tasks of this app on the main screen (not in front of the user) that were kept by the previous task. */
internal fun ShellService.keptOnMain(packageName: String, userId: Int): List<Any> {
    val ids = synchronized(kept) { kept.keys.toSet() }
    if (ids.isEmpty()) return emptyList()
    val idField = TASK_INFO.getField("taskId")
    val displayField = TASK_INFO.getField("displayId")
    val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
    val all = allTasks()
    // Stop remembering tasks that no longer exist
    synchronized(kept) { kept.keys.retainAll(all.map { idField.getInt(it) }.toSet()) }
    return all.filter { t ->
        idField.getInt(t) in ids && displayField.getInt(t) == Display.DEFAULT_DISPLAY && isCopy(t, packageName, userId) &&
            visibleField?.getBoolean(t) != true
    }
}

/** Move the task kept behind the main screen back to Claire's display; returns false if there is none or it cannot be moved (the caller launches as usual). */
internal fun ShellService.resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
    val row = runCatching { keptOnMain(packageName, userId).firstOrNull() }.getOrNull() ?: return false
    val id = TASK_INFO.getField("taskId").getInt(row)
    if (!moveRoot(id, displayId)) return false
    // If there was no time to unhide during close-out, do it once more after moving it back.
    val token = runCatching { TASK_INFO.getField("token").get(row) }.getOrNull()
    if (token != null && !transact { wct -> wctCall(wct, "setHidden", token, false) }) {
        Log.w(TAG, "task $id is still hidden after moving it back to display $displayId")
    }
    synchronized(kept) { kept.remove(id) }
    return true
}

/** Before a cold start, close the copy left behind on the main screen by the previous task (left there by Claire itself, not by the user). */
internal fun ShellService.dropKept(packageName: String, userId: Int) {
    keptOnMain(packageName, userId).forEach { t ->
        val id = TASK_INFO.getField("taskId").getInt(t)
        if (removeTask(id)) synchronized(kept) { kept.remove(id) }
    }
}

/** Tasks on all displays, converted to [MainScreenLog.Task]; when the page type cannot be read, treat it as a standard app page. */
internal fun ShellService.taskRows(): List<MainScreenLog.Task> {
    val id = TASK_INFO.getField("taskId")
    val display = TASK_INFO.getField("displayId")
    val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
    val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
    return allTasks().map { t ->
        val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
        MainScreenLog.Task(
            taskId = id.getInt(t),
            displayId = display.getInt(t),
            packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
            userId = user?.getInt(t) ?: -1,
            topActivity = top?.className,
            standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
        )
    }
}

/** uid of the top task on the virtual display (the target of directed injection); returns null if it cannot be resolved (falls back to undirected injection). */
internal fun ShellService.topTaskUid(displayId: Int): Int? = try {
    val task = tasksOn(displayId).firstOrNull() ?: return null
    val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
    val packageName = top?.packageName ?: return null
    val userId = TASK_INFO.getField("userId").getInt(task)
    resolveTargetUid(Build.VERSION.SDK_INT, packageName, userId) { pkg, user ->
        val pm = context.packageManager
        pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
            .invoke(pm, pkg, 0, user) as Int
    }
} catch (t: Throwable) {
    Log.w(TAG, "resolve top task uid on display $displayId failed", t)
    null
}
