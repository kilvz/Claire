package com.wangzan101.mika.proactive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The proactive capability's own lock state: while the screen is off, the phone is not woken up for this feature.
 *
 * The state is read from the system every time ([read]: whether the screen is on and whether the keyguard is showing); the SCREEN_OFF /
 * SCREEN_ON / USER_PRESENT broadcasts only mean "look again" and are forwarded by [ProactiveRuntime] to [refresh]. While the screen is off it
 * only waits for broadcasts and does nothing on a timer. The only thing polled is "on but keyguard still showing": some unlock paths send
 * USER_PRESENT first and retract the keyguard a moment later, with no further broadcast (see the note above
 * [com.wangzan101.mika.task.LockState]). Only when that state is read and while watching (after [start]) is it re-read every [POLL_MS]; reading
 * anything else (screen off or already unlocked) stops the polling. Whether to poll is decided by the pure function [shouldPoll].
 *
 * All methods must be called on the serial coroutine of [scope] (broadcasts are thrown there first too), so no extra locking is needed.
 */
internal class ProactiveLockWatch(private val read: () -> Reading, private val scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean) {
        val locked get() = !interactive || keyguardLocked
    }

    private var active = false
    private val _locked = MutableStateFlow(true)
    /** Screen off or locked; not read before [start], so it is treated as locked (reading less is preferred). */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** The running poll; it only exists while watching, with the screen on and the keyguard still showing. Tests look at it. */
    internal var pollJob: Job? = null
        private set

    /** Start watching: read once. */
    fun start() {
        active = true
        refresh()
    }

    /** Stop watching: stop polling; [refresh] still reads afterwards, but no longer starts polling. */
    fun stop() {
        active = false
        cancelPoll()
    }

    /** Read from the system once, and start or stop polling based on what was read. */
    fun refresh() {
        val r = read()
        _locked.value = r.locked
        if (!shouldPoll(active, r)) cancelPoll()
        else if (pollJob?.isActive != true) pollJob = scope.launch { poll() }
    }

    /** The poll loop exits by itself once it reads that it should no longer poll, instead of cancelling itself through [refresh]. */
    private suspend fun poll() {
        while (true) {
            delay(POLL_MS)
            val r = read()
            _locked.value = r.locked
            if (!shouldPoll(active, r)) {
                pollJob = null
                return
            }
        }
    }

    private fun cancelPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        const val POLL_MS = 500L

        /** Poll only while watching, with the screen on and the keyguard still showing; never poll with the screen off (wait for SCREEN_ON). */
        fun shouldPoll(active: Boolean, r: Reading): Boolean = active && r.interactive && r.keyguardLocked
    }
}
