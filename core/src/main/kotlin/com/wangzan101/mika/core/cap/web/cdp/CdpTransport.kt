package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A bidirectional byte stream. Once closed both directions end, and a blocking read must return or throw because of it. */
interface CdpStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Byte channel to a DevTools endpoint. The endpoint is an opaque string (a local abstract socket name, an address relayed through a shell, and so on)
 * interpreted by the implementation itself; every [open] is a new connection. Callers invoke it on IO threads, so it may block.
 */
fun interface CdpTransport {
    fun open(endpoint: String): CdpStream
}

@Serializable
data class CdpTargetInfo(
    val id: String,
    val type: String,
    val url: String = "",
    val title: String = "",
    /** DevTools omits this field when another client is already attached to the target. */
    val webSocketDebuggerUrl: String? = null,
)

@Serializable
data class CdpBrowserVersion(
    @SerialName("Browser") val browser: String = "",
    @SerialName("Protocol-Version") val protocolVersion: String = "",
    @SerialName("User-Agent") val userAgent: String = "",
    val webSocketDebuggerUrl: String? = null,
)

/** DevTools' HTTP discovery interface: `/json/version`, `/json/list`. Each request opens its own connection. */
object CdpDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun version(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CdpBrowserVersion =
        parseVersion(get(transport, endpoint, "/json/version", host, timeoutMs))

    suspend fun list(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<CdpTargetInfo> =
        parseList(get(transport, endpoint, "/json/list", host, timeoutMs))

    fun parseVersion(body: String): CdpBrowserVersion = json.decodeFromString(body)

    fun parseList(body: String): List<CdpTargetInfo> = json.decodeFromString(body)

    /** `ws://localhost/devtools/page/ID` → `/devtools/page/ID`; the transport layer ignores the host and only needs the path. */
    fun webSocketPath(webSocketUrl: String): String {
        val rest = webSocketUrl.substringAfter("://", webSocketUrl)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    /** The whole request takes at most [timeoutMs]: at the deadline the connection is closed, blocking reads and writes return, and it fails with [CdpTimeoutException]. */
    suspend fun get(transport: CdpTransport, endpoint: String, path: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        withStreamDeadline(timeoutMs, "GET $path", open = { transport.open(endpoint) }) { s ->
            s.use {
                s.output.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                s.output.flush()
                val input = s.input.buffered()
                val status = Http.readLine(input) ?: throw IOException("no HTTP response for $path")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                val headers = Http.readHeaders(input)
                val body = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Http.readChunked(input)
                    headers["content-length"] != null -> {
                        val n = headers.getValue("content-length").trim().toInt()
                        Http.readUpTo(input, n).also { if (it.size < n) throw IOException("HTTP body truncated") }
                    }
                    else -> input.readBytes()
                }
                if (code != 200) throw IOException("GET $path: $status")
                body.toString(Charsets.UTF_8)
            }
        }

    /** DevTools only accepts requests whose host name is localhost or an IP (to prevent DNS rebinding). */
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_TIMEOUT_MS = 5_000L
}

private val deadlines = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Opens a stream on IO threads and performs a blocking stretch of reads and writes, at most [timeoutMs]. At the deadline (or when the caller is cancelled, see closeOnCancel) the stream is closed,
 * so the blocking reads and writes throw and return instead of leaving the caller and the locks it holds stuck. A timeout fails with [CdpTimeoutException].
 * The blocking stretch runs in its own coroutine, so the caller can return without waiting for it to finish.
 */
internal suspend fun <S : Closeable, T> withStreamDeadline(
    timeoutMs: Long,
    what: String,
    open: () -> S,
    /** Close the stream immediately when the caller is cancelled; when false, let that stretch of reads and writes finish on its own (still bounded by [timeoutMs]). */
    closeOnCancel: Boolean = true,
    block: (S) -> T,
): T {
    val fired = AtomicBoolean(false)
    val opened = java.util.concurrent.atomic.AtomicReference<Closeable?>(null)
    val work = deadlines.async {
        val s = open()
        opened.set(s)
        if (fired.get()) {
            runCatching { s.close() }
            throw CdpTimeoutException(what, timeoutMs)
        }
        block(s)
    }
    val watchdog = deadlines.launch {
        delay(timeoutMs)
        fired.set(true)
        opened.get()?.let { runCatching { it.close() } }
    }
    // The watchdog follows this stretch of reads and writes, not the caller: even if the caller leaves first, they are still bounded
    work.invokeOnCompletion { watchdog.cancel() }
    try {
        return work.await()
    } catch (e: CancellationException) {
        if (closeOnCancel) opened.get()?.let { runCatching { it.close() } }
        throw e
    } catch (e: IOException) {
        if (fired.get() && e !is CdpTimeoutException) throw CdpTimeoutException(what, timeoutMs)
        throw e
    }
}

internal object Http {
    private const val MAX_LINE = 16 * 1024

    /** Reads one line (dropping the CRLF); returns null when the stream ends at the start of the line. */
    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw IOException("unexpected end of stream in header")
            }
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("header line too long")
        }
        val s = String(buf.toByteArray(), Charsets.ISO_8859_1)
        return s.removeSuffix("\r")
    }

    /** Header names are always lowercased. */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("unexpected end of stream in headers")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("bad header line: $line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /**
     * Reads up to [n] bytes, returning the part read if the stream ends first. Not using InputStream.readNBytes: that only exists from Android 13,
     * and likewise not ByteArrayOutputStream.toString(Charset).
     */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) Unit
                return out.toByteArray()
            }
            val chunk = readUpTo(input, size)
            if (chunk.size < size) throw IOException("HTTP chunk truncated")
            out.write(chunk)
            readLine(input)
        }
    }
}
