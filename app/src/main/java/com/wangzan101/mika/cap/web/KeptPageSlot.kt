package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS
import com.wangzan101.mika.core.cap.web.KeptPageClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The outward shape of the page kept running in the background after a task ends: which conversation and which turn it came from ([conversationId], [runId]), that turn's original words [goal],
 * the page's site, URL and title, when it was kept and when it closes automatically (wall clock, ms).
 */
data class KeptPage(
    val id: Long,
    val conversationId: String,
    val runId: String?,
    val goal: String,
    val host: String,
    val url: String?,
    val title: String?,
    val keptAt: Long,
    val closesAt: Long,
)

/** Which task kept the page: conversation, run record id, original words. */
data class KeptOrigin(val conversationId: String, val runId: String?, val goal: String)

/**
 * The pure logic of page keeping (does not touch WebView): at most one page globally; keeping a new one closes the old one first ([KeptPageClose.Replaced]); it is closed by [expireIfDue] at [closesAt][KeptPage.closesAt];
 * on close it releases what it holds ([release]) and reports to [onClosed] (written into the originating conversation's turn). [H] is the thing actually holding the page (the tab and its display).
 * It remembers the most recently closed page and its reason, so it can still answer when the task's turn has not been written to the conversation yet ([recordFor]). Time is supplied by the caller; tests use a fake clock. Thread-safe.
 */
class KeptPageSlot<H>(
    private val clock: () -> Long,
    private val release: (H) -> Unit,
    private val onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> },
    private val ttlMs: Long = KEEP_OPEN_HOURS * HOUR_MS,
) {
    private val _page = MutableStateFlow<KeptPage?>(null)
    /** The page kept at this moment; null if none. */
    val page: StateFlow<KeptPage?> = _page.asStateFlow()

    private var held: H? = null
    private var counter = 0L
    private var lastClosed: Pair<KeptPage, KeptPageClose>? = null

    /** Keeps the page held by [handle]; if a page is already kept, close it first. Returns the newly kept page. */
    fun keep(handle: H, origin: KeptOrigin, host: String, url: String?, title: String?): KeptPage {
        close(KeptPageClose.Replaced)
        val now = clock()
        val p = synchronized(this) {
            held = handle
            KeptPage(++counter, origin.conversationId, origin.runId, origin.goal, host, url, title, now, now + ttlMs).also { _page.value = it }
        }
        return p
    }

    /** Closes the kept page; returns false if nothing is kept. */
    fun close(reason: KeptPageClose): Boolean {
        val (p, h) = synchronized(this) {
            val p = _page.value ?: return false
            val h = held
            held = null
            _page.value = null
            lastClosed = p to reason
            p to h
        }
        h?.let { runCatching { release(it) } }
        onClosed(p, reason)
        return true
    }

    /** Closes it only if the page with [id] is still the kept one (crash and reclamation callbacks can arrive late, so do not close a page kept later by mistake). */
    fun closeIf(id: Long, reason: KeptPageClose): Boolean = if (_page.value?.id == id) close(reason) else false

    /** Closes it once the deadline has passed ([KeptPageClose.Expired]); returns true if it was closed. */
    fun expireIfDue(): Boolean {
        val p = _page.value ?: return false
        return if (clock() >= p.closesAt) closeIf(p.id, KeptPageClose.Expired) else false
    }

    /** How long until it closes automatically (ms, not less than 0); null if nothing is kept. */
    fun remainingMs(): Long? = _page.value?.let { (it.closesAt - clock()).coerceAtLeast(0) }

    @Synchronized
    fun handle(): H? = held

    /**
     * The kept page to record for the turn with [runId]: (site, close reason); the reason is null while it is still kept, and the whole result is null if that turn kept no page.
     * The turn is only written to the conversation when the task wraps up, and the page may already have been closed by then (crashed, replaced by a newly kept one); this still provides the answer.
     */
    @Synchronized
    fun recordFor(runId: String?): Pair<String, String?>? {
        if (runId == null) return null
        _page.value?.takeIf { it.runId == runId }?.let { return it.host to null }
        return lastClosed?.takeIf { it.first.runId == runId }?.let { (p, r) -> p.host to r.wire }
    }

    companion object {
        const val HOUR_MS = 3_600_000L
    }
}
