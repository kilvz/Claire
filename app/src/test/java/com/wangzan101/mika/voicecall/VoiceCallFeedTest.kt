package com.wangzan101.mika.voicecall

import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Said
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Speaker
import com.wangzan101.mika.voicecall.VoiceCallFeedItem.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCallFeedTest {

    private fun VoiceCallFeed.said() = items.value.filterIsInstance<Said>().map { Triple(it.speaker, it.text, it.partial) }

    @Test fun `partial user speech is replaced in place and settles on final`() {
        val feed = VoiceCallFeed()
        feed.onUser("帮我", isFinal = false)
        feed.onUser("帮我查天气", isFinal = false)
        assertEquals(listOf(Triple(Speaker.User, "帮我查天气", true)), feed.said())
        feed.onUser("帮我查天气。", isFinal = true)
        assertEquals(listOf(Triple(Speaker.User, "帮我查天气。", false)), feed.said())
        feed.onUser("再定个闹钟", isFinal = true)
        assertEquals(2, feed.items.value.size)
    }

    @Test fun `assistant draft grows in one bubble and an interruption keeps what was said`() {
        val feed = VoiceCallFeed()
        feed.onUser("天气", isFinal = true)
        feed.onAssistantDraft("好的,")
        feed.onAssistantDraft("好的,我看")
        assertEquals(Triple(Speaker.Mika, "好的,我看", true), feed.said().last())
        feed.onAssistant("好的,我看", isFinal = false)
        assertEquals(Triple(Speaker.Mika, "好的,我看", false), feed.said().last())
        feed.onAssistantDraft("明天多云")
        assertEquals("a new segment after an interruption starts a new item", 3, feed.items.value.size)
    }

    @Test fun `blank text is ignored`() {
        val feed = VoiceCallFeed()
        feed.onUser("  ", isFinal = true)
        feed.onAssistant("", isFinal = true)
        assertTrue(feed.items.value.isEmpty())
    }

    @Test fun `a task card collects new status lines and closes when the route is idle`() {
        val feed = VoiceCallFeed()
        val old = listOf("上次办完了")
        feed.onTask(busy = false, lines = old)
        assertTrue("no card when not running a task", feed.items.value.isEmpty())
        feed.onTask(busy = true, lines = old)
        var task = feed.items.value.single() as Task
        assertTrue(task.running)
        assertTrue("lines left over from the previous run do not count as this run's steps", task.steps.isEmpty())
        feed.onTask(busy = true, lines = old + "打开天气")
        feed.onTask(busy = true, lines = old + "打开天气" + "读取预报")
        feed.onTask(busy = false, lines = old + "打开天气" + "读取预报" + "已办完")
        task = feed.items.value.single() as Task
        assertEquals(listOf("打开天气", "读取预报", "已办完"), task.steps)
        assertFalse(task.running)
        feed.onTask(busy = true, lines = old + "打开天气" + "读取预报" + "已办完")
        assertEquals("the next task opens a new card", 2, feed.items.value.filterIsInstance<Task>().size)
    }

    @Test fun `the feed keeps only the latest items`() {
        val feed = VoiceCallFeed(maxItems = 3)
        repeat(5) { feed.onUser("第$it 句", isFinal = true) }
        assertEquals(listOf("第2 句", "第3 句", "第4 句"), feed.said().map { it.second })
    }
}
