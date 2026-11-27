package com.wangzan101.mika.core.model

/** The upstream rejected this key: the HTTP status (429 rate limit; 401/403 invalid or blocked) and its suggested cooldown. */
data class KeyRejection(val status: Int, val retryAfterMs: Long?)

/**
 * Rotate through the key pool for one logical request: when [rejectionOf] reports that [send]'s result means "this key
 * was rejected", pass it to [rejected] first (the wiring records a 429 cooldown or 401/403 exhaustion by status), then
 * ask [nextKey] for another key and send again. Each key is tried at most once; when the pool has no other usable key,
 * the last result is returned unchanged. Budgets, cancellation and network errors are not handled here.
 */
suspend fun <T> withKeyRotation(
    nextKey: () -> String?,
    rejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)?,
    rejectionOf: (T) -> KeyRejection?,
    send: suspend (String?) -> T,
): T {
    var key = nextKey()
    val attempted = mutableSetOf(key)
    while (true) {
        val result = send(key)
        val rejection = rejectionOf(result) ?: return result
        if (key == null || rejected == null) return result
        rejected(key, rejection.retryAfterMs, rejection.status)
        val next = nextKey()
        if (next.isNullOrBlank() || !attempted.add(next)) return result
        key = next
    }
}

/** The chat-adapter path: decide rejection from the raw HTTP status. The rejection callback carries the status so the wiring can distinguish 429 cooldown from 401/403 exhaustion. */
internal suspend fun requestWithKeyRotation(
    nextKey: suspend () -> String?,
    rejected: ((String, Long?, Int) -> Unit)?,
    send: suspend (String?) -> RawResponse,
): RawResponse = withKeyRotation(nextKey, rejected, { response ->
    if (response.status in REJECTED_KEY_STATUSES) {
        KeyRejection(response.status, HttpSupport.retryAfterMs(response.retryAfter, response.body))
    } else null
}, send)

/**
 * Checks whether a failure already mapped to [ModelError] and thrown (voice transcription, read-aloud and other calls
 * that do not go through [RawResponse]) means "this key was rejected". It walks the cause chain because the voice layer
 * wraps a [ModelError] before throwing. Recognizes the same three statuses as [requestWithKeyRotation].
 */
fun keyRejectionOf(error: Throwable): KeyRejection? = generateSequence(error) { it.cause }
    .take(MAX_CAUSE_DEPTH)
    .firstNotNullOfOrNull { cause ->
        when (cause) {
            is ModelError.RateLimited -> KeyRejection(429, cause.retryAfterMs)
            is ModelError.Auth -> KeyRejection(401, null)
            is ModelError.Server -> cause.status.takeIf { it in REJECTED_KEY_STATUSES }?.let { KeyRejection(it, null) }
            else -> null
        }
    }

private val REJECTED_KEY_STATUSES = setOf(401, 403, 429)
private const val MAX_CAUSE_DEPTH = 8
