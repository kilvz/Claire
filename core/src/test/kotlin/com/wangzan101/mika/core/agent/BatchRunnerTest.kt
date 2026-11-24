package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.Verification
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.assembly.toolDispatcher

/** Elements: name → normalized bounds; each screen read advances to the next screen (after the last one it stays there); action results are consumed in order. */
private class BatchScreen(vararg val screens: List<Pair<String, List<Int>>>) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var reads = 0
    val results = ArrayDeque<ActionResult>()
    /** When set, this screen is shown from then on (simulating a screen change while waiting at the gate). */
    var replaced: List<Pair<String, List<Int>>>? = null
    var settles = 0
    override suspend fun waitStable(timeoutMs: Long): Boolean { settles++; return true }
    private fun build(i: Int) = ScreenSnapshot("p${i + 1}", "com.x", (replaced ?: screens[minOf(i, screens.size - 1)]).mapIndexed { n, (name, b) ->
        UiElement("@e${n + 1}", "button", name, ((b[0] + b[2]) / 2) * 1079 / 999, ((b[1] + b[3]) / 2) * 2399 / 999, b, null, true, name.startsWith("输入"), false)
    }, 1080, 2400)
    override suspend fun snapshot(): ScreenSnapshot = build(reads++)
    override suspend fun peek(): ScreenSnapshot = build(maxOf(0, reads - 1))
    private fun next(): ActionResult = results.removeFirstOrNull() ?: ActionResult.Ok(changed = true)
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return next() }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { calls += "tapXY $x $y"; return next() }
    // The port reads back after typing and the values match (real ports always read back)
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
        calls += "type $text"; return results.removeFirstOrNull() ?: ActionResult.Ok(verification = Verification.MATCH)
    }
    override suspend fun back(): ActionResult { calls += "back"; return next() }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult { calls += "swipe"; return next() }
}

class BatchRunnerTest {
    private val home = listOf("搜索框" to listOf(0, 0, 999, 80), "返回" to listOf(0, 900, 100, 999), "提交订单" to listOf(800, 900, 999, 999))
    private val events = mutableListOf<RunEvent>()
    private val sink = object : RunSink { override fun event(e: RunEvent) { events += e } }
    private var gates = 0

