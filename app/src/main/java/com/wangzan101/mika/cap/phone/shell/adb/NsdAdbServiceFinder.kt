package com.wangzan101.mika.cap.phone.shell.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.wangzan101.mika.core.device.adb.AdbServiceFinder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Uses the system mDNS (NsdManager) to find the port advertised by the local adbd. Only results whose address
 * belongs to a local network interface are accepted, because other phones on the same LAN also advertise
 * services under the same name. Once the port is obtained, the caller connects to 127.0.0.1.
 * After the first hit, wait an extra [SETTLE_MS] to collect all entries advertised at the same time (stale
 * records and new ones may come back together) and resolve them one by one (older systems resolve only one at
 * a time).
 */
class NsdAdbServiceFinder(context: Context) : AdbServiceFinder {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override suspend fun findPorts(serviceType: String, timeoutMs: Long): List<Int> {
        val services = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { services.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { services.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        val ports = LinkedHashSet<Int>()
        try {
            withTimeoutOrNull(timeoutMs) {
                var settleUntil = Long.MAX_VALUE
                while (System.currentTimeMillis() < settleUntil) {
                    val info = withTimeoutOrNull((settleUntil - System.currentTimeMillis()).coerceAtMost(timeoutMs)) {
                        services.receiveCatching().getOrNull()
                    } ?: break
                    resolve(info)?.let {
                        ports += it
                        if (settleUntil == Long.MAX_VALUE) settleUntil = System.currentTimeMillis() + SETTLE_MS
                    }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }

    /** Resolves one service and returns its port if it is local; null when resolution fails or it is not local. */
    private suspend fun resolve(info: NsdServiceInfo): Int? {
        val done = Channel<Int?>(1)
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { done.trySend(null) }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                done.trySend(info.port.takeIf { isLocal(info.host) })
            }
        })
        return withTimeoutOrNull(RESOLVE_MS) { done.receive() }
    }

    private fun isLocal(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nic -> nic.inetAddresses.asSequence().any { it == host } }
        }.getOrDefault(false)
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val RESOLVE_MS = 1_500L
    }
}
