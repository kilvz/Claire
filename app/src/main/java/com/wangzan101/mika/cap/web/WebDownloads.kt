package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxTree
import android.util.Log
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.cap.web.DownloadAddressPolicy
import com.wangzan101.mika.core.cap.web.DownloadInfo
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web downloads: files land under downloads/ in this conversation's workspace (seen inside the sandbox as /workspace/downloads/<name>).
 * A workspace is only a folder inside the app's private directory, so downloads do not depend on the sandbox. Downloaded files are only saved, never opened or installed (APKs included).
 *
 * - The name comes from Content-Disposition (preferring filename*), otherwise from the name the page gave the link (`<a download>`, obtained by asking the page script), otherwise the last segment of the URL
 *   (blob:/data: URLs do not count); path separators, control characters, bidi control characters, and leading dots are removed,
 *   at most 80 characters, truncated by bytes (keeping the extension); an empty name becomes download and duplicates get " (2)". The name comes from the web page, so it is marked as web data when handed to the model.
 * - Writing to disk goes through [gate]: create a new file in downloads/ (never overwriting an existing name), verify by handle, then write on the same handle; on failure only that file itself is deleted.
 *   If downloads/ is replaced by a link (a command in the sandbox can do that), refuse and mark unsafe_path.
 * - Requests carry the Cookie for that URL (fetched for the post-redirect address on each hop), the UA, and the referer; only http/https is fetched.
 *   Every hop passes [addressRefusal] (the same rule as web_navigate, plus loopback and LAN are disallowed); a redirect to a disallowed address is marked blocked_redirect;
 *   hostnames resolving to the intranet are also refused ([dns]).
 * - Per-file limit [maxBytes]; exceeding it aborts, deletes the partial file, and marks too_large; blob:/data: content limit [maxBlobBytes].
 * - One task may have at most [maxFiles] files totaling [maxTotalBytes] (download_limit); one download (including all redirects) may take at most [timeoutMs] (timed_out).
 */
