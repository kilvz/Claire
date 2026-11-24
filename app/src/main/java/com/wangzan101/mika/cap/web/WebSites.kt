package com.wangzan101.mika.cap.web

import android.content.Context
import android.webkit.WebViewDatabase

/** Settings-page operations for the site registry; the registry is the single process-wide instance (provided by the App, shared by the settings page and the tab pool). */
object WebSites {
    fun registry(context: Context): SiteRegistry = WebOwner.of(context).webSites

    /** Logout operations used by the settings page: cookies and web storage of the mika web profile. Call on the main thread. */
    fun login(context: Context): WebLogin = WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry(context))

    /**
     * The settings page's "log out everywhere and clear web data": the cache has to be cleared even with no tabs open, so a temporary
     * invisible tab with the same profile is opened to do the clearing and closed as soon as it is done. Call on the main thread.
     */
    fun logoutAll(context: Context) {
        val tmp = WebTab(context.applicationContext, "clear", "settings")
        try {
            login(context).logoutAll(listOf(tmp.view), WebViewDatabase.getInstance(context))
        } finally {
            tmp.destroy()
        }
    }
}
