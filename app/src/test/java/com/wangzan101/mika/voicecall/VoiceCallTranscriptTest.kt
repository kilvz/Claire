package com.wangzan101.mika.voicecall

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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallTranscriptTest {

    private class Harness(conversationId: String? = "conv-9") {
        var now = 1_000L
        val appended = mutableListOf<Pair<String, ChatTurn>>()
        val transcript = VoiceCallTranscript(
            conversationId = { conversationId },
            appendTurn = { id, turn -> appended += id to turn },
            nowMillis = { now },
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
        assertEquals("succeeded", t1.status)
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
}
