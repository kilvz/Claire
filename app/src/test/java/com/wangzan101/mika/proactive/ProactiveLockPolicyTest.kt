package com.wangzan101.mika.proactive

import com.wangzan101.mika.proactive.ProactiveLockPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveLockPolicyTest {
    // The three below: follow the returned actions, and treat a catch-up as a successful read of the notification shade (the runtime calls `caughtUp` after a successful read). The read-failure cases have their own tests
    private fun ProactiveLockPolicy.startOk(locked: Boolean, connected: Boolean) = start(locked, connected).also { if (Action.CatchUp in it) caughtUp() }
    private fun ProactiveLockPolicy.unlockedOk(connected: Boolean) = unlocked(connected).also { if (Action.CatchUp in it) caughtUp() }
    private fun ProactiveLockPolicy.connectedOk(locked: Boolean, nowMs: Long) = connected(locked, nowMs).also { if (Action.CatchUp in it) caughtUp() }

    @Test fun startUnlockedRebinds() = assertEquals(listOf(Action.Rebind), ProactiveLockPolicy(0).start(locked = false, connected = false))

    @Test fun startLockedKeepsTheStoredLockTime() {
        val p = ProactiveLockPolicy(100)
        assertEquals(emptyList<Action>(), p.startOk(locked = true, connected = false))
        assertEquals(100L, p.lockedAtMs)
        // Seeing locked again inside the same locked stretch does not change the lock time
        assertEquals(emptyList<Action>(), p.locked(500, connected = false))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
    }

    @Test fun lockPersistsOnceAndUnbinds() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        assertEquals(listOf(Action.PersistLockedAt(1_000), Action.Unbind), p.locked(1_000, connected = true))
        assertEquals(listOf(Action.Unbind), p.locked(2_000, connected = true))
        assertEquals(emptyList<Action>(), p.locked(3_000, connected = false))
        assertEquals(1_000L, p.lockedAtMs)
    }

    @Test fun unlockRebindsOrCatchesUpWhenStillConnected() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        p.locked(1_000, connected = true)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(emptyList<Action>(), p.unlockedOk(connected = false)) // already unlocked, no repeat
        p.locked(2_000, connected = true)
        // The unbind failed and the listener stayed connected: there will be no further "connected", so catch up directly
        assertEquals(listOf(Action.CatchUp), p.unlockedOk(connected = true))
    }

    @Test fun connectedWhileUnlockedCatchesUpAndWhileLockedUnbinds() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = false)
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 10))
        assertEquals(listOf(Action.PersistLockedAt(20), Action.Unbind), p.connectedOk(locked = true, nowMs = 20))
    }

    @Test fun postIgnoredBeforeTheLockBroadcastMovesTheLockTimeBack() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        // The service saw locked first and skipped the notification posted at 900; the broadcast only arrives at 1000
        assertEquals(listOf(Action.PersistLockedAt(899), Action.Unbind), p.ignoredWhileLocked(900, connected = true))
        assertEquals(emptyList<Action>(), p.locked(1_000, connected = false))
        assertEquals(899L, p.lockedAtMs)
        // An earlier one moves the time further back, a later one leaves it alone
        assertEquals(listOf(Action.PersistLockedAt(799)), p.ignoredWhileLocked(800, connected = false))
        assertEquals(emptyList<Action>(), p.ignoredWhileLocked(950, connected = false))
        assertEquals(799L, p.lockedAtMs)
    }

    // ---- Owed catch-up: the lock time stays at the start of the earliest locked stretch not yet caught up ----

    // Lock (unbind) -> notification N posted -> unlock (request rebind) -> locked again before connecting -> connected while locked (unbind only) -> unlock and connect: the catch-up must cover N
    @Test fun relockBeforeTheListenerConnectsKeepsTheEarlierLockTime() {
        val p = ProactiveLockPolicy(0)
        assertEquals(listOf(Action.CatchUp), p.startOk(locked = false, connected = true))
        assertEquals(listOf(Action.PersistLockedAt(1_000), Action.Unbind), p.locked(1_000, connected = true))
        val postedN = 2_000L
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        // Locked again before connecting: do not move the time later and do not persist it
        assertEquals(emptyList<Action>(), p.locked(3_000, connected = false))
        assertEquals(listOf(Action.Unbind), p.connectedOk(locked = true, nowMs = 3_500))
        assertEquals(1_000L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 4_000))
        assertTrue("补收从 N 之前算起", p.lockedAtMs < postedN)
        // Catch-up done: the next lock records a fresh lock time as usual
        assertEquals(listOf(Action.PersistLockedAt(5_000), Action.Unbind), p.locked(5_000, connected = true))
        assertEquals(5_000L, p.lockedAtMs)
    }

    // Unlocked when the process started, then locked before the first connect: keep the stored lock time until a catch-up has actually happened
    @Test fun lockBeforeTheFirstConnectAfterStartKeepsTheStoredLockTime() {
        val p = ProactiveLockPolicy(100)
        assertEquals(listOf(Action.Rebind), p.startOk(locked = false, connected = false))
        assertEquals(emptyList<Action>(), p.locked(500, connected = false))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 600))
        assertEquals(100L, p.lockedAtMs)
        assertEquals(listOf(Action.PersistLockedAt(900)), p.locked(900, connected = false))
    }

    @Test fun severalFlapsBeforeAConnectKeepTheFirstLockTime() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        p.locked(1_000, connected = true)
        for (t in listOf(2_000L, 3_000L, 4_000L)) {
            assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
            assertEquals(emptyList<Action>(), p.locked(t, connected = false))
        }
        assertEquals(listOf(Action.Unbind), p.connectedOk(locked = true, nowMs = 4_500))
        p.unlockedOk(connected = false)
        assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = 5_000))
        assertEquals(1_000L, p.lockedAtMs)
    }

    // The ordinary lock/unlock pair: after each catch-up, the next lock persists its own time
    @Test fun eachLockAfterACatchUpPersistsItsOwnTime() {
        val p = ProactiveLockPolicy(0)
        p.startOk(locked = false, connected = true)
        for (t in listOf(1_000L, 2_000L, 3_000L)) {
            assertEquals(listOf(Action.PersistLockedAt(t), Action.Unbind), p.locked(t, connected = true))
            assertEquals(listOf(Action.Rebind), p.unlockedOk(connected = false))
            assertEquals(listOf(Action.CatchUp), p.connectedOk(locked = false, nowMs = t + 100))
            assertEquals(t, p.lockedAtMs)
        }
        // When the unbind failed and the listener stayed connected, unlocking catches up and likewise counts as caught up
        p.locked(4_000, connected = true)
        assertEquals(listOf(Action.CatchUp), p.unlockedOk(connected = true))
        assertEquals(listOf(Action.PersistLockedAt(5_000)), p.locked(5_000, connected = false))
    }

    // While a catch-up is owed, a skipped notification can only move the lock time earlier
    @Test fun ignoredWhileOwedOnlyMovesTheTimeEarlier() {
        val p = ProactiveLockPolicy(1_000)
        p.startOk(locked = false, connected = false)
        assertEquals(emptyList<Action>(), p.ignoredWhileLocked(1_500, connected = false))
        assertEquals(1_000L, p.lockedAtMs)
        assertEquals(listOf(Action.PersistLockedAt(799)), p.ignoredWhileLocked(800, connected = false))
        assertEquals(799L, p.lockedAtMs)
        p.unlockedOk(connected = false)
        assertEquals(listOf(Action.Unbind), p.ignoredWhileLocked(900, connected = true))
        assertEquals(799L, p.lockedAtMs)
    }

    // The notification shade cannot be read during the catch-up (the listener just disconnected, or the system threw): this attempt does not count and the catch-up is still owed.
    // The next lock must not move the lock time later — the batch still in the shade was posted before it, and moving the time later would make it impossible to catch up
    @Test fun aCatchUpThatCouldNotReadTheShadeIsStillOwed() {
        val p = ProactiveLockPolicy(100_000)
        p.start(locked = true, connected = false)
        assertEquals(listOf(Action.Rebind), p.unlocked(connected = false))
        // Connected and offered a catch-up — but reading the shade fails, so nobody calls `caughtUp`
        assertEquals(listOf(Action.CatchUp), p.connected(locked = false, nowMs = 160_000))
        assertTrue(p.catchUpOwed)
        // Locked again: the lock time stays at the start of the earlier stretch, nothing new is persisted
        assertEquals(listOf(Action.Unbind), p.locked(nowMs = 200_000, connected = true))
        assertEquals(100_000L, p.lockedAtMs)
        // Unlock and connect again: this time the read succeeds, so the one posted at 150 s falls inside the "after the lock" range
        p.unlocked(connected = false)
        assertEquals(listOf(Action.CatchUp), p.connected(locked = false, nowMs = 260_000))
        val earlier = com.wangzan101.mika.core.proactive.NoticeRecord("k", "com.chat", 150_000, "t", "x")
        assertEquals(listOf(earlier), com.wangzan101.mika.core.proactive.CatchUp.select(listOf(earlier), enabledAtMs = 0, lastLockedAtMs = p.lockedAtMs - 5_000))
        p.caughtUp()
        assertEquals(false, p.catchUpOwed)
        // Only after the catch-up is settled does the next lock record its own time
        assertEquals(listOf(Action.PersistLockedAt(300_000), Action.Unbind), p.locked(nowMs = 300_000, connected = true))
    }

    // While still owed, retry whenever possible: only when unlocked and the listener is connected; if locked wait for the unlock, if not connected wait for the connect, and once settled do not retry
    @Test fun anOwedCatchUpIsRetriedWhenPossible() {
        val p = ProactiveLockPolicy(0)
        p.start(locked = false, connected = true)
        assertTrue(p.catchUpOwed)
        assertEquals(listOf(Action.CatchUp), p.retry(locked = false, connected = true))
        assertEquals(emptyList<Action>(), p.retry(locked = true, connected = true))
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = false))
        p.caughtUp()
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = true))
        // No retry within the locked stretch
        p.locked(1_000, connected = true)
        assertEquals(emptyList<Action>(), p.retry(locked = false, connected = true))
    }
}
