package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.McpRpc
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * The gateway for computer connections: every request passes through here first, and only then is handed to the protocol layer. The order is fixed:
 * requests from a browser (carrying Origin) always get 403; too many authentication failures from the same address get 429 first; a wrong access
 * token gets 401. Routing and error serialization are left to the entry adapters.
 * Tokens are compared in constant time; failure counts are recorded per request source ([HttpRequest.source]), and [clock] is replaceable so tests
 * can advance the time. During the cooldown even a correct token is not let through: letting it through would amount to telling the guesser "you
 * got it right this time", and then the cooldown could not stop token guessing.
 * Requests forwarded through the gateway are recorded separately per caller as seen by the gateway, so one anonymous caller guessing wrong only
 * cools down itself.
 * [token] is accepted from any connection; [loopbackToken] (the one used by computer connections over USB) is only accepted from connections
 * arriving on the local loopback.
 */
class RemoteAccess(
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 60_000,
    private val loopbackToken: () -> String? = { null },
) {
    sealed interface Verdict {
        data class Pass(val owner: String) : Verdict
        data class Reject(val status: Int, val message: String, val headers: Map<String, String> = emptyMap()) : Verdict
    }

    private class Failures(var count: Int, var since: Long, var lockedUntil: Long)
    private val failures = HashMap<String, Failures>()

    fun check(req: HttpRequest): Verdict {
        // Requests from browsers always carry Origin; MCP clients on a computer do not. A web page must not be able to reach this port (guards against DNS rebinding)
        if (req.header("origin") != null) return Verdict.Reject(403, "Browser requests are not allowed")
        val who = req.source
        val now = clock()
        synchronized(failures) {
            val f = failures[who]
            if (f != null && f.lockedUntil > now) {
                val wait = ((f.lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                return Verdict.Reject(429, "Too many failed attempts", mapOf("Retry-After" to wait.toString()))
            }
        }
        val given = presented(req)
        val loopback = if (req.remote.isLoopbackAddress) loopbackToken() else null
        // Compare both, without skipping a comparison just because the first one matched.
        val networkMatch = given != null && matches(given, token())
        val usbMatch = given != null && matches(given, loopback)
        if (given == null || !(networkMatch || usbMatch)) {
            fail(who, now)
            return Verdict.Reject(401, "Missing or wrong access token", mapOf("WWW-Authenticate" to "Bearer"))
        }
        synchronized(failures) { failures.remove(who) }

        return Verdict.Pass(if (networkMatch) RemoteOwnership.of(given) else RemoteOwnership.USB)
    }

    /** Local listening and remote forwarding share authentication and protocol errors; only after passing does a request enter the task layer. */
    fun handle(req: HttpRequest, next: (HttpRequest) -> HttpResponse): HttpResponse = when (val verdict = check(req)) {
        is Verdict.Reject -> if (req.path == McpRpc.PATH)
            McpRpc.transportError(verdict.status, verdict.message, verdict.headers)
        else RemoteTaskApi.error(verdict.status, "access_denied", verdict.message, verdict.headers)
        is Verdict.Pass -> next(req.ownedBy(verdict.owner))
    }

    private fun presented(req: HttpRequest) = req.header("authorization")?.let { h ->
        if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else null
    }

    private fun matches(given: String, expected: String?) = !expected.isNullOrEmpty() && same(given, expected)

    private fun fail(who: String, now: Long) = synchronized(failures) {
        val f = failures.getOrPut(who) { Failures(0, now, 0) }
        if (now - f.since > windowMs) { f.count = 0; f.since = now }
        f.count++
        if (f.count >= maxFailures) { f.lockedUntil = now + lockMs; f.count = 0; f.since = now }
        // Keep only the most recent addresses when there are many, so the table does not grow without bound
        if (failures.size > 256) failures.entries.minByOrNull { it.value.since }?.let { failures.remove(it.key) }
    }

    companion object {
        /** Constant-time comparison: first digest each into equal-length values, then compare byte by byte, so the time does not vary with how many leading characters matched. */
        fun same(a: String, b: String): Boolean {
            val d = MessageDigest.getInstance("SHA-256")
            val x = d.digest(a.toByteArray(Charsets.UTF_8))
            val y = d.digest(b.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(x, y)
        }

        /**
         * Whether to accept a connection from this address: the local loopback, LAN private ranges (10/8, 172.16/12, 192.168/16),
         * the carrier-grade NAT range commonly used by private mesh networks (100.64/10), IPv4 link-local, as well as IPv6 unique local
         * (fc00::/7) and link-local (fe80::/10). Public addresses (for example when on mobile data) are never accepted.
         */
        fun privateAddress(a: InetAddress): Boolean {
            val v4 = when (a) {
                is Inet4Address -> a.address
                is Inet6Address -> a.address.let { b ->
                    // An IPv4-mapped IPv6 form (::ffff:a.b.c.d) is treated as IPv4
                    if ((0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) b.copyOfRange(12, 16) else null
                }
                else -> null
            }
            if (v4 != null) {
                val o0 = v4[0].toInt() and 0xff
                val o1 = v4[1].toInt() and 0xff
                return o0 == 127 || o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) ||
                    (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
            }
            if (a.isLoopbackAddress) return true
            val b0 = a.address[0].toInt() and 0xff
            val b1 = a.address[1].toInt() and 0xff
            return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
        }
    }
}
