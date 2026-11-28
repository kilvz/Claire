package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.TokenEstimate
import com.wangzan101.mika.core.safety.guard.DIAGNOSTIC_VERBS
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.label
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.verb
import com.wangzan101.mika.core.assembly.toolDispatcher

/** Replies according to a script; a script item is either a reply or an exception to throw. */
/** When used as the vision model, grounding is considered verified by default ([groundingVerified]); the unverified case is tested separately. */
class FakeModel(vararg script: Any, private val repeatLast: Boolean = false, contextTokens: Int = 128_000, groundingVerified: Boolean = true) : ChatModel {
    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = contextTokens, reasoning = false, groundingVerified = groundingVerified)
    private val script = script.toMutableList()
    val requests = mutableListOf<List<Message>>()
    val offeredTools = mutableListOf<List<String>>()
    var system: String? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        this.system = system
        requests += messages
        offeredTools += tools.map { it.name }
        val next = if (repeatLast && script.size == 1) script[0] else script.removeAt(0)
        if (next is Throwable) throw next
        return next as ModelReply
    }
}

private var callSeq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("call${++callSeq}", name, args)
private fun reply(vararg parts: Part) = ModelReply(parts.toList(), StopReason.ToolUse, null)
private fun tools(vararg calls: Part.ToolCall) = reply(*calls)
private const val DONE = "done"
private fun finish(answer: String = DONE) = com.wangzan101.mika.core.testing.answerReply(answer)

