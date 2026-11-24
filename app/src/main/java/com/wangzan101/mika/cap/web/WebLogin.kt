package com.wangzan101.mika.cap.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * Signing out of a website: best effort. The system APIs can only read Cookie names by URL, not paths, domains or HttpOnly details,
 * so for every Cookie read for this host name and each parent domain level (down to the registrable domain), write one expiry record for "host only" and one for "whole domain",
 * then clear the web storage of those origins. On a few sites the login may remain; use [logoutAll] to clear thoroughly.
 * [cookies] and [storage] come from the mika web profile (see [WebTab.cookieManager]); called on the main thread.
 */
class WebLogin(private val cookies: CookieManager, private val storage: WebStorage, private val registry: SiteRegistry?) {

    /** Returns the number of expired Cookies (counted by name). */
    fun logout(host: String): Int {
        val h = WebUrlPolicy.host("https://${host.trim()}") ?: return 0
        var expired = 0
        for (d in domainsFor(h)) {
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://$d/"
                val names = cookieNames(cookies.getCookie(url))
                for (name in names) expiryLines(name, d).forEach { cookies.setCookie(url, it) }
                if (scheme == "https") expired += names.size
                storage.deleteOrigin("$scheme://$d")
            }
            storage.deleteOrigin("https://www.$d")
        }
        cookies.flush()
        registry?.remove(h)
        return expired
    }

    /**
     * Sign out everywhere and clear web data: all Cookies (including session Cookies), all web storage, the cache of [views] (the cache is shared by the whole web profile,
     * so one view clears all of it), the HTTP auth credentials remembered in [database], and the site registry is emptied. Still best effort.
     */
    fun logoutAll(views: List<WebView> = emptyList(), database: WebViewDatabase? = null) {
        cookies.removeSessionCookies(null)
        cookies.removeAllCookies(null)
        cookies.flush()
        storage.deleteAllData()
        views.forEach { runCatching { it.clearCache(true) } }
        database?.let { runCatching { it.clearHttpAuthUsernamePassword() } }
        registry?.clear()
    }

    companion object {
        /** The host name and its successive parent domains, down to the registrable domain; if the registrable domain cannot be determined, only the host itself. */
        fun domainsFor(host: String): List<String> {
            val top = WebUrlPolicy.registrable(host) ?: return listOf(host)
            val out = mutableListOf<String>()
            var d = host
            while (true) {
                out += d
                if (d == top || !d.contains('.')) break
                d = d.substringAfter('.')
            }
            return out
        }

        /** Names in a Cookie header, de-duplicated, order preserved. */
        fun cookieNames(header: String?): List<String> =
            header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

        /** The two forms that expire one Cookie: host-only, and whole domain. */
        fun expiryLines(name: String, domain: String) = listOf(
            "$name=; Max-Age=0; Path=/",
            "$name=; Max-Age=0; Path=/; Domain=.$domain",
        )
    }
}
