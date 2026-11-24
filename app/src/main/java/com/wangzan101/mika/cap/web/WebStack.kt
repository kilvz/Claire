package com.wangzan101.mika.cap.web

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.content.Context
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.SystemClock
import android.util.Log
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.takeover.HandoverActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.net.NetProxy
import java.io.File
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * The built-in browser of one task: its own off-screen web display, its own tab pool, web ports, and an isolated script engine.
 * One per task (when tasks run in parallel, each task's current tab is attached to its own display and renders as usual). Created and closed on the main thread.
 */
class WebStack private constructor(
    private val context: Context,
    val taskId: String,
    val host: WebHost,
    val pool: WebTabPool,
    workspace: Workspace?,
    /** Receives the page left over after the task ends; when null no page can be kept (web_keep_open reports it as unavailable). */
    private val keeper: PageKeeper?,
) {
    /**
     * The workspace of this conversation: [dir] is the host directory (work/<conversation>, created if missing; the sandbox does not have to be installed).
     * Downloads are written into it and uploads read from it, all through [gate] (opened once, verified by handle).
     */
    class Workspace(val dir: File, val gate: WorkspaceFileGate, val tree: SandboxTree)

    // Downloads run in the background; they are all cancelled when the task ends, and unfinished ones are marked cancelled and their partial files deleted
    private val transfers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Downloads and uploads; null when there is no workspace (both then report unavailable). */
    val files: WebFiles? = workspace?.let { w ->
        val downloads = WebDownloads(w.dir, w.gate, w.tree, NetProxy.shared.client(), cookies = { url -> WebTab.cookieManager().getCookie(url) }, scope = transfers)
        WebFiles(context, w.dir, downloads, w.gate, transfers)
    }

    val screen: WebScreen = WebScreen(pool, taskId, host, SystemClock::uptimeMillis, files, keptNow = keeper?.let { k -> { k.page.value } })

    /** Records under whose name the kept page lives (conversation, run record, original words); read when the task ends. When unset, no page is kept. */
    @Volatile var keepOrigin: (() -> KeptOrigin)? = null

    init {
        files?.let { f -> pool.downloadHandler = { tab, url, ua, cd, mime -> onDownload(f, tab, url, ua, cd, mime) } }
    }

    // Page-initiated downloads: http(s) is fetched directly, blob: is read out through page scripts, data: is decoded directly; anything else (e.g. intent:) is ignored. Files are only saved, never opened.
    // All three first ask the page for the name on the link (<a download>): the download listener only carries the name from the response headers, and blob:/data: have no headers at all
    private fun onDownload(f: WebFiles, tab: WebTab, url: String, ua: String, cd: String?, mime: String?) {
        val d = f.downloads
        val pageName: suspend () -> String? = { screen.downloadName(tab, url) }
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                val referer = tab.view.url
                // When our own TLS connection fails, let the page fetch it with the browser's network stack: only addresses same-origin with the current page are offered
                val viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = if (!sameOrigin(url, referer)) null else { max ->
                    (screen.readBlob(tab, url, max) as? PageScript.Blob.Ok)?.let { it.bytes to it.mime }
                }
                d.withPageName(pageName) { name -> d.start(url, ua, cd, mime, referer, name, viaPage) }
            }
            url.startsWith("blob:", true) -> d.withPageName(pageName) { name ->
                d.startFrom(url, cd, mime, name) { max ->
                    when (val b = screen.readBlob(tab, url, max)) {
                        is PageScript.Blob.Ok -> b.bytes to b.mime
                        is PageScript.Blob.Failed -> throw WebDownloads.BlobRefused(b.error)
                    }
                }
            }
            url.startsWith("data:", true) -> d.withPageName(pageName) { name -> d.dataUrl(url, cd, name) }
            else -> Log.i(TAG, "download with an unsupported address ignored")
        }
    }

    /** Whether two URLs are same-origin (same scheme, host, and port); false if either is missing. */
    private fun sameOrigin(a: String, b: String?): Boolean {
        fun origin(u: String?) = u?.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
        val x = origin(a) ?: return false
        return x == origin(b)
    }

    /** The tab currently handed to the user (during takeover); null if none. */
    @Volatile private var handedTab: WebTab? = null

    /** The page is in the user's hands right now (during takeover). */
    val handedOver: Boolean get() = handedTab != null

    /** Whether this task has a current tab (the current tab is attached to the web display and visible); false when the web was only read in a temporary background tab. */
    val hasVisibleTab: Boolean get() = pool.current(taskId) != null

    /** The current tab is the borrowed page the user is watching (see [borrow]): it is not on the web display, so picture-in-picture shows no frame. */
    val onBorrowedTab: Boolean get() = pool.current(taskId)?.let { pool.isExternal(it.id) } == true

    // ---------- borrowed tabs ----------

    /**
     * Borrows [tab], the tab the user is watching on the browser page, as this task's current tab (see [WebTabPool.attachExternal]); its life cycle still belongs to the original owner.
     * Returns false when it cannot be borrowed (already closed, crashed). Main thread.
     */
    fun borrow(tab: WebTab): Boolean = pool.attachExternal(taskId, tab).also { if (it) Log.i(TAG, "task $taskId borrowed tab ${tab.id}") }

    /** Returns the borrowed tab [tab] (the task ended, or the user's side no longer offers it); does nothing if it was not borrowed. Main thread. */
    fun giveBack(tab: WebTab) {
        if (pool.detachExternal(tab.id)) Log.i(TAG, "task $taskId returned tab ${tab.id}")
    }

    /**
     * The borrowed tab changed to [next] (the user's page opened or closed a popup window and another one is now visible): the old one is returned and the new one borrowed;
     * if the old one was the current tab, the new one becomes current too. Main thread.
     */
    fun swapBorrowed(prev: WebTab, next: WebTab) {
        val wasCurrent = pool.current(taskId) === prev
        giveBack(prev)
        if (wasCurrent || pool.current(taskId) == null) borrow(next)
    }

    /**
     * The user closed the browser page hosting the lent page and the tab is handed to this task ([adopt]): the borrowed entry is returned, the same WebView is taken into the
     * pool as an ordinary tab, attached to the web display and running as usual, so page state (scroll position, forms, single-page-app state) is not lost; it is closed with the
     * other tabs when the task ends. Returns false when it cannot be taken in (already closed, crashed), in which case the caller destroys it itself. Main thread.
     */
    fun adopt(tab: WebTab): Boolean {
        val wasCurrent = pool.current(taskId) === tab
        giveBack(tab)
        return pool.adopt(taskId, tab, current = wasCurrent).also { if (it) Log.i(TAG, "task $taskId adopted tab ${tab.id}") }
    }

    /**
     * Takeover: locks the current tab and brings it into the takeover UI on the main screen. Returns false when there is no current tab or the UI cannot be opened.
     * [actions] hands the UI buttons to the task (handed back, I finished it myself, cancel, leave it for now); [currentKey] is the task's current wait key.
     */
    fun showForTakeover(reason: String, message: String?, waitKey: Int, actions: (WebHandover.Action) -> Unit, currentKey: () -> Int?): Boolean {
        val tab = pool.current(taskId) ?: return false
        pool.lock(tab.id)
        // A borrowed tab is already in front of the user (on the browser page): do not move it, just lock it and wait for the user to hand it back once done there
        val shown = if (pool.isExternal(tab.id)) !tab.destroyed
            else WebHandover.show(context, tab, host, reason, message, waitKey, taskId, actions, currentKey)
        if (shown) handedTab = tab else pool.unlock(tab.id)
        // A CAPTCHA or access block genuinely stopped the task (it has to go to the user): record the task's site as having hit a block
        if (shown && reason in SiteRegistry.CHALLENGE_KINDS) {
            tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host)?.let { WebSites.registry(context).challenged(it, reason) }
        }
        return shown
    }

    /** Hand back: closes the takeover UI, puts the tab back in the background and unlocks it; when [loggedIn] and the page looks signed in, the site is marked as signed in. Null when not in takeover. */
    fun returnFromTakeover(loggedIn: Boolean): HandoverActivity? {
        val tab = handedTab ?: return null
        handedTab = null
        if (pool.isExternal(tab.id)) {
            pool.unlock(tab.id)
            if (loggedIn && !tab.destroyed) LoginProbe.probe(tab) { host, state -> if (state == LoginState.SignedIn) WebSites.registry(context).markLoggedIn(host) }
            return HandoverActivity(borrowedTouches?.invoke(tab) ?: 0, tab.view.title?.takeIf { it.isNotBlank() })
        }
        val r = WebHandover.end(loggedIn, WebSites.registry(context), tab)
        pool.unlock(tab.id)
        return r
    }

    /** How many times the user touched the borrowed tab (recorded in the result when takeover hands back); supplied by the lending side. */
    @Volatile var borrowedTouches: ((WebTab) -> Int)? = null

    /**
     * Task end: take the takeover back first if still active, then close this task's tabs and display. When the model marked a page with web_keep_open, that page together with
     * this display is handed to [keeper] to keep running in the background (so the display is not closed); if it cannot be handed off (the page was closed, crashed) everything is closed as usual.
     */
    fun close() {
        runCatching { returnFromTakeover(loggedIn = false) }.onFailure { Log.w(TAG, "takeover not returned cleanly", it) }
        // Take the page to keep out of the pool first, so closing other tabs cannot attach another page to the display and displace it
        val kept = runCatching { pool.closeKeeping(taskId, screen.keepMark?.tabId) }.onFailure { Log.w(TAG, "kept page not released", it) }.getOrNull()
        runCatching { pool.close() }
        val handed = kept != null && runCatching { handOff(kept) }.onFailure { Log.w(TAG, "kept page not handed off", it) }.getOrDefault(false)
        if (kept != null && !handed) runCatching { kept.destroy() }
        if (!handed) runCatching { host.close() }
        runCatching { files?.close() }
        transfers.cancel()
    }

    /** Hands the kept page to [keeper]; returns true when accepted. */
    private fun handOff(tab: WebTab): Boolean {
        val k = keeper ?: return false
        val origin = keepOrigin?.invoke() ?: return false
        return k.keep(tab, host, origin) != null
    }

    companion object {
        private const val TAG = "MikaWebStack"

        /** Each parallel subtask uses its own off-screen browser, created on the first navigation or URL read and closed as soon as the subtask ends. */
        internal fun childPort(
            context: Context, childId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            resources: ResourceScope, assetLoaders: List<WebViewAssetLoader> = emptyList(),
        ): LazyWebPort = LazyWebPort {
            resources.acquire(Dispatchers.Main, WebStack::close) {
                create(context, childId, widthPx, heightPx, densityDpi, assetLoaders, keeper = null)
            }.screen
        }

        /** Creates one; throws when it cannot be built (system WebView unavailable, etc.), in which case the caller only offers the phone UI. Call on the main thread. */
        fun create(
            context: Context, taskId: String, widthPx: Int, heightPx: Int, densityDpi: Int,
            assetLoaders: List<WebViewAssetLoader> = emptyList(),
            workspace: Workspace? = null,
            keeper: PageKeeper? = WebOwner.of(context).pageKeeper,
        ): WebStack {
            val host = WebHost(context, widthPx, heightPx, densityDpi)
            return try {
                val pool = WebTabPool(context, host, assetLoaders = assetLoaders, registry = WebSites.registry(context))
                WebStack(context.applicationContext, taskId, host, pool, workspace, keeper)
            } catch (e: Exception) {
                host.close()
                throw e
            }
        }
    }
}
