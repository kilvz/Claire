package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunInbox
import java.util.UUID

/**
 * Sending, receiving, routing and withdrawal of messages injected while a task is running. The steering logic from the original TaskRun was moved here wholesale.
 * The transient TaskRun fields it needs ([loop]/[stopPressed]/[mcpExternalId]) are injected as lambdas that read the current value.
 */
internal class SteerCoordinator(
    private val service: TaskService,
    private val conversationId: String,
    private val state: TaskRunState,
    private val inbox: RunInbox,
    private val loop: () -> AgentLoop?,
    private val stopPressed: () -> Boolean,
    private val mcpExternalId: () -> String?,
) {
    /** The stretch of reply that a steer cut off, attached to the first steer of the batch when it is handed to the model. */
    private val steerJoin = SteerJoin()

    /**
     * New user text in the same conversation while the task is running, handed to the next model step; during the preparation phase it is also queued in the Inbox first.
     * [messageId] is the ID the UI assigned to this message; the UI uses it to show where the message went (see [SteerNote]).
     */
    fun steer(text: String, messageId: String = UUID.randomUUID().toString()) {
        synchronized(service.taskState.shownLock) {
            state._steers.value = state._steers.value + SteerNote(messageId, text, SteerState.Pending)
            // Tell the user as soon as the inbox accepts it: the model's current request has been cancelled and the next step will think with this message (changed to joined when handed to the model)
            if (inbox.steer(messageId, text) == InputReceipt.Accepted) markSteers(setOf(messageId), SteerState.Received, from = SteerState.Pending)
            else {
                if (stopPressed()) markSteers(setOf(messageId), SteerState.Cancelled)
                else {
                    markSteers(setOf(messageId), SteerState.Next)
                    service.deferMessage(conversationId, text, messageId)
                }
            }
        }
    }

    /** A remote addition belongs to the current task only; this is mutually exclusive with state publishing, and the inbox then atomically verifies whether the run has already ended/been cancelled. */
    fun steerRemote(text: String): String? = synchronized(state.publishLock) delivery@{
        if (state.ended || stopPressed() || mcpExternalId() == null || state._state.value !is TaskState.Running) return@delivery null
        if (loop()?.state?.value?.let { it !is TaskState.Running } == true) return@delivery null
        val id = UUID.randomUUID().toString()
        synchronized(service.taskState.shownLock) {
            // Record the message first, then wake the model; the consumption event may follow immediately, so Joined must not be turned back into Received.
            state._steers.value = state._steers.value + SteerNote(id, text, SteerState.Received, remote = true)
            if (inbox.steerCurrent(id, text) != InputReceipt.Accepted) {
                state._steers.value = state._steers.value.filterNot { it.id == id }
                return@delivery null
            }
            id
        }
    }

    /** Withdraw a message injected from the phone that the model hasn't taken yet; returns false and leaves the routing unchanged if it was already taken, the run has finished, or it was a remote addition (rejected by the inbox). */
    fun withdrawSteer(messageId: String): Boolean = synchronized(service.taskState.shownLock) {
        if (!inbox.withdrawSteer(messageId)) return@synchronized false
        markSteers(setOf(messageId), SteerState.Withdrawn)
        true
    }

    /** Steers from the phone can carry over to the next task; remote steers are only good for the current task, so any unconsumed ones are discarded, without changing their origin or permissions. */
    fun handoffUnconsumed(cancelled: Boolean = stopPressed()) {
        val left = inbox.handoffUnconsumed()
        val (discard, next) = left.partition { cancelled || it.currentTaskOnly }
        markSteers(discard.map { it.messageId }.toSet(), SteerState.Cancelled)
        markSteers(next.map { it.messageId }.toSet(), SteerState.Next)
        next.forEach { service.deferMessage(conversationId, it.text, it.messageId) }
    }

    fun joinedSteers(): List<String> = joined().map { it.text }

    /** One-to-one with [joinedSteers]: the stretch of reply each steer interrupted, empty string when it interrupted nothing. */
    fun joinedInterrupted(): List<String> = joined().map { it.interrupted.orEmpty() }

    /** One-to-one with [joinedSteers]: the stretch of reply already finished and shown before each steer was handed to the model, empty string when there is none. */
    fun joinedFinished(): List<String> = joined().map { it.finished.orEmpty() }

    private fun joined() = state._steers.value.filter { it.state == SteerState.Joined }

    private fun joinSteer(id: String) {
        synchronized(service.taskState.shownLock) { state._steers.value = steerJoin.joined(state._steers.value, id) }
    }

    /** Set the [ids] entries to [to]; when [from] is given only those still in that state are changed (acceptance and handoff to the model can arrive out of order, so never move backwards). */
    private fun markSteers(ids: Set<String>, to: SteerState, from: SteerState? = null) {
        if (ids.isEmpty()) return
        synchronized(service.taskState.shownLock) {
            state._steers.value = state._steers.value.map { if (it.id in ids && (from == null || it.state == from)) it.copy(state = to) else it }
        }
    }

    // Delegates for TaskRun's liveEvents (steering-related events)
    fun onInterrupted(detail: String) { steerJoin.interrupted(detail) }
    fun onFinished(detail: String) { steerJoin.finished(detail) }
    fun onSteerReceived(detail: String) { joinSteer(detail) }
}
