package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lifecycle control and user interaction for one task (cancel, takeover, Q&A, confirmation, teardown). The corresponding functions from the original TaskRun were moved here wholesale.
 * The transient TaskRun fields it needs to read/write are injected as lambdas.
 */
internal class TaskRunControl(
    private val service: TaskService,
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val state: TaskRunState,
    private val inbox: RunInbox,
    private val loop: () -> AgentLoop?,
    private val gate: () -> TaskGate?,
    private val takeover: () -> Takeover?,
    private val children: () -> ChildAgentRunner?,
    private val job: () -> Job?,
    private val cancelRequested: () -> Boolean,
    private val setCancelRequested: (Boolean) -> Unit,
    private val shownRef: () -> Boolean,
    private val publish: (TaskState) -> Unit,
    private val key: String,
    private val setStopPressed: (Boolean) -> Unit,
) {
    fun cancel() {
        setStopPressed(true)
        children()?.cancelAll(RunId(key))
        val running = loop()
        val t = takeover()
        when {
            t != null -> scope.launch { t.cancel() }
            running != null -> inbox.cancel()
            else -> {
                // Cancel tapped right after being released from the queue but before the coroutine is attached: cancel as soon as it gets attached
                setCancelRequested(true)
                job()?.cancel()
            }
        }
    }

    fun resume() {
        val g = gate() ?: return
        val wasOnPage = g.userOnPagePending
        g.resume()
        // A pause caused by the user operating on the page: the loop may not have reached the gate yet (still thinking), so re-display its current state and stop showing it as paused
        if (wasOnPage) loop()?.state?.value?.takeIf { it is TaskState.Running }?.let(publish)
    }

    fun takeOver(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.handOver(waitKey) } } }
    fun handBack(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.handBack(waitKey) } } }
    fun finishedMyself(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.finishedMyself(waitKey) } } }
    fun skip(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.skip(waitKey) } } }
    fun putAside(waitKey: Int?) { takeover()?.let { t -> scope.launch { t.putAside(waitKey) } } }

    /** Answer the question; when it is no longer waiting (answered after the timeout, or already answered), call [tooLate] so the user learns this answer went unused. */
    fun answer(text: String, tooLate: () -> Unit) {
        if (loop() == null) return
        if (state._state.value is TaskState.WaitingInput) scope.launch {
            if (inbox.send(RunInput.Answer(text)) != InputReceipt.Accepted) tooLate()
        }
        else tooLate()
    }

    /** The channel has already verified the question ID; the answer still goes through the same inbox so the run lifecycle isn't bypassed. */
    suspend fun answerFromChannel(text: String, askId: String): Boolean =
        inbox.send(RunInput.Answer(text, askId)) == InputReceipt.Accepted

    /** A new instruction supersedes an ordinary question that hasn't been answered yet; the old task tears down as cancelled and the queued new instruction runs afterwards. */
    fun cancelAsk(askId: String): Boolean = loop()?.cancelInput(askId) == true

    val activeAsk: Delivery.Ask? get() = loop()?.activeAsk

    /** The notification and card buttons carry the request [key] they belong to; taps on an already replaced old notification or old card don't count. */
    fun confirm(choice: ConfirmChoice, key: Int?) {
        val running = loop()
        val waiting = state._state.value as? TaskState.WaitingConfirmation
        if (running != null && waiting != null && key == ConfirmationNotifier.requestKey(waiting)) {
            scope.launch { inbox.send(RunInput.Confirm(choice)) }
        }
    }

    /**
     * The task's terminal state: written only after [execute] has finished its teardown, bypassing [publish]. Whether or not this task is the displayed one, its run ID is first recorded under
     * its own conversation ([TaskService.endedRunIds]) so the UI can look it up by conversation when the end state arrives; when it is the displayed task, [TaskService.endedRunId] is written too.
     */
    fun end(end: TaskState) = synchronized(service.taskState.shownLock) {
        service.taskState._endedRunIds.value = service.taskState._endedRunIds.value + (conversationId to state._runId.value)
        if (shownRef()) service.taskState._endedRunId.value = state._runId.value
        state.setState(end)
    }

    /** Queued because the parallelism limit was exceeded; [dequeue] is called when it is released to start. */
    fun queue(position: Int) { state.setState(TaskState.Held(GateReason.queued(trPlural(R.plurals.queue_ahead, position)))) }
    fun dequeue() { state.setState(TaskState.Running(0, TaskService.PREPARING)) }

    /** The explanation at the top of the web takeover UI: the takeover reason, plus item, amount and address when it is a payment. */
    fun takeoverMessage(state: TaskState): String? {
        val w = state as? TaskState.WaitingTakeover ?: return null
        return listOfNotNull(
            w.message.takeIf { it.isNotBlank() },
            w.item?.let { tr(R.string.payment_item, it) },
            w.amount?.let { tr(R.string.payment_amount, it) },
            w.address?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }
    }
}
