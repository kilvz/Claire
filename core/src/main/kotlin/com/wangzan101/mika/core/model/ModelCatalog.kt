package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A model listed by a provider. A null capability field means the API did not provide it; the caller keeps its own initial value, nothing is guessed here.
 *
 * Some providers have models under one key that use different API protocols, and the list states the protocol and URL for each model (the `api` table).
 * [endpoint] is the one Claire speaks, used to choose the adapter and URL when calling this model; null means go by the provider's API type.
 * [unsupportedApis] are stated protocol names that Claire speaks none of: such a model always fails when called, so it is not selectable (see [usable]).
 * [chat] is whether the API says it is a chat model (`type` is chat / embedding / rerank…, or whether the output modalities include text); null means not stated.
 */
data class ListedModel(
    val id: String,
    val displayName: String = "",
    val toolCalls: Boolean? = null,
    val vision: Boolean? = null,
    val contextTokens: Int? = null,
    val reasoning: Boolean? = null,
    val endpoint: ModelEndpoint? = null,
    val unsupportedApis: List<String> = emptyList(),
    val chat: Boolean? = null,
    /** Reasoning efforts explicitly given by the provider; null means unknown, an empty list means no selectable effort. The provider's order is preserved. */
    val reasoningEfforts: List<String>? = null,
    /**
     * The thinking modes the provider states this model accepts (the ones supported in Anthropic's list under `capabilities.thinking.types`,
     * such as `adaptive`, `enabled`, `disabled`); null means not stated.
     */
    val thinkingTypes: List<String>? = null,
    /**
     * Which protocols the API states this model can use (some aggregation services provide them via `supported_endpoints`, such as `/chat/completions`,
     * `/messages`); null means not stated. Calling with an API type not in the list always fails, see [ModelProtocol.ofPath].
     */
    val protocols: List<ModelProtocol>? = null,
) {
    val usable: Boolean get() = endpoint != null || unsupportedApis.isEmpty()
}

/** The API protocols recognized in the model list's `api` table; [wire] is the key name in the table. */
enum class ModelProtocol(val wire: String) {
    /** OpenAI Chat Completions, called by [OpenAiCompatModel]. */
    ChatCompletions("chat-completions"),
    /** Anthropic Messages, called by [AnthropicModel]. */
    Messages("messages"),
    /** Gemini generateContent, called by [GeminiModel]. */
    GenerateContent("generate-content"),
    /** OpenAI Responses, called by [ResponsesModel]. Listed last: when the same model also declares one of the earlier ones, the earlier ones are still used. */
    Responses("responses");

    companion object {
        fun of(wire: String?): ModelProtocol? = entries.firstOrNull { it.wire == wire }

        /**
         * Recognize the protocol by endpoint path: some aggregation services use `supported_endpoints` to give the endpoints each model can use (`/chat/completions`,
         * `/messages`, `/responses`, `/v1beta/models/{model}:generateContent`), giving only paths without protocol names. Null when unrecognized.
         *
         * Only the Gemini generation endpoint `…:generateContent` is recognized: the bare `/models` (or `/v1/models`, `/provider/v1/models`)
         * is a model-listing endpoint, and being listed does not mean it can be called; taking it as "can generate" would make models that should not be tried get tried once, so it is better left unrecognized.
         */
        fun ofPath(path: String?): ModelProtocol? {
            val p = path?.trim()?.trimEnd('/')?.lowercase() ?: return null
            return when {
                p.endsWith("/chat/completions") -> ChatCompletions
                p.endsWith("/messages") -> Messages
                p.endsWith("/responses") -> Responses
                p.endsWith(":generatecontent") -> GenerateContent
                else -> null
            }
        }
    }
}

/**
 * An API specified individually for a model: the protocol, the URL given in the list ([baseUrl], the root for this protocol, e.g. `…/anthropic/v1`),
 * and which field a compatible API puts thinking content in ([reasoningField], e.g. `reasoning_content`; null when not stated).
 */
