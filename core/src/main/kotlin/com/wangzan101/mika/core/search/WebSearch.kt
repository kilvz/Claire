package com.wangzan101.mika.core.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One web search result; the body text is only untrusted material and must not be treated as instructions. */
data class WebSearchHit(val title: String, val url: String, val snippet: String)

sealed interface WebSearchResult {
    data class Success(val hits: List<WebSearchHit>) : WebSearchResult
    data class Failure(val code: String, val message: String, val retryAfterSeconds: Long? = null) : WebSearchResult
}

/** Web search port. The implementation is provided by the provider the user chose (see [WebSearchProvider]); when nothing is configured, no search tool is offered. */
interface WebSearch {
    suspend fun search(query: String, count: Int): WebSearchResult

    companion object {
        /** Maximum number of results per request: providers have different limits, so this one is used uniformly, keeping too much untrusted content out of the model context. */
        const val MAX_COUNT = 10

        /** Only HTTP(S) is accepted, with no query or fragment of its own in the address and no username or password, so it does not conflict with the request parameters. */
        fun parseEndpoint(raw: String): HttpUrl? = raw.trim().toHttpUrlOrNull()?.takeIf {
            (it.scheme == "https" || it.scheme == "http") && it.query == null && it.fragment == null &&
                it.username.isEmpty() && it.password.isEmpty()
        }
    }
}
