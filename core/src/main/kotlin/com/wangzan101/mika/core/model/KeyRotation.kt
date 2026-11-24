package com.wangzan101.mika.core.model

/** Each key is tried at most once per logical request; budgets, cancellation and network errors are still handled by the original HTTP call. The rejection callback carries the HTTP status code, which the wiring uses to distinguish 429 cooldown from 401/403 exhaustion. */
internal suspend fun requestWithKeyRotation(
    nextKey: () -> String?,
    rejected: ((String, Long?, Int) -> Unit)?,
    send: suspend (String?) -> RawResponse,
): RawResponse {
    var key = nextKey()
    val attempted = mutableSetOf(key)
    while (true) {
        val response = send(key)
        if (response.status !in setOf(401, 403, 429) || key == null || rejected == null) return response
        rejected(key, HttpSupport.retryAfterMs(response.retryAfter, response.body), response.status)
        val next = nextKey()
        if (next.isNullOrBlank() || !attempted.add(next)) return response
        key = next
    }
}
