package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One row in "recently opened": a search is shown as "Search: <term>" (the subtitle is the search engine used), while a web page shows its title (falling back to the site name when unreadable) and the site name.
 * [host] is the web page's host name (used to draw the icon) and is null for searches.
 */
data class RecentItem(val url: String, val title: String, val subtitle: String, val host: String?, val search: Boolean, val openedAtMs: Long)

/** One suggestion shown below the input while typing: when [search] is true the typed text is searched with the current search engine, otherwise [url] is opened. */
data class Suggestion(val title: String, val subtitle: String, val url: String, val search: Boolean, val host: String?)

/** Layout logic for the start page: recently opened, typing suggestions, relative time. All pure functions; names are supplied by the caller by host name (see [SiteLooks.face]). */
object StartPageModel {
    val RECENT_SOURCE: String get() = tr(R.string.browser_recent_source)
    /** Maximum number of recently opened entries among the suggestions. */
    const val MAX_RECENT_SUGGESTIONS = 5

    /** Arranges recently opened entries into rows: only the most recent entry per URL (ignoring the part after #), most recent first. */
    fun recentRows(
        pages: List<RecentPage>,
        nameOf: (String) -> String,
        engines: List<SearchEngineSpec> = SearchEngine.entries,
    ): List<RecentItem> {
        val seen = HashSet<String>()
        return pages.sortedByDescending { it.openedAtMs }.filter { seen.add(it.url.trim().substringBefore('#')) }.map { p ->
            val match = SearchEngineSpec.match(p.url, engines)
            if (match != null) {
                RecentItem(p.url, tr(R.string.browser_search_colon, match.second), match.first.displayName, null, true, p.openedAtMs)
            } else {
                val host = WebUrlPolicy.host(p.url)
                val site = host?.let(nameOf) ?: p.url
                RecentItem(p.url, p.title.ifBlank { site }, site, host, false, p.openedAtMs)
            }
        }
    }

    /**
     * Suggestions while typing: the first one is the text itself — opened when it looks like a URL, otherwise searched with [engine]; after that come recently opened entries whose title or URL contains the text (case-insensitive).
     * Empty input yields no suggestions.
     */
    fun suggestions(input: String, recent: List<RecentItem>, engine: SearchEngineSpec = SearchEngine.Bing): List<Suggestion> {
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val target = AddressInput.resolve(q, engine) ?: return emptyList()
        val first = if (engine.queryOf(target) != null) Suggestion(tr(R.string.browser_search_q, q), engine.displayName, target, true, null)
        else Suggestion(q, tr(R.string.browser_open_url), target, false, WebUrlPolicy.host(target))
        val hits = recent.filter { it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .filter { it.url != target }
            .take(MAX_RECENT_SUGGESTIONS)
            .map { Suggestion(it.title, RECENT_SOURCE, it.url, false, it.host) }
        return listOf(first) + hits
    }

    /** Relative time: "just now", "10 minutes ago", "today 11:20", "yesterday", "Oct 21", with the year added when it is not the current year. */
    fun relativeTime(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = nowMs - atMs
        if (diff in 0 until 60_000) return tr(R.string.browser_just_now)
        if (diff in 60_000 until 3_600_000) return trPlural(R.plurals.browser_minutes_ago, (diff / 60_000).toInt())
        val then = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> tr(R.string.browser_today_at, "%02d:%02d".format(then.hour, then.minute))
            days == 1L -> tr(R.string.drawer_yesterday)
            then.year == now.year -> tr(R.string.date_month_day, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
            else -> tr(R.string.date_year_month_day, then.year, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
        }
    }
}
