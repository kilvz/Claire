package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.screen.vision.FRAME_A
import com.wangzan101.mika.core.screen.vision.JvmTestImageOps
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.screen.vision.says
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * The chat page with Zhang San: title, input box, bottom-right button; the chat holds a pending "Transfer ¥25" payment: the amount due is on screen, so "Submit order" here spends money and needs approval first when asked.
 * With nothing typed the button is "+", after typing it becomes "Submit order" (as in many chat apps).
 * [snapshot] registers the screen read (ids s1, s2, ...), [peek] does not register (ids p1, p2, ...).
 */
private class ChatScreen(var app: String = "com.chat") : ScreenPort {
    val taps = mutableListOf<String>()
    var reads = 0
    var peeks = 0
    var typed = ""
    var png: ByteArray? = null
    /** After how many peeks the screen can no longer be read. */
    var peekFailsAfter = Int.MAX_VALUE
    /** Custom-drawn UI: the screen read yields no elements at all. */
    var blank = false
    /** Replaces the label on the third button (for example a pay button). */
    var button: String? = null
    private fun read(id: String) = ScreenSnapshot(id, app, if (blank) emptyList() else listOf(
        UiElement("@e1", "text", "张三", 500, 100, listOf(0, 0, 999, 80), null, false, false, false),
        UiElement("@e2", "edit", typed.ifEmpty { "发消息" }, 400, 2300, listOf(0, 900, 790, 999), null, true, true, false, hint = typed.isEmpty()),
        UiElement("@e3", "button", button ?: if (typed.isEmpty()) "+" else "提交订单", 950, 2300, listOf(800, 900, 990, 990), null, true, false, false),
        UiElement("@e4", "text", "转账 ¥25", 500, 1000, listOf(0, 400, 600, 460), null, false, false, false),
    ), 1080, 2400)
    override suspend fun snapshot(): ScreenSnapshot = read("s${++reads}")
    override suspend fun peek(): ScreenSnapshot {
        if (peeks >= peekFailsAfter) throw IllegalStateException("screen unreadable")
        return read("p${++peeks}")
    }
    override suspend fun screenshotPng(): ByteArray? = png
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += ref; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult { typed = text; return ActionResult.Ok(changed = true) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back() = ActionResult.Ok()
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.chat" to "Chat")
}

private class ListSink : RunSink {
    val events = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { events += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
private fun tools(vararg c: Part.ToolCall) = ModelReply(c.toList(), StopReason.ToolUse, null)
private const val DONE = "发好了"
private val OBSERVE get() = tools(call("phone_observe"))
private val FINISH get() = com.wangzan101.mika.core.testing.answerReply("发好了")
private val TYPE get() = tools(call("phone_type_text", """{"text":"明天见","ref":"@e2","snapshotId":"s1"}"""))
private fun tapSend(snapshot: String = "s1") = tools(call("phone_tap", """{"ref":"@e3","snapshotId":"$snapshot"}"""))
private fun ask(action: String = "order", target: String = " 张三 ", content: String = "明天见") =
    tools(call("request_confirmation", """{"action":"$action","target":"$target","content":"$content","amount":"¥25","app":"com.chat"}"""))
private fun failure(msg: String) = tools(call("end_not_done", """{"message":"$msg"}"""))

/** The last tool result in the [i]-th request. */
private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject

private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

/** Each step computes its reply from the conversation so far: an @vN's snapshotId is random, so it has to be taken from the previous tool result. */
private class StepModel(vararg steps: (List<Message>) -> ModelReply) : ChatModel {
    override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
    private val steps = steps.toMutableList()
    val results = mutableListOf<JsonObject>()
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        messages.lastOrNull { it.role == Role.Tool }?.parts?.filterIsInstance<Part.ToolResult>()?.firstOrNull()
            ?.let { results += Json.parseToJsonElement(it.content).jsonObject }
        return steps.removeAt(0)(messages)
    }
}

private fun step(r: ModelReply): (List<Message>) -> ModelReply = { r }
private fun locate(target: String, text: String) = step(tools(call("phone_find_visually", """{"target":"$target","text":"$text"}""")))
private fun StepModel.tapLocated(): (List<Message>) -> ModelReply = {
    val r = results.last()
    tools(call("phone_tap", """{"ref":"${r.s("ref")}","snapshotId":"${r.s("snapshotId")}"}"""))
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopGateTest {
    private fun loop(m: FakeModel, s: ChatScreen, sink: RunSink = RunSink.NONE, gate: ConfirmationGate? = ConfirmationGate(), maxSteps: Int = 60) =
        AgentLoop(m, toolDispatcher(s), "BASE", maxSteps = maxSteps, sink = sink, confirmations = gate)

    @Test fun blockedWithoutConfirmation() = runTest {
        val s = ChatScreen(); val sink = ListSink()
        val m = FakeModel(OBSERVE, TYPE, tapSend(), tools(call("phone_type_text", """{"text":"明天见","pressEnter":true}""")), failure("没发"))
        assertEquals(TaskState.Failed("没发", FailKind.GaveUp), loop(m, s, sink).run("给张三发明天见"))
        assertTrue(s.taps.isEmpty())
        // in the screen read the model holds (s1) the button is still "+", but it has become "Submit order" by now: judge by the current state
        val r = m.result(3)
        assertEquals("false", r.s("ok")); assertEquals("confirmation_required", r.s("error"))
        assertEquals("order", r.s("action")); assertEquals("提交订单", r.s("matched"))
        assertTrue(r.s("message").startsWith("This step spends money (order), so it was not run. First call request_confirmation"))
        assertTrue(r.s("message"), "uses it up whether it succeeds or not" in r.s("message"))
        // enter after typing means send: sending a message spends no money, so it runs as usual even with an amount on screen
        assertEquals(null, m.result(4)["error"])
        assertTrue("request_confirmation" in m.offeredTools[0])
        assertEquals(listOf("required"), sink.events.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    // C1: the UI changed after the screen read ("+" became "Submit order"), so a coordinate tap without a target must be blocked too
    @Test fun coordinateTapJudgedOnFreshScreen() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, tools(call("phone_tap", """{"x":900,"y":950}""")), TYPE, tools(call("phone_tap", """{"x":900,"y":950}""")), failure("没发"))
        loop(m, s).run("t")
        assertEquals("「+」照常点", 1, s.taps.size)
        assertEquals("confirmation_required", m.result(4).s("error"))
        assertEquals(1, s.taps.size)
    }

    @Test fun allowedOnceAfterApproval() = runTest {
        val s = ChatScreen(); val sink = ListSink()
        val m = FakeModel(OBSERVE, TYPE, ask(), tapSend(), tapSend(), FINISH)
        val l = loop(m, s, sink)
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        assertEquals(TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "张三", "明天见", "¥25", "com.chat")), l.state.value)
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals("true", m.result(3).s("approved"))
        assertTrue("uses it up whether it succeeds or not" in m.result(3).s("message"))
        assertEquals(listOf("@e3"), s.taps)
        assertEquals("confirmation_required", m.result(5).s("error"))
        assertEquals(listOf(null, "approved", "required"), sink.events.filterIsInstance<RunEvent.Confirmation>().map { it.decision })
    }

