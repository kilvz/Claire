package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Totals of metered model calls and actual HTTP requests within one run. The run puts this into its own coroutine context, and
 * every model wrapped by [metered] adds an entry when a call ends, so bypass calls outside the main loop (intent detection,
 * verification, image viewing, compaction, …) and every retried attempt count toward this run; a compatibility resend inside the
 * adapter increases the actual request count but not the logical call count. Calls outside this context (outcome decision after
 * the answer is handed over, memory extraction) do not count, though the token usage page still records them all. Accumulated in
 * memory only; safe across threads.
 */
class RunUsageTally(val tokenBudget: Long? = null, val deepSeekCost: DeepSeekRunCost? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    init { require(tokenBudget == null || tokenBudget > 0) }

    private val logicalCalls = AtomicInteger()
    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val httpUnreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()

    /** Records one logical call and its [httpAttempts] actual requests; only the [usage] of the final successful reply may record tokens. */
    fun add(usage: Usage?, httpAttempts: Int) {
        logicalCalls.incrementAndGet()
        if (usage == null) unreported.incrementAndGet()
        val attempts = httpAttempts.coerceAtLeast(0)
        calls.addAndGet(attempts)
        httpUnreported.addAndGet((attempts - if (usage == null) 0 else 1).coerceAtLeast(0))
        if (usage == null) return
        input.addAndGet(usage.inputTokens.toLong())
        output.addAndGet(usage.outputTokens.toLong())
        cached.addAndGet(usage.cachedInputTokens.toLong())
    }

    /** Reported input plus output; cache hits are already included in input and must not be added again. */
    fun totalTokens(): Long = input.get() + output.get()

    /** Called before the next actual request; once the budget is reached, no wrapping up, retry or bypass call may keep consuming. */
    fun checkBudget() {
        val limit = tokenBudget ?: return
        val used = totalTokens()
        if (used >= limit) throw ModelError.TaskBudgetExceeded(limit, used)
    }

    /** The totals at this moment, as one [RunEvent.ModelCalls] entry in the run record. */
    fun event(atMs: Long, cost: DeepSeekCost? = null) = RunEvent.ModelCalls(
        atMs, logicalCalls.get(), unreported.get(), input.get(), output.get(), cached.get(), calls.get(), httpUnreported.get(), deepSeekCost = cost,
    )
}
