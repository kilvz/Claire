package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.wangzan101.mika.cap.web.LoginProbe
import com.wangzan101.mika.cap.web.LoginState
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.screen.uistate.PageDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * The web page the browser page currently has open. [desktop]: the tab is currently using the desktop version of the page; [loginFor]: this is a
 * "log in for Claire" session, for the site to log in to (opened under the identity Claire uses when acting, see [BrowserIdentity]); null for
 * ordinary browsing.
 */
data class BrowserPage(
    val url: String,
    val title: String,
    val progress: Int,
    val loading: Boolean,
    val canBack: Boolean,
    val canForward: Boolean,
    val desktop: Boolean = false,
    val loginFor: String? = null,
)

/** The file chooser a page pops up in the user browser; the chosen URI is handed only to the tab that made the request. */
class FilePickRequest(val tab: WebTab, val callback: ValueCallback<Array<Uri>>, val params: WebChromeClient.FileChooserParams) {
    val id: String = UUID.randomUUID().toString()
    @Volatile var started = false
}

/** One entry in the tab list: [active] is the one being viewed right now. */
data class BrowserTab(val id: String, val title: String, val url: String, val host: String?, val active: Boolean)

/** Fullscreen video: the view supplied by the page and the callback it requires. */
class FullscreenVideo(val view: View, val callback: WebChromeClient.CustomViewCallback)

/** In-page find: whether it is open, the search term, how many matches there are, and which match is current (all counted from 1). */
data class FindState(val active: Boolean = false, val query: String = "", val matches: Int = 0, val index: Int = 0)

/** WebView reports the current match ordinal counting from 0; the UI displays it from 1, and shows 0 when there are no matches. */
internal fun findMatchIndex(active: Int, matches: Int): Int =
    if (matches > 0) (active + 1).coerceIn(1, matches) else 0

/**
 * The browser the user uses themselves ("Browser" in the sidebar): one visible [WebTab] sharing the same web profile (cookies, web storage) as
 * the Agent's tabs. Logging in here also means being logged in when Claire acts. Which version of a page is used is decided by [BrowserIdentity]:
 * the mobile version by default, switchable per site to the desktop version (recorded in [modes]); for "log in for Claire" it uses the version
 * Claire uses when acting; the policy is installed only on this tab (WebDevtools.setModePolicy), so the Agent's tabs are unaffected.
 *
 * It does not enter any task's tab pool and does not go through the takeover in [com.wangzan101.mika.cap.web.WebHandover]: the Agent's tabs and
 * takeovers carry on as usual, independently of it.
 * Tabs live in the process (the same WebView keeps being used across rotation and page recreation) and are closed when leaving the browser page.
 * When the UI has more than one instance the browser page may exist in several places at once: see [PageHosts] for which place the view is attached
 * to and which place leaving closes the page.
 * The WebView's context is a [MutableContextWrapper]: it is switched to that Activity when attached to the UI (dropdowns and long-press menus need
 * it) and switched back to the App when detached.
 * Pages go through [WebUrlPolicy.checkNavigable] before opening; visited pages are recorded in [recent]; once a page settles, the login state is
 * checked and recorded in the site registry.
 * When the user asks Claire on a page, the page being viewed is lent to that conversation's task as its current web tab (see [loans]): the lent page
 * follows when a page opens or closes a popup, and when the page is closed it is handed to the task to keep using rather than being destroyed; when
 * the user touches the page by hand ([userTouched]) the task borrowing it is told.
 * Only called on the main thread.
 */
