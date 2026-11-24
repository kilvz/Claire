package com.wangzan101.mika.core.search

import com.wangzan101.mika.core.net.NetProxy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A self-hosted SearXNG instance: `GET {instance address}/search?q=&format=json`, results are in `results[]`, the snippet is taken from content.
 * Instances do not enable JSON output by default and return 403 when it is off; json must be added to `search.formats` in settings.yml. This is unrelated to the key and is explained separately.
 * SearXNG does not return results by count, so only the first count entries are taken here.
 */
class SearxngSearch(
    private val base: HttpUrl,
    private val client: OkHttpClient = NetProxy.shared.client(),
) : WebSearch {
    override suspend fun search(query: String, count: Int): WebSearchResult {
        val n = count.coerceIn(1, WebSearch.MAX_COUNT)
        // The instance address is what the user enters; one that already ends in /search is accepted too
        // With /search/ the last pathSegments entry is an empty string, so taking the last non-empty segment avoids appending another /search and producing /search/search
        val path = if (base.pathSegments.lastOrNull { it.isNotEmpty() } == "search") base.newBuilder() else base.newBuilder().addPathSegment("search")
        val url = path.addQueryParameter("q", query).addQueryParameter("format", "json").build()
        return SearchHttp.call(client, Request.Builder().url(url).get().build(), special = { response ->
            if (response.code == 403) WebSearchResult.Failure(
                "json_disabled",
                "The SearXNG instance refused JSON output (HTTP 403); its owner must add json to search.formats in settings.yml. Use the browser meanwhile.",
            ) else null
        }) { root -> parse(root, n) }
    }

    private fun parse(root: JsonObject, count: Int): WebSearchResult {
        val results = root["results"] as? JsonArray
            ?: return WebSearchResult.Failure("invalid_response", "The SearXNG response has no results array.")
        return WebSearchResult.Success(results.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            SearchHttp.hit(SearchHttp.text(o, "title"), SearchHttp.text(o, "url"), SearchHttp.text(o, "content"))
        }.take(count))
    }
}
