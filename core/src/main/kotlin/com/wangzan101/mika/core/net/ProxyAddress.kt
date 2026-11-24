package com.wangzan101.mika.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder

/**
 * A network proxy address of the form `scheme://[user:password@]host:port`: the scheme is one of http, https, socks5, socks5h, with no path after it.
 * [toString] omits the credentials, so writing it to logs or showing it in the UI leaks nothing.
 */
class ProxyAddress private constructor(
    val scheme: String,
    /** Hostname or IP; IPv6 without brackets. */
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
) {
    val socks: Boolean get() = scheme == "socks5" || scheme == "socks5h"
    val hasCredentials: Boolean get() = user.isNotEmpty()

    /**
     * Whether Claire's own connections (calling models, downloading updates, the built-in browser) can go through it. An https proxy requires a TLS handshake with the proxy itself first,
     * which the network library these connections use does not support; only commands in the container (curl and the like) can use it.
     */
    val forApp: Boolean get() = scheme != "https"

    fun javaProxy(): Proxy = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    /** The human-facing form: scheme, host, port, without credentials. */
    val display: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    override fun toString(): String = display

    companion object {
        val SCHEMES = setOf("http", "https", "socks5", "socks5h")

        /** Parses a proxy address; null for an empty string or an invalid format. */
        fun parse(text: String): ProxyAddress? {
            val v = text.trim()
            if (v.isEmpty() || v.any { it.isWhitespace() }) return null
            val u = runCatching { URI(v) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme !in SCHEMES) return null
            val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (u.port !in 1..65535) return null
            if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/") || u.rawQuery != null || u.rawFragment != null) return null
            val info = u.rawUserInfo.orEmpty()
            val user = decodeCredential(info.substringBefore(':'))
            val password = decodeCredential(info.substringAfter(':', ""))
            return ProxyAddress(scheme, host, u.port, user, password)
        }

        // A plus sign in a URI is a literal character, so keep it when decoding.
        private fun decodeCredential(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

        /** An empty string (no proxy), or a proxy address that parses. */
        fun valid(text: String): Boolean = text.isBlank() || parse(text) != null
    }
}