class WebDownloads(
    private val workspaceDir: File,
    private val gate: WorkspaceFileGate,
    /** The lock of the tree containing the workspace: downloads write to disk under its permission. */
    private val tree: SandboxTree,
    http: OkHttpClient,
    private val cookies: (String) -> String?,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 200L shl 20,
    private val maxBlobBytes: Long = 20L shl 20,
    private val maxFiles: Int = 50,
    private val maxTotalBytes: Long = 1L shl 30,
    private val timeoutMs: Long = 10 * 60_000L,
    /** Reason the address cannot be fetched (null = can fetch); tests allow the loopback test server. */
    private val addressRefusal: (String) -> String? = DownloadAddressPolicy::refusal,
    dns: Dns = DownloadAddressPolicy.guardedDns(),
) {
    private class Entry(val file: String, val mime: String?) {
        @Volatile var bytes = 0L
        @Volatile var status = RUNNING
        @Volatile var error: String? = null
        /** Handle used for writing (already verified by the gate); closed when finished (completed or failed). */
        var gated: GatedFile? = null
        /** Holds a task quota slot (one file + [charged] bytes written). */
        var counted = false
        var charged = 0L
    }

    private val entries = mutableListOf<Entry>()
    /** Quota: the number of files held and bytes written, all modified inside synchronized(entries). */
    private var files = 0
    private var totalBytes = 0L

    private val client = http.newBuilder()
        // Follow redirects one hop at a time, checking the address at every hop
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        // Each request after a redirect carries the Cookie for its own address
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val c = cookies(req.url.toString())
            chain.proceed(if (c.isNullOrEmpty()) req else req.newBuilder().header("Cookie", c).build())
        }
        .build()

    /** Downloads that are asking the page for a name and have not been registered yet. */
    private val naming = AtomicInteger()

    /**
     * The name a page gives a download (`<a download>`) exists only in the page and is unavailable to the download listener: first ask the page via [pageName], then hand the answer (or lack of one) to [then] for registration.
     * Asking for the name counts as the download having started, and [awaitNamed] waits for them all to register. [then] runs under the permission for writing `files/sandbox/` (see SandboxTree).
     */
    fun withPageName(pageName: suspend () -> String?, then: (String?) -> Unit) {
        naming.incrementAndGet()
        scope.launch {
            try {
                val name = try { pageName() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                tree.shared { then(name) }
            } finally {
                naming.decrementAndGet()
            }
        }
    }

    /** Wait for downloads that are asking for names to register, at most [capMs]. */
    suspend fun awaitNamed(capMs: Long) {
        withTimeoutOrNull(capMs) { while (naming.get() > 0) delay(NAMING_POLL_MS) }
    }

    /**
     * Start downloading [url]; returns the sandbox path immediately with status running. [suggested] is the name given by the page.
     * [viaPage]: when the app itself cannot connect due to a TLS / handshake-type error (nothing has been written yet), have the page fetch it using the browser's own network stack instead (at most the given number of bytes);
     * it returns (content, type) on success and null on failure. If not provided, no switch is made. The caller only provides it when the URL is same-origin with the page (the page side also only fetches same-origin and does not follow redirects);
     * the address rules are checked again before switching paths.
     */
    fun start(
        url: String, ua: String, contentDisposition: String?, mime: String?, referer: String?, suggested: String? = null,
        viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = null,
    ): String {
        val name = fileName(contentDisposition, url, mime, suggested)
        if (WebUrlPolicy.checkNavigable(url) != null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
            return failed(name, mime, "not_a_web_address")
        }
        if (addressRefusal(url) != null) return failed(name, mime, BLOCKED_ADDRESS)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            val unreached = try {
                tree.shared { fetch(url, ua, referer, entry) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                networkError(e)
            }
            if (unreached != null) fallBack(entry, url, unreached, viaPage)
        }
        return entry.file
    }

    /**
     * The app could not connect ([reason] carries the real error type): if it is a TLS / handshake-type error, [viaPage] was provided, and the address still passes the rules, let the page fetch it instead,
     * and save the result if retrieved; otherwise record a failure with the reason.
     */
    private suspend fun fallBack(entry: Entry, url: String, reason: String, viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)?) {
        val usable = viaPage?.takeIf { reason.startsWith(TLS_ERROR) && addressRefusal(url) == null }
        val got = usable?.let { read ->
            try { read(maxBlobBytes) } catch (e: CancellationException) { fail(entry, "cancelled"); throw e } catch (e: Exception) { null }
        }
        if (got == null) return fail(entry, reason)
        if (got.first.size > maxBlobBytes) return fail(entry, TOO_LARGE)
        tree.shared { write(entry, got.first) }
    }

    /** blob: content read by the page script, or decoded data: content; saved directly. */
    fun save(sourceUrl: String, contentDisposition: String?, mime: String?, bytes: ByteArray, suggested: String? = null): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        if (bytes.size > maxBlobBytes) return failed(name, mime, TOO_LARGE)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        write(entry, bytes)
        return entry.file
    }

    /**
     * blob: download: register first (running), then have [read] make the page read the content out (at most [maxBlobBytes]); if it cannot be read, give the reason.
     * [read] returns (content, the type reported by the page) or a failure reason.
     */
    fun startFrom(
        sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String? = null,
        read: suspend (Long) -> Pair<ByteArray, String?>?,
    ): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            try {
                val got = read(maxBlobBytes) ?: return@launch fail(entry, "unreadable")
                if (got.first.size > maxBlobBytes) return@launch fail(entry, TOO_LARGE)
                tree.shared { write(entry, got.first) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: BlobRefused) {
                fail(entry, e.reason)
            } catch (e: Exception) {
                Log.w(TAG, "blob download failed", e)
                fail(entry, "unreadable")
            }
        }
        return entry.file
    }

    /** An explicit reason reported by [startFrom]'s reader (e.g. too_large). */
    class BlobRefused(val reason: String) : Exception(reason)

    private fun localName(sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String?) =
        fileName(contentDisposition, sourceUrl.takeUnless { it.startsWith("blob:", true) || it.startsWith("data:", true) }.orEmpty(), mime, suggested)

    private fun write(entry: Entry, bytes: ByteArray) {
        try {
            if (!charge(entry, bytes.size.toLong())) return fail(entry, DOWNLOAD_LIMIT)
            entry.gated!!.write(bytes)
            entry.bytes = bytes.size.toLong()
            done(entry)
        } catch (e: IOException) {
            fail(entry, "write_failed")
        }
    }

    /**
     * data: URL: decode as base64 or percent-encoding and save; a failed decode is recorded as a failure.
     * Check the length before decoding: base64 gives 3 bytes per 4 characters, and percent-encoding uses at least 1 character per byte, so anything over the limit is not decoded.
     */
    fun dataUrl(url: String, contentDisposition: String?, suggested: String? = null): String {
        val comma = url.indexOf(',')
        val header = if (comma < 0) "" else url.substring(0, comma)
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { null }
        val base64 = header.endsWith(";base64", true)
        val payloadLength = if (comma < 0) 0L else (url.length - comma - 1).toLong()
        val limit = if (base64) maxBlobBytes * 4 / 3 + 8 else maxBlobBytes * 3
        if (payloadLength > limit) return failed(fileName(contentDisposition, "", mime, suggested), mime, TOO_LARGE)
        val payload = if (comma < 0) "" else url.substring(comma + 1)
        val bytes = runCatching {
            if (base64) Base64.getDecoder().decode(payload) else decodeDataPayload(payload)
        }.getOrNull() ?: return failed(fileName(contentDisposition, "", mime, suggested), mime, "bad_data_url")
        return save(url, contentDisposition, mime, bytes, suggested)
    }

    fun list(): List<DownloadInfo> = synchronized(entries) {
        entries.map { DownloadInfo(it.file, it.bytes, it.mime, it.status, it.error) }
    }

    /** Downloads started after this point; used to mention them in the result of the action that triggered the download. */
    fun count(): Int = synchronized(entries) { entries.size }

    fun since(count: Int): List<DownloadInfo> = list().drop(count)

    /**
     * Fetch hop by hop: check the address before each hop; the whole download (including redirects and reading the body) takes no more than [timeoutMs].
     * When it cannot connect (nothing has been written yet), do not record a failure; return the reason so the caller can switch paths. In all other cases record the outcome here and return null.
     */
    private fun fetch(url: String, ua: String, referer: String?, entry: Entry): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var current = url.toHttpUrlOrNull() ?: return fail(entry, "not_a_web_address").let { null }
        var hops = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return fail(entry, TIMED_OUT).let { null }
            val req = Request.Builder().url(current).header("User-Agent", ua).apply { referer?.let { header("Referer", it) } }.build()
            val call = client.newCall(req)
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                val reason = failureOf(e, hops, deadline)
                // A TLS / handshake-type error on the first hop: let the caller decide whether to fetch via the page instead; rejected addresses, timeouts, and post-redirect errors are recorded as they are
                if (reason.startsWith(TLS_ERROR) && hops == 0) return reason
                return fail(entry, reason).let { null }
            }
            try {
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return fail(entry, "http_${resp.code}").let { null }
                    if (++hops > MAX_REDIRECTS) return fail(entry, "too_many_redirects").let { null }
                    if (addressRefusal(next.toString()) != null) return fail(entry, BLOCKED_REDIRECT).let { null }
                    current = next
                    continue
                }
                if (!resp.isSuccessful) return fail(entry, "http_${resp.code}").let { null }
                val body = resp.body ?: return fail(entry, "empty_response").let { null }
                if (body.contentLength() > maxBytes) return fail(entry, TOO_LARGE).let { null }
                val out = entry.gated ?: return null
                val input = body.byteStream()
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (e: IOException) { return fail(entry, failureOf(e, hops, deadline)).let { null } }
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return fail(entry, TOO_LARGE).let { null }
                    if (!charge(entry, n.toLong())) return fail(entry, DOWNLOAD_LIMIT).let { null }
                    out.write(if (n == buf.size) buf else buf.copyOf(n))
                    entry.bytes = total
                    if (System.nanoTime() > deadline) return fail(entry, TIMED_OUT).let { null }
                }
                return done(entry).let { null }
            } finally {
                resp.close()
            }
        }
    }

    private fun failureOf(e: IOException, hops: Int, deadline: Long): String = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is DownloadAddressPolicy.BlockedAddress } -> if (hops == 0) BLOCKED_ADDRESS else BLOCKED_REDIRECT
        System.nanoTime() >= deadline || e is java.io.InterruptedIOException -> TIMED_OUT
        else -> networkError(e)
    }

    /**
     * Network errors carry the real error type (the innermost cause), e.g. tls_error (SSLHandshakeException: …), network_error (UnknownHostException: …);
     * certificate and handshake-type errors (SSLException) are called tls_error separately.
     */
    private fun networkError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val root = chain.last()
        val detail = root.message?.lineSequence()?.firstOrNull()?.take(MAX_ERROR_DETAIL)?.let { ": $it" }.orEmpty()
        val kind = if (chain.any { it is javax.net.ssl.SSLException }) TLS_ERROR else NETWORK_ERROR
        return "$kind (${root.javaClass.simpleName}$detail)"
    }

    /**
     * Reserve a name and a quota slot: create a new empty file in downloads/ through the gate (not following links, not overwriting existing ones) and keep the handle for writing.
     * Returns an already-failed record when the quota is insufficient or the directory is unsafe.
     */
    private fun reserve(name: String, mime: String?): Entry = synchronized(entries) {
        if (files >= maxFiles) return failedEntry(name, mime, DOWNLOAD_LIMIT)
        // The gate refuses (downloads/ was replaced by a link, etc.) or the file cannot be created: record unsafe_path
        val opened = try {
            workspaceDir.mkdirs()
            gate.createNumbered(workspaceDir, DIR, name, MAX_SAME_NAME)
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } ?: return failedEntry(name, mime, UNSAFE_PATH)
        files++
        Entry("$SANDBOX_DIR/${opened.first}", mime).also {
            it.gated = opened.second
            it.counted = true
            entries += it
        }
    }

    /** Record [n] newly written bytes; false if the task total would be exceeded. */
    private fun charge(entry: Entry, n: Long): Boolean = synchronized(entries) {
        if (totalBytes + n > maxTotalBytes) return false
        totalBytes += n
        entry.charged += n
        true
    }

    private fun done(entry: Entry) = synchronized(entry) {
        if (entry.status != RUNNING) return
        entry.status = DONE
        entry.gated?.close()
        entry.gated = null
    }

    private fun failed(name: String, mime: String?, error: String): String = failedEntry(name, mime, error).file

    private fun failedEntry(name: String, mime: String?, error: String): Entry =
        Entry("$SANDBOX_DIR/$name", mime).also { it.status = FAILED; it.error = error; synchronized(entries) { entries += it } }

    /** Failure: delete the file this download created itself (the one verified by handle; if the name has been swapped for something else, leave it alone) and release the quota. */
    private fun fail(entry: Entry, error: String) {
        synchronized(entry) {
            if (entry.status != RUNNING) return
            entry.status = FAILED
            entry.error = error
            entry.gated?.let { g -> runCatching { g.delete() }; g.close() }
            entry.gated = null
        }
        synchronized(entries) {
            if (entry.counted) {
                entry.counted = false
                files--
                totalBytes -= entry.charged
            }
        }
    }

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val TOO_LARGE = "too_large"
        const val UNSAFE_PATH = "unsafe_path"
        const val BLOCKED_ADDRESS = "blocked_address"
        const val BLOCKED_REDIRECT = "blocked_redirect"
        const val DOWNLOAD_LIMIT = "download_limit"
        const val TIMED_OUT = "timed_out"
        const val NETWORK_ERROR = "network_error"
        const val TLS_ERROR = "tls_error"
        private const val MAX_ERROR_DETAIL = 120
        private const val MAX_REDIRECTS = 10
        /** Names come from web pages, so their length is limited before they are handed to the model. */
        const val MAX_NAME_CHARS = 80
        private const val TAG = "MikaDownloads"
        private const val DIR = "downloads"
        private const val SANDBOX_DIR = "/workspace/downloads"
        private const val BUFFER = 64 * 1024
        private const val MAX_SAME_NAME = 999
        private const val MAX_NAME_BYTES = 200
        private const val MAX_EXT_BYTES = 20
        private const val FALLBACK = "download"
        private const val NAMING_POLL_MS = 20L

        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME_QUOTED = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
        private val FILENAME_TOKEN = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        /** Control characters, bidi control characters, and zero-width characters: they can make "exe" display as "pdf" and the like. */
        private val UNSAFE_CHARS = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
        private val MIME_EXT = mapOf(
            "application/pdf" to "pdf", "text/csv" to "csv", "text/plain" to "txt", "application/json" to "json",
            "application/zip" to "zip", "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls", "application/msword" to "doc", "text/html" to "html",
        )

        /** Non-base64 data: content: + and spaces are preserved as-is, %HH yields one byte directly, and unescaped Unicode is encoded as UTF-8. */
        private fun decodeDataPayload(payload: String): ByteArray {
            fun hex(c: Char) = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> -1
            }
            val out = ByteArrayOutputStream(payload.length)
            var start = 0
            while (start < payload.length) {
                if (payload[start] != '%') {
                    val end = payload.indexOf('%', start).let { if (it < 0) payload.length else it }
                    out.write(payload.substring(start, end).toByteArray(Charsets.UTF_8))
                    start = end
                } else {
                    require(start + 2 < payload.length) { "incomplete percent escape" }
                    val high = hex(payload[start + 1])
                    val low = hex(payload[start + 2])
                    require(high >= 0 && low >= 0) { "invalid percent escape" }
                    out.write((high shl 4) or low)
                    start += 3
                }
            }
            return out.toByteArray()
        }

        /** The download's file name: see the class documentation. [suggested] is the name given by the page (`<a download>`), ranked after the response header and before the URL. */
        fun fileName(contentDisposition: String?, url: String, mime: String?, suggested: String? = null): String {
            val fromHeader = contentDisposition?.let { cd ->
                FILENAME_STAR.find(cd)?.let { m ->
                    val charset = m.groupValues[1].ifBlank { "UTF-8" }
                    runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                } ?: FILENAME_QUOTED.find(cd)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                    ?: FILENAME_TOKEN.find(cd)?.groupValues?.get(1)
            }
            val fromUrl = runCatching { URI(url).rawPath }.getOrNull()?.substringAfterLast('/')
                ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            var name = clean(fromHeader ?: "").ifEmpty { clean(suggested ?: "") }.ifEmpty { clean(fromUrl ?: "") }.ifEmpty { FALLBACK }
            if (!name.contains('.')) MIME_EXT[mime?.substringBefore(';')?.trim()?.lowercase()]?.let { name = "$name.$it" }
            return fitBytes(name)
        }

        private fun clean(raw: String): String {
            // Keep only the last segment: both forward and backward slashes count as separators
            val last = raw.substringAfterLast('/').substringAfterLast('\\')
            return last.replace(UNSAFE_CHARS, "").trim().trimStart('.').trim().takeUnless { it == "." || it == ".." }.orEmpty()
        }

        private fun fitBytes(name: String): String {
            fun chars(t: String) = t.codePointCount(0, t.length)
            if (name.toByteArray().size <= MAX_NAME_BYTES && chars(name) <= MAX_NAME_CHARS) return name
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_EXT_BYTES }?.let { ".$it" }.orEmpty()
            var base = name.removeSuffix(ext)
            // Truncate by code point so surrogate pairs are not split
            while (((base + ext).toByteArray().size > MAX_NAME_BYTES || chars(base + ext) > MAX_NAME_CHARS) && base.isNotEmpty()) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            return (base + ext).ifEmpty { FALLBACK }
        }
    }
}
