package com.wangzan101.mika.core.model

import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayOutputStream
import java.util.Base64

/** A model that turns text into speech. Separate from chat models: no context, tool calls or chain of thought. */
interface TtsModel {
    /** The model name, used only for records. */
    val name: String

    val capabilities: TtsCapabilities

    /** Throws [ModelError] or [TtsError] on failure. */
    suspend fun synthesize(request: TtsRequest): TtsResponse
}

/** A speech model that can deliver audio in chunks. [synthesize] can still be used to save complete audio. */
interface StreamingTtsModel : TtsModel {
    /** The request is issued on collection. Each synthesis delivers PCM chunks or one complete audio file; cancelling collection releases the connection. */
    fun streamAudio(request: TtsRequest): Flow<TtsAudioPart>
}

sealed interface TtsAudioPart {
    /** A complete interleaved PCM16LE frame. Sample rate and channel count are fixed within one synthesis. */
    data class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int) : TtsAudioPart

    data class Complete(val response: TtsResponse) : TtsAudioPart
}

/**
 * One read-aloud request. An empty [voice] uses the default voice from the model or settings; an empty [speed] uses the provider
 * default; [format] is the desired output format (wav/mp3/opus/aac/flac), which providers may not all support.
 */
data class TtsRequest(
    val text: String,
    val voice: String? = null,
    val speed: Float? = null,
    val format: String = "wav",
)

/** The synthesized audio. Raw PCM has already been wrapped into WAV by the adapter; [sampleRate] is null when it cannot be read. */
class TtsResponse(val bytes: ByteArray, val mime: String, val sampleRate: Int? = null, val durationMs: Long? = null)

/** A piece of audio already written into the workspace. */
class StoredAudio(val path: String, val bytes: Int, val mime: String, val durationMs: Long? = null)

/** A piece of audio already saved into the music library. */
class SavedAudio(val savedAs: String, val uri: String, val bytes: Long)

/**
 * What a speech model can do. A null [voices] means unrestricted/unknown (voice names are validated by the provider);
 * [maxChars] is the per-request text limit; [formats] are the container formats it can output.
 */
data class TtsCapabilities(
    val voices: Set<String>? = null,
    val maxChars: Int = 4000,
    val formats: Set<String> = setOf("wav", "mp3"),
)

/** A speech model's own limits: invalid request, response is not audio, audio too large. */
sealed class TtsError(message: String) : Exception(message) {
    class Unsupported(message: String) : TtsError(message)
    class BadAudio(message: String) : TtsError(message)
    class TooLarge(val bytes: Long) : TtsError("audio is too large")
}

/** Maximum number of bytes of generated audio; anything larger is not handed to the player/workspace. */
internal const val MAX_TTS_BYTES = 20 * 1024 * 1024

/** Recognizes the audio container from the file header; null if unrecognized. Only the few containers TTS commonly uses are recognized. */
internal fun sniffAudioMime(bytes: ByteArray): String? = when {
    bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE" -> "audio/wav"
    bytes.size >= 3 && ascii(bytes, 0, 3) == "ID3" -> "audio/mpeg"
    bytes.size >= 2 && u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xF6) == 0xF0 -> "audio/aac"
    bytes.size >= 2 && u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xE0) == 0xE0 -> "audio/mpeg"
    bytes.size >= 4 && ascii(bytes, 0, 4) == "OggS" -> "audio/ogg"
    bytes.size >= 4 && ascii(bytes, 0, 4) == "fLaC" -> "audio/flac"
    else -> null
}

internal fun audioMimeOrBad(bytes: ByteArray, declared: String?): String {
    sniffAudioMime(bytes)?.let { return it }
    if (bytes.size >= 4 && ascii(bytes, 0, 4) == "RIFF") {
        throw TtsError.BadAudio("response is not a complete audio file")
    }
    return declared?.substringBefore(';')?.takeIf { it.startsWith("audio/") }
        ?: throw TtsError.BadAudio("response is not a known audio format")
}

/** Once RIFF/WAVE has been fully received, write back the unknown length of the streaming response so Android can play it as a file. */
internal fun finalizeStreamingWavHeader(bytes: ByteArray) {
    if (bytes.size < 12 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return
    fun sizeAt(offset: Int): Long =
        (0..3).fold(0L) { size, i -> size or ((bytes[offset + i].toLong() and 0xff) shl (8 * i)) }
    fun putSize(offset: Int, size: Int) {
        for (i in 0..3) bytes[offset + i] = (size ushr (8 * i)).toByte()
    }

    var offset = 12
    while (offset <= bytes.size - 8) {
        val length = sizeAt(offset + 4)
        val start = offset + 8
        if (ascii(bytes, offset, 4) == "data") {
            if (length == 0xffff_ffffL) putSize(offset + 4, bytes.size - start)
            if (sizeAt(4) == 0xffff_ffffL) putSize(4, bytes.size - 8)
            return
        }
        val next = start + length + (length and 1L)
        if (next > bytes.size) return
        offset = next.toInt()
    }
}

/** Wraps raw 16-bit mono PCM into WAV; Gemini TTS commonly returns this. */
internal fun wrapPcm16Wav(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
    val bits = 16
    val block = channels * bits / 8
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun le32(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF) }
    fun le16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    out.write("RIFF".toByteArray(Charsets.ISO_8859_1)); le32(36 + pcm.size); out.write("WAVE".toByteArray(Charsets.ISO_8859_1))
    out.write("fmt ".toByteArray(Charsets.ISO_8859_1)); le32(16); le16(1); le16(channels); le32(sampleRate)
    le32(sampleRate * block); le16(block); le16(bits)
    out.write("data".toByteArray(Charsets.ISO_8859_1)); le32(pcm.size); out.write(pcm)
    return out.toByteArray()
}

/** Gemini inlineData mime often carries `rate=24000`; 24000 is used when it cannot be read. */
internal fun pcmRateOf(mime: String?): Int = mime?.let { Regex("rate=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    ?.takeIf { it in 8_000..192_000 } ?: 24_000

internal fun decodeBase64Audio(data: String, mime: String?): ByteArray = try {
    Base64.getDecoder().decode(data)
} catch (e: IllegalArgumentException) {
    throw TtsError.BadAudio("response is not valid base64")
}

internal fun checkTtsSize(bytes: ByteArray) {
    if (bytes.size > MAX_TTS_BYTES) throw TtsError.TooLarge(bytes.size.toLong())
}

private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
private fun ascii(b: ByteArray, at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)
