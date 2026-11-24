package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's own words within a single message: the text typed this time ([UserWords.typed]) and the part that memory and skill quotes count as said ([UserWords.said]), following the chat-page and browser-page composition rules. */
class UserWordsTest {
    private val tools = toolDispatcher(FakeScreen(), web = FakeWebPort())
    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "你常用的收货地址是幸福路 8 号")
    private val own = QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子")
    private val pdf = listOf("/workspace/inbox/a.pdf" to "2 KB")

    private fun words(text: String) = UserWords.typed(text, tools) to UserWords.said(text, tools)

    // Quoting Claire's reply: the quoted passage counts for neither; attachment lines do not count either, so with only an attachment and no typing both are empty
    @Test fun aQuotedReplyIsNotTheUserWords() {
        assertEquals("照这个改" to "照这个改", words(AttachmentLines.compose(QuoteMessage.compose(mika, "照这个改"), pdf)))
        assertEquals("" to "", words(AttachmentLines.compose(QuoteMessage.compose(mika, ""), pdf)))
    }

    // Quoting something the user said themselves: the typed text is only this time's, while the memory quote also counts the quoted passage, placed in front
    @Test fun anOwnQuoteIsSaidButNotTyped() {
        assertEquals("这个记一下" to "我家猫叫团子\n这个记一下", words(AttachmentLines.compose(QuoteMessage.compose(own, "这个记一下"), pdf)))
        assertEquals("" to "我家猫叫团子", words(AttachmentLines.compose(QuoteMessage.compose(own, ""), pdf)))
    }

    // Asking on a browser page: the page description is stripped as before
    @Test fun thePageDescriptionIsNotTheUserWords() {
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        assertEquals("这一页的地址对不对" to "这一页的地址对不对", words(AttachedPage.compose(page, "这一页的地址对不对")))
    }

    // Text without quotes and attachments stays as-is; ordinary text that starts with ">" and text starting with ">" typed after a quote are not treated as quotes
    @Test fun plainWordsStayAsTheyAre() {
        assertEquals("查天气" to "查天气", words("查天气"))
        assertEquals("> 这句是我自己写的\n后面" to "> 这句是我自己写的\n后面", words("> 这句是我自己写的\n后面"))
        assertEquals("> 这句什么意思\n解释一下" to "> 这句什么意思\n解释一下", words(QuoteMessage.compose(mika, "> 这句什么意思\n解释一下")))
    }
}
