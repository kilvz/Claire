package com.wangzan101.mika.core.model.usage

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The number of inference requests actually handed to the HTTP client within one logical model call.
 * Both compatibility retries inside the adapter and retries in the outer layer pass through the same counting point; it exists
 * only in the coroutine context of the current call and is not accumulated across calls.
 */
internal class ModelHttpAttempts(
    /** Called before the second and later requests of the same logical call are sent (argument: how many have already been sent); throwing prevents the request. */
    private val beforeRetry: ((started: Int) -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelHttpAttempts>

    private val count = AtomicInteger()
    val started: Int get() = count.get()

    fun start() {
        count.get().let { if (it > 0) beforeRetry?.invoke(it) }
        count.incrementAndGet()
    }
}
