package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * The Anthropic Messages API.
 * - System messages in the history are merged into the system text (Anthropic has no system-role messages).
 * - When a thinking effort is chosen, adaptive thinking is enabled (`thinking.type = adaptive`) and the level goes into `output_config.effort`; choosing `none` turns it off.
 *   When the listing states which thinking types the model accepts ([thinkingTypes]), the unsupported one is not sent (nothing is sent, leaving the server default).
 *   The thinking block's `signature` is stored in [Part.Thinking.opaque] together with the address it was received from ([SignedThinking]),
 *   and turns that contain tool calls pass it back unchanged to the same address (otherwise the server rejects it), with the blocks in the order they were received.
 *   The signature is also bound to the system prompt, the tools and the preceding messages at the time it was produced; once these change (expanding a group of tools, compacting history, etc.), models from Claude 5.1 on may
 *   reject the passed-back thinking blocks ([STALE_THINKING]); in that case, following the documented recovery, the thinking blocks are dropped and the request is sent once more (on these models Claire's thinking is only ever adaptive thinking, and the docs say
 *   adaptive thinking does not require assistant turns to start with a thinking block; here everything carried this time is dropped, see [dropStaleThinking]), and later requests no longer carry them
 *   ([staleThinking]).
 * - Adjacent messages with the same role are merged (Anthropic requires user/assistant alternation).
 */
