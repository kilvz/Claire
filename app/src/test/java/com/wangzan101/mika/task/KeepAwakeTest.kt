package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeepAwakeTest {
    private class FakeLock : WakeLockPort {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }

    @Test
    fun holdsWhileRunningAndReleasesAtEnd() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun waitingKeepsScreenOnForGraceThenReleases() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.WaitingTakeover("login", "请登录"))
        advanceTimeBy(KeepAwake.GRACE_MS - 1)
        runCurrent()
        assertTrue(lock.held)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(lock.held)

        // The user is done and the task runs on: the screen is kept awake again
        awake.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
    }

    @Test
    fun resumingWithinGraceKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(TaskGate.paused("x")))
        advanceTimeBy(KeepAwake.GRACE_MS / 2)
        awake.onState(TaskState.Running(2, ""))
        advanceTimeBy(KeepAwake.GRACE_MS)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Cancelled)
        assertFalse(lock.held)
    }

    // Queued behind other tasks for the virtual screen: still working, so it is not released after the waiting-for-user grace period
    @Test
    fun queuedForDisplayKeepsHolding() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        assertTrue(lock.held)
        awake.onState(TaskState.Running(1, ""))
        awake.onState(TaskState.Held(GateReason.queued("等屏")))
        advanceTimeBy(KeepAwake.GRACE_MS * 2)
        runCurrent()
        assertTrue(lock.held)
        awake.onState(TaskState.Running(2, ""))
        awake.onState(TaskState.Succeeded("ok"))
        assertFalse(lock.held)
    }

    @Test
    fun failureAndCancelRelease() = runTest {
        for (end in listOf(TaskState.Failed("x", FailKind.GaveUp), TaskState.Cancelled)) {
            val lock = FakeLock()
            val awake = KeepAwake(lock, backgroundScope)
            awake.onState(TaskState.Running(1, ""))
            awake.onState(end)
            assertFalse(lock.held)
        }
    }

    @Test
    fun noReacquireAfterClose() = runTest {
        val lock = FakeLock()
        val awake = KeepAwake(lock, backgroundScope)
        awake.onState(TaskState.Running(1, ""))
        awake.close()
        assertFalse(lock.held)
        // A late state publication after the task ended must not take the lock back
        awake.onState(TaskState.Running(2, ""))
        assertFalse(lock.held)
    }

    // Two tasks each hold one share: the lock is taken when the first starts and released only when the last ends
    @Test
    fun refCountedAcrossRuns() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        val b = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        b.onState(TaskState.Running(1, ""))
        assertTrue(lock.held)
        a.onState(TaskState.Succeeded("ok"))
        assertTrue(lock.held)
        a.close()
        assertTrue(lock.held)
        b.onState(TaskState.Cancelled)
        assertFalse(lock.held)
        b.onState(TaskState.Running(2, ""))
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    // A queued task takes over: as the previous one wraps up it first acquires a share for the successor and then releases its own; the successor acquires its own share and then releases the handoff one, so the screen lock is never dropped in between
    @Test
    fun handoffBridgesToQueuedRun() = runTest {
        val lock = FakeLock()
        val shared = SharedWakeLock(lock)
        val a = KeepAwake(shared.lease(), backgroundScope)
        a.onState(TaskState.Running(1, ""))
        val handoff = shared.lease().also { it.acquire() }
        a.close()
        assertTrue(lock.held)
        val b = KeepAwake(shared.lease(), backgroundScope)
        b.onState(TaskState.Running(0, ""))
        handoff.release()
        assertTrue(lock.held)
        // A repeated release (one when the run starts, another when the coroutine ends) is not counted twice
        handoff.release()
        assertTrue(lock.held)
        b.close()
        assertFalse(lock.held)
    }

    /** The screen is only kept on, not locked at full brightness: when the user is idle the system dims it as usual. */
    @Suppress("DEPRECATION")
    @Test
    fun screenLockLetsTheSystemDim() {
        assertEquals(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, ScreenWakeLock.LEVEL)
    }
}
