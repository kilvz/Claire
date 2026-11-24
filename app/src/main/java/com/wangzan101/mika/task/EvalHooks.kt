package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.cap.phone.OperatingPace

/**
 * Hooks for the evaluation runner. Only set in tests; in product code they all hold their defaults and behavior is unchanged.
 * One instance per process (see [TaskEnvironment]), shared by the tasks running at the same time (the auto-answer only looks at the question itself, so it is the same for any task).
 */
class EvalHooks {
    /** Auto-answer for follow-up questions (ask_user); returning null or blank means no answer, and the task waits until the evaluation times out. */
    @Volatile var autoAnswer: ((prompt: String, options: List<String>?) -> String?)? = null

    /** What the evaluation runner does on takeover: cancel (cancel the task, recorded as takeover) or wait (wait for the user to handle it). Read by the runner. */
    @Volatile var onTakeover: String = "cancel"

    /** Step cap per item; null uses the Agent's default. */
    @Volatile var maxSteps: Int? = null

    /**
     * Auto-answer for the confirmation gate; when non-null the user is not awaited and its result is used directly (always false in evaluations: never really send, delete, or place an order).
     * The gate still records events as usual, and the card content is left for the evaluation to verify.
     */
    @Volatile var autoDecideConfirmation: ((ConfirmationRequest) -> Boolean)? = null

    /** Accept drafted skill drafts directly (the training round of evaluations). */
    @Volatile var autoApproveSkillDrafts: Boolean = false

    /** Overrides the "learn skills from completed tasks" switch; null uses the one in settings. Off by default in evaluations (EvalRunTest sets false), and only enabled in training rounds. */
    @Volatile var learnSkills: Boolean? = null

    /** Overrides the "automatically remember things I said" switch; null uses the one in settings. Off by default in evaluations, and only enabled for memory test sets. */
    @Volatile var rememberMemory: Boolean? = null

    /** Overrides the operating pace from settings; null uses the one in settings. Evaluations and real-device acceptance default to "fast", and only switch to another pace when a parameter says so. */
    @Volatile var operatingPace: OperatingPace? = null

    fun reset() {
        operatingPace = null
        autoApproveSkillDrafts = false
        learnSkills = null
        rememberMemory = null
        autoAnswer = null
        onTakeover = "cancel"
        maxSteps = null
        autoDecideConfirmation = null
    }

    internal fun answerFor(state: TaskState): String? {
        val s = state as? TaskState.WaitingInput ?: return null
        return autoAnswer?.invoke(s.prompt, s.options)?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun decisionFor(state: TaskState): Boolean? {
        val s = state as? TaskState.WaitingConfirmation ?: return null
        return autoDecideConfirmation?.invoke(s.request)
    }
}
