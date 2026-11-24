package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("API Key 无效或没有权限")
    /**
     * A request header value contains characters OkHttp rejects (newlines, other control characters, non-ASCII). Thrown before calling `header()`,
     * so that constructing the Request does not kill the process with [IllegalArgumentException]. The message carries no header text, so a key never lands in the logs.
     * [header] is only the header name.
     */
    class IllegalHeader(val header: String) : ModelError("请求头含有无法使用的字符")
    class QuotaExceeded : ModelError("额度已用完")
    /** Reported usage for this task reached the budget; no more cleanup or retry requests are made. */
    class TaskBudgetExceeded(val limit: Long, val used: Long) : ModelError("本任务的 Token 预算已用完")
    /** Reported usage for this provider today, locally, reached the budget; [providerName] is only used to tell the user which provider it is. */
    class DailyBudgetExceeded(
        val providerName: String, val limit: Long, val used: Long, val providerId: String? = null,
    ) : ModelError("服务商今日的 Token 预算已用完")
    /**
     * Reported usage of the proactive capabilities today (local calendar day, summed across providers) reached their own budget; no request is sent.
     * When [byCalls] is true the limit reached is the request count and [limit], [used] are counts; otherwise they are tokens.
     * Only occurs in background calls of proactive capabilities; callers should stop quietly instead of reporting it as a failure of the user's task. See [com.wangzan101.mika.core.model.usage.ProactiveBudget].
     */
    class ProactiveBudgetExceeded(val limit: Long, val used: Long, val byCalls: Boolean = false) : ModelError("主动能力今日的预算已用完")
    class RateLimited(val retryAfterMs: Long?) : ModelError("请求太频繁,请稍后再试")
    class ContextTooLong : ModelError("对话内容超出模型上下文长度")
    /**
     * The provider has no such model (or the URL is wrong and the requested API does not exist); [model] is the requested model name, empty when unknown.
     * [detail] is the wording from the provider's JSON error (clipped), empty when absent: gateways and relay services also use 404 for other cases (e.g. their upstream models are all unavailable right now),
     * only the wording tells which one it is.
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "找不到该模型" else "找不到模型「$model」")
    /**
     * The provider has this model, but this account cannot use it (not activated, no permission, not purchased); [detail] is the provider's wording (clipped), empty when absent.
     * Switching to another model or enabling it in the provider's dashboard is required; retrying does not help. See [ModelAccessErrors].
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "这个账号用不了该模型(没开通或没权限)" else "这个账号用不了模型「$model」(没开通或没权限)")
    /** The server explicitly says this model does not support tool calling (a request with tools was rejected). Claire gets work done with tools, so it cannot be the main model. */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "该模型不支持调用工具" else "模型「$model」不支持调用工具")
    class Network(cause: Throwable? = null) : ModelError("网络连接失败", cause)
    /**
     * An error in the proxy hop when going through a network proxy (cannot connect to the proxy, or the proxy rejected the credentials), not a provider problem. See [com.wangzan101.mika.core.net.NetProxy].
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "连不上网络代理"
            ProxyFailure.AuthFailed -> "网络代理拒绝了账号或密码"
        },
        cause,
    )
    /**
     * The connection closed before the streaming reply finished (no end marker and no finish reason): most likely a gateway or proxy idle timeout along the way, or a network switch cutting the connection.
     * The reply is incomplete and unusable; resending the same request usually fixes it, and it is unrelated to the API configuration.
     */
    class StreamCut : ModelError("回复还没传完,连接就被断开了")
    /** [detail] preserves the provider's original error wording: the classification is Server so failover proceeds, and the wording is for diagnostic scenarios such as connection probes (MK-1 batch 2). */
    class Server(val status: Int, val detail: String? = null) : ModelError(
        "模型服务出错(HTTP $status)" + (detail?.takeIf { it.isNotBlank() }?.let { ":" + it.take(200) } ?: ""),
    )
    class ContentRefused : ModelError("模型拒绝处理该内容")
    /** The server explicitly rejected the reasoning effort parameter: keep the server's wording and steer the user to change the level, never misreport it as a URL or API error. */
    class ReasoningEffortRejected(val detail: String) : ModelError("模型不接受所选的思考强度")
    class ServiceTierRejected(val detail: String) : ModelError("OpenAI 不接受所选的响应速度")
    /** The ChatGPT plan quota for use by other apps has hit its cap: do not retry; wait for the quota to reset or switch models. */
    class ChatGptLimitReached : ModelError("ChatGPT 额度用完了")
    /** This ChatGPT account cannot use plan quota in other apps (plan mismatch, region or account policy does not allow it): do not retry. */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "这个账号不能在其他 App 里用 ChatGPT 额度" else "这个账号不能在其他 App 里用 ChatGPT 额度: $detail")
    /** The sign-in is no longer valid (the token refresh was rejected, or the user disconnected this app from the account); signing in again is required. [account] is which sign-in it is (the name shown in the UI). */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("登录已失效,请重新登录")
    /**
     * An API that signs in with an account (e.g. xAI) returned HTTP 403: the sign-in succeeded, but this subscription tier cannot call this API. Do not retry;
     * this provider's API Key must be used instead. [account] is which provider, [detail] is the provider's wording (clipped), empty when absent.
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("你的${account}订阅档位不能用这个接口")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "模型返回了无法解析的内容" else "模型返回了无法解析的内容: $detail", cause)
    /**
     * The whole main-model chain was tried and all failed (MK-1 batch 2): [primary] is the main model's original error, and the failure message is based on it;
     * [entries] records each model in attempt order (name, the error it produced), with the main model itself first. Task/daily budget errors are not wrapped in this layer, they are passed through as-is.
     */
    class ChainFailed(val primary: ModelError, val entries: List<Pair<String, ModelError>>) :
        ModelError(primary.message.orEmpty())

    /** No usable member in the model group: all disabled, capabilities do not match, cooling down or rate limited. [detail] says which kind, without content. */
    class GroupUnavailable(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "模型组「$group」没有可用的模型" else "模型组「$group」没有可用的模型: $detail")

    /** All members of the model group reached their limits. */
    class GroupExhausted(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "模型组「$group」的模型都达到限额" else "模型组「$group」的模型都达到限额: $detail")
}

/** Shared error classification: which errors are transient, where sending the same request again later may succeed. */
object ModelErrors {
    fun transient(e: Throwable): Boolean = when (e) {
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut -> true
        is ModelError.Server -> e.status in 500..599
        is ModelError.Proxy -> e.failure == ProxyFailure.Unreachable
        else -> false
    }
}
