package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import java.net.URI
import java.net.URLDecoder
import com.wangzan101.mika.core.net.UrlHost

/** A link by which the page wants to jump to an App; [packageName] is present only when it can be parsed out of the link and is a valid package name. */
data class ExternalLink(val url: String, val packageName: String?)

/** URL rules: what can be opened, which links jump to an App, and whether two addresses are the same site. */
object WebUrlPolicy {
    /** The browser's own schemes: they can neither navigate nor jump to an App. */
    private val BROWSER_SCHEMES = setOf("http", "https", "file", "content", "javascript", "data", "about", "blob")

    private val SCHEME_RE = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    /** Web addresses of app stores (host, path prefix): opening them is meant to install or open an App. */
    private val STORE_PAGES = listOf(
        "play.google.com" to "/store/apps",
        "market.android.com" to "/details",
        "apps.apple.com" to "/",
        "itunes.apple.com" to "/",
    )

    /** null = can be opened; otherwise an invalid_arguments explanation for the model. */
    fun checkNavigable(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "the URL is empty"
        val scheme = scheme(u) ?: return "the URL must start with http:// or https://"
        if (external(u) != null) return "this is a link that opens an app, so it is not opened in the browser; when the task needs that app, open it on the phone"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        if (host(u) == null) return "the URL has no domain"
        return null
    }

    /** intent:, custom schemes, and app store links (including store web pages) → links that jump to an App; plain web pages and the browser's own schemes yield null. */
    fun external(url: String): ExternalLink? {
        val u = url.trim()
        val scheme = scheme(u) ?: return null
        when (scheme) {
            "intent" -> {
                val pkg = u.substringAfter("#Intent;", "").split(';')
                    .firstOrNull { it.startsWith("package=") }?.removePrefix("package=")
                return ExternalLink(u, validPackage(pkg))
            }
            "market" -> return ExternalLink(u, validPackage(queryParam(u, "id")))
            "http", "https" -> {
                val h = host(u) ?: return null
                val path = runCatching { URI(u).rawPath }.getOrNull().orEmpty().ifEmpty { "/" }
                if (STORE_PAGES.none { (sh, prefix) -> h == sh && path.startsWith(prefix) }) return null
                return ExternalLink(u, validPackage(queryParam(u, "id")))
            }
        }
        return if (scheme in BROWSER_SCHEMES) null else ExternalLink(u, null)
    }

    /** Lowercase ASCII domain (internationalized domains are converted to the xn-- form), with the port and any leading www. removed; null when it cannot be parsed. */
    fun host(url: String): String? = UrlHost.of(url)

    /** Whether two addresses are the same site; see [UrlHost.sameSite]. */
    fun sameSite(a: String, b: String): Boolean = UrlHost.sameSite(a, b)

    /** The registrable domain; see [UrlHost.registrable]. */
    fun registrable(host: String): String? = UrlHost.registrable(host)

    /** Same origin: scheme, host name, and port all match (a missing port is filled in from the scheme). */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    private fun origin(url: String): Triple<String, String, Int>? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val h = UrlHost.raw(url) ?: return null
        val port = u.port.takeIf { it >= 0 } ?: portOf(u.rawAuthority) ?: when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        return Triple(scheme, h, port)
    }

    private fun portOf(authority: String?): Int? =
        authority?.substringAfterLast('@')?.substringAfterLast(']')?.substringAfter(':', "")?.toIntOrNull()

    private fun validPackage(raw: String?): String? {
        val p = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim() ?: return null
        return p.takeIf(PACKAGE_NAME_RE::matches)
    }

    /** Scheme name (lowercase); forms without a written scheme such as `example.com:8080` or `localhost:3000` do not count. */
    private fun scheme(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!SCHEME_RE.matches(s) || !s.all { it.code < 128 }) return null
        val rest = url.substring(i + 1)
        if ('.' in s || (rest.isNotEmpty() && rest.takeWhile { it != '/' }.all(Char::isDigit) && rest.first().isDigit())) return null
        return s.lowercase()
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringBefore('#').substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")?.takeIf { it.isNotBlank() }
}
