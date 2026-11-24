package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.cap.web.PageText
import com.wangzan101.mika.core.cap.web.PageDigest
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.cap.web.WebBarrier
import com.wangzan101.mika.core.cap.web.WebDialogOpen
import com.wangzan101.mika.core.cap.web.WebTabCrashed
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.cap.web.SettlePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.min
import com.wangzan101.mika.cap.web.WebScreen.Companion.QUIET_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_POLL_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_STABLE_POLLS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_EMPTY_POLLS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CONTENT_AFTER_SCROLL_CAP_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_READS
import com.wangzan101.mika.cap.web.WebScreen.Companion.backgroundReads
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_CRASHED
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_DOWNLOAD
import com.wangzan101.mika.cap.web.WebScreen.Companion.BACKGROUND_DIALOG
import com.wangzan101.mika.cap.web.WebScreen.Companion.NO_POOL
import com.wangzan101.mika.cap.web.WebScreen.Companion.NAVIGATE_TIMEOUT_MS
import com.wangzan101.mika.cap.web.WebScreen.Companion.CLEARTEXT

// Web screen: extract body text per tab and read in the background
/**
 * Before reading the first segment ([PageRead.lazy] with offset 0), wait for the body to settle, scroll a few screens to trigger lazy loading and wait once more, then scroll back after reading;
 * later segments neither wait nor scroll. How the body is assembled, folded and judged for quality is in [PageDigest].
 */
internal suspend fun WebScreen.digestTab(tab: WebTab, req: PageRead): PageText {
    val s = script(tab)
    val first = req.lazy && req.offset == 0
    var lazy: String? = null
    try {
        if (first) {
            contentStable(tab, CONTENT_CAP_MS)
            lazy = s.startLazy()
            if (lazy != null) contentStable(tab, CONTENT_AFTER_SCROLL_CAP_MS)
        }
        val raw = s.digest()
        if (tab.crashed) throw WebTabCrashed(tab.id)
        raw ?: return PageText(url(tab).orEmpty(), title(tab), "", emptyList(), emptyList(), false, null)
        val d = PageDigest.parse(raw)
        val r = PageDigest.render(d, req.unfold)
        val status = tab.httpErrorStatus
        val pageUrl = d.url.ifEmpty { url(tab).orEmpty() }
        val check = WebBarrier.classify(PageDigest.barrierFacts(d, status, WebUrlPolicy.host(pageUrl)))
        val start = req.offset.coerceIn(0, r.text.length)
        val end = min(r.text.length, start + req.maxChars)
        return PageText(
            url = pageUrl, title = d.title, text = r.text.substring(start, end), links = r.links, images = d.images,
            truncated = end < r.text.length, nextOffset = end.takeIf { it < r.text.length },
            quality = PageDigest.quality(d, r, check, status),
        )
    } finally {
        lazy?.let { s.endLazy(it) }
    }
}

/** The body (length, digest) counts as stable only after staying unchanged several times in a row, at most [capMs]. */
internal suspend fun WebScreen.contentStable(tab: WebTab, capMs: Long): Boolean = SettlePolicy.contentStable(
    clock, { if (tab.crashed) null else script(tab).contentSample() }, CONTENT_POLL_MS, CONTENT_STABLE_POLLS, CONTENT_EMPTY_POLLS, capMs,
)

/**
 * Open [url] in a temporary background tab and read one segment, then close it (also on error or cancellation). At most [BACKGROUND_READS] at a time (process-wide).
 * Failure to open, turning into a download and a crash are all reported as-is in [PageText.netError].
 */
internal suspend fun WebScreen.readInBackground(req: PageRead, url: String): PageText {
    val pool = pool ?: throw UnsupportedOperationException(NO_POOL)
    val task = taskId!!
    return backgroundReads.withPermit {
        val tab = withContext(Dispatchers.Main) { pool.openBackground(task, host.width, host.height) }
        fun failed(error: String) = PageText(url, "", "", emptyList(), emptyList(), false, null, netError = error)
        try {
            tab.load(url, NAVIGATE_TIMEOUT_MS)
            if (!tab.crashed && tab.mainFrameError?.contains(CLEARTEXT) == true && url.startsWith("http://", ignoreCase = true)) {
                tab.load("https://" + url.substring("http://".length), NAVIGATE_TIMEOUT_MS)
            }
            when {
                tab.crashed -> failed(BACKGROUND_CRASHED)
                tab.loadBecameDownload -> failed(BACKGROUND_DOWNLOAD)
                tab.mainFrameError != null -> failed(tab.mainFrameError!!)
                else -> {
                    if (tab.lastLoadFinished) awaitQuiet(tab, QUIET_MS, CAP_MS)
                    // Same gate as reading the current tab: do not read while a JS dialog is open on the page (page scripts do not respond then); the temporary tab is closed right away, so report it truthfully
                    try { dialogGuard(tab); digestTab(tab, req) } catch (e: WebTabCrashed) { failed(BACKGROUND_CRASHED) } catch (e: WebDialogOpen) { failed(BACKGROUND_DIALOG) }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { pool.close(tab.id) }
        }
    }
}
