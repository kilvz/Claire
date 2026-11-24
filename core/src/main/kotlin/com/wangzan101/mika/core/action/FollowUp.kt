package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonObject

/** The hooks the caller inserts into the execution entry point (used only by model single steps): resolving outcomes that wait on the user, and the final shaping before handing off to the model. */
interface FollowUp {
    /** After execution and before the guard review: replaces an outcome that waits on the user (confirmation, follow-up question, hand-over) with the final result; null means the task is cancelled at this point. */
    suspend fun resolve(exec: ToolExecution): ToolExecution? = exec

    /** After the guard review and before recording: returns the final result and the content handed to the model (which is also recorded in the run record). */
    suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented = Presented(exec, exec.content)

    /** Blocked by the confirmation gate: after the explanation is given to the model and before it is recorded in the run record. */
    fun refused(content: JsonObject) {}

    /**
     * An off-screen step that the tool declares must be asked about ([request] is the declaration shown to the user): ask the user on the spot and return the user's decision (ok is true when approved,
     * and that approval has been recorded in the confirmation gate); null when there is no one to ask (the step is then not done).
     */
    suspend fun approve(request: ConfirmationRequest): ToolExecution? = null

    companion object {
        val NONE = object : FollowUp {}
    }
}

data class Presented(val exec: ToolExecution, val content: JsonObject)
