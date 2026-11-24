package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState

@Suppress("EnumEntryName")
/**
 * [verified]: the task was done ([LearnGate]), and a trial skill turns active on this (the stored name is kept, so old records still read the same);
 * [unverified]: judged as done but verification did not pass; [unreviewed]: no outcome was judged, which says nothing about whether the skill works and does not count toward suspension.
 */
enum class SkillResult { verified, unverified, failed, verifier_rejected, unreviewed }

/**
 * The skill note under the result card: "Claire learned a new skill: ... · Take a look" ([draftId]),
 * or "This skill has not been working lately, so it was suspended · Restore · Delete" ([suspended] is the skill name).
 */
data class SkillNote(val text: String, val draftId: String? = null, val suspended: String? = null)

/** Recent usage outcomes of personal skills and automatic suspension (a skill that fails during a run is suspended). */
object SkillOutcomes {
    const val KEEP = 5
    private const val WINDOW = 3
    private const val BAD_IN_WINDOW = 2
    /** Result card and verifier rejection: following the skill yet being sent back by verification is judged as verifier_rejected. */
    const val VERIFIER_REJECTED_MARK = "核查未通过"

    /**
     * Append one outcome and keep only the most recent [KEEP]; the rules may turn the skill into suspended.
     * Trial and active skills use the same suspension threshold: suspend only when "following it was sent back by verification" or "several of the recent runs did not work".
     * A single [SkillResult.failed] no longer suspends a trial skill directly — one failure may be a problem elsewhere in the task (out of steps, gave up),
     * which says nothing about whether the skill itself works, and a false suspension would also pin "did not get done" on a skill that was never run at all (see #569).
     */
    fun record(s: PersonalSkill, r: SkillResult, followed: Boolean): PersonalSkill {
        val outcomes = (s.outcomes + r.name).takeLast(KEEP)
        val suspend = shouldSuspend(outcomes, followed && r == SkillResult.verifier_rejected)
        return s.copy(
            outcomes = outcomes,
            status = when {
                s.status == SkillStatus.trial && r == SkillResult.verified -> SkillStatus.active
                suspend -> SkillStatus.suspended
                else -> s.status
            },
        )
    }

    /** Two of the last 3 runs (unreviewed ones excluded) were not verified, or following it this time was sent back by verification. */
    fun shouldSuspend(all: List<String>, lastFollowedRejected: Boolean): Boolean {
        val outcomes = all.filter { it != SkillResult.unreviewed.name }
        return lastFollowedRejected || outcomes.takeLast(WINDOW).count { it != SkillResult.verified.name } >= BAD_IN_WINDOW
    }

    /**
     * The result corresponding to the task's ending; cancelled ones are not recorded (null). [outcome] is the verdict after the answer was handed over ([RunOutcome]):
     * an answer that honestly says it was not done, or only partly done, counts as failed; an unjudged run says nothing about whether the skill works and counts as unreviewed;
     * a done task counts as verified per [LearnGate] (even if no verification ran), and one where verification ran but did not pass counts as unverified;
     * of failures only giving up and running out of steps count as failed; model errors, environment problems, stopping by operation guards, process interruption and running out of the token budget count as unreviewed.
     */
    fun resultOf(end: TaskState, outcome: RunOutcome): SkillResult? = when (end) {
        is TaskState.Succeeded -> when {
            outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible -> SkillResult.failed
            // Still something unmatched after one rejection: following the skill still did not get it right, same as being sent back by verification before (whether or not the outcome was judged)
            end.unconfirmed.isNotEmpty() -> SkillResult.verifier_rejected
            outcome == RunOutcome.Unknown -> SkillResult.unreviewed
            LearnGate.done(end, outcome) -> SkillResult.verified
            else -> SkillResult.unverified
        }
        is TaskState.Failed -> when {
            VERIFIER_REJECTED_MARK in end.reason -> SkillResult.verifier_rejected
            end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit -> SkillResult.failed
            // Model errors, environment problems, stopping by operation guards, process interruption, running out of the token budget or of a scheduled task's runtime: unrelated to whether following the skill works (#569)
            else -> SkillResult.unreviewed
        }
        else -> null
    }
}
