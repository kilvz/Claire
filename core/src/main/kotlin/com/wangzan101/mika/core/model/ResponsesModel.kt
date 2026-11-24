package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Bearer token sent with requests. A true [fresh] means the previous one was rejected (HTTP 401) and a new one must be
 * obtained first; if that fails a [ModelError] is thrown.
 * Null when there is no token (local services). When [refreshable] is false (a fixed API key), no retry happens after rejection.
 */
interface BearerToken {
    val refreshable: Boolean get() = false
    suspend fun get(fresh: Boolean): String?

    companion object {
        /** A fixed API key. */
        fun fixed(key: String?): BearerToken = object : BearerToken {
            override suspend fun get(fresh: Boolean): String? = key
        }
    }
}

/** OpenAI Responses' explicit speed tiers; when none is selected the server decides. */
enum class OpenAiServiceTier(val requestValue: String) {
    Fast("priority"),
    Ultrafast("ultrafast"),
}

/**
 * OpenAI Responses API (`POST {base}/responses`), always streaming and never storing the conversation on the server
 * (`store:false`): all context needed is sent in the `input` array each time, the system prompt goes into `instructions`, and
 * system messages in the conversation become developer messages (some servers do not accept system message items).
 * Tools are function tools (`strict:false`, Claire's parameter schemas are not written in strict mode); results go back to the
 * model as `function_call_output`.
 * Thinking effort and speed are sent as `reasoning.effort` and `service_tier` only when the user selects them. Temperature and
 * output limits are not sent (the plan endpoint does not accept them).
 *
 * Thinking of reasoning models: the encrypted thinking content is requested (`include: reasoning.encrypted_content`) and stored
 * verbatim in [Part.ToolCall.opaque] of this turn's first tool call; on replay it is placed in front of the tool call so the model
 * continues the previous step's line of thought. Turns that only give a text answer do not need to keep thinking and are not
 * stored.
 * When the server says it does not accept `include`, drop it and send again.
 *
 * Success counts only on `response.completed` (or `response.incomplete`); a stream that disconnects before that is treated as a
 * network failure (and retried).
 * When [chatGptPlan] is true the request uses the user's ChatGPT plan quota: HTTP 429 means the quota is used up and 403 means
 * the account cannot be used this way; neither is retried.
 * [accountLogin] is the name of the other provider whose account was used to sign in (e.g. xAI): HTTP 403 means the subscription
 * tier cannot use this API ([ModelError.AccountPlanNotAllowed]).
 */
