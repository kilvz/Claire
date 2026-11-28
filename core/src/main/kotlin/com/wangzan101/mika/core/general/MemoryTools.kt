package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryIndex
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemoryRejected
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/**
 * Refused by the rules (not said by the user, sensitive, no explicit request to change or delete): this is an answer about this entry, not an operational failure. It does not count toward consecutive failures —
 * when the user asks to save several entries at once, it is normal for some of them to be refused; stopping the whole task as "operations failed repeatedly" would then also count the entries already saved as failures.
 * It is still marked unrecoverable: do not retry the same entry. Real faults such as storage being full or unreadable data do not go through here.
 *
 * There is a cap: if the same entry (same arguments, same reason) is submitted verbatim again, or more than [com.wangzan101.mika.core.tool.MAX_QUIET_MEMORY_REFUSALS] entries are refused in one task,
 * it is no longer marked "does not count as failure", and after three in a row the guard stops the run (see [ToolContext.noteMemoryRefusal]).
 */
private fun refused(ctx: ToolContext, a: Args, error: String, message: String): ToolExecution {
    val result = fail(error, message, recoverable = false)
    return if (ctx.noteMemoryRefusal("${a.tool.name}|$error|${a.o}")) ToolExecution(JsonObject(result.content + ("counts_as_failure" to JsonPrimitive(false))))
    else result
}

/** Offered only when both memory and the user's utterances from this run are provided. */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val label = "Search memory"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        ctx.noteMemorySearch(found.map { (m, _) -> m.id })
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject {
                    put("id", m.id); put("kind", m.kind.name); put("content", m.content)
                    // Inferred by Claire itself: not said by the user, so mark it
                    if (m.source.type == MemoryContext.INFERRED) put("inferred", true)
                }
            }))
        }
    }

    /** What is in memory was said by the user earlier: it is handed to verification as facts given by the user. The entries Claire itself inferred were not said by the user, so they are provided as a separate fact found by a tool. */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val (own, said) = (r.content["memories"] as? JsonArray).orEmpty().partition { (it as? JsonObject)?.get("inferred") == JsonPrimitive(true) }
        fun lines(list: List<JsonElement>, head: String) =
            list.joinToString("\n", head) { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        return listOfNotNull(
            said.takeIf { it.isNotEmpty() }?.let { Observation(ObsKind.UserSaid, lines(it, "Memory search \"${o.argStr("query")}\":\n"), source = "memory") },
            // The inferred entries are things a tool found, not facts given by the user: they enter the ledger with their source stated
            own.takeIf { it.isNotEmpty() }?.let {
                Observation(ObsKind.Fact, lines(it, "Found by memory search \"${o.argStr("query")}\"; summarized earlier by Claire itself (not said by the user):\n"), source = "memory")
            },
        )
    }
}

/** Changes only Claire's own memory; does not touch the screen or the outside world. */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val label = "Save to memory"
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user, preference = what the user likes, procedure = how the user wants a kind of task done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. The user does not want cilantro in takeout orders."),
            "quote" to str("The user's exact words this is based on."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)!!
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()
        val utterances = ctx.config.utterances!!
        // The quote must come from the user's words in this turn, or from an explicitly confirmed previous turn recorded strictly; the screen, web pages and the assistant's own suggestions do not count
        if (!utterances.containsMemoryQuote(quote)) return refused(ctx, a, "quote_not_from_user", Feedback["memory.quote_not_from_user"])
        val store = ctx.config.memory!!
        // The quote matches, but concrete values in the content (addresses, numbers, names) must also have been said by the user and cannot be filled in from the screen.
        // When the user has turned on "let Claire record its own inferences" and also asked in this turn for it to be remembered, do not refuse: save it as-is, but record the source as inferred by Claire rather than posing as something the user said
        val inferred = (listOf(content) + keywords).any { utterances.unsupportedMemorySpecifics(it, quote).isNotEmpty() }
        if (inferred) {
            if (!store.inferenceAllowed()) return refused(ctx, a, "content_not_from_user", Feedback["memory.content_not_from_user"])
            // Even when enabled, it does not accept everything it sees: the quotation must fall in the half-sentence the user said this turn that asks it to remember.
            // Otherwise any sentence such as "help me look at this web page" could be used as a quotation and store text from the screen or web page;
            // in "don't remember my address; remember that I like tea", quoting the first half is not allowed either—the second half is what asks it to remember
            if (utterances.quotedClauses(quote).none { clauses -> clauses.any(MemoryRequests::asksToRemember) }) {
                return refused(ctx, a, "inference_not_requested", Feedback["memory.inference_not_requested"])
            }
        }
        val added = store.add(kind, content, keywords, MemorySource(if (inferred) MemoryContext.INFERRED else "user_said", quote.trim(),
            ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c ->
                ok {
                    put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name)
                    if (inferred) { put("inferred", true); put("note", Feedback["memory.saved_as_inferred"]) }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }
}

