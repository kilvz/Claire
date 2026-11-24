package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.TabInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import com.wangzan101.mika.core.cap.web.ExternalLink as Link
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** Things that happen in the tab pool: a new tab, a close (with reason), a crash, a blocked app-jump link, and a reload when switching back after memory reclamation. */
sealed interface PoolEvent {
    data class NewTab(val tabId: String, val taskId: String, val fromPopup: Boolean) : PoolEvent
    /**
     * [reason]: closed for a deliberate close, limit when the cap evicted the least recently used one, task_end when the task ended, popup_other_site when a popup wanted another site;
     * returned when a borrowed tab ([WebTabPool.attachExternal]) is given back, and kept when it stays in the background after the task ends ([WebTabPool.release]) (in both cases the tab itself is not closed).
     */
    data class Closed(val tabId: String, val reason: String) : PoolEvent
    data class Crashed(val tabId: String) : PoolEvent
    data class ExternalLink(val tabId: String, val link: Link) : PoolEvent
    data class Reloaded(val tabId: String) : PoolEvent
}

/**
 * The web tab pool: at most [perTask] tabs per task, and at most the [budget] cap across all tasks (one shared across tasks);
 * when exceeded, this task's least recently used tab is closed, and only when this task has none to close is the least recently used tab of another task closed.
 * Only the most recently opened or switched-to tab is attached to [host] for drawing at any time; the rest keep running in the background (pages see a background tab).
 * A new window opened by a page creates a new tab in the pool only when the user clicked (there is a gesture), and only for the same site; app-jump links are not followed and emit [PoolEvent.ExternalLink].
 * Renderer crash: the tab is destroyed, [PoolEvent.Crashed] is emitted, and the next URL open creates a new tab.
 * Under system memory pressure, background tabs are closed with only their URL recorded and reloaded when switched back; cookies are flushed to disk when a page finishes loading, when a task ends, and when the App goes to the background, so logins are not lost.
 * Borrowed tabs ([attachExternal], the one the user is watching on the browser page): used as the task's current tab but not managed by the pool -- not attached to [host] (it lives in the user's UI),
 * not counted against the cap, not evicted or closed by memory reclamation, and the pool does not take its events (downloads, popups, login probes are handled by its original owner); closing it or ending the task only gives it back ([detachExternal]), never destroying it.
 *
 * Methods that touch WebViews ([open], [switchTo], [close], [closeAll], [list], [onTrimMemory]) are called on the main thread;
 * [current], [isLive], [crashedTab] may be called from any thread.
 */
