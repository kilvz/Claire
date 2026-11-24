package com.wangzan101.mika.core.run

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * A group of system resources that live and die together (virtual screen, web display, etc.): created with [acquire], registered on the spot once created, and released in reverse registration order by [close].
 * "Create, hand back to the caller, then record in a field" has a suspension point in between; if the task is cancelled right there, nobody remembers to release what was created. Registering inside the same section as the creation leaves no such gap.
 * Usable from any thread; each resource is released exactly once and [close] may be called repeatedly.
 */
class ResourceScope {
    private class Entry(val context: CoroutineContext, val release: () -> Unit)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var closed = false

    /**
     * Calls [create] in [context], registers the result immediately, and at teardown calls [release] in [context] as well.
     * Once created, the resource belongs to the scope even if the task is then cancelled (the cancellation is thrown when returning to the caller). If the scope is already closed, whatever was created is released at once and [IllegalStateException] is thrown.
     */
    suspend fun <T> acquire(context: CoroutineContext, release: (T) -> Unit, create: suspend () -> T): T =
        withContext(context) {
            val made = create()
            val owned = synchronized(lock) { if (!closed) entries += Entry(context) { release(made) }; !closed }
            if (!owned) {
                release(made)
                throw IllegalStateException("resource scope already closed")
            }
            made
        }

    /** Releases everything in reverse registration order; one failure does not affect the rest, and the first error is thrown after all are released. Not interrupted by cancellation. */
    suspend fun close() {
        val todo = synchronized(lock) {
            closed = true
            entries.asReversed().toList().also { entries.clear() }
        }
        var failure: Throwable? = null
        for (e in todo) {
            try {
                withContext(e.context + NonCancellable) { e.release() }
            } catch (t: Throwable) {
                failure?.addSuppressed(t) ?: run { failure = t }
            }
        }
        failure?.let { throw it }
    }
}
