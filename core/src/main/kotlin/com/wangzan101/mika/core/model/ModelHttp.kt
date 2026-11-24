package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Used by places outside the chat adapters that must report errors the same way (e.g. speech-to-text): send a request and convert the HTTP status and error content into a [ModelError].
 * The mapping was moved over from the adapters unchanged; the adapters call this instead so there are not two copies drifting apart.
 */
object ModelHttp {
    class Reply(val status: Int, val retryAfter: String?, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** Send the request and read the whole reply; a network failure throws [ModelError.Network], cancelling the coroutine cancels the request. */
    suspend fun send(http: OkHttpClient, request: Request): Reply =
        HttpSupport.execute(http, request).let { Reply(it.status, it.retryAfter, it.body) }

    /**
     * Error mapping for OpenAI-compatible APIs (chat and transcription share the same one). [model] is the requested model name.
     * HTTP errors (>= 400) without a more specific meaning all become [ModelError.Server] (MK-1 batch 2: classification goes by status code, not by whether the body parses,
     * so the failover chain survives); only content from HTTP 200 that cannot be parsed is [ModelError.BadResponse].
     */
    fun openAiError(status: Int, retryAfter: String?, body: String, model: String): ModelError =
        ModelAccessErrors.of(status, body, model) ?: reasoningEffortRejected(status, body) ?: when {
            status == 401 -> ModelError.Auth()
            status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
            status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, body))
            contextTooLong(status, body) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            status == 403 -> ModelError.Auth()
            else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    /** Error frames inside an HTTP 200 SSE stream are also classified like HTTP errors and handed to retry and failover. */
    fun streamError(root: JsonObject): ModelError? {
        val error = root["error"] ?: return null
        val errorObject = error as? JsonObject
        val type = (errorObject?.get("type") as? JsonPrimitive)?.contentOrNull
            ?: (root["type"] as? JsonPrimitive)?.contentOrNull
        val detail = (errorObject?.get("message") as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "rate_limit_error" -> ModelError.RateLimited(null)
            "overloaded_error" -> ModelError.Server(529, detail)
            else -> if (saysContextTooLong(error.toString())) ModelError.ContextTooLong()
            else ModelError.BadResponse(error.toString().take(200))
        }
    }

    /** An HTTP 400 error saying the request exceeds the model's context (see [saysContextTooLong]). Shared by the OpenAI-compatible, Responses and Anthropic APIs; a locally run service may expose all three. */
    fun contextTooLong(status: Int, body: String): Boolean = status == 400 && saysContextTooLong(body)

    /** HTTP 400 explicitly stating the reasoning effort parameter is invalid. Only the parameter name or a complete protocol field is accepted, no guessing from arbitrary "unsupported" text. */
    fun reasoningEffortRejected(status: Int, body: String): ModelError.ReasoningEffortRejected? {
        if (status != 400) return null
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = root?.get("error") as? JsonObject
        val param = (error?.get("param") as? JsonPrimitive)?.contentOrNull ?: (root?.get("param") as? JsonPrimitive)?.contentOrNull
        val lower = body.lowercase()
        val namesParameter = param in setOf("reasoning.effort", "reasoning_effort") ||
            (param == null && ("reasoning.effort" in lower || "reasoning_effort" in lower))
        val rejected = listOf("invalid", "unsupported", "not supported", "unknown").any { it in lower }
        return if (namesParameter && rejected) ModelError.ReasoningEffortRejected(ModelAccessErrors.detail(body)) else null
    }

    /**
     * The error content says the request exceeds the model's context. Providers word it differently (`context_length_exceeded`, "maximum context length",
     * "exceeds the available context size", "is longer than the model's context length", "exceeds the context window",
     * "is greater than the context length", "is longer than the maximum model length" when images are attached…):
     * matches "exceed / longer than / larger than / greater than / max" followed shortly after (in the same sentence) by context length, size, limit, window or model length, case-insensitive;
     * the previously recognized `context_length` and "maximum context" are unchanged; also recognizes an older locally run service's "loaded with context length of only".
     * A mention of "context window" alone does not count: the error may echo wording from the request.
     */
    internal fun saysContextTooLong(text: String): Boolean = CONTEXT_TOO_LONG.containsMatchIn(text)

    private val CONTEXT_TOO_LONG = Regex(
        """(?:exceed|longer than|larger than|greater than|max)[^.\n]{0,40}?(?:context[ _](?:length|size|limit|window)|model[ _]length)|context_length|maximum context|context length of only""",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Error mapping for the native Gemini API. */
    fun geminiError(status: Int, retryAfter: String?, body: String, model: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // A 429 for zero quota on this model was already recognized as unusable above; other 429s are treated as retryable rate limits (their rate-limit and quota wording is mixed, so they cannot be told apart reliably)
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter, msg))
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> if (status >= 400) ModelError.Server(status, ModelAccessErrors.detail(body)) else ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }
}
