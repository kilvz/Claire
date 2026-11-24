package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelJson
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Suppress("EnumEntryName")
enum class NoteKind { added, updated, forgot, pending }

/**
 * One line under the result card. It carries only the kind and the ID, not the memory content: the conversation file
 * is plaintext, so the content is fetched fresh from the encrypted memory each time ([text]).
 * [undoToken] is handed to [MemoryStore.undo]; [pendingId] is the pending-approval procedure ("OK" = approve,
 * "no" = undo or delete).
 */
data class MemoryNote(val kind: NoteKind, val memoryId: String, val undoToken: String?, val pendingId: String?) {
    /** [item] is the item [memoryId] refers to at this moment; when it is already gone (deleted, cleared) it writes [DELETED]. */
    fun text(item: MemoryItem?): String {
        val content = item?.content ?: DELETED
        return when (kind) {
            NoteKind.added -> "Remembered: $content"
            NoteKind.updated -> "Updated: $content" + (item?.replaces?.let { " (was: $it)" } ?: "")
            NoteKind.forgot -> if (item == null) "Forgot a memory" else "Forgot: $content"
            NoteKind.pending -> "Should I always do it this way from now on? $content"
        }
    }

    companion object {
        const val DELETED = "a deleted memory"
    }
}

/**
 * A separate call after the task ends (lightweight model, no tools): it looks only at what the user said this time
 * and at the relevant existing memories, and proposes additions, updates and deletions.
 * Every item the model produces is re-validated by code; if the model fails or its reply is unusable, nothing is done
 * and the task outcome is unaffected.
 */
class MemoryExtractor(private val model: ChatModel) {
    suspend fun extract(utterances: List<String>, related: List<MemoryItem>, store: MemoryStore, source: (String) -> MemorySource): List<Change> {
        if (utterances.none { it.isNotBlank() }) return emptyList()
        val labels = related.take(MAX_RELATED).mapIndexed { i, m -> "#${i + 1}" to m }.toMap()
        val ops = try {
            ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, listOf(Part.Text(request(utterances, labels))), Author.Mika)), ::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val said = UserUtterances().apply { utterances.forEach(::add) }
        return ops.take(MAX_OPS).mapNotNull { apply(it, labels, said, store, source) }
    }

    private fun apply(o: JsonObject, labels: Map<String, MemoryItem>, said: UserUtterances, store: MemoryStore, source: (String) -> MemorySource): Change? {
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
        val quote = str("quote")?.takeIf { said.contains(it) } ?: return null
        val keywords = (o["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
        val target = str("target")?.let { t -> labels[t] ?: labels["#$t"] }
        fun fromUser(content: String) = (listOf(content) + keywords).all { said.unsupportedSpecifics(it, quote).isEmpty() }
        return when (str("op")) {
            "add" -> {
                val kind = MemoryKind.entries.find { it.name == str("kind") } ?: return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.add(kind, content, keywords, source(quote)).getOrNull()
            }
            "update" -> {
                val t = target ?: return null
                // a one-off parameter must not change a long-term preference
                if (ONE_OFF.containsMatchIn(quote)) return null
                val content = str("content")?.takeIf(::fromUser) ?: return null
                store.update(t.id, content, keywords.takeIf { it.isNotEmpty() }, source(quote)).getOrNull()
            }
            "forget" -> {
                val t = target ?: return null
                if (!FORGET.containsMatchIn(quote)) return null
                store.forget(t.id).getOrNull()
            }
            else -> null
        }
    }

    private fun request(utterances: List<String>, labels: Map<String, MemoryItem>) = buildString {
        appendLine("What the user said this time:")
        utterances.filter { it.isNotBlank() }.forEachIndexed { i, u -> appendLine("${i + 1}. $u") }
        appendLine()
        if (labels.isEmpty()) append("Related existing memories: none")
        else {
            appendLine("Related existing memories:")
            labels.forEach { (label, m) -> appendLine("$label [${m.kind.name}] ${m.content}") }
        }
    }.trimEnd()

    companion object {
        const val MAX_OPS = 5
        const val MAX_RELATED = 10
        private val FORGET = Regex("忘|别记|删|不用记|forget", RegexOption.IGNORE_CASE)
        private val ONE_OFF = Regex("这次|这一次|今天先|this time", RegexOption.IGNORE_CASE)

        val PROMPT: String get() = Prompts.role("memory_extract")

        /** Existing memories related to what was said this time (pending ones are included too, so the same procedure is not proposed twice). */
        fun related(utterances: List<String>, items: List<MemoryItem>): List<MemoryItem> =
            MemoryIndex(items).search(utterances.joinToString("\n"), MAX_RELATED, includePending = true).map { it.first }

        /** Turns changes into hint lines under the result card. */
        fun notes(changes: List<Change>): List<MemoryNote> = changes.map { c ->
            when (c) {
                is Change.Added ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.added, c.item.id, c.undoToken, null)
                is Change.Updated ->
                    if (c.item.status == MemoryStatus.pending) MemoryNote(NoteKind.pending, c.item.id, c.undoToken, c.item.id)
                    else MemoryNote(NoteKind.updated, c.item.id, c.undoToken, null)
                is Change.Forgot -> MemoryNote(NoteKind.forgot, c.before.id, c.undoToken, null)
            }
        }

        /** The first readable JSON object in the reply (see [ModelJson]); null if it is not valid. */
        internal fun parse(text: String): List<JsonObject>? {
            val obj = ModelJson.firstObject(text) ?: return null
            return (obj.field("ops", "operations") as? JsonArray)?.mapNotNull { it as? JsonObject }
        }
    }
}