/** Changes or deletes one entry by the stable id returned by memory_search, only when the user explicitly asks in this turn; the change goes through the existing result notice and undo mechanism. */
internal object MemoryEdit : AgentTool {
    override val verb = Verb.MemoryEdit
    override val label = "Edit memory"
    override val risk = Risk.Read
    override val def = ToolDef("memory_edit",
        Prompts.tool("memory_edit"),
        mapOf(
            "op" to str("update = replace content and optionally keywords; delete = forget the entry.", listOf("update", "delete")),
            "id" to str("Exact stable id returned by memory_search. Never guess an id or choose between multiple plausible entries."),
            "content" to str("For update: the replacement sentence, at most 200 characters."),
            "quote" to str("The current user message explicitly requesting this update or deletion."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "For update: optional replacement search words, up to 8.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("op", "id", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        val id = a.str("id", max = 200)!!
        val quote = a.str("quote", max = 1000)!!
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30)
        if (op == "update" && content == null) throw ArgError("content", "required for update")
        val utterances = ctx.config.utterances!!
        // Judge negation on the full original message containing the quote, so the model cannot simply pick out "delete" from "do not delete".
        val messages = utterances.matchingCurrentMessages(quote)
        if (messages.isEmpty()) return refused(ctx, a, "quote_not_from_user", Feedback["memory.edit_quote_not_from_user"])
        if (op != "update" && op != "delete") throw ArgError("op", "must be update or delete")
        // The words explicitly ask to change (delete), do not say not to, and are not a mere question such as "if I said … what would you do"; the phrases for each language are in MemoryRequests
        if (!MemoryRequests.requested(op, quote, messages)) return refused(ctx, a, "edit_not_requested", Feedback["memory.edit_not_requested"])
        val candidates = ctx.memorySearchCandidates()
        if (candidates == null || id !in candidates) {
            return fail("not_found", Feedback["memory.not_found"], recoverable = true)
        }
        if (candidates.size > 1 && !quotesExactId(quote, id)) {
            return fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
        }
        val store = ctx.config.memory!!
        // The entry's original form: concrete values it already contained (addresses, numbers, names) still carried along do not count as newly added
        val before = store.items.value.firstOrNull { it.id == id }
        var carried = false
        if (op == "update") {
            val texts = listOf(content!!) + keywords.orEmpty()
            val known = before?.let { listOf(it.content) + it.keywords }.orEmpty()
            if (texts.any { utterances.unsupportedEditSpecifics(it, quote, known).isNotEmpty() }) {
                return refused(ctx, a, "content_not_from_user", Feedback["memory.edit_content_not_from_user"])
            }
            // The new content contains concrete values that only stand up because of the original entry (the user did not say them in this turn)
            carried = texts.any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }
        }
        // An entry Claire inferred stays inferred after rewriting: its concrete values are still not what the user said. Only when the user said all the content aloud in this turn is it re-recorded as said by the user
        val type = if (carried && before?.source?.type == MemoryContext.INFERRED) MemoryContext.INFERRED else "user_said"
        val changed: Result<Change> = when (op) {
            "update" -> store.update(id, content!!, keywords,
                MemorySource(type, quote.trim(), ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
            "delete" -> store.forget(id)
            else -> throw ArgError("op", "must be update or delete")
        }
        changed.getOrNull()?.let(ctx::noteSaved)
        return changed.fold(
            onSuccess = { c ->
                ok {
                    put("op", op); put("id", id)
                    when (c) {
                        is Change.Updated -> { put("kind", c.item.kind.name); put("status", c.item.status.name) }
                        is Change.Forgot -> put("deleted", true)
                        else -> Unit
                    }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> refused(ctx, a, "sensitive", Feedback["memory.sensitive"])
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "not_found" -> fail("not_found", Feedback["memory.not_found"], recoverable = true)
                    "duplicate_id" -> fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    private fun quotesExactId(quote: String, id: String): Boolean =
        Regex("(?<![A-Za-z0-9_-])" + Regex.escape(id) + "(?![A-Za-z0-9_-])").containsMatchIn(quote)
}