class UserBrowser private constructor(private val app: Context) {
    val sites: SiteRegistry = WebSites.registry(app)
    private val dir = File(app.filesDir, "web")
    val recent = RecentPages(dir)
    val favorites = BrowserFavorites(dir)
    val bookmarks = PageBookmarks(dir)
    val looks = SiteLooks(dir)
    /** Downloads in the user browser: handed to the system downloader, and the list is requested from the system as well. */
    val downloads = BrowserDownloads(app, dir)
    /** The search engine used by the start page and the address bar; the user can switch it on the start page or add their own. */
    val searchEngine = SearchEngineStore(dir)
    private val modes = UserSiteModes(dir)
    /** The page being viewed, lent to a task (see [PageLoans]). */
    val loans = PageLoans<WebTab>()
    /** Tab number: unique for every tab (a page and the popup it opens may be lent to the same task one after the other). */
    private var tabCount = 0

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wrapper = MutableContextWrapper(app)
    /** Ordinary tabs (popups are not included), in the order they were opened. */
    private val openTabs = ArrayList<WebTab>()
    private var tab: WebTab? = null
        set(value) {
            if (field !== value) denyPendingPermissions()
            field = value
        }
    /** While a page's popup is open ([tab] is the popup), the page that opened it; null when there is no popup. */
    private var opener: WebTab? = null
    private val hosts = PageHosts<ViewGroup, Context>()
    /** Each tab's own "log in for Claire" site; null for ordinary browsing. */
    private val loginFor = HashMap<WebTab, String?>()
    /** Each tab's own login probe task. */
    private val probes = HashMap<WebTab, Runnable>()

    private val _page = MutableStateFlow<BrowserPage?>(null)
    /** When null there is no open web page (the start page is shown). */
    val page: StateFlow<BrowserPage?> = _page.asStateFlow()

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    /** The open ordinary tabs, in the order they were opened; [BrowserTab.active] is the one being viewed. */
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _find = MutableStateFlow(FindState())
    /** The in-page find state; [FindState.active] = false when it is closed. */
    val find: StateFlow<FindState> = _find.asStateFlow()

    private val _fullscreen = MutableStateFlow<FullscreenVideo?>(null)
    /** The video currently playing fullscreen; null when there is none. */
    val fullscreen: StateFlow<FullscreenVideo?> = _fullscreen.asStateFlow()

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    /** A dialog the page has popped up and is waiting for the user to answer. */
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** A one-line notice for the user (what was blocked, the page crashed); call [noticeShown] after showing it. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _filePick = MutableStateFlow<FilePickRequest?>(null)
    /** The page wants to pick a file and waits for the UI to show the system chooser; call [answerFileChooser] once chosen. */
    val filePick: StateFlow<FilePickRequest?> = _filePick.asStateFlow()

    private val mediaPermissions = BrowserPermissionRequests<PermissionRequest>()
    /** The page wants the camera, microphone, etc. and waits for the UI to ask the user; call [answerPagePermission] to answer. */
    internal val pagePermission = mediaPermissions.pending

    private val locationPermissions = BrowserPermissionRequests<GeolocationPermissions.Callback>()
    /** The page wants geolocation and waits for the UI to ask the user; call [answerGeolocation] to answer. */
    internal val geoPermission = locationPermissions.pending

    /** The system chooser has returned: hand the result back to the page; a cancellation (empty) is handed back as null. */
    fun answerFileChooser(id: String, uris: List<Uri>?) {
        val r = _filePick.takeFileRequest(id, FilePickRequest::id) ?: return
        r.callback.onReceiveValue(uris?.takeIf { tab === r.tab && it.isNotEmpty() }?.toTypedArray())
    }

    /** The user allows or denies the page's use of the camera and microphone. */
    fun answerPagePermission(id: String, grant: Boolean) {
        val request = mediaPermissions.take(id)?.value ?: return
        if (grant) {
            // Grant only the camera and microphone; MIDI and protected media bundled into the request are not allowed through
            val allowed = request.resources
                .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
                .toTypedArray()
            request.grant(allowed)
        } else {
            request.deny()
        }
    }

    /** The user allows or denies the page's use of geolocation. */
    fun answerGeolocation(id: String, grant: Boolean) {
        val r = locationPermissions.take(id) ?: return
        r.value.invoke(r.origin, grant, false)
    }

    private fun answerPendingRequests() {
        _filePick.value?.callback?.onReceiveValue(null)
        _filePick.value = null
        denyPendingPermissions()
    }

    private fun denyPendingPermissions() {
        pagePermission.value?.let { answerPagePermission(it.id, false) }
        geoPermission.value?.let { answerGeolocation(it.id, false) }
    }

    /**
     * Opens whatever was typed into the address bar or search box (see [AddressInput]). When it cannot be opened, returns a reason to show the user
     * without touching the current page.
     */
    fun submit(input: String): String? {
        val url = AddressInput.resolve(input, searchEngine.choices.value.selected) ?: return null
        return open(url)
    }

