package com.wangzan101.mika.core.net

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide shared "rate-limit degradation": zero overhead in the normal case; only after hitting an HTTP 429 do subsequent requests wait out a cooldown window, and a success immediately restores full speed.
 * Upgrades "back off per request, give up after a few tries" to "wait out the rate-limit window uniformly across tasks and subagents".
 *
 * Each key stores an absolute deadline on the monotonic clock. When a task is cancelled the deadline still expires naturally, so later tasks do not inherit an already-expired cooldown duration.
 * Multiple tasks each wait for the same deadline, approximately sharing the cooldown and keeping concurrent tasks from collectively hammering the provider.
 *
 * [providerKey] is approximated by the model name for now: different models of the same provider each compute their own cooldown (on the conservative side). Isolating precisely per provider
 * would require threading the provider identity all the way through, which the first version does not do.
 */
internal object RateLimitGovernor {
    private class State {
        @Volatile var cooldownUntilMs = 0L
        @Volatile var consecutive429 = 0
    }

    private val states = ConcurrentHashMap<String, State>()
    private val systemMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }
    @Volatile private var nowMs: () -> Long = systemMonotonicMs

    /** Called before sending a request: waits only until the current absolute deadline, then clears it once expired; returns immediately when not cooling down (zero overhead). */
    suspend fun awaitClear(providerKey: String) {
        val s = states[providerKey] ?: return
        while (true) {
            val until = s.cooldownUntilMs
            val wait = until - nowMs()
            if (wait <= 0) {
                s.cooldownUntilMs = 0
                return
            }
            delay(wait)
            // A normal delay waits until the deadline. Clear only when no concurrent extension of the cooldown happened, so as not to overwrite a later deadline another coroutine just wrote.
            if (s.cooldownUntilMs == until) {
                s.cooldownUntilMs = 0
                return
            }
        }
    }

    /** Hit a 429: record the absolute cooldown deadline and return this round's wait (ms). With a Retry-After, follow it (capped at [RETRY_AFTER_CAP], and the backoff chain resets to zero); without one, back off exponentially by consecutive 429 count. */
    fun reportRateLimited(providerKey: String, retryAfterMs: Long?): Long {
        val s = states.computeIfAbsent(providerKey) { State() }
        val wait = if (retryAfterMs != null) {
            s.consecutive429 = 0
            retryAfterMs.coerceIn(MIN_RETRY_AFTER_MS, RETRY_AFTER_CAP)
        } else {
            val n = s.consecutive429 + 1
            s.consecutive429 = n
            backoff(n)
        }
        s.cooldownUntilMs = nowMs() + wait
        return wait
    }

    /** Success (2xx): clear the cooldown and reset the consecutive 429 count, back to full speed. */
    fun reportSuccess(providerKey: String) {
        states[providerKey]?.let {
            it.cooldownUntilMs = 0
            it.consecutive429 = 0
        }
    }

    /** For tests: clears all cooldown state and optionally injects a monotonic clock. */
    internal fun reset(clock: () -> Long = systemMonotonicMs) {
        states.clear()
        nowMs = clock
    }

    /** Backoff when there is no Retry-After: 1s→2s→4s→8s→16s→32s, with anything beyond capped by [MAX_BACKOFF_MS]. */
    private fun backoff(n: Int): Long {
        var ms = BASE_BACKOFF_MS
        repeat(minOf(n - 1, 6)) { ms *= 2 }
        return minOf(ms, MAX_BACKOFF_MS)
    }

    private const val MIN_RETRY_AFTER_MS = 1_000L
    private const val RETRY_AFTER_CAP = 30_000L
    private const val BASE_BACKOFF_MS = 1_000L
    private const val MAX_BACKOFF_MS = 60_000L
}
