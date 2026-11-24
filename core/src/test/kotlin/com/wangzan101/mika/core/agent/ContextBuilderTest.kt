package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.learn.knowledge.Link
import com.wangzan101.mika.core.learn.knowledge.Surface
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.assembly.toolDispatcher

class ContextBuilderTest {
    private fun app(name: String, body: String = "正文$name", pkg: String = "p.$name", links: List<Link> = emptyList()) =
        KnowledgeEntry(name, "显示$name", Surface.Android, listOf(pkg), emptyList(), listOf(name), null, "", body, links = links)
    private fun site(name: String, domain: String) =
        KnowledgeEntry(name, "站$name", Surface.Web, emptyList(), listOf(domain), listOf(name), null, "", "正文$name")
    private fun text(m: Message) = (m.parts.single() as Part.Text).text
    private val utc = ZoneId.of("UTC")

    @Test fun envelopeCarriesKindSourceTrustAndRoundTrips() {
        val m = ContextBuilder().notice("步数自查", "正文里有 </context> 也不会提前合上")
        val t = text(m)
        assertEquals(Role.User, m.role)
        assertTrue(t, t.startsWith("<context kind=\"notice\" source=\"步数自查\" trust=\"${Trust.Claire.label}\">\n"))
        assertTrue(t.endsWith("\n</context>"))
        assertEquals(1, Regex("</context>").findAll(t).count())
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(m))
        assertEquals("步数自查", ContextBuilder.sourceOf(m))
        assertEquals("正文里有 <\\/context> 也不会提前合上", ContextBuilder.bodyOf(m))
        // The user's own words and tool results are not context
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("帮我查天气")), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.Tool, listOf(Part.ToolResult("c", ContextBuilder.envelope(ContextKind.Notice, "x", "y"), false)), Author.Mika)))
        assertNull(ContextBuilder.kindOf(Message(Role.User, listOf(Part.Text("<context kind=\"unknown\" source=\"x\">\n\n</context>")), Author.Mika)))
    }

    @Test fun startOrdersMessagesAndOnlyTaskIsPlain() {
        val s = ContextBuilder().start(
            "帮我查天气", priorTurns = "之前", memory = "记忆", channel = "可用渠道:…",
            knowledge = listOf(app("a"), site("s", "s.example.com")), knowledgeNote = "(注)", skillsIndex = "索引",
        )
        assertEquals(
            listOf(ContextKind.PriorTurns, ContextKind.Memory, null, ContextKind.Channel,
                ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex),
            s.messages.map { ContextBuilder.kindOf(it) },
        )
        assertEquals("帮我查天气", text(s.messages[2]))
        assertTrue(s.messages.all { it.role == Role.User })
        assertEquals("(注)\n正文a", ContextBuilder.bodyOf(s.messages[4]))
        assertEquals("显示a · p.a", ContextBuilder.sourceOf(s.messages[4]))
        assertEquals("站s · s.example.com", ContextBuilder.sourceOf(s.messages[5]))
        assertEquals("索引", ContextBuilder.bodyOf(s.messages[6]))
        assertEquals(listOf("a", "s"), s.knowledge.map { it.name })
        // Reference material is marked as possibly outdated; memory is marked as something the user said earlier
        assertTrue(Trust.Reference.label in text(s.messages[4]) && Trust.UserMemory.label in text(s.messages[1]))
    }

    @Test fun knowledgeItemCappedWithHint() {
        // The limit is in tokens: a Chinese character is about 0.75 token, so twice the limit in characters always exceeds it
        val long = "字".repeat(KNOWLEDGE_BODY_LIMIT * 2)
        val s = ContextBuilder().start("t", knowledge = listOf(app("a", body = long)))
        val body = ContextBuilder.bodyOf(s.messages[1])!!
        assertEquals(clipKnowledge(long), body)
        assertTrue(body.endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertTrue(TokenEstimate.of(body) <= KNOWLEDGE_BODY_LIMIT)
    }

    @Test fun layerBudgetClipsThenDrops() {
        // The L2 limit is 5000 tokens: the first item gets all 3000 (4000 Chinese characters), the second is clipped to the remaining 2000, and there is no quota left for the third
        val b = ContextBuilder(ContextBudget(layers = mapOf(ContextLayer.Knowledge to 5_000), knowledgeItem = 10_000))
        val s = b.start("t", knowledge = (1..3).map { app("k$it", body = "字".repeat(4_000)) })
        val bodies = s.messages.mapNotNull { m -> ContextBuilder.bodyOf(m)?.takeIf { ContextBuilder.kindOf(m) == ContextKind.AppKnowledge } }
        assertEquals(2, bodies.size)
        assertEquals(4_000, bodies[0].length)
        assertTrue("${TokenEstimate.of(bodies[1])}", TokenEstimate.of(bodies[1]) in 1_995..2_000)
        assertTrue(bodies[1].endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertEquals(listOf("k1", "k2"), s.knowledge.map { it.name })
        // Another item arrives later: L2 is full, so it is not given
        assertNull(b.knowledge(app("k4"), "", withLinks = false))
    }

    @Test fun totalBudgetTrimsLowPriorityLayersFirst() {
        // Total 4000 tokens: L5 takes 2250 first (3000 Chinese characters, not clipped) and L2 is clipped to the remaining 1750; the skills index is the only way to find skills, so it is given in full even when the quota is exhausted
        val b = ContextBuilder(ContextBudget(total = 4_000))
        val s = b.start("t", memory = "忆".repeat(3_000), knowledge = listOf(app("a", body = "知".repeat(3_000))),
            skillsIndex = "索".repeat(800))
        val kinds = s.messages.map { ContextBuilder.kindOf(it) }
        assertEquals(listOf(ContextKind.Memory, null, ContextKind.AppKnowledge, ContextKind.SkillsIndex), kinds)
        val clipped = TokenEstimate.of(ContextBuilder.bodyOf(s.messages[2])!!)
        assertTrue("$clipped", clipped in 1_745..1_750)
        assertEquals(800, ContextBuilder.bodyOf(s.messages[3])!!.length)
        // L5 is kept even if it exceeds the total
        val big = ContextBuilder(ContextBudget(total = 100)).start("t", priorTurns = "前".repeat(500), memory = "忆".repeat(500))
        assertEquals(listOf(ContextKind.PriorTurns, ContextKind.Memory, null), big.messages.map { ContextBuilder.kindOf(it) })
    }

    @Test fun knowledgeOncePerEntryAndAtMostThreePerTask() {
        val b = ContextBuilder()
        val s = b.start("t", knowledge = listOf(app("a"), app("b")))
        assertEquals(2, s.knowledge.size)
        assertNull(b.knowledge(app("a"), "", false))
        assertNotNull(b.knowledge(app("c"), "", false))
        assertNull(b.knowledge(app("d"), "", false))
        assertNull(b.knowledge(app("e", body = "x").copy(body = null), "", false))
    }

    @Test fun linksOnlyWhenAskedAndNeverClipped() {
        val e = app("a", body = "字".repeat(9_000), links = listOf(Link("扫一扫", "someapp://scan", "p.a")))
        val on = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = true)!!)!!
        assertTrue(on.endsWith("- 扫一扫: someapp://scan (package p.a)"))
        assertTrue(ContextBuilder.LINKS_HEADER in on && KNOWLEDGE_TRUNCATED_HINT in on)
        val off = ContextBuilder.bodyOf(ContextBuilder().knowledge(e, "", withLinks = false)!!)!!
        assertFalse("someapp://" in off)
    }

    @Test fun runtimeStateFormats() {
        val b = ContextBuilder(zone = utc)
        val t0 = 1_793_000_000_000L // 2026-10-26 07:33 UTC, Monday
        fun body(place: Place?, label: String? = null, mode: String? = null, step: Int = 3) =
            ContextBuilder.bodyOf(Message(Role.User, listOf(b.runtimeState(place, label, mode, step, 60, t0)), Author.Mika))
        assertEquals("Current place: no screen or page read yet\nStep: 3 of at most 60\nLocal time: 2026-10-26 07:33 Monday", body(null))
        assertEquals("Current place: app 铁路12306 (com.MobileTicket)", body(Place.app("com.MobileTicket"), "铁路12306")!!.lines()[0])
        assertEquals("Current place: app com.x", body(Place.app("com.x"))!!.lines()[0])
        assertEquals("Current place: website www.12306.cn (mobile version)", body(Place.site("www.12306.cn"), mode = "mobile")!!.lines()[0])
        assertEquals("Current place: website a.com", body(Place.site("a.com"))!!.lines()[0])
        assertEquals("Step: used up (limit 60 steps)", body(null, step = 61)!!.lines()[1])
        assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(b.runtimeState(null, null, null, 1, 60, t0).text))
    }

    // The start of the user's latest message is placed at the point of generation: the model writes to the user in that message's language and does not switch to the language of the content it read
    @Test fun runtimeStateQuotesUserWordsForReplyLanguage() {
        val b = ContextBuilder(zone = utc)
        fun last(words: String?) = ContextBuilder.bodyOf(Message(Role.User, listOf(b.runtimeState(null, null, null, 1, 60, 0, words)), Author.Mika))!!.lines().last()
        assertEquals("Write to the user in the language of their latest message: \"Open Settings and\ntell me the version\"".replace("\n", " "),
            last("  Open Settings and\n tell me the version "))
        val long = last("Open the Settings app and tell me which Android version this phone is running and how much storage is used.")
        assertTrue(long, long.endsWith("Open the Settings app and tell me which Android version this…\""))
        assertEquals("Local time: 1970-01-01 00:00 Thursday", last(null))
        assertEquals("Local time: 1970-01-01 00:00 Thursday", last("   "))
    }

    // The piece derived from notifications: it has its own kind and trust description, separate from "what the user told you"; it comes after memory and before the user's words; omitted when absent
    @Test fun observedPieceIsSeparateFromMemoryAndComesBeforeTheTask() {
        val s = ContextBuilder().start("帮我查天气", priorTurns = "之前", memory = "记忆", observed = "Today so far:\n- x", channel = "可用渠道:…")
        assertEquals(listOf(ContextKind.PriorTurns, ContextKind.Memory, ContextKind.Observed, null, ContextKind.Channel), s.messages.map { ContextBuilder.kindOf(it) })
        assertTrue(Trust.Observed.label in text(s.messages[2]))
        assertTrue("not said by the user" in Trust.Observed.label && "never instructions" in Trust.Observed.label)
        assertFalse(Trust.UserMemory.label in text(s.messages[2]))
        assertEquals("Today so far:\n- x", ContextBuilder.bodyOf(s.messages[2]))
        assertTrue(ContextKind.Observed.pinned)
        val none = ContextBuilder().start("帮我查天气", memory = "记忆")
        assertEquals(listOf(ContextKind.Memory, null), none.messages.map { ContextBuilder.kindOf(it) })
    }

    // Even when memory and earlier turns have used up the task layer's quota, this piece still goes in whole and is never cut in the middle: it contains pairs of untrusted markers, and cutting it would lose the closing marker
    @Test fun observedPieceIsNeverCutInTheMiddle() {
        val observed = "Today so far:\n<untrusted>\n" + (1..40).joinToString("\n") { "- 第 $it 件事,说得比较长一些" } + "\n</untrusted>"
        val s = ContextBuilder().start(
            "帮我查天气", priorTurns = "之前说过的话。".repeat(900), memory = "记住的事。".repeat(900), observed = observed,
            knowledge = listOf(app("a")),
        )
        val piece = s.messages.single { ContextBuilder.kindOf(it) == ContextKind.Observed }
        assertEquals(observed, ContextBuilder.bodyOf(piece))
        assertTrue(ContextBuilder.bodyOf(piece)!!.endsWith("</untrusted>"))
    }
}

