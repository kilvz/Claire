package com.wangzan101.mika.core.device.adb

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * adb only connects to this device itself: 127/8, the IPv6 loopback, or an address on one of this device's network interfaces.
 *
 * Domains are never handed to the system resolver here. Apart from the exact `localhost` (which may have one trailing dot and is always mapped to 127.0.0.1), only numeric literals are accepted;
 * parsing, verification and the address the Socket then actually uses are the same [InetAddress], so there is no window in which the original string gets resolved again after validation.
 */
object AdbHosts {
    private val LOOPBACK4 = byteArrayOf(127, 0, 0, 1)

    private data class LocalInterface(
        val name: String,
        val index: Int,
        val addresses: List<InetAddress>,
    )

    /** Returns the address once verified as this device's own; domains, invalid literals and addresses of other machines all return null. No DNS at any point. */
    fun ownAddress(host: String): InetAddress? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map {
                LocalInterface(it.name, it.index, it.inetAddresses.toList())
            }
        }.getOrDefault(emptyList())
        return ownAddress(host, interfaces)
    }

    /** Returns an already-resolved address that can be handed straight to a Socket; the caller must not build an [InetSocketAddress] from the original host again. */
    fun ownSocketAddress(host: String, port: Int): InetSocketAddress? =
        ownAddress(host)?.let { InetSocketAddress(it, port) }

    private fun ownAddress(host: String, interfaces: List<LocalInterface>): InetAddress? {
        val address = parseLiteral(host, interfaces) ?: return null
        if (address.isLoopbackAddress || address.address.size == 4 && address.address[0] == 127.toByte()) return address
        return address.takeIf { target ->
            interfaces.any { nic -> nic.addresses.any { local -> sameAddress(target, local) } }
        }
    }

    private fun sameAddress(target: InetAddress, local: InetAddress): Boolean {
        if (!target.address.contentEquals(local.address)) return false
        // A literal without a scope matches by address; one with a scope must land on the same interface, so another interface with an identically named address must not be accepted.
        return target !is Inet6Address || local !is Inet6Address || target.scopeId == 0 || target.scopeId == local.scopeId
    }

    private fun parseLiteral(raw: String, interfaces: List<LocalInterface>): InetAddress? {
        var host = raw.trim()
        if (host.equals("localhost", ignoreCase = true) || host.equals("localhost.", ignoreCase = true)) {
            return InetAddress.getByAddress(null, LOOPBACK4)
        }
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        if (host.startsWith('[') || host.endsWith(']')) {
            if (!host.startsWith('[') || !host.endsWith(']') || host.length < 3) return null
            host = host.substring(1, host.length - 1)
            // Square brackets are the IPv6 literal notation, not a generic wrapper for arbitrary strings.
            if (':' !in host || '[' in host || ']' in host) return null
        }
        return if (':' in host) parseIpv6(host, interfaces) else parseIpv4(host)?.let { InetAddress.getByAddress(null, it) }
    }

    /** Strictly four decimal parts; no shortened, hexadecimal, octal, or single-integer form of IPv4. */
    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
            val value = part.fold(0) { n, c -> n * 10 + (c - '0') }
            if (value > 255) return null
            out[i] = value.toByte()
        }
        return out
    }

    private fun parseIpv6(host: String, interfaces: List<LocalInterface>): InetAddress? {
        val percent = host.indexOf('%')
        if (percent >= 0 && host.indexOf('%', percent + 1) >= 0) return null
        val address = if (percent >= 0) host.substring(0, percent) else host
        val zone = if (percent >= 0) host.substring(percent + 1) else ""
        if (percent >= 0 && zone.isEmpty()) return null
        val scope = when {
            percent < 0 -> 0
            zone.all { it in '0'..'9' } -> zone.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
            else -> interfaces.firstOrNull { it.name == zone }?.index ?: return null
        }
        val bytes = ipv6Bytes(address) ?: return null
        // IPv4-mapped IPv6 is normalized to IPv4 so that the 127/8 and local-interface comparisons cannot be bypassed by the 16-byte representation.
        if (isIpv4Mapped(bytes)) {
            if (scope != 0) return null
            return InetAddress.getByAddress(null, bytes.copyOfRange(12, 16))
        }
        return Inet6Address.getByAddress(null, bytes, scope)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()

    /** Expands IPv6 by itself, avoiding InetAddress.getByName, which may still go through the system name resolver even for malformed numeric input. */
    private fun ipv6Bytes(address: String): ByteArray? {
        if (address.isEmpty()) return null
        val halves = address.split("::")
        if (halves.size > 2) return null
        val compressed = halves.size == 2
        val left: List<Int>
        val right: List<Int>
        val whole: List<Int>
        if (compressed) {
            left = ipv6Groups(halves[0], allowIpv4 = false) ?: return null
            right = ipv6Groups(halves[1], allowIpv4 = true) ?: return null
            whole = left + right
            if (whole.size >= 8) return null
        } else {
            whole = ipv6Groups(address, allowIpv4 = true) ?: return null
            if (whole.size != 8) return null
            left = whole
            right = emptyList()
        }
        val out = ByteArray(16)
        fun write(groups: List<Int>, startGroup: Int) {
            for ((i, group) in groups.withIndex()) {
                out[(startGroup + i) * 2] = (group ushr 8).toByte()
                out[(startGroup + i) * 2 + 1] = group.toByte()
            }
        }
        if (compressed) {
            write(left, 0)
            write(right, 8 - right.size)
        } else {
            write(whole, 0)
        }
        return out
    }

    /** Returns the 16-bit groups; a trailing embedded IPv4 occupies two groups. */
    private fun ipv6Groups(text: String, allowIpv4: Boolean): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        if (parts.any { it.isEmpty() }) return null
        val groups = ArrayList<Int>(8)
        for ((i, part) in parts.withIndex()) {
            if ('.' in part) {
                if (!allowIpv4 || i != parts.lastIndex) return null
                val ipv4 = parseIpv4(part) ?: return null
                groups += ((ipv4[0].toInt() and 0xff) shl 8) or (ipv4[1].toInt() and 0xff)
                groups += ((ipv4[2].toInt() and 0xff) shl 8) or (ipv4[3].toInt() and 0xff)
            } else {
                if (part.length > 4 || part.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
                groups += part.toInt(16)
            }
        }
        return groups.takeIf { it.size <= 8 }
    }
}
