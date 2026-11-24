package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState

@Suppress("EnumEntryName")
enum class FailureClass {
    perception, grounding, route, barrier, app_state, input, guard_stop, step_limit, model_error,
    false_success, verifier_reject, gate, harness, unknown,
    /** Marked "unverified" on hand-over and checks also failed: counted separately from false success, to ease before/after comparison. */
    unverified_wrong,
    /** Another task running at the same time in parallel evaluation grabbed the app this task needed. */
    interference,
}

data class TaskScore(
    val id: String,
    val passed: Boolean,
    val falseSuccess: Boolean,
    val unverified: Boolean,
    /** Failed checks: "outcome_is" or "<index>:<type>". */
    val failedChecks: List<String>,
    val failureClass: FailureClass?,
    /** Data marker created by write-type tasks ({run} already substituted), listed in the report for manual cleanup. */
    val marker: String? = null,
    /** See [Observed.leftScope]. */
    val leftScope: List<String> = emptyList(),
    /** The kind of obstacle handed over to the user (see [Scoring.handedOver]): listed separately, not counted as completed. */
    val handedOver: String? = null,
)

object Scoring {
    private val MODEL_ERROR = Regex("API Key|额度|限流|模型|Model error")

    /** When the evaluation runner itself errors, the failure reason starts with this. */
    const val HARNESS_PREFIX = "Evaluator:"

    /** Failures caused by two tasks interfering with each other in parallel evaluation start with this reason prefix. */
    const val INTERFERENCE_PREFIX = "Parallel interference:"

    /** Failure reason when the Agent itself calls end_not_done; tasks expected to fail accept only this one. */
    const val GAVE_UP = "The Agent honestly reported it could not be done"

    /** Start of the failure reason when verification never passes (see AgentLoop). */
    const val VERIFICATION_PREFIX = "核查未通过"

    /**
     * The failure reason handed to scoring: generated from the failure source [FailKind], not from words the Agent itself said,
     * otherwise when the Agent honestly gives up, words like "model" or "quota" in its message would be misjudged as a model error.
     */
    fun failReason(f: TaskState.Failed): String = when (f.kind) {
        FailKind.GaveUp -> GAVE_UP
        FailKind.Model -> "Model error: ${f.reason}"
        FailKind.Guard, FailKind.StepLimit, FailKind.TokenLimit, FailKind.TimeLimit -> f.reason
        // A process or service disappearing mid-run is also a matter of the runtime environment, not the Agent's fault
        FailKind.Environment, FailKind.Interrupted -> "${HARNESS_PREFIX}environment: ${f.reason}"
    }

    /**
     * Scores using the outcome judged in the run record: when the answer honestly says it was not done, or only partly done ([Observed.judged] is partial / not_possible),
     * it counts as failed, same as an honest give-up by the Agent, and is not counted as reported success. If there was no judgment, or no verdict was reached, the outcome at hand-over applies.
     */
    fun recorded(o: Observed): Observed =
        if ((o.outcome == "succeeded" || o.outcome == "unverified") && (o.judged == "partial" || o.judged == "not_possible"))
            o.copy(outcome = "failed", failReason = GAVE_UP)
        else o

    fun score(t: EvalTask, observed: Observed): TaskScore {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        return TaskScore(
            id = t.id,
            passed = failed.isEmpty(),
            falseSuccess = isFalseSuccess(o, failed),
            unverified = o.outcome == "unverified",
            failedChecks = failed,
            failureClass = initialClass(t, o),
            // Data can only be created after at least one step; tasks that never started are not listed in the cleanup list
            marker = t.marker?.takeIf { o.steps > 0 },
            leftScope = o.leftScope,
            handedOver = handedOver(t, o),
        )
    }