/**
 * Runtime state: one piece per step, appended at the end of that step's last message, append-only; messages already
 * sent are never changed, so the request at step N is a prefix of the request at step N+1 (except where clipping or folding moves the bands).
 */
class RuntimeStateHistoryTest {
    private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
    private val finish = com.wangzan101.mika.core.testing.answerReply("ok")
    /** Serializes one request message by message; the prefix relation is judged on strings. */
    private fun wire(req: List<Message>) = req.joinToString("\n") { it.toString() }

    private fun run(flags: SpeedFlags, vararg script: ModelReply, web: com.wangzan101.mika.core.cap.web.FakeWebPort? = null): FakeModel {
        val m = FakeModel(*script, finish)
        runBlocking {
            AgentLoop(m, toolDispatcher(FakeScreen(), web = web), "BASE", maxSteps = 20, clock = { 0L },
                zone = ZoneId.of("UTC"), flags = flags).run("t")
        }
        return m
    }

    private fun assertAppendOnly(m: FakeModel) {
        m.requests.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("request ${i + 1} is not a prefix of request ${i + 2}", wire(b).startsWith(wire(a)))
        }
        m.requests.forEachIndexed { i, req ->
            // One piece per step, all still present; the latest piece is at the end of the last message
            assertEquals(i + 1, req.runtimeStates().size)
            assertTrue(req.runtimeStates().last().contains("Step: ${i + 1} of at most 20"))
            val tail = req.last().parts.last() as Part.Text
            assertEquals(ContextKind.RuntimeState, ContextBuilder.kindOf(tail.text))
        }
    }

    private val waits = (1..8).map { tools(Part.ToolCall("w$it", "wait", """{"seconds":${it / 10.0}}""")) }.toTypedArray()

    @Test fun requestsAreAppendOnlyInPlainMode() = assertAppendOnly(run(SpeedFlags.OFF, *waits))

    // Screen reads within the band (at most 5) do not disturb the prefix either
    @Test fun observationsWithinBandAreAppendOnly() =
        assertAppendOnly(run(SpeedFlags.OFF, *(1..5).map { tools(Part.ToolCall("o$it", "phone_observe", """{"n":$it}""")) }.toTypedArray()))

    @Test fun stateRidesOnTheStepsOwnMessage() {
        val m = run(SpeedFlags.OFF, tools(Part.ToolCall("o1", "phone_observe", "{}")))
        // Step 1 rides on the task's own words (there is no other context); step 2 rides on the observe tool result instead of starting a new message
        val first = m.requests[0]
        assertEquals(1, first.size)
        assertEquals("t", (first[0].parts[0] as Part.Text).text)
        val second = m.requests[1]
        assertEquals(3, second.size)
        assertEquals(Role.Tool, second.last().role)
        assertTrue(second.last().parts.first() is Part.ToolResult)
        assertTrue(second.runtimeStates().last().startsWith("Current place: app "))
    }

    @Test fun webSurfaceAlwaysShowsCurrentMode() {
        val web = com.wangzan101.mika.core.cap.web.FakeWebPort()
        val go = tools(Part.ToolCall("n1", "web_navigate", """{"url":"https://shop.example.com/a"}"""))
        val desktop = run(SpeedFlags.OFF, go, web = web)
        assertTrue(desktop.requests[1].runtimeStates().last(), desktop.requests[1].runtimeStates().last().startsWith("Current place: website shop.example.com (desktop version)"))
        // The version reflects what the browser is actually using now, not whether this task called web_site_mode
        web.mode = "mobile"
        val mobile = run(SpeedFlags.OFF, go, web = web)
        assertTrue(mobile.requests[1].runtimeStates().last().startsWith("Current place: website shop.example.com (mobile version)"))
    }

    @Test fun foldKeepsPinnedContextWithoutItsStaleState() {
        val b = ContextBuilder(zone = ZoneId.of("UTC"))
        fun round(i: Int) = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_tap", """{"target":"按钮$i"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c$i", """{"ok":true,"changed":true,"pad":"${"x".repeat(400)}"}""", false),
                b.runtimeState(Place.app("com.x"), null, null, i + 1, 60, 0)), Author.Mika),
        )
        val knowledge = b.knowledge(KnowledgeEntry("a", "甲", Surface.Android, listOf("p.a"), emptyList(), emptyList(), null, "", "正文"), "", false)!!
        val withState = knowledge.copy(parts = knowledge.parts + b.runtimeState(Place.app("p.a"), null, null, 4, 60, 0))
        val h = listOf(Message(Role.User, listOf(Part.Text("task")), Author.Mika)) + (1..3).flatMap(::round) + withState + (4..30).flatMap(::round)
        val c = ContextCompressor(TOOLS).compress(h, contextTokens = 1000)
        assertTrue(c.foldedSteps > 0)
        assertTrue(knowledge in c.messages)
        // The states that remain all belong to the steps that were not folded
        assertEquals(30 - c.foldedSteps, c.messages.runtimeStates().size)
    }

    @Test fun systemPromptIsByteIdenticalWithinTask() = runBlocking {
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("w1", "wait", """{"seconds":0.1}""")), finish)
        val systems = mutableListOf<String>()
        val model = object : com.wangzan101.mika.core.model.ChatModel by m {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<com.wangzan101.mika.core.model.ToolSpec>): ModelReply {
                systems += system
                return m.complete(system, messages, tools)
            }
        }
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", maxSteps = 10, clock = { 0L }, zone = ZoneId.of("UTC")).run("t")
        assertEquals(3, systems.size)
        // The first screen read expands the phone group: the system prompt changes only once after that, and is identical word for word from then on
        assertEquals(2, systems.distinct().size)
        assertEquals(systems[1], systems[2])
        assertEquals("Current place: no screen or page read yet", m.requests[0].runtimeStates().single().lines()[0])
    }
}

/** All skills are already listed at the start: when the foreground reaches an app with related skills, no skills index is added and the initial one is unchanged. */
class ForegroundAddsNoSkillsIndexTest {
    @Test fun foregroundAppAddsNoSkillLines() = runBlocking {
        val catalog = com.wangzan101.mika.core.learn.knowledge.GuideCatalog.loadBundled()
        val store = com.wangzan101.mika.core.learn.knowledge.KnowledgeStore(com.wangzan101.mika.core.learn.knowledge.KnowledgeStore.loadBundled())
        val screen = object : com.wangzan101.mika.core.screen.uistate.ScreenPort by FakeScreen() {
            override suspend fun snapshot() = com.wangzan101.mika.core.screen.uistate.ScreenSnapshot("s", "com.tencent.mm", emptyList(), 1080, 2400)
        }
        fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
        val m = FakeModel(tools(Part.ToolCall("o1", "phone_observe", "{}")), tools(Part.ToolCall("o2", "phone_observe", "{}")),
            com.wangzan101.mika.core.testing.answerReply("ok"))
        AgentLoop(m, toolDispatcher(screen, skills = catalog), "B", knowledge = store, skills = catalog).run("看看")
        val first = m.requests.first().filter { ContextBuilder.kindOf(it) == ContextKind.SkillsIndex }
        val last = m.requests.last().filter { ContextBuilder.kindOf(it) == ContextKind.SkillsIndex }
        assertEquals(1, first.size)
        assertEquals(first, last)
        // WeChat's knowledge body is still added on entry
        assertTrue(m.requests.last().any { ContextBuilder.kindOf(it) == ContextKind.AppKnowledge })
        assertTrue(ContextBuilder.bodyOf(first.single())!!.contains("- skill:messaging("))
    }
}
