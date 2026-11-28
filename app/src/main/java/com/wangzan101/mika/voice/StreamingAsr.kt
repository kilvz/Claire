package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * One client shared by streaming ASR (Doubao, self-hosted Fun-ASR, Bailian), so there is only one connection pool.
 * Separate from the chat model client: no redirects (credentials only go to the configured address), no read timeout (speech may take a long time to produce a result), no automatic retries.
 */
internal val streamingAsrHttp: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).build()

/** Established ASR WebSocket: server messages go into the bounded [incoming], and the connection closes when it drops (carrying the reason on abnormal disconnects). */
internal class AsrWebSocket<T>(val incoming: Channel<T>, private val socket: WebSocket) {
    fun sendText(text: String) { if (!socket.send(text)) throw IOException("ASR send failed") }

    /** A send-queue backlog over 256 KiB (the network is stuck) counts as a failure; audio is not accumulated without bound. */
    fun sendBinary(data: ByteArray) {
        if (socket.queueSize() > MAX_QUEUE || !socket.send(data.toByteString())) throw IOException("ASR send failed")
    }

    fun close() { socket.cancel(); incoming.cancel() }

    private companion object { const val MAX_QUEUE = 256 * 1024L }
}

/**
 * Returns once connected; on handshake failure, 401/403 report [ModelError.Auth] and 429 reports [ModelError.RateLimited]; anything else counts as a network error. None of them include the server's own wording.
 * Received messages go through [fromText]/[fromBytes] into [T] (which may throw IOException to reject them); a single message over 1 MiB ([size] measure) or a full buffer closes the connection.
 */
internal suspend fun <T> openAsrWebSocket(
    client: OkHttpClient,
    request: Request,
    capacity: Int,
    fromText: (String) -> T,
    fromBytes: (ByteString) -> T,
    size: (T) -> Int,
): AsrWebSocket<T> {
    val ready = CompletableDeferred<Unit>()
    val messages = Channel<T>(capacity)
    val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) { offer(webSocket) { fromText(text) } }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { offer(webSocket) { fromBytes(bytes) } }
        private inline fun offer(webSocket: WebSocket, read: () -> T) {
            val message = try { read() } catch (e: IOException) { messages.close(e); webSocket.cancel(); return }
            if (size(message) > MAX_MESSAGE || messages.trySend(message).isFailure) {
                messages.close(IOException("ASR response buffer exceeded")); webSocket.cancel()
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            messages.close(); webSocket.close(code, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = when (response?.code) {
                401, 403 -> ModelError.Auth()
                429 -> ModelError.RateLimited(null)
                else -> ModelError.Network()
            }
            response?.close()
            ready.completeExceptionally(error)
            messages.close(error)
        }
    })
    try { ready.await() } catch (e: Throwable) { socket.cancel(); messages.cancel(); throw e }
    return AsrWebSocket(messages, socket)
}

private const val MAX_MESSAGE = 1024 * 1024

/**
 * What one streaming ASR protocol does in a session: connect, open, wait for start, read results, send audio, wrap up. A new instance per session (may carry state).
 * Recording, upload and receiving results run in parallel; the per-stage timeouts and how errors are reported are handled by [StreamingAsrSession].
 */
internal interface AsrStream {
    /** How many samples per packet. */
    val packetSamples: Int

    /** Minimum milliseconds between two audio packets; 0 means send as much as arrives. */
    val packetGapMs: Long

    /** Send even when the last packet is empty (the protocol relies on it to mark end of audio); otherwise nothing is sent when no audio remains. */
    val sendsEmptyLast: Boolean get() = false

    /** Upper bound for the whole session (connect to final result); null means no limit. */
    val totalTimeoutMs: Long? get() = null

    /** Message reported when the server reply cannot be parsed. */
    val invalidResponse: String

    /** What the protocol's own error (an error code reported by the server) is reported as; returns null if it is not that kind of error. */
    fun serviceError(e: Exception): ModelError?

    suspend fun connect()

    /** Message sent first after connecting. */
    fun open()

    /**
     * Wait until the server confirms audio can be sent (within the connect timeout); interim text is handed out via [partial].
     * A non-null return means the server has already produced the final result, so no more audio is sent.
     */
    suspend fun awaitStart(partial: (stable: String, tail: String) -> Unit): String?

