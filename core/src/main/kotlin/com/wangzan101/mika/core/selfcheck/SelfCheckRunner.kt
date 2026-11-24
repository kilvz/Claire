package com.wangzan101.mika.core.selfcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What one self-check looks like right now: [results] follows the order of [SelfCheck], one entry per item. */
data class SelfCheckState(
    val phase: Phase = Phase.Idle,
    val results: List<CheckResult> = SelfCheck.entries.map { CheckResult(it) },
    val facts: DeviceFacts? = null,
    val elapsedMs: Long = 0,
) {
    enum class Phase { Idle, Running, Done, Cancelled }

    fun of(check: SelfCheck): CheckResult = results[check.ordinal]

    /** Finished or cancelled: the report can be produced. */
    val ended: Boolean get() = phase == Phase.Done || phase == Phase.Cancelled
}

/**
 * Run every item of [SelfCheck] in order: if a required item did not pass, skip this one and state which item is missing; each item has its own time limit, and the whole run has a total limit [budgetMs],
 * after which the remaining items are all recorded as skipped. Errors are recorded as failures and do not stop later items. Whether the run finished, errored or was cancelled, [SelfCheckPort.release] is called at the end.
 * [state] reports progress at any time, and the UI draws from it. An instance runs only once.
 */
class SelfCheckRunner(
    private val port: SelfCheckPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = TOTAL_BUDGET_MS,
) {
    private val _state = MutableStateFlow(SelfCheckState())
    val state: StateFlow<SelfCheckState> = _state.asStateFlow()

    suspend fun run(): SelfCheckState {
        val start = now()
        _state.value = SelfCheckState(SelfCheckState.Phase.Running)
        try {
            for (c in SelfCheck.entries) step(c, start)
            finish(SelfCheckState.Phase.Done, start)
        } catch (e: CancellationException) {
            finish(SelfCheckState.Phase.Cancelled, start)
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { port.release() } }
        }
        return _state.value
    }

    private suspend fun step(c: SelfCheck, start: Long) {
        val results = _state.value.results
        c.needs.firstOrNull { !results[it.ordinal].status.passed }?.let { blocker ->
            set(CheckResult(c, CheckStatus.Skipped, "needs ${blocker.key} (${results[blocker.ordinal].status.name.lowercase()})", blockedBy = blocker))
            return
        }
        val left = budgetMs - (now() - start)
        if (left <= 0) {
            set(CheckResult(c, CheckStatus.Skipped, "out of time", Advice.OutOfTime))
            return
        }
        set(CheckResult(c, CheckStatus.Running))
        val t0 = now()
        val limit = minOf(c.timeoutMs, left)
        val outcome = try {
            withTimeoutOrNull(limit) { if (c == SelfCheck.DEVICE) device() else port.check(c) }
                ?: CheckOutcome.fail("timed out after ${seconds(limit)} s", Advice.TimedOut)
        } catch (e: TimeoutCancellationException) {
            // The port's own time limit expired (this self-check was not cancelled)
            CheckOutcome.fail("timed out inside the check", Advice.TimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckOutcome.fail("error: ${e.javaClass.simpleName}", Advice.Error)
        }
        set(CheckResult(c, outcome.status, outcome.detail, outcome.advice, durationMs = now() - t0))
        _state.value = _state.value.copy(elapsedMs = now() - start)
    }

    private suspend fun device(): CheckOutcome {
        val f = port.facts()
        _state.value = _state.value.copy(facts = f)
        return judgeDevice(f)
    }

    private fun set(r: CheckResult) {
        val s = _state.value
        _state.value = s.copy(results = s.results.map { if (it.check == r.check) r else it })
    }

    /** Finish: everything that has no conclusion yet (the one running at cancellation and all after it) is recorded as skipped. */
    private fun finish(phase: SelfCheckState.Phase, start: Long) {
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            elapsedMs = now() - start,
            results = s.results.map { if (it.status.final) it else CheckResult(it.check, CheckStatus.Skipped, "cancelled", Advice.Cancelled) },
        )
    }

    companion object {
        /** Total time limit for the whole self-check: the per-item limits add up to more than this, but normally it is far from exhausted. */
        const val TOTAL_BUDGET_MS = 90_000L

        private fun seconds(ms: Long) = (ms + 999) / 1000

        /**
         * The "device" item: below Android 12 phone control is unavailable; it is also unavailable when this environment has no way to start the helper;
         * without wireless debugging only starting from a computer is possible, which counts as partially usable.
         */
        fun judgeDevice(f: DeviceFacts): CheckOutcome {
            val detail = "Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}" +
                ", wireless debugging ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}"
            return when {
                f.sdk < DeviceFacts.MIN_PHONE_SDK -> CheckOutcome.fail("$detail; phone control needs Android 12+", Advice.PhoneTooOld)
                f.phoneControlUnavailable -> CheckOutcome.fail(
                    "$detail, developer options page missing; phone control unavailable in this environment", Advice.PhoneControlUnavailable,
                )
                f.wirelessDebugging == false -> CheckOutcome.partial(detail, Advice.NoWirelessDebugging)
                else -> CheckOutcome.pass(detail)
            }
        }
    }
}
