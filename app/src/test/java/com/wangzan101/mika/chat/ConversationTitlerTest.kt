package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationTitlerTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A model that replies with fixed text; throws when [fail] is set and never replies when [hang] is set. */
    private class Titler(private val reply: String = "", private val fail: Boolean = false, private val hang: Boolean = false) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 8_000, reasoning = false)
        val requests = mutableListOf<List<Message>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages
            if (fail) error("offline")
            if (hang) delay(60_000)
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }

    private fun store() = ConversationStore(tmp.newFolder("c"))
    private fun text(m: Titler) = m.requests.single().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("") { it.text }

    @Test fun titlesTheFirstMessageOnce() = runBlocking {
        val s = store()
        val id = s.newConversation()
        val m = Titler("附近的川菜")
        ConversationTitler.ensure(s, id, "帮我看看附近评分最高的川菜") { listOf(m) }
        assertEquals("附近的川菜", s.title(id))
        // A second attempt (the model puts it differently) changes nothing and makes no further request
        val again = Titler("别的名字")
        ConversationTitler.ensure(s, id, "第二句话") { listOf(again) }
        assertEquals("附近的川菜", s.title(id))
        assertTrue(again.requests.isEmpty())
    }

    @Test fun promptCarriesOnlyTheFirstMessage() = runBlocking {
        val m = Titler("订高铁票")
        ConversationTitler.generate(listOf(m), "订明天的高铁票")
        assertEquals("<message>\n订明天的高铁票\n</message>", text(m))
    }

    /** The first message asked on a browser page: the page description is not passed to the titling model, only what the user said. */
    @Test fun pageContextIsNotPartOfTheTitleInput() = runBlocking {
        val m = Titler("挑耳机")
        val first = com.wangzan101.mika.core.agent.AttachedPage.compose(
            com.wangzan101.mika.core.agent.AttachedPage.Page("https://shop.example.com/s", "搜索结果"), "挑一个评价最好的")
        assertEquals("挑耳机", ConversationTitler.generate(listOf(m), first))
        assertTrue(text(m).contains("挑一个评价最好的"))
        assertFalse(text(m).contains("shop.example.com"))
    }

    @Test fun notRegeneratedOnceTheConversationHasTurns() = runBlocking {
        val s = store()
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded"))
        val m = Titler("天气")
        ConversationTitler.ensure(s, id, "查天气") { listOf(m) }
        assertNull(s.title(id))
        assertTrue(m.requests.isEmpty())
        // An old conversation has no title, so the list still shows the first message
        assertEquals("查天气", s.list().single().title)
    }

    @Test fun failureLeavesNoTitle() = runBlocking {
        val s = store()
        val id = s.newConversation()
        ConversationTitler.ensure(s, id, "查天气") { listOf(Titler(fail = true)) }
        assertNull(s.title(id))
        s.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals("查天气", s.list().single().title)
    }

    @Test fun fallsBackToTheNextModelAndTimesOut() = runBlocking {
        assertEquals("天气", ConversationTitler.generate(listOf(Titler(fail = true), Titler("天气")), "查天气"))
        assertNull(ConversationTitler.generate(listOf(Titler(hang = true)), "查天气", timeoutMs = 50))
        assertNull(ConversationTitler.generate(emptyList(), "查天气"))
    }

    @Test fun screenOnlyMessageIsNotTitled() = runBlocking {
        val m = Titler("屏幕")
        val line = com.wangzan101.mika.core.screen.user.UserScreenMessage.line("abc")
        assertNull(ConversationTitler.generate(listOf(m), line))
        assertTrue(m.requests.isEmpty())
        // With a trailing remark only the remark is given to the model
        ConversationTitler.generate(listOf(m), line + "\n这是什么")
        assertEquals("<message>\n这是什么\n</message>", text(m))
    }

    @Test fun cleansTheReply() {
        assertEquals("附近的川菜", ConversationTitler.clean("「附近的川菜」"))
        assertEquals("附近的川菜", ConversationTitler.clean("标题：附近的川菜。"))
        assertEquals("Book train", ConversationTitler.clean("\"Book train\"\n多余的解释"))
        assertEquals(40, ConversationTitler.clean("一".repeat(60))!!.length)
        // English titles are not chopped at the Chinese-length limit
        assertEquals("Book a taxi to the airport", ConversationTitler.clean("Book a taxi to the airport"))
        assertNull(ConversationTitler.clean("  \n "))
        assertNull(ConversationTitler.clean("「」"))
    }

    /** A title in another script is discarded rather than shown: the list falls back to the first message instead of a Chinese name for an English chat. */
    @Test fun titleStaysInTheMessagesScript() = runBlocking {
        assertNull(ConversationTitler.generate(listOf(Titler("附近的川菜")), "Show me what you can do"))
        assertEquals(
            "Capabilities demo",
            ConversationTitler.generate(listOf(Titler("附近的川菜"), Titler("Capabilities demo")), "Show me what you can do"),
        )
        assertEquals("附近的川菜", ConversationTitler.generate(listOf(Titler("附近的川菜")), "带我看看你能做什么"))
    }

    @Test fun titleRoundTripsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        assertFalse(a.setTitle(id, "  "))
        assertTrue(a.setTitle(id, "附近的川菜"))
        assertFalse(a.setTitle(id, "改一下"))
        a.append(id, Turn("帮我看看附近评分最高的川菜", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals("附近的川菜", b.title(id))
        assertEquals("附近的川菜", b.list().single().title)
        // The title does not go into the conversation file, and the turns still read back
        assertEquals(listOf("帮我看看附近评分最高的川菜"), b.turns(id).map { it.userText })
        assertNull(b.title("不存在"))
    }
}
