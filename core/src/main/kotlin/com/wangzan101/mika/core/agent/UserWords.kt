package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.user.UserScreenMessage
import com.wangzan101.mika.core.tool.ToolDispatcher

/**
 * The user's own words within one message (opening, steering, answering a question and the words checked for front-desk dispatch all use one rule). The language hint and reply-language check only look at what they typed this time ([typed]);
 * Memory, skill citations and the original words checked for front-desk dispatch also accept his own earlier words when quoted ([said]); a quoted answer from Claire does not count.
 */
object UserWords {
    /**
     * [text] is the utterance with the screen description removed (see [UserScreenTurns]); then, in order, the browser page description ([ToolDispatcher.userWords]),
     * the quoted passage at the front ([QuoteMessage]), and the attachment lines at the end ([AttachmentLines]) are removed. When none of these apply, it is the original utterance; when only a passage was quoted, only text was selected, or only attachments were attached, it is empty.
     */
    fun typed(text: String, tools: ToolDispatcher): String = typed(tools.userWords(text))

    /** Beyond [typed], when the quote is the user's own earlier words the quoted passage counts too (the chat page quotes what he typed then) and goes first; when it quotes Claire's answer only [typed] counts. */
    fun said(text: String, tools: ToolDispatcher): String = said(tools.userWords(text))

    /**
     * The front-desk rule: the front desk has no tool dispatcher, so screen notes ([UserScreenMessage]) and browser page notes ([AttachedPage]) are stripped first, then the same as [said].
     * A quoted answer from Claire and attachment lines do not count as the user's words and fail front-desk dispatch checks.
     */
    fun saidToDesk(text: String): String = said(AttachedPage.words(UserScreenMessage.parse(text).words))

    /** [text] with the notes from each capability removed. */
    private fun typed(text: String): String = AttachmentLines.words(QuoteMessage.words(text))

    private fun said(text: String): String {
        val own = QuoteMessage.parse(text).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
