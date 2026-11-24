package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillDrafter
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.settings.MemorySwitches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.knownTools

@OptIn(ExperimentalCoroutinesApi::class)
class SedimentationWiringTest {
    @get:Rule val texts = ChineseTexts()

    private val catalog = GuideCatalog.loadBundled()
    private val tools = setOf("phone_launch_app", "phone_observe", "phone_tap", "phone_type_text", "hand_to_user", "finish")
    private val draftMd = "---\nname: send-wechat-message\ndisplay: 发微信消息\ndescription: 给指定联系人发一条微信消息\napps: [com.tencent.mm]\nsource: learned\n---\n" +
        "## 入口\n用 `phone_launch_app` 打开。\n## 步骤\n1. 点搜索\n## 容易出错的地方\n同名群聊。\n## 怎样算办成(核实证据)\n消息出现在聊天里。\n## 什么时候停下交给用户\n要登录时。"

    /**
     * A run that sends a WeChat message: performs 6 actions in WeChat ([app]). Actions start at time 100; events in [extra] are ordered by the times they carry
     * (a skill read at a time like 41 means the skill was read before acting).
     */
    private fun events(verdict: String = "pass", extra: List<RunEvent> = emptyList(), app: String = "com.tencent.mm"): List<RunEvent> {
        var t = 99L
        val ev = mutableListOf<RunEvent>(RunEvent.TaskStarted(1, "发微信", "m"))
        listOf("phone_launch_app", "phone_tap", "phone_tap", "phone_type_text", "phone_tap", "phone_tap").forEachIndexed { i, tool ->
            ev += RunEvent.ModelReply(++t, i + 1, "", tool, "{}", null, null, null)
            val result = if (tool == "phone_launch_app") """{"ok":true,"opened":{"package":"$app"}}"""
                else """{"ok":true,"screen":{"currentApp":"$app"}}"""
            ev += RunEvent.ToolResult(++t, i + 1, tool, result, true, null)
            ev += RunEvent.Observed(++t, i + 1, "界面", place = Place.app(app))
        }
        ev += extra
        ev += RunEvent.Verifier(++t, verdict, """{"reason":"","checks":[]}""")
        ev += RunEvent.TaskEnded(++t, "succeeded", "ok")
        return ev
    }

    private fun learning(skills: MapSkills, learn: Boolean, autoApprove: Boolean = false, model: TextModel = TextModel(draftMd)) =
        RunLearning(MemorySwitches(learnSkills = learn), null, skills, GuideCatalog.withPersonal(catalog, skills), null, SkillDrafter(model), { "draft-1" }, autoApprove)

    private fun after(ev: List<RunEvent>, end: TaskState = TaskState.Succeeded("ok"), outcome: RunOutcome = RunOutcome.Done) = RunAftermath(
        "c1", "run-1", "给老妈发微信", end, emptyList(), emptyList(), emptyList(), tools, knownTools(), events = { ev }, outcome = outcome,
    )

    @Test fun draftOnlyWhenEnabledAndEligible() {
        val off = MapSkills()
        val offModel = TextModel(draftMd)
        runBlocking { learning(off, learn = false, model = offModel).learn(after(events())) }
        assertTrue(off.drafts.isEmpty() && offModel.requests.isEmpty())

        // Learning looks at the outcome, not the verification: no verification run but judged done still drafts; verification that ran without passing does not draft
        val unreviewed = MapSkills()
        runBlocking { learning(unreviewed, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("draft-1"), unreviewed.drafts.keys.toList())
        val doubtful = MapSkills()
        runBlocking { learning(doubtful, learn = true).learn(after(events(), end = TaskState.Succeeded("ok", verified = false, note = "x"))) }
        assertTrue(doubtful.drafts.isEmpty())

        // The answer was verified, but the outcome is partial, not possible, or unknown: no draft
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val notDone = MapSkills()
            val model = TextModel(draftMd)
            runBlocking { learning(notDone, learn = true, model = model).learn(after(events(), outcome = o)) }
            assertTrue(o.wire, notDone.drafts.isEmpty() && model.requests.isEmpty())
        }

        // This took the replay route: no further drafting
        val replayed = MapSkills()
        runBlocking { learning(replayed, learn = true).learn(after(events(extra = listOf(RunEvent.Replay(50, "r", 1, 3, true, ""))))) }
        assertTrue(replayed.drafts.isEmpty())

        val on = MapSkills()
        val notes = runBlocking { learning(on, learn = true).learn(after(events())) }
        assertEquals(listOf("draft-1"), on.drafts.keys.toList())
        assertEquals("Claire 学会了一项技能:给指定联系人发一条微信消息", notes.skillNotes.single().text)
        assertEquals("draft-1", notes.skillNotes.single().draftId)
    }

