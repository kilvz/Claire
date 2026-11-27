package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskGateTest {
    @get:Rule val texts = ChineseTexts()

    private val locked = MutableStateFlow(false)
    private val shellUp = MutableStateFlow(true)
    private var moveBackCalls = 0
    private var moveBackResult = true
    private val gate = TaskGate(locked, shellUp) { moveBackCalls++; moveBackResult }
    private val reported = mutableListOf<TaskState?>()

    @Test
    fun passesImmediatelyWhenNothingBlocks() = runTest {
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun nonInteractiveWaitsForUnlockThenResumesByItself() = runTest {
        locked.value = LockState.Reading(interactive = false, keyguardLocked = false, deviceLocked = false).locked
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        locked.value = LockState.Reading(interactive = true, keyguardLocked = true, deviceLocked = true).locked // screen on but still on the lock screen
        runCurrent()
        assertFalse(passed)
        locked.value = LockState.Reading(interactive = true, keyguardLocked = false, deviceLocked = false).locked
        runCurrent()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    @Test
    fun gateRefreshesSystemLockStateBeforeAllowingWrite() = runTest {
        var systemLocked = true
        var unlocks = 0
        val refreshed = TaskGate(locked, shellUp,
            refreshLocked = { locked.value = systemLocked }, // the screen already went off, but the state flow for the broadcast has not updated yet
            unattendedUnlock = {
                unlocks++
                systemLocked = false
                locked.value = false
                "unlocked"
            }) { true }

        refreshed.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskUnlocksThenContinues() = runTest {
        locked.value = true
        var unlocks = 0
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = {
            unlocks++
            locked.value = false
            "unlocked"
        }) { true }

        unattended.pass { reported += it }

        assertEquals(1, unlocks)
        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun unattendedTaskFailsWhenUnlockIsUnavailable() = runTest {
        locked.value = true
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = { "unsupported_keyguard" }) { true }

        val result = withTimeoutOrNull(1_000) { runCatching { unattended.pass { reported += it } } }

        val error = result?.exceptionOrNull()
        assertTrue(error is UnattendedLockException)
        assertEquals("unsupported_keyguard", (error as UnattendedLockException).status)
        assertTrue(error.message.orEmpty().endsWith("[unsupported_keyguard]"))
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
    }

    @Test
    fun channelUnlockFailureUsesItsOwnGuidanceAndDoesNotRetry() = runTest {
        locked.value = true
        var unlocks = 0
        val channel = TaskGate(locked, shellUp,
            unattendedUnlock = { unlocks++; "disabled" },
            unlockFailureMessage = { tr(R.string.wechat_unlock_failed) }) { true }

        val result = withTimeoutOrNull(1_000) { runCatching { channel.pass { reported += it } } }

        val error = result?.exceptionOrNull()
        assertTrue(error is UnattendedLockException)
        assertEquals("${tr(R.string.wechat_unlock_failed)} [disabled]", error?.message)
        assertEquals(1, unlocks)
        assertTrue(locked.value)
        assertEquals(listOf(TaskState.Held(TaskGate.LOCKED)), reported)
    }

    /** A started task with local authorization fails to unlock: the task is no longer killed and credentials are not re-injected; it stays on the lock screen waiting for the user to unlock manually. */
    @Test
    fun midRunFailedUnlockWaitsForManualUnlockWithoutRetryingCredential() = runTest {
        var attempts = 0
        var failure: String? = null
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { attempts++; "attempt_blocked" },
            recoverUnlockFailure = { true },
            onRecoveryFailure = { failure = it }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        var resumed = false
        var needsFreshScreen: Boolean? = null
        launch { needsFreshScreen = recovering.pass { reported += it }; resumed = true }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals("attempt_blocked", failure)
        assertFalse(resumed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(resumed)
        assertEquals(true, needsFreshScreen)
        assertEquals(1, attempts)
    }

    /** A started task with local authorization unlocks automatically: the gate tells the caller that a lock-screen cycle happened, so prepared actions must re-read the screen before running. */
    @Test
    fun midRunSuccessfulAutoUnlockAlsoRequiresFreshScreen() = runTest {
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { locked.value = false; "unlocked" },
            recoverUnlockFailure = { true }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        assertTrue(recovering.pass { reported += it })
        assertFalse(locked.value)
    }

    /** The unlock helper errors midway: treated as an internal error and turned into a wait for the user to unlock, without throwing outward and losing progress. */
    @Test
    fun midRunUnlockExceptionWaitsForUserInsteadOfLosingProgress() = runTest {
        var reportedStatus: String? = null
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { error("helper disconnected") }, recoverUnlockFailure = { true },
            onRecoveryFailure = { reportedStatus = it }) { true }
        assertFalse(recovering.pass { reported += it })
        locked.value = true
        var resumed = false
        launch { recovering.pass { reported += it }; resumed = true }
        runCurrent()
        assertEquals("internal_error", reportedStatus)
        assertFalse(resumed)
        locked.value = false
        runCurrent()
        assertTrue(resumed)
    }

    /** Unlock failure in a task that never started still ends in failure as before: there is no progress to preserve. */
    @Test
    fun initialFailureStillEndsUnattendedRun() = runTest {
        locked.value = true
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { "unsupported_keyguard" }, recoverUnlockFailure = { true }) { true }
        val outcome = runCatching { recovering.pass { reported += it } }
        assertTrue(outcome.exceptionOrNull() is UnattendedLockException)
    }

    /** A task without unlock authorization (the user is present) when the unlock helper errors: still ends in failure as before, without pretending it can recover. */
    @Test
    fun unauthorizedRunStillFailsWhenUnlockErrors() = runTest {
        locked.value = true
        val recovering = TaskGate(locked, shellUp,
            unattendedUnlock = { error("helper disconnected") }, recoverUnlockFailure = { false }) { true }
        val outcome = runCatching { recovering.pass { reported += it } }
        assertTrue(outcome.exceptionOrNull() is UnattendedLockException)
        assertEquals("internal_error", (outcome.exceptionOrNull() as UnattendedLockException).status)
    }

    /** An attended task that is locked mid-run and comes back via manual unlock: the gate likewise reports a lock-screen cycle, and prepared actions re-read the screen first. */
    @Test
    fun attendedRunResumedFromManualUnlockRequiresFreshScreen() = runTest {
        assertFalse(gate.pass { reported += it })
        locked.value = true
        var passed = false
        var needsFreshScreen: Boolean? = null
        launch { needsFreshScreen = gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())
        locked.value = false
        runCurrent()
        assertTrue(passed)
        assertEquals(true, needsFreshScreen)
    }

    @Test
    fun unattendedTaskTrustsRefreshedSystemStateOverAttemptOutcome() = runTest {
        locked.value = true
        // When the flow returns early for reasons such as permissions, the user may have just unlocked manually; the actual system state is what counts.
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = {
            locked.value = false
            "disabled"
        }) { true }

        unattended.pass { reported += it }

        assertEquals(listOf<TaskState?>(TaskState.Held(TaskGate.LOCKED), null), reported)
    }

    @Test
    fun movedAwayPausesUntilUserContinuesThenMovesBack() = runTest {
        gate.resume() // a "Continue" from before the pause does not count
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), reported.last())
        assertEquals(0, moveBackCalls)

        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)

        // No longer paused after being moved back
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    /** The user touches the page that was handed over: every kind of screen stops, and tapping "Continue" lets it through right away; an earlier "Continue" does not count, and nothing is moved. */
    @Test
    fun userOnPagePausesAnyStepUntilContinue() = runTest {
        gate.onUserOnPage()
        assertTrue(gate.userOnPagePending)
        var passed = false
        launch { gate.pass({ false }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.USER_ON_PAGE)), reported.last())
        gate.resume()
        runCurrent()
        assertTrue(passed)
        assertFalse(gate.userOnPagePending)
        assertEquals(0, moveBackCalls)
        // Continue tapped before the step reached the gate: it does not stop
        gate.onUserOnPage()
        gate.resume()
        reported.clear()
        gate.pass { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)
    }

    @Test
    fun failedMoveBackStaysPaused() = runTest {
        moveBackResult = false
        gate.onTaskMovedAway()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        gate.resume()
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVE_BACK_FAILED)), reported.last())

        moveBackResult = true
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun helperLossPausesUntilBackAndUserContinues() = runTest {
        shellUp.value = false
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())

        gate.resume() // the helper has not recovered yet: Continue has no effect
        runCurrent()
        assertFalse(passed)

        shellUp.value = true
        runCurrent()
        assertFalse(passed) // even after recovery, it waits for the user to tap continue
        gate.resume()
        runCurrent()
        assertTrue(passed)
    }

    @Test
    fun movedAwayMeansOnMainAndNotOnAgentDisplay() {
        assertTrue(DeviceStateWatcher.isMovedAway(agentTop = 5, mainTop = 7, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = 7, mainTop = 3, taskId = 7))
        // The Agent closed the app itself: it is not on top of any screen, so it does not count as taken away
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = 3, taskId = 7))
        assertFalse(DeviceStateWatcher.isMovedAway(agentTop = -1, mainTop = -1, taskId = -1))
    }

    @Test
    fun continueTappedRightAsPauseIsShownIsNotLost() = runTest {
        gate.onTaskMovedAway()
        var passed = false
        // The user tapped continue the moment the notification showed "paused": the tap happened during the setWaiting callback
        launch {
            gate.pass { reported += it; if (it is TaskState.Held && moveBackCalls == 0) gate.resume() }
            passed = true
        }
        runCurrent()
        assertTrue(passed)
        assertEquals(1, moveBackCalls)
    }

    @Test
    fun exposesPendingMoveForImmediateNotice() {
        assertFalse(gate.movedAwayPending)
        gate.onTaskMovedAway()
        assertTrue(gate.movedAwayPending)
    }

    @Test
    fun continueTappedAfterMoveDetectedButBeforeGateCounts() = runTest {
        // The notification shows paused as soon as the move-away is detected; the user tapped continue before the Agent reached the gate
        gate.onTaskMovedAway()
        gate.resume()
        gate.pass { reported += it }
        assertEquals(1, moveBackCalls)
        assertEquals(null, reported.last())
    }

    /** Web/sandbox steps do not need the shell, virtual display move-back, or phone screen unlock. */
    @Test
    fun shellMovedAwayAndLockedOnlyCheckedForPhoneSteps() = runTest {
        shellUp.value = false
        gate.onTaskMovedAway()
        locked.value = true
        gate.pass({ false }) { reported += it }
        assertEquals(listOf<TaskState?>(null), reported)

        var passed = false
        launch { gate.pass({ true }) { reported += it }; passed = true }
        runCurrent()
        assertFalse(passed)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.SHELL_DOWN)), reported.last())
        coroutineContext.cancelChildren()
    }

    /** 锁屏时网页、沙箱步骤照常走,不去解锁;轮到手机步骤才按解锁策略办(无人值守的先试解锁,解不开就失败收尾,不盲目操作)。 */
    @Test
    fun lockedScreenBlocksOnlyPhoneStepsAndOnlyThoseTryToUnlock() = runTest {
        var unlocks = 0
        val unattended = TaskGate(locked, shellUp, unattendedUnlock = { unlocks++; "credential_required" }) { true }
        locked.value = true
        assertFalse(unattended.pass({ false }) { reported += it })
        assertEquals(0, unlocks)
        assertEquals(listOf<TaskState?>(null), reported)
        val failure = runCatching { unattended.pass({ true }) { reported += it } }.exceptionOrNull()
        assertEquals(1, unlocks)
        assertTrue(failure is UnattendedLockException)
        assertEquals("credential_required", (failure as UnattendedLockException).status)
    }
}
