package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * AgentLoop calls [pass] before every tool that changes the screen. The checks, in order:
 * 1. The phone-control helper is unavailable → pause, wait for it to recover and for the user to tap "Continue";
 * 2. The task was moved to the main screen (the user opened this app on the main screen) → pause, and move it back to the virtual display only after the user taps "Continue";
 *    Claire never decides by itself that the user is done, to avoid pulling the app away in front of them;
 * 3. The screen is off or locked → scheduled tasks and separately authorized WeChat tasks try to wake/unlock it, other tasks wait for the unlock; they continue automatically once the screen is on and unlocked.
 * The first two are only checked when this step uses the phone (phone is true): steps on the web or in the sandbox need no helper and can't be moved to the main screen.
 * In addition, when the user personally touches the page lent to the task on the browser page ([onUserOnPage]) → stop first regardless of the surface, and continue once the user taps "Continue".
 * Steps that don't touch the screen (model inference, reading the screen) don't come through here and proceed as usual.
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
                    while (shellAvailable.value && !moveBack()) pauseUntilResume(MOVE_BACK_FAILED, setWaiting)
                    if (shellAvailable.value) movedAway.value = false
                }
                locked.value -> {
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
