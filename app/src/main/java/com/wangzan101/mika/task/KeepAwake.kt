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
 * 只在 Running(含等另一个任务用完某个 App)和排在别的任务后面等虚拟屏时持有。等待接管/回答/继续/解锁时屏幕不需要为 Agent 亮着,
 * 再保持 [GRACE_MS](用户多半正要处理,别让屏幕在他眼前熄掉)后释放,任务继续跑时重新持有。
 * 结束、失败、取消立即释放。锁随进程走:Mika 异常退出时系统自动收回,不需要持久化恢复。
 * 用户按电源键照样能熄屏(没带 ACQUIRE_CAUSES_WAKEUP),这时 gate 会让任务等待解锁。
 *
 * 任务的 CPU 锁([CpuWakeLock])用同一套规则,只是 keepThroughEnd:从开跑一直撑到任务收尾(结果送达、账本记好),
 * 等你处理时同样过了宽限就放开,不让一个等回答的任务整晚撑着 CPU。
 */
class KeepAwake(
    private val lock: WakeLockPort,
    private val scope: CoroutineScope,
    /** 到了终态(和开跑前的 Idle)也接着持有、直到 [close]:CPU 锁要撑到结果送达、定时账本记好;屏幕锁不用。 */
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
 * CPU 唤醒锁(PARTIAL_WAKE_LOCK):熄屏、Doze 里也让任务的网络与推理接着走,不点亮屏幕。
 * 和屏幕锁一样由 [SharedWakeLock] 分成每个任务各持一份、按任务状态拿放(见 [KeepAwake] 的 keepThroughEnd);
 * [MAX_HOLD_MS] 只是漏了释放时的兜底。
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
 * 定时派发到任务服务接手之间的 CPU 唤醒。闹钟广播结束后系统就不再替 App 撑着 CPU,
 * 而前台服务的 onStartCommand、任务拿到自己那份 CPU 锁都在这之后:派发方([TaskService.startTrigger])先拿上,
 * 服务处理完这次启动(开跑、排队或因暂停/退出不开)就放开;服务没起来时到 [HANDOFF_MS] 自动放开。
 * 只有这一个派发窗口要撑,所以整个进程一把、不计数:连着来两次就把期限往后推。
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
