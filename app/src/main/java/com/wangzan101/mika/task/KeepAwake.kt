package com.wangzan101.mika.task

import android.content.Context
import android.os.PowerManager
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface WakeLockPort {
    fun acquire()
    fun release()
}

/**
 * Keep the screen on while a task is running: hold a screen wake lock; no system settings are changed and brightness is not touched.
 * Once the screen goes off the system drops injected touches and then locks, so the screen must not turn off by itself while the Agent is working;
 * but the screen does not need to stay fully bright while the user is not interacting, and it still dims at the system timeout (see [ScreenWakeLock]).
 *
 * Held only while Running (including waiting for another task to finish with an app) and while queued behind other tasks waiting for a virtual display. The screen need not stay on for the Agent while waiting to take over / answer / continue / unlock,
 * so it is held for another [GRACE_MS] (the user is most likely about to deal with it, so don't let the screen go dark in front of them) and then released, and re-acquired when the task keeps running.
 * Finishing, failing and cancelling release it immediately. The lock follows the process: when Claire exits abnormally the system reclaims it, so no persisted recovery is needed.
 * The user can still turn the screen off with the power button (ACQUIRE_CAUSES_WAKEUP is not set); in that case the gate makes the task wait for unlock.
 */
class KeepAwake(private val lock: WakeLockPort, private val scope: CoroutineScope) {
    private var held = false
    private var pendingRelease: Job? = null
    private var closed = false

    @Synchronized
    fun onState(state: TaskState) {
        if (closed) return
        when (state) {
            is TaskState.Running -> hold()
            // Queued behind other tasks waiting for a virtual display: still working, nothing for you to handle, so keep holding;
            // if it were released after the grace period, the moment that task ends and drops its share the screen could go dark right then, and creating a display to start work would have to wait for unlock
            is TaskState.Held -> if (state.reason.queued) hold() else releaseLater()
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> release()
            else -> releaseLater()
        }
    }

    private fun hold() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (!held) {
            lock.acquire()
            held = true
        }
    }

    private fun releaseLater() {
        if (held && pendingRelease == null) {
            pendingRelease = scope.launch {
                delay(GRACE_MS)
                synchronized(this@KeepAwake) {
                    pendingRelease = null
                    release()
                }
            }
        }
    }

    @Synchronized
    fun release() {
        pendingRelease?.cancel()
        pendingRelease = null
        if (held) {
            lock.release()
            held = false
        }
    }

    /** Task finished: release and ignore any state that arrives afterwards (a late publish from a side coroutine must not re-acquire the lock). */
    @Synchronized
    fun close() {
        closed = true
        release()
    }

    companion object {
        const val GRACE_MS = 30_000L
    }
}

/**
 * Several tasks share one screen lock: each task takes a [lease]; the lock is acquired while the first lease is held and released when the last is dropped.
 * Each lease remembers whether it is held, so repeated acquires or releases are not double-counted.
 */
class SharedWakeLock(private val lock: WakeLockPort) {
    private var count = 0

    fun lease(): WakeLockPort = object : WakeLockPort {
        private var held = false

        override fun acquire() {
            synchronized(this@SharedWakeLock) {
                if (held) return
                held = true
                if (count++ == 0) lock.acquire()
            }
        }

        override fun release() {
            synchronized(this@SharedWakeLock) {
                if (!held) return
                held = false
                if (--count == 0) lock.release()
            }
        }
    }
}

/**
 * The screen lock uses SCREEN_DIM_WAKE_LOCK: the screen stays on, and when the user has not touched the phone for a while the system dims it as usual,
 * returning to the previous brightness as soon as the user touches it again. System brightness is neither changed nor increased. While dimmed the screen is still on and interactive,
 * so injected touches are still delivered; the Agent operates a separate virtual display, whose input is likewise unaffected by the main screen's brightness.
 * This kind of lock is marked deprecated (a window's FLAG_KEEP_SCREEN_ON is recommended), but Claire has no window of its own while running tasks in the background,
 * and this kind of lock held by a foreground service still works.
 */
class ScreenWakeLock(context: Context) : WakeLockPort {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(LEVEL, "Claire:task")
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    companion object {
        /** Keep the screen on but let the system dim it on its usual timeout. */
        @Suppress("DEPRECATION")
        const val LEVEL = PowerManager.SCREEN_DIM_WAKE_LOCK

        /** Fallback upper bound so a missed release on any path cannot hold forever; normally the task state releases it. */
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
