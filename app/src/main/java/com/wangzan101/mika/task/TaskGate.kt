package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop 在每个会改动屏幕的工具之前调用 [pass]。按先后检查:
 * 1. 手机操作助手不可用 → 暂停,等它恢复且用户点「继续」;
 * 2. 任务被挪到主屏(用户在主屏打开了这个 App)→ 暂停,用户点「继续」后才把它挪回虚拟屏;
 *    Mika 不自己判断用户是否用完,避免在用户眼前把 App 抽走;
 * 3. 屏幕熄灭或锁屏 → 定时任务及另行授权的微信任务尝试唤醒/解锁，其他任务等待解锁；亮屏并解锁后自动继续。
 * 这三条只在这一步用的是手机(phone 为 true)时才查:网页、沙箱上的步骤不需要助手,不会被挪到主屏,
 * 也不碰主屏(网页在 App 自己的私有虚拟屏上),熄屏、锁屏时照常开始和继续。
 * 另外,用户在浏览器页上亲手摸了借给任务的页面([onUserOnPage])→ 不论哪种屏都先停下,用户点「继续」再接着做。
 * 不碰界面的步骤(模型推理、读屏)不经过这里,照常进行。
 */
class TaskGate(
    private val locked: StateFlow<Boolean>,
    private val shellAvailable: StateFlow<Boolean>,
    /** Re-read the system lock state synchronously before write operations, so nothing is let through while the screen-off broadcast is still unprocessed. */
    private val refreshLocked: (() -> Unit)? = null,
    /** Called when an unattended task hits the lock screen; returns the unlock status, and [locked] must be refreshed from the system state before returning. */
    private val unattendedUnlock: (suspend () -> String)? = null,
    private val unlockFailureMessage: () -> String = { tr(R.string.trigger_unlock_failed) },
    /** For tasks on a local authorization (an unlock authorization stored on this device): after an unlock failure they may switch to waiting for the user to unlock manually instead of ending in failure. */
    private val recoverUnlockFailure: () -> Boolean = { false },
    /** A line to report when switching to waiting for a manual unlock (with a credential-free status code), so a reminder can be sent. */
    private val onRecoveryFailure: (String) -> Unit = {},
    private val moveBack: suspend () -> Boolean,
) {
    private val movedAway = MutableStateFlow(false)
    private val userOnPage = MutableStateFlow(false)
    private val resumes = MutableStateFlow(0)
    /** Whether the task has already done at least one step (the gate has let it through once): the initial unlock doesn't count as a "lock during the run". */
    private var hasPassed = false
    /** Resume-tap count at the moment the move-away was noticed: "Continue" taps after that all count, even if the Agent hasn't reached the gate yet. */
    @Volatile private var resumesAtMove = 0

    fun onTaskMovedAway() {
        if (!movedAway.value) resumesAtMove = resumes.value
        movedAway.value = true
    }

    /** The move-away has been noticed but the move back hasn't happened yet: the task service uses this to show the pause immediately instead of waiting for the next screen-changing tool. */
    val movedAwayPending: Boolean get() = movedAway.value

    /**
     * The user personally touched the page lent to the task on the browser page (they are operating, so Claire must not act at the same time):
     * stop before the next screen-changing tool and wait for the user to tap "Continue"; the task service uses this to show the pause immediately.
     */
    fun onUserOnPage() { userOnPage.value = true }

    /** The user is operating on the lent-out page and hasn't tapped "Continue" yet. */
    val userOnPagePending: Boolean get() = userOnPage.value

    /** The user tapped "Continue". It only applies to pauses that start after the tap or are already in progress; a pause caused by the user operating on the page is lifted on the spot. */
    fun resume() {
        resumes.update { it + 1 }
        userOnPage.value = false
    }

    /**
     * Returning true means a lock cycle happened during this wait (after the task had started): the caller uses this to discard actions prepared against the old
     * screen and re-read the current screen first. The initial unlock at the start doesn't count.
     */
    suspend fun pass(phone: () -> Boolean = { true }, setWaiting: (TaskState?) -> Unit): Boolean {
        var recoveredMidRun = false
        while (true) {
            refreshLocked?.invoke()
            val onPhone = phone()
            when {
                userOnPage.value -> {
                    setWaiting(TaskState.Held(paused(USER_ON_PAGE)))
                    userOnPage.first { !it }
                }
                onPhone && !shellAvailable.value -> {
                    pauseUntilResume(SHELL_DOWN, setWaiting)
                }
                onPhone && movedAway.value -> {
                    pauseUntilResume(MOVED_AWAY, setWaiting, seen = resumesAtMove)
                    try {
                        while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    } catch (e: TargetAppRestoreFailed) {
                        movedAway.value = false
                        setWaiting(null)
                        throw e
                    }
                    if (shellAvailable.value) movedAway.value = false
                }
                onPhone && locked.value -> {
                    recoveredMidRun = recoveredMidRun || hasPassed
                    setWaiting(TaskState.Held(LOCKED))
                    if (unattendedUnlock == null) {
                        locked.first { !it }
                    } else {
                        val status = try { unattendedUnlock.invoke() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { refreshLocked?.invoke(); "internal_error" }
                        // Unattended runs can't wait indefinitely for the user to unlock; trust only the refreshed system state, not the flow claiming success.
                        if (locked.value) {
                            // A local-authorization task that has already started is neither killed nor injected with credentials again: instead it waits for the user to unlock manually
                            if (!hasPassed || !recoverUnlockFailure()) throw UnattendedLockException(unlockFailureMessage(), status)
                            onRecoveryFailure(status)
                            locked.first { !it }
                        }
                    }
                }
                else -> {
                    hasPassed = true
                    setWaiting(null)
                    return recoveredMidRun
                }
            }
        }
    }

    /** Require the user to handle one step by hand on the phone, then tap "Continue" (counts as "needs you" in the UI). */
    suspend fun pauseForUser(why: String, setWaiting: (TaskState?) -> Unit) {
        val seen = resumes.value
        setWaiting(TaskState.Held(GateReason("paused", why, resumable = true, needsYou = true)))
        resumes.first { it != seen }
    }

    /** [seen]: only "Continue" taps after this count are accepted; defaults to the current value (the count is captured before the pause is shown, so a tap right after it appears still counts). */
    private suspend fun pauseUntilResume(why: String, setWaiting: (TaskState?) -> Unit, seen: Int = resumes.value) {
        setWaiting(TaskState.Held(paused(why)))
        resumes.first { it != seen }
    }

    companion object {
        /** Locked or screen off: continue automatically after unlocking (recorded as waiting_unlock in the run record). */
        val LOCKED = GateReason("waiting_unlock")

        /** Pause; continue only after the user taps "Continue" (recorded as paused, with the reason, in the run record). */
        fun paused(why: String) = GateReason("paused", why, resumable = true)

        /** Wait for the user to do something by hand on the phone (e.g. pick one in a system chooser), then continue automatically (recorded as waiting_user in the run record). */
        fun userStep(why: String) = GateReason("waiting_user", why, needsYou = true)

        /** The line asking the user to choose when the system asks which app to open [app] with on the user's screen; the name is omitted when it can't be read. */
        fun pickCopy(app: String?) = app?.let { tr(R.string.gate_pick_app, it) } ?: tr(R.string.gate_pick_system)

        /** The line used when the chosen [app] requires the user to handle it by hand. */
        fun finishCopy(app: String?) = app?.let { tr(R.string.gate_finish_app, it) } ?: tr(R.string.gate_finish_unknown)

        val MOVED_AWAY: String get() = tr(R.string.gate_moved_away)
        val MOVE_BACK_FAILED: String get() = tr(R.string.gate_move_back_failed)
        val SHELL_DOWN: String get() = tr(R.string.gate_shell_down)
        /** The reason for pausing when the user personally operates the page lent to the task on the browser page (the UI recognizes this pause by it). */
        val USER_ON_PAGE: String get() = tr(R.string.gate_user_on_page)
    }
}

/** An unattended task couldn't get past the system lock screen; the credential-free status code is kept so the task service can turn it into a failed terminal state with a result notification. */
internal class UnattendedLockException(message: String, val status: String) : Exception("$message [$status]")