private fun Message.results() = parts.filterIsInstance<Part.ToolResult>()
private fun Part.ToolResult.json() = Json.parseToJsonElement(content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
/** Retry without jitter: the wait is exactly the backoff ceiling, so virtual time can be asserted. */
private val FIXED_RETRY = RetryPolicy(jitter = { 1.0 })

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {
    /** The system prompt with only the phone available and its group expanded: the group entry points, the hand-over rules, and the phone section (minus the lines for tools that are not offered). */
    private fun phoneOpenedPrompt(base: String) = listOf(
        base, com.wangzan101.mika.core.capability.PromptEnv(setOf("phone_launch_app", "phone_set_alarm"), false).gate(SystemPrompt.read("groups")), SystemPrompt.read("handover"),
        SystemPrompt.read("phone").lines().filterNot { "<!-- needs: phone_open_link -->" in it || "<!-- needs: phone_find_visually -->" in it || "<!-- needs: phone_sms_login -->" in it }.joinToString("\n"),
    ).joinToString("\n\n")

    private fun loop(
        model: ChatModel, screen: FakeScreen = FakeScreen(), maxSteps: Int = 60, vision: Boolean = false, sink: RunSink = RunSink.NONE,
        gate: suspend (setWaiting: (TaskState?) -> Unit) -> Unit = {},
    ) = AgentLoop(model, toolDispatcher(screen, vision), "BASE", maxSteps, gate, sink = sink, retry = FIXED_RETRY)

    @Test fun discussionToolProposalIsReplacedByDirectAnswer() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish("可以，先讨论具体流程。"))
        val judge = FakeModel(finish("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("以后可以帮我把内容同步到公众号并发送，对吧？")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, main.requests.size)
        assertTrue(main.offeredTools.first().contains("phone_list_apps"))
        assertTrue(main.offeredTools.last().isEmpty())
        assertTrue(events.none { it is RunEvent.ToolResult })
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "intent_gate" && it.detail == "Discuss" })
    }

    @Test fun typedIntentDecisionUsesExistingDiscussionGateAndIsRecorded() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish("可以，先讨论具体流程。"))
        val judge = FakeModel()
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            intentDecision = com.wangzan101.mika.core.model.decision.DecisionModel { _, _, _, _ ->
                com.wangzan101.mika.core.model.decision.DecisionChoice("DISCUSS", 0.98)
            }, sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("以后可以帮我把内容同步到公众号并发送，对吧？")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(judge.requests.isEmpty())
        assertTrue(events.none { it is RunEvent.ToolResult })
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "jev_intent" && it.detail == "selected:Discuss" })
    }

    // When the intent cannot be determined (neither the judge model nor the fallback main model gives one of the three words), carry out the main model's original decision instead of turning it into a text answer
    @Test fun unclearIntentKeepsProposedTool() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish("我觉得是要操作"), finish())
        val judge = FakeModel(finish("maybe"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("帮我看看装了哪些 App")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "intent_gate" && it.detail == "unavailable" })
        assertTrue(events.any { it is RunEvent.ToolResult })
        assertEquals(1, judge.requests.size)
    }

    @Test fun currentLookupStillUsesTool() = runTest {
        val main = FakeModel(tools(call("phone_list_apps")), finish())
        val judge = FakeModel(finish("READ"))
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
            .run("现在看看装了哪些 App")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, main.requests.size)
        assertTrue(events.any { it is RunEvent.ToolResult && it.tool == "phone_list_apps" })
    }

    @Test fun discussionSkipsMatchingReplayBeforeAnyAction() = runTest {
        val goal = "以后打开备忘录看看，对吧？"
        val route = com.wangzan101.mika.core.learn.replay.Route("discussion", goal, 0, emptyList(), emptyMap(), emptyList(), lastUsedMs = 0)
        val store = object : com.wangzan101.mika.core.learn.replay.RouteStore {
            override fun all() = listOf(route)
            override fun put(r: com.wangzan101.mika.core.learn.replay.Route) = Unit
            override fun remove(id: String) = Unit
            override fun clear() = Unit
            override fun count() = 1
        }
        val picker = FakeModel(finish("{\"route\":1,\"slots\":[]}"))
        val main = FakeModel(finish("可以先讨论。"))
        val judge = FakeModel(finish("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", intentJudge = judge,
            routes = com.wangzan101.mika.core.learn.replay.RouteMatcher(picker, store, null),
            flags = SpeedFlags(replay = true),
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }).run(goal)
        assertTrue(picker.requests.isEmpty())
        assertTrue(events.none { it is RunEvent.Replay || it is RunEvent.ToolResult })
        assertEquals(1, main.requests.size)
    }

    @Test fun phoneRunRequeriesAcrossScreensAndRecordsEachAction() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen("com.example.app", listOf(button("@e1", "群聊"))),
            Screen("com.example.app", listOf(button("@e1", "消息"))),
            Screen("com.example.app", listOf(label("@e1", "完成"))),
        ))
        var screenshots = 0
        val port = object : ScreenPort by screen {
            override suspend fun screenshotPng(): ByteArray? { screenshots++; return byteArrayOf(1) }
        }
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(reply(call("phone_run", """{"commands":[{"op":"tap","targetText":"群聊"},{"op":"tap","targetText":"消息"}]}""")), finish())
        val end = AgentLoop(model, toolDispatcher(port, inlineScreen = true, recordFrames = true), "BASE", sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("打开消息")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(2, model.requests.size)
        assertEquals(2, screen.calls.size)
        assertEquals(0, screenshots)
        assertEquals(2, events.filterIsInstance<RunEvent.ToolResult>().count { it.source == "script" && it.tool == "phone_tap" })
        assertEquals("true", model.requests[1].withoutRuntime().last().results().single().json().s("ok"))
    }

    @Test fun lightStepsOffAlwaysMain() = runTest {
        val main = FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish())
        val light = FakeModel()
        val r = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", router = ModelRouter(main, light)).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(0, light.requests.size)
        assertEquals(4, main.requests.size)
    }

    @Test fun inlineScreenPromptOnlyWhenFlagOn() = runTest {
        val off = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(off, toolDispatcher(FakeScreen()), SystemPrompt.identity()).run("t")
        assertEquals(phoneOpenedPrompt(SystemPrompt.identity()), off.system)
        assertTrue("You do not need to observe after every" !in off.system!!)
        // While the phone group is still collapsed there is no screen, so there is no note about actions carrying their own screen
        val collapsed = FakeModel(finish())
        AgentLoop(collapsed, toolDispatcher(FakeScreen(), inlineScreen = true), SystemPrompt.identity(), flags = SpeedFlags(inlineScreen = true)).run("t")
        assertTrue("You do not need to observe after every" !in collapsed.system!!)
        val on = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(on, toolDispatcher(FakeScreen(), inlineScreen = true), SystemPrompt.identity(),
            flags = SpeedFlags(inlineScreen = true)).run("t")
        assertTrue(on.system!!.startsWith(SystemPrompt.identity()))
        assertTrue("already includes the screen after the action\n  (`screen`: only elements that appeared, disappeared or changed" in on.system!!)
        assertTrue("You do not need to observe after every\n  action" in on.system!!)
    }

    @Test fun stuckGuardSeesInlineScreens() = runTest {
        // No observe, tapping the same spot repeatedly while the screen never changes: the screen carried by the action result is enough to recognise that there is no progress
        val m = FakeModel(*Array(8) { reply(call("phone_back")) }, finish())
        AgentLoop(m, toolDispatcher(FakeScreen(), inlineScreen = true), "BASE", flags = SpeedFlags(inlineScreen = true)).run("t")
        val seen = m.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(seen.any { "no_progress" in it })
        // With the switch off and no observe either: the effect the action reports itself (no_effect) also reveals a lack of progress
        val off = FakeModel(*Array(8) { reply(call("phone_back")) }, finish())
        AgentLoop(off, toolDispatcher(FakeScreen()), "BASE").run("t")
        val offSeen = off.requests.last().flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertTrue(offSeen.any { "no_progress" in it })
        assertTrue(offSeen.all { "\"kind\":\"no_effect\"" in it || "ok\":true" !in it })
    }

    @Test fun visibleScreensListsUnprunedReads() {
        val calls = listOf(Part.ToolCall("a", "phone_observe", "{}"), Part.ToolCall("b", "phone_tap", "{}"), Part.ToolCall("c", "phone_back", "{}"), Part.ToolCall("d", "phone_observe", "{}"))
        val results = listOf(
            Part.ToolResult("a", """{"ok":true,"snapshotId":"s1","elements":"${HistoryPruner.OMITTED}"}""", false),
            Part.ToolResult("b", """{"ok":true,"screen":{"snapshotId":"s2","full":false,"added":[]}}""", false),
            Part.ToolResult("c", """{"ok":true,"screen":"${HistoryPruner.OMITTED}"}""", false),
            Part.ToolResult("d", """{"ok":true,"snapshotId":"s4","elements":[]}""", false),
        )
        val h = calls.zip(results).flatMap { (c, r) -> listOf(Message(Role.Assistant, listOf(c), Author.Mika), Message(Role.Tool, listOf(r), Author.Mika)) }
        assertEquals(setOf("s2", "s4"), visibleReads(h))
    }

    @Test fun batchCountsAsOneStepButEachActionGuarded() = runTest {
        val backs = { n: Int -> call("do_steps", """{"steps":[${List(n) { """{"tool":"phone_back","args":{}}""" }.joinToString(",")}]}""") }
        val m = FakeModel(reply(backs(3)), finish())
        val r = AgentLoop(m, toolDispatcher(FakeScreen().apply { result = ActionResult.Ok(changed = true) }), "BASE", maxSteps = 2, flags = SpeedFlags(batchSteps = true)).run("t")
        // The whole batch counts as a single step against the step limit
        assertTrue(r is TaskState.Succeeded)
        // Multi-step calls follow the group that has a screen: not offered while the phone group is collapsed, and available only after a phone action in the batch expands it
        assertTrue("do_steps" !in m.offeredTools.first())
        assertTrue("do_steps" in m.offeredTools[1])
        val result = m.requests[1].withoutRuntime().last().results().single().json()
        assertEquals("true", result.s("ok"))
        // The same action repeated across batches can still be executed
        val m2 = FakeModel(reply(backs(5)), reply(backs(5)), reply(backs(5)), finish())
        val r2 = AgentLoop(m2, toolDispatcher(FakeScreen().also { it.result = ActionResult.Ok(changed = true) }), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(r2 is TaskState.Succeeded)
        assertEquals(4, m2.requests.size)
    }

    @Test fun batchSubStepsNoticeRepeatedInputWithoutUiProgress() = runTest {
        val typing = call("do_steps", """{"steps":[{"tool":"phone_type_text","args":{"text":"咖啡"}},{"tool":"phone_back","args":{}}]}""")
        val again = call("do_steps", """{"steps":[{"tool":"phone_type_text","args":{"text":"咖啡"}},{"tool":"phone_back","args":{}}]}""")
        val m = FakeModel(reply(typing), reply(again), finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue(m.requests[1].withoutRuntime().last().results().single().json()["advice"] == null)
        assertTrue(m.requests[2].withoutRuntime().last().results().single().json()["advice"] != null)
    }

    @Test fun doStepsAbsentWhenFlagOff() = runTest {
        val m = FakeModel(reply(call("do_steps", """{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back","args":{}}]}""")), finish())
        val s = FakeScreen()
        AgentLoop(m, toolDispatcher(s), SystemPrompt.identity()).run("t")
        assertTrue("do_steps" !in m.offeredTools.first())
        assertTrue("do_steps" !in m.system!!)
        assertEquals("unknown_tool", m.requests[1].withoutRuntime().last().results().single().json().s("error"))
        assertTrue(s.calls.isEmpty())
        val on = FakeModel(reply(call("phone_observe")), finish())
        AgentLoop(on, toolDispatcher(FakeScreen()), SystemPrompt.identity(), flags = SpeedFlags(batchSteps = true)).run("t")
        assertTrue("give them in one do_steps call" in on.system!!)
    }

    @Test fun completesWithTextAnswer() = runTest {
        val m = FakeModel(reply(Part.Thinking("t"), Part.Text("看一下"), call("phone_observe")), finish("找到了"))
        val l = loop(m)
        val r = l.run("帮我查")
        com.wangzan101.mika.core.testing.assertAnswered("找到了", r)
        assertEquals(r, l.state.value)
        assertEquals(phoneOpenedPrompt("BASE"), m.system)
        val second = m.requests[1].withoutRuntime()
        assertEquals(Role.User, second[0].role)
        assertEquals("帮我查", (second[0].parts.single() as Part.Text).text)
        // The assistant reply enters the history as is (including Thinking and ToolCall), followed by a Tool message.
        assertEquals(Role.Assistant, second[1].role)
        assertTrue(second[1].parts.any { it is Part.Thinking })
        assertEquals(Role.Tool, second[2].role)
        assertEquals("true", second[2].results().single().json().s("ok"))
    }

    @Test fun opaqueToolCallIsReplayed() = runTest {
        val c = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sig")
        val m = FakeModel(reply(c), finish())
        loop(m).run("t")
        assertEquals(c, m.requests[1][1].parts.single())
        assertEquals("x1", m.requests[1][2].results().single().callId)
    }

    @Test fun stopsAtMaxSteps() = runTest {
        val m = FakeModel(tools(call("wait", """{"seconds":0.2}""")), repeatLast = true)
        val r = loop(m, maxSteps = 3).run("t")
        assertTrue(r is TaskState.Failed && "3" in r.reason && r.kind == FailKind.StepLimit)
        // 3 steps plus a final closeout round (the model still wants to wait, which does not count)
        assertEquals(4, m.requests.size)
    }

    @Test fun tokenBudgetExhaustionEndsAsTokenLimitEvenDuringCloseout() = runTest {
        val raw = FakeModel(tools(call("wait", """{"seconds":0.2}""")).copy(usage = Usage(3, 2)), finish())
        val model = raw.metered(UsageSource("p", "P", "m", UsagePurpose.Main), sink = { _, _ -> })
        val events = mutableListOf<RunEvent>()
        val end = loop(model, maxSteps = 1, sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run(RunSpec(RunId("budget"), "t", "test", tokenBudget = 5), RunInbox())
        assertTrue("$end", end is TaskState.Failed && end.kind == FailKind.TokenLimit)
        assertTrue(end is TaskState.Failed && "5" in end.reason)
        assertEquals("no extra closeout request once the budget is reached", 1, raw.requests.size)
        assertEquals(5L, (events.first() as RunEvent.TaskStarted).tokenBudget)
    }

    @Test fun dailyBudgetExhaustionEndsAsTokenLimitWithoutARequestOrCloseout() = runTest {
        val raw = FakeModel(finish())
        val model = raw.metered(UsageSource("p", "服务商甲", "m", UsagePurpose.Main), { _, _ -> }) {
            throw ModelError.DailyBudgetExceeded("服务商甲", 10_000, 10_000)
        }
        val end = loop(model).run("t")
        assertTrue("$end", end is TaskState.Failed && end.kind == FailKind.TokenLimit)
        assertTrue(end is TaskState.Failed && "服务商甲" in end.reason)
        assertEquals(0, raw.requests.size)
    }


    private fun waits(n: Int) = (1..n).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray()

    @Test fun finalTurnOffersOnlyReportFailure() = runTest {
        val m = FakeModel(*waits(3), tools(call("end_not_done", """{"message":"查过搜索和主菜单,没有这个菜单"}""")))
        val r = loop(m, maxSteps = 3).run("t")
        assertEquals(TaskState.Failed("查过搜索和主菜单,没有这个菜单", FailKind.GaveUp), r)
        assertEquals(listOf("end_not_done"), m.offeredTools.last())
        assertTrue(m.offeredTools.first().size > 2)
        val note = (m.requests.last().withoutRuntime().last().parts.single() as Part.Text).text
        assertTrue(note, "out of steps" in note && "in text" in note)
    }

    @Test fun finalTurnTextIsTheAnswer() = runTest {
        com.wangzan101.mika.core.testing.assertAnswered("查过了,没找到", loop(FakeModel(*waits(3), finish("查过了,没找到")), maxSteps = 3).run("t"))
        // The final round replied with empty text or tried to call another tool: treated as steps used up
        val empty = loop(FakeModel(*waits(3), reply(Part.Text(""))), maxSteps = 3).run("t")
        assertTrue("$empty", empty is TaskState.Failed && empty.kind == FailKind.StepLimit)
    }

    @Test fun finalTurnUnreadValueIsLabelledNotRetried() = runTest {
        // No steps left to send a retry back in the final round: a value that was never read is marked unconfirmed directly
        val m = FakeModel(*waits(3), finish("结果是 3.6"))
        val r = loop(m, maxSteps = 3).run("t")
        assertEquals(listOf("3.6"), (r as TaskState.Succeeded).unconfirmed)
        assertEquals(4, m.requests.size)
    }

    // A reply without tool calls is the answer and the task ends; no further reminder is needed
    @Test fun plainTextEndsTheTask() = runTest {
        val m = FakeModel(reply(Part.Text("我想想")), finish())
        com.wangzan101.mika.core.testing.assertAnswered("我想想", loop(m).run("t"))
        assertEquals(1, m.requests.size)
    }

    @Test fun emptyTextIsNudgedOnceThenFails() = runTest {
        val m = FakeModel(reply(Part.Text("")), reply(Part.Text("  ")))
        val r = loop(m).run("t")
        assertEquals(StopRules.noAnswer(), r)
        val nudge = m.requests[1].withoutRuntime().last()
        assertEquals(Role.User, nudge.role)
        assertTrue("empty" in (nudge.parts.single() as Part.Text).text)
    }

    @Test fun toolTurnResetsEmptyNudge() = runTest {
        val m = FakeModel(reply(Part.Text("")), tools(call("phone_observe")), reply(Part.Text("")), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m).run("t"))
    }

    @Test fun onlyFirstToolCallExecuted() = runTest {
        val s = FakeScreen()
        val m = FakeModel(tools(call("phone_tap", """{"x":500,"y":500}"""), call("phone_back")), finish())
        loop(m, s).run("t")
        assertEquals(listOf("tapXY 540 1201"), s.calls)
        val results = m.requests[1].withoutRuntime().last().results()
        assertEquals(2, results.size)
        assertEquals("parallel_tool_call_skipped", results[1].json().s("error"))
        assertTrue(results[1].isError)
        assertFalse(results[0].isError)
    }

    @Test fun unknownToolReturnsErrorToModel() = runTest {
        val m = FakeModel(tools(call("fly")), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m).run("t"))
        val r = m.requests[1].withoutRuntime().last().results().single()
        assertTrue(r.isError)
        assertEquals("unknown_tool", r.json().s("error"))
    }

    @Test fun authErrorStopsWithReason() = runTest {
        val r = loop(FakeModel(ModelError.Auth())).run("t")
        assertTrue(r is TaskState.Failed && "API Key" in r.reason && r.kind == FailKind.Model)
    }

    @Test fun otherFatalModelErrorsFailWithoutRetry() = runTest {
        for (e in listOf(ModelError.QuotaExceeded(), ModelError.ModelNotFound(), ModelError.ContentRefused())) {
            val m = FakeModel(e, finish())
            val r = loop(m).run("t")
            assertTrue("$e", r is TaskState.Failed && (r as TaskState.Failed).reason.isNotBlank())
            assertEquals(1, m.requests.size)
        }
    }

    @Test fun rateLimitedFollowsRetryAfter() = runTest {
        val m = FakeModel(ModelError.RateLimited(5000), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertTrue("cooldown should be close to Retry-After: $currentTime", currentTime in 4_000..5_000)
    }

    // Transient failures (rate limiting, network, 5xx) share one retry budget and wait with exponential backoff: 1 s, 2 s, 4 s
    @Test fun transientErrorsShareBoundedBackoff() = runTest {
        val m = FakeModel(ModelError.RateLimited(null), ModelError.Network(), ModelError.Server(503), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertEquals(4, m.requests.size)
        assertTrue("backoff should total close to 7s: $currentTime", currentTime in 6_000..7_000)

        val always = FakeModel(ModelError.Network(), ModelError.Network(), ModelError.Network(), ModelError.Network(), finish())
        val r = loop(always).run("t")
        assertTrue("$r", r is TaskState.Failed && r.kind == FailKind.Model)
        assertEquals(4, always.requests.size)
    }

    @Test fun serverErrorsRetryOnly5xx() = runTest {
        val down = FakeModel(ModelError.Server(500), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(down).run("t"))
        assertEquals(2, down.requests.size)

        val bad = FakeModel(ModelError.Server(400), finish())
        val r = loop(bad).run("t")
        assertTrue("$r", r is TaskState.Failed && "400" in r.reason)
        assertEquals(1, bad.requests.size)
    }

    // Every retry goes into the run log: the failure name, which attempt, and how long it waited
    @Test fun retriesAreRecorded() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
            override fun media(mediaId: Int, png: ByteArray) = Unit
        }
        loop(FakeModel(ModelError.Server(502), ModelError.RateLimited(1500), finish()), sink = sink).run("t")
        val retries = events.filterIsInstance<RunEvent.State>().filter { it.name == "model_retry" }.map { it.detail }
        assertEquals(listOf("main server_502 #1 1000ms", "main rate_limited #2 1500ms"), retries)
    }

    // Cancel the task during the backoff wait: no further request is sent and the task ends as cancelled
    @Test fun cancelDuringBackoff() = runTest {
        val m = FakeModel(ModelError.Server(503), finish())
        val l = loop(m)
        val run = async { l.run("t") }
        runCurrent()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(1, m.requests.size)
    }

    @Test fun unexpectedModelExceptionFails() = runTest {
        val l = loop(FakeModel(IllegalStateException("boom")))
        assertEquals(TaskState.Failed("Model call failed: boom", FailKind.Model), l.run("t"))
        assertEquals(TaskState.Failed("Model call failed: boom", FailKind.Model), l.state.value)
    }

    @Test fun rateLimitWaitIsCapped() = runTest {
        loop(FakeModel(ModelError.RateLimited(600_000), finish())).run("t")
        assertTrue("cooldown should be capped near 30s: $currentTime", currentTime in 29_000..30_000)
    }

    // The streamed reply is cut off before it finishes: the reply has not entered the history and no tool has run, so it is resent as is; the partial text in the UI is cleared before the retry
    @Test fun streamCutIsRetriedWithoutKeepingPartialText() = runTest {
        lateinit var l: AgentLoop
        val previews = mutableListOf<String?>()
        val requests = mutableListOf<List<Message>>()
        fun preview() = (l.state.value as? TaskState.Running)?.preview
        val model = object : StreamingChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("should use streaming")
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                requests += messages
                if (requests.size == 1) {
                    onText("半句")
                    previews += preview()
                    throw ModelError.StreamCut()
                }
                previews += preview()
                onText(DONE)
                return finish()
            }
        }
        l = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", retry = FIXED_RETRY, streamReplies = true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        assertEquals(listOf("半句", null), previews)
        assertEquals(2, requests.size)
        assertEquals(requests[0], requests[1])
        assertEquals(1000, currentTime)
    }

    @Test fun streamedProgressBlockIsNeverShown() = runTest {
        lateinit var l: AgentLoop
        val previews = mutableListOf<String?>()
        fun preview() = (l.state.value as? TaskState.Running)?.preview
        val block = """<task_progress>{"criteria":["已保存"],"complete":false}</task_progress>"""
        var calls = 0
        val model = object : StreamingChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("should use streaming")
            override suspend fun completeStreaming(
                system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
            ): ModelReply {
                if (calls++ > 0) {
                    onText(DONE)
                    return finish()
                }
                onText("保存"); previews += preview()
                onText("保存<task_pro"); previews += preview()
                onText("保存${block}继续"); previews += preview()
                return reply(Part.Text("保存${block}继续"), call("phone_observe"))
            }
        }
        l = AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", streamReplies = true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        assertEquals(listOf("保存", "保存", "保存继续"), previews)
    }

    @Test fun networkErrorRetriesAfterOneSecond() = runTest {
        val m = FakeModel(ModelError.Network(), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m).run("t"))
        assertEquals(1000, currentTime)
    }

    @Test fun reportFailureEndsFailedWithMessage() = runTest {
        val r = loop(FakeModel(tools(call("end_not_done", """{"message":"没有库存"}""")))).run("t")
        assertEquals(TaskState.Failed("没有库存", FailKind.GaveUp), r)
    }

    @Test fun cancelEndsWithCancelled() = runTest {
        val l = loop(FakeModel(tools(call("hand_to_user", """{"reason":"other","message":"m"}"""))))
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, l.state.value)
    }

    @Test fun cancelDuringModelCall() = runTest {
        val hang = object : ChatModel {
            override val capabilities = Capabilities(true, false, 1000, false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = awaitCancellation()
        }
        val l = loop(hang)
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
    }

    @Test fun gateIsCalledBeforeWritingTools() = runTest {
        val s = FakeScreen()
        val open = CompletableDeferred<Unit>()
        var gates = 0
        val m = FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"x":1,"y":1}""")), finish())
        val l = loop(m, s) { gates++; open.await() }
        val run = async { l.run("t") }
        advanceUntilIdle()
        // observe does not go through the gate; the gate suspends before tap, so tap has not run yet.
        assertEquals(1, gates)
        assertTrue(s.calls.isEmpty())
        open.complete(Unit)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals(1, s.calls.size)
        assertEquals(1, gates) // finish is not a write operation
    }

    @Test fun gateReportsWaitingUnlockThenRunning() = gateReports(TaskState.Held(GateReason("waiting_unlock")), clearWithNull = true)

    @Test fun gateReportsPausedAndRunningIsRestoredWithoutNull() = gateReports(TaskState.Held(GateReason("paused", "手机被拿走", resumable = true)), clearWithNull = false)

    /** While suspended, the externally visible state is the one the gate reports; once released (whether or not null is passed), Running is already restored when the tool executes. */
    private fun gateReports(waiting: TaskState, clearWithNull: Boolean) = runTest {
        val open = CompletableDeferred<Unit>()
        var stateDuringTap: TaskState? = null
        lateinit var l: AgentLoop
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                stateDuringTap = l.state.value
                return ActionResult.Ok()
            }
        }
        val m = FakeModel(reply(Part.Text("点一下"), call("phone_tap", """{"x":1,"y":1}""")), finish())
        l = AgentLoop(m, toolDispatcher(screen), "B", gate = { setWaiting ->
            setWaiting(waiting)
            open.await()
            if (clearWithNull) setWaiting(null)
        })
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertEquals(waiting, l.state.value)
        assertNull(stateDuringTap)
        open.complete(Unit)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals(TaskState.Running(1, "点一下", "phone_tap", narration = "点一下"), stateDuringTap)
    }

    @Test fun repeatedTapWithProgressCanExceedOldStepLimit() = runTest {
        // While the screen keeps changing, the same action may exceed the old 60-step limit
        val s = FakeScreen().apply { result = ActionResult.Ok(changed = true) }
        val m = FakeModel(*(1..65).map { tools(call("phone_tap", """{"x":105,"y":106}""")) }.toTypedArray(), finish())
        loop(m, s, maxSteps = Int.MAX_VALUE).run("t")
        assertEquals(65, s.calls.size)
    }

    @Test fun typingTheSameQueryAgainSuggestsReportFailure() = runTest {
        val m = FakeModel(
            tools(call("phone_type_text", """{"text":"回溯"}""")), tools(call("phone_type_text", """{"text":"时光"}""")),
            tools(call("phone_type_text", """{"text":" 回溯 ","ref":"@e2","snapshotId":"s9"}""")), finish(),
        )
        loop(m).run("t")
        assertNull(m.requests[1].withoutRuntime().last().results().single().json()["advice"])
        assertNull(m.requests[2].withoutRuntime().last().results().single().json()["advice"])
        val again = m.requests[3].withoutRuntime().last().results().single().json()
        assertTrue(again.s("advice"), "回溯" in again.s("advice") && "end_not_done" in again.s("advice"))
    }

    @Test fun sameQueryInAnotherAppIsNotARepeat() = runTest {
        val pkgs = ArrayDeque(listOf("com.a", "com.b"))
        val s = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s${pkgs.size}", pkgs.removeFirst(), emptyList(), 1080, 2400)
        }
        val m = FakeModel(
            tools(call("phone_observe")), tools(call("phone_type_text", """{"text":"天气"}""")),
            tools(call("phone_observe")), tools(call("phone_type_text", """{"text":"天气"}""")), finish(),
        )
        AgentLoop(m, toolDispatcher(s), "BASE").run("t")
        assertNull(m.requests[4].withoutRuntime().last().results().single().json()["advice"])
    }

    @Test fun repeatedPasswordIsNeverEchoedInAdvice() = runTest {
        val s = FakeScreen().apply { result = ActionResult.Ok(secret = true) }
        val m = FakeModel(tools(call("phone_type_text", """{"text":"pw123456"}""")), tools(call("phone_type_text", """{"text":"pw123456"}""")), finish())
        loop(m, s).run("t")
        assertNull(m.requests[2].withoutRuntime().last().results().single().json()["advice"])
    }

    @Test fun progressCheckSuggestsGivingUpHonestlyHalfwayThrough() = runTest {
        // A different wait each step, so the repeat guard is not triggered
        val m = FakeModel(*(1..20).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray())
        loop(m, maxSteps = 20).run("t")
        fun notes(i: Int) = m.requests[i].filter { it.role == Role.User }.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        // requests[i] is the request sent for step i+1: there is no reminder before 10 steps are used, and one appears at 10 steps and again at 15
        assertTrue(notes(9).none { "end_not_done" in it })
        val half = notes(10).filter { "end_not_done" in it }
        assertEquals(1, half.size)
        assertTrue(half.single(), "10 of 20" in half.single())
        assertEquals(1, notes(14).count { "end_not_done" in it })
        assertEquals(2, notes(15).count { "end_not_done" in it })
    }

    @Test fun giveUpRemindersTellAnAnswerFromAFailure() = runTest {
        // The wanted result cannot be produced if a needed feature, item or value is missing; only for a yes/no question about whether something exists is an explicit "no" from the app's search the answer, and then answer directly and quote the text that was read
        fun separates(s: String) = "feature" in s && "end_not_done" in s && "yes/no question about whether something exists" in s && "definitive" in s
        val taps = mutableListOf<Any>()
        repeat(6) { taps += tools(call("phone_tap", """{"x":105,"y":106}""")) }
        taps += finish()
        val m = FakeModel(*taps.toTypedArray())
        loop(m, FakeScreen().apply { result = ActionResult.Ok(changed = true) }).run("t")
        assertNull(m.requests[6].withoutRuntime().last().results().single().json()["advice"])

        val typed = FakeModel(tools(call("phone_type_text", """{"text":"张三"}""")), tools(call("phone_type_text", """{"text":"张三"}""")), finish())
        loop(typed).run("t")
        assertNull(typed.requests[2].withoutRuntime().last().results().single().json()["advice"])

        val w = FakeModel(*(1..11).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray(), finish())
        loop(w, maxSteps = 20).run("t")
        val check = w.requests[10].filter { it.role == Role.User }.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
            .single { "Progress check" in it }
        assertTrue(check, separates(check))
    }

    @Test fun unchangedElementListClosesOutWithoutNewData() = runTest {
        // After the same elements are read three times in a row, close out as no-new-data first; if the closeout still only acts without giving a result, stop via the guard.
        var i = 0
        val script = mutableListOf<Any>(tools(call("phone_observe")))
        repeat(12) {
            script += tools(call("phone_tap", """{"x":${(i++) * 80},"y":10}"""))
            script += tools(call("phone_observe"))
        }
        val r = loop(FakeModel(*script.toTypedArray())).run("t")
        assertTrue("$r", r is TaskState.Failed && "No new data was read 3 times in a row" in r.reason)
    }

    @Test fun visibleButUnreadableTreeStopsAfterThreeNoEffectTaps() = runTest {
        val s = object : ScreenPort by FakeScreen() {
            private val empty = ScreenSnapshot("visible", "com.example", emptyList(), 1080, 2400)
            override suspend fun snapshot() = empty
            override suspend fun peek() = empty
        }
        val m = FakeModel(*(1..4).map { tools(call("phone_tap", """{"x":500,"y":500}""")) }.toTypedArray())
        val r = AgentLoop(m, toolDispatcher(s), "BASE", retry = FIXED_RETRY).run("t")
        assertTrue("$r", r is TaskState.Failed && r.kind == FailKind.Guard)
        assertTrue("$r", r is TaskState.Failed && "may not respond" in r.reason)
        assertEquals(3, m.requests.size)
    }

    @Test fun screenshotsFollowToolResultsWhenVisionOn() = runTest {
        val m = FakeModel(tools(call("phone_observe")), finish())
        loop(m, vision = true).run("t")
        val msgs = m.requests[1]
        assertEquals(Role.Tool, msgs[2].role)
        assertEquals(Role.User, msgs[3].role)
        assertTrue(msgs[3].parts.any { it is Part.Image })

        val noVision = FakeModel(tools(call("phone_observe")), finish())
        loop(noVision).run("t")
        assertEquals(3, noVision.requests[1].withoutRuntime().size)
    }

    @Test fun recalledScreenshotsAreSentAsImagesAndCountAsNew() = runTest {
        val recall = call("view_past_screens", """{"ids":[1]}""")
        val m = FakeModel(*Array(4) { tools(call("phone_observe")) }, tools(recall), finish())
        var reads = 0
        val changing = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                return ScreenSnapshot("snap$reads", "com.x", listOf(label("@e1", "第 $reads 屏")), 1080, 2400)
            }
        }
        AgentLoop(m, toolDispatcher(changing, visionEnabled = true), "BASE", retry = FIXED_RETRY).run("t")
        val msgs = m.requests[5].withoutRuntime()
        val at = msgs.indexOfFirst { it.parts.any { p -> p is Part.ToolResult && p.callId == recall.id } }
        val after = msgs[at + 1]
        assertEquals(Role.User, after.role)
        assertEquals(1, after.parts.count { it is Part.Image })
        assertTrue(after.parts.any { it is Part.Text && "earlier" in it.text })
        // The recalled screenshot is the latest frame, so it is kept; the pruning line has not moved yet (5 round trips), and the images from earlier screen reads are all still there
        assertEquals(4, msgs.take(at).sumOf { msg -> msg.parts.count { it is Part.Image } })

        val text = FakeModel(tools(call("phone_observe")), tools(call("view_past_screens", """{"ids":[1]}""")), finish())
        loop(text).run("t")
        val last = text.requests[2]
        assertTrue(last.none { msg -> msg.parts.any { it is Part.Image } })
        assertTrue("elementsText" in last.withoutRuntime().last().results().single().content)
        // Recall is a read-only diagnostic tool and does not count as an action
        assertTrue(verb("view_past_screens") in DIAGNOSTIC_VERBS)
    }

    @Test fun coffeeTaskConfirmsGoalsWithoutCountingClicksOrClosingOnFiveNotes() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        fun block(json: String) = "<task_progress>$json</task_progress>"
        fun step(seconds: Double, narration: String, json: String? = null) =
            reply(Part.Text(narration + json?.let(::block).orEmpty()), call("wait", """{"seconds":$seconds}"""))
        val address = """{"confirmed":[{"id":1,"evidence":"高德地址卡显示天目里地址"}]}"""
        val m = FakeModel(
            reply(Part.Text(block("""{"criteria":["确认地址","确认店名","根据五篇笔记给出推荐"],"metric":{"name":"步骤完成","done":0,"total":3}}""")),
                call("phone_launch_app", """{"packageName":"com.x","reason":"查看地址卡"}""")),
            step(0.2, "地址已核实", address),
            step(0.3, "打开点评"),
            step(0.4, "打开店铺页", address), // Re-reporting the address is not taken as confirming the store name
            reply(Part.Text(block("""{"confirmed":[{"id":2,"evidence":"店铺页标题为山池咖啡"}]}""")), call("phone_observe")),
            step(0.6, "笔记已读完", """{"metric":{"name":"已读笔记","done":5,"total":5},"complete":true}"""),
            finish(block("""{"confirmed":[{"id":3,"evidence":"根据五篇笔记给出推荐：拿铁"}]}""") + "推荐拿铁"),
        )
        val end = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", sink = sink).run("找咖啡馆并推荐咖啡")
        com.wangzan101.mika.core.testing.assertAnswered("推荐拿铁", end)
        val updates = events.filterIsInstance<RunEvent.Criteria>()
        assertEquals(listOf(0, 1, 2, 2, 3), updates.map { it.confirmedIds?.size })
        assertEquals(listOf(false, false, false, false, true), updates.map { it.complete })
        assertEquals(7, m.requests.size)
        assertTrue(events.none { it is RunEvent.State && it.name == "task_progress_plan_reminder" })
        assertTrue("phone_observe" in m.offeredTools.last())
        fun runtime(i: Int) = m.requests[i].last().parts.filterIsInstance<Part.Text>().last().text
        assertTrue("2: " in runtime(4) && "1: " !in runtime(4))
        assertTrue("report its ID and evidence" in runtime(4))
        assertTrue("report its ID and evidence" !in runtime(5))
        assertTrue("3: " in runtime(6) && "2: " !in runtime(6))
    }

    @Test fun missingChecklistCanBeBackfilledOnTheNextCoffeeTaskReply() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun listApps() = listOf("com.autonavi.minimap" to "高德地图")
        }
        val m = FakeModel(
            tools(call("phone_list_apps")), // The first call had no checklist yet, but the tool result can already confirm the first item.
            reply(Part.Text("""<task_progress>{"criteria":["确认有高德地图","确认咖啡店名","给出推荐"],"confirmed":[{"id":1,"evidence":"已安装列表有高德地图"}]}</task_progress>"""),
                call("wait", """{"seconds":0.2}""")),
            finish("高德已安装，店名和推荐尚未核实。"),
        )
        AgentLoop(m, toolDispatcher(screen), "BASE", sink = sink).run("确认高德已安装，再找咖啡馆并给出推荐")
        val runtime = m.requests.map { request -> request.last().parts.filterIsInstance<Part.Text>().last().text }
        assertEquals(3, runtime.size)
        assertFalse("No goal checklist" in runtime[0])
        assertTrue("No goal checklist" in runtime[1] && "<task_progress>" in runtime[1])
        assertTrue("Pending goal IDs: 2:" in runtime[2] && "3:" in runtime[2])
        val reported = events.filterIsInstance<RunEvent.Criteria>().single()
        assertEquals(setOf(1), reported.confirmedIds)
        assertEquals(3, reported.criteria.size)
    }

    @Test fun ignoredChecklistReminderDoesNotRepeatOrInventProgress() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val m = FakeModel(finish(""), tools(call("phone_list_apps")), tools(call("wait", """{"seconds":0.2}""")), finish())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, sink = sink).run("查店"))
        val runtime = m.requests.map { it.last().parts.filterIsInstance<Part.Text>().last().text }
        assertEquals(listOf(false, false, true, false), runtime.map { "No goal checklist" in it })
        assertTrue(events.none { it is RunEvent.Criteria })
        assertEquals(1, events.filterIsInstance<RunEvent.State>().count { it.name == "task_progress_plan_reminder" })
    }

    @Test fun completedQuantityClosesOutInsteadOfRecheckingForever() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val progress = """<task_progress>{"criteria":["两条都已保存"],"metric":{"name":"已保存","done":2,"total":2},"complete":false}</task_progress>保存最后一条"""
        val m = FakeModel(
            reply(Part.Text(progress), call("phone_observe")),
            finish("""<task_progress>{"complete":true}</task_progress>$DONE"""),
            tools(call("phone_observe")), // The old behaviour would keep self-verifying here
        )
        val end = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", sink = sink).run("保存两条")
        com.wangzan101.mika.core.testing.assertAnswered(DONE, end)
        assertEquals(2, m.requests.size)
        assertEquals(listOf("end_not_done"), m.offeredTools.last())
        val criteria = events.filterIsInstance<RunEvent.Criteria>().last()
        assertTrue(criteria.complete)
        assertEquals(RunEvent.Progress("已保存", 2, 2), criteria.progress)
        assertTrue(m.requests.last().flatMap { it.parts }.filterIsInstance<Part.Text>().none { "task_progress" in it.text })
    }

    @Test fun repeatedCollectionWithoutNewDataStopsAndSummarizes() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val progress = """<task_progress>{"criteria":["收集全部记录"],"metric":{"name":"已收集","done":1,"boundary":"第 1 条"}}</task_progress>开始收集"""
        val m = FakeModel(
            reply(Part.Text(progress), call("phone_observe")),
            tools(call("phone_observe")),
            tools(call("phone_observe")),
            tools(call("phone_observe")),
            finish(),
            tools(call("phone_scroll", """{"direction":"down"}""")), // Must not run after the stall closeout
        )
        val end = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", sink = sink).run("收集记录")
        com.wangzan101.mika.core.testing.assertAnswered(DONE, end)
        assertEquals(5, m.requests.size)
        assertEquals(listOf("end_not_done"), m.offeredTools.last())
        assertEquals(1, events.filterIsInstance<RunEvent.State>().count { it.name == "no_new_data" && it.detail == "3" })
        assertEquals("第 1 条", events.filterIsInstance<RunEvent.Criteria>().last().progress?.boundary)
        assertTrue(m.requests.last().flatMap { it.parts }.filterIsInstance<Part.Text>().any {
            "no new content" in it.text && "Latest confirmed progress: 已收集 · 1 · 第 1 条" in it.text
        })
    }

    @Test fun runningStateCarriesStepAndNote() = runTest {
        val seen = mutableListOf<TaskState>()
        lateinit var l: AgentLoop
        val inner = FakeModel(reply(Part.Text("打开设置"), call("phone_observe")), finish())
        val spy = object : ChatModel by inner {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                seen += l.state.value
                return inner.complete(system, messages, tools)
            }
        }
        l = AgentLoop(spy, toolDispatcher(FakeScreen()), "B")
        l.run("t")
        assertEquals(TaskState.Running(1, ""), seen[0])
        assertEquals(TaskState.Running(2, "打开设置"), seen[1])
    }

    private fun distinctWaits(n: Int, from: Int = 1) = (from until from + n).map { tools(call("wait", """{"seconds":${it / 10.0}}""")) }

    @Test fun contextTooLongRetriesOnceCompressed() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        val script = distinctWaits(10) + ModelError.ContextTooLong() + distinctWaits(1, from = 11) + finish()
        val m = FakeModel(*script.toTypedArray())
        val r = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60, sink = sink).run("t")
        com.wangzan101.mika.core.testing.assertAnswered(DONE, r)
        // The 11th call reports too long; compress harder and retry once: keep the latest 6 round trips and fold the previous 4 into a summary
        assertEquals(13, m.requests.size)
        assertEquals(21, m.requests[10].withoutRuntime().size)
        val retried = m.requests[11].withoutRuntime()
        assertEquals(1 + 1 + 6 * 2, retried.size)
        val summary = retried[1].plainText()
        assertTrue(summary, summary.startsWith(ContextCompressor.HEADER) && "Step 4 wait(0.4) → applied" in summary && "Step 5 " !in summary)
        // Only appends after that: the compressed result stays as is at the front, and one more round trip is appended
        assertEquals(1 + 1 + 7 * 2, m.requests[12].withoutRuntime().size)
        assertEquals(retried, m.requests[12].withoutRuntime().take(retried.size))
        assertEquals(listOf(4), events.filterIsInstance<RunEvent.Compressed>().map { it.foldedSteps })

        // Still too long after compressing: fail without retrying
        val again = FakeModel(*distinctWaits(10).toTypedArray(), ModelError.ContextTooLong(), ModelError.ContextTooLong())
        val failed = loop(again).run("t")
        assertTrue(failed is TaskState.Failed && failed.kind == FailKind.Model)
        assertEquals(12, again.requests.size)
    }

    @Test fun secondContextTooLongAfterAggressiveCompactionRecompacts() = runTest {
        // A few more steps after the aggressive compaction overflow again: compress the full history aggressively once more and resend; the task completes as usual
        val script = distinctWaits(10) + ModelError.ContextTooLong() + distinctWaits(4, from = 11) + ModelError.ContextTooLong() +
            distinctWaits(1, from = 15) + finish()
        val m = FakeModel(*script.toTypedArray())
        val r = AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60).run("t")
        com.wangzan101.mika.core.testing.assertAnswered(DONE, r)
        val overflowed = m.requests[15].withoutRuntime()
        val retried = m.requests[16].withoutRuntime()
        assertTrue("${retried.size} < ${overflowed.size}", retried.size < overflowed.size)
        val summary = retried[1].plainText()
        assertTrue(summary, summary.startsWith(ContextCompressor.HEADER) && "Step 8 " in summary && "Step 9 " !in summary)
    }

    @Test fun contextTooLongWithNothingToFoldFailsWithoutResending() = runTest {
        val m = FakeModel(*distinctWaits(3).toTypedArray(), ModelError.ContextTooLong(), finish())
        val r = loop(m).run("t")
        assertTrue(r is TaskState.Failed && r.kind == FailKind.Model)
        assertEquals(4, m.requests.size)
    }

    @Test fun compressedEventOnlyWhenFoldingChanges() = runTest {
        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
        // The context is tiny: the base prompt and tool definitions alone exceed the trigger point, and even the lightest compaction cannot get back under it, so every extra round trip folds one more step;
        // the following round replied with empty text (nudged once), the fold count is unchanged and no event is recorded
        val m = FakeModel(*distinctWaits(13).toTypedArray(), reply(Part.Text("")), finish(), contextTokens = 100)
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", 60, sink = sink).run("t")
        assertEquals(15, m.requests.size)
        assertEquals((1..12).toList(), events.filterIsInstance<RunEvent.Compressed>().map { it.foldedSteps })
    }

    @Test fun longTaskStaysUnderBudget() = runTest {
        // Each screen read is about 6000 characters; 60 steps with a 32000-token context: the estimate for every request sent (multiplied by the uncalibrated ratio) stays within the usable window
        var n = 0
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                n++
                val els = (1..40).map { UiElement("@e$it", "text", "第${n}次读屏的第${it}项" + "字".repeat(140), 10, 10, listOf(0, 0, 9, 9), null, false, false, false) }
                return ScreenSnapshot("snap$n", "com.x", els, 1080, 2400)
            }
        }
        // Alternate between the two kinds of screen read to avoid triggering the repeated-action guard
        val script = (1..59).map { tools(call(if (it % 2 == 1) "phone_observe" else "phone_list_elements")) } + finish()
        val m = FakeModel(*script.toTypedArray(), contextTokens = 32_000)
        val l = AgentLoop(m, toolDispatcher(screen), "BASE", 60)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("长任务"))
        assertEquals(60, m.requests.size)
        // Read the screen once to verify that an observation really is ~6000 characters: once the phone group expands, this read is clipped into a placeholder by compression, so the version sent to the model cannot be inspected
        val rawObservation = com.wangzan101.mika.core.screen.uistate.ScreenJson.elements(screen.snapshot(), Int.MAX_VALUE).first
        assertTrue("observation should be about 6000+ chars, was ${rawObservation.toString().length}", rawObservation.toString().length >= 6000)
        val worst = m.requests.maxOf { TokenEstimate.of(it) }
        assertTrue("sent about $worst tokens", worst * ContextCompressor.UNCALIBRATED_SCALE <= ContextLimits.of(32_000).usable)
        // Clipping only the old screen reads is already over budget (the compressor only folds when over); after folding it is back within budget
        assertTrue(m.requests.last().any { msg -> msg.plainText().startsWith(ContextCompressor.HEADER) })
    }

    @Test fun priorTurnsPrecedeTask() = runTest {
        val m = FakeModel(finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("再查一次", listOf(Turn("查天气", "晴", "succeeded")))
        val first = m.requests[0].withoutRuntime()
        assertEquals(2, first.size)
        assertEquals(Role.User, first[0].role)
        assertTrue("查天气" in (first[0].parts.single() as Part.Text).text)
        assertEquals("再查一次", (first[1].parts.single() as Part.Text).text)

        val none = FakeModel(finish())
        AgentLoop(none, toolDispatcher(FakeScreen()), "BASE").run("单独的任务")
        assertEquals(1, none.requests[0].withoutRuntime().size)
    }

    @Test fun loopIsAppendOnlyBelowTheThreshold() = runTest {
        val m = FakeModel(*Array(12) { tools(call("phone_observe")) }, finish())
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE").run("t")
        fun omitted(ms: List<Message>) = ms.flatMap { it.results() }.count { HistoryPruner.OMITTED in it.content }
        // The compression trigger is never crossed: old screen reads are never clipped, and every request is a prefix of the next
        assertTrue(m.requests.all { omitted(it) == 0 })
        m.requests.zipWithNext().forEach { (a, b) -> assertEquals(a, b.subList(0, a.size)) }
    }
}