data class ModelEndpoint(val protocol: ModelProtocol, val baseUrl: String, val reasoningField: String? = null) {
    /**
     * Base URL handed to the adapter. The adapters append the version path themselves: [AnthropicModel] appends `/v1/messages`, [GeminiModel] appends
     * `/v1beta/models/…`, so a trailing segment like that already present in the list URL is removed to avoid duplication; Chat Completions only appends `/chat/completions`,
     * Responses only appends `/responses`, so the URL is used as-is.
     */
    val adapterBaseUrl: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            return when (protocol) {
                ModelProtocol.ChatCompletions, ModelProtocol.Responses -> url
                ModelProtocol.Messages -> url.removeSuffix("/v1")
                ModelProtocol.GenerateContent -> url.removeSuffix("/v1beta")
            }
        }
}

/** Why listing models failed; [text] is the human-readable reason. */
enum class ListFailure(val text: String) {
    BadUrl("接口地址不对,要以 http:// 或 https:// 开头"),
    Auth("API Key 无效或没有权限"),
    /** The pasted key contains characters that cannot go into an HTTP header. No request was sent. */
    IllegalKey("API Key 含有无法使用的字符，请只粘贴密钥本身"),
    Quota("账户额度已用完"),
    RateLimited("请求太频繁,请稍后再试"),
    Network("连不上这个地址,请检查网络和接口地址"),
    ProxyUnreachable("连不上网络代理,或代理没有转发这个请求;请检查设置 › 网络代理"),
    ProxyAuth("网络代理拒绝了账号或密码,请检查设置 › 网络代理"),
    NotSupported("这个服务不提供模型列表"),
    Server("服务暂时出错,请稍后再试"),
    BadResponse("返回的内容看不懂,请检查接口地址和接口类型"),
    ;

    companion object {
        /** The request was not sent: proxy-hop problems are reported as such, anything else counts as a connection failure. */
        fun of(e: Throwable): ListFailure = when ((e as? ModelError.Proxy)?.failure) {
            ProxyFailure.Unreachable -> ProxyUnreachable
            ProxyFailure.AuthFailed -> ProxyAuth
            null -> Network
        }
    }
}

sealed interface ModelListResult {
    data class Ok(val models: List<ListedModel>) : ModelListResult
    data class Failed(val reason: ListFailure, val detail: String = "") : ModelListResult {
        val message: String get() = if (detail.isEmpty()) reason.text else "${reason.text}($detail)"
    }
}

/**
 * Fetch available models from each provider's public "list models" API:
 * - OpenAI-compatible: `GET {base}/models` (Bearer). Models that only give an id are returned as they are; capabilities are filled in only for those carrying `context_length`,
 *   `architecture.input_modalities`, `supported_parameters` and similar fields (some aggregation services provide them).
 *   Another form is also recognized: `capabilities.tools.supported`, `capabilities.reasoning.supported`, `limit.context`,
 *   `modalities.input` (vision when it contains `image/…`), and the per-protocol URL `api` table (see [ListedModel.endpoint]).
 *   Listing with a ChatGPT account returns the `models` table: the name is in `slug`, the display name in `display_name`; only those whose `visibility` is `list` are kept, in the provider's order;
 *   when `input_modalities`, `context_window`, `supported_reasoning_levels` are given they are filled in accordingly.
 * - Anthropic: `GET {base}/v1/models`, paginated by `after_id`; `max_input_tokens` is the context,
 *   `capabilities.image_input` / `capabilities.thinking` are vision and reasoning. No tool-call information is given.
 *   Reasoning efforts are filled in item by item from `capabilities.effort` and `capabilities.thinking.types` (see [anthropicEfforts]).
 * - Gemini: `GET {base}/v1beta/models`, paginated by `pageToken`; only those that can `generateContent` are kept,
 *   `inputTokenLimit` is the context, `thinking` is reasoning. No tool-call or vision information, and no reasoning efforts.
 */
