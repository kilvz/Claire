package com.wangzan101.mika.calls

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DeskToolSet
import com.wangzan101.mika.core.frontdesk.DeskTools
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDeskTest {
    /** Records what was asked and how many times it was withdrawn; [reply] simulates the owner replying (in a notification or in the conversation). */
    private class FakeAsk(var reach: OwnerReach = OwnerReach.Notified) : OwnerAsk {
        val questions = mutableListOf<String>()
        var cancels = 0
        private var onReply: ((String) -> Unit)? = null

        override fun ask(question: String, onReply: (String) -> Unit): OwnerReach {
            if (reach == OwnerReach.Unreachable) return reach
            questions += question
            this.onReply = onReply
            return reach
        }

        override fun cancel() {
            cancels++
        }

        /** A late reply still comes in whether or not it was withdrawn: ignoring it is PhoneDesk's job. */
        fun reply(text: String) = onReply!!.invoke(text)
    }

    /** Cues handed to the model; [ended] is kept so the test can decide whether it finished speaking. */
    private class FakeCues(var accept: Boolean = true) {
        val sent = mutableListOf<String>()
        val ended = mutableListOf<(Boolean) -> Unit>()
        val context = mutableListOf<String>()
        fun cue(text: String, end: (Boolean) -> Unit): Boolean {
            if (!accept) return false
            sent += text
            ended += end
            return true
        }
        fun context(text: String): Boolean {
            if (!accept) return false
            context += text
            return true
        }
    }

    private fun args(question: String) = buildJsonObject { put("question", question) }
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun phoneHasOnlyAskOwner() = runBlocking {
        assertEquals(setOf(DeskTools.ASK_OWNER), DeskTools.specs(DeskToolSet.Phone).map { it.getValue("name").jsonPrimitive.content }.toSet())
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask)
        for (name in listOf(DeskTools.DISPATCH, DeskTools.ADD, DeskTools.ANSWER, DeskTools.CANCEL, "web_search", "check_tasks", "send_dtmf")) {
            assertEquals(name, "unsupported", desk.execute(name, buildJsonObject { put("words", "帮我订个位"); put("title", "x") }).status())
        }
        assertTrue(ask.questions.isEmpty())
    }

    @Test fun askOwnerReturnsImmediatelyAndRelaysReplyAsACueNotVerbatim() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val desk = PhoneDesk({ null }, ask, cues::cue)
        val result = withTimeout(1_000) { desk.execute(DeskTools.ASK_OWNER, args("对方问能不能改到周日")) }
        assertEquals("asked", result.status())
        assertTrue(result.text("next").startsWith("Tell the caller you are checking"))
        assertEquals(listOf("对方问能不能改到周日"), ask.questions)
        assertTrue(cues.sent.isEmpty())
        ask.reply("周日也行，下午两点以后")
        // What is handed to the model is a cue: it carries the owner's original words, marks them as not the other party's and not to be read aloud, and the model relays it
        val cue = cues.sent.single()
        assertTrue(cue.contains("周日也行，下午两点以后"))
        assertTrue(cue.startsWith(PhoneDesk.CUE_MARK))
    }

    @Test fun secondAskWhilePendingIsBusy() = runBlocking {
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, FakeCues()::cue)
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("第一个问题")).status())
        assertEquals("busy", desk.execute(DeskTools.ASK_OWNER, args("第二个问题")).status())
        assertEquals(listOf("第一个问题"), ask.questions)
        ask.reply("好")
        // After a reply you can ask again
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("第三个问题")).status())
    }

    @Test fun dtmfStillHandledByBridge() {
        assertEquals(CallToolRoute.Dtmf, callToolRoute(VolcRealtimeProtocol.DTMF_FUNCTION))
        assertEquals(CallToolRoute.Desk, callToolRoute(DeskTools.ASK_OWNER))
        // The user's front-desk tool names and made-up names all go to PhoneDesk, which answers unsupported; they never reach the user's front-desk executor
        assertEquals(CallToolRoute.Desk, callToolRoute(DeskTools.DISPATCH))
        assertEquals(CallToolRoute.Desk, callToolRoute("dial_number"))
    }

    @Test fun callEndCancelsOwnerAsk() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("要不要加一个人"))
        desk.close()
        assertEquals(1, ask.cancels)
        ask.reply("加")
        assertTrue(cues.sent.isEmpty())
        assertTrue(unrelayed.isEmpty())
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("还在吗")).status())
        assertEquals(listOf("要不要加一个人"), ask.questions)
    }

    @Test fun askWithoutAReachableOwnerSaysSoAndFreesTheSlot() = runBlocking {
        val ask = FakeAsk(OwnerReach.Unreachable)
        val desk = PhoneDesk({ null }, ask)
        assertEquals("unavailable", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        ask.reach = OwnerReach.Notified
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("能不能付定金")).status())
        assertEquals("invalid_arguments", PhoneDesk({ null }, FakeAsk()).execute(DeskTools.ASK_OWNER, args("  ")).status())
    }

    @Test fun aQuestionThatOnlyReachedTheConversationTellsTheCallerToExpectAFollowUp() = runBlocking {
        // When the notification cannot be sent (the ask channel is off) the question still enters the conversation: still asked, but the model is told to say it will get back to the caller
        val desk = PhoneDesk({ null }, FakeAsk(OwnerReach.InConversation))
        val result = desk.execute(DeskTools.ASK_OWNER, args("几点到"))
        assertEquals("asked", result.status())
        assertTrue(result.text("next").contains("get back to them"))
    }

    @Test fun cutReplyIsResentOnceThenHeldForTheNextAskAndLoggedIfNeverRelayed() = runBlocking {
        val ask = FakeAsk()
        val cues = FakeCues()
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("几点到"))
        ask.reply("七点")
        cues.ended.single().invoke(false)
        assertEquals(2, cues.sent.size)
        cues.ended[1].invoke(false)
        assertEquals(2, cues.sent.size)
        // Neither attempt got through: it is kept and handed to the model as the result the next time it asks, for it to relay
        val held = desk.execute(DeskTools.ASK_OWNER, args("主人回了吗"))
        assertEquals("owner_replied", held.status())
        assertEquals("七点", held.text("reply"))
        assertEquals(listOf("几点到"), ask.questions)
        assertTrue(unrelayed.isEmpty())

        // Once spoken, it is not kept
        desk.execute(DeskTools.ASK_OWNER, args("几个人"))
        ask.reply("四个")
        cues.ended.last().invoke(true)
        desk.close()
        assertTrue(unrelayed.isEmpty())
    }

    @Test fun withoutACueInputTheReplyWaitsForTheNextAskOrGoesToTheTranscript() = runBlocking {
        // The protocol has no text input that lets the model keep replying (cue returns false): do not retry, leave it for the next ask; if still unclaimed at hangup, record it in the call log
        val ask = FakeAsk()
        val cues = FakeCues(accept = false)
        val unrelayed = mutableListOf<String>()
        val desk = PhoneDesk({ null }, ask, cues::cue) { unrelayed += it }
        desk.execute(DeskTools.ASK_OWNER, args("要包间吗"))
        ask.reply("不要")
        assertTrue(cues.sent.isEmpty())
        assertTrue(unrelayed.isEmpty())
        desk.close()
        assertEquals(listOf("不要"), unrelayed)
    }

    @Test fun withoutCuesTheModelIsToldToCollectTheReplyAndReAskingCollectsIt() = runBlocking {
        // Protocols that cannot receive cues: the reply does not arrive by itself, so both the ask result and the prompt tell the model to ask again to collect it
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, canCue = false)
        val asked = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("asked", asked.status())
        assertTrue(asked.text("next").contains("will NOT arrive by itself"))
        assertTrue(asked.text("next").contains("call ask_owner again"))
        // Not replied yet: asking again is not busy, it is told we are still waiting, and no new question is sent
        val waiting = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("still_waiting", waiting.status())
        assertEquals(listOf("能不能改到周日"), ask.questions)
        // Replied: asking again gets the original words for the model to relay
        ask.reply("周日也行")
        val replied = desk.execute(DeskTools.ASK_OWNER, args("能不能改到周日"))
        assertEquals("owner_replied", replied.status())
        assertEquals("周日也行", replied.text("reply"))
        // After it has been collected, the next ask is a new question
        assertEquals("asked", desk.execute(DeskTools.ASK_OWNER, args("几个人")).status())
        assertEquals(listOf("能不能改到周日", "几个人"), ask.questions)
    }

    @Test fun withoutCuesAQuietQuestionAlsoSaysToCollectTheReply() = runBlocking {
        val next = PhoneDesk({ null }, FakeAsk(OwnerReach.InConversation), canCue = false)
            .execute(DeskTools.ASK_OWNER, args("几点到")).text("next")
        assertTrue(next.contains("get back to them"))
        assertTrue(next.contains("call ask_owner again"))
    }

    @Test fun cueCapableCallsKeepTheNoteWordingAndBusy() = runBlocking {
        val ask = FakeAsk()
        val desk = PhoneDesk({ null }, ask, FakeCues()::cue)
        val next = desk.execute(DeskTools.ASK_OWNER, args("几点到")).text("next")
        assertTrue(next.contains("will reach you as a note"))
        assertFalse(next.contains("call ask_owner again"))
        assertEquals("busy", desk.execute(DeskTools.ASK_OWNER, args("几点到")).status())
    }

    @Test fun thePromptsRelayRuleFollowsWhetherTheProtocolCanCue() {
        val cue = PhoneDesk.systemPrompt("电话规则", null, null, canCue = true)
        val noCue = PhoneDesk.systemPrompt("电话规则", null, null, canCue = false)
        assertTrue(cue.contains(PhoneDesk.RELAY_BY_CUE))
        assertFalse(cue.contains(PhoneDesk.RELAY_BY_ASKING))
        assertTrue(noCue.contains(PhoneDesk.RELAY_BY_ASKING))
        assertFalse(noCue.contains(PhoneDesk.RELAY_BY_CUE))
        // Which protocols can receive cues: standard calls and realtime protocols that allow a system text item can; duplex conversation protocols only have read-aloud text input and cannot
        assertTrue(PhoneDesk.canCue(standard = true, protocol = CallVoiceProtocol.VOLC_DUPLEX))
        assertTrue(PhoneDesk.canCue(standard = false, protocol = CallVoiceProtocol.OPENAI_REALTIME))
        assertFalse(PhoneDesk.canCue(standard = false, protocol = CallVoiceProtocol.VOLC_DUPLEX))
    }

    @Test fun ownerTaskStateIsRenderedForTheDeskContext() = runBlocking {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 3, note = "phone_call_dial"))
        val desk = PhoneDesk({ state }, FakeAsk())
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "in progress", desk.ownerTaskLine())
        state.value = TaskState.WaitingInput("哪天?")
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "waiting for the owner", desk.ownerTaskLine())
        // It is also included in the ask result, so the model still knows when the protocol cannot push state
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "waiting for the owner", desk.execute(DeskTools.ASK_OWNER, args("x")).text("owner_task"))
        state.value = TaskState.Cancelled
        assertEquals(PhoneDesk.OWNER_TASK_PREFIX + "cancelled", desk.ownerTaskLine())
        assertNull(PhoneDesk({ null }, FakeAsk()).ownerTaskLine())
    }

    @Test fun realtimePushesOnlyOwnerTaskChangesAsSilentContext() = runBlocking {
        val state = MutableStateFlow<TaskState>(TaskState.Running(step = 1, note = "a"))
        val cues = FakeCues()
        val desk = PhoneDesk({ state }, FakeAsk(), cues::cue, context = cues::context)
        val pushing = async { desk.pushOwnerTaskChanges() }
        yield()
        // The opening state is already in the prompt; step changes within the same state are not pushed
        state.value = TaskState.Running(step = 2, note = "b")
        yield()
        assertTrue(cues.context.isEmpty())
        state.value = TaskState.Succeeded("已经订好")
        withTimeout(1_000) { while (cues.context.isEmpty()) yield() }
        val line = cues.context.single()
        assertTrue(line.startsWith(PhoneDesk.CUE_MARK))
        assertTrue(line.contains(PhoneDesk.OWNER_TASK_PREFIX + "done"))
        // State is background only and does not make the model speak
        assertTrue(cues.sent.isEmpty())
        desk.close()
        withTimeout(1_000) { pushing.await() }
        assertFalse(pushing.isCancelled)
    }
}
