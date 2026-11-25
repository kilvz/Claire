package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.model.Part
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user only sees Claire: the words the model can read (reply, call and phone prompts, each turn's background marker, tool descriptions, earlier turns given to a task)
 * must not contain internal layering, or the model will repeat "handed it to the background". The only exception is the prompt line that explicitly says not to do this.
 */
class OneMikaWordingTest {
    private val inside = listOf("front desk", "frontdesk", "backend", "back end", "background task", "前台", "后台")

    private fun assertNoInsideWords(where: String, text: String) {
        val lower = text.lowercase()
        for (word in inside) assertFalse("$where contains \"$word\"", word in lower)
    }

    /** Remove the "do not mention how it works inside" line (it has to name these words), then check the rest as usual. */
    private fun withoutTheDontSayRule(prompt: String): String {
        val start = prompt.indexOf("- Never mention how it works inside")
        assertTrue("The prompt is missing the \"do not mention how it works inside\" line", start >= 0)
        val end = prompt.indexOf("\n- ", start + 1).takeIf { it > 0 } ?: prompt.length
        return prompt.removeRange(start, end)
    }

    @Test fun replyPromptsSpeakAsOneMika() {
        for (spoken in listOf(false, true)) {
            val prompt = DeskPrompt.user(spoken)
            assertTrue(prompt.startsWith(if (spoken) LlmBrain.SPOKEN_STYLE else "You are Claire"))
            assertNoInsideWords("Reply prompt (spoken=$spoken)", withoutTheDontSayRule(prompt))
        }
    }

    @Test fun phonePromptSpeaksAsMika() {
        assertNoInsideWords("Phone prompt", DeskPrompt.phone("goal: x", "policy"))
    }

    @Test fun markersAndToolDescriptionsCarryNoInsideWords() {
        assertNoInsideWords("Background marker", LlmBrain.BACKGROUND_PREFIX)
        for (set in DeskToolSet.entries) assertNoInsideWords("Tool descriptions $set", DeskTools.specs(set).joinToString("\n"))
    }

    @Test fun earlierTurnsShowOnlyMika() {
        val turns = listOf(
            Turn("帮我订明天的日料", "好,我去订。", DeskTurn.STATUS, deskTasks = listOf("c1")),
            Turn("顺便查汇率", "", DeskTurn.STATUS, deskTasks = listOf("c2")),
            Turn("", "日料订好了", DeskTurn.REPORT_STATUS, deskTasks = listOf("c1")),
        )
        val text = (Conversation.context(turns)!!.parts.single() as Part.Text).text
        assertNoInsideWords("Earlier turns", text)
    }
}
