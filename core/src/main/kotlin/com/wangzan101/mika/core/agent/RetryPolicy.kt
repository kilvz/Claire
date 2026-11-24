package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelErrors
import kotlin.random.Random

/**
 * Whether a model call should be retried after a transient failure, and how long to wait. Transient failures: rate
 * limiting (429), server errors (5xx), cannot connect / connection dropped / timeout, and a streaming reply whose
 * connection dropped before it finished (the reply is not yet in history and no tool has run, so resending is safe).
 * Other errors (key, quota, model name, content refusal, other 4xx, unparseable) would fail the same way on retry and
 * are not retried; context-too-long is handled separately by compaction.
 * Rate limiting that carries Retry-After waits as told (with a cap); everything else uses exponential backoff plus
 * jitter, retrying at most [maxRetries] times per call.
 * The total time one task spends waiting for retries is capped by [taskWaitBudgetMs] (5 minutes by default, just a
 * safety net): once it is used up, the next error is handed out immediately without waiting.
 * Pure functions: jitter comes from [jitter] (0..1) and is replaced with a fixed value in tests.
 */
class RetryPolicy(
    val maxRetries: Int = 3,
    private val baseMs: Long = 1_000,
    private val maxBackoffMs: Long = 8_000,
    private val retryAfterCapMs: Long = 30_000,
    /** The cap on all retry waits added together within one task. */
    val taskWaitBudgetMs: Long = 5 * 60_000,
    private val jitter: () -> Double = { Random.nextDouble() },
) {
    init {
        require(maxRetries >= 0 && baseMs > 0 && maxBackoffMs >= baseMs && retryAfterCapMs >= MIN_RETRY_AFTER_MS && taskWaitBudgetMs >= 0)
    }

    /**
     * After this call has already retried [retriesSoFar] times and hits [e] again, with [waitedInTaskMs] already spent
     * waiting on retries in this task: returns the milliseconds to wait before retrying, or null when it should not be
     * retried (not a transient failure, attempts exhausted, or this wait would exceed the task's wait budget).
     * [maxRetries] may be lowered temporarily for this one call (with another member in the group, the same member is
     * tried only once).
     */
    fun delayFor(e: Throwable, retriesSoFar: Int, waitedInTaskMs: Long = 0, maxRetries: Int = this.maxRetries): Long? {
        if (retriesSoFar >= maxRetries || !transient(e)) return null
        val wait = (e as? ModelError.RateLimited)?.retryAfterMs?.coerceIn(MIN_RETRY_AFTER_MS, retryAfterCapMs) ?: run {
            val ceiling = minOf(maxBackoffMs, baseMs shl minOf(retriesSoFar, 20))
            // Half fixed, half random: several tasks failing at once do not hit it again at the same instant
            ceiling / 2 + (ceiling / 2 * jitter().coerceIn(0.0, 1.0)).toLong()
        }
        return wait.takeIf { waitedInTaskMs + it <= taskWaitBudgetMs }
    }

    companion object {
        private const val MIN_RETRY_AFTER_MS = 1_000L

        /** No retries: hand the error out on failure (when the light model fails, this step asks the primary model directly). */
        val NONE = RetryPolicy(maxRetries = 0)

        /** A transient failure: sending the same request again after a while may just work. */
        fun transient(e: Throwable): Boolean = ModelErrors.transient(e)

        /** The name of this failure in the run log. */
        fun label(e: Throwable): String = when (e) {
            is ModelError.RateLimited -> "rate_limited"
            is ModelError.Network -> "network"
            is ModelError.StreamCut -> "stream_cut"
            is ModelError.Server -> "server_${e.status}"
            is ModelError.Proxy -> if (e.failure == ProxyFailure.Unreachable) "proxy_unreachable" else "proxy_auth"
            else -> e::class.simpleName.orEmpty()
        }
    }
}
