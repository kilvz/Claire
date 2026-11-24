package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.run.waitOrSteer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.wangzan101.mika.core.screen.uistate.DwellInterrupted
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.screen.uistate.PageLimitReached
import com.wangzan101.mika.core.screen.uistate.PlacePace

/**
 * Dwell within one task according to [PagePacing]: a new page is recognized from the address seen for the page ([seen]), and when scrolling down the page itself pulls in another batch of content ([loadedMore])
 * that also counts as a page; before an action that changes the page, [before] tops up the time this page has not dwelled yet, and how long was dwelled is handed to [onWait] (recorded as the wait of this call).
 * [pace] supplies, per host name, what site knowledge says about how fast this site may be browsed.
 * When [idle] is given and the action is not done by screen coordinates (a port was passed in), the page is scrolled lightly a few times during the dwell as scheduled ([WebPort.idleScroll]), and the scroll count is handed to [onIdleScroll].
 * When pages from the same site reach the task's cap, [before] stops waiting and throws [PageLimitReached]: the caller does not perform the action;
 * the cap governs browsing only: an outward action in progress (sending, submitting, between [outwardStart] and [outwardEnd]) is not blocked,
 * and once an outward action has succeeded on this site in this task, no cap is set any more.
 * If the user interjects during the dwell ([waitOrSteer]), [before] stops waiting and throws [DwellInterrupted]: the caller likewise does not perform the action;
 * the part of the dwell this page missed does not count as dwelled, and is topped up again before the next page change.
 */
internal class PageDwell(
    private val pacing: PagePacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** Called after a pause (the argument is how many milliseconds it lasted). */
    var onWait: (Long) -> Unit = {}

    /** Called after the page was scrolled lightly during a dwell (the argument is how many scrolls happened). */
    var onIdleScroll: (Int) -> Unit = {}

    /** Called when a dwell scrolled the page lightly and an action is about to follow (it re-reads the pre-action state so the scrolling is not taken as the action's effect). */
    var afterIdle: suspend () -> Unit = {}

    private var lastUrl: String? = null

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** The page's current address was observed (screen read, result of opening a page): a difference from the last one seen (ignoring what follows #) means a new page has come. */
    @Synchronized
    fun seen(url: String?) {
        val u = url?.substringBefore('#')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
        if (u == lastUrl) return
        lastUrl = u
        pacing.noteLoad(u, clock(), paceOf(u))
    }

    /** The address did not change but the page pulled in another batch of content by itself (scrolling down to load more). */
    @Synchronized
    fun loadedMore() {
        lastUrl?.let { pacing.noteLoad(it, clock(), paceOf(it)) }
    }

    /** The user has answered: page counts for all sites are recomputed. */
    fun userAnswered() = pacing.userAnswered()

    /** The site (registrable domain) of the outward action in progress; null if there is none. */
    @Volatile private var outwardNow: String? = null

    /** An outward action is about to be performed on [site] (a site host name): this one is not blocked by the page cap. */
    fun outwardStart(site: String) { outwardNow = siteKey(site) }

    /** The outward action has finished; when [done] (it succeeded), no page cap applies to this site any more. */
    fun outwardEnd(site: String, done: Boolean) {
        outwardNow = null
        if (done) pacing.outwardDone(site)
    }

    /**
     * Before changing the page: top up whatever this page has not dwelled yet; [target] is the URL about to be opened (when opening a page), and when it is not given the site of the current page is used. Returns how long the pause lasted.
     * When [port] is given (the action is not done by screen coordinates), the page may be scrolled lightly a few times during the dwell. With [gate], the page cap is checked first, and [PageLimitReached] is thrown once it is reached.
     */
    suspend fun before(target: String? = null, port: WebPort? = null, gate: Boolean = true): Long {
        val site = target ?: synchronized(this) { lastUrl } ?: return 0
        val p = paceOf(site)
        if (gate && siteKey(site) != outwardNow && pacing.capReached(site, p)) {
            throw PageLimitReached(hostOf(site), pacing.pagesOn(site), pacing.capFor(p) ?: 0, PageLimitReached.Scope.WEB_PAGES)
        }
        val wait = pacing.waitBefore(site, clock())
        if (wait <= 0) return 0
        if (port != null && idle != null) idleWait(port, idle, wait) else pause(wait)
        pacing.served(site)
        onWait(wait)
        return wait
    }

    /** Pause for [ms] milliseconds; if the user interjects the pause is cut short and [DwellInterrupted] is thrown. */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /**
     * Pause for [wait] milliseconds, scrolling lightly a few times as [plan] schedules; if the port says scrolling is not convenient right now (a dialog is open, an input field holds focus, etc.) the scrolling stops but the full pause still happens.
     * When the user's interjection cuts the dwell short, the scrolls already done are still reported and the screen the model holds is still marked as stale.
     */
    private suspend fun idleWait(port: WebPort, plan: IdleScroll, wait: Long) {
        val start = clock()
        var done = 0
        suspend fun scrolled() { if (done > 0) { onIdleScroll(done); afterIdle() } }
        try {
            for (step in plan.plan(wait)) {
                val left = step.atMs - (clock() - start)
                if (left > 0) pause(left)
                val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                if (!ok) break
                done++
            }
            val rest = wait - (clock() - start)
            if (rest > 0) pause(rest)
        } catch (e: DwellInterrupted) {
            scrolled()
            throw e
        }
        scrolled()
    }

    private fun paceOf(site: String): PlacePace {
        val host = hostOf(site)
        return synchronized(paces) { paces.getOrPut(host) { pace(host) } }
    }

    private fun siteKey(site: String): String { val h = hostOf(site); return UrlHost.registrable(h) ?: h }

    private fun hostOf(site: String): String = UrlHost.of(if ("://" in site) site else "https://$site") ?: site

    /** Record the responses the page has already received before scrolling down; after scrolling, [afterScroll] compares them, and new batch-like data means more content was loaded. */
    suspend fun scrollMark(port: WebPort): Set<String>? = if (!paced) null else try {
        port.captures(null, null, false).mapTo(HashSet()) { it.id }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun afterScroll(port: WebPort, mark: Set<String>?) {
        mark ?: return
        val fresh = try { port.captures(null, null, false) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        if (fresh.any { it.id !in mark && batch(it) }) loadedMore()
    }

    /** A response that looks like a batch of list data: completed successfully, JSON or text, not too small (small responses such as analytics or heartbeat ones do not count). */
    private fun batch(c: CapturedResponse): Boolean {
        if (c.state != CapturedResponse.State.DONE || c.status !in 200..299) return false
        val mime = c.mime?.lowercase().orEmpty()
        if ("json" !in mime && !mime.startsWith("text/")) return false
        return (c.size ?: 0) >= MORE_MIN_BYTES
    }

    private companion object {
        /** A response counts as "another batch of content" only from this size up. */
        const val MORE_MIN_BYTES = 2_048L
    }
}
