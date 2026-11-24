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
    override val label = "查找记忆"
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
            said.takeIf { it.isNotEmpty() }?.let { Observation(ObsKind.UserSaid, lines(it, "记忆检索「${o.argStr("query")}」:\n"), source = "memory") },
            // The inferred entries are things a tool found, not facts given by the user: they enter the ledger with their source stated
            own.takeIf { it.isNotEmpty() }?.let {
                Observation(ObsKind.Fact, lines(it, "记忆检索「${o.argStr("query")}」查到的、Claire 以前自己归纳的(不是用户说的):\n"), source = "memory")
            },
        )
    }
}

/** Changes only Claire's own memory; does not touch the screen or the outside world. */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val label = "写入记忆"
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
            // Even when this is on, it does not accept just anything: the quote must fall on the half-sentence the user said in this turn asking for it to be remembered.
            // Otherwise any phrase like "help me look at this web page" could serve as a quote to store text from the screen or a web page;
            // and in "don't remember my address, remember I like tea" quoting the first half does not work either — what was asked to be remembered is the second half
            if (utterances.quotedClauses(quote).none { clauses -> clauses.any(::asksToRemember) }) {
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

    /**
     * This half-sentence asks for it to be remembered: remember, note down, write to memory, store it, save, don't forget, remember, save, add ... to memory. Only Chinese and English verb forms are recognized
     * ([MemoryEdit] for changing or deleting memory recognizes only these two as well); merely mentioning the word "memory" does not count, and any other language is treated as not asking for it to be remembered —
     * a wrong match would store something the user did not agree to, so it is better not to match. Used only when accepting "inferred by Claire" entries: the user's own words still need no such wording.
     */
    private val SAVE_REQUEST = Regex(
        "[记記](?:住|下|一下|一[条條]|一[笔筆]|[几幾][条條]|到|[进進]|入|[着著]|好|上|吧|[录錄](?:一下|下|到|[进進]|在))|" +
            "[写寫][入进進到]|存[入进進到下一上吧]|存起[来來]|保存|儲存|[别別]忘|不要忘|(?:加|添加|放|收)[到进進入]\\S{0,8}?(?:记忆|記憶)|" +
            "\\b(?:remember|memori[sz]e|save|store|note\\s+(?:that|this|it|down)|keep\\s+in\\s+mind|" +
            "(?:add|write|put|commit)\\b[^,.;!?\\n]{0,40}?\\b(?:to|in|into)\\s+(?:your\\s+|the\\s+|my\\s+)?memor(?:y|ies))\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Asks whether it still remembers something; not a request for it to remember. */
    private val RECALL_QUESTION = Regex("\\b(?:do|did)\\s+you\\s+(?:still\\s+|even\\s+)?remember\\b|\\bwhat\\s+(?:do|did|can)\\s+you\\s+remember\\b", RegexOption.IGNORE_CASE)
    /** This half-sentence says not to remember: "don't remember it", "don't store these", "don't save". "Don't forget to note it down" and "can't recall" are not this. */
    private val SAVE_NEGATION = Regex(
        "(?<![能要用可])(?:不|别|別|勿|无需|無需|无须|無須|禁止)\\s*(?:要|用|再|去|必|该|該|应该|應該|许|許|准|可以|能)?\\s*(?:把[^,，。;；!！?？\\n]{0,12}?)?(?:给我|給我|帮我|幫我|替我)?\\s*" +
            "(?:[记記](?![得忆憶性])|[写寫][入进進到下]|存(?![在款货貨量储儲])|保存|儲存)|" +
            "\\b(?:do\\s+not|don'?t|never|no\\s+need\\s+to|not\\s+to|shouldn'?t|should\\s+not|mustn'?t|must\\s+not|won'?t|without)\\s+" +
            "(?:ever\\s+|actually\\s+|please\\s+|you\\s+)?(?:remember|memori[sz]e|sav(?:e|ing)|stor(?:e|ing)|not(?:e|ing)|keep(?:ing)?|add(?:ing)?|writ(?:e|ing)|put(?:ting)?|commit(?:ting)?)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Whether this half-sentence asks for it to be remembered: it contains a remembering verb phrase and is not a prohibition. */
    private fun asksToRemember(clause: String): Boolean {
        val c = clause.replace('’', '\'')
        return SAVE_REQUEST.containsMatchIn(RECALL_QUESTION.replace(c, " ")) && !SAVE_NEGATION.containsMatchIn(c)
    }
}

/** Changes or deletes one entry by the stable id returned by memory_search, only when the user explicitly asks in this turn; the change goes through the existing result notice and undo mechanism. */
internal object MemoryEdit : AgentTool {
    override val verb = Verb.MemoryEdit
    override val label = "修改记忆"
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
        val (request, negation) = when (op) {
            "update" -> UPDATE_REQUEST to UPDATE_NEGATION
            "delete" -> DELETE_REQUEST to DELETE_NEGATION
            else -> throw ArgError("op", "must be update or delete")
        }
        // The message explicitly asks for a change (or deletion), does not say not to, and is not merely a question like "if I said ..., what would you do"
        val requested = request.containsMatchIn(quote) && messages.none(negation::containsMatchIn) && messages.none(::onlyAsking)
        if (!requested) return refused(ctx, a, "edit_not_requested", Feedback["memory.edit_not_requested"])
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

    // Change requested: the various forms of the word "change" (change into, make more concise, change a bit; forms like "another day" or "transform" do not count), plus words that plainly mean change such as simplify, merge, tidy up, replace.
    // Recognizing only a few fixed phrasings would treat "make those entries a bit more concise" as not requesting a change
    private const val UPDATE_WORDS = "修改|改(?![天日期变變革善观觀口])|更新|更正|编辑|編輯|精简|精簡|缩短|縮短|简化|簡化|压缩|壓縮|合并|合併|整理|重写|重寫|换成|換成|替换|替換|调整|調整|润色|潤色"
    private const val UPDATE_WORDS_EN = "update|change|edit|correct|rewrite|reword|rephrase|shorten|simplify|merge|replace|trim|tidy"
    private const val DELETE_WORDS = "删|刪|忘掉|忘记|忘記|去掉|移除|清掉|清除"
    private const val DELETE_WORDS_EN = "delete|forget|remove|erase|drop"
    // "Can it be changed" and "does it need deleting" are questions and requests for it to act, not prohibitions: a negating word immediately preceded by a modal character (can / need / use / may) does not count as negation
    private const val NOT = "(?<![能要用可])(?:不|别|別|无需|無需|无须|無須|不用|不必|不要|不能|不准|禁止)\\s*(?:要|用|再|去)?\\s*"
    private const val NOT_EN = "\\b(?:do\\s+not|don't|dont|never|no\\s+need\\s+to)\\s+"
    private val UPDATE_REQUEST = Regex("$UPDATE_WORDS|\\b(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_REQUEST = Regex("$DELETE_WORDS|别记|別記|不要记|不要記|取消记忆|取消記憶|\\b(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val UPDATE_NEGATION = Regex("$NOT(?:$UPDATE_WORDS)|$NOT_EN(?:$UPDATE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_NEGATION = Regex("$NOT(?:$DELETE_WORDS)|$NOT_EN(?:$DELETE_WORDS_EN)\\b", RegexOption.IGNORE_CASE)

    /**
     * "If I said to delete that entry, what would you do": this asks how it would handle it, not for it to do it now. Memory edits and deletions do not pass the "discussion or action" gate
     * (see MEMORY_WRITE_VERBS in AgentLoop), so this kind of phrasing is rejected here. Both must be present: the hypothetical phrasing, plus a question about how it would act —
     * a hypothetical conditional alone is often exactly the content to be remembered ("if I say the usual place, it means the one downstairs from the office").
     */
    private val HYPOTHETICAL = Regex("(?:如果|假如|要是|假设|假設|假使)我(?:说|說|让|讓|叫|要)|\\bif\\s+i\\s+(?:said|asked|told|were\\s+to)\\b", RegexOption.IGNORE_CASE)
    private val ASKING_HOW = Regex("你会(?:怎么|怎样|如何)|你會(?:怎麼|怎樣|如何)|会(?:发生什么|怎么样)|會(?:發生什麼|怎麼樣)|\\b(?:what|how)\\s+would\\s+you\\b|\\bwhat\\s+would\\s+happen\\b", RegexOption.IGNORE_CASE)
    private fun onlyAsking(message: String) = HYPOTHETICAL.containsMatchIn(message) && ASKING_HOW.containsMatchIn(message)
}
