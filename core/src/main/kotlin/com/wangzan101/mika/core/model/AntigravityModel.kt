package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Cloud Code Assist (the API behind Antigravity) `generateContent`, Gemini-shaped but
 * wrapped one level deeper: the request is `{model, project, request: {contents, systemInstruction,
 * generationConfig, tools}}` and every reply (including each streamed event) is
 * `{"response": {candidates: [...], usageMetadata: {...}}}`.
 *
 * - Roles are only user / model; adjacent same-role turns merge. System messages go to
 *   `systemInstruction`. Tool calls come back as `functionCall` parts and are sent back as
 *   `functionResponse` parts, like [GeminiModel].
 * - Reasoning effort is a suffix on the wire model id: base + "-" + effort, with "max" sent as
 *   "xhigh" ("low"/"medium"/"high" pass through unchanged).
 * - The GCP project id comes from [project] (loadCodeAssist, cached by the caller); it is only
 *   resolved lazily, on the first call that needs it.
 * - HTTP errors are mapped with [ModelHttp.geminiError] (the same Google JSON error shape);
 *   the server's message is kept in the error, verbatim.
 */
class AntigravityModel(
    baseUrl: String = DEFAULT_BASE_URL,
    private val token: BearerToken,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** Headers sent with every request (see [ClientHeaders]); the auth header is added here. */
    private val headers: Map<String, String> = emptyMap(),
    /** Reply cap (`generationConfig.maxOutputTokens`); null leaves the server default. */
    private val maxOutputTokens: Int? = null,
    /** The user's chosen reasoning level; null sends nothing. "max" is sent as "xhigh". */
    private val reasoningEffort: String? = null,
    /** The GCP project id for this account (loadCodeAssist, cached by the caller). */
    private val project: suspend () -> String,
) : StreamingChatModel {
    override val name: String get() = model
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /** The model id on the wire: the effort is a suffix, "max" maps to "xhigh". */
    val wireModel: String
        get() = when (val effort = reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }) {
            null -> model
            "max" -> "$model-xhigh"
            else -> "$model-$effort"
        }

    /** The model travels in the JSON body, so the path is just `:generateContent` on the API root. */
    private fun endpoint(stream: Boolean) =
        "$base:${if (stream) "streamGenerateContent?alt=sse" else "generateContent"}"

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = executeWithRefresh { accessToken ->
            HttpSupport.execute(http, request(system, messages, tools, stream = false, accessToken))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = AntigravityStream(onText, ::parse, ::parseUsage)
        val (status, retryAfter, text) = executeWithRefresh { accessToken ->
            stream = AntigravityStream(onText, ::parse, ::parseUsage)
            HttpSupport.stream(http, request(system, messages, tools, stream = true, accessToken), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    /**
     * Run [send] with the current access token; if the server refuses it (401) and the token can
     * be refreshed, retry once with a fresh token. A refused refresh throws (see [AntigravityTokens]).
     */
    private suspend fun executeWithRefresh(send: suspend (String) -> RawResponse): RawResponse {
        val first = send(token.get(false) ?: throw ModelError.SignInExpired("Antigravity"))
        if (first.status != 401 || !token.refreshable) return first
        return send(token.get(true) ?: throw ModelError.SignInExpired("Antigravity"))
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, accessToken: String): Request {
        val projectId = project()
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val inner = buildJsonObject {
            if (systemText.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
            }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray { tools.forEach { add(toolJson(it)) } })
                })
            })
            put("contents", buildContents(messages))
            if (maxOutputTokens != null) putJsonObject("generationConfig") {
                put("maxOutputTokens", maxOutputTokens)
            }
        }
        val payload = buildJsonObject {
            put("model", wireModel)
            put("project", projectId)
            put("request", inner)
        }
        return Request.Builder().url(endpoint(stream))
            .withHeaders(headers)
            .headerOrReject("Authorization", "Bearer $accessToken")
            .postInference(InferenceProtocol.CloudCodeAssist, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.geminiError(status, retryAfter, body, model)

    // Cloud Code takes the same tool schemas as Gemini: no additionalProperties / $schema / $id / $defs.
    private val rejectedKeys = setOf("additionalProperties", "\$schema", "\$id", "\$defs")

    // Only strip at schema positions; "properties" is a name -> schema map, its keys stay untouched.
    private fun sanitize(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.filterKeys { it !in rejectedKeys }.mapValues { (k, v) ->
            if (k == "properties" && v is JsonObject) JsonObject(v.mapValues { sanitize(it.value) }) else sanitize(v)
        })
        is JsonArray -> JsonArray(el.map { sanitize(it) })
        else -> el
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("Tool ${t.name} parameters schema is not valid JSON", e)
        }
        // A no-argument tool: Gemini-shaped APIs reject an object schema without properties, so omit parameters.
        val clean = sanitize(schema)
        val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
        if (hasProps) put("parameters", clean)
    }

    private fun buildContents(messages: List<Message>): JsonArray {
        val nameById = HashMap<String, String>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { nameById[it.id] = it.name } }
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "model" else "user"
            val parts = if (role == "model") modelParts(m) else userParts(m, nameById)
            if (parts.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += parts else merged += role to parts.toMutableList()
        }
        return buildJsonArray {
            merged.forEach { (role, parts) ->
                add(buildJsonObject { put("role", role); put("parts", JsonArray(parts)) })
            }
        }
    }

    private fun userParts(m: Message, nameById: Map<String, String>): List<JsonObject> {
        // functionResponse goes right after its functionCall, before the other parts.
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                putJsonObject("functionResponse") {
                    put("name", nameById[r.callId] ?: r.callId)
                    putJsonObject("response") {
                        put("content", r.content)
                        if (r.isError) put("is_error", true)
                    }
                }
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    putJsonObject("inlineData") { put("mimeType", p.mime); put("data", p.pngBase64) }
                }
                p is Part.Image -> buildJsonObject { put("text", "[screenshot omitted: the current model cannot see images]") }
                else -> null
            }
        }
        return results + rest
    }

    private fun modelParts(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                putJsonObject("functionCall") {
                    put("name", p.name)
                    put("args", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
                }
            }
            else -> null
        }
    }

    /** Unwrap the "response" envelope; a bare reply (no envelope) is read as-is. */
    private fun unwrap(root: JsonObject): JsonObject = (root["response"] as? JsonObject) ?: root

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val resp = unwrap(root)
            val blockReason = ((resp["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) throw ModelError.ContentRefused()
            val cand = resp["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("missing candidates")
            val parts = mutableListOf<Part>()
            var calls = 0
            (cand["content"] as? JsonObject)?.get("parts")?.jsonArray?.forEach { el ->
                val p = el.jsonObject
                val fc = p["functionCall"] as? JsonObject
                val t = (p["text"] as? JsonPrimitive)?.contentOrNull
                when {
                    fc != null -> {
                        val name = (fc["name"] as? JsonPrimitive)?.contentOrNull
                            ?: throw ModelError.BadResponse("functionCall has no name")
                        val id = (fc["id"] as? JsonPrimitive)?.contentOrNull ?: "antigravity-$calls-$name"
                        parts += Part.ToolCall(id, name, (fc["args"] ?: JsonObject(emptyMap())).toString())
                        calls++
                    }
                    t.isNullOrEmpty() -> {}
                    (p["thought"] as? JsonPrimitive)?.booleanOrNull == true -> parts += Part.Thinking(t)
                    else -> parts += Part.Text(t)
                }
            }
            val usable = parts.any { it !is Part.Thinking }
            val stop = when (val fr = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull) {
                null, "STOP", "FINISH_REASON_STOP" -> if (calls > 0) StopReason.ToolUse else StopReason.EndTurn
                "MAX_TOKENS" -> StopReason.MaxTokens
                "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    if (usable) StopReason.Other else throw ModelError.ContentRefused()
                else -> if (calls > 0 && fr.isNotEmpty()) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(resp["usageMetadata"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("promptTokenCount") ?: return null
        return Usage(inT, (n("candidatesTokenCount") ?: 0) + (n("thoughtsTokenCount") ?: 0), n("cachedContentTokenCount") ?: 0,
            n("thoughtsTokenCount") ?: 0, cacheReported = u.containsKey("cachedContentTokenCount"))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://cloudcode-pa.googleapis.com/v1internal"
    }
}

private class AntigravityStream(
    private val onText: (String) -> Unit,
    private val parseChunk: (String) -> ModelReply,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private val calls = mutableListOf<Part.ToolCall>()
    private var usage: Usage? = null
    private var finishReason: String? = null

    fun accept(data: String): Boolean {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("streamed reply is not valid JSON", e) }
        val resp = (root["response"] as? JsonObject) ?: root
        var usageInEvent = false
        usageOf(resp["usageMetadata"])?.let { usage = it; usageInEvent = true }
        val candidate = (resp["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            // A usage-only frame may follow the finishReason frame; only finish once it arrives.
            return finishReason != null && usageInEvent
        }
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { finishReason = it }
        val reply = parseChunk(data)
        var chunkCalls = 0
        reply.parts.forEach { part ->
            when (part) {
                is Part.Text -> { visible.append(part.text); onText(visible.toString()) }
                is Part.Thinking -> thinking.append(part.text)
                is Part.ToolCall -> {
                    calls += if (part.id == "antigravity-$chunkCalls-${part.name}") part.copy(id = "antigravity-${calls.size}-${part.name}") else part
                    chunkCalls++
                }
                else -> Unit
            }
        }
        // Finish as soon as a frame carries both usage and a finishReason; otherwise keep waiting
        // for a usage-only frame or EOF.
        return finishReason != null && usageInEvent
    }

    fun finish(): ModelReply {
        // The frame carrying finishReason is the end; never seeing it means the connection cut mid-stream.
        if (finishReason == null) throw ModelError.StreamCut()
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            addAll(calls)
        }
        val stop = when (finishReason) {
            "STOP", "FINISH_REASON_STOP" -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            "MAX_TOKENS" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}

/** The GCP project and tier for an account, from Cloud Code `loadCodeAssist`. */
data class CodeAssistInfo(val projectId: String, val tier: String)

/**
 * Cloud Code discovery calls (loadCodeAssist / fetchAvailableModels), used by [AntigravityModel]'s
 * project lookup and by the model list. Responses are read defensively: unknown fields are ignored,
 * and a model entry without a readable "name" is skipped.
 */
class AntigravityDiscovery(
    private val http: OkHttpClient,
    private val baseUrl: String = AntigravityModel.DEFAULT_BASE_URL,
    private val token: BearerToken,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = baseUrl.trimEnd('/')

    /** Discover (and cache, by the caller) the managed GCP project and the account's tier. */
    suspend fun loadCodeAssist(): CodeAssistInfo {
        val body = buildJsonObject {
            putJsonObject("metadata") {
                put("ideType", "IDE")
                put("platform", "PLATFORM")
                put("pluginType", "PLUGIN")
            }
        }.toString().toRequestBody(JSON_TYPE)
        val root = post(":loadCodeAssist", body)
        val project = (root["managedProjectId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ModelError.BadResponse("loadCodeAssist returned no managedProjectId")
        val tierEl = root["currentTier"]
        val tier = when (tierEl) {
            is JsonPrimitive -> tierEl.takeIf { it.isString }?.contentOrNull.orEmpty()
            is JsonObject -> (tierEl["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: (tierEl["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
            else -> ""
        }
        return CodeAssistInfo(project, tier)
    }

    /** The models this project can call, as bare model ids ("models/" prefix stripped). */
    suspend fun fetchAvailableModels(projectId: String): List<String> {
        val body = buildJsonObject { put("project", projectId) }.toString().toRequestBody(JSON_TYPE)
        val root = post(":fetchAvailableModels", body)
        val models = root["models"] as? JsonArray ?: return emptyList()
        return models.mapNotNull { el ->
            (el as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                ?.removePrefix("models/")?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    private suspend fun post(path: String, body: okhttp3.RequestBody): JsonObject {
        suspend fun send(accessToken: String): RawResponse {
            val req = Request.Builder().url("$base$path").withHeaders(headers)
                .headerOrReject("Authorization", "Bearer $accessToken").post(body).build()
            return HttpSupport.execute(http, req)
        }
        var raw = send(token.get(false) ?: throw ModelError.SignInExpired("Antigravity"))
        if (raw.status == 401 && token.refreshable) {
            raw = send(token.get(true) ?: throw ModelError.SignInExpired("Antigravity"))
        }
        if (raw.status !in 200..299) throw ModelHttp.geminiError(raw.status, raw.retryAfter, raw.body, "")
        return try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) {
            throw ModelError.BadResponse("Cloud Code reply is not valid JSON", e)
        }
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}

/** Model ids used when discovery fails or the user is not signed in yet. */
val ANTIGRAVITY_FALLBACK_MODEL_IDS = listOf(
    "gemini-3.8-flash", "gemini-4-neon", "gemini-4-hydrogen", "gemini-4-carbon", "gemini-4-argon",
)

/** Listed-model entries for [ids]: tool calls and vision on, 1M-token context (per the preset). */
fun antigravityListedModels(ids: List<String>): List<ListedModel> =
    ids.map { ListedModel(id = it, displayName = it, toolCalls = true, vision = true, contextTokens = 1_048_576) }

/** The fallback list, used when discovery fails or the user is not signed in yet. */
fun antigravityFallbackModels(): List<ListedModel> = antigravityListedModels(ANTIGRAVITY_FALLBACK_MODEL_IDS)
