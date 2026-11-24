package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation title: the model is asked once when the first message is sent, and it is never changed afterwards. The model only gets the text of the first message
 * (no screen, history, or anything else); if no title can be produced (no model, error, timeout, a reply that does not look like a title) nothing is recorded, and the UI keeps using the first message.
 */
object ConversationTitler {
    const val TIMEOUT_MS = 8_000L
    private const val MAX_TITLE = 16
    private const val MAX_INPUT = 400

    /**
     * When conversation [id] has no title yet and no turn has been stored, generate one from [first] and record it. [models] are tried in order (lightweight models first, the main model as fallback).
     * Not generated when only a screen was sent and there is no text. Never throws (cancellation propagates as usual).
     */
    suspend fun ensure(store: ConversationStore, id: String, first: String, models: () -> List<ChatModel>) {
        if (store.title(id) != null || store.turns(id).isNotEmpty()) return
        val title = generate(models(), first) ?: return
        store.setTitle(id, title)
    }

    suspend fun generate(models: List<ChatModel>, first: String, timeoutMs: Long = TIMEOUT_MS): String? {
        // The page description does not count as the first message; when only selected text is attached and nothing was written, generate from the selected text
        val page = AttachedPage.parse(first)
        val said = page.words.ifBlank { page.page?.selection.orEmpty() }
        val words = UserScreenMessage.parse(said).words.take(MAX_INPUT)
        if (words.isBlank()) return null
        val input = "<message>\n" + words.replace(TAG, "") + "\n</message>"
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (m in models.distinct()) {
            val title = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = m.complete(Prompts.role("conversation_title"), messages, emptyList())
                    clean(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
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

    private val TAG = Regex("</?\\s*message\\s*>", RegexOption.IGNORE_CASE)
    private val WRAP = "\"'“”‘’「」『』《》`*#".toSet()
    private val TAIL = "。.!！?？,，;；:：、 ".toSet()

    /** The first non-empty line of the reply becomes the title: removes quotes at both ends, trailing punctuation, and a "Title:" prefix, truncating if too long; null when empty. */
    fun clean(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.replace(Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim { it in WRAP || it.isWhitespace() }.trimEnd { it in TAIL }.trim { it in WRAP || it.isWhitespace() }
        return t.take(MAX_TITLE).takeIf(String::isNotBlank)
    }
}