class ResponsesModel(
    baseUrl: String,
    private val token: BearerToken,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val chatGptPlan: Boolean = false,
    /** Headers sent with every request (see [ClientHeaders]); the token header is added by the adapter itself. This API has no reply limit (the plan route does not accept one). */
    private val headers: Map<String, String> = emptyMap(),
    /** null uses the server default; when the server rejects the selected tier the error is reported as usual, without silently switching tiers and retrying. */
    private val reasoningEffort: String? = null,
    private val accountLogin: String? = null,
    private val serviceTier: OpenAiServiceTier? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trim().trimEnd('/') + "/responses"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var includeReasoning = true
        var fresh = false
        while (true) {
            val stream = ResponsesStream(onText, ::errorOf)
            val bearer = token.get(fresh)
            val (status, retryAfter, text) = HttpSupport.stream(http, request(bearer, system, messages, tools, includeReasoning), stream::accept)
            if (status in 200..299) {
                if (text.isNotEmpty()) return stream.fromBody(text).also { reply ->
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                }
                return stream.finish()
            }
            val err = errorBody(text)
            when {
                status == 401 && token.refreshable && !fresh -> fresh = true
                status == 400 && includeReasoning && (err?.param == "include" || text.contains("reasoning.encrypted_content")) ->
                    includeReasoning = false
                else -> throw httpError(status, retryAfter, text, err)
            }
        }
    }

    internal fun body(system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean = true): JsonObject =
        buildJsonObject {
            put("model", model)
            if (system.isNotEmpty()) put("instructions", system)
            put("input", JsonArray(messages.flatMap(::serialize)))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            put("store", false)
            put("stream", true)
            reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            serviceTier?.let { put("service_tier", it.requestValue) }
            if (includeReasoning) putJsonArray("include") { add(ENCRYPTED_REASONING) }
        }

    private suspend fun request(bearer: String?, system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean): Request =
        Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!bearer.isNullOrEmpty()) headerOrReject("Authorization", "Bearer $bearer") }
            .header("Accept", "text/event-stream")
            .postInference(InferenceProtocol.Responses, body(system, messages, tools, includeReasoning)) {
                if (chatGptPlan || endpoint.toHttpUrlOrNull()?.host == "api.openai.com") "openai" else "openai_compatible"
            }
            .build()

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", t.name)
        put("description", t.description)
        put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("The parameter schema for tool ${t.name} is not valid JSON", e)
        })
        put("strict", false)
    }

    /** One internal message expands into several `input` items: the assistant's text, thinking and tool calls; each tool result gets its own item; text and images are combined into one message. */
    private fun serialize(m: Message): List<JsonObject> {
        if (m.role == Role.Assistant) return assistantItems(m)
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject { put("type", "function_call_output"); put("call_id", r.callId); put("output", r.content) }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) out += buildJsonObject {
            put("type", "message")
            put("role", if (m.role == Role.System) "developer" else "user")
            putJsonArray("content") {
                rest.forEach { p ->
                    addJsonObject {
                        if (p is Part.Image && capabilities.vision) {
                            put("type", "input_image"); put("image_url", "data:${p.mime};base64,${p.pngBase64}")
                        } else {
                            put("type", "input_text"); put("text", if (p is Part.Text) p.text else IMAGE_OMITTED)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun assistantItems(m: Message): List<JsonObject> {
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val reasoning = calls.mapNotNull { it.opaque }.flatMap { o ->
            runCatching { json.parseToJsonElement(o) as? JsonArray }.getOrNull().orEmpty().filterIsInstance<JsonObject>()
        }
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        return buildList {
            addAll(reasoning)
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "message"); put("role", "assistant")
                putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
            })
            calls.forEach { c ->
                add(buildJsonObject { put("type", "function_call"); put("call_id", c.id); put("name", c.name); put("arguments", c.argsJson) })
            }
        }
    }

    internal data class ErrorBody(val code: String?, val message: String, val param: String?)

    private fun errorBody(text: String): ErrorBody? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val e = root["error"] as? JsonObject
        if (e != null) return ErrorBody(e.str("code") ?: e.str("type"), e.str("message").orEmpty(), e.str("param"))
        return root.str("detail")?.let { ErrorBody(null, it, null) }
    }

    /** `response.failed` / `error` in the stream, and `error.code` in the body on an HTTP error. Unrecognized codes are null. */
    private fun errorOf(code: String?, message: String, param: String?): ModelError? {
        if (serviceTier != null && param == "service_tier") {
            return ModelError.ServiceTierRejected(ModelAccessErrors.clip(message))
        }
        return when (code) {
            "subscription_sharing_usage_limit_exceeded" -> ModelError.ChatGptLimitReached()
            "subscription_sharing_user_not_eligible" -> ModelError.ChatGptNotEligible(message.take(200))
            // The server is temporarily unavailable: keep the login and try again later
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ModelError.Server(503)
            "subscription_sharing_unsupported_capability" -> ModelError.BadResponse("The server does not support ${param ?: "an item"} in the request")
            "subscription_sharing_route_not_supported" -> ModelError.BadResponse("The server does not support this endpoint")
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
                ModelError.Auth()
            "context_length_exceeded" -> ModelError.ContextTooLong()
            "insufficient_quota" -> ModelError.QuotaExceeded()
            "rate_limit_exceeded" -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(null)
            "model_not_found" -> ModelError.ModelNotFound(model, ModelAccessErrors.clip(message))
            "server_error" -> ModelError.Server(500)
            else -> null
        }
    }

    private fun httpError(status: Int, retryAfter: String?, body: String, err: ErrorBody?): ModelError =
        err?.let { errorOf(it.code, it.message, it.param) }
            // The account sign-in already verified the identity: a 403 with no recognizable error code means an insufficient plan tier, not a wrong key or a model that is not enabled
            ?: (if (status == 403 && accountLogin != null) ModelError.AccountPlanNotAllowed(accountLogin, err?.message.orEmpty().take(200)) else null)
            ?: ModelHttp.reasoningEffortRejected(status, body)
            ?: ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 403 -> if (chatGptPlan) ModelError.ChatGptNotEligible(err?.message.orEmpty().take(200)) else ModelError.Auth()
            status == 429 -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 402 -> ModelError.QuotaExceeded()
            ModelHttp.contextTooLong(status, body) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private companion object {
        const val ENCRYPTED_REASONING = "reasoning.encrypted_content"
        const val IMAGE_OMITTED = "[screenshot omitted: the current model cannot see images]"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Reads the Responses event stream: `response.output_text.delta` is text in pieces, `response.output_item.done` is one finished
 * item (message, tool call, thinking), `response.completed` / `response.incomplete` finish it, and `response.failed` / `error`
 * are failures.
 */
private class ResponsesStream(
    private val onText: (String) -> Unit,
    private val errorOf: (String?, String, String?) -> ModelError?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val items = mutableListOf<JsonObject>()
    private var final: JsonObject? = null
    private var incomplete = false

    fun accept(data: String): Boolean {
        if (data == "[DONE]") return false
        val root = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("The streaming response is not valid JSON")
        when (root.str("type")) {
            "response.output_text.delta" -> root.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                visible.append(it)
                onText(visible.toString())
            }
            "response.output_item.done" -> (root["item"] as? JsonObject)?.let { items += it }
            "response.completed" -> final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
            "response.incomplete" -> {
                final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
                incomplete = true
            }
            "response.failed" -> throw failure((root["response"] as? JsonObject)?.get("error") as? JsonObject)
            "error" -> throw failure(root["error"] as? JsonObject ?: root)
        }
        return final != null
    }

    private fun failure(e: JsonObject?): ModelError {
        val code = e?.str("code")
        val message = e?.str("message").orEmpty()
        return errorOf(code, message, e?.str("param")) ?: ModelError.BadResponse(listOfNotNull(code, message.take(200)).joinToString(" "))
    }

    /** The server did not reply as a stream and returned the whole response object directly. */
    fun fromBody(text: String): ModelReply {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("The response content is not valid JSON")
        (root["error"] as? JsonObject)?.let { throw failure(it) }
        final = root
        incomplete = root.str("status") == "incomplete"
        return finish()
    }

    fun finish(): ModelReply {
        // Not reaching response.completed / response.incomplete means the connection dropped mid-way
        val response = final ?: throw ModelError.StreamCut()
        val output = items.ifEmpty { (response["output"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty() }
        val reasoning = mutableListOf<JsonObject>()
        val thinking = StringBuilder()
        val text = StringBuilder()
        var refusal = false
        val calls = mutableListOf<Part.ToolCall>()
        output.forEach { item ->
            when (item.str("type")) {
                "reasoning" -> {
                    (item["summary"] as? JsonArray)?.forEach { s -> (s as? JsonObject)?.str("text")?.let(thinking::append) }
                    // For replay: drop the id and status that only mean something on the server, keep the encrypted thinking
                    if (item["encrypted_content"] != null) reasoning += JsonObject(item - "id" - "status")
                }
                "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    when (o.str("type")) {
                        "output_text" -> o.str("text")?.let(text::append)
                        "refusal" -> refusal = true
                    }
                }
                "function_call" -> {
                    val id = item.str("call_id") ?: throw ModelError.BadResponse("The tool call is missing an ID")
                    val name = item.str("name") ?: throw ModelError.BadResponse("The tool call is missing a name")
                    calls += Part.ToolCall(id, name, item.str("arguments")?.ifEmpty { null } ?: "{}")
                }
            }
        }
        if (text.isEmpty() && visible.isNotEmpty()) text.append(visible)
        val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
        if (refusal && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        if (incomplete && reason == "content_filter" && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        val withReasoning = if (reasoning.isNotEmpty() && calls.isNotEmpty()) {
            listOf(calls.first().copy(opaque = JsonArray(reasoning).toString())) + calls.drop(1)
        } else calls
        val parts = buildList {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (text.isNotEmpty()) add(Part.Text(text.toString()))
            addAll(withReasoning)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.ToolUse
            incomplete && reason == "max_output_tokens" -> StopReason.MaxTokens
            incomplete -> StopReason.Other
            else -> StopReason.EndTurn
        }
        return ModelReply(parts, stop, usageOf(response["usage"]))
    }

    private fun usageOf(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        val input = int(u, "input_tokens") ?: return null
        val cached = int(u["input_tokens_details"] as? JsonObject, "cached_tokens")
        val output = int(u, "output_tokens") ?: 0
        val reasoning = (int(u["output_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, output)
        return Usage(input, output, (cached ?: 0).coerceAtMost(input), reasoning, cacheReported = cached != null)
    }
}
