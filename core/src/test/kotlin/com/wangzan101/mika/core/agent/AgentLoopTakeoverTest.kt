package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.safety.verify.Verifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("t${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
private const val ANSWER = "付好了"
private fun finish() = com.wangzan101.mika.core.testing.answerReply("付好了")
private const val FAIL = """{"checks": [{"criterion": "已付款", "status": "unmet", "quote": null}], "verdict": "fail", "reason": "接管被跳过,没有付款"}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTakeoverTest {
    private class ListSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { events += e }
    }

    private fun takeover(args: String = """{"reason":"login","message":"请登录"}""") = call("hand_to_user", args)

    /** Runs until it waits for takeover, hands back [back], and returns the end state plus the last message in the model request made after the hand-back. */
    private suspend fun TestScope.handBack(
        back: HandBack, main: FakeModel = FakeModel(tools(takeover()), finish()),
        verifier: Verifier? = null, sink: RunSink = RunSink.NONE,
    ): Pair<TaskState, AgentLoop> {
        val l = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", sink = sink, verifier = verifier)
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertTrue(l.state.value.toString(), l.state.value is TaskState.WaitingTakeover)
        l.resumeAfterTakeover(back)
        return run.await() to l
    }

    private fun FakeModel.takeoverResult(): JsonObject {
        val r = requests[1].withoutRuntime().last()
        assertEquals(Role.Tool, r.role)
        return Json.parseToJsonElement(r.parts.filterIsInstance<Part.ToolResult>().single().content).jsonObject
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun paymentFieldsCarriedToState() = runTest {
        val main = FakeModel(tools(takeover("""{"reason":"payment","message":"请付款","item":"咖啡豆 500g ×1","amount":"¥89.00","address":"上海市某路 1 号"}""")), finish())
        val l = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE")
        val run = async { l.run("t") }
        advanceUntilIdle()
        assertEquals(TaskState.WaitingTakeover("payment", "请付款", item = "咖啡豆 500g ×1", amount = "¥89.00", address = "上海市某路 1 号"), l.state.value)
        l.resumeAfterTakeover(HandBack(HandBackOutcome.complete))
        run.await()
    }

    @Test fun replyRecordedOnceOnNormalHandBack() = runTest {
        val sink = ListSink()
        handBack(HandBack(HandBackOutcome.complete), sink = sink)
        assertEquals(1, sink.events.count { it is RunEvent.ModelReply && it.tool == "hand_to_user" })
    }

    @Test fun completeResultHasNoteAndCounts() = runTest {
        val main = FakeModel(tools(takeover()), finish())
        val sink = ListSink()
        val (end, _) = handBack(HandBack(HandBackOutcome.complete, durationMs = 4200, inputCount = 3, stoppedAt = "订单详情"), main, sink = sink)
        com.wangzan101.mika.core.testing.assertAnswered(ANSWER, end)
        val r = main.takeoverResult()
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("complete", r.s("outcome"))
        assertEquals("4200", r.s("durationMs")); assertEquals("3", r.s("inputCount")); assertEquals("订单详情", r.s("stoppedAt"))
        assertEquals(UserExchange.HANDBACK_NOTE, r.s("note"))
        assertNull(r["instruction"])
        val ev = sink.events.filterIsInstance<RunEvent.Takeover>().single()
        assertEquals(RunEvent.Takeover(ev.atMs, "login", "complete", 4200, 3), ev)
    }

    @Test fun skippedIsNotSuccess() = runTest {
        // After the skip the model answers directly: payment is irreversible, so verification is requested separately; the action list the verifier receives states that the takeover was skipped, the verdict fails, and the answer is marked unconfirmed
        val main = FakeModel(tools(takeover("""{"reason":"payment","message":"请付款","amount":"¥89.00"}""")), finish(), finish(), finish())
        val checker = FakeModel(text(FAIL), text(FAIL), text(FAIL))
        val (end, _) = handBack(HandBack(HandBackOutcome.skipped), main, verifier = Verifier(checker, null))
        val r = main.takeoverResult()
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("skipped", r.s("outcome")); assertEquals("takeover_skipped", r.s("reason"))
        assertEquals("false", r.s("counts_as_failure")); assertEquals(UserExchange.NO_HANDBACK_NOTE, r.s("note"))
        val verifierPrompt = checker.requests.first().joinToString("\n") { m -> m.parts.filterIsInstance<Part.Text>().joinToString { it.text } }
        assertTrue(verifierPrompt, "hand_to_user(payment) 接管被跳过" in verifierPrompt)
        assertTrue(end.toString(), end is TaskState.Succeeded && !end.verified && end.unconfirmed.isNotEmpty())
    }

    @Test fun waitingPastTheOldTimeoutKeepsTheSameRunAlive() = runTest {
        val main = FakeModel(tools(takeover()), finish())
        val l = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE")
        val run = async { l.run("t") }
        advanceUntilIdle()
        val waiting = l.state.value
        advanceTimeBy(16 * 60_000L)
        assertTrue(run.isActive)
        assertEquals(waiting, l.state.value)
        assertTrue(main.requests.size == 1)
        l.resumeAfterTakeover(HandBack(HandBackOutcome.complete))
        com.wangzan101.mika.core.testing.assertAnswered(ANSWER, run.await())
        assertEquals("complete", main.takeoverResult().s("outcome"))
    }

    @Test fun timeoutResultShape() = runTest {
        val main = FakeModel(tools(takeover()), finish())
        handBack(HandBack(HandBackOutcome.timeout), main)
        val r = main.takeoverResult()
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals("timeout", r.s("outcome")); assertEquals("takeover_timeout", r.s("reason"))
        assertEquals("false", r.s("counts_as_failure")); assertEquals(UserExchange.NO_HANDBACK_NOTE, r.s("note"))
        assertNull(r["durationMs"])
    }

    @Test fun userFinishedAddsVerifyOnlyRule() = runTest {
        val main = FakeModel(tools(takeover()), finish())
        handBack(HandBack(HandBackOutcome.complete, userFinished = true), main)
        val r = main.takeoverResult()
        assertEquals("complete", r.s("outcome")); assertEquals("true", r.s("userFinished"))
        assertEquals(UserExchange.HANDBACK_NOTE, r.s("note"))
        assertEquals(UserExchange.USER_FINISHED_PROMPT, r.s("instruction"))
    }

    @Test fun takeoverResultPairsWithCall() = runTest {
        val c = takeover()
        val main = FakeModel(tools(c), finish())
        handBack(HandBack(HandBackOutcome.complete), main)
        val history = main.requests[1].withoutRuntime()
        val i = history.indexOfFirst { m -> m.parts.any { it is Part.ToolCall && it.id == c.id } }
        val result = history[i + 1].parts.filterIsInstance<Part.ToolResult>().single()
        assertEquals(c.id, result.callId)
        assertTrue(result.content, "\"outcome\":\"complete\"" in result.content)
        // The hand-back result is the tool result itself; no separate user message follows it
        assertEquals(i + 2, history.size)
    }

    @Test fun abortedCancelsTask() = runTest {
        val sink = ListSink()
        val (end, l) = handBack(HandBack(HandBackOutcome.aborted), sink = sink)
        // Even when aborted, this model reply is kept and recorded before the state of waiting for takeover
        val reply = sink.events.indexOfFirst { it is RunEvent.ModelReply && it.tool == "hand_to_user" }
        assertTrue(sink.events.toString(), reply >= 0)
        assertTrue(reply < sink.events.indexOfFirst { it is RunEvent.State && it.name == "waiting_takeover" })
        assertEquals(TaskState.Cancelled, end)
        assertEquals(TaskState.Cancelled, l.state.value)
    }

    @Test fun legacyNoteWrapperStillHandsBack() = runTest {
        val main = FakeModel(tools(takeover()), finish())
        val l = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE")
        val run = async { l.run("t") }
        advanceUntilIdle()
        l.resumeAfterTakeover(AgentLoop.USER_FINISHED_NOTE)
        run.await()
        val r = main.takeoverResult()
        assertEquals("complete", r.s("outcome")); assertEquals(UserExchange.USER_FINISHED_PROMPT, r.s("instruction"))
    }
}
