package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
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
 * Streaming recognition over text control frames plus binary PCM audio (self-hosted Fun-ASR, Bailian realtime recognition).
 * Providers differ only in [LiveAsrProtocol]: what is sent to open, what is sent to close, and how server messages are read; connecting, uploading and waiting for the final result all live here.
 */
internal interface LiveAsrProtocol {
    /** Text messages sent first once connected; audio is only sent after [LiveAsrUpdate.Started] arrives. */
    fun opening(): List<String>

    /** Text messages sent after recording ends and the last audio packet goes out; afterwards it waits for [LiveAsrUpdate.Ended]. */
    fun closing(): List<String>

    /** Minimum milliseconds between two audio packets; 0 means send as fast as it arrives (audio buffered before connecting catches up as quickly as possible). */
    val packetGapMs: Long get() = 0

    /** Reads one server message (binary messages are read as UTF-8 text). Throws [LiveAsrError] when the server reports an error. */
    fun read(message: String): LiveAsrUpdate
}

internal sealed interface LiveAsrUpdate {
    /** The server is ready to receive audio. */
    data object Started : LiveAsrUpdate

    /** The full text so far: [stable] are the finalized sentences, [tail] is the sentence still changing. Each update replaces the whole text; it is not an increment. */
    data class Text(val stable: String, val tail: String) : LiveAsrUpdate

    /** This recognition has ended; when [text] is null, the full text of the last [Text] is used. */
    data class Ended(val text: String? = null) : LiveAsrUpdate

    /** Heartbeats, acknowledgements and the like; does not change the text. */
    data object None : LiveAsrUpdate
}

/** Error reported by the server: carries only the error code, not the server's own wording (which may echo credentials or what was said). [auth] covers authentication, quota and the like; the UI points to settings. */
internal class LiveAsrError(val code: String, val auth: Boolean = false) : IOException("ASR error $code")

internal interface LiveAsrSocket {
    /** Server messages; closed when the connection drops (carrying the reason on abnormal disconnects). */
    val incoming: ReceiveChannel<String>
    fun sendText(text: String)
    fun sendAudio(pcm: ByteArray)
    fun close()
}

