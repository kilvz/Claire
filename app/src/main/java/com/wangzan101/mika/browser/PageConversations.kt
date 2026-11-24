package com.wangzan101.mika.browser

import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.PageOrigin
import com.wangzan101.mika.core.cap.web.WebUrlPolicy

/**
 * Which conversation to continue when asking Claire about a page on the browser page (pure logic). Rules:
 * 1. If something was asked on the same page (same URL, ignoring everything after `#`), continue the most recent conversation, no matter how long ago it was;
 * 2. Otherwise, if something was asked on the same site (same host name) within [SAME_SITE_MS], continue the most recent one — clicking through a single site (search results → product page → cart)
 *    is still the same matter;
 * 3. If neither applies, start a new conversation. The user can choose "new conversation" at any time.
 * Coming back to another page of the same site after a long time is most likely a different matter, hence the time limit in rule 2; the same page has no time limit, since returning to this page means continuing this page's matter.
 */
object PageConversations {
    /** Time limit for "continuing to ask" on the same site: the most recent change must be within this. */
    const val SAME_SITE_MS = 2 * 60 * 60 * 1000L

    /** [all] is the conversation list (most recently modified first, see [com.wangzan101.mika.chat.ConversationStore.list]); [url] is the page being viewed right now. */
    fun pick(all: List<ConversationSummary>, url: String, now: Long): ConversationSummary? {
        val page = pageKey(url)
        val host = WebUrlPolicy.host(url)
        val fromPages = all.filter { it.page != null }.sortedByDescending { it.updatedAt }
        return fromPages.firstOrNull { pageKey(it.page!!.url) == page }
            ?: fromPages.firstOrNull { host != null && it.page!!.host == host && now - it.updatedAt in 0..SAME_SITE_MS }
    }

    /** Records this page as the conversation's origin: URL, host name and title; null when it is not a web page address. */
    fun origin(url: String, title: String): PageOrigin? {
        val host = WebUrlPolicy.host(url)?.takeIf { url.startsWith("http", ignoreCase = true) } ?: return null
        return PageOrigin(url, host, title.trim())
    }

    /** URL used to recognize "the same page": everything after `#` and trailing slashes are removed. */
    fun pageKey(url: String): String = url.substringBefore('#').trimEnd('/')
}
