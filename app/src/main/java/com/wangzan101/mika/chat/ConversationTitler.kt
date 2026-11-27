package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.Character.UnicodeScript

/**
 * The conversation title: the model is asked once when the first message is sent, and it is never changed afterwards. The model only gets the text of the first message
 * (no screen, history, or anything else); if no title can be produced (no model, error, timeout, a reply that does not look like a title) nothing is recorded, and the UI keeps using the first message.
 */
object ConversationTitler {
    const val TIMEOUT_MS = 8_000L
    private const val MAX_TITLE_CJK = 16
    private const val MAX_TITLE_ALPHABETIC = 40
    private const val MAX_INPUT = 400

    /**
     * When conversation [id] has no title yet and no turn has been stored, generate one from [first] and record it. [models] are tried in order (lightweight models first, the main model as fallback).
     * Not generated when only a screen was sent and there is no text. Never throws (cancellation propagates as usual).
     */
    suspend fun ensure(
        store: ConversationStore,
        id: String,
        first: String,
        models: () -> List<ChatModel>,
        language: String? = null,
    ) {
        if (store.title(id) != null || store.turns(id).isNotEmpty()) return
        val title = generate(models(), first, language = language) ?: return
        store.setTitle(id, title)
    }

    suspend fun generate(
        models: List<ChatModel>,
        first: String,
        timeoutMs: Long = TIMEOUT_MS,
        language: String? = null,
    ): String? {
        // 网页说明不算第一句话;只带着选中的字、没写话时按选中的字起
        val page = AttachedPage.parse(first)
        val said = page.words.ifBlank { page.page?.selection.orEmpty() }
        val words = UserScreenMessage.parse(said).words.take(MAX_INPUT)
        if (words.isBlank()) return null
        val input = "<message>\n" + words.replace(TAG, "") + "\n</message>"
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        val systemPrompt = SystemPrompt.withLanguage(Prompts.role("conversation_title"), language)
        for (m in models.distinct()) {
            val title = try {
                withTimeoutOrNull(timeoutMs) {
                    val raw = m.complete(systemPrompt, messages, emptyList())
                        .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                    // The script check reads the whole cleaned title; truncating first could drop the only CJK
                    // character of a mixed-script title and make it look like the model switched languages.
                    val full = trimReply(raw)
                    if (full == null || scriptsDiffer(words, full)) null else clean(full)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (title != null) return title
        }
        return null
    }

    /** True when one text has CJK characters and the other has none, so a title never changes the message's language. */
    private fun scriptsDiffer(input: String, title: String): Boolean = hasCjk(title) != hasCjk(input)

    private val TAG = Regex("</?\\s*message\\s*>", RegexOption.IGNORE_CASE)
    private val WRAP = "\"'“”‘’「」『』《》`*#".toSet()
    private val TAIL = "。.!！?？,，;；:：、 ".toSet()

    /** The first non-empty line without quotes at both ends, trailing punctuation, or a "Title:" prefix; null when empty. */
    private fun trimReply(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.replace(Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim { it in WRAP || it.isWhitespace() }.trimEnd { it in TAIL }.trim { it in WRAP || it.isWhitespace() }
        return t.takeIf(String::isNotBlank)
    }

    /** The cleaned reply, truncated to the CJK or alphabetic limit; null when empty. */
    fun clean(raw: String): String? {
        val t = trimReply(raw) ?: return null
        val maxTitle = if (hasCjk(t)) MAX_TITLE_CJK else MAX_TITLE_ALPHABETIC
        return t.take(maxTitle).takeIf(String::isNotBlank)
    }

    /** 按码点识别文字体系,包含兼容汉字和基本多文种平面以外的汉字。 */
    private fun hasCjk(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when (UnicodeScript.of(codePoint)) {
                UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HANGUL -> return true
                else -> Unit
            }
            index += Character.charCount(codePoint)
        }
        return false
    }
}
