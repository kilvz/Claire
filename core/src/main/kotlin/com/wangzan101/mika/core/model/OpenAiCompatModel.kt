package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * An OpenAI Chat Completions compatible endpoint (DeepSeek/Qwen/Kimi/GLM/OpenRouter/local services, etc.). Differences are
 * expressed only through [capabilities].
 *
 * Providers put thinking content in different places: `reasoning_content`, `reasoning`, or `content` as a chunk array with the
 * thinking inside `thinking` chunks.
 * Received thinking records which endpoint and position it came from ([ReasoningVia], stored in [Part.Thinking.opaque]); requests
 * that carry tools put it back exactly where it was: some providers require the thinking around tool calls to be sent back, and
 * reject the request otherwise. Only thinking received from this endpoint is replayed; thinking from other providers is not sent
 * (providers that do not know this field never receive it). [reasoningField] is the thinking field declared in the model list
 * (`reasoning_content` or `reasoning`): when it is declared, thinking of unknown origin (history saved by older versions) is
 * replayed using it as well.
 * When a thinking effort is selected, `reasoning_effort` is sent separately; providers that do not declare levels in the list
 * never receive a value here.
 */
class OpenAiCompatModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    reasoningField: String? = null,
    /** Headers sent with every request (client identification, session ids the provider requires, etc., see [ClientHeaders]); the key header is added by the adapter itself. */
    private val headers: Map<String, String> = emptyMap(),
    /** The thinking effort selected by the user (`reasoning_effort`); null means it is not sent. */
    private val reasoningEffort: String? = null,
    private val keySupplier: (() -> String?)? = null,
    /** Called back with the HTTP status when the key is rejected upstream (429/401/403); the wiring decides by status whether to cool down (429) or mark it exhausted (401/403). */
    private val onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val declaredVia = ReasoningVia.ofField(reasoningField)
    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveKey(): String? = keySupplier?.invoke()?.takeIf { it.isNotBlank() } ?: apiKey

    private companion object {
        val MARKABLE_ROLES = setOf("system", "user", "tool")
        val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        /** The last item of an SSE stream: the reply counts as complete only once it arrives. */
        const val STREAM_DONE = "[DONE]"
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val response = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            HttpSupport.execute(http, request(system, messages, tools, stream = false, key = key))
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        // Some compatible endpoints do not honor OpenAI's defaults: they still answer with SSE even when streaming was not
        // requested (common for self-hosted services on a local network). Read it as a stream and concatenate; do not treat
        // `data:` as broken JSON.
        if (!looksLikeEvents(text)) return parse(text)
        val stream = OpenAiStream({}, ::parseUsage, ::thinking)
        response.replay(eventsOf(text), stream::accept)
        return stream.finish()
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = OpenAiStream(onText, ::parseUsage, ::thinking)
        val response = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            stream = OpenAiStream(onText, ::parseUsage, ::thinking)
            var raw = HttpSupport.stream(http, request(system, messages, tools, stream = true, key = key), stream::accept)
            if (raw.status == 400 && raw.body.contains("stream_options")) {
                // Some compatible endpoints can stream but have no usage option.
                stream = OpenAiStream(onText, ::parseUsage, ::thinking)
                raw = HttpSupport.stream(http, request(system, messages, tools, stream = true, includeUsage = false, key = key), stream::accept)
            }
            raw
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) {
            // A compatible endpoint without streaming support can still complete the task through the original endpoint.
            if (status == 400 && text.contains("stream", ignoreCase = true)) return complete(system, messages, tools).also {
                it.parts.filterIsInstance<Part.Text>().joinToString("") { part -> part.text }.takeIf(String::isNotEmpty)?.let(onText)
            }
            throw mapError(status, retryAfter, text)
        }
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, includeUsage: Boolean = true, key: String? = resolveKey()): Request {
        val payload = buildJsonObject {
            put("model", model)
            reasoningEffort?.let { put("reasoning_effort", it) }
            // Thinking is replayed only in requests that carry tools (it must follow along step by step across tool calls); side calls without tools do not need it
            val replayReasoning = tools.isNotEmpty()
            val wire = listOf(buildJsonObject { put("role", "system"); put("content", system) }) +
                messages.flatMap { serialize(it, replayReasoning) }
            put("messages", JsonArray(if (capabilities.promptCaching == PromptCaching.Explicit) withCacheMarks(wire) else wire))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            } else {
                // Explicitly say no streaming: without this field some compatible endpoints answer with SSE by default
                put("stream", false)
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply { if (!key.isNullOrEmpty()) headerOrReject("Authorization", "Bearer $key") }
            .postInference(InferenceProtocol.Chat, payload)
            .build()
    }

    /**
     * Explicit caching: marked on the system prompt (identical every time), then on the last message that can be marked (caching
     * the whole conversation up to this point; the next request whose beginning matches takes it from the cache). The server
     * searches backwards from the mark for an identical beginning, so earlier messages must not be rewritten.
     * String content is turned into a single text block before marking; assistant messages (which may hold only tool calls) are
     * not marked.
     */
    private fun withCacheMarks(wire: List<JsonObject>): List<JsonObject> {
        val last = wire.indexOfLast { (it["role"] as? JsonPrimitive)?.contentOrNull in MARKABLE_ROLES && it["content"] !is JsonNull }
        return wire.mapIndexed { i, m -> if (i == 0 || i == last) marked(m) else m }
    }

    private fun marked(m: JsonObject): JsonObject {
        val blocks = when (val c = m["content"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.content) })
            is JsonArray -> c.map { it.jsonObject }
            else -> return m
        }
        if (blocks.isEmpty()) return m
        val withMark = blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to EPHEMERAL))
        return JsonObject(m + ("content" to JsonArray(withMark)))
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.openAiError(status, retryAfter, body, model)

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
                throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
            })
        }
    }

    /** One internal message may expand into several wire messages (each tool result gets its own; images can only go in user messages). */
    private fun serialize(m: Message, replayReasoning: Boolean): List<JsonObject> {
        if (m.role == Role.Assistant) return listOf(assistantJson(m, replayReasoning))
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content)
            }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) {
            val role = if (m.role == Role.System) "system" else "user"
            val hasImage = rest.any { it is Part.Image } && capabilities.vision
            out += buildJsonObject {
                put("role", role)
                if (!hasImage) {
                    put("content", rest.joinToString("\n") { textOf(it) })
                } else {
                    putJsonArray("content") {
                        rest.forEach { p ->
                            add(buildJsonObject {
                                if (p is Part.Image) {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${p.mime};base64,${p.pngBase64}") }
                                } else {
                                    put("type", "text"); put("text", textOf(p))
                                }
                            })
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textOf(p: Part): String = when (p) {
        is Part.Text -> p.text
        is Part.Image -> "[screenshot omitted: the current model cannot see images]"
        else -> ""
    }

    /** A piece of received thinking: records this endpoint and the position it was received at, so replay recognizes it. */
    private fun thinking(text: String, via: ReasoningVia) = Part.Thinking(text, via.tag(endpoint))

    /** The thinking of this assistant message that can be replayed to this endpoint, and where to put it: what was received from here goes back to its received position; unknown origin uses the field declared in the list; null if neither. */
    private fun replayable(m: Message): Pair<String, ReasoningVia>? {
        val parts = m.parts.filterIsInstance<Part.Thinking>()
        val own = parts.mapNotNull { p -> ReasoningVia.of(p.opaque, endpoint)?.let { p.text to it } }
        if (own.isNotEmpty()) return own.joinToString("") { it.first } to own.first().second
        val declared = declaredVia ?: return null
        val untagged = parts.filter { it.opaque == null }.joinToString("") { it.text }
        return if (untagged.isEmpty()) null else untagged to declared
    }

    private fun assistantJson(m: Message, replayReasoning: Boolean): JsonObject {
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val replay = if (replayReasoning) replayable(m) else null
        return buildJsonObject {
            put("role", "assistant")
            when {
                // The chunk-based provider wants the whole message replayed verbatim: thinking block first, text block after
                replay?.second == ReasoningVia.ThinkingChunks -> putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        putJsonArray("thinking") { addJsonObject { put("type", "text"); put("text", replay.first) } }
                    }
                    if (text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", text) }
                }
                text.isEmpty() && calls.isNotEmpty() -> put("content", JsonNull)
                else -> put("content", text)
            }
            replay?.second?.field?.let { put(it, replay.first) }
            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        putJsonObject("function") { put("name", c.name); put("arguments", c.argsJson) }
                    })
                }
            }
        }
    }

    /** Whether the whole reply is SSE (`data: …` items) or a single JSON document. */
    private fun looksLikeEvents(body: String): Boolean {
        val head = body.trimStart()
        return head.startsWith("data:") || head.startsWith("event:")
    }

    /** Splits a whole SSE reply into individual events; the content has already been fully received, so a missing end marker is appended and it counts as a normal finish. */
    private fun eventsOf(body: String): List<String> {
        val out = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun dispatch() {
            if (data.isNotEmpty()) {
                out += data.joinToString("\n")
                data.clear()
            }
        }
        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        dispatch()
        if (out.lastOrNull() != STREAM_DONE) out += STREAM_DONE
        return out
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 choices")
            val msg = choice["message"]?.jsonObject ?: throw ModelError.BadResponse("缺少 message")
            val parts = mutableListOf<Part>()
            ReasoningVia.fields.firstNotNullOfOrNull { via ->
                (msg[via.field!!] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { it to via }
            }?.let { (t, via) -> parts += thinking(t, via) }
            when (val content = msg["content"]) {
                is JsonArray -> {
                    val chunks = ContentChunks.of(content)
                    if (chunks.thinking.isNotEmpty() && parts.isEmpty()) parts += thinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                    if (chunks.text.isNotEmpty()) parts += Part.Text(chunks.text)
                }
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                else -> {}
            }
            (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                val c = el.jsonObject
                val fn = c["function"]!!.jsonObject
                parts += Part.ToolCall(
                    c["id"]!!.jsonPrimitive.content,
                    fn["name"]!!.jsonPrimitive.content,
                    // Some adapters send an empty string for tools without arguments: treat it as `{}`, otherwise the caller's JSON parse fails
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
            val stop = when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                "stop" -> StopReason.EndTurn
                "tool_calls", "function_call" -> StopReason.ToolUse
                "length" -> StopReason.MaxTokens
                else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.Other
            }
            // Some models dump tool call markup straight into the text: restore it into calls first, strip it off if that
            // fails, and do not hand garbage to the UI (see LeakedToolMarkup)
            return LeakedToolMarkup.repair(ModelReply(parts, stop, parseUsage(root["usage"])))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        // Compatible APIs have two possible cache-hit field names; take whichever is present
        val hit = int(u, "prompt_cache_hit_tokens")
        val miss = int(u, "prompt_cache_miss_tokens")
        val inT = int(u, "prompt_tokens") ?: if (hit != null && miss != null) hit + miss else return null
        val outT = int(u, "completion_tokens") ?: 0
        val detail = int(u["prompt_tokens_details"] as? JsonObject, "cached_tokens")
        val cached = maxOf(detail ?: 0, hit ?: 0).coerceAtMost(inT)
        // completion_tokens includes thinking; some providers report it separately in completion_tokens_details.reasoning_tokens
        val reasoning = (int(u["completion_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, outT)
        return Usage(inT, outT, cached, reasoning, cacheReported = detail != null || hit != null)
    }
}

/** A Chat Completions delta can split one tool's name and arguments across several frames. */
private class OpenAiStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
    private val thinkingOf: (String, ReasoningVia) -> Part.Thinking,
) {
    private data class Call(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder())

    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private var thinkingVia: ReasoningVia? = null
    private val calls = sortedMapOf<Int, Call>()
    private var usage: Usage? = null
    private var reason: String? = null
    private var done = false
    private var chunks = 0

    fun accept(data: String): Boolean {
        if (data == "[DONE]") { done = true; return true }
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        // Some services return 200 when a streaming request fails right at the start; route those into retry and fallback by error type as well
        ModelHttp.streamError(root)?.let { throw it }
        chunks++
        usageOf(root["usage"])?.let { usage = it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return false
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { reason = it }
        val delta = choice["delta"] as? JsonObject ?: return false
        ReasoningVia.fields.forEach { via ->
            (delta[via.field!!] as? JsonPrimitive)?.contentOrNull?.let { appendThinking(it, via) }
        }
        when (val content = delta["content"]) {
            is JsonArray -> {
                val chunks = ContentChunks.of(content)
                if (chunks.thinking.isNotEmpty()) appendThinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                appendVisible(chunks.text)
            }
            is JsonPrimitive -> content.contentOrNull?.let(::appendVisible)
            else -> {}
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val call = calls.getOrPut(index, ::Call)
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id.append(it) }
            val fn = obj["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.args.append(it) }
        }
        return done
    }

    /** Within one reply, thinking is recorded only at the position it is first received from (a provider does not switch positions; when both fields are present only one copy is kept). */
    private fun appendThinking(t: String, via: ReasoningVia) {
        if (t.isEmpty()) return
        if (thinkingVia == null) thinkingVia = via
        if (via == thinkingVia) thinking.append(t)
    }

    private fun appendVisible(t: String) {
        if (t.isEmpty()) return
        visible.append(t)
        // Strip the preview too: when a model dumps tool call markup into the text, do not let the user watch garbage stream past
        onText(LeakedToolMarkup.stripMarkup(visible.toString()))
    }

    fun finish(): ModelReply {
        // Receiving finish_reason counts as a complete reply: some compatible endpoints do not send [DONE]. Neither means the connection dropped mid-way
        if (!done && reason == null) throw ModelError.StreamCut()
        if (chunks == 0) throw ModelError.BadResponse("流式响应没有内容")
        val parts = buildList<Part> {
            thinkingVia?.takeIf { thinking.isNotEmpty() }?.let { add(thinkingOf(thinking.toString(), it)) }
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            calls.values.forEach { c ->
                if (c.name.isEmpty() || c.id.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                add(Part.ToolCall(c.id.toString(), c.name.toString(), c.args.toString().ifEmpty { "{}" }))
            }
        }
        val stop = when (reason) {
            "stop" -> StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Other
        }
        return LeakedToolMarkup.repair(ModelReply(parts, stop, usage))
    }
}

/**
 * Where a compatible API puts thinking: [field] is the field name on the assistant message; [ThinkingChunks] means `content` is
 * a chunk array with thinking inside `thinking` blocks.
 * What is stored in [Part.Thinking.opaque] is `{"endpoint":…,"via":…}`.
 */
internal enum class ReasoningVia(val wire: String, val field: String?) {
    ReasoningContent("reasoning_content", "reasoning_content"),
    Reasoning("reasoning", "reasoning"),
    ThinkingChunks("thinking_chunks", null);

    fun tag(endpoint: String): String = buildJsonObject { put("endpoint", endpoint); put("via", wire) }.toString()

    companion object {
        /** The ones carried on message fields, recognized in this order (`reasoning_content` wins when both are present). */
        val fields = listOf(ReasoningContent, Reasoning)

        fun ofField(name: String?): ReasoningVia? = fields.firstOrNull { it.field == name }

        /** Gives its position when [opaque] is thinking received from [endpoint]; null for thinking from elsewhere or unrecognizable. */
        fun of(opaque: String?, endpoint: String): ReasoningVia? {
            val o = opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
            val via = (o["via"] as? JsonPrimitive)?.contentOrNull
            return entries.firstOrNull { it.wire == via }
        }
    }
}

/** When `content` is a chunk array: `text` blocks form the answer, and `thinking` blocks (which contain `text` blocks) form the thinking. Other blocks are not recognized. */
private class ContentChunks(val text: String, val thinking: String) {
    companion object {
        fun of(content: JsonArray): ContentChunks {
            val text = StringBuilder()
            val thinking = StringBuilder()
            content.forEach { el ->
                val c = el as? JsonObject ?: return@forEach
                when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
                    "thinking" -> when (val t = c["thinking"]) {
                        is JsonArray -> t.forEach { inner ->
                            ((inner as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(thinking::append)
                        }
                        is JsonPrimitive -> t.contentOrNull?.let(thinking::append)
                        else -> {}
                    }
                }
            }
            return ContentChunks(text.toString(), thinking.toString())
        }
    }
}