    /** Initial failure class; returns null for tasks that succeeded. A human can reclassify after reviewing the run record. */
    fun initialClass(t: EvalTask, observed: Observed): FailureClass? {
        val o = recorded(observed)
        val failed = failedChecks(t, o)
        if (failed.isEmpty()) return null
        if (isFalseSuccess(o, failed)) return FailureClass.false_success
        if (o.outcome == "unverified") return FailureClass.unverified_wrong
        val reason = o.failReason.orEmpty()
        // The raw text of unmet criteria may contain words like "model", so check verification first
        if (reason.startsWith(VERIFICATION_PREFIX)) return FailureClass.verifier_reject
        infraClass(o)?.let { return it }
        return when {
            "已安全停止" in reason || "Stopped safely" in reason -> FailureClass.guard_stop
            "步上限" in reason || "step limit" in reason -> FailureClass.step_limit
            "核查未通过" in reason -> FailureClass.verifier_reject
            o.outcome == "takeover" && t.category != EvalCategory.Barrier -> FailureClass.barrier
            else -> FailureClass.unknown
        }
    }

    /** Failures caused by infrastructure (model, evaluation runner); these take precedence over classifications such as guard stop and step limit. */
    private fun infraClass(o: Observed): FailureClass? {
        val reason = o.failReason ?: return null
        return when {
            reason.startsWith(HARNESS_PREFIX) -> FailureClass.harness
            reason.startsWith(INTERFERENCE_PREFIX) -> FailureClass.interference
            MODEL_ERROR.containsMatchIn(reason) -> FailureClass.model_error
            else -> null
        }
    }

    private fun isFalseSuccess(o: Observed, failed: List<String>) = o.outcome == "succeeded" && failed.isNotEmpty()

    /** Default when [EvalTask.acceptTakeoverOn] is not set: read-type tasks that hit a login wall hand over to the user (only the user can log in); other categories do not accept it. */
    fun takeoverKinds(t: EvalTask): List<String> =
        t.acceptTakeoverOn ?: if (t.category == EvalCategory.Read && t.expect == Expect.Succeeded) listOf("login") else emptyList()

    /** A precondition declaring that the user is already signed in: hitting a login wall then cannot be explained as "hand over to the user". */
    private val SIGNED_IN_KINDS = setOf("signedIn", "login", "site")

    /**
     * An obstacle accepted by the task was hit, was visible on screen ([Observed.takeoverBarrier]), and was handed to the user: returns the obstacle kind, recorded as its own "handed over to the user" class.
     * It does not count as completed (whether it counts is decided by a human), nor as any other failure. Not accepted when the task declares a signed-in precondition.
     */
    fun handedOver(t: EvalTask, o: Observed): String? = o.takeoverBarrier?.takeIf { kind ->
        t.expect != Expect.Takeover && o.outcome == "takeover" && kind in takeoverKinds(t) &&
            t.requires.none { it.substringBefore(':') in SIGNED_IN_KINDS }
    }

    private fun failedChecks(t: EvalTask, o: Observed): List<String> {
        val expected = when (t.expect) {
            Expect.Succeeded -> "succeeded" // unverified does not count
            Expect.Failed -> "failed"
            Expect.Confirmation -> "confirmation"
            Expect.Takeover -> "takeover"
        }
        val out = mutableListOf<String>()
        // For tasks expected to fail, only an honest give-up by the Agent itself counts; model or runner errors, hitting the step limit, or being stopped by a guard do not
        val honest = t.expect != Expect.Failed || o.failReason == GAVE_UP
        if (o.outcome != expected || !honest) out += "outcome_is"
        t.checks.forEachIndexed { i, c -> if (!Checks.passes(c, o)) out += "$i:${typeName(c)}" }
        return out
    }

    private fun typeName(c: Check) = when (c) {
        is Check.AnswerContains -> "answer_contains"
        is Check.AnswerRegex -> "answer_regex"
        is Check.AnswerGrounded -> "answer_grounded"
        is Check.ScreenHas -> "screen_has"
        is Check.MemoryUsed -> "memory_used"
        is Check.AskedUser -> "asked_user"
        is Check.WebHas -> "web_has"
        is Check.ProbeEquals -> "probe_equals"
        is Check.ConfirmationShows -> "confirmation_shows"
    }
}
