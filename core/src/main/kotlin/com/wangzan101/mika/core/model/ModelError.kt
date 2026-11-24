package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("The API Key is invalid or has no permission")
    /**
     * A request header value contains characters OkHttp rejects (newlines, other control characters, non-ASCII). Thrown before calling `header()`,
     * so that constructing the Request does not kill the process with [IllegalArgumentException]. The message carries no header text, so a key never lands in the logs.
     * [header] is only the header name.
     */
    class IllegalHeader(val header: String) : ModelError("The request header contains characters that cannot be used")
    class QuotaExceeded : ModelError("Quota exhausted")
    /** Reported usage for this task reached the budget; no more cleanup or retry requests are made. */
    class TaskBudgetExceeded(val limit: Long, val used: Long) : ModelError("The Token budget for this task is exhausted")
    /** Reported usage for this provider today, locally, reached the budget; [providerName] is only used to tell the user which provider it is. */
    class DailyBudgetExceeded(
        val providerName: String, val limit: Long, val used: Long, val providerId: String? = null,
    ) : ModelError("The provider's Token budget for today is exhausted")
    /**
     * Reported usage of the proactive capabilities today (local calendar day, summed across providers) reached their own budget; no request is sent.
     * When [byCalls] is true the limit reached is the request count and [limit], [used] are counts; otherwise they are tokens.
     * Only occurs in background calls of proactive capabilities; callers should stop quietly instead of reporting it as a failure of the user's task. See [com.wangzan101.mika.core.model.usage.ProactiveBudget].
     */
    class ProactiveBudgetExceeded(val limit: Long, val used: Long, val byCalls: Boolean = false) : ModelError("The proactive capability budget for today is exhausted")
    class RateLimited(val retryAfterMs: Long?) : ModelError("Too many requests, please try again later")
    class ContextTooLong : ModelError("The conversation exceeds the model's context length")
    /**
     * The provider has no such model (or the URL is wrong and the requested API does not exist); [model] is the requested model name, empty when unknown.
     * [detail] is the wording from the provider's JSON error (clipped), empty when absent: gateways and relay services also use 404 for other cases (e.g. their upstream models are all unavailable right now),
     * only the wording tells which one it is.
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Model not found" else "Model \"$model\" not found")
    /**
     * The provider has this model, but this account cannot use it (not activated, no permission, not purchased); [detail] is the provider's wording (clipped), empty when absent.
     * Switching to another model or enabling it in the provider's dashboard is required; retrying does not help. See [ModelAccessErrors].
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "This account cannot use this model (not activated or no permission)" else "This account cannot use model \"$model\" (not activated or no permission)")
    /** The server explicitly says this model does not support tool calling (a request with tools was rejected). Claire gets work done with tools, so it cannot be the main model. */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "This model does not support tool calling" else "Model \"$model\" does not support tool calling")
    class Network(cause: Throwable? = null) : ModelError("Network connection failed", cause)
    /**
     * An error in the proxy hop when going through a network proxy (cannot connect to the proxy, or the proxy rejected the credentials), not a provider problem. See [com.wangzan101.mika.core.net.NetProxy].
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "Cannot connect to the network proxy"
            ProxyFailure.AuthFailed -> "The network proxy rejected the account or password"
        },
        cause,
    )
    /**
     * The connection closed before the streaming reply finished (no end marker and no finish reason): most likely a gateway or proxy idle timeout along the way, or a network switch cutting the connection.
     * The reply is incomplete and unusable; resending the same request usually fixes it, and it is unrelated to the API configuration.
     */
    class StreamCut : ModelError("The connection was closed before the reply finished")
    /** [detail] preserves the provider's original error wording: the classification is Server so failover proceeds, and the wording is for diagnostic scenarios such as connection probes (MK-1 batch 2). */
    class Server(val status: Int, val detail: String? = null) : ModelError(
        "Model service error (HTTP $status)" + (detail?.takeIf { it.isNotBlank() }?.let { ":" + it.take(200) } ?: ""),
    )
    class ContentRefused : ModelError("The model refused to process this content")
    /** The server explicitly rejected the reasoning effort parameter: keep the server's wording and steer the user to change the level, never misreport it as a URL or API error. */
    class ReasoningEffortRejected(val detail: String) : ModelError("The model does not accept the selected reasoning effort")
    class ServiceTierRejected(val detail: String) : ModelError("OpenAI does not accept the selected response speed")
    /** The ChatGPT plan quota for use by other apps has hit its cap: do not retry; wait for the quota to reset or switch models. */
    class ChatGptLimitReached : ModelError("The ChatGPT quota is exhausted")
    /** This ChatGPT account cannot use plan quota in other apps (plan mismatch, region or account policy does not allow it): do not retry. */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "This account cannot use ChatGPT quota in other apps" else "This account cannot use ChatGPT quota in other apps: $detail")
    /** The sign-in is no longer valid (the token refresh was rejected, or the user disconnected this app from the account); signing in again is required. [account] is which sign-in it is (the name shown in the UI). */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("Sign-in has expired, please sign in again")
    /**
     * An API that signs in with an account (e.g. xAI) returned HTTP 403: the sign-in succeeded, but this subscription tier cannot call this API. Do not retry;
     * this provider's API Key must be used instead. [account] is which provider, [detail] is the provider's wording (clipped), empty when absent.
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("Your ${account} subscription tier cannot use this API")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "The model returned content that could not be parsed" else "The model returned content that could not be parsed: $detail", cause)
    /**
     * The whole main-model chain was tried and all failed (MK-1 batch 2): [primary] is the main model's original error, and the failure message is based on it;
     * [entries] records each model in attempt order (name, the error it produced), with the main model itself first. Task/daily budget errors are not wrapped in this layer, they are passed through as-is.
     */
    class ChainFailed(val primary: ModelError, val entries: List<Pair<String, ModelError>>) :
        ModelError(primary.message.orEmpty())

    /** No usable member in the model group: all disabled, capabilities do not match, cooling down or rate limited. [detail] says which kind, without content. */
    class GroupUnavailable(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "Model group \"$group\" has no available models" else "Model group \"$group\" has no available models: $detail")

    /** All members of the model group reached their limits. */
    class GroupExhausted(val group: String = "", val detail: String = "") :
        ModelError(if (detail.isEmpty()) "All models in group \"$group\" have reached their limits" else "All models in group \"$group\" have reached their limits: $detail")
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
