package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/** Claire's accessibility service as the App sees it: whether it is in the system list ([enabled]) and whether the service instance is connected ([connected]). */
data class A11yObservation(val enabled: Boolean, val connected: Boolean) {
    /**
     * The service is not running, so it must be repaired: whether it is on in the system but not bound (after the process is killed the system treats it as crashed),
     * or it was removed from the system list (the system does that on a force stop). To turn it off, the user disables "turn on accessibility for me".
     */
    val stuck: Boolean get() = !connected
}

/** How far the automatic restart of the accessibility service has got: a party that needs the service uses this to decide whether to wait a bit longer or give up immediately. */
enum class RepairPhase {
    /** Nothing to repair (the service is connected, the user turned off turn-on-for-me in Claire, the helper is not there, or it gave up after two attempts). */
    Idle,
    /** Stuck; wait a while first to see whether the system connects it by itself. */
    Pending,
    /** Removing and re-adding it, waiting for the service to connect. */
    Repairing,
}

/** Result of the last automatic restart of the accessibility service (kept in memory only, to say one line on the settings page). */
data class RepairOutcome(val restarted: Boolean, val atMillis: Long, val attempts: Int)

/**
 * Turns Claire's accessibility service back on automatically when it is not running: while the built-in helper is running, use it to write Claire's service back into the system list
 * ([RecoveryPermissions.restartAccessibility]: if it is in the list, remove and re-add it so the system binds it again; if not, just add it).
 * When the App itself holds the permission to modify system settings ([direct]) it writes directly, without waiting for the helper (repair also works with wireless debugging off).
 * Only three inputs are watched: the service ([A11yObservation]), the built-in helper, and whether the user has unlocked; any change triggers a fresh decision, with no polling.
 *
 * - Repair whenever it is not running; only a user turning off "turn on accessibility for me" in Claire makes it leave it alone.
 * - Before the user has unlocked once since boot (the credential-encrypted storage is not yet unlocked), services that do not support direct start cannot be bound anyway: no repair then,
 *   and once the user unlocks (unlocked of [watch] becomes true) it decides again and repairs when needed.
 * - When it first gets stuck, wait [GRACE_MS] first: right after the process starts the system usually connects at once, so no action is needed.
 * - Each stuck episode is tried at most [MAX_ATTEMPTS] times with growing intervals; if it still does not connect, stop and leave it to the hint for manual handling in the UI.
 *   This episode counts as over only once the service is connected; a later disconnect starts the count again.
 */
class AccessibilityRepair(
    private val restart: suspend (gapMs: Long) -> PermissionResult,
    private val consent: () -> RecoveryConsent,
    /** True when the App itself can write system settings (it holds the permission to modify system settings): then the built-in helper is not waited for. */
    private val direct: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    /** False after an explicit exit: nothing is written back for accessibility; when it is turned on manually next time, the active flow of [watch] decides again. */
    private val allowed: () -> Boolean = { true },
    /** True when the user paused Claire (reads the persisted pause flag): do not turn accessibility on automatically even if the helper status has not caught up. */
    private val paused: () -> Boolean = { false },
) {
    private val _last = MutableStateFlow<RepairOutcome?>(null)
    val last: StateFlow<RepairOutcome?> = _last.asStateFlow()

    private val _phase = MutableStateFlow(RepairPhase.Idle)
    val phase: StateFlow<RepairPhase> = _phase.asStateFlow()

    private enum class Result { Restored, GaveUp, Interrupted }

    private data class WatchInputs(
        val service: A11yObservation,
        val helper: BuiltinShellStatus,
        val unlocked: Boolean,
        val active: Boolean,
    )

    /**
     * Watches continuously until cancelled. Changes of state during a repair (including its own removal and re-adding) cause one more decision on the latest state.
     * [unlocked] is whether the user has unlocked since boot; nothing is repaired while it is false, and its change on unlock makes this decide again.
     * [active] is true when there has been no explicit exit; changes from exiting and from turning on manually also make this decide again.
     */
    suspend fun watch(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean> = MutableStateFlow(true),
        active: Flow<Boolean> = flowOf(true),
    ) {
        var exhausted = false
        combine(service, helper, unlocked, active, ::WatchInputs).conflate().collect { (s, h, u, activeNow) ->
            if (!activeNow) return@collect
            if (s.connected) {
                exhausted = false
                return@collect
            }
            if (exhausted || !eligible(s, h, u)) return@collect
            try {
                _phase.value = RepairPhase.Pending
                delay(GRACE_MS)
                if (!eligible(service.value, helper.value, unlocked.value)) return@collect
                _phase.value = RepairPhase.Repairing
                exhausted = repair(service, helper, unlocked) == Result.GaveUp
            } finally {
                _phase.value = RepairPhase.Idle
            }
        }
    }

    private fun eligible(s: A11yObservation, h: BuiltinShellStatus, unlocked: Boolean) =
        // Never turn accessibility on automatically while paused: pausing turns it off itself, so repairing it automatically would cancel the pause.
        // After resuming, repair as usual; if repair fails, the recovery page asks the user to turn it on in system settings.
        allowed() && !paused() && s.stuck && unlocked && h != BuiltinShellStatus.Paused &&
            (direct() || h == BuiltinShellStatus.Running) && consent().wants(RecoveryCapability.Accessibility)

    private suspend fun repair(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean>,
    ): Result {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(BACKOFF_MS * (attempt - 1))
                val s = service.value
                if (s.connected) return Result.Restored.also { record(true, attempt - 1) }
                if (!eligible(s, helper.value, unlocked.value)) return Result.Interrupted
            }
            if (restart(GAP_MS * attempt) == PermissionResult.Done &&
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.first { it.connected } } != null
            ) {
                return Result.Restored.also { record(true, attempt) }
            }
        }
        record(false, MAX_ATTEMPTS)
        return Result.GaveUp
    }

    private fun record(restarted: Boolean, attempts: Int) {
        _last.value = RepairOutcome(restarted, now(), attempts)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_ATTEMPTS = 2
        /** Pause between the removal and the re-adding, growing each time (1 s, 2 s): when two writes are too close together, the system may only see the final list. */
        const val GAP_MS = 1_000L
        const val BACKOFF_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** The longest it takes from getting stuck to giving up: the initial wait, two remove-and-re-add rounds each waiting for the connect, one backoff in between, plus some margin for running commands. */
        const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * CONNECT_TIMEOUT_MS + GAP_MS * 3 + BACKOFF_MS + 2_000L

        /**
         * Waits for the accessibility service when it is needed: first waits [baseMs] as usual; if it is still not connected but a repair is pending or under way ([phase] is not Idle),
         * keep waiting until it connects or the repair side stops (on success the service is already connected; on giving up it returns null), never exceeding [capMs] in total.
         * With nothing to repair it behaves as before and returns null once [baseMs] has elapsed.
         */
        suspend fun <T : Any> awaitService(
            service: StateFlow<T?>,
            phase: StateFlow<RepairPhase>,
            baseMs: Long,
            capMs: Long = WORST_CASE_MS,
        ): T? {
            withTimeoutOrNull(baseMs) { service.filterNotNull().first() }?.let { return it }
            if (phase.value == RepairPhase.Idle) return service.value
            return withTimeoutOrNull((capMs - baseMs).coerceAtLeast(0)) {
                combine(service, phase, ::Pair).first { (s, p) -> s != null || p == RepairPhase.Idle }.first
            }
        }
    }
}
