package com.wangzan101.mika.core.net

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/** Hostnames and sites within URLs: how "which website" is expressed (screen reading, run status, knowledge matching, skill drafts) is all derived from this. */
object UrlHost {
    /** Common two-label public suffixes. */
    private val TWO_PART_SUFFIXES = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.mo", "com.sg", "com.au", "com.br",
        "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
    )

    /** Hosting platforms: each user's subdomain is a different site, so the platform domain itself counts as a public suffix. */
    private val PRIVATE_SUFFIXES = setOf(
        "github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "herokuapp.com",
        "amazonaws.com", "workers.dev", "web.app", "appspot.com", "firebaseapp.com",
    )

    /** Words that commonly serve as the second-label public suffix under a country-code TLD. */
    private val GENERIC_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

    /** Lowercase ASCII hostname (internationalized domains converted to xn-- form), with the port and a leading www. stripped; null when it cannot be parsed. */
    fun of(url: String): String? = raw(url)?.removePrefix("www.")?.ifEmpty { null }

    /**
     * Whether two addresses are the same site: identical hostnames (ignoring www), or both under the same registrable domain that can be determined.
     * Scheme and port are not considered. Suffixes that cannot be determined confidently are always treated as different sites.
     */
    fun sameSite(a: String, b: String): Boolean {
        val ha = of(a) ?: return false
        val hb = of(b) ?: return false
        if (ha == hb) return true
        val sa = registrable(ha) ?: return false
        return sa == registrable(hb)
    }

    internal fun raw(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        // A URI whose hostname contains non-ASCII characters yields no host, so take it from the authority
        val h = u.host ?: u.rawAuthority?.let(::hostOfAuthority) ?: return null
        val ascii = runCatching { IDN.toASCII(h.trimEnd('.')) }.getOrNull()?.lowercase() ?: return null
        return ascii.ifEmpty { null }
    }

    private fun hostOfAuthority(authority: String): String? {
        val a = URLDecoder.decode(authority.substringAfterLast('@'), "UTF-8")
        if (a.startsWith("[")) return a.substringBefore(']') + "]"
        return a.substringBefore(':').ifEmpty { null }
    }

    /** The registrable domain (the public suffix plus one more label); null when the suffix is uncertain or the hostname is the suffix itself. */
    fun registrable(host: String): String? {
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        fun tail(n: Int) = labels.takeLast(n).joinToString(".")
        val suffixLen = when {
            tail(2) in PRIVATE_SUFFIXES || tail(2) in TWO_PART_SUFFIXES -> 2
            labels.last().length == 2 && labels.size >= 2 &&
                (labels[labels.size - 2].length <= 3 || labels[labels.size - 2] in GENERIC_SECOND_LEVEL) -> 2
            else -> 1
        }
        if (labels.size <= suffixLen) return null
        return tail(suffixLen + 1)
    }
}
