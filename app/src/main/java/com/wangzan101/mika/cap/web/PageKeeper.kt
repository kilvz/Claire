package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The single in-process "web page left behind after a task ends": when a task ends ([WebStack.close]), the page the model marked with web_keep_open is taken out of the task's tab pool
 * and handed over here together with the background web display it is attached to — the page stays attached to the same display it had during the task, visible and running just as during the task, with no special tricks.
 * At most one page globally ([KeptPageSlot]); closed automatically after [KEEP_OPEN_HOURS][com.wangzan101.mika.core.cap.web.KEEP_OPEN_HOURS] hours;
 * also closed when memory pressure makes the system ask for memory back, when the renderer process crashes or is reclaimed by the system, when the user closes it, or when another page is kept. Closing destroys the tab, releases the display, flushes Cookies to disk,
 * and reports to [onClosed] (recording the reason on the originating conversation's turn).
 * The kept page is in no task's tab pool: later tasks cannot list it or close it; it counts toward the tab total shared by tasks (see [TabBudget]) but is never evicted to make room.
 * The process is kept alive by the task service's foreground notification (see TaskService). Everything that touches WebView runs on the main thread; [close] may be called from any thread.
 */
class PageKeeper(
    private val context: Context,
    private val budget: TabBudget,
    clock: () -> Long = System::currentTimeMillis,
) : TabBudget.Member {
    /** The tab holding the page and the display it is attached to. */
    class Held(val tab: WebTab, val host: WebHost)

    private val main = Handler(Looper.getMainLooper())

    /** After a page is closed (may also be called off the main thread): record the reason on the originating conversation's turn. Wired up by the App. */
    @Volatile var onClosed: (KeptPage, KeptPageClose) -> Unit = { _, _ -> }

    private val slot: KeptPageSlot<Held> = KeptPageSlot(clock, release = ::release, onClosed = { p, r ->
        Log.i(TAG, "kept page ${p.id} closed: ${r.wire}")
        main.removeCallbacks(expiry)
        onClosed(p, r)
    })

    /** The page kept at this moment; null if none. */
    val page: StateFlow<KeptPage?> get() = slot.page

    private val expiry: Runnable = object : Runnable {
        override fun run() {
            if (slot.expireIfDue()) return
            // This timer stops while the system is in deep sleep: check against the wall clock every so often, and close after waking once the deadline has passed
            slot.remainingMs()?.let { main.postDelayed(this, it.coerceIn(1_000L, CHECK_MS)) }
        }
    }

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) close(KeptPageClose.Memory)
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = Unit
    }

    /** Events on the kept page: only crashes are handled; dialogs, downloads and app launches are all ignored (no task is watching it). */
    private inner class Listener(val id: Long) : WebTab.Listener {
        override fun onCrashed(tab: WebTab) {
            // Close outside the WebView's own callback
            main.post { slot.closeIf(id, if (tab.reclaimed) KeptPageClose.Memory else KeptPageClose.Crashed) }
        }
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /**
     * Takes in [tab] (already taken out of the task's tab pool) and its display [host], keeping it running in the background; any other page currently kept is closed first.
     * If the tab is already closed or crashed, or the display is closed, it cannot be accepted and null is returned, and the caller disposes of it itself. Main thread.
     */
    fun keep(tab: WebTab, host: WebHost, origin: KeptOrigin): KeptPage? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PageKeeper.keep must run on the main thread" }
        if (tab.destroyed || tab.crashed || host.closed) return null
        val url = tab.view.url
        val site = url?.let(WebUrlPolicy::host) ?: url.orEmpty()
        // Attach it back to its display (at task end it may not be the attached one); the page still considers itself visible
        host.attach(tab)
        runCatching { WebTab.cookieManager().flush() }
        val p = slot.keep(Held(tab, host), origin, site, url, tab.view.title)
        tab.listener = Listener(p.id)
        main.removeCallbacks(expiry)
        main.postDelayed(expiry, (p.closesAt - p.keptAt).coerceIn(1_000L, CHECK_MS))
        Log.i(TAG, "kept page ${p.id} on $site from conversation ${p.conversationId}")
        return p
    }

    /** Closes the kept page (the user tapped close, a notification button, low memory, etc.); returns false if nothing is kept. Any thread. */
    fun close(reason: KeptPageClose): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (slot.page.value == null) return false
            main.post { slot.close(reason) }
            return true
        }
        return slot.close(reason)
    }

    /** How long until it closes automatically (ms); null if nothing is kept. */
    fun remainingMs(): Long? = slot.remainingMs()

    /** The kept tab (used by tests and on-device acceptance to see whether the page is still running); null if none. */
    fun keptTab(): WebTab? = slot.handle()?.tab

    /** The kept page to record for the turn with [runId]: (site, close reason), see [KeptPageSlot.recordFor]. */
    fun recordFor(runId: String?): Pair<String, String?>? = slot.recordFor(runId)

    /** Live picture of the kept page, one frame every [FRAME_MS] (same source as the picture-in-picture during the task: the latest frame of its display); emits null when there is no frame. */
    fun frames(): Flow<Bitmap?> = flow {
        while (true) {
            emit(slot.handle()?.host?.takeIf { !it.closed }?.latestFrame())
            delay(FRAME_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ---------- Global limit ----------

    override fun tabCount(): Int = if (slot.page.value != null) 1 else 0

    /** The kept page is never evicted to make room: the user wants it to stay open. */
    override fun oldestSpare(except: String?): Pair<String, Long>? = null

    override fun evictForBudget(tabId: String) = Unit

    private fun release(h: Held) {
        val run = Runnable {
            runCatching { WebTab.cookieManager().flush() }
            runCatching { h.tab.destroy() }
            runCatching { h.host.close() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else main.post(run)
    }

    private companion object {
        const val TAG = "MikaPageKeeper"
        /** How often to check against the wall clock whether the deadline has been reached. */
        const val CHECK_MS = 60_000L
        const val FRAME_MS = 500L
    }
}
