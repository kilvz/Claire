package com.wangzan101.mika.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether an address is on this machine or the local network. Only the address itself is examined; no DNS resolution is done.
 * - [isLocal] (strict): this machine (localhost, 127.0.0.0/8, ::1), private ranges (10/8, 172.16/12, 192.168/16, IPv6 fc00::/7),
 *   link-local (169.254/16, fe80::/10) and `.local` names. Used for decisions such as whether plain http is safe.
 * - [bypassesProxy] (broader): addresses that still connect directly when a network proxy is in use — beyond [isLocal], it also covers hostnames without a dot (machine names on the local network),
 *   suffixes common on local networks (`.lan`, `.home.arpa`, `.internal`) and the carrier-grade NAT shared range 100.64/10 (mesh tools often assign devices addresses from it).
 *   Proxies usually cannot reach model services running on this machine or the local network, hence the direct connection.
 */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        ipv4(h)?.let { (a, b) -> return privateIpv4(a, b) }
        if (':' in h) {
            // the fully expanded form of ::1 is loopback as well
            if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
            // ::ffff:a.b.c.d is IPv4-mapped IPv6, so it is judged by the IPv4 part inside; local model services are often written this way
            if (h.startsWith("::ffff:")) {
                val (a, b) = ipv4(h.removePrefix("::ffff:")) ?: return false
                return privateIpv4(a, b)
            }
            val first = h.substringBefore(':').toIntOrNull(16) ?: return false
            return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
        }
        return false
    }

    private fun privateIpv4(a: Int, b: Int) =
        a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)

    /**
     * Whether this is this machine, the local network, or the CGNAT range 100.64/10 commonly used by mesh tools.
     * VPNs such as Tailscale assign devices addresses from this range and traffic is already encrypted at the network layer, so plain http is safe too;
     * therefore checks such as "is plain http acceptable" treat it as a private address. It recognizes this one range beyond [isLocal].
     */
    fun isPrivateOrMesh(host: String): Boolean {
        if (isLocal(host)) return true
        val h = normalize(host)
        if (h.isEmpty()) return false
        ipv4(h)?.let { (a, b) -> return a == 100 && b in 64..127 }
        if (h.startsWith("::ffff:")) {
            val (a, b) = ipv4(h.removePrefix("::ffff:")) ?: return false
            return a == 100 && b in 64..127
        }
        return false
    }

    /** This host still connects directly when a network proxy is in use. */
    fun bypassesProxy(host: String): Boolean {
        if (isLocal(host)) return true
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (PROXY_SUFFIXES.any { h.endsWith(it) }) return true
        ipv4(h)?.let { (a, b) -> return a == 100 && b in 64..127 }
        if (':' in h) return false
        return '.' !in h
    }

    /** A plain-http address that is not on this machine or the local network: keys and conversations travel over the network unencrypted. Addresses that cannot be recognized do not count. */
    fun unencryptedRemote(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return !u.isHttps && !isLocal(u.host)
    }

    private val PROXY_SUFFIXES = listOf(".lan", ".home.arpa", ".internal")

    /**
     * Direct-connection rules for the browser (the same set as [bypassesProxy], written in the format the browser engine understands); the engine connects local addresses directly anyway,
     * while dotless hostnames are handled separately by "connect simple hostnames directly".
     */
    val BYPASS_RULES: List<String> = listOf(
        "localhost", "*.localhost", "*.local", "*.lan", "*.home.arpa", "*.internal",
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "fc00::/7", "fe80::/10",
    )

    /** no_proxy for command-line tools: comma-separated hosts, suffixes and ranges (tools that do not understand ranges still recognize at least the first three). */
    const val NO_PROXY = "localhost,127.0.0.1,::1,127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,100.64.0.0/10," +
        "fc00::/7,fe80::/10,.localhost,.local,.lan,.home.arpa,.internal"

    private fun normalize(host: String) = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()

    /** The first two octets of a dotted-quad IPv4 address; null when it is not IPv4. */
    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
