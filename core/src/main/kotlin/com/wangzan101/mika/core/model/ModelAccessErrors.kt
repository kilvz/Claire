package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared by all adapters: when the error content of HTTP 400 / 403 / 404 says "this model is the problem" rather than the key or the request itself being wrong, work out which case it is.
 * Providers differ in error format and wording, so only whether the content contains one of these kinds of phrasing is checked (case-insensitive):
 * - tool calling not supported ("does not support tools", "function calling is not supported", the Chinese wording for "tools not supported"…) → [ModelError.ToolsUnsupported];
 * - this model cannot go through the current API (the provider requires a different API shape, e.g. "Model … must be called via /messages",
 *   "unsupported_model"…, see [ENDPOINT_MISMATCH]) → [ModelError.ModelNotAvailable], with the provider's own words:
 *   switching to another model or another API type is required, handled the same as "this account cannot use it", then try the next one;
 * - this account cannot use this model ("not activated", "not authorized to access this model", "does not have access to the model",
 *   "Model.AccessDenied", the Chinese wording for "not activated" / "no permission"…), or this model has been taken offline ("no longer available", "has been retired",
 *   "model … is deprecated", the Chinese wording for "taken offline"…) → [ModelError.ModelNotAvailable], with the provider's own words;
 * - no such model ("model_not_found", "model not found", "model does not exist", "not found for API version", the Chinese wording for "model does not exist"…)
 *   → [ModelError.ModelNotFound], with the original wording from the JSON ([jsonDetail]).
 * When a 404 does not say either of the first two, the caller still treats it as model not found (also with [jsonDetail]); unrecognized is null and the caller goes by the status code. A 401 is always a key problem and is not inspected.
 *
 * HTTP 429 is usually a rate limit or exhausted quota, handled by the caller; only when the content says this account's quota on this model is zero ("limit: 0",
 * "doesn't have a free quota tier"…, see [QUOTA_ZERO]) is the model considered unusable, giving [ModelError.ModelNotAvailable]: waiting will not help, another model is needed.
 */
object ModelAccessErrors {
    private val json = Json { ignoreUnknownKeys = true }

    private val TOOLS_UNSUPPORTED = listOf(
        "does not support tools", "does not support tool", "doesn't support tools", "tools is not supported", "tools are not supported",
        "tool use is not supported", "tool calling is not supported", "function calling is not supported",
        "does not support function calling", "不支持工具", "不支持函数调用", "不支持 function call",
    )

    private val NOT_AVAILABLE = listOf(
        "not activated", "not been activated", "model.accessdenied", "model access denied", "accessdenied.unpurchased",
        "not authorized to access this model", "not authorized to use this model", "not authorized to access the model",
        "do not have access to the model", "does not have access to the model", "does not have access to model",
        "do not have access to model", "no access to model", "no permission to access the model", "no permission to use the model",
        "model_not_available", "model is not available", "model not available", "not available for your account",
        "is not available on", "not available on", "not allowed to access", "require_workers_paid",
        "has been deprecated", "was deprecated",
        "未开通", "没有开通", "尚未开通", "无权限", "没有权限使用", "无权访问", "无权使用",
        // The model has been taken offline
        "no longer available", "has been retired", "has been discontinued", "has been shut down",
        "已下线", "已停用", "已下架", "已停止服务",
    )

    /**
     * Those saying "this model cannot go through this API": a provider splits models by API shape (e.g. one model family is only available under `/messages`,
     * and calling it on the OpenAI-compatible route always returns 400 "must be called via /provider/v1/messages"), only another API type
     * or the next model in the list works. This is not a key, URL or network problem; treat the model as unusable and try the next one.
     * Only counts when the error is about the model (see [aboutTheModel]): "Your API key is not supported for this endpoint" is about the key,
     * so it is still left to the caller to handle by status code (403 is a key problem, and saving is still blocked as before).
     */
    private val ENDPOINT_MISMATCH = listOf(
        "unsupported_model",
        "must be called via",
        "only supported in v1/",
        "not supported for this endpoint",
        "unsupported for this endpoint",
    )

    /** Says "model … is deprecated / retired": deprecated alone may refer to a parameter being deprecated, so it only counts when said together with model. */
    private val MODEL_RETIRED = Regex("""\bmodels?\b[^;,\n]{0,80}?\b(?:is|has been|was)\s+(?:deprecated|retired)|\bdeprecated\s+models?\b""")

    /** A 429 saying this account's quota on this model is zero: matches `limit: 0`, `"quotaValue": "0"`, or explicitly no free quota tier. */
    private val QUOTA_ZERO = Regex(
        """\blimit\s*:\s*0(?![\d.])|"quotavalue"\s*:\s*"?0"?\s*[,}]|(?:doesn't|does not|don't|do not) have (?:a |any )?free quota tier|has no free quota tier""",
    )

    private val NOT_FOUND = listOf(
        "model_not_found", "model not found", "model does not exist", "model not exist", "no such model", "unknown model",
        "not found for api version", "模型不存在", "找不到模型",
    )

    /** [model] is the requested model name. */
    fun of(status: Int, body: String, model: String): ModelError? {
        val lower = body.lowercase()
        if (status == 429) return if (QUOTA_ZERO.containsMatchIn(lower)) ModelError.ModelNotAvailable(model, detail(body)) else null
        if (status != 400 && status != 403 && status != 404) return null
        return when {
            TOOLS_UNSUPPORTED.any { it in lower } -> ModelError.ToolsUnsupported(model)
            ENDPOINT_MISMATCH.any { it in lower } && aboutTheModel(lower, body, model) -> ModelError.ModelNotAvailable(model, detail(body))
            NOT_AVAILABLE.any { it in lower } || MODEL_RETIRED.containsMatchIn(lower) -> ModelError.ModelNotAvailable(model, detail(body))
            status != 404 && NOT_FOUND.any { it in lower } -> ModelError.ModelNotFound(model, jsonDetail(body))
            else -> null
        }
    }

    /** The error is about the model itself: it mentions model (including `unsupported_model`, `"param":"model"`), or the original wording names the requested model directly. */
    private fun aboutTheModel(lower: String, body: String, model: String): Boolean =
        "model" in lower || (model.isNotBlank() && model.lowercase() in (said(body) ?: body).lowercase())

    /** The provider's own words: the one in the JSON ([said]), or the whole body when it is not JSON; long ones are clipped ([clip]). */
    internal fun detail(body: String): String = clip(said(body) ?: body)

    /** Same as [detail], but only the wording from the JSON: empty when it is not JSON (the HTML page a reverse proxy returns for a wrong URL, empty content). */
    internal fun jsonDetail(body: String): String = said(body)?.let(::clip).orEmpty()

    /** Trim surrounding whitespace; when longer than [DETAIL_MAX] characters, clip it and append "…" so it shows there is more. */
    internal fun clip(text: String): String =
        text.trim().let { if (it.length <= DETAIL_MAX) it else it.take(DETAIL_MAX).trimEnd() + "…" }

    /** `error.message`, `error` (when it is a string), `message`, `msg`, `detail` in the JSON object (some services return an array, take the first one); null when none of them exist. */
    private fun said(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val root = parsed as? JsonObject ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject
        val err = root?.get("error")
        val errorsArray = (root?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
        return (err as? JsonObject)?.str("message") ?: (err as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: errorsArray?.str("message")
            ?: root?.str("message") ?: root?.str("msg") ?: root?.str("detail")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private const val DETAIL_MAX = 160
}
