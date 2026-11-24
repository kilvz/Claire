package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.learn.skills.InMemoryPersonalSkills
import com.wangzan101.mika.core.learn.skills.PersonalSkill
import com.wangzan101.mika.core.learn.skills.SkillStatus
import com.wangzan101.mika.core.learn.skills.personalSkill
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopSkillTest {
    private class ListSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { events += e }
    }

    private fun call(name: String, args: String = "{}") = Part.ToolCall("k$name${args.hashCode()}", name, args)
    private fun reply(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    private val bundled = GuideCatalog.loadBundled()

    private class Run(val requests: List<List<Message>>, val events: List<RunEvent>)

    private fun run(task: String, vararg personal: PersonalSkill, recommend: Boolean = true, script: List<Any> = listOf(finish)): Run {
        val catalog = GuideCatalog.withPersonal(bundled, InMemoryPersonalSkills(*personal))
        val model = FakeModel(*script.toTypedArray())
        val sink = ListSink()
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), skills = catalog), "B", sink = sink,
                skills = catalog, recommendSkills = recommend).run(task)
        }
        return Run(model.requests, sink.events)
    }

    private fun List<Message>.userTexts() = filter { it.role == Role.User }.map { it.plainText() }
    private fun List<Message>.index() = userTexts().filter { it.startsWith(SKILL_INDEX_HEADER) || it.startsWith(KNOWLEDGE_INDEX_HEADER) }
    private fun List<Message>.skillLines() = index().flatMap { it.substringAfter(SKILL_INDEX_HEADER + "\n", "").lines() }.filter { it.startsWith("- ") }
    private val zhihu = personalSkill("zhihu-collections", display = "查看知乎收藏夹列表", description = "打开知乎我的收藏,数收藏夹和条数",
        extraMeta = "aliases: [知乎收藏夹, 我的收藏, 收藏夹条数]\n")

    @Test fun knowledgeIndexStillByTaskWords() {
        val r = run("用闲鱼查手机,再发微信给小王,再在知乎搜一下")
        val idx = r.requests.first().index().single()
        // Knowledge and skills each sit under their own heading (the heading says which tool reads the full text)
        assertTrue(idx.lines().first() == KNOWLEDGE_INDEX_HEADER && idx.lines().any { it.startsWith("- app:xianyu(Xianyu):") })
        assertTrue(SKILL_INDEX_HEADER in idx.lines())
        // The index line comes after the task text
        val texts = r.requests.first().userTexts()
        assertTrue(texts.indexOf(idx) > texts.indexOfFirst { it.startsWith("用闲鱼") })
    }

    // Not a single character of the goal matches a skill name literally: every skill is still listed, and the model decides from the descriptions
    @Test fun allSkillsListedEvenWithoutSharedWords() {
        val r = run("知乎上我一共建了几个收藏夹？哪个收藏夹里的内容最多？", zhihu, personalSkill("a-trip", display = "我的出行"))
        val lines = r.requests.first().skillLines()
        val builtIn = bundled.items.filter { it.kind == "skill" }.map { it.name }.sorted()
        assertEquals(builtIn.map { "skill:$it" } + listOf("personal:a-trip", "personal:zhihu-collections"), lines.map { it.removePrefix("- ").substringBefore('(') })
        assertTrue(lines.contains("- personal:zhihu-collections(查看知乎收藏夹列表):打开知乎我的收藏,数收藏夹和条数"))
        // Even ones completely unrelated to the task are listed: nothing is picked by literal matching
        assertTrue(run("把屏幕亮度调高一点", zhihu).requests.first().skillLines().any { it.startsWith("- personal:zhihu-collections(") })
    }

    @Test fun suspendedAndPendingNotListedTrialMarked() {
        val store = InMemoryPersonalSkills(zhihu.copy(status = SkillStatus.trial), personalSkill("old-one", status = SkillStatus.suspended))
        store.putPending("d1", personalSkill("draft-one"))
        val catalog = GuideCatalog.withPersonal(bundled, store)
        val lines = GuideRecommender.index(catalog, "随便")!!.lines()
        assertTrue(lines.any { it.startsWith("- personal:zhihu-collections(查看知乎收藏夹列表):[unverified") })
        assertTrue(lines.none { "old-one" in it || "draft-one" in it })
    }

    // Only the index is given at the start; the full text of a personal skill must be read by the model itself with skill_read
    @Test fun noPersonalFullTextAtStart() {
        val mine = personalSkill("lao-ma-msg", body = "## 入口\n打开微信找老妈", display = "给老妈发消息")
        val r = run("给老妈发消息说晚点到", mine)
        val first = r.requests.first()
        assertTrue(first.userTexts().none { "打开微信找老妈" in it })
        assertTrue(first.userTexts().any { "- personal:lao-ma-msg(给老妈发消息):" in it })
        assertTrue(r.events.none { it is RunEvent.SkillUsed })
    }

    @Test fun skillReadOfPersonalRecordsSkillUsed() {
        val r = run("知乎上我一共建了几个收藏夹？", zhihu, personalSkill("my-trip", display = "我的出行"), script = listOf(
            reply(call("skill_read", """{"name":"zhihu-collections"}""")),
            reply(call("knowledge_read", """{"name":"app:wechat"}""")), // built-in ones aren't recorded
            finish,
        ))
        // The full text comes from the skill_read result
        assertTrue(r.requests.last().any { m -> m.parts.any { it is Part.ToolResult && "打开 App" in it.content } })
        val used = r.events.filterIsInstance<RunEvent.SkillUsed>().map { it.name to it.how }
        assertEquals(listOf("zhihu-collections" to "viewed"), used)
    }

    @Test fun indexOffWhenRecommendDisabled() {
        val off = run("给老妈发消息", personalSkill("lao-ma-msg", display = "给老妈发消息"), recommend = false)
        assertTrue(off.requests.first().index().isEmpty())
    }
}
