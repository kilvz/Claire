package com.wangzan101.mika.voicecall

import com.wangzan101.mika.frontdesk.AnyWords
import com.wangzan101.mika.calls.VolcRealtimeProtocol
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceDeskBridgeTest {
    private class Rig(scope: CoroutineScope, clock: () -> Long) {
        val port = FakeDeskPort()
        val sink = FakeDeskSink()
        val realtime = FakeDeskRealtime()
        var live: FakeDeskRealtime? = realtime
        val ledger = DeskLedger()
        val desk = FrontDesk("p", scope, port, ledger, ReportPolicy(clock), sink)
        val executor = DeskToolExecutor(port, ledger, AnyWords, clock, "p") { desk.track(it.taskId, it.title) }
        val bridge = VoiceDeskBridge(desk, executor, { live }, { report -> "${report.title}:${report.event::class.simpleName}" })
        val attachment = bridge.attach()
    }

    private fun TestScope.rig() = Rig(backgroundScope) { currentTime }

    private fun dispatch(words: String, note: String? = null) = buildJsonObject {
        put("words", words)
        put("title", words.take(8))
        note?.let { put("note", it) }
    }

    private fun JsonObject.status() = getValue("status").jsonPrimitive.content

    @Test fun dispatchReturnsImmediately() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        // The tool result goes back to the session right after dispatch returns, while the task is still running.
        val (callId, result) = r.realtime.toolResults.single()
        assertEquals("c1", callId)
        assertEquals("started", result.status())
        assertEquals("t1", result.getValue("task_id").jsonPrimitive.content)
        assertTrue(r.port.states.getValue("t1").value is TaskState.Running)
    }

    @Test fun twoTasksCanRunTogether() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        r.bridge.onFunctionCall("c2", DeskTools.DISPATCH, dispatch("再查一下明天的天气"))
        assertEquals(listOf("started", "started"), r.realtime.toolResults.map { it.second.status() })
        assertEquals(2, r.port.dispatched.size)
    }

    @Test fun needsAnswerIsSpokenAndAnswerRelayed() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        val before = r.realtime.notices.size
        r.port.states.getValue("t1").value = TaskState.WaitingInput("要几点?")
        runCurrent()
        assertEquals(listOf("帮我订明晚的日料:NeedsAnswer"), r.realtime.notices.drop(before))
        r.bridge.onFunctionCall("c2", DeskTools.ANSWER, buildJsonObject { put("task_id", "t1"); put("words", "七点") })
        assertEquals(listOf("t1" to "七点"), r.port.answers)
        assertEquals("ok", r.realtime.toolResults.last().second.status())
    }

    @Test fun terminalSpokenOnce() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        val flow = r.port.states.getValue("t1")
        flow.value = TaskState.Succeeded("订好了七点")
        runCurrent()
        flow.value = TaskState.Succeeded("订好了七点", verified = false)
        runCurrent()
        assertEquals(1, r.realtime.notices.count { it.endsWith("Succeeded") })
        assertTrue(r.sink.recorded.isEmpty())
    }

    @Test fun hangupFallsBackToSink() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        val spoken = r.realtime.notices.size
        r.live = null // hung up: the session is gone
        r.port.states.getValue("t1").value = TaskState.Failed("没位子了", FailKind.Model)
        runCurrent()
        assertEquals(spoken, r.realtime.notices.size)
        val (conversation, report) = r.sink.recorded.single()
        assertEquals("p", conversation)
        assertTrue(report.event is DeskEvent.Failed)
    }

    @Test fun realtimeDispatchNoteIsTruncatedWordsNever() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料", note = "备".repeat(300)))
        assertEquals(VolcRealtimeProtocol.MAX_FUNCTION_ARGUMENT_CHARS, r.port.dispatched.single().second.length)

        val long = "字".repeat(VolcRealtimeProtocol.MAX_UTTERANCE_CHARS + 1)
        r.bridge.onFunctionCall("c2", DeskTools.DISPATCH, dispatch(long))
        assertEquals(1, r.port.dispatched.size)
        assertEquals(
            buildJsonObject { put("status", "rejected"); put("reason", "words_too_long") },
            r.realtime.toolResults.last().second,
        )
    }

    @Test fun detachedBridgeLeavesReportsToTheSink() = runTest {
        val r = rig()
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        r.attachment.close()
        r.port.states.getValue("t1").value = TaskState.Succeeded("订好了")
        runCurrent()
        assertEquals(DeskReport(DeskEvent.Succeeded("t1", "订好了"), "帮我订明晚的日料".take(8)), r.sink.recorded.single().second)
    }

    @Test fun noticeCutOffGoesToTheSinkNotSpokenAgain() = runTest {
        val r = rig()
        r.realtime.endsAtOnce = false
        r.bridge.onFunctionCall("c1", DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        val before = r.realtime.notices.size
        r.port.states.getValue("t1").value = TaskState.WaitingInput("要几点?")
        runCurrent()
        r.port.states.getValue("t1").value = TaskState.Succeeded("订好了七点")
        runCurrent()
        // Only one notice at a time: the question notice has not finished, so the completed one is not handed over
        assertEquals(before + 1, r.realtime.notices.size)
        r.realtime.endNotice(spoken = false)
        runCurrent()
        // The interrupted notice is written back to the conversation and no longer spoken; the next one is handed to the call as usual
        assertTrue(r.sink.recorded.single().second.event is DeskEvent.NeedsAnswer)
        assertEquals(before + 2, r.realtime.notices.size)
        r.realtime.endNotice(spoken = true)
        runCurrent()
        assertEquals(1, r.sink.recorded.size)
    }

    // The call ends (even if the page is hidden): the call releases the front desk, the chat page takes it back, and later reports go to the chat page instead of falling to the fallback.
    // It also releases on failure; once the user reconnects, the call takes it back.
    @Test fun anEndedCallReleasesTheDeskForTheChat() = runTest {
        val r = rig()
        r.attachment.close()
        val state = MutableStateFlow<VoiceCallState>(VoiceCallState.Connecting)
        val live = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { r.bridge.attachWhileLive(state) }
        assertSame(r.bridge, r.desk.attached.value)
        state.value = VoiceCallState.Failed(VoiceCallError.Disconnected, 1)
        runCurrent()
        assertNull(r.desk.attached.value)
        state.value = VoiceCallState.Active(0)
        runCurrent()
        assertSame(r.bridge, r.desk.attached.value)

        state.value = VoiceCallState.Ended(EndReason.Notification)
        runCurrent()
        assertTrue(live.isCompleted)
        assertNull(r.desk.attached.value)
        val chat = mutableListOf<String>()
        r.desk.attach(object : DeskSurface {
            override suspend fun deliver(report: DeskReport): Boolean { chat += report.event.taskId; return true }
        })
        r.executor.execute(DeskTools.DISPATCH, dispatch("帮我订明晚的日料"))
        runCurrent()
        r.port.states.getValue("t1").value = TaskState.Succeeded("订好了")
        runCurrent()
        assertEquals(listOf("t1"), chat)
        assertTrue(r.sink.recorded.isEmpty())
    }
}
