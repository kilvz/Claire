package com.wangzan101.mika.cap.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val MAX_CONNECTIONS = 4
/** Clients that connect and stay silent, or stop halfway through, are disconnected after 30 seconds. */
private const val CLIENT_TIMEOUT_MS = 30_000
private const val WAKE_DEADLINE_MS = 1_000L
private const val WAKE_RETRY_MS = 50L
private const val TAG = "SandboxBridge"

/**
 * Bridge for sandbox commands to call back into Claire: each run opens an abstract-namespace Unix socket (randomly named) plus a one-time token, passed to the command via environment variables,
 * and closes as soon as the command ends (including timeout, kill, or cancellation). The network cutoff filter only blocks AF_INET/AF_INET6, so the bridge works even while the network is cut.
 * Only connections with the same identity (uid) as Claire are accepted; the protocol is described in [BridgeProtocol].
 */
class SandboxBridge(
    private val onProgress: (String) -> Unit,
    private val onShare: suspend (sandboxPath: String, name: String?) -> String,
    private val info: () -> JsonObject = { JsonObject(emptyMap()) },
    private val onPhone: (suspend (String, JsonObject) -> JsonObject)? = null,
) {
    fun open(): Session {
        val rnd = SecureRandom()
        val name = "mika-bridge-" + String(CharArray(16) { NAME_CHARS[rnd.nextInt(NAME_CHARS.length)] })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))
        return Session(name, token, LocalServerSocket(name), onProgress, onShare, info, onPhone)
    }

    class Session internal constructor(
        val name: String, val token: String,
        private val server: LocalServerSocket,
        private val onProgress: (String) -> Unit,
        private val onShare: suspend (String, String?) -> String,
        private val info: () -> JsonObject,
        private val onPhone: (suspend (String, JsonObject) -> JsonObject)?,
    ) : Closeable {
        @Volatile private var closed = false
        private val clients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
        private val gate = ProgressGate()
        /** All in-flight exports live here; they are cancelled together when the session closes, so nothing more is written. */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** The tool context belongs to a single task, so phone requests arriving over multiple CLI connections are executed sequentially. */
        private val phoneLock = Mutex()
        private val acceptor = thread(isDaemon = true, name = "mika-bridge-accept") { acceptLoop() }

        private fun acceptLoop() {
            while (!closed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (closed) { runCatching { s.close() }; break }
                // Accept only the same identity; reject when there are too many connections (a single command does not need many)
                val uid = runCatching { s.peerCredentials.uid }.getOrNull()
                if (uid != Process.myUid() || clients.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                runCatching { s.soTimeout = CLIENT_TIMEOUT_MS }
                clients += s
                thread(isDaemon = true, name = "mika-bridge-conn") { serve(s) }
            }
        }

        private fun serve(s: LocalSocket) {
            try {
                val input = s.inputStream.buffered()
                val output = s.outputStream
                while (!closed) {
                    val reply = when (val line = BridgeProtocol.readLine(input)) {
                        BridgeProtocol.Line.End -> break
                        BridgeProtocol.Line.TooLarge -> BridgeReply("""{"ok":false,"error":"too_large"}""", close = true)
                        is BridgeProtocol.Line.Text -> runBlocking {
                            BridgeProtocol.handle(line.text, token, ::progress, ::share, info, ::phone) { Log.w(TAG, "bridge request failed", it) }
                        }
                    }
                    output.write((reply.json + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (reply.close) break
                }
            } catch (_: IOException) {
                // The peer disconnected or the session was closed
            } catch (_: CancellationException) {
                // The session was closed and in-flight exports were cancelled
            } finally {
                clients -= s
                runCatching { s.close() }
            }
        }

        private suspend fun share(path: String, name: String?): String {
            if (closed) throw CancellationException("bridge session closed")
            return work.async { onShare(path, name) }.await()
        }

        private suspend fun phone(tool: String, args: JsonObject): JsonObject {
            if (closed) throw CancellationException("bridge session closed")
            val handler = onPhone ?: return kotlinx.serialization.json.buildJsonObject {
                put("ok", false); put("error", "phone_unavailable")
            }
            return work.async { phoneLock.withLock { handler(tool, args) } }.await()
        }

        private fun progress(text: String): Boolean {
            if (closed || !gate.offer()) return false
            onProgress(text)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            work.cancel()
            // accept() does not necessarily return because of close: connect to it ourselves to wake it up (retrying while the connect fails) until the deadline, then close
            val deadline = System.currentTimeMillis() + WAKE_DEADLINE_MS
            while (acceptor.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
                acceptor.join(WAKE_RETRY_MS)
            }
            runCatching { server.close() }
            synchronized(clients) { clients.toList() }.forEach { c ->
                runCatching { c.shutdownInput() }
                runCatching { c.close() }
            }
        }
    }
}
