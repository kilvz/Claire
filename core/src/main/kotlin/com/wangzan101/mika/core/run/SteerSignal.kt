package com.wangzan101.mika.core.run

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The "a user steer has not been handed to the model yet" signal for this run (provided by [RunInbox]), carried in the run's coroutine context.
 * Calls that lose nothing when cancelled (pure waits, the pause and gap before an action, asking only the image-reading model) are wrapped in [waitOrSteer] and end early as soon as a steer arrives,
 * so the model reads that message right away; actions already under way are unaffected.
 */
class SteerSignal internal constructor(internal val pending: StateFlow<Boolean>) : AbstractCoroutineContextElement(SteerSignal) {
    companion object Key : CoroutineContext.Key<SteerSignal>
}

/** The error code a cancelled call gives the model: nothing was done this time (a wait was cut short, the pause before an action did not finish), so read the user's new message first. */
const val SKIPPED_FOR_NEW_MESSAGE = "skipped_for_new_message"

/**
 * Runs a [block] that loses nothing when cancelled: if a user steer is already waiting or arrives while it runs, it is cancelled and null is returned; otherwise its result is returned.
 * Outside a run (no [SteerSignal]) it simply runs to completion.
 */
suspend fun <T : Any> waitOrSteer(block: suspend () -> T): T? {
    val pending = coroutineContext[SteerSignal]?.pending ?: return block()
    if (pending.value) return null
    return coroutineScope {
        val work = async { block() }
        val steer = async { pending.first { it } }
        select<T?> {
            work.onAwait { steer.cancel(); it }
            steer.onAwait { work.cancel(); null }
        }
    }
}
