package com.wangzan101.mika.ui

import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import com.wangzan101.mika.chat.ui.AttachmentMessage
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.ScreenCopy

/** Quoting on the chat screen: quotes and attachments stay separate when both are present, and titles leave the quote out; answers copy as plain text. For how a quote is written into the message, see QuoteMessageTest in core. */
class QuoteMessageTest {
    @get:Rule val texts = ChineseTexts()

    private val mika = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天上午有小雨\n下午转晴")

    // Quote plus attachment: the attachment line follows the words, and parsing keeps each part separate
    @Test fun quotesAndAttachmentsTogether() {
        val goal = ChatViewModel.withAttachments(QuoteMessage.compose(mika, "照这个改"), listOf("/workspace/inbox/a.pdf" to 2048L))
        val q = QuoteMessage.parse(goal)
        assertEquals(mika, q.quote)
        val a = AttachmentMessage.parse(q.words)
        assertEquals("照这个改", a.words)
        assertEquals(listOf("a.pdf"), a.files.map { it.name })
        // Only a quote and an attachment, with no words written
        val only = QuoteMessage.parse(ChatViewModel.withAttachments(QuoteMessage.compose(mika, ""), listOf("/workspace/inbox/a.pdf" to 2048L)))
        assertEquals(mika, only.quote)
        assertEquals("a.pdf", AttachmentMessage.plain(only.words))
    }

    // Titles and notifications naming this message leave the quote out
    @Test fun titlesSkipTheQuote() {
        val goal = QuoteMessage.compose(mika, "那要带伞吗")
        assertEquals("那要带伞吗", ChatCopy.plainTitle(goal))
        assertEquals("那要带伞吗", ScreenCopy.plain(goal))
    }

    @Test fun answersCopyAsPlainText() {
        assertEquals(
            "上海今天晴,详见 天气\n\n要点\n\n• 早上 12 度\n• 下午 20 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\nval x = 1\n\n城市\t天气\n上海\t晴",
            MarkdownModel.toPlainText(
                "上海今天**晴**,详见 [天气](https://example.com)\n\n## 要点\n\n- 早上 12 度\n- 下午 *20* 度\n\n1. 带伞\n2. 穿外套\n\n> 注意降温\n\n" +
                    "```kotlin\nval x = 1\n```\n\n---\n\n| 城市 | 天气 |\n| --- | --- |\n| 上海 | 晴 |",
            ),
        )
        assertEquals("", MarkdownModel.toPlainText(""))
    }
}
