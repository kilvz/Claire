package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.tool.HandBack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/** The single entry point for input during a run; the user's own words queue without limit and are kept verbatim, while stop signals are delivered directly. */
class RunInbox {
    data class UserSteer(val messageId: String, val text: String, val currentTaskOnly: Boolean = false)

    internal interface Handler {
        suspend fun answer(text: String, askId: String?): Boolean
        suspend fun confirm(choice: ConfirmChoice, askId: String?): Boolean
        suspend fun handBack(result: HandBack, askId: String?): Boolean
        fun cancel()
    }

    private val lock = Any()
    private val steers = ArrayDeque<UserSteer>()
    @Volatile private var handler: Handler? = null
    @Volatile private var closed = false
    @Volatile private var stopRequested = false
    private var remaining: List<UserSteer> = emptyList()
    /** Every steer wakes the model request that is waiting (cancels it and asks again with this message); a tool that is executing is unaffected. */
    internal val redirects = Channel<Unit>(Channel.CONFLATED)
    private val pending = MutableStateFlow(false)
    /** A steer has not been handed to the model yet: waits that lose nothing when cancelled end early because of it (see [waitOrSteer]). */
    val signal = SteerSignal(pending)

    internal fun bind(to: Handler) {
        handler = to
        if (stopRequested) to.cancel()
    }

    val wasCancelled: Boolean get() = stopRequested

    fun cancel(): InputReceipt {
        val to = synchronized(lock) {
            if (closed) return InputReceipt.Closed
            stopRequested = true
            handler
        }
        to?.cancel()
        return InputReceipt.Accepted
    }

    /** A remote append belongs to the current task only; it is mutually exclusive with closing and cancellation, and when rejected it does not turn into the next task. */
    fun steerCurrent(messageId: String, text: String): InputReceipt = synchronized(lock) {
        if (closed || stopRequested) InputReceipt.Closed
        else enqueue(UserSteer(messageId, text, currentTaskOnly = true))
    }

    suspend fun send(input: RunInput): InputReceipt = when (input) {
        is RunInput.Steer -> steer(input.messageId, input.text)
        is RunInput.Redirect -> steer(input.messageId, input.text)
        RunInput.Cancel -> cancel()
        is RunInput.Answer -> if (closed) InputReceipt.Closed else
            if (handler?.answer(input.text, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.Confirm -> if (closed) InputReceipt.Closed else
            if (handler?.confirm(input.choice, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
        is RunInput.HandBack -> if (closed) InputReceipt.Closed else
            if (handler?.handBack(input.result, input.askId) == true) InputReceipt.Accepted else InputReceipt.NoPendingInteraction
    }

    fun steer(id: String, text: String): InputReceipt = synchronized(lock) {
        if (closed) InputReceipt.NextTask else enqueue(UserSteer(id, text))
    }

    private fun enqueue(steer: UserSteer): InputReceipt {
        steers += steer
        pending.value = true
        redirects.trySend(Unit)
        return InputReceipt.Accepted
    }

    /** Withdraws only messages still in the inbox that were inserted on the phone; mutually exclusive with handing to the model and with wrap-up. What the desktop appended ([UserSteer.currentTaskOnly]) cannot be withdrawn and is decided on the desktop side. */
    fun withdrawSteer(id: String): Boolean = synchronized(lock) {
        val i = steers.indexOfFirst { it.messageId == id }
        if (closed || i < 0 || steers[i].currentTaskOnly) return@synchronized false
        steers.removeAt(i)
        pending.value = steers.isNotEmpty()
        if (steers.isEmpty()) while (redirects.tryReceive().isSuccess) Unit
        true
    }

    /** Whether there is a steer not yet handed to the model; peeks without taking (batch execution stops between sub-steps based on it). */
    fun hasSteers(): Boolean = synchronized(lock) { steers.isNotEmpty() }

    /** Taken only before a model call or after a batch of tools completes; clears redirect wake-ups that are no longer needed. */
    internal fun takeSteers(): List<UserSteer> = synchronized(lock) {
        val out = steers.toList()
        steers.clear()
        pending.value = false
        while (redirects.tryReceive().isSuccess) Unit
        out
    }

    /** Steers not yet consumed at wrap-up are handed to the host as the next task. */
    internal fun close(): List<UserSteer> = synchronized(lock) {
        if (closed) return@synchronized remaining
        closed = true
        handler = null
        redirects.close()
        pending.value = false
        remaining = steers.toList().also { steers.clear() }
        remaining
    }

    /**
     * Called when handing over the answer and wrapping up: if there is still a steer not handed to the model, it does not close (returns false, and the caller carries on with it);
     * otherwise it closes (returns true) and only later steers become the next task. The check and the close are in the same lock, so no message can slip in between.
     */
    internal fun closeUnlessSteered(): Boolean = synchronized(lock) {
        if (steers.isNotEmpty()) return@synchronized false
        close()
        true
    }

    fun nextTaskMessages(): List<UserSteer> = synchronized(lock) { remaining.toList() }

    /** When the host wraps up or cancels a task that has not started running, hands the unconsumed user words to the next task. */
    fun handoffUnconsumed(): List<UserSteer> = synchronized(lock) {
        close()
        remaining.also { remaining = emptyList() }
    }
}