    /** Opens a URL for ordinary browsing; returns the reason when it cannot be opened. */
    fun open(url: String): String? = load(url, null)

    /** Opens a page in a new tab; with [select] false it stays in the background without switching to it. Returns the reason when it cannot be opened. */
    fun openInNewTab(url: String, select: Boolean): String? {
        refusal(url)?.let { return it }
        val prev = tab
        newTab()
        val error = open(url)
        if (error != null) {
            tab?.let { closeTab(it.id) }
            prev?.let { selectTab(it.id) }
            return error
        }
        if (!select && prev != null) selectTab(prev.id)
        return null
    }

    /** Opens a favorite site: ones not yet logged in go through "log in for Claire" and open with the version Claire uses when acting. */
    fun openFavorite(site: FavoriteSite): String? =
        load("https://${site.host}/", site.host.takeIf { BrowserIdentity.loginFlow(site) })

    /**
     * Switches the current site to the desktop version ([desktop]) or back to the mobile version, remembers it for next time; it no longer counts as
     * "log in for Claire". Reopens the current page with the new version.
     */
    fun setDesktop(desktop: Boolean) {
        val t = tab ?: return
        val url = currentUrl() ?: return
        val host = WebUrlPolicy.host(url) ?: return
        modes.set(host, desktop)
        loginFor[t] = null
        navigate(t, url)
    }

    /** The site "log in for Claire" currently needs to log in to; null for ordinary browsing. Only called on the main thread. */
    fun loginHost(): String? = tab?.let { loginFor[it] }

    /**
     * "I'm logged in" was tapped in "log in for Claire": take a look at the page. If it clearly is not logged in, show a notice; if it is logged in,
     * or it cannot be told (taking the user's word), mark this site as logged in and return to the start page. When the page cannot be inspected yet
     * because it is still loading, wait [CONFIRM_WAIT_MS] and treat it as cannot be told.
     */
    fun confirmLogin() {
        val t = tab ?: return
        val site = loginFor[t] ?: return
        var done = false
        fun finish(state: LoginState) {
            if (done || t !== tab || t.destroyed) return
            done = true
            if (state == LoginState.SignedOut) {
                _notice.value = tr(R.string.browser_not_logged_in)
                return
            }
            sites.markLoggedIn(site)
            currentUrl()?.let(WebUrlPolicy::host)?.takeIf { it != site && BrowserFavorites.siteKey(it) == BrowserFavorites.siteKey(site) }?.let(sites::markLoggedIn)
            WebTab.cookieManager().flush()
            Log.i(TAG, "browser login confirmed for $site ($state)")
            _notice.value = LOGGED_IN_NOTICE
            closeActiveTab()
        }
        val timeout = Runnable { finish(LoginState.Unknown) }
        main.postDelayed(timeout, CONFIRM_WAIT_MS)
        LoginProbe.probe(t) { _, state ->
            main.post {
                main.removeCallbacks(timeout)
                finish(state)
            }
        }
    }

    private fun load(url: String, login: String?): String? {
        refusal(url)?.let { return it }
        stopFind()
        val t = tab ?: newTopTab()
        loginFor[t] = login
        _page.value = BrowserPage(url, "", 0, true, false, false, desktop = !t.devtools.isMobile(t), loginFor = login)
        navigate(t, url)
        return null
    }

    private fun navigate(t: WebTab, url: String) {
        scope.launch {
            // Like the Agent's tabs: before navigating, apply this tab's rules to the desktop/mobile settings and emulation (waiting at most a moment)
            t.devtools.beforeLoad(t, url)
            if (!t.destroyed) t.view.loadUrl(url)
        }
    }

    private fun currentUrl(): String? = tab?.view?.url?.takeIf { it.startsWith("http") } ?: _page.value?.url

