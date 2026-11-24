package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.JsonHttp
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

/**
 * The part shared by every search provider: send the request, turn common HTTP statuses into failures for the model, read the JSON.
 * Each provider's special statuses are recognized first by [special]: it may be a failure (such as SearXNG's 403) or a result (such as the 422 Jina returns for zero hits).
 */
internal object SearchHttp {
    /** Maximum length of one snippet: the endpoint is untrusted, so a whole page of text must not be stuffed into the model context. */
    private const val MAX_SNIPPET_CHARS = 600

    fun json(body: JsonObject): RequestBody = JsonHttp.json(body)

    suspend fun call(
        client: OkHttpClient,
        request: Request,
        special: (Response) -> WebSearchResult? = { null },
        parse: (JsonObject) -> WebSearchResult,
    ): WebSearchResult = JsonHttp.call(
        client, request, "search", special = special,
        failure = { WebSearchResult.Failure(it.code, it.message, it.retryAfterSeconds) },
        parse = parse,
    )

    fun text(o: JsonObject, name: String): String = JsonHttp.text(o, name)

    /** Results without a URL are dropped; the snippet is shortened. */
    fun hit(title: String, url: String, snippet: String): WebSearchHit? =
        if (url.isBlank()) null else WebSearchHit(title, url, snippet.trim().let(::clip))

    /** Clipped by code point: the snippet comes from an untrusted endpoint, and surrogate pairs such as emoji must not be cut in half. */
    private fun clip(s: String): String =
        if (s.codePointCount(0, s.length) <= MAX_SNIPPET_CHARS) s
        else s.substring(0, s.offsetByCodePoints(0, MAX_SNIPPET_CHARS)) + "…"
}