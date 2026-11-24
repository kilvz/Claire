package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ModelError

/**
 * Switching decisions for the main model chain (item 3): after the main model fails at this step, whether to switch to
 * the next model on the chain, and which one. Pure functions, for easy unit testing.
 * Principle: errors that belong to this model or account (key, quota, model name, not provisioned, tools unsupported,
 * plan eligibility) are useless to retry, so switch immediately; transient failures (rate limiting, 5xx, network,
 * stream cut, proxy unreachable) are first retried on the same model by [RetryPolicy], and once
 * [ModelCaller.complete] gives up they arrive here and likewise switch to the next one. If this task's budget is used
 * up, switching to anyone is useless, so do not switch; the provider's daily budget only blocks that one provider:
 * switching to a different provider helps, the same provider is skipped. Content rejected, reply unparsable, and
 * thinking effort rejected are not switched (another model would most likely behave the same; context overflow is
 * handled separately by compaction) — they are handed over as-is, and the failure message is the same as with no
 * fallback.
 */
internal object Failover {
    /**
     * [e] is the error from the model at index [fromIndex] on the chain; [providerIds] is parallel to the chain and may be an empty list.
     * The provider identity and the daily budget use the same ID. Returns the index to try next, or null for no switch.
     */
    fun nextIndex(e: ModelError, fromIndex: Int, providerIds: List<String?>, chainSize: Int): Int? = when (e) {
        is ModelError.TaskBudgetExceeded -> null
        is ModelError.DailyBudgetExceeded -> e.providerId?.let { exhausted ->
            (fromIndex + 1 until chainSize).firstOrNull { index ->
                val candidate = providerIds.getOrNull(index)
                candidate != null && candidate != exhausted
            }
        }
        is ModelError.QuotaExceeded, is ModelError.Auth, is ModelError.IllegalHeader, is ModelError.ModelNotFound, is ModelError.ModelNotAvailable,
        is ModelError.ToolsUnsupported, is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed,
        is ModelError.ChatGptLimitReached, is ModelError.ChatGptNotEligible,
        is ModelError.RateLimited, is ModelError.Network, is ModelError.StreamCut,
        // The group currently has nobody available / is exhausted: nothing left to switch to within the group, so let the chain move to the next entry
        is ModelError.GroupUnavailable, is ModelError.GroupExhausted,
        is ModelError.Server, is ModelError.Proxy ->
            (fromIndex + 1).takeIf { it < chainSize }
        else -> null
    }
}
