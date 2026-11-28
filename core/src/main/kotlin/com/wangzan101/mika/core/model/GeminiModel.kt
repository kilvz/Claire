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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native Gemini generateContent API.
 * - Roles are only user / model; adjacent messages with the same role are merged. System messages in the history are folded into systemInstruction.
 * - Gemini does not give call IDs: synthesize "gemini-<index>-<name>" (use the id from the response if present);
 *   when sending functionResponse back, look up the function name by callId among the ToolCalls in the history.
 * - thought parts are parsed as Thinking; when a reasoning effort is selected, `generationConfig.thinkingConfig.thinkingLevel` controls the depth.
 */
/** Placeholder signature provided by Google: used when the call was not produced by Gemini itself (the original signature cannot be supplied) so that signature validation is skipped. */
internal const val SKIP_SIGNATURE = "skip_thought_signature_validator"

class GeminiModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** Headers sent with every request (see [ClientHeaders]); key headers are added by the adapter itself. */
    private val headers: Map<String, String> = emptyMap(),
    /** Maximum number of tokens in the reply (`generationConfig.maxOutputTokens`); null means unlimited, using the server default. */
    private val maxOutputTokens: Int? = null,
    /** Reasoning effort selected by the user; null sends nothing, `none` turns thinking off as far as possible, anything else is sent as `thinkingConfig.thinkingLevel`. */
    private val reasoningEffort: String? = null,
    private val keySupplier: (suspend () -> String?)? = null,
    /** Called back with the HTTP status code when a key is rejected upstream (429/401/403); the wiring uses the status to decide between cooldown (429) and marking exhausted (401/403). */
    private val onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val streamEndpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:streamGenerateContent?alt=sse"
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun resolveKey(): String = keySupplier?.invoke()?.takeIf { it.isNotBlank() } ?: apiKey

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            HttpSupport.execute(http, request(system, messages, tools, stream = false, key = key.orEmpty()))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = GeminiStream(onText, ::parse, ::parseUsage)
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            stream = GeminiStream(onText, ::parse, ::parseUsage)
            HttpSupport.stream(http, request(system, messages, tools, stream = true, key = key.orEmpty()), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, key: String): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val payload = buildJsonObject {
            if (systemText.isNotBlank()) putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", systemText) }) }
            }
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray { tools.forEach { add(toolJson(it)) } })
                })
            })
            put("contents", buildContents(messages))
            if (maxOutputTokens != null || reasoningEffort != null) putJsonObject("generationConfig") {
                maxOutputTokens?.let { put("maxOutputTokens", it) }
                reasoningEffort?.let { effort ->
                    putJsonObject("thinkingConfig") {
                        if (effort == "none") {
                            put("thinkingBudget", 0)
                        } else {
                            put("includeThoughts", true)
                            put("thinkingLevel", effort.uppercase())
                        }
                    }
                }
            }
        }
        return Request.Builder().url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .headerOrReject("x-goog-api-key", key)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.geminiError(status, retryAfter, body, model)

    // Gemini does not accept keys outside its OpenAPI subset, such as additionalProperties / $schema / $id / allOf;
    // title, minLength, maxLength, pattern and default are in its Schema, so they are kept as-is
    private val rejectedKeys = setOf(
        "additionalProperties", "\$schema", "\$id", "\$defs", "definitions", "\$ref",
        "allOf", "not", "const", "examples", "\$comment",
        "\$vocabulary", "\$anchor", "\$dynamicRef", "\$dynamicAnchor",
        "dependentSchemas", "dependentRequired", "dependencies",
        "patternProperties", "propertyNames", "if", "then", "else",
        "unevaluatedProperties", "unevaluatedItems", "exclusiveMinimum", "exclusiveMaximum",
        "multipleOf", "contentEncoding", "contentMediaType",
        "readOnly", "writeOnly", "deprecated",
    )

    // Strip and downgrade structures outside the OpenAPI subset only at schema positions; properties is a name -> schema map, and the names themselves are untouched
    private fun sanitize(el: JsonElement, isRoot: Boolean = false): JsonElement = when (el) {
        is JsonObject -> {
            val out = linkedMapOf<String, JsonElement>()
            val rawType = el["type"]
            val constVal = el["const"]

            el.forEach { (k, v) ->
                when {
                    k in rejectedKeys -> Unit
                    k == "properties" && v is JsonObject ->
                        out[k] = JsonObject(v.mapValues { sanitize(it.value, isRoot = false) })
                    k == "default" -> out[k] = v
                    k == "enum" && v is JsonArray -> {
                        val strings = v.filter { it is JsonPrimitive && it.isString }
                        if (strings.isNotEmpty()) out[k] = JsonArray(strings)
                    }
                    k == "type" && v is JsonArray -> {
                        val types = v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                        val nonNull = types.filter { it != "null" && it.isNotBlank() }
                        out["type"] = JsonPrimitive(nonNull.firstOrNull() ?: "string")
                        if ("null" in types && "nullable" !in el) out["nullable"] = JsonPrimitive(true)
                    }
                    k == "type" && v is JsonPrimitive && v.isString && v.content == "null" -> {
                        out["type"] = JsonPrimitive("string")
                        if ("nullable" !in el) out["nullable"] = JsonPrimitive(true)
                    }
                    k == "items" && v is JsonArray -> {
                        val first = v.firstOrNull() as? JsonObject
                        out["items"] = first?.let { sanitize(it, isRoot = false) }
                            ?: buildJsonObject { put("type", "string") }
                    }
                    // Gemini has no oneOf, so send it as anyOf (which only relaxes "exactly one")
                    (k == "anyOf" || k == "oneOf") && v is JsonArray -> {
                        val nonNull = v.filterNot { branch ->
                            val obj = branch as? JsonObject ?: return@filterNot false
                            val t = obj["type"]
                            (t is JsonPrimitive && t.isString && t.content == "null") ||
                                (t is JsonArray && t.all { it is JsonPrimitive && it.isString && it.content == "null" })
                        }
                        if (nonNull.size < v.size && "nullable" !in el) out["nullable"] = JsonPrimitive(true)
                        val cleanBranches = nonNull.map { sanitize(it, isRoot = false) }
                        if (cleanBranches.size == 1 && cleanBranches[0] is JsonObject) {
                            (cleanBranches[0] as JsonObject).forEach { (bk, bv) -> if (bk !in out) out[bk] = bv }
                        } else if (cleanBranches.isNotEmpty() && "anyOf" !in out) {
                            out["anyOf"] = JsonArray(cleanBranches)
                        }
                    }
                    else -> out[k] = sanitize(v, isRoot = false)
                }
            }

            if (constVal != null && constVal !is kotlinx.serialization.json.JsonNull) {
                if (constVal is JsonPrimitive && constVal.isString && "enum" !in out) {
                    out["enum"] = JsonArray(listOf(constVal))
                }
                if ("type" !in out && constVal is JsonPrimitive) {
                    val inferred = when {
                        constVal.isString -> "string"
                        constVal.content == "true" || constVal.content == "false" -> "boolean"
                        constVal.content.toLongOrNull() != null -> "integer"
                        constVal.content.toDoubleOrNull() != null -> "number"
                        else -> null
                    }
                    if (inferred != null) out["type"] = JsonPrimitive(inferred)
                }
            }

            if (rawType !is JsonArray && "type" !in out && "items" in out) {
                out["type"] = JsonPrimitive("array")
            }

            val effType = (out["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (effType == "array" && out["items"] !is JsonObject) {
                out["items"] = buildJsonObject { put("type", "string") }
            }

            if (effType != null && effType != "object") {
                out.remove("properties")
                out.remove("required")
            } else if (!isRoot && effType == "object") {
                // An object without properties is "arbitrary keys and values" (both built-in do_steps args and read_script args are like this), so send it as-is;
                // downgrading it to string would make the model pass a string, which the tool rejects as "must be an object"
                val propsObj = (out["properties"] as? JsonObject)?.takeIf { it.isNotEmpty() }
                if (propsObj == null) {
                    out.remove("properties")
                    out.remove("required")
                } else if (out["required"] is JsonArray) {
                    val keptReq = (out["required"] as JsonArray).filter {
                        it is JsonPrimitive && it.isString && it.content in propsObj
                    }
                    if (keptReq.isEmpty()) out.remove("required") else out["required"] = JsonArray(keptReq)
                }
            }

            // Only nodes that originally had content but were stripped to zero keys (for example, only $ref) fall back to string; nodes without a type are sent as-is
            if (!isRoot && out.isEmpty() && el.isNotEmpty()) {
                out["type"] = JsonPrimitive("string")
            }
            JsonObject(out)
        }
        is JsonArray -> JsonArray(el.map { sanitize(it, isRoot = false) })
        else -> el
    }

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        val schema = try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("The parameter schema for tool ${t.name} is not valid JSON", e)
        }
        // Tools without parameters: Gemini rejects an object schema without properties, so omit parameters
        val clean = sanitize(schema, isRoot = true)
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
        // functionResponse follows right after functionCall, before the other parts
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

    private fun modelParts(m: Message): List<JsonObject> {
        // Only the first call in a round carries a signature (the other parallel calls never had one); no signature on the first call means the call was not produced by this adapter
        // (model switched, or a call replayed from the log), so send the validation-skipping placeholder signature as documented by Google
        val firstCall = m.parts.firstOrNull { it is Part.ToolCall }
        return m.parts.mapNotNull { p -> modelPart(p, first = p === firstCall) }
    }

    private fun modelPart(p: Part, first: Boolean): JsonObject? =
        when (p) {
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                (p.opaque ?: SKIP_SIGNATURE.takeIf { first })?.let { put("thoughtSignature", it) }
                putJsonObject("functionCall") {
                    put("name", p.name)
                    put("args", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
                }
            }
            else -> null
        }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val blockReason = ((root["promptFeedback"] as? JsonObject)?.get("blockReason") as? JsonPrimitive)?.contentOrNull
            if (blockReason != null) throw ModelError.ContentRefused()
            val cand = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("Missing candidates")
            val parts = mutableListOf<Part>()
            var calls = 0
            (cand["content"] as? JsonObject)?.get("parts")?.jsonArray?.forEach { el ->
                val p = el.jsonObject
                val fc = p["functionCall"] as? JsonObject
                val t = (p["text"] as? JsonPrimitive)?.contentOrNull
                when {
                    fc != null -> {
                        val name = (fc["name"] as? JsonPrimitive)?.contentOrNull
                            ?: throw ModelError.BadResponse("functionCall is missing name")
                        val id = (fc["id"] as? JsonPrimitive)?.contentOrNull ?: "gemini-$calls-$name"
                        val sig = (p["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
                        parts += Part.ToolCall(id, name, (fc["args"] ?: JsonObject(emptyMap())).toString(), sig)
                        calls++
                    }
                    t.isNullOrEmpty() -> {}
                    (p["thought"] as? JsonPrimitive)?.booleanOrNull == true -> parts += Part.Thinking(t)
                    else -> parts += Part.Text(t)
                }
            }
            val usable = parts.any { it !is Part.Thinking }
            val stop = when (val fr = (cand["finishReason"] as? JsonPrimitive)?.contentOrNull) {
                null, "STOP" -> if (calls > 0) StopReason.ToolUse else StopReason.EndTurn
                "MAX_TOKENS" -> StopReason.MaxTokens
                "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    if (usable) StopReason.Other else throw ModelError.ContentRefused()
                else -> if (calls > 0 && fr.isNotEmpty()) StopReason.ToolUse else StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usageMetadata"]))
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
        // promptTokenCount already includes cache hits, matching the common convention
        val thoughts = n("thoughtsTokenCount") ?: 0
        val cached = n("cachedContentTokenCount")
        return Usage(inT, (n("candidatesTokenCount") ?: 0) + thoughts, cached ?: 0, thoughts, cacheReported = cached != null)
    }
}

private class GeminiStream(
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
        catch (e: Exception) { throw ModelError.BadResponse("The streaming response is not valid JSON", e) }
        var usageInEvent = false
        usageOf(root["usageMetadata"])?.let { usage = it; usageInEvent = true }
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate == null) {
            // After finishReason there may be another frame carrying only usage; only once it arrives is the terminal state complete, do not close the stream early and drop it
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
                    calls += if (part.id == "gemini-$chunkCalls-${part.name}") part.copy(id = "gemini-${calls.size}-${part.name}") else part
                    chunkCalls++
                }
                else -> Unit
            }
        }
        // If this frame carried usage and finishReason has already been seen, finish now; otherwise keep waiting for a possible usage-only frame or EOF
        return finishReason != null && usageInEvent
    }

    fun finish(): ModelReply {
        // Gemini streams have no separate end marker; the frame with finishReason is the end, and not receiving it means the connection was cut midway
        if (finishReason == null) throw ModelError.StreamCut()
        val parts = buildList<Part> {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            addAll(calls)
        }
        val stop = when (finishReason) {
            "STOP" -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.EndTurn
            "MAX_TOKENS" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}
