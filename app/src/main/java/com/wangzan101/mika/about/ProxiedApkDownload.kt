package com.wangzan101.mika.about

import android.net.Network
import com.wangzan101.mika.download.DownloadFile
import com.wangzan101.mika.download.VerifiedDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory

/**
 * In-app update downloads, used for automatic downloads bound to Wi-Fi and manual downloads through the app proxy.
 * A thin wrapper over [VerifiedDownload] with a fixed task key: it writes to `<target>.part`, renames to the target only when complete,
 * so "the target file exists" means the download finished. There is one instance per process (one download at a time; `start` cancels the previous one);
 * an unfinished download after the process is killed counts as failed and the user can retry. Voice packs and other downloads use different keys and never cancel each other.
 */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val downloads = VerifiedDownload(http, scope)
    @Volatile private var target: File? = null

    /**
     * Downloads the [sources] in order, moving to the next one when one fails; every source must pass [verify] (size, digest) after
     * download, and failing that also counts as a failure of that source. If all fail, no target file is left in the process, the
     * state returns to Failed and the user can retry.
     */
    @Synchronized
    fun start(
        sources: List<String>,
        file: File,
        wifi: Network? = null,
        verify: (File) -> Unit = {},
    ) {
        require(sources.isNotEmpty()) { "no download source" }
        cancel()
        target = file
        // Use Wi-Fi's own DNS and sockets: when it disconnects the connection fails instead of continuing on mobile data.
        val client = wifi?.let { network ->
            wifiBoundClient(http, network.socketFactory, object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
        }
        // Updates always download again: the old approach overwrote or deleted a same-named file instead of skipping already-verified ones as voice packs do.
        file.delete()
        downloads.start(KEY, listOf(DownloadFile(file, sources, verify)), client)
    }

    /** State of this [file]: downloading, downloaded, or failed (not downloading and no complete file). */
    @Synchronized
    fun state(file: File): ApkDownloadState {
        val progress = downloads.progress(KEY).value
        return when {
            progress.phase == DownloadPhase.Downloading && target == file ->
                ApkDownloadState(DownloadPhase.Downloading, progress.doneBytes, progress.totalBytes)
            file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
            else -> ApkDownloadState(DownloadPhase.Failed)
        }
    }

    @Synchronized
    fun cancel() {
        // Cut the connection too, without waiting for the current block to finish
        downloads.cancel(KEY)
        target?.let { File(it.path + PART).delete() }
    }

    companion object {
        private const val PART = ".part"
        private const val KEY = "app-update"

        /** Chooses the proxy per target on every route (including redirects), forbidding SOCKS that would bypass the Wi-Fi socket factory. */
        internal fun wifiBoundClient(http: OkHttpClient, sockets: SocketFactory, dns: Dns): OkHttpClient {
            val delegate = http.proxySelector
            val selector = object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    val proxies = http.proxy?.let { listOf(it) } ?: delegate.select(uri)
                    if (proxies.any { it.type() == Proxy.Type.SOCKS }) {
                        // IOException is retried by OkHttp during route selection, so fail without retrying.
                        throw IllegalStateException("SOCKS proxy cannot be bound to Wi-Fi")
                    }
                    return proxies
                }

                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
                    delegate.connectFailed(uri, address, error)
                }
            }
            return http.newBuilder().proxy(null).proxySelector(selector).socketFactory(sockets).dns(dns)
                .connectionPool(ConnectionPool()).build()
        }

        /** Downloads [url] to [file], reporting progress as it goes (done, total, -1 when total is unknown); throws on a non-2xx response or an incomplete download and leaves no target file. */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) =
            VerifiedDownload.fetch(http, url, file, onCall, progress)
    }
}
