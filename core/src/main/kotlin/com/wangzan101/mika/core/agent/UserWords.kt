package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.ToolDispatcher

/**
 * The user's own words within one utterance (same convention for the opening, interjections, and answers to follow-up questions). Language hints and the answer's language check look only at what the user typed this time ([typed]);
 * memory and skill quotes also accept text the user quoted of themselves ([said]), while quotes of Claire's answers do not count.
 */
internal object UserWords {
    /**
     * [text] is the utterance with the screen description removed (see [UserScreenTurns]); then, in order, the browser page description ([ToolDispatcher.userWords]),
     * the quoted passage at the front ([QuoteMessage]), and the attachment lines at the end ([AttachmentLines]) are removed. When none of these apply, it is the original utterance; when only a passage was quoted, only text was selected, or only attachments were attached, it is empty.
     */
    fun typed(text: String, tools: ToolDispatcher): String = AttachmentLines.words(QuoteMessage.words(tools.userWords(text)))

    /** In addition to [typed], when the quote is something the user said themselves the quoted passage is included too (on the conversation page, the quote is what they typed at the time) and placed first; when Claire's answer is quoted, only [typed] is used. */
    fun said(text: String, tools: ToolDispatcher): String {
        val own = QuoteMessage.parse(tools.userWords(text)).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text, tools)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
