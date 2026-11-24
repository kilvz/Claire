package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.verify.AnswerSource

/** Numbered in first-round checklist order (starting at 1); includes only goals the model has confirmed with evidence. */
data class GoalChecklist(val items: List<String>, val confirmed: Set<Int>, val stale: Boolean)

/** Progress for when several steps are done in one go without the model; task cards and notifications use it to state what is being done. */
sealed interface RunProgress {
    /** Executing a remembered route, currently on step [step]/[total]. */
    data class Replay(val step: Int, val total: Int) : RunProgress
    /** Executes [steps] steps supplied by the model in a single go (do_steps). */
    data class Batch(val steps: Int) : RunProgress
    /** Task count/boundary progress reported by the main model as it works; [name] and [boundary] are already written in the user's language. */
    data class Task(val name: String, val done: Int?, val total: Int?, val boundary: String?, val goals: GoalChecklist? = null) : RunProgress {
        val display: String get() = listOfNotNull(
            name.ifBlank { null },
            done?.let { d -> total?.let { "$d/$it" } ?: d.toString() },
            boundary?.ifBlank { null },
        ).joinToString(" · ")
    }
    /**
     * Another task is currently using [app] (its UI name), so this step stops and waits for it to be released (the model asked to wait when opening the app).
     * It is only waiting for another task and requires nothing from the user: the UI still counts it as working, not as "needs you".
     */
    data class WaitingForApp(val app: String) : RunProgress
}

sealed interface TaskState {
    /** The ways a run can finish: answered, failed, cancelled. */
    sealed interface Terminal : TaskState
    /** Has not started running yet. */
    data object Idle : TaskState
    /**
     * [note] is the model's latest utterance; when there is none, it is the name of the most recently called tool.
     * [tool] is the tool to call in this step; empty means this step is still waiting for the model's reply (in which case [note] still belongs to the previous step).
     * When [progress] is non-null, this step runs several steps without the model (route replay, batch execution).
     * [reason] is the intent the model wrote for this call (the `reason` call argument); null when none was written.
     * [narration] is what the model wrote alongside this call (a progress note, not an answer); the UI prefers it for "what it's doing" and falls back to [reason].
     * [preview] is the visible text the current model request has already streamed; it is not yet confirmed whether it is the final answer or tool progress.
     * [mcpConnector] is the external MCP connector being called right now (its configured name); non-null only while the call is in flight.
     */
    data class Running(
        val step: Int, val note: String, val tool: String? = null, val progress: RunProgress? = null, val reason: String? = null,
        val narration: String? = null,
        val preview: String? = null,
        val mcpConnector: String? = null,
    ) : TaskState
    /** A capability's precondition is not met right now, so it stops here and waits (see [GateReason]); it returns to Running once the condition holds or the user taps continue. */
    data class Held(val reason: GateReason) : TaskState
    /** During payment, [item]/[amount]/[address] are listed separately on the takeover card; the outcome is handed back via [AgentLoop.resumeAfterTakeover]. */
    data class WaitingTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : TaskState
    data class WaitingInput(val prompt: String, val options: List<String>? = null) : TaskState
    /**
     * Waits for the user to approve a single operation that spends money (approval covers this one time only); answered via [AgentLoop.answerConfirmation], waiting for an answer, a cancellation, or a timeout (a timeout counts as declined).
     */
    data class WaitingConfirmation(val request: ConfirmationRequest) : TaskState
    /**
     * A model reply without tool calls is the [answer] (shown to the user as-is). Verification has three outcomes:
     * [reviewed] false means nothing was verified at all (chat, no verifiable values in the answer, no verifier model requested), in which case [verified] is also false and the UI shows no badge;
     * [verified] is true when verification ran and everything matched; [verified] is false when verification ran but could not confirm, with [note] giving the reason.
     * [unconfirmed] holds the spots that still failed to match after one retry (the answer is delivered anyway, with those spots marked unconfirmed).
     * [sources] are the reads that the code found in the evidence ledger as supporting the answer (the model does not write the basis).
     * Learning (experience routes, personal skills, app knowledge) looks at the validated outcome, not at this verification; see [LearnGate].
     */
    data class Succeeded(
        val answer: String, val verified: Boolean = true, val note: String? = null,
        val sources: List<AnswerSource> = emptyList(), val unconfirmed: List<String> = emptyList(),
        val reviewed: Boolean = true,
    ) : Terminal {
        /** Verification ran but could not confirm (flagged in the UI, recorded as unverified in evals). */
        val doubtful: Boolean get() = reviewed && !verified
    }
    /** [kind] is supplied by the code that produced the failure; it is never guessed from the [reason] text. */
    data class Failed(val reason: String, val kind: FailKind, val code: FailureCode? = null) : Terminal
    data object Cancelled : Terminal
}

/**
 * Why it stopped to wait: decided by the capability that provides this precondition (the core does not know what it is). [code] is the name of the state event in the run record,
 * [why] is the explanation shown to the user (may be empty), and when [resumable] is true the user can tap "continue"; otherwise it resumes automatically once the condition holds.
 * When [needsYou] is true, the user must do something on the phone before work can continue (the UI counts this as "needs you", not merely waiting on a condition).
 */
data class GateReason(val code: String, val why: String = "", val resumable: Boolean = false, val needsYou: Boolean = false) {
    /** Not started yet, queued to start (parallelism is full, or the same conversation already has a task): it starts automatically when its turn comes, and tapping "continue" cannot jump the queue. */
    val queued: Boolean get() = code == QUEUED

    companion object {
        const val QUEUED = "queued"

        /** Queued waiting to start; [why] is the sentence shown to the user (e.g. how many tasks are ahead). */
        fun queued(why: String) = GateReason(QUEUED, why)
    }
}

/** A program-generated failure reason; the UI uses it to choose the matching localized text. */
enum class FailureCode { ModelRateLimited }

/** The origin of a task failure. */
enum class FailKind {
    /** The agent called end_not_done, honestly reporting it could not do it. */
    GaveUp,
    /** The model call errored, returned something abnormal, or the run stopped without producing a result. */
    Model,
    /** An action guard triggered, stopping the run safely. */
    Guard,
    StepLimit,
    /** The per-task or per-provider token budget is exhausted; no further wrap-up request is sent. */
    TokenLimit,
    /** A scheduled task ran past the maximum runtime configured in settings and stopped; like an exhausted budget, this says nothing about whether the approach works. */
    TimeLimit,
    /** The runtime environment is unsatisfied or faulty (phone control helper, accessibility, no model configured, virtual screen disconnected, etc.). */
    Environment,
    /**
     * Accepted, but the task service or process executing it vanished midway (stopped by the system, process killed).
     * Scoring, like an environment error, does not count it against the agent; learning and skill trials do not treat it as failure (only [GaveUp] and [StepLimit] count).
     */
    Interrupted,
}

