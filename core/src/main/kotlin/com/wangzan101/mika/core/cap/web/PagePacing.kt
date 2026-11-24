package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.net.UrlHost
import kotlin.random.Random
import com.wangzan101.mika.core.screen.uistate.PlacePace

/**
 * The rhythm of paging down a website one page at a time (pure logic, touches neither the clock nor the page): a person who opens a page always looks at it for a while before turning to the next page and opening the next item.
 * Each time new page content arrives ([noteLoad]: a new address was opened, or while scrolling down the page itself pulled in another batch of content), how long this page should dwell is decided from how many pages this site has recently seen;
 * before any later action that changes the page (opening a URL on the same site, clicking, going back, scrolling down, pressing a key) [waitBefore] is asked first: if this page has not dwelled long enough, the remaining time is topped up.
 *
 * - The first page on a site does not dwell (having just arrived at the site, the first action proceeds as usual);
 * - From the second page on, the dwell duration is drawn at random from [Limits.firstDwellMs], and the more pages were seen within the most recent [Limits.windowMs], the more is added to both bounds ([Limits.perPageMs]),
 *   never exceeding [Limits.maxDwellMs];
 * - The dwell counts from the moment that page arrived, so time the model spends thinking or reading the page counts towards it, and only the shortfall is topped up;
 * - Sites are recognized by registrable domain (www.example.com and m.example.com are the same); going to another site does not wait, and there the count starts from the first page.
 * - When site knowledge says this site limits how fast it may be browsed ([PlacePace.CAREFUL]), pages of this site dwell by the [careful] ranges (longer).
 * - When a page cap is given ([Limits.pageCap]), a task brings at most that many pages from the same site, and once it is reached [capReached] is true and the caller stops changing pages;
 *   it is unset by default (hard-stopping to ask the user would leave reading, summarizing and unattended tasks unfinished, so the risk of being blocked is pressed down by dwelling instead);
 *   the count restarts after the user has answered ([userAnswered]). The cap governs browsing only: once this task has performed an outward action on this site ([outwardDone], sending, submitting and the like),
 *   no cap applies to this site for the rest of the task (bulk outreach and submissions have to reach the site's own limits), while dwelling carries on as before.
 *
 * [Off] never waits and sets no page cap (used by tests and by callers that need no rhythm).
 */
class PagePacing(private val random: Random?, val limits: Limits = Limits(), val careful: Limits = Limits.CAREFUL) {
    /** Durations are in milliseconds. */
    data class Limits(
        /** Dwell range for the second page on a site. */
        val firstDwellMs: LongRange = 4_000L..7_000L,
        /** How much is added to each of the lower and upper bounds per additional page (lower, upper). */
        val perPageMs: Pair<Long, Long> = 600L to 1_000L,
        /** Upper bound on the dwell: no page ever exceeds it. */
        val maxDwellMs: Long = 12_000L,
        /** Time window used to count "how many pages were recently seen". */
        val windowMs: Long = 10 * 60_000L,
        /** Maximum number of pages from the same site within a task (between user answers); null means no cap (the default). */
        val pageCap: Int? = null,
    ) {
        companion object {
            /** Sites that limit how fast they may be browsed: each page dwells longer. */
            val CAREFUL = Limits(firstDwellMs = 10_000L..16_000L, perPageMs = 600L to 1_000L, maxDwellMs = 30_000L)
        }
    }

    private class Load(val site: String, val atMs: Long, var dwellMs: Long, val limits: Limits)

    private val loads = ArrayDeque<Load>()

    /** How many pages each site has brought in this task (since the last user answer); not cleared by the time window. */
    private val pages = HashMap<String, Int>()

    /** Sites on which an outward action was performed in this task: no page cap applies to them any more; user answers do not clear them either. */
    private val outward = HashSet<String>()

    /** Whether pacing is active (not [Off]). */
    val paced: Boolean get() = random != null

    /**
     * A site brought in new page content at [site] (a host name or a URL both work); returns the dwell duration decided for this page.
     * [pace] is what site knowledge says about how fast this site may be browsed.
     */
    @Synchronized
    fun noteLoad(site: String, nowMs: Long, pace: PlacePace = PlacePace.NORMAL): Long {
        if (random == null) return 0
        val key = key(site) ?: return 0
        prune(nowMs)
        val l = limitsFor(pace)
        val seen = loads.count { it.site == key }
        val dwell = if (seen == 0) 0 else dwellFor(seen + 1, l)
        loads.addLast(Load(key, nowMs, dwell, l))
        pages[key] = (pages[key] ?: 0) + 1
        return dwell
    }

    /** How many pages [site] has brought in this task (since the last user answer). */
    @Synchronized
    fun pagesOn(site: String): Int = key(site)?.let { pages[it] } ?: 0

    /** The page cap according to [pace]; null means none is set. */
    fun capFor(pace: PlacePace): Int? = limitsFor(pace).pageCap

    /** Pages from [site] have reached the cap: one has to stop before changing pages again (and look only once the user agrees). Always false for sites with an outward action done ([outwardDone]) and for [Off]. */
    @Synchronized
    fun capReached(site: String, pace: PlacePace = PlacePace.NORMAL): Boolean =
        random != null && key(site)?.let { it in outward } != true && capFor(pace)?.let { pagesOn(site) >= it } == true

    /** This task performed an outward action on [site] (sending, submitting): no page cap applies to this site for the rest of the time. */
    @Synchronized
    fun outwardDone(site: String) {
        key(site)?.let(outward::add)
    }

    /** The user has answered (replied to a follow-up question, handed control back): page counts for all sites restart from scratch. */
    @Synchronized
    fun userAnswered() = pages.clear()

    /**
     * How long still to wait before an action on [site] that may change the page: the remaining time when the most recent page came from this site and has not dwelled long enough, otherwise 0.
     * Always 0 for [Off].
     */
    @Synchronized
    fun waitBefore(site: String?, nowMs: Long): Long {
        if (random == null) return 0
        val key = site?.let(::key) ?: return 0
        val last = loads.lastOrNull() ?: return 0
        if (last.site != key) return 0
        return (last.dwellMs - (nowMs - last.atMs)).coerceIn(0, last.limits.maxDwellMs)
    }

    /** The most recent page on [site] has been dwelled for (the caller finished waiting): actions after this page no longer wait. */
    @Synchronized
    fun served(site: String) {
        val key = key(site) ?: return
        loads.lastOrNull()?.takeIf { it.site == key }?.dwellMs = 0
    }

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    /** Dwell duration for page [n] (n ≥ 2) according to [l]. */
    private fun dwellFor(n: Int, l: Limits): Long {
        val r = random ?: return 0
        val extra = (n - 2).toLong()
        val lo = (l.firstDwellMs.first + extra * l.perPageMs.first).coerceAtMost(l.maxDwellMs)
        val hi = (l.firstDwellMs.last + extra * l.perPageMs.second).coerceAtMost(l.maxDwellMs)
        return if (lo >= hi) hi else r.nextLong(lo, hi + 1)
    }

    private fun prune(nowMs: Long) {
        while (loads.isNotEmpty() && nowMs - loads.first().atMs > limits.windowMs) loads.removeFirst()
    }

    private fun key(site: String): String? {
        val host = UrlHost.of(if ("://" in site) site else "https://$site") ?: return null
        return UrlHost.registrable(host) ?: host
    }

    companion object {
        /** Never waits. */
        val Off = PagePacing(null)

        /** Pacing like a person browsing web pages; [random] may be swapped for a fixed seed (tests). */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            PagePacing(random, limits, careful)
    }
}
