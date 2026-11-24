package com.wangzan101.mika.cap.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.wangzan101.mika.core.net.LocalHosts
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.net.ProxyAddress

/**
 * Network proxy for the built-in browser (every WebView in this process), following the same address as Settings › Network proxy.
 * Android's WebView can only set a proxy for the app alone when "proxy override" is supported (androidx.webkit's [ProxyController]);
 * when it is not supported, or the proxy is a SOCKS proxy with a username and password (the browser engine does not support SOCKS auth), the browser connects directly, and the settings screen says so.
 * Local and LAN addresses still connect directly; when an HTTP proxy needs credentials, [credentialsFor] answers (see `WebTab`).
 */
object WebProxy {
    /** The current situation on the browser side. */
    enum class Mode {
        /** No proxy set (or the proxy is for containers only): direct connection. */
        Off,
        /** The browser goes through the proxy. */
        Applied,
        /** This phone's WebView does not support setting a proxy for the app alone: direct connection. */
        Unsupported,
        /** A SOCKS proxy with credentials cannot be used by the browser: direct connection. */
        SocksWithCredentials,
    }

    private const val TAG = "MikaWebProxy"
    @Volatile private var overridden = false

    /** Decides how the browser connects under the current settings (a pure function, handy for tests). */
    fun mode(address: ProxyAddress?, supported: Boolean): Mode = when {
        address == null -> Mode.Off
        !supported -> Mode.Unsupported
        address.socks && address.hasCredentials -> Mode.SocksWithCredentials
        else -> Mode.Applied
    }

    /** Proxy rule syntax accepted by the browser engine. For SOCKS some engines accept only `socks://`, so try `socks5://` first. */
    fun rules(address: ProxyAddress): List<String> {
        val host = if (address.host.contains(':')) "[${address.host}]" else address.host
        return if (address.socks) listOf("socks5://$host:${address.port}", "socks://$host:${address.port}")
        else listOf("http://$host:${address.port}")
    }

    /** Whether WebView supports setting a proxy for the app alone; the query itself loads WebView, so call it on a background thread. */
    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)

    /** The current situation on the browser side (loads WebView, so call it on a background thread). */
    fun mode(): Mode = NetProxy.shared.current().let { mode(it, it != null && supported()) }

    /**
     * Sets (or removes) the proxy on this process's WebViews according to the current settings. If no proxy is set and none was ever set, does nothing (and does not load WebView for it).
     * Done on the main thread; may be called from any thread.
     */
    fun apply(context: Context) {
        val address = NetProxy.shared.current()
        if (address == null && !overridden) return
        Handler(Looper.getMainLooper()).post {
            runCatching { applyNow(address) }.onFailure { Log.w(TAG, "proxy override failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun applyNow(address: ProxyAddress?) {
        if (!supported()) return
        val controller = ProxyController.getInstance()
        if (mode(address, true) != Mode.Applied || address == null) {
            if (overridden) controller.clearProxyOverride({ it.run() }, {})
            overridden = false
            return
        }
        for (rule in rules(address)) {
            val config = ProxyConfig.Builder().addProxyRule(rule).bypassSimpleHostnames().apply {
                LocalHosts.BYPASS_RULES.forEach { addBypassRule(it) }
            }.build()
            val ok = runCatching { controller.setProxyOverride(config, { it.run() }, {}) }.isSuccess
            if (ok) {
                overridden = true
                return
            }
        }
    }

    /** When the browser asks for credentials: answer only if the current HTTP proxy is what is being asked about; for other hosts (a site's own login box) do not answer. */
    fun credentialsFor(host: String?): Pair<String, String>? {
        val p = NetProxy.shared.current() ?: return null
        if (p.socks || !p.hasCredentials || host == null) return null
        if (!host.removeSurrounding("[", "]").equals(p.host, ignoreCase = true)) return null
        return p.user to p.password
    }
}

/** Each tab's main document gets the proxy answered for it at most three times; the next navigation restarts the count, so fixing the settings does not require closing the tab. Main thread only. */
internal class WebProxyAuth {
    private var answers = 0

    fun onPageStarted() { answers = 0 }

    fun credentialsFor(host: String?): Pair<String, String>? {
        val credentials = WebProxy.credentialsFor(host) ?: return null
        if (answers >= 3) return null
        answers++
        return credentials
    }
}
