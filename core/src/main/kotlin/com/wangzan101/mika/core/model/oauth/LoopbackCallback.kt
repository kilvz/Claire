package com.wangzan101.mika.core.model.oauth

import com.wangzan101.mika.core.device.adb.OwnListeningPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The small local server used for the sign-in callback (shared by ChatGPT and OpenRouter sign-in): it binds only 127.0.0.1 (no
 * other device can connect) and the system picks a free port. After signing in, the browser is redirected back to
 * `http://127.0.0.1:<port>/auth/callback?…` (or written as localhost); the parameters are read here, this sign-in is finished
 * (token exchange, verification) and then a result page is returned; meanwhile the browser keeps waiting on this connection.
 * Other paths (such as an icon the browser happens to request) get 404. Only one callback is accepted; it must be [close]d
 * when finished or abandoned.
 */
class LoopbackCallback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    // When scanning for wireless debugging ports, scan the local machine but not this listening socket
    init { OwnListeningPorts.add(server.localPort) }
    val redirectUri: String get() = redirectUri(HOST)

    /** The callback address is written as [host] (`127.0.0.1` or `localhost`, both meaning this machine): some services accept only one of the two spellings. */
    fun redirectUri(host: String): String = "http://$host:$port$PATH"

    /**
     * Waits for the callback, at most [timeoutMs]; null on timeout. After receiving the callback, [page] is called first (it may
     * suspend: finish the sign-in inside it) and the page it returns is sent back to the browser, so the page can show the final
     * result. On coroutine cancellation it returns immediately (the connection is closed).
     * A callback that [accept] does not recognize (e.g. a state mismatch: not started by this sign-in) gets 400 and waiting
     * continues.
     */
    suspend fun await(
        timeoutMs: Long,
        accept: (Map<String, String>) -> Boolean = { true },
        page: suspend (Map<String, String>) -> String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@withContext null }
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = try { reader.readLine() } catch (e: IOException) { null } ?: return@use
                // Drain the request headers
                while (true) {
                    val line = try { reader.readLine() } catch (e: IOException) { null }
                    if (line.isNullOrEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                if (parts.firstOrNull() != "GET" || path != PATH) {
                    respond(s.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                    return@use
                }
                // Treat malformed escaping (e.g. a lone %) as not this sign-in's callback
                val params = runCatching { parseQuery(target.substringAfter('?', "")) }.getOrNull()
                if (params == null || !accept(params)) {
                    respond(s.getOutputStream(), 400, "Bad Request", "text/plain", "Bad Request")
                    return@use
                }
                respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", page(params))
                return@withContext params
            }
        }
        null
    }

    override fun close() {
        OwnListeningPorts.remove(server.localPort)
        runCatching { server.close() }
    }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000

        /** Opens a listener; [port] 0 lets the system pick one. */
        fun open(port: Int = 0): LoopbackCallback = LoopbackCallback(ServerSocket(port, 4, InetAddress.getByName(HOST)))

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
