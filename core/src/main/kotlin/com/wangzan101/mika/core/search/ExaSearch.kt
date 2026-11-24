package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exa search: `POST https://api.exa.ai/search`, `Authorization: Bearer`, request body `{"query","numResults","contents":{"highlights":true}}`.
 * Only highlights are wanted (fragments relevant to the query, not billed extra), not the full-page text; the snippet is taken from highlights, or from summary or text when they are missing.
 */
class ExaSearch(
    apiKey: String,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val url: HttpUrl = URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey.trim()

    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val body = buildJsonObject {
            put("query", query)
            put("numResults", n)
            putJsonObject("contents") { put("highlights", true) }
        }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").post(SearchHttp.json(body)).build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The Exa response has no results array.")
        return WebSearchResult.Success(results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val highlights = (o["highlights"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }?.filter(String::isNotEmpty).orEmpty()
            val snippet = highlights.joinToString(" … ").ifBlank { SearchHttp.text(o, "summary").ifBlank { SearchHttp.text(o, "text") } }
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), snippet)
        }.take(count))
    }

    companion object {
        const val URL = "https://api.exa.ai/search"
    }
}
