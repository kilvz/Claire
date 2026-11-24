package com.wangzan101.mika.browser

import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * What was typed into the address bar or the home search box → the URL to open.
 * - Anything with `://` is opened as-is (whether it can be opened is decided later by [WebUrlPolicy.checkNavigable]);
 * - localhost, IPv4 and bracketed IPv6 (optionally with a port and path) get an http:// prefix;
 * - Domain-like input (English letters, digits and hyphens separated by dots, whose last label is at least two letters or starts with xn--, optionally with a port and path) gets an https:// prefix;
 * - Everything else (containing spaces, Chinese, a single word, an email address and the like) is searched with [engine], Bing by default.
 */
object AddressInput {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val TLD = Regex("^([A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")
    private val PORT = Regex("^\\d{1,5}$")

    /** Null when the input is empty (whitespace only). */
    fun resolve(input: String, engine: SearchEngineSpec = SearchEngine.Bing): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return search(s, engine)
        if (SCHEME.containsMatchIn(s)) return s
        val (host, port) = authority(s) ?: return search(s, engine)
        if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return search(s, engine)
        return when {
            host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || IPV6.matches(host) -> "http://$s"
            domain(host) -> "https://$s"
            else -> search(s, engine)
        }
    }

    fun search(query: String, engine: SearchEngineSpec = SearchEngine.Bing): String = engine.searchUrl(query)

    /** The search term when the URL is a search-results page of one of the engines in [engines], otherwise null. */
    fun searchQuery(url: String, engines: List<SearchEngineSpec> = SearchEngine.entries): String? =
        SearchEngineSpec.match(url, engines)?.second

    /** Host name and port from the beginning up to the first / ? #; null when it contains characters such as @ that do not look like a host name. */
    private fun authority(s: String): Pair<String, String?>? {
        val auth = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (auth.isEmpty() || '@' in auth) return null
        if (auth.startsWith("[")) {
            val end = auth.indexOf(']')
            if (end < 0) return null
            val rest = auth.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            return auth.substring(0, end + 1) to rest.removePrefix(":").ifEmpty { null }
        }
        val parts = auth.split(':')
        return when (parts.size) {
            1 -> parts[0] to null
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun domain(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { LABEL.matches(it) } && TLD.matches(labels.last())
    }
}
