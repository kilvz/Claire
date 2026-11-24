package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A protocol event. A non-null [sessionId] means it comes from a child session of a flattened attach (`Target.setAutoAttach` with `flatten=true`). */
data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String? = null)

/** The peer returned an error object for a call. */
class CdpException(val code: Int, val errorMessage: String, val method: String, val data: String? = null) :
    RuntimeException("$method failed ($code): $errorMessage" + (data?.let { " — $it" } ?: ""))

class CdpTimeoutException(val method: String, val timeoutMs: Long) : IOException("$method timed out after ${timeoutMs}ms")

/** The connection is already lost or closed; when it drops, calls still awaiting a reply fail with this. */
class CdpConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A JSON-RPC session over one DevTools WebSocket. A single reader coroutine receives packets: responses are returned by id, everything else is emitted as an event;
 * sending happens on IO threads and frames are written serially. The same connection can carry multiple flattened child sessions, distinguished by sessionId.
 *
 * The event stream is not replayed; events are dropped outright when there are no subscribers. [eventBuffer] events are buffered, and when a subscriber cannot keep up the oldest are dropped,
 * so the reader coroutine never blocks on events (otherwise responses would be stuck too). Subscribers that need the complete event sequence must consume promptly
 * and start subscribing before sending the command that triggers the events.
 */
class CdpSession(
    private val ws: WebSocketConnection,
    eventBuffer: Int = DEFAULT_EVENT_BUFFER,
) : Closeable {
    private class Pending(val method: String, val result: CompletableDeferred<JsonObject>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closeError: CdpConnectionClosedException? = null

    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = eventBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    /**
     * Ratio of Input domain (mouse, drag-and-drop) coordinates to CSS pixels: the coordinates sent = CSS pixels × this.
     * Normally 1; when viewport emulation includes scaling (a desktop-width page shrunk into a phone view), Input interprets coordinates in the pre-scale view coordinate system, and whoever sets up the emulation changes this.
     */
    @Volatile var inputScale: Double = 1.0

    /** Where the last mouse event landed (CSS pixels); null if none has been sent yet. The next move travels from here along a path. */
    @Volatile var pointer: Pair<Double, Double>? = null

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private val reader = scope.launch { readLoop() }

    suspend fun send(
        method: String,
        params: JsonObject = EMPTY,
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        closeError?.let { throw CdpConnectionClosedException(it.message ?: "closed", it.cause) }
        val id = nextId.getAndIncrement()
        val p = Pending(method, CompletableDeferred())
        pending[id] = p
        // The reader coroutine sets closeError before clearing pending: check once more after registering here, so nothing is missed
        closeError?.let {
            pending.remove(id)
            throw CdpConnectionClosedException(it.message ?: "closed", it.cause)
        }
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }.toString()
        try {
            // Writes are bounded too: when the peer stops reading and the buffer fills up, the connection is dropped at the deadline rather than leaving the caller stuck in the write
            withStreamDeadline(timeoutMs, method, open = { Closeable { ws.abort() } }, closeOnCancel = false) { ws.sendText(msg) }
        } catch (e: IOException) {
            pending.remove(id)
            ws.abort()
            throw CdpConnectionClosedException("CDP connection lost while sending $method", e)
        }
        try {
            return withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw CdpTimeoutException(method, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    /** Sends the close frame and disconnects; unfinished calls fail immediately. */
    override fun close() {
        if (closeError != null) return
        shutdown(CdpConnectionClosedException("CDP session closed"))
        runCatching { ws.sendClose() }
        // Give the peer a moment to send its close frame back, then hard-abort
        scope.launch {
            withTimeoutOrNull(CLOSE_GRACE_MS) { reader.join() }
            ws.abort()
            scope.cancel()
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val text = ws.receive() ?: break
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) {
                    val p = pending[id] ?: continue
                    val error = obj["error"] as? JsonObject
                    if (error != null) {
                        p.result.completeExceptionally(
                            CdpException(
                                code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "",
                                method = p.method,
                                data = error["data"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
                            ),
                        )
                    } else {
                        p.result.complete(obj["result"] as? JsonObject ?: EMPTY)
                    }
                    continue
                }
                val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                _events.tryEmit(
                    CdpEvent(method, obj["params"] as? JsonObject ?: EMPTY, (obj["sessionId"] as? JsonPrimitive)?.contentOrNull),
                )
            }
        } catch (t: Throwable) {
            cause = t
        }
        val reason = when {
            closeError != null -> null
            ws.closeCode != null -> "CDP connection closed by peer (${ws.closeCode}${ws.closeReason.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""})"
            else -> "CDP connection lost: ${cause?.message ?: "end of stream"}"
        }
        if (reason != null) shutdown(CdpConnectionClosedException(reason, cause))
        ws.abort()
    }

    private fun shutdown(error: CdpConnectionClosedException) {
        synchronized(this) {
            if (closeError != null) return
            closeError = error
            // Let callers see "closed" first, then wake in-flight calls; after catching the exception a caller can recover immediately according to the closed state.
            _closed.value = true
        }
        for (id in pending.keys.toList()) {
            pending.remove(id)?.result?.completeExceptionally(
                CdpConnectionClosedException(error.message ?: "closed", error.cause),
            )
        }
        _closed.value = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVENT_BUFFER = 1024
        private const val CLOSE_GRACE_MS = 1_000L
        private val EMPTY = JsonObject(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }

        /** Opens the transport, performs the WebSocket handshake and returns the session. [path] comes from the target's webSocketDebuggerUrl. Throws [CdpTimeoutException] on timeout. */
        suspend fun connect(
            transport: CdpTransport,
            endpoint: String,
            path: String,
            host: String = CdpDiscovery.DEFAULT_HOST,
            maxMessageBytes: Int = WebSocketConnection.DEFAULT_MAX_MESSAGE_BYTES,
            eventBuffer: Int = DEFAULT_EVENT_BUFFER,
            handshakeTimeoutMs: Long = CdpDiscovery.DEFAULT_TIMEOUT_MS,
        ): CdpSession {
            // The handshake gets at most [handshakeTimeoutMs]: at the deadline the stream is closed and a stuck handshake fails with it
            val ws = withStreamDeadline(handshakeTimeoutMs, "WebSocket handshake $path", open = { transport.open(endpoint) }) { s ->
                WebSocketConnection.connect(s, path, host, maxMessageBytes)
            }
            return CdpSession(ws, eventBuffer)
        }
    }
}
