package com.wangzan101.mika.voicecall

import com.wangzan101.mika.chat.ConversationCall
import com.wangzan101.mika.core.agent.Turn as ChatTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTranscriptTest {

    private class Harness(conversationId: String? = "conv-9") {
        var now = 1_000L
        val appended = mutableListOf<Pair<String, ChatTurn>>()
        val recorded = mutableListOf<Pair<String, ConversationCall>>()
        val transcript = VoiceCallTranscript(
            conversationId = { conversationId },
            appendTurn = { id, turn -> appended += id to turn },
            nowMillis = { now },
            callId = "call-1",
            recordCall = { id, call -> recorded += id to call },
        )
    }

    @Test
    fun `aggregates questions, answers and timing`() {
        val h = Harness()
        h.transcript.onUser("明天天气怎么样")
        h.now = 2_000L
        h.transcript.onAssistant("明天晴天")
        h.transcript.onAssistant("最高 25 度")
        assertEquals(
            listOf(VoiceCallTranscript.Turn("明天天气怎么样", "明天晴天\n最高 25 度", 1_000L)),
            h.transcript.turns(),
        )
    }

    @Test
    fun `a new user utterance closes the previous turn`() {
        val h = Harness()
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答一")
        h.now = 5_000L
        h.transcript.onUser("第二句")
        h.transcript.onAssistant("答二")
        assertEquals(
            listOf(
                VoiceCallTranscript.Turn("第一句", "答一", 1_000L),
                VoiceCallTranscript.Turn("第二句", "答二", 5_000L),
            ),
            h.transcript.turns(),
        )
    }

    @Test
    fun `assistant speech before the user speaks forms an empty question turn`() {
        val h = Harness()
        h.transcript.onAssistant("你好，我在听")
        assertEquals(listOf(VoiceCallTranscript.Turn("", "你好，我在听", 1_000L)), h.transcript.turns())
    }

    @Test
    fun `blank text is not admitted`() {
        val h = Harness()
        h.transcript.onUser("")
        h.transcript.onAssistant("   ")
        assertTrue(h.transcript.turns().isEmpty())
    }

    @Test
    fun `ending writes back to the originating conversation — the same store entry as chat`() = runTest {
        val h = Harness()
        h.transcript.onUser("带猫去打疫苗")
        h.transcript.onAssistant("好的，记下了")
        h.now = 3_000L
        h.transcript.onUser("顺便买猫粮")
        h.transcript.onAssistant("已加入清单")
        h.transcript.close()
        assertEquals(2, h.appended.size)
        val (id1, t1) = h.appended[0]
        assertEquals("conv-9", id1)
        assertEquals("带猫去打疫苗", t1.userText)
        assertEquals("好的，记下了", t1.outcome)
        // The front desk is the one replying during the call: it is written as a front-desk round, so a round that only dispatched and never replied does not render as an empty "done"
        assertEquals(com.wangzan101.mika.core.agent.DeskTurn.STATUS, t1.status)
        assertEquals(1_000L, t1.atMs)
        val (id2, t2) = h.appended[1]
        assertEquals("conv-9", id2)
        assertEquals("顺便买猫粮", t2.userText)
        assertEquals(3_000L, t2.atMs)
    }

    @Test
    fun `last turn with a question but no answer is not written as an empty outcome`() = runTest {
        val h = Harness()
        h.transcript.onUser("带猫去打疫苗")
        h.transcript.onAssistant("好的，记下了")
        h.now = 3_000L
        h.transcript.onUser("顺便买猫粮") // the call dropped here; this utterance never got an answer
        h.transcript.close()
        assertEquals(1, h.appended.size)
        val (id, turn) = h.appended[0]
        assertEquals("conv-9", id)
        assertEquals("带猫去打疫苗", turn.userText)
        assertEquals("好的，记下了", turn.outcome)
    }

    @Test
    fun `a question with no answer throughout writes nothing at all`() = runTest {
        val h = Harness()
        h.transcript.onUser("喂喂，听得到吗")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `an empty question turn is not written back`() = runTest {
        val h = Harness()
        h.transcript.onAssistant("你好，我在听")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `repeated ending does not write twice`() = runTest {
        val h = Harness()
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答")
        h.transcript.close()
        h.transcript.close()
        assertEquals(1, h.appended.size)
    }

    @Test
    fun `nothing is written when there is no originating conversation`() = runTest {
        val h = Harness(conversationId = null)
        h.transcript.onUser("第一句")
        h.transcript.onAssistant("答")
        h.transcript.close()
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `still writes the batch when the caller coroutine is cancelled`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val appended = mutableListOf<String>()
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, turn ->
                appended += turn.userText
                if (appended.size == 1) scope.cancel() // the whole call coroutine scope is cancelled mid-write
                delay(1)
            },
            nowMillis = { 7L },
        )
        transcript.onUser("第一句")
        transcript.onAssistant("甲")
        transcript.onUser("第二句")
        transcript.onAssistant("乙")
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { transcript.close() }
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("第一句", "第二句"), appended)
    }

    @Test
    fun `rounds that dispatched a task are written back with the task id, even without an answer`() = runTest {
        val h = Harness()
        h.transcript.onTask("t0") // no current round yet: ignored
        h.transcript.onUser("帮我订明晚的日料")
        h.transcript.onTask("t1")
        h.transcript.onTask("t1")
        h.transcript.onUser("再查一下天气")
        h.transcript.onTask("t2") // dispatched and then hung up; this sentence never got an answer
        h.transcript.close()
        assertEquals(listOf(listOf("t1"), listOf("t2")), h.appended.map { it.second.deskTasks })
        assertEquals(listOf("帮我订明晚的日料", "再查一下天气"), h.appended.map { it.second.userText })
    }

    @Test
    fun `each round is written once done, without waiting for hangup; hangup only fills in the last round`() = runTest {
        val appended = mutableListOf<String>()
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, turn -> appended += turn.userText + "=" + turn.outcome },
            nowMillis = { 7L },
            writeScope = this,
        )
        transcript.onUser("第一句")
        transcript.onAssistant("甲")
        advanceUntilIdle()
        assertTrue("the round still being answered is not written yet", appended.isEmpty())
        transcript.onUser("第二句") // the user starts the next sentence: the previous round is done, write it now
        advanceUntilIdle()
        assertEquals(listOf("第一句=甲"), appended)
        transcript.onUser("第三句") // the second sentence got no answer and dispatched no task: do not write
        transcript.onAssistant("丙")
        advanceUntilIdle()
        assertEquals(listOf("第一句=甲"), appended)
        transcript.close()
        assertEquals("hangup fills in the last round; already written rounds are not rewritten", listOf("第一句=甲", "第三句=丙"), appended)
        transcript.close()
        assertEquals(2, appended.size)
    }

    @Test
    fun `every round written into the conversation carries this call's id`() = runTest {
        val h = Harness()
        h.transcript.onUser("几点了")
        h.transcript.onAssistant("九点")
        h.transcript.close()
        assertEquals(listOf("call-1"), h.appended.map { it.second.callId })
    }

    @Test
    fun `connecting records an unfinished call; hangup fills in the end, reason, and whether anyone spoke`() = runTest {
        val recorded = mutableListOf<ConversationCall>()
        var now = 5_000L
        val transcript = VoiceCallTranscript(
            conversationId = { "conv-9" },
            appendTurn = { _, _ -> },
            nowMillis = { now },
            writeScope = this,
            callId = "call-1",
            recordCall = { _, call -> recorded += call },
        )
        transcript.onConnected(4_000L)
        transcript.onConnected(4_500L) // reconnected and connected again: connect time unchanged, not recorded again
        advanceUntilIdle()
        assertEquals(listOf(ConversationCall("call-1", 4_000L)), recorded)
        transcript.onUser("明天天气")
        transcript.onAssistant("晴")
        now = 9_000L
        transcript.close(ConversationCall.END_IDLE)
        assertEquals(ConversationCall("call-1", 4_000L, 9_000L, ConversationCall.END_IDLE, spoke = true), recorded.last())
        assertEquals(2, recorded.size)
    }

    @Test
    fun `a call with nothing said is still recorded, marked as silent`() = runTest {
        val h = Harness()
        h.transcript.onConnected(500L)
        h.transcript.onUser("喂") // never got an answer: does not count as having spoken
        h.transcript.close()
        val call = h.recorded.single().second
        assertFalse(call.spoke)
        assertEquals(500L, call.startMs)
        assertEquals(ConversationCall.END_HANGUP, call.endReason)
        assertTrue(h.appended.isEmpty())
    }

    @Test
    fun `a call that never connected is not recorded`() = runTest {
        val h = Harness()
        h.transcript.close()
        assertTrue(h.recorded.isEmpty())
    }
}
