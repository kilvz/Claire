package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streaming recognition over text control frames plus binary PCM audio (self-hosted Fun-ASR, Bailian realtime recognition).
 * Providers differ only in [LiveAsrProtocol]: what is sent to open, what is sent to close, and how server messages are read; connecting, uploading and waiting for the final result live in [StreamingAsrSession].
 */
internal interface LiveAsrProtocol {
    /** The text messages sent first after connecting; audio starts only once [LiveAsrUpdate.Started] arrives. */
    fun opening(): List<String>

    /** The text messages sent after recording ends and the last audio packet is out; [LiveAsrUpdate.Ended] follows. */
    fun closing(): List<String>

    /** Minimum milliseconds between two audio packets; 0 means send as fast as they arrive (audio buffered before connecting catches up quickly). */
    val packetGapMs: Long get() = 0

    /** Reads one server message (binary messages are read as UTF-8 text). Throws [LiveAsrError] when the server reports an error. */
    fun read(message: String): LiveAsrUpdate
}

internal sealed interface LiveAsrUpdate {
    /** The server is ready to receive audio. */
    data object Started : LiveAsrUpdate

    /** The transcript so far: [stable] is the settled text, [tail] is the sentence still changing. Each update replaces the whole thing, it is not a delta. */
    data class Text(val stable: String, val tail: String) : LiveAsrUpdate

    /** This recognition is over; when [text] is null, the last [Text] transcript is used. */
    data class Ended(val text: String? = null) : LiveAsrUpdate

    /** Heartbeats, acknowledgements and the like; does not change the text. */
    data object None : LiveAsrUpdate
}

/** An error reported by the server: it carries only the error code, not the server text (which may echo credentials or what was said). [auth] covers authentication and quota errors, and the UI points to settings. */
internal class LiveAsrError(val code: String, val auth: Boolean = false) : IOException("ASR error $code")

internal interface LiveAsrSocket {
    /** Server messages; closed when the connection drops (with the reason on an abnormal drop). */
    val incoming: ReceiveChannel<String>
    fun sendText(text: String)
    fun sendAudio(pcm: ByteArray)
    fun close()
}

/** The recognition connection over text control frames plus binary audio; binary server messages are read as UTF-8 text. */
internal class LiveAsrTransport(private val client: OkHttpClient = streamingAsrHttp) {
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap()): LiveAsrSocket {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val socket = openAsrWebSocket(client, request, 64, fromText = { it }, fromBytes = { it.utf8() }, size = String::length)
        return object : LiveAsrSocket {
            override val incoming = socket.incoming
            override fun sendText(text: String) = socket.sendText(text)
            override fun sendAudio(pcm: ByteArray) = socket.sendBinary(pcm)
            override fun close() = socket.close()
        }
    }
}

/**
 * Tells the service which language is being spoken during live transcription; [Auto] leaves it to the service. [code] is stored (it is also the language code Bailian `language_hints` uses),
 * and it can only be appended to. The self-hosted Fun-ASR service prompt names languages in Chinese, hence the separate [funAsrName].
 * Whether a language can be recognized depends on the model the service runs (Fun-ASR-Nano has only Chinese, English and Japanese; multilingual builds have more).
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
        /** Anything unreadable or unknown becomes [Auto], so the whole choice does not fail. */
        fun of(code: String?): AsrLanguage = entries.firstOrNull { it.code == code } ?: Auto
    }
}

/** Joins text: a space is inserted when both sides end and start with a Latin letter or digit; Chinese joins directly. */
internal fun joinAsr(parts: List<String>): String = parts.filter(String::isNotEmpty).fold("") { acc, s ->
    if (acc.isNotEmpty() && acc.last().isLetterOrDigit() && acc.last().code < 128 && s.first().isLetterOrDigit() && s.first().code < 128) "$acc $s" else acc + s
}

/** One press-and-hold utterance with live transcription, run by [StreamingAsrSession]; hold as long as you like (audio streams as you speak, no whole-recording buffering). */
internal class LiveSpeech(
    recorder: StreamingRecorder,
    protocol: LiveAsrProtocol,
    connect: suspend () -> LiveAsrSocket,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    connectTimeoutMs: Long = 10_000,
    finalTimeoutMs: Long = 10_000,
    packetSamples: Int = PACKET_SAMPLES,
) : SpeechSession by StreamingAsrSession(
    recorder, LiveAsrStream(protocol, connect, packetSamples), scope, connectTimeoutMs, finalTimeoutMs, unlimited = true,
) {
    companion object {
        /** One packet per 100 ms (16 kHz mono). */
        const val PACKET_SAMPLES = 1600
    }
}

/** Wires [LiveAsrProtocol] into the session: opening and closing send text, audio goes as binary, and results are whole replaced transcripts. */
private class LiveAsrStream(
    private val protocol: LiveAsrProtocol,
    private val connector: suspend () -> LiveAsrSocket,
    override val packetSamples: Int,
) : AsrStream {
    private var socket: LiveAsrSocket? = null
    private var text = LiveAsrUpdate.Text("", "")
    override val packetGapMs get() = protocol.packetGapMs
    override val invalidResponse = "Invalid ASR response"

    override fun serviceError(e: Exception): ModelError? =
        (e as? LiveAsrError)?.let { if (it.auth) ModelError.Auth() else ModelError.BadResponse("ASR error ${it.code}") }

    override suspend fun connect() { socket = connector() }
    override fun open() { protocol.opening().forEach(socket!!::sendText) }

    override suspend fun awaitStart(partial: (String, String) -> Unit): String? {
        while (true) {
            when (val u = protocol.read(socket!!.incoming.receive())) {
                LiveAsrUpdate.Started -> return null
                is LiveAsrUpdate.Text -> text = u
                is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                LiveAsrUpdate.None -> Unit
            }
        }
    }

    override suspend fun awaitFinal(partial: (String, String) -> Unit): String {
        for (message in socket!!.incoming) {
            when (val u = protocol.read(message)) {
                is LiveAsrUpdate.Text -> if (u != text) {
                    text = u
                    if (u.stable.isNotEmpty() || u.tail.isNotEmpty()) partial(u.stable, u.tail)
                }
                is LiveAsrUpdate.Ended -> return (u.text ?: joinAsr(listOf(text.stable, text.tail))).trim()
                LiveAsrUpdate.Started, LiveAsrUpdate.None -> Unit
            }
        }
        throw IOException("ASR closed before final result")
    }

    override fun sendAudio(pcm: ShortArray, count: Int, last: Boolean) = socket!!.sendAudio(Pcm16.toBytes(pcm, count))
    override fun endAudio() { protocol.closing().forEach(socket!!::sendText) }
    override fun close() { socket?.close() }
}

/**
 * Opening only, without speaking: it passes when the connection works and the server returns "started" (address, credentials and model all accepted), then the closing message is sent and it disconnects.
 * Used by the settings page before saving; no audio is sent and no recognition time is used; it does not wait for the server's closing reply to an empty recognition (how vendors close one with no audio varies).
 * Failures throw [LiveAsrError], [ModelError] or IOException.
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