    fun back() { tab?.view?.takeIf { it.canGoBack() }?.goBack() }
    fun forward() { tab?.view?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { tab?.view?.reload() }
    fun stop() {
        tab?.view?.stopLoading()
        refresh()
    }

    /** System back: go back in the web page if it can, otherwise close an open dialog, otherwise close the current tab. Returns false when no web page is open. */
    /** Whether the current tab can still go back within itself. */
    fun canGoBackInTab(): Boolean = tab?.view?.canGoBack() == true

    fun systemBack(): Boolean {
        val v = tab?.view ?: return false
        if (v.canGoBack()) v.goBack() else if (opener != null) closePopup() else closeActiveTab()
        return true
    }

    /** Closes all tabs and returns to the start page. Tabs lent to a task are handed over for the task to keep using (not destroyed once the task has taken them). */
    fun closePage() {
        answerPendingRequests()
        stopFind()
        clearFullscreen()
        probes.values.forEach(main::removeCallbacks)
        probes.clear()
        loginFor.clear()
        opener = null
        tab?.takeIf { it !in openTabs }?.let { popup ->
            if (!loans.closing(popup)) popup.destroy() else handedOff(popup)
        }
        val all = openTabs.toList()
        openTabs.clear()
        all.forEach { t ->
            if (!loans.closing(t)) t.destroy() else handedOff(t)
        }
        loans.withdraw()
        tab = null
        _page.value = null
        _dialog.value = null
        publishTabs()
    }

    /** Opens an empty tab and shows the start page. */
    fun newTab() {
        if (opener != null) closePopup()
        stopFind()
        clearFullscreen()
        newTopTab()
        _page.value = null
        _dialog.value = null
    }

    /** Switches to tab [id]; does nothing when it cannot be found. */
    fun selectTab(id: String) {
        val target = openTabs.firstOrNull { it.id == id } ?: return
        if (target === tab && opener == null) return
        if (opener != null) closePopup()
        if (target === tab) return
        stopFind()
        exitFullscreen()
        val prev = tab
        if (prev != null && loans.closing(prev)) {
            openTabs.remove(prev)
            loginFor.remove(prev)
            probes.remove(prev)?.let(main::removeCallbacks)
            handedOff(prev)
        }
        tab = target
        showActive()
        publishTabs()
    }

    /** Closes tab [id]; if it was the one being viewed, switch to a neighbor, or return to the start page when there is none. */
    fun closeTab(id: String) {
        val t = openTabs.firstOrNull { it.id == id } ?: return
        // The tab being closed is the popup's parent page: close the popup first, otherwise closing the popup would return to a page that no longer exists
        if (t === opener) closePopup()
        if (t === tab) denyPendingPermissions()
        val index = openTabs.indexOf(t)
        openTabs.removeAt(index)
        loginFor.remove(t)
        probes.remove(t)?.let(main::removeCallbacks)
        // Only a lent or currently viewed tab goes through the lending logic; closing a background tab must not void the one the current page promised to lend
        val handed = if (t === tab || loans.current?.tab === t) loans.closing(t) else false
        if (!handed) t.destroy() else handedOff(t)
        if (t === tab) {
            stopFind()
            clearFullscreen()
            tab = openTabs.getOrNull(index) ?: openTabs.lastOrNull()
            if (tab == null) {
                _page.value = null
                _dialog.value = null
                wrapper.baseContext = app
            } else {
                showActive()
            }
        }
        publishTabs()
    }

    /** Closes the tab being viewed (closing the popup first when one is open). */
    fun closeActiveTab() {
        if (opener != null) {
            closePopup()
            return
        }
        tab?.let { closeTab(it.id) } ?: run {
            _page.value = null
            publishTabs()
        }
    }

    private fun newTopTab(): WebTab = createTab().also {
        openTabs += it
        tab = it
        loginFor[it] = null
        place()
        publishTabs()
    }

    private fun publishTabs() {
        val active = tab
        _tabs.value = openTabs.map { t ->
            val url = t.view.url?.takeIf { it.startsWith("http") }.orEmpty()
            val title = t.view.title?.takeIf { it.isNotBlank() && it != url } ?: url
            BrowserTab(t.id, title, url, WebUrlPolicy.host(url), t === active)
        }
    }

    /** Attaches the tab being viewed to the UI; a newly opened tab that has not loaded yet shows the start page. */
    private fun showActive() {
        val t = tab ?: run {
            _page.value = null
            return
        }
        place()
        val url = t.view.url
        if (url == null || url.isEmpty() || url == "about:blank") _page.value = null else refresh()
        _dialog.value = t.dialogs.current
    }

    // ---------- In-page find ----------

    /** Opens the in-page find bar. */
    fun startFind() {
        if (tab == null) return
        _find.value = FindState(active = true)
    }

    fun setFindQuery(query: String) {
        val v = tab?.view ?: return
        val q = query.trim()
        if (q.isEmpty()) {
            v.clearMatches()
            _find.value = FindState(active = true)
        } else {
            v.findAllAsync(q)
            _find.value = FindState(active = true, query = query, matches = 0, index = 0)
        }
    }

    fun findNext(forward: Boolean) {
        tab?.view?.findNext(forward)
    }

    fun stopFind() {
        tab?.view?.clearMatches()
        _find.value = FindState()
    }

    // ---------- Fullscreen video ----------

    /** Exits fullscreen so the page returns to inline playback. */
    fun exitFullscreen() {
        val f = _fullscreen.value ?: return
        _fullscreen.value = null
        f.callback.onCustomViewHidden()
    }

    private fun clearFullscreen() {
        val f = _fullscreen.value ?: return
        _fullscreen.value = null
        (f.view.parent as? ViewGroup)?.removeView(f.view)
    }

    // ---------- Clear browsing data ----------

    /** Clears the selected browsing data. Site data logs out of every site, including the ones Claire uses when acting. */
    fun clearBrowsingData(history: Boolean, siteData: Boolean, cache: Boolean) {
        if (history) recent.clear()
        if (siteData) {
            runCatching {
                WebTab.cookieManager().removeAllCookies(null)
                WebTab.cookieManager().flush()
                WebTab.webStorage().deleteAllData()
                WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword()
                sites.clear()
            }
            loginFor.keys.forEach { loginFor[it] = null }
            refresh()
        }
        if (cache) {
            openTabs.forEach { runCatching { it.view.clearCache(true) } }
            tab?.let { runCatching { it.view.clearCache(true) } }
        }
    }

    // A tab handed to a task: it is no longer ours (the view has been attached by the task to its own web display), and the context is switched back to the App (not keeping the UI around)
    private fun handedOff(t: WebTab) {
        wrapper.baseContext = app
        Log.i(TAG, "browser page ${t.id} handed to the task that borrowed it")
    }

    // ---------- Ask Claire on this page ----------

    /** The page being viewed right now (used to describe it to Claire); null when no page is open. */
    fun pageInfo(): com.wangzan101.mika.core.agent.AttachedPage.Page? {
        val url = currentUrl()?.takeIf { it.startsWith("http") } ?: return null
        val title = tab?.view?.title?.takeIf { it != url }.orEmpty().ifEmpty { _page.value?.title.orEmpty() }
        return com.wangzan101.mika.core.agent.AttachedPage.Page(url, title)
    }

    /** Reads the text the user selected on the page and passes it to [onText] (null when nothing is selected or it cannot be read). */
    fun readSelection(onText: (String?) -> Unit) {
        val t = tab ?: return onText(null)
        t.view.evaluateJavascript(SELECTION_SCRIPT) { raw ->
            val text = runCatching { org.json.JSONArray("[$raw]").optString(0) }.getOrNull()
            onText(text?.takeIf { raw != "null" && it.isNotBlank() })
        }
    }

    /** The user asked on this page, so the page is lent to the task the conversation [conversationId] is about to start. */
    fun lend(conversationId: String) = loans.offer(conversationId)

    /** Called when the task [runKey] of conversation [conversationId] starts, to claim the page promised to it; null when nothing was promised, the page is gone, or it has already been lent. */
    fun claim(conversationId: String, runKey: String): PageLoans.Loan<WebTab>? =
        loans.claim(conversationId, runKey, tab?.takeIf { !it.destroyed && !it.crashed })

    /** The user touched the page being viewed themselves (a real touch, not input injected by Claire). */
    fun userTouched() {
        tab?.let(loans::touched)
    }

    /** Answers the page's dialog. */
    fun answerDialog(accept: Boolean, text: String?) {
        tab?.dialogs?.answer(accept, text)
        _dialog.value = tab?.dialogs?.current
    }

    fun noticeShown() {
        _notice.value = null
    }

    /** A place a browser page occupies (on one instance of the UI); used with [openHost], [setContainer], [hostStarted], [closeHost]. */
    fun newHost(activity: Context): PageHosts.Host<ViewGroup, Context> = PageHosts.Host(activity)

    /** This place has entered the browser page. */
    fun openHost(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.open(host)
        place()
    }

    /** This place is back in the foreground: the web view is attached here. */
    fun hostStarted(host: PageHosts.Host<ViewGroup, Context>) {
        hosts.started(host)
        place()
    }

    /** The container this place reserves for the page has appeared ([container]) or is gone (null). */
    fun setContainer(host: PageHosts.Host<ViewGroup, Context>, container: ViewGroup?) {
        val old = host.container
        hosts.setContainer(host, container)
        if (old !== container) old?.removeAllViews()
        place()
    }

    /**
     * This place has left the browser page. When it is the last place and this is not a rotation ([keepPage] is false), the page is closed;
     * when it is not the last place, the page is left for another place (the view moves elsewhere).
     */
    fun closeHost(host: PageHosts.Host<ViewGroup, Context>, keepPage: Boolean) {
        host.container?.removeAllViews()
        if (hosts.close(host) && !keepPage) closePage() else place()
    }

    /**
     * Attaches the web view to the place it belongs ([PageHosts.target]) and switches the context to that place's UI; when there is nowhere to
     * attach it, detaches it and switches the context back to the App (not keeping the old UI around).
     */
    private fun place() {
        val v = tab?.view
        val target = hosts.target()
        val container = target?.container
        if (v == null || container == null) {
            v?.let { (it.parent as? ViewGroup)?.removeView(it) }
            wrapper.baseContext = app
            return
        }
        if (v.parent === container) return
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper.baseContext = target.activity
        container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url) ?: return null
        return if (WebUrlPolicy.external(url) != null) tr(R.string.browser_external_link) else tr(R.string.browser_bad_scheme)
    }