    // The model says this run updates an existing pending draft: write back under that draft's id instead of starting a new one
    @Test fun draftUpdatingPendingDraftReplacesIt() {
        val skills = MapSkills()
        skills.putPending("draft-0", PersonalSkill("send-wechat-message", draftMd, SkillStatus.pending, "r0", null, 0, emptyList()))
        val updated = draftMd.replace("source: learned", "updates: send-wechat-message\nsource: learned")
        val notes = runBlocking { learning(skills, learn = true, model = TextModel(updated)).learn(after(events())) }
        assertEquals(listOf("draft-0"), skills.drafts.keys.toList())
        assertEquals("run-1", skills.drafts.getValue("draft-0").learnedFrom)
        assertEquals("draft-0", notes.skillNotes.single().draftId)
    }

    @Test fun evalAutoApproveHook() {
        val skills = MapSkills()
        val notes = runBlocking { learning(skills, learn = true, autoApprove = true).learn(after(events())) }
        assertTrue(skills.drafts.isEmpty())
        assertEquals(SkillStatus.active, skills.get("send-wechat-message")!!.status)
        assertTrue(notes.skillNotes.isEmpty())
    }

    @Test fun draftingRunsAfterResultNotification() = runTest {
        val order = mutableListOf<String>()
        RunLearning.launchAfter(this, notify = { order += "notify" }) { order += "learn" }
        assertEquals(listOf("notify"), order)
        runCurrent()
        assertEquals(listOf("notify", "learn"), order)
    }

