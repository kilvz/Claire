package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.ModelError

/**
 * The daily token budget for proactive capabilities (cheap calls Claire makes on her own in the background, such as
 * notification triage): summed across providers, per local calendar day, and independent of each provider's daily budget —
 * proactive usage does not count toward a provider's, and a provider's does not block proactive capabilities.
 * There is no "unlimited": unset or invalid values use [DEFAULT_DAILY_LIMIT]. When it is used up, callers should stop quietly
 * without disturbing the user.
 *
 * There is also a request-count limit ([callLimit], tied to the token limit): some providers report no usage and failed
 * requests have no usage either, so the token count stays 0 and a token-only limit would be no limit at all; background calls
 * have nobody watching, so there must be a limit.
 *
 * Triage (for notification sorting) only uses [TRIAGE_SHARE_PERCENT]% of each limit and stops by itself ([triageExhausted]),
 * leaving the remainder to other low-frequency proactive uses; the hard limit no use may cross is [check] (the full amount).
 */
object ProactiveBudget {
    // On device (a local model without thinking) a batch of triage measured 470–700 tokens including the prompt; models with thinking use several times more.
    // At 20k that is only enough for thirty or forty batches.
    const val DEFAULT_DAILY_LIMIT = 200_000L

    /** The floor for the daily request limit: however small the token limit is, at least this many requests. */
    const val CALL_LIMIT_FLOOR = 200L

    /**
     * The daily request limit = token limit / this number. One triage measured about 500 tokens; 250 gives twice the margin:
     * providers that report usage always reach the token limit first; this limit only covers providers that report no usage
     * (token count stays 0).
     */
    const val TOKENS_PER_CALL_ALLOWANCE = 250L

    /** The share (percent) triage may use, applied to both tokens and request counts; the remainder is left to other proactive uses. */
    const val TRIAGE_SHARE_PERCENT = 80L

    /** Which limit has been used up. */
    enum class Limit { Tokens, Calls }

    /** The maximum number of requests actually sent per day (including retries), summed across providers: follows the token limit [tokenLimit] and is never below [CALL_LIMIT_FLOOR]. */
    fun callLimit(tokenLimit: Long): Long = maxOf(CALL_LIMIT_FLOOR, tokenLimit / TOKENS_PER_CALL_ALLOWANCE)

    /** Triage's share of a given limit [limit]. */
    fun triageShare(limit: Long): Long = limit * TRIAGE_SHARE_PERCENT / 100

    /**
     * Given [tokensToday] tokens used and [callsToday] requests sent today, which of the limits (based on the full [tokenLimit])
     * is used up; null if none is. Other uses use the full amount through this; triage uses [triageExhausted].
     */
    fun exhausted(tokensToday: Long, callsToday: Long, tokenLimit: Long): Limit? = when {
        !allowed(tokensToday, tokenLimit) -> Limit.Tokens
        !allowed(callsToday, callLimit(tokenLimit)) -> Limit.Calls
        else -> null
    }

    /** Same as [exhausted], but counting only the triage share ([TRIAGE_SHARE_PERCENT]%): triage stops by itself at that point. */
    fun triageExhausted(tokensToday: Long, callsToday: Long, tokenLimit: Long): Limit? = when {
        !allowed(tokensToday, triageShare(tokenLimit)) -> Limit.Tokens
        !allowed(callsToday, triageShare(callLimit(tokenLimit))) -> Limit.Calls
        else -> null
    }

    /** The value stored in settings; anything outside the [TokenBudgetLimits] range (including unset) falls back to the default. */
    fun limitOf(stored: Long?): Long = stored?.takeIf(TokenBudgetLimits::valid) ?: DEFAULT_DAILY_LIMIT

    /** Whether one more request may be sent given [usedToday]; it stops at the limit, by the same measure as the provider's daily budget. */
    fun allowed(usedToday: Long, limit: Long): Boolean = usedToday < limit

    /**
     * Throws [ModelError.ProactiveBudgetExceeded] when no more can be sent; called before every actual request — once at the start
     * of a call, and also before the adapter sends another request within the same call (compatible retry, resend after a token
     * refresh; see the beforeRetry of [metered]).
     * [callsToday] is the number of requests already sent today (including those already sent in this call but not yet recorded in
     * the ledger); it also stops at [callLimit] (then [ModelError.ProactiveBudgetExceeded.byCalls] in the error is true).
     * This is the hard limit on the full amount, shared by all uses.
     */
    fun check(usedToday: Long, limit: Long, callsToday: Long = 0) {
        if (!allowed(usedToday, limit)) throw ModelError.ProactiveBudgetExceeded(limit, usedToday)
        val calls = callLimit(limit)
        if (!allowed(callsToday, calls)) throw ModelError.ProactiveBudgetExceeded(calls, callsToday, byCalls = true)
    }
}
