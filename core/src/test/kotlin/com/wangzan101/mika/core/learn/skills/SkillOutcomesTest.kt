package com.wangzan101.mika.core.learn.skills

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillOutcomesTest {
    @Test fun trialNeedsVerifiedUseBeforeBecomingActive() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        assertEquals(SkillStatus.trial, SkillOutcomes.record(draft, SkillResult.unverified, followed = true).status)
        assertEquals(SkillStatus.active, SkillOutcomes.record(draft, SkillResult.verified, followed = true).status)
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }

    @Test fun trialSingleFailureStaysTrial() {
        // a single failure (possibly a problem elsewhere in the task) does not suspend a trial skill; it only records this result (#569)
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        val once = SkillOutcomes.record(draft, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.trial, once.status)
        assertEquals(listOf("failed"), once.outcomes)
        // being rejected by the verifier after following it is still a strong signal: one is enough to suspend
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }

    @Test fun trialTwoBadInThreeSuspends() {
        // trial behaves like active: suspend only when it fails several times among the recent results
        var cur = personalSkill("trial-skill", status = SkillStatus.trial)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.trial, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
    }
    private val s = personalSkill("wechat-msg")

    @Test fun keepsLast5() {
        var cur = s
        repeat(7) { cur = SkillOutcomes.record(cur, SkillResult.verified, followed = true) }
        assertEquals(SkillOutcomes.KEEP, cur.outcomes.size)
        assertEquals(SkillStatus.active, cur.status)
    }

    @Test fun twoOfLastThreeSuspends() {
        var cur = SkillOutcomes.record(s, SkillResult.unverified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.verified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
        assertEquals(listOf("unverified", "verified", "failed"), cur.outcomes)
    }

    @Test fun followedRejectionSuspendsImmediately() {
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = true).status)
        // when it was not followed (only recommended), a single rejection does not suspend
        assertEquals(SkillStatus.active, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = false).status)
    }

    @Test fun cancelledNotRecorded() {
        assertNull(SkillOutcomes.resultOf(TaskState.Cancelled, RunOutcome.Unknown))
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Done))
        assertEquals(SkillResult.unverified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done))
        // judged successful without the verifier having run: counts as good just like a verified run (this is how a trial turns active)
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Done))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Unknown))
        assertEquals(SkillResult.verifier_rejected, SkillOutcomes.resultOf(TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp), RunOutcome.Unknown))
        // the answer honestly reports it was not done, or only partly done: counts as failed; undetermined: neither good nor bad
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("找不到这个菜单", verified = true), RunOutcome.NotPossible))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("只看了两条", verified = true), RunOutcome.Partial))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("找不到", FailKind.GaveUp), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("步数用完", FailKind.StepLimit), RunOutcome.Unknown))
        // model error, environment failure, an operation guard stopping the run, or the token budget running out: says nothing about whether the skill or knowledge works
        for (k in listOf(FailKind.Model, FailKind.Environment, FailKind.Guard, FailKind.Interrupted, FailKind.TokenLimit))
            assertEquals(k.name, SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Failed("x", k), RunOutcome.Unknown))
    }
}
