package com.wangzan101.mika.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeShapesTest {
    private fun rec(pkg: String, title: String, text: String) = NoticeRecord("k", pkg, 1, title, text)
    private fun shaped(pkg: String, title: String, text: String) = NoticeShapes.apply(rec(pkg, title, text))

    // a QQ group notification seen on a real device: the title is "group name (13 new messages)", the body is "someone: that line"
    @Test fun qqGroup() {
        for (title in listOf("三年2班家长群(13条新消息)", "三年2班家长群", "三年2班家长群 (99条以上新消息)")) {
            val r = shaped("com.tencent.mobileqq", title, "王老师: 明天下午三点开家长会")
            assertEquals("三年2班家长群", r.title)
            assertEquals("王老师", r.sender)
            assertEquals("明天下午三点开家长会", r.text)
            assertEquals(true, r.group)
            assertEquals("王老师: 明天下午三点开家长会", r.said)
        }
        assertEquals("王老师", shaped("com.tencent.tim", "家长群(2条新消息)", "王老师: 好的").sender)
    }

    // QQ private chat: the body has no "someone: ", and the title is the other party
    @Test fun qqPrivate() {
        val r = shaped("com.tencent.mobileqq", "张三(2条新消息)", "明天来吗")
        assertEquals("张三", r.title)
        assertNull(r.sender)
        assertEquals("明天来吗", r.text)
        assertEquals(false, r.group)
        assertEquals("明天来吗", r.said)
        // the name in the body equals the title: also a private chat
        val same = shaped("com.tencent.mobileqq", "张三", "张三: 明天来吗")
        assertNull(same.sender)
        assertEquals("明天来吗", same.text)
        assertEquals(false, same.group)
    }

    // WeChat: the body is "[3 messages] someone: that line"; if the someone is the title it is a private chat, otherwise a group
    @Test fun wechat() {
        val private = shaped("com.tencent.mm", "张三", "[3条]张三: 明天来吗")
        assertEquals("张三", private.title)
        assertNull(private.sender)
        assertEquals("明天来吗", private.text)
        assertEquals(false, private.group)
        val group = shaped("com.tencent.mm", "家长群", "[12条]王老师: 明天开会")
        assertEquals("王老师", group.sender)
        assertEquals("明天开会", group.text)
        assertEquals(true, group.group)
        // a first message without a count, and one without "someone: " (no telling whether it is a group): only the count is stripped, nothing else changes
        assertEquals("王老师", shaped("com.tencent.mm", "家长群", "王老师: 明天开会").sender)
        val plain = shaped("com.tencent.mm", "微信支付", "[2条]你有一笔新的收款")
        assertEquals("你有一笔新的收款", plain.text)
        assertNull(plain.sender)
        assertNull(plain.group)
    }

    // anything that cannot be split is left as it is: the name is too long, the name contains a colon, there is no space after the colon, or nothing follows the colon
    @Test fun textThatIsNotSomeoneSayingSomethingIsLeftAlone() {
        val pkg = "com.tencent.mobileqq"
        for (text in listOf("x".repeat(41) + ": 你好", "网址 http://a.b: 看看", "时间:明天", "王老师: ", "只有一句话")) {
            val r = shaped(pkg, "家长群", text)
            assertEquals(text, text.trim(), r.text.trim())
            assertNull(text, r.sender)
        }
    }

    // notifications from other apps are left alone; conversation-style ones without a conversation name (private chats) are left alone too
    @Test fun otherAppsAndMessagingStyleAreUntouched() {
        val other = rec("com.shop", "订单(2条新消息)", "客服: 已发货")
        assertSame(other, NoticeShapes.apply(other))
        val styled = NoticeRecord("k", "com.tencent.mobileqq", 1, "群(2条新消息)", "甲: 乙", listOf(NoticeMessage("甲", "乙", 1)), conversation = true)
        assertSame(styled, NoticeShapes.apply(styled))
    }

    // conversation-style groups (any app): the system writes the title as "conversation name: speaker" and provides the conversation name separately. The title is replaced with the conversation name — otherwise every speaker in the same group would become a separate conversation
    @Test fun messagingStyleGroupsUseTheSystemsConversationName() {
        fun styled(title: String, conv: String?, group: Boolean?, sender: String) = NoticeShapes.apply(NoticeFilter.sanitize(NoticeRecord(
            "k", "org.chat.any", 1, title, "那句话", listOf(NoticeMessage(sender, "那句话", 1)), conversation = true, group = group, conversationTitle = conv)))
        val a = styled("周末爬山群: 张三", "周末爬山群", true, "张三")
        val b = styled("周末爬山群：李四", "周末爬山群", true, "李四")
        assertEquals("周末爬山群", a.title)
        assertEquals(a.title, b.title)
        assertEquals(true, a.group)
        assertEquals("张三", a.messages.single().sender)
        // the system did not say whether it is a group: a conversation name makes it a group
        assertEquals(true, styled("周末爬山群: 张三", "周末爬山群", null, "张三").group)
        // the speaker from the title is kept, and the line shown to the user and the model carries it
        assertEquals("张三", a.sender)
        assertEquals("李四", b.sender)
        assertEquals("张三: 那句话", a.said)
        assertNull(styled("周末爬山群", "周末爬山群", true, "张三").sender)
        // the speaker is the conversation name (a private chat that uses the other party's name as its conversation name): not recorded separately
        assertNull(styled("张三: 张三", "张三", false, "张三").sender)
        // the system says it is not a group, yet a conversation name is present: no telling (the old form only sets a conversation name without saying it is a group, while the new form can give a private chat a conversation name too), so it is not claimed to be a private chat
        val unsure = styled("备注名: 张三", "备注名", false, "张三")
        assertNull(unsure.group)
        assertEquals("备注名", unsure.title)
        assertEquals(false, unsure.titleIsSpeaker)
        // a group with no conversation name: the title is the speaker, the conversation cannot be recognised, and that is flagged; the title is left as is
        val nameless = styled("张三", null, true, "张三")
        assertEquals("张三", nameless.title)
        assertTrue(nameless.titleIsSpeaker)
        assertEquals(true, nameless.group)
        // private chat: no conversation name, so the title is the other party
        val p = styled("张三", null, false, "张三")
        assertEquals("张三", p.title)
        assertEquals(false, p.group)
        // a conversation name that is only whitespace or control characters counts as absent
        assertEquals("周末爬山群: 张三", styled("周末爬山群: 张三", " \u200B ", true, "张三").title)
    }

    // a known misreading (documented in the notes): in a QQ private chat, a line like "time: tomorrow" sent by the other party is taken as coming from a group member called "time"; the conversation still counts as that person's
    @Test fun knownMisreadKeepsTheConversation() {
        val r = shaped("com.tencent.mobileqq", "张三", "时间: 明天")
        assertEquals("张三", r.title)
        assertEquals("时间", r.sender)
    }

    // what every part sees once shaped: the triage input, the hints, the learn events and the successive segments in the inbox all carry the speaker for group messages, and the title is the clean conversation name
    @Test fun shapedRecordIsWhatEveryConsumerSees() {
        val r = NoticeShapes.apply(NoticeRecord("k", "com.tencent.mobileqq", 10, "家长群(3条新消息)", "王老师: 明天开会"))
        val input = NoticeTriage.buildInput(listOf(TriageItem(r)), "English")
        assertTrue(input, "家长群" in input && "王老师: 明天开会" in input && "条新消息" !in input)
        // whether it is a group or a private chat is written for the model to see, outside the untrusted block
        assertTrue(input, input.lines().contains("chat: group"))
        val hints = NoticeHints()
        hints.sync(listOf(r), mapOf("k" to TriageMark.Done(10, TriageVerdict(TriageDecision.Surface, "why"))), true)
        assertEquals("家长群" to "王老师: 明天开会", hints.state.value.list.single().let { it.title to it.text })
        val buffer = LearnBuffer()
        buffer.capture(r)
        val event = buffer.begin("com.tencent.mobileqq").single()
        assertEquals("家长群" to "王老师: 明天开会", event.title to event.text)
        assertEquals(true, event.group)
        assertTrue(NoticeLearn.buildInput("com.tencent.mobileqq", listOf(event), emptyList(), "English").lines().contains("chat: group"))
        val inbox = NoticeInbox()
        inbox.add(r)
        inbox.add(NoticeShapes.apply(NoticeRecord("k", "com.tencent.mobileqq", 20, "家长群(4条新消息)", "李老师: 收到")))
        assertEquals(listOf("王老师: 明天开会", "李老师: 收到"), inbox.snapshot().history.getValue("k").map { it.text })
        assertEquals(listOf("家长群", "家长群"), inbox.snapshot().history.getValue("k").map { it.title })
        // titles of the same group with different message counts resolve to the same contact
        assertEquals(
            ContactIdentity.of("com.tencent.mobileqq", "k", "家长群(3条新消息)")!!.id,
            ContactIdentity.of(r.packageName, r.key, r.title)!!.id,
        )
    }
}