class AnthropicModel(
    baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** Reply limit (`max_tokens`, required); null uses the default: [THINKING_MAX_TOKENS] with thinking on, otherwise [DEFAULT_MAX_TOKENS]. */
    maxTokens: Int? = null,
    /** Headers sent with every request (see [ClientHeaders]); the key header is added by the adapter itself. */
    private val headers: Map<String, String> = emptyMap(),
    /** Thinking effort chosen by the user; null sends nothing, `none` turns thinking off, anything else is sent as `output_config.effort`. */
    private val reasoningEffort: String? = null,
    /**
     * The thinking types the listing says this model accepts (see [ListedModel.thinkingTypes]); when it does not include `adaptive` the effort is not sent, and when it does not include `disabled` nothing is sent for `none`.
     * null means the listing does not say (the effort comes from another kind of listing), so it is sent as given.
     */
    private val thinkingTypes: List<String>? = null,
    /** Supplies the key at request time instead of [apiKey] (subscription sign-in tokens can change between requests). */
    private val keySupplier: (suspend () -> String?)? = null,
    /** Called back with the HTTP status when a key is rejected upstream (429/401/403); the wiring decides by status how to react. */
    private val onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"
    /** The `thinking.type` actually sent: `adaptive`, `disabled`, or null (not sent). */
    private val thinkingType: String? = when (reasoningEffort) {
        null -> null
        "none" -> "disabled"
        else -> "adaptive"
    }?.takeIf { thinkingTypes == null || it in thinkingTypes }
    private val maxTokens = maxTokens ?: if (thinkingType == "adaptive") THINKING_MAX_TOKENS else DEFAULT_MAX_TOKENS
    private val json = Json { ignoreUnknownKeys = true }
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }
    /**
     * The thinking blocks ([Part.Thinking.opaque]) carried by the request that was rejected; they stay in the history, but later requests do not carry them.
     * One model is created per task and shared with its subtasks, so several coroutines may use it at the same time.
     */
    private val staleThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private suspend fun resolveKey(): String = keySupplier?.invoke()?.takeIf { it.isNotBlank() } ?: apiKey

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
                HttpSupport.execute(http, request(system, messages, tools, stream = false, key = key.orEmpty()))
            }
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = AnthropicStream(endpoint, onText, ::parseUsage)
        // The rejected attempt produces no events (HttpSupport.stream does not call accept for a non-2xx), so the resend keeps using this stream
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
                HttpSupport.stream(http, request(system, messages, tools, stream = true, key = key.orEmpty()), stream::accept)
            }
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    /**
     * Send once; when the server says the prefix of the passed-back thinking blocks does not match ([STALE_THINKING]), record all thinking blocks carried this time in [staleThinking] and send once more (only once).
     * The documented approach drops the block named in the error and the ones after it; here everything is dropped, so no position has to be found via `messages.N`, at the cost of no longer carrying earlier valid thinking either.
     * When no thinking blocks were carried this time, nothing is resent and the error is reported as usual.
     */
    private suspend fun dropStaleThinking(messages: List<Message>, send: suspend () -> RawResponse): RawResponse {
        val sent = messages.filter { it.role == Role.Assistant }.flatMap { it.parts }.filterIsInstance<Part.Thinking>()
            .filter(::replays).mapNotNull { it.opaque }
        val first = send()
        if (first.status != 400 || STALE_THINKING !in first.body || !staleThinking.addAll(sent)) return first
        return send()
    }

    /** Whether this block is passed back: not when thinking is turned off; not when it was received from another address (a signature is only honored by the service that issued it); not when the rejected request carried it ([staleThinking]). */
    private fun replays(p: Part.Thinking): Boolean {
        val opaque = p.opaque ?: return false
        return thinkingType != "disabled" && opaque !in staleThinking && SignedThinking.block(p, endpoint) != null
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, key: String): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)
            // Anthropic rejects empty text blocks, so the whole field is omitted when system is empty
            if (systemText.isNotBlank()) put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text"); put("text", systemText); put("cache_control", ephemeral)
                })
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEachIndexed { i, t -> add(toolJson(t, last = i == tools.lastIndex)) }
            })
            put("messages", buildMessages(messages))
            when (thinkingType) {
                "disabled" -> putJsonObject("thinking") { put("type", "disabled") }
                "adaptive" -> {
                    putJsonObject("thinking") {
                        put("type", "adaptive")
                        put("display", "summarized")
                    }
                    putJsonObject("output_config") { put("effort", reasoningEffort) }
                }
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .headerOrReject("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .postInference(InferenceProtocol.Anthropic, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 || status == 403 -> ModelError.Auth()
        status == 402 || body.contains("credit balance is too low") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 413 || body.contains("prompt is too long") || ModelHttp.contextTooLong(status, body) -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
        status == 529 || body.contains("overloaded_error") -> ModelError.Server(529)
        status in 500..599 -> ModelError.Server(status)
        else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec, last: Boolean): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        put("input_schema", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("The parameter schema for tool ${t.name} is not valid JSON", e)
        })
        if (last) put("cache_control", ephemeral)
    }

    private fun buildMessages(messages: List<Message>): JsonArray {
        // (role, blocks): merge adjacent same-role entries first, then emit
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        // Cache through the last block of the last user message; if the next step starts the same, it comes from cache. Assistant messages may end with a thinking block, so they are not marked.
        // Trailing uncached messages that change every time do not count: the mark goes before them, and their blocks still merge in after the mark.
        val stable = messages.size - messages.takeLastWhile { it.uncached }.size
        var mark: Pair<Int, Int>? = null
        messages.forEachIndexed { index, m ->
            if (m.role == Role.System) return@forEachIndexed
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val blocks = if (role == "assistant") assistantBlocks(m) else userBlocks(m)
            if (blocks.isEmpty()) return@forEachIndexed
            if (merged.lastOrNull()?.first == role) merged.last().second += blocks else merged += role to blocks.toMutableList()
            if (role == "user" && index < stable) mark = merged.lastIndex to merged.last().second.lastIndex
        }
        return buildJsonArray {
            merged.forEachIndexed { i, (role, blocks) ->
                val content = mark?.takeIf { it.first == i }?.let { (_, at) ->
                    blocks.mapIndexed { j, b -> if (j == at) JsonObject(b + ("cache_control" to ephemeral)) else b }
                } ?: blocks
                add(buildJsonObject { put("role", role); put("content", JsonArray(content)) })
            }
        }
    }

    // tool_result must come before the other blocks of the same user message
    private fun userBlocks(m: Message): List<JsonObject> {
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", r.callId)
                put("content", r.content); put("is_error", r.isError)
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64"); put("media_type", p.mime); put("data", p.pngBase64)
                    }
                }
                p is Part.Image -> buildJsonObject {
                    put("type", "text"); put("text", "[screenshot omitted: the current model cannot see images]")
                }
                else -> null
            }
        }
        return results + rest
    }

    // With thinking on, a turn containing tool calls must pass back the previous assistant message's thinking blocks together with their signatures, unchanged, in the order they were received:
    // the progress note newer models write before a tool call is a separate thinking block placed after the text; moving it in front makes the server treat the preceding content as modified.
    // When thinking is explicitly off they are not carried, to avoid conflicting with the current request's thinking configuration; neither are ones received from another address or carried by the rejected request ([replays]).
    private fun assistantBlocks(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
            is Part.Thinking -> if (replays(p)) SignedThinking.block(p, endpoint) else null
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("type", "text"); put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                put("type", "tool_use"); put("id", p.id); put("name", p.name)
                put("input", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
            }
            else -> null
        }
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["content"]?.jsonArray ?: throw ModelError.BadResponse("Missing content")
            val parts = mutableListOf<Part>()
            content.forEach { el ->
                val b = el.jsonObject
                when (b["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                    "thinking" -> {
                        val text = (b["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val signature = (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        if (text.isNotEmpty() || signature != null) parts += Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) })
                    }
                    "redacted_thinking" -> (b["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { parts += Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                    "tool_use" -> parts += Part.ToolCall(
                        b["id"]!!.jsonPrimitive.content,
                        b["name"]!!.jsonPrimitive.content,
                        (b["input"] ?: JsonObject(emptyMap())).toString(),
                    )
                }
            }
            val stop = when ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull) {
                "end_turn", "stop_sequence" -> StopReason.EndTurn
                "tool_use" -> StopReason.ToolUse
                "max_tokens" -> StopReason.MaxTokens
                else -> StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usage"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("input_tokens") ?: return null
        val cached = n("cache_read_input_tokens")
        // Anthropic's input_tokens excludes the cached part; normalize to total input. output_tokens includes thinking but is not reported separately, so reasoningTokens is recorded as 0
        return Usage(inT + (cached ?: 0) + (n("cache_creation_input_tokens") ?: 0), n("output_tokens") ?: 0, cached ?: 0, cacheReported = cached != null)
    }
}

private class AnthropicStream(
    private val endpoint: String,
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Block(
        val type: String, val id: String = "", val name: String = "",
        val text: StringBuilder = StringBuilder(), val input: StringBuilder = StringBuilder(), val initialInput: String = "{}",
        val signature: StringBuilder = StringBuilder(), val data: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, Block>()
    private val visible = StringBuilder()
    private var usage: JsonObject? = null
    private var reason: String? = null
    private var stopped = false

    fun accept(data: String): Boolean {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("The streaming response is not valid JSON", e) }
        when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "message_start" -> usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
            "content_block_start" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return false
                val b = root["content_block"] as? JsonObject ?: return false
                val type = (b["type"] as? JsonPrimitive)?.contentOrNull ?: return false
                blocks[index] = Block(type,
                    id = (b["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = (b["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    initialInput = b["input"]?.toString() ?: "{}",
                    data = (b["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                ).also { block ->
                    (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let(block.signature::append)
                    (b["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                }
            }
            "content_block_delta" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return false
                val block = blocks[index] ?: return false
                val delta = root["delta"] as? JsonObject ?: return false
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { block.text.append(it) }
                    "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature.append(it) }
                    "input_json_delta" -> (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let { block.input.append(it) }
                }
            }
            "message_delta" -> {
                reason = ((root["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull ?: reason
                // Usage is cumulative: fields carrying an integer (output, and possibly input or cache) override those from message_start, while absent or null ones are kept; parsed again at the end
                (root["usage"] as? JsonObject)?.let { delta ->
                    usage = JsonObject(usage.orEmpty() + delta.filterValues { (it as? JsonPrimitive)?.intOrNull != null })
                }
            }
            "message_stop" -> stopped = true
            "error" -> throw ModelHttp.streamError(root) ?: ModelError.BadResponse(root["error"].toString().take(200))
        }
        return stopped
    }

    fun finish(): ModelReply {
        // When message_delta brings stop_reason all content blocks are already complete, and missing only message_stop still counts as complete; having neither means the connection was cut midway
        if (!stopped && reason == null) throw ModelError.StreamCut()
        val parts = blocks.values.mapNotNull { block ->
            when (block.type) {
                "text" -> block.text.toString().takeIf(String::isNotEmpty)?.let(Part::Text)
                "thinking" -> {
                    val text = block.text.toString()
                    val signature = block.signature.toString().takeIf(String::isNotEmpty)
                    if (text.isNotEmpty() || signature != null) Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) }) else null
                }
                "redacted_thinking" -> block.data.takeIf(String::isNotEmpty)?.let { Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                "tool_use" -> {
                    if (block.id.isEmpty() || block.name.isEmpty()) throw ModelError.BadResponse("The tool-call stream is missing a name or ID")
                    Part.ToolCall(block.id, block.name, block.input.toString().ifEmpty { block.initialInput })
                }
                else -> null
            }
        }
        val stop = when (reason) {
            "end_turn", "stop_sequence" -> StopReason.EndTurn
            "tool_use" -> StopReason.ToolUse
            "max_tokens" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usageOf(usage))
    }
}

/** Default reply limit; when thinking is on, thinking also draws on this budget, and 4K is often used up before an answer appears, hence [THINKING_MAX_TOKENS]. */
private const val DEFAULT_MAX_TOKENS = 4096
private const val THINKING_MAX_TOKENS = 16_000

/**
 * One phrase from the error reported when the prefix of passed-back thinking blocks does not match (verbatim from the Preserved thinking section of Anthropic's docs).
 * An error with only the first half, "Invalid `signature` in `thinking` block", and without this phrase means the signature itself is broken: no resend, the error is reported as usual.
 */
private const val STALE_THINKING = "bound to a different conversation"

/**
 * An Anthropic thinking block stored in [Part.Thinking.opaque]: a signed thinking block is `{"endpoint":...,"signature":...}`,
 * and redacted_thinking is `{"endpoint":...,"redacted":...}`. A signature is only honored by the service that issued it, so on replay only blocks received from the same address are honored;
 * blocks from another address (a different Anthropic-compatible service) or stored by another adapter are never passed back.
 */
internal object SignedThinking {
    fun signed(endpoint: String, signature: String): String =
        buildJsonObject { put("endpoint", endpoint); put("signature", signature) }.toString()

    fun redacted(endpoint: String, data: String): String =
        buildJsonObject { put("endpoint", endpoint); put("redacted", data) }.toString()

    /** When [p] is a thinking block received from [endpoint], the block to use for replay; null when it came from elsewhere or cannot be recognized. */
    fun block(p: Part.Thinking, endpoint: String): JsonObject? {
        val o = p.opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
        (o["redacted"] as? JsonPrimitive)?.contentOrNull?.let { data ->
            return buildJsonObject { put("type", "redacted_thinking"); put("data", data) }
        }
        val signature = (o["signature"] as? JsonPrimitive)?.contentOrNull ?: return null
        return buildJsonObject { put("type", "thinking"); put("thinking", p.text); put("signature", signature) }
    }
}
