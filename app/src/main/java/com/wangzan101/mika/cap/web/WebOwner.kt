package com.wangzan101.mika.cap.web

import android.content.Context
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** The process-wide DevTools session entry for web pages, the site registry, the total tab limit and the one page kept in the background: provided by the App and shared by the settings screen and every task. */
interface WebOwner {
    val webDevtools: WebDevtools
    val webSites: SiteRegistry
    /** The total tab limit shared by every task's tab pool. */
    val tabBudget: TabBudget
    /** The page that keeps running in the background after a task ends (at most one page globally). */
    val pageKeeper: PageKeeper

    companion object {
        fun of(context: Context): WebOwner = context.applicationContext as? WebOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement WebOwner to give the browser's shared state")
    }
}