class ModelCatalog(private val http: OkHttpClient) {
    // headers for the methods: headers sent with every request (see [ClientHeaders]); key headers are added here
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openAiCompat(baseUrl: String, apiKey: String?, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        if (isCloudflareWorkersAi(baseUrl)) {
            val cfResult = cloudflareWorkersAi(baseUrl, apiKey, headers)
            if (cfResult is ModelListResult.Ok && cfResult.models.isNotEmpty()) {
                return@run cfResult
            }
        }
        val url = endpoint(baseUrl, "/models") ?: return@run badUrl()
        val req = buildRequest {
            Request.Builder().url(url).get().withHeaders(headers)
                .apply { if (!apiKey.isNullOrEmpty()) headerOrReject("Authorization", "Bearer $apiKey") }.build()
        } ?: return@run illegalKey()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        ModelListResult.Ok(data.mapNotNull { (it as? JsonObject)?.let(::openAiModel) }.distinctBy { it.id })
    }

    suspend fun anthropic(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("limit", "1000")
                .apply { if (after != null) addQueryParameter("after_id", after) }.build()
            val req = buildRequest {
                Request.Builder().url(url).get().withHeaders(headers)
                    .headerOrReject("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            } ?: return@run illegalKey()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["data"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
            all += data.mapNotNull { (it as? JsonObject)?.let(::anthropicModel) }
            after = root.str("last_id")
            if (root.bool("has_more") != true || after == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun gemini(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1beta/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("pageSize", "1000")
                .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
            val req = buildRequest {
                Request.Builder().url(url).get().withHeaders(headers).headerOrReject("x-goog-api-key", apiKey).build()
            } ?: return@run illegalKey()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["models"] as? JsonArray ?: JsonArray(emptyList())
            all += data.mapNotNull { (it as? JsonObject)?.let(::geminiModel) }
            token = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    private fun openAiModel(o: JsonObject): ListedModel? {
        // When listing models with a ChatGPT account (the `models` table) the name is in `slug`, the display name in `display_name`; only those whose `visibility` is list are kept
        o.str("visibility")?.let { if (it != "list") return null }
        val id = (o.str("id") ?: o.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val arch = o["architecture"] as? JsonObject
        val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val modality = arch?.str("modality")?.substringBefore("->")
        val outputs = ((arch?.get("output_modalities") ?: o.obj("modalities")?.get("output")) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: arch?.str("modality")?.takeIf { "->" in it }?.substringAfter("->")?.split('+')
        val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val caps = o["capabilities"] as? JsonObject
        val modalIn = ((o.obj("modalities")?.get("input") ?: o["input_modalities"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // DeepSeek's list is under `effort.supported_levels` (there is also `default_level`, not used here)
        val reasoningLevels = (o["supported_reasoning_levels"] ?: caps?.obj("reasoning")?.get("effort")
            ?: o.obj("effort")?.get("supported_levels")) as? JsonArray
        val efforts = reasoningLevels?.mapNotNull { level ->
            (((level as? JsonObject)?.get("effort") ?: level) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()
        val api = o.obj("api")
        // Some aggregation services do not provide the `api` table, and instead use `supported_endpoints` to state which endpoints this model can use
        val protocols = (o["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ModelProtocol::ofPath) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        // A model may declare several protocols: take the first one Claire speaks, in the order Chat Completions, Messages, generateContent, Responses
        val endpoint = api?.let { a ->
            ModelProtocol.entries.firstNotNullOfOrNull { p ->
                val e = a.obj(p.wire) ?: return@firstNotNullOfOrNull null
                val url = e.str("base_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                ModelEndpoint(p, url, e.obj("compatibility")?.str("reasoning_field"))
            }
        }
        return ListedModel(
            id = id,
            displayName = (o.str("name") ?: o.str("display_name")).orEmpty(),
            toolCalls = params?.let { "tools" in it } ?: caps?.obj("tools")?.bool("supported")
                ?: caps?.bool("function_calling") ?: caps?.bool("tool_calls"),
            vision = inputs?.let { "image" in it } ?: modalIn?.let { m -> m.any { it == "image" || it.startsWith("image/") } }
                ?: modality?.let { "image" in it } ?: o.bool("supports_image_in") ?: caps?.bool("vision"),
            contextTokens = o.int("context_length") ?: o.int("context_window") ?: o.int("max_context_length")
                ?: o.obj("limit")?.int("context"),
            reasoning = params?.let { "reasoning" in it || "include_reasoning" in it } ?: caps?.obj("reasoning")?.bool("supported")
                ?: o.bool("supports_reasoning") ?: reasoningLevels?.let { it.isNotEmpty() },
            endpoint = endpoint,
            reasoningEfforts = efforts,
            unsupportedApis = if (endpoint == null) api?.keys?.toList().orEmpty() else emptyList(),
            protocols = protocols,
            chat = when (o.str("type")?.lowercase()) {
                in CHAT_TYPES -> true
                in NON_CHAT_TYPES -> false
                // Models that only output images or audio are not chat models; ones that output text are not recorded specially
                else -> outputs?.takeIf { "text" !in it }?.let { false }
            },
        )
    }

    private fun anthropicModel(o: JsonObject): ListedModel? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = o["capabilities"] as? JsonObject
        val thinking = caps?.obj("thinking")
        val types = thinking?.obj("types")?.supportedKeys()
        return ListedModel(
            id = id,
            displayName = o.str("display_name").orEmpty(),
            vision = (caps?.get("image_input") as? JsonObject)?.bool("supported"),
            contextTokens = o.int("max_input_tokens"),
            reasoning = thinking?.bool("supported"),
            reasoningEfforts = types?.let { anthropicEfforts(caps?.obj("effort"), it) },
            thinkingTypes = types,
        )
    }

    /**
     * Selectable reasoning efforts for an Anthropic model: given only when it accepts adaptive thinking (`adaptive`, with the effort under `output_config.effort`),
     * the efforts being the levels marked supported in `capabilities.effort`; when it also accepts turning thinking off (`disabled`), a `none` level is prepended.
     * When it only accepts budget-based thinking (`enabled` + `budget_tokens`) or has no levels at all, there are no selectable efforts.
     */
    private fun anthropicEfforts(effort: JsonObject?, types: List<String>): List<String> {
        val levels = effort?.takeIf { it.bool("supported") == true }?.supportedKeys().orEmpty()
            .sortedBy { EFFORT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: EFFORT_ORDER.size }
        if ("adaptive" !in types || levels.isEmpty()) return emptyList()
        return listOfNotNull("none".takeIf { "disabled" in types }) + levels
    }

    /** The sub-items marked supported in `{"a":{"supported":true},"b":{"supported":false},"supported":true}`: `[a]`. */
    private fun JsonObject.supportedKeys(): List<String> = keys.filter { (this[it] as? JsonObject)?.bool("supported") == true }

    private fun geminiModel(o: JsonObject): ListedModel? {
        val id = o.str("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "generateContent" !in methods) return null
        return ListedModel(
            id = id,
            displayName = o.str("displayName").orEmpty(),
            contextTokens = o.int("inputTokenLimit"),
            reasoning = o.bool("thinking"),
        )
    }

    private fun isCloudflareWorkersAi(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        return trimmed.contains("/accounts/", ignoreCase = true) &&
            (trimmed.contains("api.cloudflare.com", ignoreCase = true) || trimmed.contains("/ai", ignoreCase = true))
    }

    private suspend fun cloudflareWorkersAi(baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult = run {
        val trimmed = baseUrl.trim().trimEnd('/')
        val searchUrl = trimmed.removeSuffix("/v1").trimEnd('/') + "/models/search?task=Text%20Generation"
        val url = searchUrl.toHttpUrlOrNull() ?: return@run badUrl()
        val req = buildRequest {
            Request.Builder().url(url).get().withHeaders(headers)
                .apply { if (!apiKey.isNullOrEmpty()) headerOrReject("Authorization", "Bearer $apiKey") }.build()
        } ?: return@run illegalKey()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val result = root["result"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        val models = result.mapNotNull { (it as? JsonObject)?.let(::cloudflareModel) }
            .sortedWith(compareByDescending<ListedModel> { it.toolCalls == true }.thenBy { it.id })
            .distinctBy { it.id }
        ModelListResult.Ok(models)
    }

    private fun cloudflareModel(o: JsonObject): ListedModel? {
        val id = (o.str("name") ?: o.str("id"))?.takeIf { it.isNotBlank() } ?: return null
        val props = (o["properties"] as? JsonArray)?.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val pid = obj.str("property_id") ?: return@mapNotNull null
            pid to obj["value"]
        }?.toMap().orEmpty()

        if (props.containsKey("planned_deprecation_date")) return null

        fun propStr(pid: String): String? = (props[pid] as? JsonPrimitive)?.contentOrNull
        fun propBool(pid: String): Boolean? = propStr(pid)?.toBooleanStrictOrNull()

        val context = propStr("context_window")?.toIntOrNull()
        val tools = propBool("function_calling")
        val vision = propBool("vision")
        val reasoning = propBool("reasoning")

        val efforts = (props["reasoning_effort"] as? JsonObject)?.obj("value")?.let { effortObj ->
            (effortObj["supported_efforts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }

        return ListedModel(
            id = id,
            displayName = id.removePrefix("@cf/"),
            toolCalls = tools,
            vision = vision,
            contextTokens = context,
            reasoning = reasoning,
            reasoningEfforts = efforts,
            chat = true,
        )
    }

    private class Failure(val result: ModelListResult.Failed) : Exception(result.message)

    private fun Throwable.asFailure(): ModelListResult.Failed =
        (this as? Failure)?.result ?: ModelListResult.Failed(ListFailure.Network)

    private suspend fun fetch(req: Request): Result<JsonObject> {
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
        }
        if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
        val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) { null }
            ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
        return Result.success(root)
    }

    private fun failureFor(status: Int, body: String): ModelListResult.Failed = when {
        status == 401 -> ModelListResult.Failed(ListFailure.Auth)
        // A 403 with insufficient_quota means the quota is used up, not an invalid key; keep the same order as [ModelHttp.openAiError] (quota before 403).
        status == 402 || body.contains("insufficient_quota") -> ModelListResult.Failed(ListFailure.Quota)
        // Gemini returns 400 INVALID_ARGUMENT for an invalid key, with the reason in the content
        status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> ModelListResult.Failed(ListFailure.Auth)
        status == 429 -> ModelListResult.Failed(ListFailure.RateLimited)
        status == 404 || status == 405 || status == 501 -> ModelListResult.Failed(ListFailure.NotSupported)
        status in 500..599 -> ModelListResult.Failed(ListFailure.Server, "HTTP $status")
        status == 403 -> ModelListResult.Failed(ListFailure.Auth)
        else -> ModelListResult.Failed(ListFailure.BadResponse, "HTTP $status")
    }

    private fun badUrl() = ModelListResult.Failed(ListFailure.BadUrl)

    private fun illegalKey() = ModelListResult.Failed(ListFailure.IllegalKey)

    /**
     * Build the request. Returns null when a header value cannot go into OkHttp, so that [IllegalArgumentException] does not escape
     * (that exception's message carries the header text).
     */
    private fun buildRequest(build: () -> Request): Request? = try {
        build()
    } catch (e: ModelError.IllegalHeader) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun endpoint(baseUrl: String, path: String) = (baseUrl.trim().trimEnd('/') + path).toHttpUrlOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private companion object {
        /** Pagination cap: prevent the server from always having a next page. */
        const val MAX_PAGES = 10
        /** Anthropic's `capabilities.effort` is ordered alphabetically; the UI orders them from shallow to deep, with unrecognized ones last. */
        val EFFORT_ORDER = listOf("low", "medium", "high", "xhigh", "max")
        /** The values of the `type` field in the list: those that are chat models, and those that are not (embedding, rerank, image generation, speech, moderation). */
        val CHAT_TYPES = setOf("chat", "llm", "language", "text", "vlm", "code")
        val NON_CHAT_TYPES = setOf(
            "embedding", "embeddings", "rerank", "reranker", "image", "images", "video", "audio", "tts", "stt", "asr",
            "speech", "transcription", "moderation",
        )
    }
}