    /** Read until the server says it is done, then return the final text; throws IOException if the connection drops first. */
    suspend fun awaitFinal(partial: (stable: String, tail: String) -> Unit): String

    /** Send the first [count] samples of [pcm]; [last] is the final packet after recording ends. */
    fun sendAudio(pcm: ShortArray, count: Int, last: Boolean)

    /** Wrap-up message to send after all audio has been sent. */
    fun endAudio() {}

    /** Disconnect; may also be called before the connection is established. */
    fun close()
}

/**
 * One push-to-talk: recording and upload, and receiving results, run concurrently; after release, send the remaining audio and the wrap-up message, and deliver Final only once the server says it is done.
 * Disconnects and timeouts count as failures; unfinished interim results are not handed out as final text (handed-out text gets forwarded to the model).
 * Connecting, waiting for start and waiting for the final result each have a timeout; the whole-session bound comes from the protocol ([AsrStream.totalTimeoutMs]).
 */
internal class StreamingAsrSession(
    private val recorder: StreamingRecorder,
    private val stream: AsrStream,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 10_000,
    override val unlimited: Boolean = false,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    private var work: Job? = null
    private var levels: Job? = null
    private var stopping: Job? = null
    private var released = false

    init {
        if (!recorder.start()) out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
            work = scope.launch { runSession() }
        }
    }

    private fun partial(stable: String, tail: String) = out.send(SpeechEvent.Partial(stable, tail))

    private suspend fun runSession() {
        try {
            val total = stream.totalTimeoutMs
            if (total == null) converse() else withTimeout(total) { converse() }
        } catch (e: TimeoutCancellationException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.finish(SpeechEvent.Failed(failure(e)))
        } finally {
            stream.close()
            levels?.cancel()
            recorder.cancel()
        }
    }

    private fun failure(e: Exception): SpeechFailure = stream.serviceError(e)?.let(SpeechFailure::Provider) ?: when (e) {
        is ModelError -> SpeechFailure.Provider(e)
        is IOException -> SpeechFailure.Network
        else -> SpeechFailure.Provider(ModelError.BadResponse(stream.invalidResponse))
    }

    private suspend fun converse() {
        withTimeout(connectTimeoutMs) { stream.connect() }
        stream.open()
        // Do not send audio before the server confirms; recording during this time stays in the recorder's queue
        val early = withTimeout(connectTimeoutMs) { stream.awaitStart(::partial) }
        if (early != null) { out.finish(SpeechEvent.Final(early)); return }
        coroutineScope {
            val result = async { stream.awaitFinal(::partial) }
            val audioEnded = CompletableDeferred<Unit>()
            val sender = launch {
                val buffer = ShortArray(stream.packetSamples)
                var size = 0
                var next = 0L
                suspend fun send(count: Int, last: Boolean) {
                    val wait = next - System.nanoTime()
                    if (wait > 0) delay((wait + 999_999) / 1_000_000)
                    stream.sendAudio(buffer, count, last)
                    next = System.nanoTime() + stream.packetGapMs * 1_000_000
                }
                for (chunk in recorder.chunks) {
                    var offset = 0
                    while (offset < chunk.size) {
                        val count = minOf(buffer.size - size, chunk.size - offset)
                        chunk.copyInto(buffer, size, offset, offset + count)
                        size += count; offset += count
                        if (size == buffer.size) { send(size, last = false); size = 0 }
                    }
                }
                if (size > 0 || stream.sendsEmptyLast) send(size, last = true)
                stream.endAudio()
                audioEnded.complete(Unit)
            }
            try {
                val finalText = select<String> {
                    result.onAwait { it }
                    audioEnded.onAwait { withTimeout(finalTimeoutMs) { result.await() } }
                }
                out.finish(SpeechEvent.Final(finalText))
            } finally { sender.cancelAndJoin() }
        }
    }

    @Synchronized override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        out.send(SpeechEvent.Converting)
        levels?.cancel()
        stopping = scope.launch { recorder.stop() }
    }

    @Synchronized override fun cancel() {
        if (!out.cancel()) return
        work?.cancel(); levels?.cancel(); stopping?.cancel()
        recorder.cancel()
    }
}
