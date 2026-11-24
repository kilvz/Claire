package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockStateTest {
    private var interactive = false
    private var keyguardLocked = true
    private var deviceLocked = true
    private var reads = 0

    private fun TestScope.lockState() = LockState({ reads++; LockState.Reading(interactive, keyguardLocked, deviceLocked) }, backgroundScope)

    private fun TestScope.tick() {
        advanceTimeBy(DeviceStateWatcher.POLL_MS)
        runCurrent()
    }

    // The unlock broadcast arrives before the lock screen dismisses: the reading still says locked, and with no later broadcast the state must still observe the unlock on its own
    @Test fun unlockSeenWithoutAnotherBroadcast() = runTest {
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        interactive = true
        lock.refresh() // SCREEN_ON or USER_PRESENT: screen is on, the lock screen is still showing
        tick()
        assertFalse(passed)
        deviceLocked = false // The device is truly unlocked (lock screen dismissed), with no broadcast
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())

        // Once the unlock has been read, no more reads
        val unlocked = reads
        repeat(10) { tick() }
        assertEquals(unlocked, reads)
    }

    // #638: polling does not stop while the screen is off; even if none of the screen-on and unlock broadcasts arrive, polling alone must keep it going.
    // The old guard blocked re-reads with the snapshot taken while the screen was off (interactive=false), so a single missed SCREEN_ON left it stuck forever at "waiting for you to unlock the phone".
    @Test fun screenOnAndUnlockSeenWithoutAnyBroadcast() = runTest {
        val lock = lockState() // Screen off, locked
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        val dark = reads
        repeat(5) { tick() } // Still reading while the screen is off: no broadcast can be relied on after the screen comes on
        assertTrue(reads > dark)
        assertFalse(passed)

        interactive = true // Screen on, but SCREEN_ON never arrived
        tick()
        assertFalse(passed) // The device is still locked, so it is not let through
        deviceLocked = false // Unlocked, and USER_PRESENT never arrived either
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // On some ROMs the lock screen dismisses first (keyguardLocked is already false) while the device is still locked (deviceLocked is still true):
    // polling must not stop just because the lock screen is gone, otherwise deviceLocked never reads false and the task stays stuck at "waiting for you to unlock the phone".
    @Test fun unlockSeenWhenKeyguardClearsBeforeDevice() = runTest {
        interactive = true
        keyguardLocked = true
        deviceLocked = true
        val lock = lockState()
        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent()
        assertEquals(TaskState.Held(TaskGate.LOCKED), reported.last())

        keyguardLocked = false // Lock screen dismisses first
        tick()
        assertFalse(passed) // The device is still locked, it cannot be let through
        deviceLocked = false // The device is truly unlocked, with no broadcast
        tick()
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // Re-read once as soon as the subscription starts: a missed screen-on/unlock need not wait another poll period
    @Test fun staleLockedHealsOnSubscribe() = runTest {
        val lock = lockState() // Read at construction: screen off, locked
        interactive = true
        deviceLocked = false // The system has actually unlocked with the screen on, but no broadcast came and no subscriber was reading before

        val gate = TaskGate(lock.locked, MutableStateFlow(true)) { true }
        val reported = mutableListOf<TaskState?>()
        var passed = false
        launch { gate.pass { reported += it }; passed = true }
        runCurrent() // The subscription starts and re-reads at once, without waiting for the first 500ms
        assertTrue(passed)
        assertEquals(null, reported.last())
    }

    // No reads while nobody is watching this state
    @Test fun noReadsWithoutSubscribers() = runTest {
        interactive = true
        val lock = lockState()
        runCurrent()
        val start = reads
        repeat(10) { tick() }
        assertEquals(start, reads)
        assertTrue(lock.locked.value)
    }
}
