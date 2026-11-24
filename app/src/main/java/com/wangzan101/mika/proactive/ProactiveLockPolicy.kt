package com.wangzan101.mika.proactive

/**
 * What the listener should do on lock / unlock: pure logic; [ProactiveRuntime] merely performs the returned actions.
 *
 * A "locked spell" runs from the first time locked is seen until unlocked is seen; the lock time is recorded once, at the start of that spell.
 * When the service skips a notification because of the lock, that also counts as seeing locked, and the lock time is moved back to before that
 * notification: with a late broadcast, notifications posted after locking but before the lock time was recorded are still caught up on unlock.
 * If the process starts while locked, the stored lock time is reused (it is earlier, which at worst catches up more; duplicates are stopped by dedup).
 *
 * Catching up is "owed": it is owed when a locked spell starts and at [start]. Returning [Action.CatchUp] only tells the caller to read the
 * shade; it counts as repaid only when the caller reports a successful read ([caughtUp]) — reading the shade can fail (the listener just
 * disconnected, the system throws), and that attempt then caught up nothing: if returning the action counted as repaid, the next lock would push
 * the lock time forward, and that batch, still in the shade, would fall before it and could never be caught up.
 * While it is owed, retry whenever possible ([retry]).
 * While it is owed, a new locked spell does not push the lock time forward (locked again after unlocking but before the listener reconnected,
 * that catch-up has not happened yet, and pushing it forward would miss notifications posted in between); notifications that were skipped can
 * still move it back.
 */
class ProactiveLockPolicy(lockedAtMs: Long) {
    sealed interface Action {
        /** Store the lock time (the catch-up is computed from it). */
        data class PersistLockedAt(val atMs: Long) : Action
        /** Ask the system to unbind the listener (only meaningful while connected). */
        data object Unbind : Action
        /** Ask the system to rebind the listener. */
        data object Rebind : Action
        /** Read the shade once to catch up on what was posted while locked. */
        data object CatchUp : Action
    }

    var lockedAtMs: Long = lockedAtMs
        private set
    var inLockedSpell: Boolean = false
        private set
    /** A catch-up has not been done yet: [lockedAtMs] is the start of the earliest locked period not yet caught up. */
    var catchUpOwed: Boolean = true
        private set

    /** Start watching the lock state (when the feature is turned on, or when the process starts with it on). */
    fun start(locked: Boolean, connected: Boolean): List<Action> {
        // Anything after the stored lock time has not been caught up yet
        catchUpOwed = true
        if (locked) {
            inLockedSpell = true
            return if (connected) listOf(Action.Unbind) else emptyList()
        }
        inLockedSpell = false
        return listOf(if (connected) catchUp() else Action.Rebind)
    }

    fun locked(nowMs: Long, connected: Boolean): List<Action> {
        val out = mutableListOf<Action>()
        if (!inLockedSpell) {
            inLockedSpell = true
            // The previous catch-up is still owed: leave the lock time at the start of that period
            if (!catchUpOwed) {
                catchUpOwed = true
                lockedAtMs = nowMs
                out += Action.PersistLockedAt(nowMs)
            }
        }
        if (connected) out += Action.Unbind
        return out
    }

    fun unlocked(connected: Boolean): List<Action> {
        if (!inLockedSpell) return emptyList()
        inLockedSpell = false
        // Without a successful unbind (still connected) there will be no "connected" callback, so catch up directly
        return listOf(if (connected) catchUp() else Action.Rebind)
    }

    /** The service saw the lock and skipped a notification posted at [postedAtMs]. */
    fun ignoredWhileLocked(postedAtMs: Long, connected: Boolean): List<Action> {
        val before = postedAtMs - 1
        val out = mutableListOf<Action>()
        if (!inLockedSpell) {
            inLockedSpell = true
            // While a catch-up is owed, only move it back
            if (!catchUpOwed || before < lockedAtMs) {
                lockedAtMs = before
                out += Action.PersistLockedAt(before)
            }
            catchUpOwed = true
        } else if (before < lockedAtMs) {
            lockedAtMs = before
            out += Action.PersistLockedAt(before)
        }
        if (connected) out += Action.Unbind
        return out
    }

    /** The system connected the listener: unbind if locked, catch up if not. */
    fun connected(locked: Boolean, nowMs: Long): List<Action> =
        if (locked) locked(nowMs, connected = true) else listOf(catchUp())

    /** Have the caller catch up; it is not repaid yet, until the caller reports a successful read ([caughtUp]). */
    private fun catchUp(): Action = Action.CatchUp

    /** The caller read the shade successfully (an empty result counts too): what was owed is repaid. */
    fun caughtUp() {
        catchUpOwed = false
    }

    /** A catch-up is still owed, it is unlocked now and the listener is connected: try once more. Otherwise nothing (locked waits for unlock, not connected waits for connect, which catches up then anyway). */
    fun retry(locked: Boolean, connected: Boolean): List<Action> =
        if (catchUpOwed && !locked && !inLockedSpell && connected) listOf(catchUp()) else emptyList()
}