    private fun createTab(): WebTab = WebTab(wrapper, "browser${++tabCount}", OWNER).also { t ->
        t.view.settings.apply {
            builtInZoomControls = true
            displayZoomControls = false
        }
        // The user looks at and taps it by hand: even the desktop version does not fix the zoom, so pinch-to-zoom works
        t.devtools.setHandOperated(t)
        t.devtools.setModePolicy(t) { host ->
            BrowserIdentity.mobile(loginForMika = loginFor[t] != null, agentMobile = t.devtools.agentWantsMobile(host), userDesktop = modes.isDesktop(host))
        }
        t.view.setFindListener { active, matches, _ ->
            if (t === tab) {
                // activeMatchOrdinal counts from 0; the UI displays from 1, and shows 0 when there are 0 matches
                _find.value = _find.value.copy(
                    matches = matches.coerceAtLeast(0),
                    index = findMatchIndex(active, matches),
                )
            }
        }
        t.listener = listener
    }

    private fun refresh() {
        val t = tab ?: return
        val v = t.view
        val url = v.url?.takeIf { it.isNotEmpty() && it != "about:blank" } ?: _page.value?.url ?: return
        _page.value = BrowserPage(
            url, v.title.orEmpty().takeIf { it != url }.orEmpty(), t.progress, t.loading, v.canGoBack(), v.canGoForward(),
            desktop = !t.devtools.isMobile(t), loginFor = loginFor[t],
        )
    }

