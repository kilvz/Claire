package com.wangzan101.mika.core.model

import okhttp3.Request
import java.util.UUID

/**
 * The external session ID of a conversation: every request of the same conversation (main model, vision, lightweight, title generation, verification, intent detection, the explanation after a stop) uses the same one,
 * so the provider routes the requests to one place and reuses prompt caches. Derived from the conversation id (UUID v3, irreversible); the conversation id itself is never sent.
 * Requests that belong to no conversation (connection tests, model listing) use a new ID from [fresh] each time.
 */
@JvmInline
value class ModelSession(val id: String) {
    companion object {
        fun of(conversationId: String): ModelSession =
            ModelSession(UUID.nameUUIDFromBytes("mika-conversation:$conversationId".toByteArray()).toString())

        fun fresh(): ModelSession = ModelSession(UUID.randomUUID().toString())
    }
}

/** Headers sent with every request to the model service; key headers are added by each adapter itself. */
object ClientHeaders {
    /**
     * [userAgent] identifies which Claire version this is (supplied by the App; core does not write the version number); [sessionHeader] is the header name the provider declares for the session ID,
     * when not declared (null or empty) the session ID is not sent.
     */
    fun of(userAgent: String, sessionHeader: String?, session: ModelSession): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (!sessionHeader.isNullOrBlank()) put(sessionHeader.trim(), session.id)
    }
}

internal fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder =
    apply { headers.forEach { (k, v) -> headerOrReject(k, v) } }