    @Test fun usedSkillOutcomeRecordedAndSuspensionNoted() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val used = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))
        val notes = runBlocking {
            learning(skills, learn = true).learn(after(events(verdict = "fail", extra = used), TaskState.Failed("核查未通过:消息没发出去", FailKind.GaveUp)))
        }
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
        // Done by following a personal skill: record the result only, do not draft
        val ok = MapSkills(skill)
        runBlocking { learning(ok, learn = true).learn(after(events(extra = used))) }
        assertEquals(listOf("verified"), ok.get("wechat-msg")!!.outcomes)
        assertTrue(ok.drafts.isEmpty())
        // No verification ran but the outcome is done: this also counts as done by following the skill
        val unreviewed = MapSkills(skill)
        runBlocking { learning(unreviewed, learn = true).learn(after(events(extra = used), TaskState.Succeeded("ok", verified = false, reviewed = false))) }
        assertEquals(listOf("verified"), unreviewed.get("wechat-msg")!!.outcomes)
        // The answer honestly says only part of it was done: following the skill did not succeed, so record failed
        val partial = MapSkills(skill)
        runBlocking { learning(partial, learn = true).learn(after(events(extra = used), outcome = RunOutcome.Partial)) }
        assertEquals(listOf("failed"), partial.get("wechat-msg")!!.outcomes)
        // Cancelled runs are not recorded
        val cancelled = MapSkills(skill)
        runBlocking { learning(cancelled, learn = false).learn(after(events(extra = used), TaskState.Cancelled)) }
        assertNull(cancelled.get("wechat-msg")!!.outcomes.firstOrNull())
    }

    // Two personal skills were read in one run and the run did not succeed: there is no telling which one was followed, so neither is recorded and neither is suspended (#569)
    @Test fun skillsOnlyBrowsedTogetherAreNotBlamed() {
        val md = draftMd.replace("send-wechat-message", "wechat-msg")
        val keepOn = PersonalSkill("keep-screen-on", md.replace("wechat-msg", "keep-screen-on"), SkillStatus.active, "r0", null, 0, emptyList())
        val drama = PersonalSkill("drama-coins", md.replace("wechat-msg", "drama-coins"), SkillStatus.active, "r0", null, 0, emptyList())
        val browsed = listOf(RunEvent.SkillUsed(41, "keep-screen-on", "viewed"), RunEvent.SkillUsed(42, "drama-coins", "viewed"), RunEvent.SkillUsed(43, "keep-screen-on", "viewed"))
        val rejected = TaskState.Failed("核查未通过:没办成", FailKind.GaveUp)
        // The same holds when it happens twice in a row (two failures used to suspend the skill)
        val both = MapSkills(keepOn, drama)
        repeat(2) {
            val notes = runBlocking { learning(both, learn = false).learn(after(events(verdict = "fail", extra = browsed), rejected)) }
            assertTrue(notes.skillNotes.isEmpty())
        }
        assertEquals(emptyList<String>(), both.get("keep-screen-on")!!.outcomes)
        assertEquals(emptyList<String>(), both.get("drama-coins")!!.outcomes)
        assertEquals(SkillStatus.active, both.get("keep-screen-on")!!.status)
        assertEquals(SkillStatus.active, both.get("drama-coins")!!.status)
        // Only one was read (viewing the same one twice still counts as one): it is recorded against that one as before, and the note states which one it was
        val skills = MapSkills(keepOn, drama)
        val once = listOf(RunEvent.SkillUsed(41, "keep-screen-on", "viewed"), RunEvent.SkillUsed(43, "keep-screen-on", "viewed"))
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = once), rejected)) }
        assertEquals(listOf("verifier_rejected"), skills.get("keep-screen-on")!!.outcomes)
        assertEquals(SkillStatus.suspended, skills.get("keep-screen-on")!!.status)
        assertEquals(emptyList<String>(), skills.get("drama-coins")!!.outcomes)
        assertTrue(notes.skillNotes.single().text.endsWith("keep-screen-on"))
    }

    // Read but not followed: acting in another app after reading, or not acting again after reading, so the result is not recorded against it (#569)
    @Test fun skillReadButNotFollowedIsNotBlamed() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val rejected = TaskState.Failed("核查未通过:没办成", FailKind.GaveUp)
        val elsewhere = MapSkills(skill)
        val browsedWechat = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"),
            RunEvent.Observed(50, 1, "只读到微信界面", place = Place.app("com.tencent.mm")))
        runBlocking { learning(elsewhere, learn = false).learn(after(events(verdict = "fail", extra = browsedWechat, app = "com.ss.android.ugc.aweme"), rejected)) }
        assertEquals(emptyList<String>(), elsewhere.get("wechat-msg")!!.outcomes)
        // It was read only after the actions were done, and the run wrapped up right after reading
        val late = MapSkills(skill)
        runBlocking { learning(late, learn = false).learn(after(events(verdict = "fail", extra = listOf(RunEvent.SkillUsed(500, "wechat-msg", "viewed"))), rejected)) }
        assertEquals(emptyList<String>(), late.get("wechat-msg")!!.outcomes)
        assertEquals(SkillStatus.active, late.get("wechat-msg")!!.status)
    }

    // One skill was read without being followed, and this run succeeded on its own: nothing is recorded against it, and drafting is not blocked
    @Test fun skillReadButNotFollowedStillDrafts() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        runBlocking { learning(skills, learn = true).learn(after(events(extra = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed")), app = "com.ss.android.ugc.aweme"))) }
        assertEquals(emptyList<String>(), skills.get("wechat-msg")!!.outcomes)
        assertEquals(listOf("draft-1"), skills.drafts.keys.toList())
    }

    // Two skills were read but only one skill's declared app was entered: that is the one followed, so the result is recorded against it
    @Test fun outcomeGoesToTheSkillWhoseAppWasUsed() {
        val md = draftMd.replace("send-wechat-message", "wechat-msg")
        val wechat = PersonalSkill("wechat-msg", md, SkillStatus.active, "r0", null, 0, emptyList())
        val douyin = PersonalSkill("douyin-like", md.replace("wechat-msg", "douyin-like").replace("com.tencent.mm", "com.ss.android.ugc.aweme"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(wechat, douyin)
        val read = listOf(RunEvent.SkillUsed(41, "douyin-like", "viewed"), RunEvent.SkillUsed(42, "wechat-msg", "viewed"))
        runBlocking { learning(skills, learn = true).learn(after(events(extra = read))) }
        assertEquals(listOf("verified"), skills.get("wechat-msg")!!.outcomes)
        assertEquals(emptyList<String>(), skills.get("douyin-like")!!.outcomes)
    }

    // A personal skill on trial: the agent read it with skill_read and this run succeeded, so it is promoted to active
    @Test fun viewedTrialSkillPromotesOnDoneRun() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.trial, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        runBlocking { learning(skills, learn = true).learn(after(events(extra = listOf(RunEvent.SkillUsed(41, "wechat-msg", "viewed"))))) }
        assertEquals(SkillStatus.active, skills.get("wechat-msg")!!.status)
        assertEquals(listOf("verified"), skills.get("wechat-msg")!!.outcomes)
        assertTrue(skills.drafts.isEmpty())
    }

    // injected in old run records (the full text used to be injected at the start) is still read, and the result is recorded as if it had been viewed
    @Test fun oldInjectedRecordCountsAsUse() {
        val skill = PersonalSkill("wechat-msg", draftMd.replace("send-wechat-message", "wechat-msg"), SkillStatus.active, "r0", null, 0, emptyList())
        val skills = MapSkills(skill)
        val rejected = TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp)
        val notes = runBlocking { learning(skills, learn = false).learn(after(events(verdict = "fail", extra = listOf(RunEvent.SkillUsed(40, "wechat-msg", "injected"))), rejected)) }
        assertEquals(listOf("verifier_rejected"), skills.get("wechat-msg")!!.outcomes)
        assertEquals(SkillStatus.suspended, skills.get("wechat-msg")!!.status)
        assertEquals("wechat-msg", notes.skillNotes.single().suspended)
    }
}