    private val listener = object : WebTab.Listener {
        override fun onPageChanged(tab: WebTab) {
            publishTabs()
            val url = tab.view.url
            if (url != null && url.startsWith("http")) tab.view.title?.let { recent.retitle(url, it) }
            if (tab === this@UserBrowser.tab) refresh()
        }

        override fun onPageFinished(tab: WebTab) {
            publishTabs()
            val url = tab.view.url ?: return
            if (!url.startsWith("http")) return
            recent.add(url, tab.view.title?.takeIf { it != url })
            bookmarks.retitle(url, tab.view.title.orEmpty())
            WebTab.cookieManager().flush()
            readName(tab, url)
            scheduleProbe(tab, 0)
            if (tab === this@UserBrowser.tab) refresh()
        }

        override fun onIcon(tab: WebTab, icon: Bitmap) {
            val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
            if (icon.width < MIN_ICON_PX || icon.height < MIN_ICON_PX) return
            val side = minOf(maxOf(icon.width, icon.height), MAX_ICON_PX)
            val scaled = runCatching { Bitmap.createScaledBitmap(icon, side, side, true) }.getOrNull() ?: return
            scope.launch {
                val targets = looks.iconTargets(host)
                val saved = withContext(Dispatchers.IO) {
                    targets.filter { h ->
                        runCatching {
                            val f = looks.iconFile(h)
                            f.parentFile?.mkdirs()
                            f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.getOrDefault(false)
                    }
                }
                saved.forEach(looks::iconSaved)
            }
        }

        /** The tab the user is viewing wants to pick a file: hand it to the UI to show the system chooser. */
        override fun onFileChooser(tab: WebTab, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            val previous = _filePick.value
            _filePick.value = FilePickRequest(tab, callback, params)
            previous?.callback?.onReceiveValue(null)
            return true
        }

        /** The tab the user is viewing wants the camera, microphone, etc.: hand it to the UI to ask the user. */
        override fun onPermissionRequest(tab: WebTab, request: PermissionRequest): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            // Accept only the camera and microphone; MIDI, protected media and the like are still denied
            if (request.resources.none { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }) return false
            mediaPermissions.replace(request.origin.toString(), request)?.value?.deny()
            return true
        }

        override fun onPermissionRequestCanceled(tab: WebTab, request: PermissionRequest) {
            pagePermission.value?.takeIf { it.value === request }?.let { mediaPermissions.take(it.id) }
        }

        /** The tab the user is viewing wants geolocation: hand it to the UI to ask the user. */
        override fun onGeolocationRequest(tab: WebTab, origin: String?, callback: GeolocationPermissions.Callback): Boolean {
            if (tab !== this@UserBrowser.tab || origin.isNullOrBlank()) return false
            geoPermission.value?.let { answerGeolocation(it.id, false) }
            locationPermissions.replace(origin, callback)
            return true
        }

        override fun onGeolocationPermissionsHidePrompt(tab: WebTab) {
            if (tab === this@UserBrowser.tab) geoPermission.value?.let { locationPermissions.take(it.id) }
        }

        override fun onDialog(tab: WebTab) {
            if (tab === this@UserBrowser.tab) _dialog.value = tab.dialogs.current
        }

        override fun onFullscreen(tab: WebTab, view: View, callback: WebChromeClient.CustomViewCallback): Boolean {
            if (tab !== this@UserBrowser.tab) return false
            clearFullscreen()
            _fullscreen.value = FullscreenVideo(view, callback)
            return true
        }

        override fun onExitFullscreen(tab: WebTab) {
            if (tab === this@UserBrowser.tab) clearFullscreen()
        }

        override fun onExternalLink(tab: WebTab, link: ExternalLink) {
            if (tab === this@UserBrowser.tab) _notice.value = tr(R.string.browser_blocked_app_link)
        }

        override fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { downloads.enqueue(url, userAgent, contentDisposition, mime) }
                _notice.value = if (name != null) tr(R.string.browser_download_started, name) else tr(R.string.browser_download_failed_start)
            }
        }

        override fun onCrashed(tab: WebTab) {
            // Destroy outside the callback
            main.post {
                if (opener != null && tab === this@UserBrowser.tab) closePopup() else closeTab(tab.id)
                _notice.value = tr(R.string.browser_crashed)
            }
        }

        /**
         * The page wants to open a new window (target=_blank, window.open): only user-initiated ones are accepted, and only one level deep. The new
         * window is displayed over the current page, which is kept ([opener]): a popup such as "sign in with a third-party account" has to hand the
         * result back to the page that opened it once login finishes, and without that page it would be left on a blank screen.
         * When the popup closes itself, or cannot go back any further inside the popup, return to the page that opened it.
         */
        override fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture || tab !== this@UserBrowser.tab || opener != null) return false
            if (tab !in openTabs) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            stopFind()
            val popup = createTab()
            transport.webView = popup.view
            resultMsg.sendToTarget()
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            opener = tab
            loginFor[popup] = null
            this@UserBrowser.tab = popup
            place()
            // The one lent to the task is the visible one: it follows along and becomes the popup
            loans.swapped(tab, popup)
            publishTabs()
            return true
        }

        override fun onCloseWindow(tab: WebTab) {
            if (tab === this@UserBrowser.tab) closePopup()
        }
    }

    /** Closes the popup and returns to the page that opened it; returns false when there is no popup. */
    private fun closePopup(): Boolean {
        val o = opener ?: return false
        denyPendingPermissions()
        opener = null
        stopFind()
        clearFullscreen()
        tab?.let { popup ->
            loginFor.remove(popup)
            probes.remove(popup)?.let(main::removeCallbacks)
            loans.swapped(popup, o)
            popup.destroy()
        }
        tab = o
        showActive()
        publishTabs()
        return true
    }

    /**
     * Page scripts usually draw the account area only after loading finishes: pause for a moment before checking the login state; if the page is
     * still loading or the URL has changed, wait again, at most a few times.
     * Sites already in the registry are marked or unmarked according to what was seen; sites that are not in it are recorded only when the page is
     * clearly logged in (the user logged in to a new site here).
     */
    private fun scheduleProbe(t: WebTab, attempt: Int) {
        probes.remove(t)?.let(main::removeCallbacks)
        val urlThen = t.view.url
        val run = Runnable {
            probes.remove(t)
            if (t.destroyed || (t !in openTabs && t !== tab)) return@Runnable
            if (t.loading || t.view.url != urlThen) {
                if (attempt < PROBE_RETRIES) scheduleProbe(t, attempt + 1)
                return@Runnable
            }
            LoginProbe.probe(t) { host, state ->
                val known = sites.list().any { it.host == host }
                val login = loginFor[t]
                val wasIn = login != null && BrowserFavorites.loggedIn(sites.list(), login)
                when {
                    known -> sites.loginObserved(host, state)
                    state == LoginState.SignedIn -> sites.markLoggedIn(host)
                    else -> Unit
                }
                // In "log in for Claire", detecting that the login succeeded tells the user once (the banner collapses with the login state)
                if (login != null && !wasIn && state == LoginState.SignedIn && BrowserFavorites.siteKey(host) == BrowserFavorites.siteKey(login)) {
                    _notice.value = LOGGED_IN_NOTICE
                }
                Log.i(TAG, "browser login probe $host: $state (known=$known)")
            }
        }
        probes[t] = run
        main.postDelayed(run, PROBE_DELAY_MS)
    }

    /** After the page finishes loading, read the site name (the name the page declares or its title) and record it in [looks]. */
    private fun readName(t: WebTab, url: String) {
        val host = WebUrlPolicy.host(url) ?: return
        if (AddressInput.searchQuery(url, searchEngine.choices.value.all) != null) return
        t.view.evaluateJavascript(SiteNames.SCRIPT) { raw ->
            if (t.destroyed) return@evaluateJavascript
            val (declared, title) = SiteNames.parse(raw) ?: return@evaluateJavascript
            val (name, rank) = SiteNames.pick(declared, title, SiteNames.isHomePage(url)) ?: return@evaluateJavascript
            looks.named(host, name, rank)
        }
    }

    companion object {
        private const val TAG = "MikaUserBrowser"
        /** This tab does not belong to any task. */
        const val OWNER = "user-browser"
        private const val PROBE_DELAY_MS = 1_500L
        private const val PROBE_RETRIES = 3
        private const val CONFIRM_WAIT_MS = 2_500L
        /** Accepted site icons are at least this large and are stored at most this large (pixels). */
        private const val MIN_ICON_PX = 16
        private const val MAX_ICON_PX = 128
        val LOGGED_IN_NOTICE: String get() = tr(R.string.browser_logged_in)
        /** The text selected on the page (including the selected part inside an input field). */
        private const val SELECTION_SCRIPT = "(function(){var s=window.getSelection();var t=s?String(s):'';" +
            "var a=document.activeElement;if(!t&&a&&typeof a.selectionStart==='number'&&a.type!=='password')" +
            "t=String(a.value||'').substring(a.selectionStart,a.selectionEnd);return t;})()"

        /** The existing instance in the process; null when the browser page has never been opened (no instance is created for it). */
        fun existing(): UserBrowser? = instance

        @Volatile private var instance: UserBrowser? = null

        fun of(context: Context): UserBrowser =
            instance ?: synchronized(this) { instance ?: UserBrowser(context.applicationContext).also { instance = it } }
    }
}
