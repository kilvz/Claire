package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The part of the built-in helper that watches the main screen: which tasks moved to the top of the main screen
 * during the short period before and after an Agent action ([MainScreenLog]).
 * When an app opens another app not from its own screen on Claire's display but from the background (a service,
 * a notification's intent, the system picker), the system places it on the main screen; Claire's side recognizes
 * this from the record and moves it back.
 *
 * It looks once whenever a task changes: it registers a task stack listener with the system (ITaskStackListener,
 * the shell has the manage-tasks permission). The listener is used only as a "something changed" signal and its
 * arguments are not parsed (method order differs between versions); on each callback the task list is read again.
 * When registration fails it falls back to looking every [POLL_MS] while the watch window is active. On query
 * ([arrivals]) it also looks right then, so the final state is not missed.
 * It does not read the task list while not watching.
 */
internal class MainScreenWatch(
    private val taskManager: () -> Any,
    /** The tasks on all displays right now, ordered by most recent use. */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("mika-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    /** Until when to watch (uptimeMillis). */
    @Volatile private var until = 0L
    /** true when the task stack listener is registered, false when registration failed, null when not tried yet. */
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** Watches for [windowMs] starting now; returns the task currently at the top of the main screen (-1 if none). */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * Tasks that surfaced to the top of the main screen after [since], in chronological order; each also carries
     * the display it is on right now (-1 when gone), and whether this record came from the listener or from
     * polling. For the fields see [toBundle].
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    /** The task stack changed: coalesce before looking, so a whole burst of changes is looked at once. */
    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    /**
     * The Binder the system calls back through following the ITaskStackListener interface. It only recognizes
     * that "a callback arrived" and does not read the arguments; these callbacks are all one-way, so no reply is
     * needed. The descriptor is filled in with the interface name, and the system wraps it accordingly.
     */
    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "MikaShell"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        /** Looked at again after the window has passed, still reading on the spot: Claire's side needs a moment to finish judging. */
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        /** Packs a group of records into parallel arrays (a Bundle has no list of objects); [now] gives the display a task is on right now. */
        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }
    }
}
