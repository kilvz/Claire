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
 * Finds the connection port for wireless debugging on this device and connects to it. Tried in order, stopping at whichever connects: the port connected last time, the system property (used only when it can be read), every port found via mDNS,
 * a scan of local ports ([scan], only when the earlier ones all failed), and the port the user entered on the onboarding screen ([manual]).
 * mDNS alone is not enough: adbd registers the service only once when wireless debugging is turned on, and that registration is gone after the mDNS daemon restarts; some networks also block multicast;
 * mDNS may also resolve stale ports that are no longer valid, so every port found is tried. A stale cached port is rejected quickly, so it comes first.
 * Once the user has entered a port, no scan is done (the field exists only because scanning had failed), and that port is tried directly.
 * A TLS-level rejection (UNAUTHORIZED, TLS_FAILED) does not necessarily mean the key is wrong: some systems let another service catch the first TLS handshake on the same port,
 * rejecting every certificate, and only an immediate reconnect lands on adbd. So the same port is retried after [tlsRetryDelayMs], and if that still fails the remaining candidates are tried;
 * only when everything fails and some port rejected us this way is UNAUTHORIZED reported (re-pairing required). Once a port has rejected us, local port scanning is skipped:
 * adbd has been found, so a scan would only find it again.
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
                if (scan == null || manualPort != null || rejected != null) emptyList() else {
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
