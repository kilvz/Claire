package com.wangzan101.mika.cap.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.cap.web.DialogReply
import com.wangzan101.mika.core.cap.web.ExternalLink
import com.wangzan101.mika.core.cap.web.PageDialogs
import com.wangzan101.mika.core.screen.uistate.PageDialog
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One web tab: a WebView plus its load state. Desktop pages are opened by default, switchable back to mobile per site (see [WebDevtools]).
 * Must be created on the main thread; [assetLoaders] serve only App-bundled pages such as local fixtures (one per domain).
 * When the system supports multiple profiles, a separate web profile named mika is used (cookies and storage kept apart from other WebViews in the system).
 * Page permission requests (geolocation, camera, microphone, MIDI, protected media) are silently denied by default; for tabs the user is watching, the UI asks the user.
 * Links that jump to an app are not followed; downloads are not accepted yet.
 * Page dialogs (alert/confirm/prompt, leave-page confirmation) are not shown as system dialogs; they are recorded in [dialogs] and wait for the Agent (or the browser page the user is using) to answer.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebTab(
    context: Context,
    val id: String,
    ownerTaskId: String,
    private val assetLoaders: List<WebViewAssetLoader> = emptyList(),
    /** DevTools session entry point for this tab; defaults to the process-wide instance. */
    val devtools: WebDevtools = WebDevtools.of(context),
) {
    /** The tab pool receives the events that happen on a tab. All callbacks are on the main thread. */
    interface Listener {
        fun onPageFinished(tab: WebTab) {}
        fun onCrashed(tab: WebTab) {}
        fun onExternalLink(tab: WebTab, link: ExternalLink) {}
        /** The page wants to open a new window; return true to accept it (hand the new tab's WebView to [resultMsg]). */
        fun onCreateWindow(tab: WebTab, isUserGesture: Boolean, resultMsg: Message): Boolean = false
        /** A new popup tab is about to go to a different site for the first time: the tab pool closes it. */
        fun onPopupRefused(tab: WebTab, url: String) {}
        /** The page wants to close its own window (window.close()). */
        fun onCloseWindow(tab: WebTab) {}
        /** The page triggered a download (save only; never open or install it). */
        fun onDownload(tab: WebTab, url: String, userAgent: String, contentDisposition: String?, mime: String?) {}
        /** The page URL, title or load progress changed (refresh the address bar and progress bar of visible tabs). */
        fun onPageChanged(tab: WebTab) {}
        /** The page raised a dialog, recorded in [dialogs] awaiting a reply. */
        fun onDialog(tab: WebTab) {}
        /** The current page's favicon was received. */
        fun onIcon(tab: WebTab, icon: Bitmap) {}
        /** A tab the user is watching needs a file chosen: the UI shows the system picker and [callback] receives the chosen files (null on cancel). Return true to accept. */
        fun onFileChooser(tab: WebTab, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean = false
        /** A tab the user is watching wants the camera, microphone, etc.: the UI asks the user, which grants or denies [request]. Return true to accept. */
        fun onPermissionRequest(tab: WebTab, request: PermissionRequest): Boolean = false
        fun onPermissionRequestCanceled(tab: WebTab, request: PermissionRequest) {}
        /** A tab the user is watching wants geolocation: the UI asks the user and answers through [callback]. Return true to accept. */
        fun onGeolocationRequest(tab: WebTab, origin: String?, callback: GeolocationPermissions.Callback): Boolean = false
        fun onGeolocationPermissionsHidePrompt(tab: WebTab) {}
        /** The page wants to play video full screen: the UI puts [view] over the whole screen and keeps the callback, handing it to the caller on exit. Return true to accept. */
        fun onFullscreen(tab: WebTab, view: View, callback: WebChromeClient.CustomViewCallback): Boolean = false
        /** The page wants to leave full screen. */
        fun onExitFullscreen(tab: WebTab) {}
    }

    /**
     * Files that web_upload placed here for the page's file chooser (content:// addresses of workspace files);
     * they are taken once when the chooser opens. With no files waiting, the chooser is simply cancelled. Read and written on the main thread only.
     */
    var pendingUpload: List<Uri>? = null

    /** How many times the page opened a file chooser with no files waiting (and it was cancelled). */
    var choosersRefused: Int = 0
        private set

    /** Which task this tab belongs to (the user's own browser page uses its own id); when the user closes a page lent to a task and the task adopts it, this becomes that task. */
    var ownerTaskId: String = ownerTaskId
        internal set

    val view: WebView = WebView(context)

    /** Page dialogs waiting for a reply on this tab. */
    val dialogs = PageDialogs()

    var listener: Listener? = null

    /** When opened by a page, the address of the page that opened it. A popup tab can only ever navigate within this site. */
    var popupOpener: String? = null

    // The popup tab has already started loading a same-site page: later attempts to reach another site only cancel that navigation, without closing the tab
    private var popupCommitted = false
    /** Proxy authentication retry budget for the current main document (touched on the main thread only). */
    private val proxyAuth = WebProxyAuth()

    /** Whether the main document is loading. Read and written on the main thread only. */
    var loading: Boolean = false
        private set

    /** Load progress of the main document (0–100). Read and written on the main thread only. */
    var progress: Int = 0
        private set

    /** Whether the last [load] waited until loading finished (error pages included); false on timeout. */
    var lastLoadFinished: Boolean = false
        private set

    /** HTTP error status of the most recent main document (4xx/5xx); null when unavailable or no error. */
    var httpErrorStatus: Int? = null
        private set

    /** The error code the browser reported when the most recent main document did not open (e.g. net::ERR_NAME_NOT_RESOLVED); null when it opened or there was no error. */
    var mainFrameError: String? = null
        private set

    /** During the last [load] the page turned into a download (the main document was a file). */
    var loadBecameDownload: Boolean = false
        private set

    /** The most recently blocked app-jump link; cleared when [load] starts. */
    var lastExternalLink: ExternalLink? = null
        private set

    /** True after [destroy]. */
    @Volatile var destroyed: Boolean = false
        private set

    /** True after the renderer crashed or was reclaimed by the system; the tab is unusable and must be reopened by the caller. */
    @Volatile var crashed: Boolean = false
        private set

    /** The renderer did not crash on its own but was reclaimed by the system to free memory ([crashed] is true at the same time). */
    @Volatile var reclaimed: Boolean = false
        private set

    private var pending: CompletableDeferred<Boolean>? = null
    // After starting a new load, onPageFinished only counts once its own onPageStarted has arrived; a late finish callback from the previous navigation does not count
    private var armed = false
    private var mainFrameFailed = false
    private var mainUrl: String? = null

    init {
        // The profile must be set before the WebView does anything else
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile(PROFILE)
            WebViewCompat.setProfile(view, PROFILE)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = false
            setGeolocationEnabled(false)
            // New windows go to the tab pool through onCreateWindow; scripts cannot open windows on their own without a user action
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        devtools.prepare(this, context)
        // Third-party cookies are off (by decision); revisit per site later if one genuinely needs them
        cookieManager().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoaders.firstNotNullOfOrNull { it.shouldInterceptRequest(request.url) }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (request.isForMainFrame && refusePopup(url)) return true
                // App-jump links (intent:, custom schemes, app stores) are not followed; only main-frame ones are reported to the tab pool (sub-frame ones are blocked silently)
                WebUrlPolicy.external(url)?.let { link ->
                    if (request.isForMainFrame) {
                        lastExternalLink = link
                        listener?.onExternalLink(this@WebTab, link)
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                // A popup's first navigation does not always go through shouldOverrideUrlLoading: check again here
                if (url != null && refusePopup(url)) {
                    v.stopLoading()
                    return
                }
                proxyAuth.onPageStarted()
                loading = true
                armed = true
                mainFrameFailed = false
                mainFrameError = null
                httpErrorStatus = null
                mainUrl = url
                devtools.onPageStarted(this@WebTab, url)
                listener?.onPageChanged(this@WebTab)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                listener?.onPageChanged(this@WebTab)
            }

            // Check the popup's site once more when the page starts showing (the last line of defence should either earlier check be bypassed)
            override fun onPageCommitVisible(v: WebView, url: String?) {
                if (url != null && refusePopup(url)) v.stopLoading()
            }

            override fun onPageFinished(v: WebView, url: String?) {
                if (!armed) return
                loading = false
                finish(!mainFrameFailed)
                listener?.onPageFinished(this@WebTab)
            }

            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    mainFrameError = error.description?.toString()?.takeIf { it.isNotBlank() } ?: "net::error ${error.errorCode}"
                }
            }

            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) {
                    httpErrorStatus = response.statusCode
                    mainFrameFailed = true
                }
            }

            // The network proxy asks for credentials: only answer for the HTTP proxy in Settings > Network proxy; once a few answers still leave it asking (wrong credentials), stop answering;
            // the site's own login prompt is not filled in here
            override fun onReceivedHttpAuthRequest(v: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                val credentials = proxyAuth.credentialsFor(host)
                if (credentials == null) {
                    handler.cancel()
                    return
                }
                handler.proceed(credentials.first, credentials.second)
            }

            // Never continue when the certificate is bad
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == mainUrl) {
                    mainFrameFailed = true
                    mainFrameError = "certificate_error ${error.primaryError}"
                }
            }

            // Returning true is required so the whole App is not killed along with it; the tab is dead and the tab pool destroys it
            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer gone for tab $id crashed=${detail.didCrash()}")
                crashed = true
                reclaimed = !detail.didCrash()
                loading = false
                (view.parent as? ViewGroup)?.removeView(view)
                finish(false)
                listener?.onCrashed(this@WebTab)
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                return listener?.onCreateWindow(this@WebTab, isUserGesture, resultMsg) ?: false
            }

            override fun onCloseWindow(window: WebView) {
                listener?.onCloseWindow(this@WebTab)
            }

            override fun onProgressChanged(v: WebView, newProgress: Int) {
                progress = newProgress
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedTitle(v: WebView, title: String?) {
                listener?.onPageChanged(this@WebTab)
            }

            override fun onReceivedIcon(v: WebView, icon: Bitmap?) {
                if (icon != null) listener?.onIcon(this@WebTab, icon)
            }

            // Page dialogs: record and return (returning true means we handle it and the system shows nothing), without blocking the UI thread; page scripts stay frozen until answered
            override fun onJsAlert(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.ALERT, message, null, result)

            override fun onJsConfirm(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.CONFIRM, message, null, result)

            override fun onJsPrompt(v: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean =
                held(PageDialog.PROMPT, message, defaultValue, result)

            override fun onJsBeforeUnload(v: WebView, url: String?, message: String?, result: JsResult): Boolean =
                held(PageDialog.BEFORE_UNLOAD, message, null, result)

            // The user may not see this tab, so do not agree on their behalf; for tabs the user is watching the UI asks
            override fun onPermissionRequest(request: PermissionRequest) {
                if (listener?.onPermissionRequest(this@WebTab, request) == true) return
                request.deny()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                listener?.onPermissionRequestCanceled(this@WebTab, request)
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                if (callback != null && listener?.onGeolocationRequest(this@WebTab, origin, callback) == true) return
                callback?.invoke(origin, false, false)
            }

            override fun onGeolocationPermissionsHidePrompt() {
                listener?.onGeolocationPermissionsHidePrompt(this@WebTab)
            }

            // File chooser: the agent places workspace files in advance with web_upload (taken and cleared once used); for tabs the user is watching, the UI shows the system picker
            // Full-screen video: hand it to the visible tab's side to cover the whole screen; exit immediately if nobody takes it
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (listener?.onFullscreen(this@WebTab, view, callback) == true) return
                callback.onCustomViewHidden()
            }

            override fun onHideCustomView() {
                listener?.onExitFullscreen(this@WebTab)
            }

            // File chooser: only hand over workspace files placed in advance by web_upload, cleared once taken; otherwise cancel without showing the system picker
            override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                val files = pendingUpload
                pendingUpload = null
                if (!files.isNullOrEmpty()) {
                    val picked = if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) files else files.take(1)
                    callback.onReceiveValue(picked.toTypedArray())
                    return true
                }
                if (listener?.onFileChooser(this@WebTab, callback, params) == true) return true
                choosersRefused++
                callback.onReceiveValue(null)
                return true
            }
        }
        // Downloads are not accepted here; the download module takes them over
        // Downloads go to the tab pool (landing in the workspace); files are never opened or installed
        view.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val l = listener
            if (l == null) Log.i(TAG, "download ignored on tab $id: ${url.take(80)}")
            else l.onDownload(this, url, userAgent.orEmpty(), contentDisposition, mimetype)
            // The navigation being awaited turned into a download: the page will not load, so there is no need to wait for the timeout
            if (pending != null) {
                loadBecameDownload = true
                loading = false
                finish(false)
            }
        }
    }

    /** Records a page dialog; the reply is passed to the browser on the main thread (a JsResult can only be answered once). */
    private fun held(kind: String, message: String?, defaultText: String?, result: JsResult): Boolean {
        val text = message.orEmpty().take(PageDialog.MAX_MESSAGE)
        dialogs.open(PageDialog(kind, text, defaultText?.take(PageDialog.MAX_MESSAGE)), object : DialogReply {
            override fun accept(text: String?) {
                view.post { if (result is JsPromptResult && text != null) result.confirm(text) else result.confirm() }
            }
            override fun dismiss() {
                view.post { result.cancel() }
            }
        })
        Log.i(TAG, "page dialog $kind held on tab $id")
        listener?.onDialog(this)
        // A dialog during loading: the page has stopped here, so the load wait no longer runs to the timeout
        if (pending != null) finish(true)
        return true
    }

    /**
     * A popup tab wants to navigate to a different site than the page that opened it (other than about:blank): block it. If it has not yet loaded a same-site page,
     * hand it to the tab pool to close; afterwards only that navigation is blocked.
     */
    private fun refusePopup(url: String): Boolean {
        val opener = popupOpener ?: return false
        if (url == "about:blank" || WebUrlPolicy.sameSite(opener, url)) {
            if (url != "about:blank") popupCommitted = true
            return false
        }
        if (!popupCommitted) listener?.onPopupRefused(this, url)
        else Log.i(TAG, "popup tab $id kept on its site, refused ${url.take(80)}")
        return true
    }

    private fun finish(ok: Boolean) {
        pending?.complete(ok)
        pending = null
        armed = false
    }

    /** Opens a URL and waits for the main document to load; returns false on timeout, main-document error, or HTTP 4xx/5xx. Only http/https addresses are accepted. */
    suspend fun load(url: String, timeoutMs: Long = 20_000): Boolean = withContext(Dispatchers.Main) {
        if (crashed) return@withContext false
        WebUrlPolicy.checkNavigable(url)?.let {
            Log.w(TAG, "refused to load a non-web address on tab $id: $it")
            lastLoadFinished = false
            return@withContext false
        }
        // Set up desktop emulation before the first navigation (waits only a short while if it cannot connect, then opens as usual)
        devtools.beforeLoad(this@WebTab, url)
        if (crashed || destroyed) return@withContext false
        finish(false)
        lastExternalLink = null
        lastLoadFinished = false
        mainFrameError = null
        loadBecameDownload = false
        val done = CompletableDeferred<Boolean>()
        pending = done
        loading = true
        view.loadUrl(url)
        val ok = withTimeoutOrNull(timeoutMs) { done.await() }
        lastLoadFinished = ok != null
        if (ok == null) {
            if (pending === done) finish(false)
            view.stopLoading()
            loading = false
        }
        ok ?: false
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        dialogs.close()
        devtools.release(this)
        listener = null
        finish(false)
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    companion object {
        private const val TAG = "MikaWebTab"
        private const val PROFILE = "mika"

        /**
         * The cookie store holding web login data: the mika profile's when multiple profiles are supported, otherwise the default one. Call on the main thread.
         * Without multi-profile support it is shared with every other WebView in this App process: no other WebView in the App may handle website logins, or login state would be mixed together.
         */
        fun cookieManager(): CookieManager =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).cookieManager
            } else {
                CookieManager.getInstance()
            }

        /** Web storage (localStorage, IndexedDB, etc.) of the same profile as [cookieManager]. Call on the main thread. */
        fun webStorage(): WebStorage =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(PROFILE).webStorage
            } else {
                WebStorage.getInstance()
            }
    }
}
