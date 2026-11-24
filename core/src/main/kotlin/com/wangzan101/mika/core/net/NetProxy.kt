package com.wangzan101.mika.core.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.net.Authenticator as JavaAuthenticator
import okhttp3.Authenticator as OkAuthenticator

/** An error that occurs when going through a network proxy, reported separately from errors on the provider side. */
enum class ProxyFailure {
    /** The proxy cannot be reached, or the proxy did not forward this request. */
    Unreachable,
    /** The proxy asked for credentials and the ones supplied are wrong (or none were supplied). */
    AuthFailed,
}

/**
 * The network proxy used by Claire's own network connections (calling models, listing models, exchanging account login for tokens, downloading updates, built-in browser downloads).
 * [source] supplies the proxy address stored at that moment on each call (an empty string means none); parse results are cached by the raw text, and [changed] is called after the setting changes.
 *
 * - Every request of an OkHttp client built through [client] asks [selector]: addresses on this machine or the local network ([LocalHosts.bypassesProxy]) connect directly, everything else goes through the proxy;
 *   when no proxy is set or the proxy does not apply ([ProxyAddress.forApp] is false), the connection is direct. Streaming replies are still read chunk by chunk, without buffering.
 * - HTTP proxy credentials are sent as `Proxy-Authorization` when the proxy asks for them (and before establishing a tunnel); if the same request already carried them and was still rejected, no further retry is made.
 * - SOCKS proxy credentials can only be supplied through the single process-wide [java.net.Authenticator]: the one installed by [installSocksAuth]
 *   answers only queries addressed to the current SOCKS proxy (host and port both matching), and answers nothing else.
 */
class NetProxy(private val source: () -> String) {
    private class Parsed(val raw: String, val address: ProxyAddress?)

    @Volatile private var parsed: Parsed? = null
    private val pools = CopyOnWriteArrayList<WeakReference<okhttp3.ConnectionPool>>()

    /** The proxy Claire's own connections should use right now; null when none is set, the format is invalid, or it does not apply. */
    fun current(): ProxyAddress? {
        val raw = runCatching(source).getOrDefault("")
        parsed?.takeIf { it.raw == raw }?.let { return it.address }
        val address = ProxyAddress.parse(raw)?.takeIf { it.forApp }
        parsed = Parsed(raw, address)
        return address
    }

    /** The proxy to use when connecting to this host; addresses on this machine or the local network connect directly (null). */
    fun proxyFor(host: String): ProxyAddress? = current()?.takeUnless { LocalHosts.bypassesProxy(host) }

    val selector: ProxySelector = Selector()

    val authenticator: OkAuthenticator = OkAuthenticator { route, response -> authenticate(route, response) }

    private fun authenticate(route: Route?, response: Response): Request? {
        // credentials were already sent and still rejected: do not retry, let the caller report "the proxy username or password is wrong"
        if (response.request.header(PROXY_AUTHORIZATION) != null) return null
        val proxy = current() ?: return null
        if (proxy.socks || !proxy.hasCredentials) return null
        if (route != null && route.proxy.type() != Proxy.Type.HTTP) return null
        return response.request.newBuilder().header(PROXY_AUTHORIZATION, Credentials.basic(proxy.user, proxy.password)).build()
    }

    /** Builds a client with this proxy configuration from [builder]; its connection pool drops idle connections when the settings change. */
    fun client(builder: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
        builder.proxySelector(selector).proxyAuthenticator(authenticator).build().also { pools += WeakReference(it.connectionPool) }

    /** The settings changed: re-parse, evict all idle connections, and the next request connects with the new settings (requests already in flight still run to completion). */
    fun changed() {
        parsed = null
        pools.removeAll { it.get() == null }
        pools.forEach { it.get()?.evictAll() }
    }

    /** SOCKS proxy credentials: answers only queries addressed to the current SOCKS proxy. */
    val socksAuthenticator: JavaAuthenticator = object : JavaAuthenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            socksCredentials(requestingProtocol, requestingHost, requestingPort)
    }

    internal fun socksCredentials(protocol: String?, host: String?, port: Int): PasswordAuthentication? {
        val proxy = current() ?: return null
        if (!proxy.socks || !proxy.hasCredentials) return null
        if (protocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (host == null || !host.removeSurrounding("[", "]").equals(proxy.host, ignoreCase = true) || port != proxy.port) return null
        return PasswordAuthentication(proxy.user, proxy.password.toCharArray())
    }

    /** Installs [socksAuthenticator] (one per process); called once at app startup. */
    fun installSocksAuth() = JavaAuthenticator.setDefault(socksAuthenticator)

    private inner class Selector : ProxySelector() {
        val owner: NetProxy get() = this@NetProxy

        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyFor(host)?.javaProxy() ?: Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    companion object {
        private const val PROXY_AUTHORIZATION = "Proxy-Authorization"

        /** The one used in the product: at app startup [sharedSource] is wired to the stored settings. */
        @Volatile var sharedSource: () -> String = { "" }
        val shared: NetProxy = NetProxy { sharedSource() }

        /** The instance matching clients built through [client]; null for other clients. */
        fun of(http: OkHttpClient): NetProxy? = (http.proxySelector as? Selector)?.owner

        /** Whether this failure is the proxy's fault: [http] went through a proxy when connecting to [url], and the error lies in the connect-through-proxy part. */
        fun failure(http: OkHttpClient, url: HttpUrl, e: IOException): ProxyFailure? {
            of(http)?.proxyFor(url.host) ?: return null
            return classify(e)
        }

        /**
         * Classification of network errors while going through a proxy: the proxy rejecting the credentials counts as [ProxyFailure.AuthFailed]; an unreachable proxy (connection refused, host not found, connect timeout),
         * a failed tunnel setup by the proxy, and SOCKS handshake errors count as [ProxyFailure.Unreachable]; a dropped connection while reading the reply and the like are not the proxy's problem (null).
         */
        fun classify(e: IOException): ProxyFailure? {
            val chain = generateSequence(e as Throwable) { it.cause }.take(6).toList()
            val messages = chain.map { it.message.orEmpty() }
            if (messages.any { it.contains("authenticate with proxy", true) || (it.contains("SOCKS", true) && it.contains("auth", true)) }) {
                return ProxyFailure.AuthFailed
            }
            if (messages.any { it.contains("for CONNECT", true) || it.contains("SOCKS", true) }) return ProxyFailure.Unreachable
            val connecting = chain.any {
                it is ConnectException || it is NoRouteToHostException || it is UnknownHostException ||
                    (it is SocketTimeoutException && it.message.orEmpty().contains("connect", true))
            }
            return if (connecting) ProxyFailure.Unreachable else null
        }
    }
}
