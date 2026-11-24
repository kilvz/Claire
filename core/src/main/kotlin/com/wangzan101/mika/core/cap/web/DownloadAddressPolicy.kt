package com.wangzan101.mika.core.cap.web

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which addresses a download may fetch: first pass [WebUrlPolicy.checkNavigable] (the same rule as web_navigate), then forbid local, LAN and reserved addresses.
 * A download is a request the App itself makes in the background, so a page could use it to reach devices on the LAN the phone sits on; hence it is stricter than opening a web page.
 * Every redirect and every DNS resolution is checked against this rule ([guardedDns] blocks host names that resolve to the intranet).
 */
object DownloadAddressPolicy {
    /** The reason it cannot be fetched; null when it can. */
    fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url)?.let { return it }
        val host = url.toHttpUrlOrNull()?.host ?: return "not a web address"
        return if (blockedHost(host)) "local or private network address" else null
    }

    /** A local or LAN name, or a literal intranet/reserved IP. Ordinary domain names are judged after resolution by [guardedDns]. */
    fun blockedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (!IPV4.matches(h) && ':' !in h) return false
        // Literal IP: getByName does not consult DNS
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
        return blockedAddress(a)
    }

    fun blockedAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> blockedV4(b)
            is Inet6Address -> {
                val u0 = b[0].toInt() and 0xff
                // fc00::/7 unique local; the IPv4 embedded in ::ffff:a.b.c.d and 64:ff9b::/96 is judged as IPv4
                (u0 and 0xfe) == 0xfc ||
                    (b.copyOfRange(0, 10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() && blockedV4(b.copyOfRange(12, 16))) ||
                    (b.copyOfRange(0, 12).contentEquals(NAT64) && blockedV4(b.copyOfRange(12, 16)))
            }
            else -> true
        }
    }

    private fun blockedV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        return o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224 ||
            (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254) || (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168) || (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0) || (o0 == 198 && o1 in 18..19)
    }

    /** If any resolved address is an intranet/reserved address, the whole lookup is refused ([BlockedAddress]). */
    fun guardedDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (blockedHost(hostname)) throw BlockedAddress(hostname)
            val all = delegate.lookup(hostname)
            if (all.any(::blockedAddress)) throw BlockedAddress(hostname)
            return all
        }
    }

    class BlockedAddress(host: String) : UnknownHostException("$host resolves to a local or private network address")

    // getByName also accepts short, integer and octal IPv4 forms such as 127.1 / 2130706433 / 0300.0250.0.1;
    // treat any host made only of digits and dots as a literal IP and hand it to getByName, then judge the intranet by the parsed result, so short forms cannot bypass this check.
    private val IPV4 = Regex("^[0-9]+(?:\\.[0-9]+)*$")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal", ".intranet")
    private val NAT64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
}