/** Standalone OkHttp WebSocket: does not follow redirects (credentials go only to the address that was entered) and reading has no timeout (results can take a long time while speaking). */
internal class LiveAsrTransport(private val client: OkHttpClient = http) {
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap()): LiveAsrSocket {
        val ready = CompletableDeferred<Unit>()
        val messages = Channel<String>(64)
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) { offer(webSocket, text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { offer(webSocket, bytes.utf8()) }
            private fun offer(webSocket: WebSocket, text: String) {
                if (text.length > MAX_MESSAGE || messages.trySend(text).isFailure) {
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
        return object : LiveAsrSocket {
            override val incoming = messages
            override fun sendText(text: String) { if (!socket.send(text)) throw IOException("ASR send failed") }
            override fun sendAudio(pcm: ByteArray) {
                if (socket.queueSize() > MAX_QUEUE || !socket.send(pcm.toByteString())) throw IOException("ASR send failed")
            }
            override fun close() { socket.cancel(); messages.cancel() }
        }
    }

    companion object {
        private const val MAX_MESSAGE = 1024 * 1024
        private const val MAX_QUEUE = 256 * 1024L
        private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()
    }
}

/**
 * Tells the service which language is spoken during live transcription; [Auto] specifies nothing and lets the service decide. It stores [code] (also the language code Bailian `language_hints` uses),
 * which may only be appended to. The self-hosted Fun-ASR service takes its prompt written with the Chinese language name (e.g. "transcribe the speech into Chinese"), hence the separate [funAsrName].
 * Whether a service can recognize a language depends on the model it runs (Fun-ASR-Nano covers only Chinese, English and Japanese; multilingual versions cover more).
 */
enum class AsrLanguage(val code: String, val funAsrName: String) {
    Auto("", ""),
    Chinese("zh", "中文"),
    English("en", "英文"),
    Japanese("ja", "日文"),
    Cantonese("yue", "粤语"),
    Korean("ko", "韩文"),
    ;

    companion object {
        /** Anything unreadable or unrecognized falls back to [Auto] so the whole selection does not become invalid. */
        fun of(code: String?): AsrLanguage = entries.firstOrNull { it.code == code } ?: Auto
    }
}

/** Joins text: a space is inserted when the characters on both sides are Latin letters or digits; Chinese joins directly. */
internal fun joinAsr(parts: List<String>): String = parts.filter(String::isNotEmpty).fold("") { acc, s ->
    if (acc.isNotEmpty() && acc.last().isLetterOrDigit() && acc.last().code < 128 && s.first().isLetterOrDigit() && s.first().code < 128) "$acc $s" else acc + s
}

/** Little-endian PCM16. */
internal fun pcmBytes(pcm: ShortArray, size: Int = pcm.size): ByteArray {
    val data = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until size) data.putShort(pcm[i])
    return data.array()
}

/**
 * One hold-to-talk session: recording, uploading and receiving results all run at once; after release the remaining audio and the closing messages are sent, and Final is emitted only once the server says it ended.
 * Disconnects and timeouts count as failures; unfinished intermediate text is never handed out as the final text (handed-out text is sent on to the model).
 * Holding is unlimited ([unlimited]): only connecting, waiting for the start and waiting for the final result have time limits; the session as a whole has no cap.
 */
internal class LiveSpeech(
    private val recorder: StreamingRecorder,
    private val protocol: LiveAsrProtocol,
    private val connect: suspend () -> LiveAsrSocket,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 10_000,
    private val packetSamples: Int = PACKET_SAMPLES,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    // Upload while speaking and never buffer the whole recording: hold for as long as you like
    override val unlimited = true
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

    private suspend fun runSession() {
        var socket: LiveAsrSocket? = null
        try {
            run {
                val connection = withTimeout(connectTimeoutMs) { connect() }
                socket = connection
                protocol.opening().forEach(connection::sendText)
                var text = LiveAsrUpdate.Text("", "")
                // Send no audio before the server confirms; audio recorded meanwhile stays in the recorder's queue
                withTimeout(connectTimeoutMs) {
                    while (true) {
                        when (val u = protocol.read(connection.incoming.receive())) {
                            LiveAsrUpdate.Started -> break
                            is LiveAsrUpdate.Text -> text = u
                            is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                            LiveAsrUpdate.None -> Unit
                        }
                    }
                }
                coroutineScope {
                    val result = async {
                        for (message in connection.incoming) {
                            when (val u = protocol.read(message)) {
                                is LiveAsrUpdate.Text -> if (u != text) {
                                    text = u
                                    if (u.stable.isNotEmpty() || u.tail.isNotEmpty()) out.send(SpeechEvent.Partial(u.stable, u.tail))
                                }
                                is LiveAsrUpdate.Ended -> return@async (u.text ?: joinAsr(listOf(text.stable, text.tail))).trim()
                                LiveAsrUpdate.Started, LiveAsrUpdate.None -> Unit
                            }
                        }
                        throw IOException("ASR closed before final result")
                    }
                    val audioEnded = CompletableDeferred<Unit>()
                    val sender = launch {
                        val buffer = ShortArray(packetSamples)
                        var size = 0
                        var next = 0L
                        suspend fun send(count: Int) {
                            val wait = next - System.nanoTime()
                            if (wait > 0) delay((wait + 999_999) / 1_000_000)
                            connection.sendAudio(pcmBytes(buffer, count))
                            next = System.nanoTime() + protocol.packetGapMs * 1_000_000
                        }
                        for (chunk in recorder.chunks) {
                            var offset = 0
                            while (offset < chunk.size) {
                                val count = minOf(buffer.size - size, chunk.size - offset)
                                chunk.copyInto(buffer, size, offset, offset + count)
                                size += count; offset += count
                                if (size == buffer.size) { send(size); size = 0 }
                            }
                        }
                        if (size > 0) send(size)
                        protocol.closing().forEach(connection::sendText)
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
        } catch (e: TimeoutCancellationException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: LiveAsrError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(if (e.auth) ModelError.Auth() else ModelError.BadResponse("ASR error ${e.code}"))))
        } catch (e: ModelError) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(e)))
        } catch (e: IOException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (_: Exception) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse("Invalid ASR response"))))
        } finally {
            socket?.close()
            levels?.cancel()
            recorder.cancel()
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

    companion object {
        /** One packet per 100 ms (16 kHz mono). */
        const val PACKET_SAMPLES = 1600
    }
}

/**
 * Opening only, without speaking: it counts as reachable when the connection succeeds and the server replies with "started" (address, authentication and model all accepted); the closing messages are then sent and the socket closed.
 * Used before saving on the settings page; sends no audio and consumes no recognition time; does not wait for the server's closing reply to an empty recognition (services differ on how they close a recognition with no audio).
 * Throws [LiveAsrError], [ModelError] or IOException on failure.
 */
internal suspend fun probeLiveAsr(protocol: LiveAsrProtocol, connect: suspend () -> LiveAsrSocket, timeoutMs: Long = 8_000) {
    withTimeout(timeoutMs) {
        val socket = connect()
        try {
            protocol.opening().forEach(socket::sendText)
            for (message in socket.incoming) {
                when (protocol.read(message)) {
                    LiveAsrUpdate.Started -> {
                        runCatching { protocol.closing().forEach(socket::sendText) }
                        return@withTimeout
                    }
                    is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                    else -> Unit
                }
            }
            throw IOException("ASR closed during probe")
        } finally { socket.close() }
    }
}
