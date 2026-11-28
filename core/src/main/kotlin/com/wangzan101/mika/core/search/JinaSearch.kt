package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Jina search: `POST https://s.jina.ai/`, request body `{"q","num"}`, `Authorization: Bearer`;
 * with `X-Respond-With: no-content` only the title, URL and snippet are fetched, so the full text of each page is not pulled back. Results are in `data[]`, the snippet is taken from description, or from content when it is missing.
 * When nothing is found, Jina returns 422 (measured: `{"code":422,"status":42206,"message":"No search results available for query …"}`),
 * which means "no results", not an error; any other 422 is still treated as an error.
 */
class JinaSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        SearchHttp.unusableKey(key)?.let { return it }
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").header("X-Respond-With", "no-content")
            .post(SearchHttp.json(buildJsonObject { put("q", query); put("num", n) })).build()
        return SearchHttp.call(client, request, special = ::noResults) { root -> parse(root, n) }
    }

    private fun noResults(response: Response): WebSearchResult? {
        if (response.code != 422) return null
        val body = runCatching { Json.parseToJsonElement(response.peekBody(MAX_ERROR_BODY).string()) as? JsonObject }.getOrNull() ?: return null
        val status = (body["status"] as? JsonPrimitive)?.intOrNull
        val message = (body["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return if (status == NO_RESULTS_STATUS || message.startsWith("No search results", ignoreCase = true)) WebSearchResult.Success(emptyList()) else null
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val data = root["data"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The Jina search response has no data array.")
        return WebSearchResult.Success(data.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "description").ifBlank { SearchHttp.text(o, "content") })
        }.take(count))
    }

    companion object {
        const val URL = "https://s.jina.ai/"
        /** The status in the error body when Jina returns zero hits. */
        private const val NO_RESULTS_STATUS = 42206
        private const val MAX_ERROR_BODY = 16_384L
    }
}
