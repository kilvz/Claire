package com.wangzan101.mika.core.search

/**
 * Which service provides web search. Each provider has its own request style and response format with no common convention, so they are implemented per provider and there is no "custom endpoint":
 * to connect another search, self-host SearXNG (which itself aggregates dozens of search engines), or connect an MCP server that offers a search tool.
 * The hosted services have fixed addresses and only need a key; SearXNG needs the instance address and no key. [wire] is stored in settings and can only be added to; it cannot be renamed or removed.
 */
enum class WebSearchProvider(val wire: String) {
    /** Google Search grounding via Gemini API (`tools: [{googleSearch: {}}]`). */
    Google("google"),
    /** You.com: with no key the free endpoint is used (100 times a day); with a key the paid endpoint is used. */
    You("you"),
    /** Jina search (s.jina.ai): a key is required. */
    Jina("jina"),
    /** Exa search (api.exa.ai): a key is required. */
    Exa("exa"),
    /** DeepSeek search (api.deepseek.com/v1/tools/search): a key is required, and it is the same DeepSeek API Key used for chat. */
    DeepSeek("deepseek"),
    /** A self-hosted SearXNG instance: enter the instance address, no key; the instance must have JSON output enabled. */
    SearXNG("searxng");

    /** Requires the user to enter an address: the instance address of SearXNG. */
    val needsEndpoint: Boolean get() = this == SearXNG

    /** Has a key field. */
    val takesKey: Boolean get() = this != SearXNG && this != Google

    /** Cannot be used without a key, and cannot be saved without one either. */
    val keyRequired: Boolean get() = this == Jina || this == Exa || this == DeepSeek

    /** Whether this provider can be used given [endpoint], [apiKey] and [groundingAvailable]; null when it can. */
    fun problem(endpoint: String, apiKey: String, groundingAvailable: Boolean = true): Problem? = when {
        this == Google && !groundingAvailable -> Problem.UnsupportedProvider
        needsEndpoint && WebSearch.parseEndpoint(endpoint) == null -> Problem.Endpoint
        keyRequired && apiKey.isBlank() -> Problem.Key
        else -> null
    }

    enum class Problem { Endpoint, Key, UnsupportedProvider }

    companion object {
        /** The value stored in settings; null when it is not recognized. */
        fun of(wire: String?): WebSearchProvider? = entries.firstOrNull { it.wire == wire }
    }
}
