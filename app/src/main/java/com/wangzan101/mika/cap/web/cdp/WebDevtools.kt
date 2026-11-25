package com.wangzan101.mika.cap.web.cdp

import android.content.Context
import android.icu.util.ULocale
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebViewCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.device.shell.DevtoolsSocket
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import android.os.ParcelFileDescriptor
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.wangzan101.mika.cap.web.WebOwner

/**
 * DevTools session entry point for each web tab. Null when it cannot connect (not supported by the system, blocked, tab already closed),
 * and the caller falls back to the script-injection approach. All tabs are desktop by default; that is the new baseline and does not depend on the session: without a session, the WebView settings'
 * desktop UA, UA-CH (when supported) and wide viewport still apply, only the emulation layer is missing (viewport width, navigator.platform).
 *
 * Routes are tried in order and the result remembered: direct to this process's `webview_devtools_remote_<pid>`; if refused, connect through [relay] (the built-in helper, shell identity; supplied by the assembly layer); if neither works, there is none.
 * Normally one per process (provided by the App, see [of]); tests can build their own, and changing the session source or the route only affects that instance.
 * When a tab is used for the first time, a marker is written, the tab is found among the targets one by one and its targetId remembered; that single session is used from then on; on disconnect it reconnects once, and if that fails the tab is degraded (retried later).
 *
 * Desktop web pages are handled here as well: as soon as a tab is created, the WebView settings are switched to a desktop UA (the fallback when DevTools cannot connect) and the identity patch is injected at document start,
 * then once the session connects, emulation switches UA-CH, viewport, touch and pointer all to desktop (see [DesktopProfile]); sites remembered as mobile switch back to the default.
 * When the user has chosen the UI language in Claire, pages of both versions are requested in that language (see [PageLanguage]); when following the system, nothing is changed.
 */
class WebDevtools(private val relay: ((String) -> ParcelFileDescriptor?)?) {
    enum class Route { Unknown, Direct, Relay, None }

    /** For tests: direct only / relay only / treat as unreachable; null means automatic. Call [resetRoute] after changing it. */
    @Volatile var forceRoute: Route? = null

    private val _route = MutableStateFlow(Route.Unknown)
    /** The most recently detected route; Unknown means it has not been probed yet. */
    val route: StateFlow<Route> = _route.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** Whether DevTools is usable right now (for diagnostics and the UI). */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    @Volatile var provider: (WebTab) -> CdpSession? = this::cached

    /** An already connected session; if not connected yet, starts connecting in the background and returns null first. Non-blocking, callable from any thread. */
    fun session(tab: WebTab): CdpSession? =
        // Sessions from other sources (e.g. test-provided ones) must also convert Input coordinates according to this tab's viewport emulation
        provider(tab)?.also { s -> links[tab]?.let { s.inputScale = it.inputScale } }

    /** Waits for the session to connect (at most [timeoutMs]); null if it cannot connect. Do not call it while holding a main-thread wait. */
    suspend fun awaitSession(tab: WebTab, timeoutMs: Long = ATTACH_TIMEOUT_MS): CdpSession? {
        val link = links[tab] ?: return null
        return bounded(timeoutMs) { attach(link) }
    }

    /** This tab currently uses the mobile version of the page. */
    fun isMobile(tab: WebTab): Boolean = links[tab]?.mobile ?: false

    /** The DevTools target id of this tab; null if it has never connected. */
    fun targetId(tab: WebTab): String? = links[tab]?.targetId

    // ---------- Tab lifecycle (called by WebTab) ----------

