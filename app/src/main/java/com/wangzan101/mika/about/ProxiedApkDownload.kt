package com.wangzan101.mika.about

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext

/**
 * In-app update download, used for automatic downloads bound to Wi-Fi and for manual downloads going through the app proxy.
 * It writes to `<target>.part` first and renames to the target file only once complete, so "the target file exists" means it is done.
 * There is only one per process (one download at a time); if the process is killed, an unfinished download counts as failed and the user can retry.
 */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var target: File? = null
    @Volatile private var bytes = 0L
    @Volatile private var total = -1L

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
        bytes = 0
        total = -1
        // Use Wi-Fi's own DNS and sockets: once it disconnects, connections fail instead of falling back to mobile data and continuing the download.
        val client = wifi?.let { network ->
            wifiBoundClient(http, network.socketFactory, object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
        } ?: http
        val download = scope.launch(start = CoroutineStart.LAZY) {
            val current = coroutineContext[Job]!!
            for (url in sources) {
                coroutineContext.ensureActive()
                synchronized(this@ProxiedApkDownload) {
                    if (job === current) { bytes = 0; total = -1 }
                }
                try {
                    fetch(client, url, file, onCall = { next ->
                        synchronized(this@ProxiedApkDownload) {
                            if (job === current && current.isActive) call = next else next.cancel()
                        }
                    }) { done, size ->
                        synchronized(this@ProxiedApkDownload) {
                            if (job === current) { bytes = done; total = size }
                        }
                    }
                    coroutineContext.ensureActive()
                    verify(file)
                    coroutineContext.ensureActive()
                    return@launch
                } catch (e: CancellationException) {
                    file.delete()
                    throw e
                } catch (_: Exception) {
                    // A connection exception caused by cancellation must not trigger the next source.
                    file.delete()
                    coroutineContext.ensureActive()
                }
            }
        }
        job = download
        download.start()
    }

    /** State of this [file]: downloading, downloaded, or failed (not downloading and no complete file). */
    @Synchronized
    fun state(file: File): ApkDownloadState = when {
        job?.isActive == true && target == file -> ApkDownloadState(DownloadPhase.Downloading, bytes, total)
        file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
        else -> ApkDownloadState(DownloadPhase.Failed)
    }

    @Synchronized
    fun cancel() {
        job?.cancel()
        // Cut the connection as well instead of waiting for this chunk to finish reading
        call?.cancel()
        job = null
        call = null
        target?.let { File(it.path + PART).delete() }
    }

    companion object {
        private const val PART = ".part"

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

        /** Downloads [url] into [file]: reporting progress along the way (downloaded, total; -1 when the total is unknown); throws if the response is not 2xx or the download is incomplete, and leaves no target file. */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                coroutineContext.ensureActive()
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                coroutineContext.ensureActive()
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
