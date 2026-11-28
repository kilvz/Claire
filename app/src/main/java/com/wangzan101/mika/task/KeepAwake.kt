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
 * Held only while Running (including waiting for another task to finish using an app) and while queued behind another task for a virtual screen. While waiting to take over/answer/continue/unlock, the screen does not need to stay on for the Agent,
 * it is released after holding for [GRACE_MS] longer (the user is probably about to act, so do not let the screen go dark in front of them), and held again when the task continues running.
 * Released immediately on completion, failure or cancellation. The lock follows the process: if Claire crashes the system reclaims it automatically, so no persisted recovery is needed.
 * The user can still turn the screen off with the power button (ACQUIRE_CAUSES_WAKEUP is not set); the gate then makes the task wait for unlock.
 *
 * The task's CPU lock ([CpuWakeLock]) follows the same rules, except keepThroughEnd: it is held from start through task teardown (result delivered, ledger recorded),
 * and is also released after the grace period while waiting for you, so a task waiting for an answer does not hold the CPU all night.
 */
class KeepAwake(
    private val lock: WakeLockPort,
    private val scope: CoroutineScope,
    /** Keep holding in a terminal state (and Idle before start) until [close]: the CPU lock must last until the result is delivered and the scheduled ledger is recorded; the screen lock does not. */
    private val keepThroughEnd: Boolean = false,
) {
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
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> if (keepThroughEnd) hold() else release()
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

/**
 * CPU wake lock (PARTIAL_WAKE_LOCK): keeps the task's networking and inference running with the screen off and in Doze, without turning the screen on.
 * Like the screen lock, [SharedWakeLock] gives each task its own share, acquired and released by task state (see keepThroughEnd on [KeepAwake]);
 * [MAX_HOLD_MS] is only a fallback for a missed release.
 */
class CpuWakeLock(context: Context, tag: String) : WakeLockPort {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }

    override fun acquire() = lock.acquire(MAX_HOLD_MS)
    override fun release() { if (lock.isHeld) lock.release() }

    companion object {
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * CPU wake held between scheduled dispatch and the task service taking over. After the alarm broadcast ends the system stops holding the CPU for the app,
 * and the foreground service's onStartCommand and the task acquiring its own CPU lock both happen after that: the dispatcher ([TaskService.startTrigger]) acquires it first,
 * and releases it once the service has handled this start (start, queue, or not start due to pause/exit); if the service does not come up it is released automatically after [HANDOFF_MS].
 * Only this one dispatch window needs holding, so there is a single process-wide lock without counting: two in a row just push the deadline back.
 */
internal object TriggerHandoffWake {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized fun hold(context: Context) {
        val l = lock ?: context.applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mika:trigger-handoff")
            .apply { setReferenceCounted(false) }.also { lock = it }
        l.acquire(HANDOFF_MS)
    }

    @Synchronized fun release() { lock?.takeIf { it.isHeld }?.release() }

    const val HANDOFF_MS = 60_000L
}