    /** A tab has just been created (main thread): enable the debugging interface, switch to the desktop WebView settings, and turn off the X-Requested-With header. */
    fun prepare(tab: WebTab, context: Context) {
        val st = tab.view.settings
        init(context, st)
        val link = Link(tab)
        links[tab] = link
        // The view changed size (moved to another host display, entering or leaving the takeover UI): the desktop viewport scale is recomputed
        tab.view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) onViewResized(link)
        }
        // REQUESTED_WITH_HEADER_ALLOW_LIST is a public constant in androidx.webkit; lint incorrectly reports it as library-internal
        @Suppress("RestrictedApi")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, emptySet()) }
        }
        applySettings(st, mobile = false)
        applyIdentityScript(link)
        link.acceptLanguage = chosenAcceptLanguage()
    }

    /** Before the main document loads (main thread, may suspend): pick mobile or desktop for the target site, and try to connect the session and set up emulation before the first navigation. */
    suspend fun beforeLoad(tab: WebTab, url: String) {
        val link = links[tab] ?: return
        val mobile = wantsMobile(link, url) ?: false
        val changed = link.mobile != mobile
        if (changed) switchMode(link, mobile)
        // The user changed Claire's language midway: already open tabs switch over before the next main document load (emulation is re-set here, so this page is requested in the new language)
        chosenAcceptLanguage().let { lang ->
            if (lang != link.acceptLanguage) {
                link.acceptLanguage = lang
                link.configured = null
            }
        }
        // Connecting and emulation happen in the background, and only a short wait is made here: on timeout it loads as usual (this page takes the WebView-settings fallback first and switches once connected)
        bounded(BEFORE_LOAD_WAIT_MS) {
            val s = attach(link)
            if (s != null && link.configured != link.mobile) link.lock.withLock { configure(link, s) }
            s
        }
    }

    // Runs in a separate coroutine; the caller leaves once the limit is reached and is not held up by blocking reads inside
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T?): T? {
        val job = scope.async { block() }
        return withTimeoutOrNull(timeoutMs) { job.await() }
    }

    /**
     * The main document started loading (main thread): when a link is clicked onto, or a redirect lands on, a site of the other version, switch over and reload once.
     * Non-web pages (about:blank and the like) are left alone.
     */
    fun onPageStarted(tab: WebTab, url: String?) {
        val link = links[tab] ?: return
        if (url == null || !url.startsWith("http")) return
        link.loadedBeforeAttach = true
        val mobile = wantsMobile(link, url) ?: return
        if (link.mobile == mobile) return
        switchMode(link, mobile)
        scope.launch {
            link.session?.takeIf { !it.closed.value }?.let { runCatching { configure(link, it) } }
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
    }

    /**
     * This tab decides for itself which version each site uses ([policy] takes a host name and returns true for mobile), instead of the per-site records (the Agent's);
     * set to null to go back to those. Affects only this tab, and takes effect at the next main document load (the caller reloads the current page). Called on the main thread.
     */
    fun setModePolicy(tab: WebTab, policy: ((String?) -> Boolean)?) {
        links[tab]?.policy = policy
    }

    /**
     * This tab is viewed and tapped by the user personally (tabs in the browser page): even in desktop mode no viewport emulation is added, so the page supports pinch zoom (see [DesktopProfile.viewportCall]).
     * Takes effect the next time the session is configured. Called on the main thread.
     */
    fun setHandOperated(tab: WebTab) {
        links[tab]?.handOperated = true
    }

    /** Whether the Agent's tab should use mobile when opening [host] (per the per-site records; the default desktop when no tab has been created yet). */
    fun agentWantsMobile(host: String?): Boolean = modes?.isMobile(host) ?: false

    // The tab's own rule takes precedence; otherwise the per-site records are used, and null before they have been loaded
    private fun wantsMobile(link: Link, url: String): Boolean? {
        val host = WebUrlPolicy.host(url)
        link.policy?.let { return it(host) }
        return modes?.isMobile(host)
    }

    /** Whether this view should be hosted on the desktop-viewport-sized host display (the tab owning it uses desktop). False for unknown views. */
    fun wantsDesktopHost(view: WebView): Boolean = links.values.firstOrNull { it.tab.view === view }?.let { !it.mobile } ?: false

    // Main thread: switch both layers of settings over and move a hosted view to the display of the matching size
    private fun switchMode(link: Link, mobile: Boolean) {
        link.mobile = mobile
        applySettings(link.tab.view.settings, mobile)
        applyIdentityScript(link)
        WebHost.followMode(link.tab)
    }

    /**
     * Main thread: in desktop mode, inject the identity patch ([DesktopProfile.identityScript]) at the start of every document (frames included); in mobile mode remove it.
     * When the system does not support document-start injection, Page.addScriptToEvaluateOnNewDocument on the session takes its place (see [configure]).
     */
    private fun applyIdentityScript(link: Link) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (link.mobile) {
            link.identityScript?.let { runCatching { it.remove() } }
            link.identityScript = null
        } else if (link.identityScript == null) {
            link.identityScript = runCatching { WebViewCompat.addDocumentStartJavaScript(link.tab.view, DesktopProfile.identityScript(), setOf("*")) }
                .onFailure { Log.w(TAG, "identity script on tab ${link.tab.id}: ${it.message}") }.getOrNull()
        }
    }

    private fun onViewResized(link: Link) {
        if (link.mobile) return
        val s = link.session?.takeIf { !it.closed.value } ?: return
        scope.launch {
            link.lock.withLock {
                if (link.session === s && !link.mobile) runCatching { configure(link, s) }.onFailure { Log.w(TAG, "re-fit tab ${link.tab.id}: ${it.message}") }
            }
        }
    }

    /** The tab was closed: disconnect the session and forget it. */
    fun release(tab: WebTab) {
        val link = links.remove(tab) ?: return
        link.session?.let { s -> scope.launch { s.close() } }
        link.session = null
    }

    /** Records whether the site uses mobile or desktop; the current page must be reloaded by the caller ([beforeLoad] switches over before loading). */
    fun setSiteMode(host: String, mobile: Boolean) {
        modes?.set(host, mobile)
    }

    /** For tests: restore the real session source (used when another test replaced [provider] and did not restore it). */
    fun resetProvider() {
        provider = this::cached
    }

    /** For tests: forget the detected route so it is detected again next time. */
    fun resetRoute() {
        path = null
        directFailed = false
        _route.value = Route.Unknown
        _available.value = false
    }

    // ---------- Internals ----------

    private class Link(val tab: WebTab) {
        val lock = Mutex()
        @Volatile var session: CdpSession? = null
        @Volatile var targetId: String? = null
        @Volatile var mobile = false
        /** This tab's own version rule (see [setModePolicy]); when null, the per-site records are used. */
        @Volatile var policy: ((String?) -> Boolean)? = null
        /** A tab operated by the user personally (see [setHandOperated]): no viewport emulation. */
        @Volatile var handOperated = false
        /** The language the page is requested in (the Accept-Language value); null means follow the system, no override. Taken from Claire's language when the tab is created and before every main document load. */
        @Volatile var acceptLanguage: String? = null
        /** The version already configured on the session; null means it has not been set yet. */
        @Volatile var configured: Boolean? = null
        @Volatile var degradedAt = 0L
        @Volatile var loadedBeforeAttach = false
        @Volatile var everAttached = false
        /** Ratio of Input domain coordinates to CSS pixels under the current emulation (see [CdpSession.inputScale]). */
        @Volatile var inputScale = 1.0
        /** The page's devicePixelRatio without viewport emulation (how many view pixels the WebView takes per CSS pixel), and the display the view was on when it was measured. */
        @Volatile var baseDpr: Double? = null
        @Volatile var baseDprDisplay = -1
        /** Viewport emulation has already been set on this session; the devicePixelRatio the page reports after it is set. */
        @Volatile var metricsSet = false
        @Volatile var emulatedDpr: Double? = null
        /** The desktop identity patch injected at document start (touched on the main thread); null in mobile mode. */
        var identityScript: ScriptHandler? = null
        /** Identifier of the same patch registered on the session when the system does not support document-start injection. */
        @Volatile var sessionScriptId: String? = null
    }

    /** One route. */
    private class Path(val route: Route, val transport: CdpTransport)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val links = ConcurrentHashMap<WebTab, Link>()
    private val pathLock = Mutex()
    @Volatile private var path: Path? = null
    @Volatile private var directFailed = false
    @Volatile private var relayFailedAt = 0L
    @Volatile private var modes: SiteModes? = null
    @Volatile private var desktop: JsonObject? = null
    @Volatile private var desktopUa: String? = null
    @Volatile private var desktopMetadata: UserAgentMetadata? = null
    @Volatile private var defaultMetadata: UserAgentMetadata? = null
    /** WebView's default mobile identity (UA plus a CDP-style UA-CH): copied as-is when mobile mode has a chosen language, replacing only the language. */
    @Volatile private var defaultUa: String = ""
    @Volatile private var defaultMetadataJson: JsonObject? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var debuggingOn = false

    private val socketName: String get() = DevtoolsSocket.name(Process.myPid())

    @Synchronized
    private fun init(context: Context, st: WebSettings) {
        if (!debuggingOn) {
            WebView.setWebContentsDebuggingEnabled(true)
            debuggingOn = true
        }
        if (modes == null) modes = SiteModes(File(context.applicationContext.filesDir, "web"))
        if (appContext == null) appContext = context.applicationContext
        if (desktopUa != null) return
        val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
        this.defaultUa = defaultUa
        val (major, full) = DesktopProfile.version(defaultUa) ?: ("120" to "120.0.0.0")
        val metadataOk = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        // Take the engine's own UA-CH before the first settings change, so mobile sites can switch back to it
        val probe = if (metadataOk) runCatching { WebSettingsCompat.getUserAgentMetadata(st) }.getOrNull() else null
        val engine = probe?.brandVersionList?.map { Brand(it.brand, it.majorVersion, it.fullVersion) }
        val brands = DesktopProfile.brands(engine, major, full)
        defaultMetadata = probe
        defaultMetadataJson = probe?.let { m ->
            DesktopProfile.metadata(
                m.brandVersionList.map { Brand(it.brand, it.majorVersion, it.fullVersion) }, m.fullVersion.orEmpty(), m.platform.orEmpty(),
                m.platformVersion.orEmpty(), m.architecture.orEmpty(), m.model.orEmpty(), m.isMobile,
                if (m.bitness == UserAgentMetadata.BITNESS_DEFAULT) "" else m.bitness.toString(), m.isWow64,
            )
        }
        desktopMetadata = if (metadataOk) {
            UserAgentMetadata.Builder()
                .setBrandVersionList(brands.map { UserAgentMetadata.BrandVersion.Builder().setBrand(it.brand).setMajorVersion(it.major).setFullVersion(it.full).build() })
                .setFullVersion(full).setPlatform(DesktopProfile.PLATFORM).setPlatformVersion("").setArchitecture("x86")
                .setModel("").setMobile(false).setBitness(64).setWow64(false).build()
        } else null
        desktop = DesktopProfile.userAgentOverride(major, full, brands)
        desktopUa = DesktopProfile.userAgent(major)
    }

    /**
     * The Accept-Language for the language the user has chosen in Claire; null when following the system. The tag is first completed in ICU's common form
     * (en → en-Latn-US) and then written as a request header.
     */
    private fun chosenAcceptLanguage(): String? {
        val tag = appContext?.let { runCatching { AppLanguage.current(it).tag }.getOrNull() } ?: return null
        return PageLanguage.acceptLanguage(ULocale.addLikelySubtags(ULocale.forLanguageTag(tag)).toLanguageTag())
    }

    // The fallback layer: UA, UA-CH (when supported), wide viewport. Once emulation is connected it takes precedence (the language can only be set through emulation, see the reload in [attach])
    private fun applySettings(st: WebSettings, mobile: Boolean) {
        st.userAgentString = if (mobile) null else desktopUa
        st.useWideViewPort = !mobile
        st.loadWithOverviewMode = !mobile
        val meta = if (mobile) defaultMetadata else desktopMetadata
        if (meta != null && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching { WebSettingsCompat.setUserAgentMetadata(st, meta) }
        }
    }

    private fun cached(tab: WebTab): CdpSession? {
        val link = links[tab] ?: return null
        link.session?.takeIf { !it.closed.value }?.let { return it }
        if (!tab.destroyed && !link.lock.isLocked && retryAllowed(link)) scope.launch { attach(link) }
        return null
    }

    private fun retryAllowed(link: Link) =
        link.degradedAt == 0L || SystemClock.elapsedRealtime() - link.degradedAt >= DEGRADED_RETRY_MS

    private suspend fun attach(link: Link): CdpSession? = link.lock.withLock {
        link.session?.takeIf { !it.closed.value }?.let { return it }
        val tab = link.tab
        if (tab.destroyed || tab.crashed || links[tab] !== link || !retryAllowed(link)) return null
        val p = path() ?: return null
        val s = try {
            link.targetId?.let { reopen(p, it) } ?: find(p, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attach tab ${tab.id}: ${e.message}")
            null
        }
        if (s == null) {
            link.degradedAt = SystemClock.elapsedRealtime()
            // The relay route may already be broken (the helper is gone): detect it again next time
            if (p.route == Route.Relay) invalidate(p)
            return null
        }
        link.session = s
        link.degradedAt = 0L
        link.configured = null
        // There is no emulation on a new session (WebView has reverted it on disconnect)
        link.metricsSet = false
        link.sessionScriptId = null
        runCatching { configure(link, s) }.onFailure { Log.w(TAG, "emulation on tab ${tab.id}: ${it.message}") }
        if (!link.everAttached && link.loadedBeforeAttach && (!link.mobile || link.acceptLanguage != null)) {
            // The page loaded before emulation was set up (a popped-up tab, the first page not waiting for the session, etc.): reload once in desktop mode or with the chosen language.
            // WebView settings have no language entry, and only emulation can change request headers, so mobile mode with a chosen language must reload too
            withContext(Dispatchers.Main) { if (!tab.destroyed) tab.view.reload() }
        }
        link.everAttached = true
        watch(link, s)
        s
    }

    private suspend fun configure(link: Link, s: CdpSession) {
        val mobile = link.mobile
        if (mobile) {
            // When following the system, an empty UA removes the override, returning to the WebView settings (already switched back to the default); with a chosen language, copy the default identity and only replace the language
            s.send("Emulation.setUserAgentOverride", PageLanguage.mobileOverride(defaultUa, defaultMetadataJson, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS)
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            s.inputScale = 1.0
            link.inputScale = 1.0
            DesktopProfile.clearMediaCall().let { (m, p) -> runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) } }
            link.sessionScriptId?.let { id ->
                runCatching { s.send("Page.removeScriptToEvaluateOnNewDocument", buildJsonObject { put("identifier", id) }, timeoutMs = CALL_TIMEOUT_MS) }
                link.sessionScriptId = null
            }
        } else {
            // Before layout has happened, use the host's default desktop viewport size
            val (size, display) = withContext(Dispatchers.Main) {
                val v = link.tab.view
                (if (v.width > 0 && v.height > 0) v.width to v.height else DesktopProfile.HOST_WIDTH to DesktopProfile.HOST_HEIGHT) to (v.display?.displayId ?: -1)
            }
            val (w, h) = size
            desktop?.let { s.send("Emulation.setUserAgentOverride", PageLanguage.withLanguage(it, link.acceptLanguage), timeoutMs = CALL_TIMEOUT_MS) }
            // Touch, pointer and hover: each is sent separately, so one the engine does not accept does not affect the others
            for ((m, p) in DesktopProfile.emulationCalls()) {
                runCatching { s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }.onFailure { Log.i(TAG, "$m not applied on tab ${link.tab.id}: ${it.message}") }
            }
            if (link.sessionScriptId == null && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                link.sessionScriptId = runCatching {
                    s.send("Page.addScriptToEvaluateOnNewDocument", buildJsonObject { put("source", DesktopProfile.identityScript()) }, timeoutMs = CALL_TIMEOUT_MS)["identifier"]
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
            val hand = link.handOperated
            val fit = if (hand) null else DesktopProfile.fitScale(w, baseDpr(link, s, display).toFloat())
            DesktopProfile.viewportCall(hand, w, h, fit).let { (m, p) -> s.send(m, p, timeoutMs = CALL_TIMEOUT_MS) }
            link.metricsSet = !hand
            link.emulatedDpr = if (hand) null else readDpr(s)
            // After scaling, Input domain coordinates are interpreted in the pre-scale view (DIP): callers still pass CSS pixels, which are multiplied when sending
            s.inputScale = fit ?: 1.0
            link.inputScale = s.inputScale
            Log.i(TAG, if (hand) "tab ${link.tab.id} desktop identity without viewport emulation (hand-operated)" else "tab ${link.tab.id} desktop viewport fitted to ${w}x$h view px (scale $fit)")
        }
        link.configured = mobile
    }

    /**
     * How many view pixels the WebView takes per CSS pixel without emulation. It depends on the display density the WebView sees and may change when the view moves to another display,
     * so it is recorded per display; to re-measure, clear the emulation first and wait until the value the page reports moves away from the emulated one.
     */
    private suspend fun baseDpr(link: Link, s: CdpSession, display: Int): Double {
        link.baseDpr?.takeIf { link.baseDprDisplay == display }?.let { return it }
        var v = if (link.metricsSet) {
            s.send("Emulation.clearDeviceMetricsOverride", timeoutMs = CALL_TIMEOUT_MS)
            link.metricsSet = false
            var cur: Double? = null
            val until = SystemClock.elapsedRealtime() + DPR_SETTLE_MS
            while (SystemClock.elapsedRealtime() < until) {
                cur = readDpr(s)
                if (cur != null && cur != link.emulatedDpr) break
                delay(DPR_POLL_MS)
            }
            cur
        } else readDpr(s)
        if (v == null || v <= 0) v = link.baseDpr ?: withContext(Dispatchers.Main) { link.tab.view.resources.displayMetrics.density.toDouble() }
        link.baseDpr = v
        link.baseDprDisplay = display
        return v
    }

    private suspend fun readDpr(s: CdpSession): Double? = runCatching {
        s.evaluate("devicePixelRatio", timeoutMs = CALL_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull?.toDouble()
    }.getOrNull()

    private fun watch(link: Link, s: CdpSession) {
        scope.launch {
            s.closed.first { it }
            val tab = link.tab
            if (links[tab] !== link || tab.destroyed || tab.crashed || link.session !== s) return@launch
            link.session = null
            // One reconnect per disconnect; if that fails the tab is degraded and retried later
            delay(RECONNECT_BACKOFF_MS)
            if (attach(link) == null) Log.w(TAG, "tab ${tab.id} lost its devtools session and could not reconnect: degraded")
        }
    }

    /** Known targetId: connect straight back (the tab-to-target mapping is unchanged across navigations). Null if the target is gone. */
    private suspend fun reopen(p: Path, targetId: String): CdpSession? {
        val t = CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS).firstOrNull { it.id == targetId } ?: return null
        val ws = t.webSocketDebuggerUrl ?: return null
        return connect(p, ws)
    }

    private suspend fun find(p: Path, link: Link): CdpSession? {
        val tab = link.tab
        val marker = UUID.randomUUID().toString()
        if (!mark(tab, marker)) return null
        val claimed = links.values.mapNotNull { l -> l.targetId.takeIf { l !== link } }.toSet()
        val tabUrl = withContext(Dispatchers.Main) { tab.view.url }
        val candidates = TargetMatch.candidates(CdpDiscovery.list(p.transport, socketName, timeoutMs = DISCOVERY_TIMEOUT_MS), claimed, tabUrl)
        for (t in candidates) {
            val s = connect(p, t.webSocketDebuggerUrl!!) ?: continue
            val v = runCatching {
                s.evaluate(TargetMatch.READ_SCRIPT, timeoutMs = MARK_TIMEOUT_MS)["value"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (TargetMatch.isMarker(v, marker)) {
                runCatching { s.evaluate(TargetMatch.CLEAR_SCRIPT, timeoutMs = MARK_TIMEOUT_MS) }
                link.targetId = t.id
                return s
            }
            s.close()
        }
        Log.w(TAG, "no devtools target carries tab ${tab.id}'s marker (${candidates.size} candidates)")
        return null
    }

    // The handshake has a limit (the stream is closed on timeout), so a stuck handshake does not hold the tab's lock forever
    private suspend fun connect(p: Path, webSocketUrl: String): CdpSession? =
        try {
            CdpSession.connect(p.transport, socketName, CdpDiscovery.webSocketPath(webSocketUrl), handshakeTimeoutMs = CONNECT_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "devtools connect via ${p.route}: ${e.message}")
            null
        }

    /** Writes the marker into the tab's current document (executed on the main thread) and waits for it to finish. */
    private suspend fun mark(tab: WebTab, marker: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            if (tab.destroyed) done.complete(false)
            else tab.view.evaluateJavascript(TargetMatch.markScript(marker)) { done.complete(true) }
        }
        return withTimeoutOrNull(MARK_TIMEOUT_MS) { done.await() } ?: false
    }

    private suspend fun path(): Path? = pathLock.withLock {
        val forced = forceRoute
        if (forced == Route.None) return publish(null, Route.None)
        path?.let { if (forced == null || it.route == forced) return it }
        if (forced == Route.Direct || (forced == null && !directFailed)) {
            val direct = DirectTransport()
            if (probe(direct)) return publish(Path(Route.Direct, direct), Route.Direct)
            if (forced == null) {
                directFailed = true
                Log.i(TAG, "direct devtools connection refused; trying the phone helper")
            }
        }
        if (forced == Route.Relay || forced == null) {
            if (forced == null && SystemClock.elapsedRealtime() - relayFailedAt < RELAY_RETRY_MS && relayFailedAt != 0L) return publish(null, Route.None)
            relay?.let { open ->
                val t = RelayTransport(open)
                if (probe(t)) return publish(Path(Route.Relay, t), Route.Relay)
                relayFailedAt = SystemClock.elapsedRealtime()
            }
        }
        publish(null, Route.None)
    }

    private fun publish(p: Path?, r: Route): Path? {
        path = p
        _route.value = r
        _available.value = p != null
        return p
    }

    private suspend fun invalidate(p: Path) = pathLock.withLock {
        if (path === p) publish(null, Route.Unknown)
    }

    private suspend fun probe(t: CdpTransport): Boolean =
        runCatching { CdpDiscovery.version(t, socketName, timeoutMs = PROBE_TIMEOUT_MS) }.isSuccess

    companion object {
        private const val TAG = "MikaDevtools"
        private const val ATTACH_TIMEOUT_MS = 3_000L
        /** Upper bound for waiting for the session before the first navigation: failing to connect must not hold up opening a web page for too long. */
        private const val BEFORE_LOAD_WAIT_MS = 2_500L
        private const val PROBE_TIMEOUT_MS = 2_000L
        private const val DISCOVERY_TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val MARK_TIMEOUT_MS = 2_000L
        private const val CALL_TIMEOUT_MS = 3_000L
        private const val RECONNECT_BACKOFF_MS = 500L
        private const val DPR_SETTLE_MS = 500L
        private const val DPR_POLL_MS = 50L
        /** A degraded tab is retried after this long (the helper may have come back up). */
        private const val DEGRADED_RETRY_MS = 60_000L
        /** After a relay probe fails, wait this long before probing again (the helper may not be up yet). */
        private const val RELAY_RETRY_MS = 15_000L

        /** The single one in the process (provided by the App). */
        fun of(context: Context): WebDevtools = WebOwner.of(context).webDevtools
    }
}
