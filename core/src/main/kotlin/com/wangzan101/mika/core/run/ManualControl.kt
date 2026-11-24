package com.wangzan101.mika.core.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Control ownership, one per run. A takeover first waits for the current screen action to finish; a hand-back first waits for the user input to finish. */
class ManualControl {
    enum class State { Automatic, Requesting, Manual, Closed }
    class Interrupted : Exception("The user changed the screen. Read the current screen again before acting.")
    private val _state = MutableStateFlow(State.Automatic)
    val state = _state.asStateFlow()
    private val ownership = Mutex()
    private val activeActions = MutableStateFlow(0)
    private val input = Mutex()
    @Volatile var version = 0L
        private set
    class Observer { @Volatile internal var version = 0L }
    private val defaultObserver = Observer()

    private class Lease(val owner: ManualControl) : AbstractCoroutineContextElement(Key) {
        var active = true
        companion object Key : CoroutineContext.Key<Lease>
    }
    private class Plan(val owner: ManualControl, val participant: Any, val version: Long) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Plan>
    }

    suspend fun awaitAutomatic() {
        if (currentCoroutineContext()[Lease]?.let { it.owner === this && it.active } == true) return
        if (_state.first { it == State.Automatic || it == State.Closed } == State.Closed) throw CancellationException("run closed")
    }

    suspend fun <T> plan(participant: Any = this, block: suspend () -> T): T {
        awaitAutomatic()
        val prior = currentCoroutineContext()[Plan]
        return if (prior?.owner === this && prior.participant === participant) block() else withContext(Plan(this, participant, version)) { block() }
    }

    suspend fun <T> automatic(observation: Boolean = false, refresh: Boolean = observation,
        refreshed: (T) -> Boolean = { true }, observer: Observer = defaultObserver, block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease]?.let { it.owner === this && it.active } == true) return block()
        val planned = currentCoroutineContext()[Plan]?.takeIf { it.owner === this }?.version ?: version
        while (true) {
            awaitAutomatic()
            // Parent and child actions of a normal task may run in parallel. Only drain actions that have already started when control changes hands or the screen is resized.
            val admitted = ownership.withLock {
                if (_state.value != State.Automatic) false
                else { activeActions.update { it + 1 }; true }
            }
            if (!admitted) continue
            val lease = Lease(this)
            try {
                if (planned != version || (observer.version != version && !observation)) throw Interrupted()
                return withContext(lease) { block() }.also { if (refresh && refreshed(it)) observer.version = version }
            } finally {
                lease.active = false
                activeActions.update { it - 1 }
            }
        }
    }

    suspend fun begin(timeoutMs: Long = 10_000): Boolean {
        var requested = false
        var handed = false
        try {
            val ready = withTimeoutOrNull(timeoutMs) {
                ownership.withLock { requested = _state.compareAndSet(State.Automatic, State.Requesting) }
                if (!requested) return@withTimeoutOrNull false
                activeActions.first { it == 0 }
                true
            } == true
            if (!ready) return false
            return input.withLock {
                if (!_state.compareAndSet(State.Requesting, State.Manual)) return@withLock false
                version++
                handed = true
                true
            }
        } finally {
            if (!handed) {
                if (requested) _state.compareAndSet(State.Requesting, State.Automatic)
            }
        }
    }

    /** Returning null means control has been lost: stale touch points must not be fed into a task started later. */
    suspend fun <T : Any> userInput(block: suspend () -> T): T? = input.withLock {
        if (_state.value != State.Manual) null else block()
    }

    /** Drains automatic/manual input when the screen layout changes, without changing ownership; older model plans and observations are invalidated at the same time. */
    suspend fun reconfigure(block: suspend () -> Boolean): Boolean {
        suspend fun resize() = block().also { if (it) version++ }
        while (true) {
            when (_state.value) {
                State.Closed -> return false
                State.Requesting -> _state.first { it != State.Requesting }
                State.Manual -> input.withLock {
                    if (_state.value == State.Manual) return resize()
                }
                State.Automatic -> ownership.withLock {
                    if (_state.value == State.Automatic) {
                        activeActions.first { it == 0 }
                        input.withLock { if (_state.value == State.Automatic) return resize() }
                    }
                }
            }
        }
    }

    suspend fun end(accepted: suspend () -> Boolean = { true }) = input.withLock {
        // Stale notifications and failed main-screen takeover requests must not hand over control of the current virtual screen.
        if (accepted() && _state.value == State.Manual) {
            version++
            _state.value = State.Automatic
        }
    }

    suspend fun close() = input.withLock {
        _state.value = State.Closed
    }

    companion object { const val CHANGED = "manual_control_changed" }
}
