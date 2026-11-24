package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.Turn

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/** The immutable opening information of one run. Later steps add capabilities, resource budgets and approval snapshots. */
data class RunSpec(
    val id: RunId,
    val goal: String,
    val threadId: String,
    val priorTurns: List<Turn> = emptyList(),
    val provenance: RunProvenance = RunProvenance(),
    /** null means the default capabilities; a child Run may provide a strict subset. */
    val capabilities: Set<String>? = null,
    /** A condition-check Run may only read; web navigation is excepted as a preparatory action for obtaining the object to read. */
    val readOnly: Boolean = false,
    val parent: RunId? = null,
    val stepBudget: Int? = null,
    /** The reported-token budget shared by the main loop, side calls and child tasks; null means unlimited. */
    val tokenBudget: Long? = null,
    /** A child task dispatched in parallel by the same batch; the host allocates stateful resources for it separately. */
    val parallelChild: Boolean = false,
) {
    init {
        require(goal.isNotBlank() && threadId.isNotBlank())
        require(tokenBudget == null || tokenBudget > 0)
    }
}

/** Input sent to a run from this conversation. Cancellation goes through the control channel and never waits in the steer queue. */
sealed interface RunInput {
    /**
     * A steer is a supplement: the pending model request is cancelled and asked again after the existing history (everything already done is kept); a tool that is executing finishes as usual and its result is still recorded,
     * and waits that lose nothing when cancelled end early (see [waitOrSteer]).
     */
    data class Steer(val messageId: String, val text: String) : RunInput
    /** Takes the same path as [Steer] (the name of the old entry point). */
    data class Redirect(val messageId: String, val text: String) : RunInput
    data class Answer(val text: String, val askId: String? = null) : RunInput
    data class Confirm(val choice: ConfirmChoice, val askId: String? = null) : RunInput
    data class HandBack(val result: com.wangzan101.mika.core.tool.HandBack, val askId: String? = null) : RunInput
    data object Cancel : RunInput
}

enum class InputReceipt { Accepted, NoPendingInteraction, NextTask, Closed }

sealed interface RunPhase {
    data object Queued : RunPhase
    data object Preparing : RunPhase
    data object Running : RunPhase
    data class WaitingUser(val askId: String? = null) : RunPhase
    data class Parked(val askId: String) : RunPhase
    data class Paused(val reason: String) : RunPhase
    data object Finishing : RunPhase
    data object Aftercare : RunPhase
    data object Ended : RunPhase
    data object Interrupted : RunPhase
}