    // C3: approval is bound to the declared content; tapping elsewhere in between voids it
    @Test fun approvalBoundToRequestAndNextAction() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, ask(target = "李四"), tapSend(), ask(), tools(call("phone_tap", """{"x":500,"y":40}""")), tapSend(), failure("没发"))
        val l = loop(m, s)
        val run = async { l.run("t") }
        advanceUntilIdle(); l.answerConfirmation(true)
        advanceUntilIdle(); l.answerConfirmation(true)
        run.await()
        val r = m.result(4)
        assertEquals("confirmation_mismatch", r.s("error"))
        assertTrue(r.s("message"), "call request_confirmation again" in r.s("message"))
        // after the second approval it taps the title first (allowed to run), the approval is voided, and the next send needs confirmation again
        assertEquals("confirmation_required", m.result(7).s("error"))
        assertEquals(1, s.taps.size)
    }

    @Test fun declinedReturnsErrorAndModelStops() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, ask(), tapSend(), ask(target = "张三"), failure("用户没同意,没有发送"))
        val l = loop(m, s)
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        l.answerConfirmation(false)
        assertEquals(TaskState.Failed("用户没同意,没有发送", FailKind.GaveUp), run.await())
        assertEquals("confirmation_declined", m.result(3).s("error"))
        assertEquals("The user did not approve. Do not try this action again; report where things stand, then end.", m.result(3).s("message"))
        assertEquals("confirmation_required", m.result(4).s("error"))
        // M2: the same kind of action in the same app is no longer asked about; it is refused outright
        assertEquals("confirmation_declined", m.result(5).s("error"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun blockDoesNotTripFailureCircuit() = runTest {
        val s = ChatScreen()
        val taps = listOf(800 to 900, 850 to 900, 900 to 900, 950 to 900, 800 to 950, 850 to 950)
            .map { (x, y) -> tools(call("phone_tap", """{"x":$x,"y":$y}""")) }
        val m = FakeModel(OBSERVE, TYPE, *taps.toTypedArray(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s).run("t"))
        assertTrue(s.taps.isEmpty())
        for (i in 3..8) {
            val res = m.result(i)
            assertEquals("confirmation_required", res.s("error"))
            assertFalse(res.toString(), "too_many_failures" in res.toString())
        }
    }

    @Test fun mismatchWhenDeclaredAppIsNotOnScreen() = runTest {
        val s = ChatScreen(app = "com.other")
        val m = FakeModel(OBSERVE, ask(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s).run("t"))
        assertEquals("confirmation_mismatch", m.result(2).s("error"))
        // M4: the foreground check uses peek and does not register an id
        assertEquals(1, s.reads)
    }

    // I1
    @Test fun paymentRequestIsRefused() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, ask(action = "pay"), FINISH)
        val l = loop(m, s)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        val r = m.result(2)
        assertEquals("payment_requires_takeover", r.s("error"))
        assertTrue(r.s("message"), "hand_to_user" in r.s("message"))
    }

    @Test fun payTapBlockedPointsToTakeoverOnly() = runTest {
        val s = ChatScreen().apply { button = "立即支付" }
        val m = FakeModel(OBSERVE, tapSend(), FINISH)
        loop(m, s).run("t")
        assertTrue(s.taps.isEmpty())
        val r = m.result(2)
        assertEquals("confirmation_required", r.s("error")); assertEquals("pay", r.s("action"))
        val msg = r.s("message")
        // for a payment the message gives a single path: it does not tell you to request confirmation first and then pay without it
        assertTrue(msg, "First call request_confirmation" !in msg)
        assertTrue(msg, "hand_to_user" in msg && "reason=payment" in msg)
        assertTrue(msg, "item" in msg && "amount" in msg && "address" in msg)
    }

    @Test fun cancelWhileWaitingConfirmation() = runTest {
        val m = FakeModel(OBSERVE, ask())
        val l = loop(m, ChatScreen())
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value is TaskState.WaitingConfirmation)
        l.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, l.state.value)
    }

    @Test fun waitingDoesNotUseSteps() = runTest {
        val s = ChatScreen()
        val l = loop(FakeModel(OBSERVE, TYPE, ask(), tapSend(), FINISH), s, maxSteps = 5)
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals(listOf("@e3"), s.taps)
    }

    @Test fun approvalDoesNotCarryToNextTask() = runTest {
        val s = ChatScreen()
        // if it approved but did not send, it must hand in an answer: it is reminded once, and the second time is accepted as usual
        val m = FakeModel(OBSERVE, TYPE, ask(), FINISH, FINISH, OBSERVE, tapSend("s2"), FINISH)
        val l = loop(m, s)
        val first = async { l.run("t1") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, first.await())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t2"))
        assertTrue(s.taps.isEmpty())
        assertEquals("confirmation_required", m.result(7).s("error"))
    }

    @Test fun withoutGateNothingIsBlocked() = runTest {
        val s = ChatScreen()
        val m = FakeModel(OBSERVE, TYPE, tapSend(), ask(), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, loop(m, s, gate = null).run("t"))
        assertEquals(listOf("@e3"), s.taps)
        assertFalse("request_confirmation" in m.offeredTools[0])
        // M5
        assertEquals("unknown_tool", m.result(4).s("error"))
    }

    // M3
    @Test fun unresolvedTapIsLoggedWithWhere() = runTest {
        val sink = ListSink()
        loop(FakeModel(OBSERVE, tools(call("phone_tap", """{"x":500,"y":500}""")), tools(call("phone_tap", """{"ref":"@e3","snapshotId":"gone"}""")), FINISH), ChatScreen(), sink).run("t")
        val logs = sink.events.filterIsInstance<RunEvent.State>().filter { it.name == "gate_unresolved" }.map { it.detail }
        assertEquals(2, logs.size)
        assertEquals("phone_tap x=500,y=500 freshRead=true", logs[0])
        assertEquals("phone_tap ref=@e3 snapshotId=gone age=unknown", logs[1])
    }

    private fun visionLoop(m: ChatModel, s: ChatScreen, vararg vision: Any, approvals: ApprovalSettings = ApprovalSettings.ASK) = AgentLoop(
        m, toolDispatcher(s, vision = VisionService(says(*vision), JvmTestImageOps)), "BASE", confirmations = ConfirmationGate(),
        approvals = approvals,
    )

    private fun lookFor(target: String) = step(tools(call("phone_find_visually", """{"target":"$target"}""")))

    // a point found by looking at the image on a custom-drawn screen (no elements from the screen read): the vision model sees "Send", which spends no money, so it is tapped directly
    @Test fun locatedSendOnBlankScreenRunsWithoutAsking() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        lateinit var m: StepModel
        m = StepModel(lookFor("右下角的图标"), { m.tapLocated()(it) }, step(failure("停")))
        visionLoop(m, s, """{"bbox_2d":[800,900,990,990],"seen":"发送"}""", "是").run("t")
        assertEquals("vision", m.results[0].s("source"))
        assertEquals(1, s.taps.size)
    }

    // no text from the screen read and no seen text from the vision model either: nothing suggests it spends money, so it is tapped as usual
    @Test fun locatedTapWithNothingToJudgeRunsWithoutAsking() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        lateinit var m: StepModel
        m = StepModel(lookFor("右下角的图标"), { m.tapLocated()(it) }, step(failure("停")))
        visionLoop(m, s, """{"bbox_2d":[800,900,990,990]}""", "是").run("t")
        assertEquals(1, s.taps.size)
    }

    // ruling: on a custom-drawn screen the seen text confirmed by a second look is what the decision rests on; with no irreversible wording it is allowed (leaving a trace)
    @Test fun locatedTapWithHarmlessSeenIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        val sink = ListSink()
        lateinit var m: StepModel
        m = StepModel(lookFor("开始按钮"), { m.tapLocated()(it) }, step(FINISH))
        val l = AgentLoop(m, toolDispatcher(s, vision = VisionService(says("""{"bbox_2d":[300,400,700,500],"seen":"开始游戏"}""", "是"), JvmTestImageOps)),
            "BASE", sink = sink, confirmations = ConfirmationGate())
        com.wangzan101.mika.core.testing.assertAnswered(DONE, l.run("t"))
        assertEquals(1, s.taps.size)
        assertEquals(1, sink.events.filterIsInstance<RunEvent.State>().count { it.name == "gate_unresolved" })
    }

    // review 2: a coordinate tap after phone_describe is treated like a point obtained from looking at the image
    @Test fun coordinateTapAfterDescriptionIsVisionSourced() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; blank = true }
        val desc = tools(call("phone_describe", """{"task":"点右下角","reason":"读屏是空的"}"""))
        val m = FakeModel(desc, tools(call("phone_tap", """{"x":900,"y":950}""")), tools(call("phone_tap", """{"x":900,"y":950,"target":"右下角图标"}""")), failure("停"))
        visionLoop(m, s, "[屏幕] 自绘页面").run("t")
        val noTarget = m.result(2)
        assertEquals("invalid_arguments", noTarget.s("error")); assertTrue("target" in noTarget.s("message"))
        // nothing suggests it spends money: it is tapped as usual
        assertEquals(1, s.taps.size)
    }

    @Test fun coordinateTapAfterDescriptionOnNamedElementIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        val desc = tools(call("phone_describe", """{"task":"看聊天对象","reason":"确认"}"""))
        val m = FakeModel(OBSERVE, desc, tools(call("phone_tap", """{"x":500,"y":40,"target":"标题 张三"}""")), FINISH)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, visionLoop(m, s, "[屏幕] 聊天页").run("t"))
        assertEquals(1, s.taps.size)
    }

    // M6: an @vN found by phone_find_visually is also judged by the element at that point
    @Test fun locatedTapIsGated() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), step(TYPE), locate("提交订单按钮", "提交订单"), { m.tapLocated()(it) }, step(failure("没发")))
        assertEquals(TaskState.Failed("没发", FailKind.GaveUp), visionLoop(m, s).run("给张三发明天见"))
        assertEquals("@v1", m.results[2].s("ref"))
        assertEquals("confirmation_required", m.results[3].s("error"))
        assertEquals("order", m.results[3].s("action"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun harmlessLocatedTapIsAllowed() = runTest {
        val s = ChatScreen().apply { png = FRAME_A }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), locate("聊天标题", "张三"), { m.tapLocated()(it) }, step(FINISH))
        com.wangzan101.mika.core.testing.assertAnswered(DONE, visionLoop(m, s).run("t"))
        assertEquals(listOf("540,96"), s.taps)
    }

    // the screen at that point cannot be read right now: nothing suggests it spends money, so it is tapped as usual
    @Test fun locatedTapRunsWhenScreenUnreadable() = runTest {
        val s = ChatScreen().apply { png = FRAME_A; peekFailsAfter = 1 }
        lateinit var m: StepModel
        m = StepModel(step(OBSERVE), locate("聊天标题", "张三"), { m.tapLocated()(it) }, step(FINISH))
        visionLoop(m, s).run("t")
        assertEquals(1, s.taps.size)
    }

    // a declaration must carry an amount (only spending money needs approval)
    @Test fun requestNeedsAmount() = runTest {
        val noAmount = tools(call("request_confirmation", """{"action":"order","target":"耳机","content":"买一副耳机","app":"com.chat"}"""))
        val m = FakeModel(OBSERVE, noAmount, FINISH)
        loop(m, ChatScreen()).run("t")
        val r = m.result(2)
        assertEquals("invalid_arguments", r.s("error"))
        assertTrue(r.s("message"), "amount" in r.s("message"))
    }

    // with an approval in hand do_steps is not accepted: the approved step is done on its own first, so the approval is not used up by a step in the batch
    @Test fun approvalRejectsDoStepsUpFront() = runTest {
        val s = ChatScreen()
        val batch = tools(call("do_steps", """{"steps":[{"tool":"phone_tap","args":{"ref":"@e3","snapshotId":"s1"}},{"tool":"phone_back","args":{}}]}"""))
        val m = FakeModel(OBSERVE, TYPE, ask(), batch, tapSend(), FINISH)
        val l = AgentLoop(m, toolDispatcher(s), "BASE", confirmations = ConfirmationGate(), flags = SpeedFlags(batchSteps = true))
        val run = async { l.run("给张三发明天见") }
        advanceUntilIdle()
        l.answerConfirmation(true)
        com.wangzan101.mika.core.testing.assertAnswered(DONE, run.await())
        assertEquals("approved_step_first", m.result(4).s("error"))
        assertTrue(m.result(4).s("message").startsWith("Do the approved step on its own first"))
        assertEquals(listOf("@e3"), s.taps)
    }
}
