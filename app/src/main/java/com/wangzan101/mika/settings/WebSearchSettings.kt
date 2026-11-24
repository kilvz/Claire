package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.search.DeepSeekSearch
import com.wangzan101.mika.core.search.ExaSearch
import com.wangzan101.mika.core.search.JinaSearch
import com.wangzan101.mika.core.search.SearxngSearch
import com.wangzan101.mika.core.search.WebSearch
import com.wangzan101.mika.core.search.WebSearchProvider
import com.wangzan101.mika.core.search.YouSearch
import java.util.Base64

/** Per-provider settings for a search service: the endpoint (only for the few providers that need an address) and the key. */
data class WebSearchEntry(val endpoint: String = "", val apiKey: String = "")

/** Whether Google Search grounding is available for the current main model. */
data class GroundingStatus(
    val available: Boolean = false,
    val providerName: String? = null,
)

/**
 * Web search settings. Disabled by default; the service is only created once the user has explicitly picked a provider, filled in what it needs, and enabled it.
 * Each provider's endpoint and key are stored separately ([entries]), so switching providers never sends one provider's key to another.
 */
data class WebSearchConfig(
    val enabled: Boolean = false,
    val provider: WebSearchProvider = WebSearchProvider.You,
    val entries: Map<WebSearchProvider, WebSearchEntry> = emptyMap(),
    val grounding: GroundingStatus = GroundingStatus(),
) {
    val current: WebSearchEntry get() = entries[provider] ?: WebSearchEntry()

    /**
     * The problem with the currently selected provider at this moment; null when it can be saved. Providers that require
     * a key can never be saved without one (whether enabled or not); the endpoint is only checked when enabled.
     */
    val problem: WebSearchProvider.Problem? get() = when {
        provider.keyRequired && current.apiKey.isBlank() -> WebSearchProvider.Problem.Key
        enabled -> provider.problem(current.endpoint, current.apiKey, grounding.available)
        else -> null
    }
}

class WebSearchSettings(
    private val prefs: SharedPreferences,
    private val cipher: KeyCipher = KeystoreKeyCipher("mika-web-search-key"),
    private val groundingStatus: () -> GroundingStatus = { GroundingStatus() },
    private val googleSearch: () -> WebSearch? = { null },
) {
    fun get(): WebSearchConfig {
        val grounding = groundingStatus()
        val stored = WebSearchProvider.entries.associateWith { p ->
            WebSearchEntry(
                if (p.needsEndpoint) prefs.getString(endpointKey(p), null).orEmpty() else "",
                if (p.takesKey) decrypt(prefs.getString(apiKeyKey(p), null)) else "",
            )
        }
        val enabled = prefs.getBoolean(ENABLED, false)
        val saved = WebSearchProvider.of(prefs.getString(PROVIDER, null))
        if (saved != null) return WebSearchConfig(enabled, saved, stored, grounding)
        // Legacy settings from before the upgrade: back then there was only one "search endpoint URL + optional key". If the URL is Exa's or Jina's official endpoint it is assigned to that provider and the key follows it
        // (they do not accept the old request style, so this makes search work right after upgrading). Other URLs have no matching provider: they are mapped to You.com but left disabled and the old key is dropped,
        // so the user does not unknowingly send their search queries to a different provider and leak a key meant for another one. The next save rewrites everything in the new shape and the two old keys are removed along the way.
        val legacyEndpoint = prefs.getString(LEGACY_ENDPOINT, null).orEmpty()
        val official = legacyOfficial(legacyEndpoint)
        return when {
            official != null -> WebSearchConfig(
                enabled,
                official,
                stored + (official to WebSearchEntry(apiKey = decrypt(prefs.getString(LEGACY_API_KEY, null)))),
                grounding,
            )
            legacyEndpoint.isNotBlank() -> WebSearchConfig(false, WebSearchProvider.You, stored, grounding)
            grounding.available -> WebSearchConfig(false, WebSearchProvider.Google, stored, grounding)
            else -> WebSearchConfig(enabled, WebSearchProvider.You, stored, grounding)
        }
    }

    @Synchronized
    fun set(config: WebSearchConfig) {
        require(config.problem == null) { "Invalid web search configuration: ${config.problem}" }
        val edit = prefs.edit().putBoolean(ENABLED, config.enabled).putString(PROVIDER, config.provider.wire)
            .remove(LEGACY_ENDPOINT).remove(LEGACY_API_KEY)
        for (p in WebSearchProvider.entries) {
            val entry = config.entries[p] ?: continue
            if (p.needsEndpoint) edit.putString(endpointKey(p), entry.endpoint.trim())
            if (p.takesKey) {
                val key = entry.apiKey.trim()
                if (key.isEmpty()) edit.remove(apiKeyKey(p))
                else edit.putString(apiKeyKey(p), Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
            }
        }
        check(edit.commit()) { "Cannot save web search settings" }
    }

    /** Rereads the settings for every task; returns null when disabled, incomplete, or the config is corrupt, in which case the task only uses the browser. */
    fun service(): WebSearch? {
        val config = get()
        if (!config.enabled || config.problem != null) return null
        val entry = config.current
        return when (config.provider) {
            WebSearchProvider.Google -> if (config.grounding.available) googleSearch() else null
            WebSearchProvider.You -> YouSearch(entry.apiKey)
            WebSearchProvider.Jina -> JinaSearch(entry.apiKey)
            WebSearchProvider.Exa -> ExaSearch(entry.apiKey)
            WebSearchProvider.DeepSeek -> DeepSeekSearch(entry.apiKey)
            WebSearchProvider.SearXNG -> SearxngSearch(checkNotNull(WebSearch.parseEndpoint(entry.endpoint)))
        }
    }

    /** Whether the URL entered before the upgrade is Exa's or Jina's official endpoint. */
    private fun legacyOfficial(endpoint: String): WebSearchProvider? = when (WebSearch.parseEndpoint(endpoint)?.host) {
        "api.exa.ai" -> WebSearchProvider.Exa
        "s.jina.ai" -> WebSearchProvider.Jina
        else -> null
    }

    private fun decrypt(encoded: String?): String = encoded?.let {
        runCatching { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }.getOrDefault("")
    }.orEmpty()

    companion object {
        private const val PREFS = "web_search"
        private const val ENABLED = "enabled"
        private const val PROVIDER = "provider"
        // Before the upgrade there was only one endpoint URL and one key; these are read only during migration and removed on save
        private const val LEGACY_ENDPOINT = "endpoint"
        private const val LEGACY_API_KEY = "api_key_encrypted"

        private fun endpointKey(p: WebSearchProvider) = "endpoint_${p.wire}"
        private fun apiKeyKey(p: WebSearchProvider) = "api_key_encrypted_${p.wire}"

        fun get(context: Context): WebSearchSettings {
            val app = context.applicationContext
            val providers = { (app as? ProviderStore.Owner)?.providers }
            return WebSearchSettings(
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                groundingStatus = { providers()?.groundingStatus() ?: GroundingStatus() },
                googleSearch = { providers()?.googleGroundingSearch() },
            )
        }
    }
}
