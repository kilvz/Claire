package com.wangzan101.mika.core.device.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The wireless debugging port that was connected last time (App-private storage). */
interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

/**
 * How many candidates each source offered during one port search (not the ports themselves; for diagnostics).
 * When [scanned] is true, this device was scanned: [scanOpen] ports were open, of which [scanAdb] looked like adbd; [manual] means the port the user entered was carried this time.
 */
data class ConnectCandidates(
    val property: Int,
    val mdns: Int,
    val cache: Int,
    val connected: Boolean,
    val scanned: Boolean = false,
    val scanOpen: Int = 0,
    val scanAdb: Int = 0,
    val manual: Boolean = false,
)

/**
 * Find and connect to the local wireless debugging port. Tries in order and stops at the first that connects: the port used last time, a system property (only if readable), every port found by mDNS,
 * a local port scan ([scan], only when the earlier sources fail), and the port the user entered on the setup page ([manual]).
 * mDNS alone is not enough: adbd registers the service only once when wireless debugging is turned on, the registration is lost when the mDNS daemon restarts, and some networks block multicast;
 * mDNS may also find stale ports at the same time, so every port it finds is tried. A stale cached port is rejected quickly, so it is tried first.
 * When the user entered a port, do not scan (the field exists precisely because scanning failed before); try that port directly.
 * A rejection over TLS (UNAUTHORIZED, TLS_FAILED) does not necessarily mean the key is wrong: some systems let another service accept the first TLS handshake on the same port
 * and reject every certificate; an immediate reconnect then reaches adbd. So retry the same port once after [tlsRetryDelayMs], and if that fails continue with the other candidates;
 * report UNAUTHORIZED (re-pairing required) only when every candidate failed and at least one port rejected us this way. Even if a port rejected us, still scan local ports (skipping those already tried):
 * the cache, the property and mDNS may point to a stale port where some service still answers but does not accept the current key, while adbd has moved to another port.
 * The scan has a time limit; report UNAUTHORIZED only when nothing else is found.
 */
class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    /** Reported once per finished connection attempt (connected or failed) with how many candidates each source offered. */
    private val report: (ConnectCandidates) -> Unit = {},
    private val scan: LocalPortScan? = null,
    /** The port the user entered this time; null when none was entered. */
    private val manual: () -> Int? = { null },
    /** Reported once each when the local port scan starts and ends (the UI shows "Searching on this device…"). */
    private val scanning: (Boolean) -> Unit = {},
    private val localHost: String = LOCALHOST,
    private val tlsRetryDelayMs: Long = 300,
) {
    /** Tries the candidates in order with [open] and returns the first result that connects; when none connects, throws the UNAUTHORIZED from a port that rejected us, otherwise the last error. */
    suspend fun <T> connect(open: (host: String, port: Int) -> T): T {
        val tried = HashSet<AdbEndpoint>()
        val triedPorts = HashSet<Int>()
        var last: AdbConnectionException? = null
        var rejected: AdbConnectionException? = null
        val manualPort = manual()?.takeIf { it in 1..65535 }
        var scanResult: ScanResult? = null
        fun local(vararg ports: Int?) = ports.filterNotNull().map { AdbEndpoint(localHost, it) }
        val sources: List<suspend () -> List<AdbEndpoint>> = listOf(
            { local(cache.load()) },
            { local(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs).map { AdbEndpoint(localHost, it) } },
            {
                if (scan == null || manualPort != null) emptyList() else {
                    scanning(true)
                    try {
                        scan.scan(triedPorts).also { scanResult = it }.endpoints
                    } finally {
                        scanning(false)
                    }
                }
            },
            { local(manualPort) },
        )
        val counts = IntArray(sources.size)
        var connected = false
        try {
            for ((i, source) in sources.withIndex()) {
                // Sources that cannot be read (property blocked, mDNS cannot start) are treated as empty, not as a failure
                val endpoints = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                counts[i] = endpoints.size
                for (ep in endpoints) {
                    if (ep.port !in 1..65535 || !tried.add(ep)) continue
                    triedPorts += ep.port
                    var retried = false
                    while (true) {
                        try {
                            return open(ep.host, ep.port).also { cache.save(ep.port); connected = true }
                        } catch (e: AdbConnectionException) {
                            // An address-policy failure does not mean this port is temporarily unavailable: we must not continue with another port, let alone retry automatically later.
                            if (e.reason == AdbConnectionException.Reason.NOT_LOCAL) throw e
                            val tls = e.reason == AdbConnectionException.Reason.UNAUTHORIZED ||
                                e.reason == AdbConnectionException.Reason.TLS_FAILED
                            if (tls && !retried) {
                                retried = true
                                delay(tlsRetryDelayMs)
                                continue
                            }
                            if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) rejected = e
                            last = e
                            break
                        }
                    }
                }
            }
            throw rejected ?: last ?: AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found")
        } finally {
            val s = scanResult
            report(
                ConnectCandidates(
                    property = counts[1], mdns = counts[2], cache = counts[0], connected = connected,
                    scanned = s != null, scanOpen = s?.open ?: 0, scanAdb = s?.adb ?: 0, manual = manualPort != null,
                ),
            )
        }
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