class WebTabPool(
    private val context: Context,
    private val host: WebHost,
    private val perTask: Int = 4,
    /** The total tab cap shared by all tasks; defaults to the process-wide instance. */
    private val budget: TabBudget = WebOwner.of(context).tabBudget,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    private val registry: SiteRegistry? = null,
    /** DevTools session entry point for tabs in the pool; defaults to the process-wide instance. */
    private val devtools: WebDevtools = WebDevtools.of(context),
) : WebTab.Listener, Closeable, TabBudget.Member {
    /**
     * [background]: a temporary background tab for page reads; not made current, not in the tab list, not counted against the cap, and closed as soon as the read is done (see [openBackground]).
     * [external]: a borrowed tab (see [attachExternal]); not counted against the cap, and closing it only gives it back.
     */
    private class Entry(
        val id: String, val taskId: String, var tab: WebTab?, var url: String?, var title: String, var lastUsed: Long,
        val background: Boolean = false, val external: Boolean = false,
    ) {
        /** An ordinary tab counted toward the per-task and global caps that may be evicted to make room. */
        val counted: Boolean get() = !background && !external
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val currentByTask = HashMap<String, String>()
    private val crashedByTask = HashMap<String, String>()
    /** Tabs currently handed to the user (during takeover): the task cannot operate them, and the pool neither closes nor reclaims them. */
    private val locked = HashSet<String>()
    private var counter = 0
    private val main = Handler(Looper.getMainLooper())
    /** Pending login-state probes per tab (touched on the main thread). */
    private val loginProbes = HashMap<String, Runnable>()

    private val _events = MutableSharedFlow<PoolEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<PoolEvent> = _events.asSharedFlow()

    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@WebTabPool.onTrimMemory(level)
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = this@WebTabPool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    init {
        context.applicationContext.registerComponentCallbacks(memory)
        budget.join(this)
    }

    /** Opens a new tab in the task and switches to it; when [url] is not null, starts loading it (without waiting for the load to finish). */
    fun open(taskId: String, url: String?): WebTab {
        checkMain()
        makeRoom(taskId, keep = null)
        val tab = create(taskId)
        url?.let { u ->
            val refused = WebUrlPolicy.checkNavigable(u)
            if (refused == null) tab.view.loadUrl(u) else Log.w(TAG, "refused to open a non-web address: $refused")
        }
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return tab
    }

    /**
     * Opens a temporary background tab for page reads: not switched to, not attached to the host (nothing lays it out, so it is measured once at [width]x[height] to give the page a viewport),
     * not listed by [list], not counted toward the per-task and global caps (how many run at once is bounded by the caller), and never evicted or closed by memory reclamation. Call [close] when done.
     */
    fun openBackground(taskId: String, width: Int, height: Int): WebTab {
        checkMain()
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        synchronized(this) { entries[id] = Entry(id, taskId, tab, null, "", budget.tick(), background = true) }
        val w = View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(height.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        tab.view.measure(w, h)
        tab.view.layout(0, 0, width, height)
        return tab
    }

    @Synchronized
    fun current(taskId: String): WebTab? = currentByTask[taskId]?.let { entries[it]?.tab }

    /**
     * Lends a tab not managed by the pool (the one the user is watching on the browser page) to task [taskId] as its current tab: web tools then read it and act on it
     * like a pooled tab. It is not attached to [host], not counted against the cap, and its listener is not taken over; if the same tab is already borrowed, it is only made current.
     * Closed tabs, crashed tabs, and tabs whose id collides with a pooled tab cannot be borrowed and return false. Main thread.
     */
    fun attachExternal(taskId: String, tab: WebTab): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed) return false
        val entry = synchronized(this) {
            val existing = entries[tab.id]
            if (existing != null && (!existing.external || existing.tab !== tab || existing.taskId != taskId)) return false
            existing ?: Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), 0, external = true).also {
                entries[tab.id] = it
                crashedByTask.remove(taskId)
            }
        }
        makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * Gives back the borrowed tab [tabId]: removes it from the pool without destroying it or touching its view; if it was the current tab, the task's most recently used other tab
     * becomes current (with none, there is no current tab). Returns false if it was not borrowed. Main thread.
     */
    fun detachExternal(tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.external != true }) return false
        remove(tabId, PoolEvent.Closed(tabId, "returned"), force = true)
        return true
    }

    /**
     * Takes in a tab [tab] that was not managed by the pool as an ordinary tab of task [taskId] (the browser page hosting the lent page was closed and the page is handed to the task):
     * its events are taken over, it counts against the cap, it may be evicted, and it is closed with the others when the task ends. It becomes current and is attached to the host when
     * [current] is true or the task has no current tab; otherwise it stays in the background. Closed tabs, crashed tabs, and ids already in the pool cannot be taken in and return false. Main thread.
     */
    fun adopt(taskId: String, tab: WebTab, current: Boolean): Boolean {
        checkMain()
        if (tab.destroyed || tab.crashed || synchronized(this) { entries.containsKey(tab.id) }) return false
        makeRoom(taskId, keep = null)
        (tab.view.parent as? android.view.ViewGroup)?.removeView(tab.view)
        tab.ownerTaskId = taskId
        tab.listener = this
        val entry = Entry(tab.id, taskId, tab, tab.view.url, tab.view.title.orEmpty(), budget.tick())
        val makeIt = synchronized(this) {
            entries[tab.id] = entry
            current || currentByTask[taskId] == null
        }
        if (makeIt) makeCurrent(entry)
        emit(PoolEvent.NewTab(tab.id, taskId, fromPopup = false))
        return true
    }

    /**
     * At task end, hands out the tab [tabId] to be kept (see [PageKeeper]): removed from the pool without destroying it, detaching it from the host, or attaching another tab
     * (if it was the current tab, this task no longer has one); afterwards its events are no longer taken, it does not count against the cap, and it is not closed with the task. Emits Closed(kept).
     * Borrowed tabs, temporary background tabs, reclaimed tabs, crashed tabs, and unknown tabs cannot be handed out and return null. Main thread.
     */
    fun release(tabId: String): WebTab? {
        checkMain()
        val tab = synchronized(this) {
            val e = entries[tabId]?.takeIf { it.counted } ?: return null
            val t = e.tab?.takeIf { !it.destroyed && !it.crashed } ?: return null
            entries.remove(tabId)
            locked -= tabId
            if (currentByTask[e.taskId] == tabId) currentByTask.remove(e.taskId)
            t
        }
        loginProbes.remove(tabId)?.let(main::removeCallbacks)
        tab.listener = null
        emit(PoolEvent.Closed(tabId, "kept"))
        return tab
    }

    /** Whether [tabId] is a borrowed tab (see [attachExternal]). */
    @Synchronized
    fun isExternal(tabId: String): Boolean = entries[tabId]?.external == true

    @Synchronized
    fun isLive(tabId: String): Boolean = entries[tabId]?.tab != null

    /** Takeover starts: locks the tab until [unlock]. */
    @Synchronized
    fun lock(tabId: String) {
        locked += tabId
    }

    @Synchronized
    fun unlock(tabId: String) {
        locked -= tabId
    }

    @Synchronized
    fun isLocked(tabId: String): Boolean = tabId in locked

    /** The tab of this task that crashed most recently and has not been replaced by a new tab since; null if none. */
    @Synchronized
    fun crashedTab(taskId: String): String? = crashedByTask[taskId]

    /** Switches to one of this task's tabs; null for tabs of other tasks or nonexistent ones. A reclaimed tab is reloaded and [PoolEvent.Reloaded] is emitted. */
    fun switchTo(taskId: String, tabId: String): WebTab? {
        checkMain()
        val entry = synchronized(this) { entries[tabId]?.takeIf { it.taskId == taskId } } ?: return null
        var reloaded = false
        val tab = entry.tab ?: newTab(entry.id, taskId).also { t ->
            synchronized(this) { entry.tab = t }
            entry.url?.let { t.view.loadUrl(it) }
            reloaded = true
        }
        makeCurrent(entry)
        if (reloaded) emit(PoolEvent.Reloaded(tabId))
        return tab
    }

    /** Closes a tab (of any task). A task closing its own tab uses close(taskId, tabId). */
    fun close(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "closed"))

    /** A task closes its own tab; returns false and does not close it when the tab belongs to another task, does not exist, or is currently with the user. */
    fun close(taskId: String, tabId: String): Boolean {
        checkMain()
        if (synchronized(this) { entries[tabId]?.taskId != taskId || tabId in locked }) return false
        close(tabId)
        return true
    }

    /** Logs out of one site: expires its cookies, clears its web storage, and removes it from the site registry (best effort, see [WebLogin]). Returns the number of expired cookies. */
    fun clearSiteData(host: String): Int {
        checkMain()
        return WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logout(host)
    }

    /** Logs out everywhere and clears web data. Open tabs are not closed, but they no longer have any login state. */
    fun clearAll() {
        checkMain()
        val views = synchronized(this) { entries.values.mapNotNull { it.tab?.view } }
        WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry).logoutAll(views, WebViewDatabase.getInstance(context))
    }

    /** Task end: closes every tab it opened (borrowed ones are given back) and flushes cookies to disk (login state is not lost when the tabs close). */
    fun closeAll(taskId: String) {
        checkMain()
        val ids = synchronized(this) { entries.values.filter { it.taskId == taskId }.map { it.id } }
        ids.forEach { remove(it, PoolEvent.Closed(it, if (isExternal(it)) "returned" else "task_end"), force = isExternal(it)) }
        synchronized(this) { crashedByTask.remove(taskId) }
        flushCookies()
    }

    /**
     * Task [taskId] ends and the page [keep] is to be kept: it is handed out first ([release]), then the task's other tabs are closed ([closeAll]).
     * The handed-out tab is managed by the caller from then on; null when [keep] is null or cannot be handed out (everything is closed as usual). Main thread.
     */
    fun closeKeeping(taskId: String, keep: String?): WebTab? {
        checkMain()
        val tab = keep?.takeIf { synchronized(this) { entries[it]?.taskId == taskId } }?.let(::release)
        closeAll(taskId)
        return tab
    }

    /** This task's tabs, in opening order. */
    fun list(taskId: String): List<TabInfo> {
        checkMain()
        val (rows, cur) = synchronized(this) { entries.values.filter { it.taskId == taskId && !it.background } to currentByTask[taskId] }
        return rows.map { e ->
            val live = e.tab
            TabInfo(e.id, live?.view?.url ?: e.url.orEmpty(), live?.view?.title ?: e.title, e.id == cur)
        }
    }

    /**
     * Under memory pressure (RUNNING_LOW and above, or already in the background and asked to release), closes each task's non-current tabs keeping only their URLs;
     * when the App goes to the background (UI_HIDDEN), flushes cookies to disk.
     */
    fun onTrimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onTrimMemory(level) }
            return
        }
        @Suppress("DEPRECATION")
        val pressure = level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushCookies()
        if (!pressure) return
        val victims = synchronized(this) {
            entries.values.filter { it.tab != null && it.counted && currentByTask[it.taskId] != it.id && it.id !in locked }
        }
        for (e in victims) {
            val tab = e.tab ?: continue
            e.url = tab.view.url ?: e.url
            e.title = tab.view.title ?: e.title
            synchronized(this) { e.tab = null }
            tab.destroy()
            Log.i(TAG, "tab ${e.id} released under memory pressure (level $level)")
        }
    }

    /** Closes all tasks' tabs and stops receiving system memory callbacks. */
    override fun close() {
        checkMain()
        val tasks = synchronized(this) { entries.values.map { it.taskId }.toSet() }
        tasks.forEach(::closeAll)
        budget.leave(this)
        context.applicationContext.unregisterComponentCallbacks(memory)
    }

    // ---------- global cap ----------

    @Synchronized
    override fun tabCount(): Int = entries.values.count { it.counted }

    @Synchronized
    override fun oldestSpare(except: String?): Pair<String, Long>? =
        entries.values.filter { it.id != except && it.counted && it.id !in locked }.minByOrNull { it.lastUsed }?.let { it.id to it.lastUsed }

    override fun evictForBudget(tabId: String) = remove(tabId, PoolEvent.Closed(tabId, "limit"))

    // ---------- events on tabs ----------

    override fun onPageFinished(tab: WebTab) {
        val url = tab.view.url
        synchronized(this) {
            entries[tab.id]?.let {
                it.url = url
                it.title = tab.view.title.orEmpty()
            }
        }
        url?.let { u -> WebUrlPolicy.host(u)?.takeIf { u.startsWith("http") }?.let { registry?.visited(it) } }
        flushCookies()
        scheduleLoginProbe(tab, 0)
    }

    /**
     * Page scripts usually draw the account area only after loading finishes: wait for the page to settle, then look at the login state, so the registry follows the page's real state.
     * If it loads again (redirect), restart the timer; only tabs the task is using and that are attached to the host are examined; if the URL is still changing or it is still loading,
     * wait again, up to a few times. Main thread.
     */
    private fun scheduleLoginProbe(tab: WebTab, attempt: Int) {
        val r = registry ?: return
        loginProbes.remove(tab.id)?.let(main::removeCallbacks)
        val urlThen = tab.view.url
        val run = Runnable {
            loginProbes.remove(tab.id)
            if (tab.destroyed) return@Runnable
            val current = synchronized(this) { currentByTask[tab.ownerTaskId] == tab.id }
            if (!current || !tab.view.isAttachedToWindow) return@Runnable
            if (tab.loading || tab.view.url != urlThen) {
                if (attempt < LOGIN_PROBE_RETRIES) scheduleLoginProbe(tab, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(tab) { host, state -> r.loginObserved(host, state) }
        }
        loginProbes[tab.id] = run
        main.postDelayed(run, LOGIN_PROBE_DELAY_MS)
    }

    override fun onCrashed(tab: WebTab) {
        // Destroy outside the callback; never call destroy from inside the WebView's own callback stack
        main.post {
            // A temporary background tab crashing does not count as the task's tab crashing: the page-read side reports it itself
            synchronized(this) { if (entries[tab.id]?.background != true) crashedByTask[tab.ownerTaskId] = tab.id }
            remove(tab.id, PoolEvent.Crashed(tab.id), force = true)
        }
    }

    /** Page-triggered downloads go here (the task routes them into the workspace); ignored when null. Main-thread callback: (tab, URL, UA, Content-Disposition, MIME type). */
    var downloadHandler: ((WebTab, String, String, String?, String?) -> Unit)? = null

    override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
        val h = downloadHandler
        if (h == null) Log.i(TAG, "download ignored on tab ${tab.id}") else h(tab, url, userAgent, contentDisposition, mime)
    }

    override fun onExternalLink(tab: WebTab, link: Link) {
        emit(PoolEvent.ExternalLink(tab.id, link))
    }

    override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
        // Never open a popup without a user action
        if (!isUserGesture) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        makeRoom(tab.ownerTaskId, keep = tab.id)
        val popup = create(tab.ownerTaskId)
        popup.popupOpener = tab.view.url ?: ""
        transport.webView = popup.view
        resultMsg.sendToTarget()
        emit(PoolEvent.NewTab(popup.id, tab.ownerTaskId, fromPopup = true))
        return true
    }

    override fun onPopupRefused(tab: WebTab, url: String) {
        Log.i(TAG, "popup ${tab.id} refused: other site ${url.take(80)}")
        main.post { remove(tab.id, PoolEvent.Closed(tab.id, "popup_other_site")) }
    }

    // ---------- internals ----------

    private fun create(taskId: String): WebTab {
        val id = synchronized(this) { "t${++counter}" }
        val tab = newTab(id, taskId)
        val entry = Entry(id, taskId, tab, null, "", 0)
        synchronized(this) {
            entries[id] = entry
            crashedByTask.remove(taskId)
        }
        makeCurrent(entry)
        return tab
    }

    private fun newTab(id: String, taskId: String) = WebTab(context, id, taskId, assetLoaders, devtools).also { it.listener = this }

    private fun makeCurrent(entry: Entry) {
        synchronized(this) {
            currentByTask[entry.taskId] = entry.id
            entry.lastUsed = budget.tick()
        }
        // A borrowed tab stays in the user's UI; whatever was already attached to the host stays attached (and keeps running in the background as usual)
        if (!entry.external) entry.tab?.let { host.attach(it) }
    }

    /**
     * Makes room for this task: when this task is full, close its least recently used tab; when all tasks together are full, close this task's first and, only if there is none,
     * the least recently used one elsewhere. [keep] and locked (in takeover) tabs are not closed but still count. If there is truly nothing to close, nothing is closed (better to overshoot slightly than to touch a page in the user's hands).
     */
    private fun makeRoom(taskId: String, keep: String?) {
        while (true) {
            val (own, perTaskFull) = synchronized(this) {
                val all = entries.values.filter { it.taskId == taskId && it.counted }
                all.filter { it.id != keep && it.id !in locked }.minByOrNull { it.lastUsed } to (all.size >= perTask)
            }
            when {
                perTaskFull && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() && own != null -> remove(own.id, PoolEvent.Closed(own.id, "limit"))
                budget.full() -> if (!budget.evictOldest(except = keep)) return
                else -> return
            }
        }
    }

    /** Closes a tab; a locked one (in takeover) is not closed unless [force] (the renderer is already gone, a borrowed tab is being given back). A borrowed tab is only removed, never destroyed. */
    private fun remove(tabId: String, event: PoolEvent, force: Boolean = false) {
        checkMain()
        val (entry, next) = synchronized(this) {
            if (tabId in locked && !force) {
                Log.i(TAG, "tab $tabId is with the user, not closed")
                return
            }
            locked -= tabId
            val e = entries.remove(tabId) ?: return
            var nextEntry: Entry? = null
            if (currentByTask[e.taskId] == tabId) {
                nextEntry = entries.values.filter { it.taskId == e.taskId && !it.background }.maxByOrNull { it.lastUsed }
                if (nextEntry == null) currentByTask.remove(e.taskId) else currentByTask[e.taskId] = nextEntry.id
            }
            e to nextEntry
        }
        if (!entry.external) entry.tab?.let {
            host.detach(it.view)
            it.destroy()
        }
        next?.let { n ->
            // If the next current tab was reclaimed, it reloads when the task switches to it; a borrowed one stays with the user
            if (!n.external) n.tab?.let { host.attach(it) }
        }
        emit(event)
    }

    private fun flushCookies() {
        runCatching { WebTab.cookieManager().flush() }
    }

    private fun emit(e: PoolEvent) {
        _events.tryEmit(e)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "WebTabPool must be used on the main thread" }

    private companion object {
        const val TAG = "MikaWebTabPool"
        const val LOGIN_PROBE_DELAY_MS = 1_500L
        const val LOGIN_PROBE_RETRIES = 3
    }
}
