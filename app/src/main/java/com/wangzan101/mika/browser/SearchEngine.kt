package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * A search engine: the start page and address bar show the name ([displayName]), a search term is turned into a search URL ([searchUrl]),
 * and the search term is read back from the URL ([queryOf], used to show the searched term in "recently opened" and in the address bar).
 * The built-in ones are [SearchEngine]; the ones the user adds are [CustomSearchEngine].
 */
sealed interface SearchEngineSpec {
    val id: String
    val displayName: String

    fun searchUrl(query: String): String

    /** The searched term when the URL is this engine's search-results page, otherwise null. */
    fun queryOf(url: String): String?

    companion object {
        /** Which engine in [engines] the URL is a search-results page of, and what was searched; null when it is none of them. Decodes only once. */
        fun match(url: String, engines: List<SearchEngineSpec>): Pair<SearchEngineSpec, String>? =
            engines.firstNotNullOfOrNull { e -> e.queryOf(url)?.let { e to it } }

        /** Which engine in [engines] the URL is a search-results page of; null when it is none of them. */
        fun of(url: String, engines: List<SearchEngineSpec>): SearchEngineSpec? = match(url, engines)?.first
    }
}

/**
 * The built-in search engines. Each is just a prefix: the search URL is the prefix plus the encoded term, and conversely the searched term is recognized from the URL.
 * Names follow the UI language: most languages write the brand names as-is, while Simplified Chinese uses their localized forms.
 */
enum class SearchEngine(override val id: String, private val nameRes: Int, private val prefix: String) : SearchEngineSpec {
    Bing("bing", R.string.browser_engine_bing, "https://www.bing.com/search?q="),
    Google("google", R.string.browser_engine_google, "https://www.google.com/search?q="),
    DuckDuckGo("duckduckgo", R.string.browser_engine_duckduckgo, "https://duckduckgo.com/?q="),
    Baidu("baidu", R.string.browser_engine_baidu, "https://www.baidu.com/s?wd=");

    override val displayName: String get() = tr(nameRes)

    override fun searchUrl(query: String): String = prefix + URLEncoder.encode(query.trim(), "UTF-8")

    override fun queryOf(url: String): String? {
        if (!url.startsWith(prefix)) return null
        val q = url.removePrefix(prefix).substringBefore('&').substringBefore('#')
        return decode(q)
    }

    companion object {
        fun fromId(id: String?): SearchEngine? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A search engine added by the user: a URL template in which [PLACEHOLDER] stands for the search term, for example `https://example.com/search?q=%s`.
 * The template must contain exactly one placeholder; the generated URL starts with the first half of the template and continues (if anything follows) with its second half.
 * When reading the search term back from a URL, if the second half is empty the term runs up to `&` or `#`; otherwise it runs up to where the second half appears.
 */
@ConsistentCopyVisibility
data class CustomSearchEngine private constructor(
    override val id: String,
    private val name: String,
    val template: String,
) : SearchEngineSpec {
    override val displayName: String get() = name

    private val prefix = template.substringBefore(PLACEHOLDER)
    private val suffix = template.substringAfter(PLACEHOLDER, "")

    override fun searchUrl(query: String): String =
        template.replace(PLACEHOLDER, URLEncoder.encode(query.trim(), "UTF-8"))

    override fun queryOf(url: String): String? {
        if (!url.startsWith(prefix)) return null
        val rest = url.removePrefix(prefix)
        val raw = if (suffix.isEmpty()) {
            rest.substringBefore('&').substringBefore('#')
        } else {
            rest.substring(0, suffixStart(rest) ?: return null)
        }
        return decode(raw)
    }

    /** Position where the template's second half appears: it must be followed by the end, `&` or `#`, so that a search term which happens to contain that segment is not cut at the wrong place. */
    private fun suffixStart(rest: String): Int? {
        var from = 0
        while (true) {
            val i = rest.indexOf(suffix, from)
            if (i < 0) return null
            val after = i + suffix.length
            if (after == rest.length || rest[after] == '&' || rest[after] == '#') return i
            from = i + 1
        }
    }

    companion object {
        const val PLACEHOLDER = "%s"

        /** Creates one from the name and URL entered by the user; null when the name is empty, the URL is not http(s), or there is not exactly one placeholder. */
        fun create(name: String, template: String): CustomSearchEngine? =
            create("custom:" + UUID.randomUUID(), name, template)

        /** Restores from stored content; null when invalid (an older version, or the file was corrupted). */
        internal fun create(id: String, name: String, template: String): CustomSearchEngine? {
            val n = name.trim()
            val t = template.trim()
            if (n.isEmpty() || id.isBlank() || !valid(t)) return null
            return CustomSearchEngine(id, n, t)
        }

        private fun valid(template: String): Boolean {
            if (Regex(PLACEHOLDER).findAll(template).count() != 1) return false
            if (!template.startsWith("http://") && !template.startsWith("https://")) return false
            if (template.any { it.isWhitespace() }) return false
            val hostStart = template.indexOf("://") + 3
            val authorityEnd = listOf(template.indexOf('/', hostStart), template.indexOf('?', hostStart), template.indexOf('#', hostStart))
                .filter { it >= 0 }.minOrNull() ?: template.length
            if (template.indexOf(PLACEHOLDER) < authorityEnd) return false
            return runCatching { URI(template.replace(PLACEHOLDER, "mika")).rawAuthority?.isNotBlank() == true }.getOrDefault(false)
        }
    }
}

private fun decode(raw: String): String? =
    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
