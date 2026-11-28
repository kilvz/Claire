package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * You.com web search. With no key it uses the free `GET https://api.you.com/v1/agents/search?query=&count=` (no account needed, 100 times a day);
 * with a key it uses `POST https://ydc-index.io/v1/search`, with X-API-Key. Both return the same `{"results":{"web":[…]}}`,
 * the snippet is taken from description, or from the first entry of snippets when it is missing. When nothing is found the API still returns 200, but `results` has no `web` (`{"results":{}}`),
 * which means "no results", not an error.
 */
class YouSearch(
    apiKey: String? = null,
    private val client: OkHttpClient = NetProxy.shared.client(),
    private val keylessUrl: HttpUrl = KEYLESS_URL.toHttpUrl(),
    private val keyedUrl: HttpUrl = KEYED_URL.toHttpUrl(),
) : WebSearch {
    private val key = apiKey?.trim()?.takeIf(String::isNotEmpty)

    override suspend fun search(query: String, count: Int): WebSearchResult {
        if (key != null) SearchHttp.unusableKey(key)?.let { return it }
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        val request = if (key == null) {
            Request.Builder().url(keylessUrl.newBuilder().addQueryParameter("query", query).addQueryParameter("count", n.toString()).build()).get()
        } else {
            Request.Builder().url(keyedUrl).header("X-API-Key", key)
                .post(SearchHttp.json(buildJsonObject { put("query", query); put("count", n) }))
        }.build()
        return SearchHttp.call(client, request) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonObject
            ?: return WebSearchResult.Failure("invalid_response", "The search service response has no results object.")
        val web = when (val w = results["web"]) {
            null, JsonNull -> return WebSearchResult.Success(emptyList())
            is JsonArray -> w
            else -> return WebSearchResult.Failure("invalid_response", "The search service response has a results.web that is not an array.")
        }
        return WebSearchResult.Success(web.take(count).mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val snippets = (o["snippets"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val snippet = SearchHttp.text(o, "description").ifBlank { snippets.firstOrNull().orEmpty() }
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), snippet)
        })
    }

    companion object {
        const val KEYLESS_URL = "https://api.you.com/v1/agents/search"
        const val KEYED_URL = "https://ydc-index.io/v1/search"
    }
}