    private var onGate: () -> Unit = {}
    private fun runner(d: ToolDispatcher, guards: OperationGuards = OperationGuards(), confirmations: ConfirmationGate? = ConfirmationGate()) =
        BatchRunner(d, guards, ActionExecutor(d, guards, confirmations, gate = { gates++; onGate() }, recorder = ActionRecorder(sink)))
    private fun steps(vararg s: Triple<String, String, String?>) = s.map { BatchStep(it.first, it.second, it.third?.let { e -> Until(appears = e) }) }
    private fun st(tool: String, args: String = "{}", expect: String? = null) = Triple(tool, args, expect)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun allStepsPassReturnsOk() = runTest {
        val s = BatchScreen(home)
        val d = toolDispatcher(s).also { it.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}")) }
        val r = runner(d).run(steps(st("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""), st("phone_type_text", """{"text":"咖啡"}"""), st("phone_back")), "c1", 3).content
        assertEquals("true", r.s("ok")); assertEquals(3, r["completed"]!!.jsonPrimitive.int); assertEquals(3, r["total"]!!.jsonPrimitive.int)
        assertEquals(listOf("tapRef @e1 p1", "type 咖啡", "back"), s.calls)
        assertEquals(3, gates)
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        assertEquals(listOf("batch", "batch", "batch"), results.map { it.source })
        assertEquals(listOf("c1.1", "c1.2", "c1.3"), results.map { it.callId })
        assertEquals(RunEvent.Batch(events.filterIsInstance<RunEvent.Batch>().single().atMs, 3, 3, false, null), events.filterIsInstance<RunEvent.Batch>().single())
    }

    // The user interjects after the first step completes: the remaining sub-steps are not run and each gets a "skipped for a new message" result; the whole batch goes back to the loop to deliver the interjection
    @Test fun newUserMessageStopsBatchAtTheNextBoundary() = runTest {
        val s = BatchScreen(home)
        val d = toolDispatcher(s)
        var waiting = false
        val r = BatchRunner(d, OperationGuards(), ActionExecutor(d, OperationGuards(), ConfirmationGate(), gate = { gates++; waiting = true }, recorder = ActionRecorder(sink)),
            steerWaiting = { waiting })
            .run(steps(st("phone_back"), st("phone_back"), st("phone_back")), "c1").content
        assertEquals(listOf("back"), s.calls)
        assertEquals("false", r.s("ok"))
        assertEquals(BatchRunner.NEW_MESSAGE, r.s("reason"))
        assertEquals("false", r.s("counts_as_failure"))
        assertEquals(1, r["completed"]!!.jsonPrimitive.int)
        val entries = r["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, entries.size)
        assertEquals("true", entries[0].s("ok"))
        assertEquals(listOf(BatchRunner.NEW_MESSAGE, BatchRunner.NEW_MESSAGE), entries.drop(1).map { it.s("error") })
        assertEquals(listOf("false", "false"), entries.drop(1).map { it.s("executed") })
        assertTrue(events.filterIsInstance<RunEvent.Batch>().single().stopped)
    }

    // A wait sub-step is cut short by the interjection: this step counts as not done and is skipped together with the rest; the wait does not run to completion and nothing else runs
    @Test fun steerCuttingAWaitStepSkipsItAndTheRest() = runTest {
        val s = BatchScreen(home)
        val inbox = com.wangzan101.mika.core.run.RunInbox()
        launch { delay(1_000); inbox.send(com.wangzan101.mika.core.run.RunInput.Steer("m1", "停一下")) }
        val r = withContext(inbox.signal) {
            runner(toolDispatcher(s)).run(steps(st("phone_back"), st("wait", """{"seconds":10}"""), st("phone_back")), "c1").content
        }
        assertEquals(1_000L, currentTime)
        assertEquals(listOf("back"), s.calls)
        assertEquals(BatchRunner.NEW_MESSAGE, r.s("reason"))
        assertEquals(1, r["completed"]!!.jsonPrimitive.int)
        val entries = r["steps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("false", "false"), entries.drop(1).map { it.s("executed") })
    }

    @Test fun unchangedScreenAloneDoesNotStopKnownSteps() = runTest {
        val s = BatchScreen(home).apply { results += ActionResult.Ok(changed = true); results += ActionResult.Ok(changed = false) }
        val d = toolDispatcher(s)
        val r = runner(d).run(steps(st("phone_back"), st("phone_back"), st("phone_back")), "c1").content
        assertEquals("true", r.s("ok"))
        assertEquals(3, r["completed"]!!.jsonPrimitive.int)
        assertEquals(3, r["steps"]!!.jsonArray.size)
        assertEquals(listOf("back", "back", "back"), s.calls)
        assertTrue(!events.filterIsInstance<RunEvent.Batch>().single().stopped)
    }

    @Test fun stopsOnExpectationUnmet() = runTest {
        val s = BatchScreen(home)
        val r = runner(toolDispatcher(s)).run(steps(st("phone_back", expect = "搜索结果"), st("phone_back")), "c1").content
        assertEquals("expectation_unmet", r.s("reason"))
        val last = r["steps"]!!.jsonArray.last().jsonObject
        assertEquals("搜索结果", last.s("expect"))
        assertEquals(listOf("back"), s.calls)
        // Text that is present on the screen counts as matched
        val ok = runner(toolDispatcher(BatchScreen(home))).run(steps(st("phone_back", expect = "搜索"), st("phone_back")), "c2").content
        assertEquals("true", ok.s("ok"))
    }

    // The site reports its limit: nothing after this step is run, and the result carries that message
    @Test fun stopsWhenALimitIsReported() = runTest {
        val s = BatchScreen(home)
        val port = object : ScreenPort by s {
            override suspend fun back(): ActionResult { s.replaced = home + ("今日次数已用完" to listOf(0, 400, 999, 460)); return s.back() }
        }
        val r = runner(toolDispatcher(port)).run(steps(st("phone_back"), st("phone_back")), "c1").content
        assertEquals("limit_reached", r.s("reason"))
        assertEquals("今日次数已用完", r["steps"]!!.jsonArray.last().jsonObject.s("limitReached"))
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun waitMayBeUnchanged() = runTest {
        val s = BatchScreen(home)
        val r = runner(toolDispatcher(s)).run(steps(st("wait", """{"seconds":0.5}"""), st("phone_back")), "c1").content
        assertEquals("true", r.s("ok")); assertEquals(listOf("back"), s.calls)
    }

    @Test fun stopsBeforeIrreversible() = runTest {
        val s = BatchScreen(home)
        val d = toolDispatcher(s).also { it.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}")) }
        val r = runner(d).run(steps(st("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""), st("phone_type_text", """{"text":"你好"}"""),
            st("phone_tap", """{"ref":"@e3","snapshotId":"p1"}""")), "c1").content
        assertEquals("confirmation_required", r.s("error"))
        assertEquals(2, r["completed"]!!.jsonPrimitive.int)
        assertEquals("false", r["steps"]!!.jsonArray.last().jsonObject.s("executed"))
        assertEquals(listOf("tapRef @e1 p1", "type 你好"), s.calls)
        assertEquals(3, gates) // Gate first, then judge against the current screen; step 3 is judged irreversible and is not executed
        assertNotNull(events.filterIsInstance<RunEvent.Confirmation>().singleOrNull())
    }

    @Test fun coordinateTapOnSendStopsBefore() = runTest {
        val s = BatchScreen(home)
        val r = runner(toolDispatcher(s)).run(steps(st("phone_back"), st("phone_tap", """{"x":900,"y":950}""")), "c1").content
        assertEquals("confirmation_required", r.s("error"))
        assertTrue("提交订单" in r.s("matched"))
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun blindCoordinateTapStops() = runTest {
        val s = BatchScreen(listOf("返回" to listOf(0, 900, 100, 999)))
        val r = runner(toolDispatcher(s)).run(steps(st("phone_back"), st("phone_tap", """{"x":500,"y":500}""")), "c1").content
        assertEquals("target_unresolved", r.s("reason")); assertEquals(listOf("back"), s.calls)
    }

    @Test fun laterRefTranslatedThroughStabilizer() = runTest {
        // The screen changes after the first step: the port gives the "Confirm" button a new ref, while the model's ref still comes from the first screen
        val after = listOf("提示" to listOf(0, 300, 999, 400), "搜索框" to listOf(0, 0, 999, 80), "确定" to listOf(400, 500, 600, 560))
        val before = listOf("搜索框" to listOf(0, 0, 999, 80), "确定" to listOf(400, 500, 600, 560))
        val s = BatchScreen(before, after)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}"))
        val r = runner(d).run(steps(st("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""), st("phone_tap", """{"ref":"@e2","snapshotId":"p1"}""")), "c1").content
        assertEquals("true", r.s("ok"))
        assertEquals(listOf("tapRef @e1 p1", "tapRef @e3 p2"), s.calls)
        assertEquals("true", r["steps"]!!.jsonArray[1].jsonObject.s("ok"))
        assertNotNull(r["screen"])
    }

    @Test fun adaptiveBatchRunsKnownStepsWithOneFinalTextScreen() = runTest {
        val form = listOf("输入名称" to listOf(0, 100, 900, 180), "输入金额" to listOf(0, 250, 900, 330))
        val s = BatchScreen(form)
        val d = toolDispatcher(s, visionEnabled = true, adaptiveVision = true, inlineScreen = true)
        assertTrue(d.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}")).images.isEmpty())
        val result = runner(d).run(steps(
            st("phone_tap", """{"ref":"@e1","snapshotId":"p1"}"""),
            st("phone_type_text", """{"text":"午餐"}"""),
            st("phone_tap", """{"ref":"@e2","snapshotId":"p1"}"""),
            st("phone_type_text", """{"text":"350"}"""),
        ), "c1")
        assertEquals("true", result.content.s("ok"))
        assertEquals(listOf("tapRef @e1 p1", "type 午餐", "tapRef @e2 p3", "type 350"), s.calls)
        assertTrue(result.images.isEmpty())
        assertNotNull(result.content["screen"])
    }

    @Test fun adaptiveBatchStopsBeforeNextStepWhenScreenNeedsImage() = runTest {
        val unlabeled = listOf("" to listOf(0, 0, 100, 100), "" to listOf(120, 0, 220, 100))
        val s = BatchScreen(home, unlabeled)
        val d = toolDispatcher(s, visionEnabled = true, adaptiveVision = true, inlineScreen = true)
        d.execute(com.wangzan101.mika.core.model.Part.ToolCall("o", "phone_observe", "{}"))
        val result = runner(d).run(steps(st("phone_back"), st("phone_back")), "c1")
        assertEquals("screen_needs_review", result.content.s("reason"))
        assertEquals(listOf("back"), s.calls)
        assertEquals(1, result.images.size)
    }

    @Test fun rejectsNonBatchableTool() {
        val avail = BATCHABLE.toSet() + "finish"
        val (none, msg) = BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{}},{"tool":"finish","args":{"answer":"x"}}]}""", avail, BATCHABLE)
        assertNull(none); assertTrue(msg!!, "steps[2].tool" in msg && "finish" in msg)
        assertTrue("steps" in BatchRunner.parse("""{"steps":[]}""", avail, BATCHABLE).second!!)
        assertEquals(1, BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{}}]}""", avail, BATCHABLE).first!!.size)
        assertTrue("expect" in BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{},"expect":"${"x".repeat(201)}"},{"tool":"phone_back","args":{}}]}""", avail, BATCHABLE).second!!)
        // A tool that is not offered right now (phone_open_link with its switch off) is rejected too
        assertTrue("phone_open_link" in BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_open_link","args":{}}]}""", setOf("phone_back"), BATCHABLE).second!!)
        assertEquals(2, BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{}},{"tool":"phone_back"}]}""", avail, BATCHABLE).first!!.size)
    }

    @Test fun gateBeforeIrreversibleCheck() = runTest {
        // The screen changes while waiting at a gate for a locked screen or a taken-away phone: the same position now shows the "Submit order" button; judgement uses the screen as of after the gate, so nothing is tapped
        val s = BatchScreen(listOf("确定" to listOf(400, 450, 600, 550), "返回" to listOf(0, 900, 100, 999)))
        onGate = { if (gates == 2) s.replaced = listOf("提交订单" to listOf(400, 450, 600, 550)) }
        val r = runner(toolDispatcher(s)).run(steps(st("phone_back"), st("phone_tap", """{"x":500,"y":500}""")), "c1").content
        assertEquals("confirmation_required", r.s("error"))
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun waitsForStableScreenBetweenSteps() = runTest {
        val s = BatchScreen(home)
        runner(toolDispatcher(s)).run(steps(st("phone_back"), st("phone_back", expect = "搜索"), st("phone_back")), "c1")
        // Two waits between steps; the port already waited for the UI inside the action (reporting whether it changed), so judging the effect does not wait again
        assertEquals(2, s.settles)
    }

    @Test fun stopEntriesSayWhetherTheStepRan() = runTest {
        val ran = runner(toolDispatcher(BatchScreen(home))).run(steps(st("phone_back", expect = "没有的字"), st("phone_back")), "c1").content
        assertEquals("true", ran["steps"]!!.jsonArray.last().jsonObject.s("executed"))
        assertTrue(ran.s("message"), "was run but did not pass its check (expectation_unmet)" in ran.s("message"))
        val notRun = runner(toolDispatcher(BatchScreen(listOf("返回" to listOf(0, 900, 100, 999))))).run(steps(st("phone_back"), st("phone_tap", """{"x":500,"y":500}""")), "c2").content
        assertEquals("false", notRun["steps"]!!.jsonArray.last().jsonObject.s("executed"))
        assertTrue("was not run (target_unresolved)" in notRun.s("message"))
    }

    @Test fun withoutConfirmationToolMessageDoesNotAskForIt() = runTest {
        val r = runner(toolDispatcher(BatchScreen(home)), confirmations = null).run(steps(st("phone_back"), st("phone_tap", """{"x":900,"y":950}""")), "c1").content
        assertEquals("confirmation_required", r.s("error"))
        assertTrue("request_confirmation" !in r.s("message"))
        val with = runner(toolDispatcher(BatchScreen(home))).run(steps(st("phone_back"), st("phone_tap", """{"x":900,"y":950}""")), "c2").content
        assertTrue("request_confirmation" in with.s("message"))
    }
}
