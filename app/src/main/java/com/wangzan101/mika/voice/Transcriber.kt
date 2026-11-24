package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.headerOrReject
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Turns a recording into text with a provider's model; the interface shapes are in [TranscribeMethod].
 * Errors go through the same mapping as chat models ([ModelHttp]), so the UI can reuse the existing wording.
 * [headers] are headers sent on every request (client identification, etc., see ClientHeaders); the key header is added here per interface type.
 */
class Transcriber(
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Transcribes a piece of audio; throws [ModelError] on failure (the same set as chat models). The returned text may be an empty string (no speech heard).
     * [mime]/[filename] tell the provider the file type; by default the WAV of a recording, while file transcription passes the original file's type.
     */
    suspend fun transcribe(
        p: Provider, key: String?, model: String, method: TranscribeMethod, wav: ByteArray, language: String?,
        mime: String = "audio/wav", filename: String = "audio.wav",
    ): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val gemini = p.kind == ProviderConfig.Kind.Gemini
        val format = formatOf(mime)
        val request = when (method) {
            TranscribeMethod.AudioTranscriptions -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, wav.toRequestBody(mime.toMediaType()))
                    .addFormDataPart("model", model)
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()
                builder("$base/audio/transcriptions", key, gemini).post(body).build()
            }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val data = if (method == TranscribeMethod.ChatInputAudio) base64(wav) else "data:$mime;base64,${base64(wav)}"
                val payload = buildJsonObject {
                    put("model", model)
                    put("stream", false)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") { put("data", data); put("format", format) }
                                })
                                // Transcription-only models do not accept text blocks; a general model without this instruction treats it as a chat reply
                                if (method != TranscribeMethod.ChatInputAudioOnly) {
                                    add(buildJsonObject { put("type", "text"); put("text", INSTRUCTION) })
                                }
                            })
                        })
                    })
                }
                builder("$base/chat/completions", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
            TranscribeMethod.GeminiInline -> {
                val payload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("inline_data") { put("mime_type", mime); put("data", base64(wav)) }
                                })
                                add(buildJsonObject { put("text", INSTRUCTION) })
                            })
                        })
                    })
                }
                // Same as the chat adapter: the stored URL has no /v1beta, so add it here
                builder("$base/v1beta/models/$model:generateContent", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
        }
        val reply = ModelHttp.send(http, request)
        if (!reply.ok) {
            throw if (gemini) ModelHttp.geminiError(reply.status, reply.retryAfter, reply.body, model)
            else ModelHttp.openAiError(reply.status, reply.retryAfter, reply.body, model)
        }
        return parse(method, reply.body)
    }

    private fun builder(url: String, key: String?, gemini: Boolean): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> headerOrReject(k, v) }
        if (!key.isNullOrEmpty()) {
            if (gemini) headerOrReject("x-goog-api-key", key) else headerOrReject("Authorization", "Bearer $key")
        }
    }

    private fun parse(method: TranscribeMethod, body: String): String {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("not json: ${body.take(120)}")
        val text = when (method) {
            TranscribeMethod.AudioTranscriptions -> root.str("text")
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                when (val c = msg?.get("content")) {
                    is JsonPrimitive -> c.contentOrNull
                    // The content is an array of blocks: join the text blocks together
                    is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
                    else -> null
                }
            }
            TranscribeMethod.GeminiInline -> {
                val cand = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                // No text blocks at all (e.g. only a finish reason when silent) means empty string; only a missing candidate counts as a malformed response
                if (cand == null) null else parts.orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
            }
        }
        return (text ?: throw ModelError.BadResponse("no text field: ${body.take(120)}")).trim()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    /** OpenAI's input_audio only recognizes a few format names; anything unrecognized is treated as wav. */
    private fun formatOf(mime: String): String = when (mime.substringAfter('/', "").substringBefore(';').lowercase()) {
        "mpeg", "mp3" -> "mp3"
        "mp4", "m4a", "x-m4a" -> "m4a"
        "webm" -> "webm"
        "ogg", "opus" -> "ogg"
        else -> "wav"
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The instruction sent to the model: only the verbatim words, no explanation (the model faces English). */
        private const val INSTRUCTION = "Transcribe this recording word for word. Output only the transcript text."

        /** Whether this provider can do speech-to-text: OpenAI-compatible with a key, Responses and Gemini can; Anthropic and account sign-in cannot. */
        fun supports(p: Provider): Boolean = when (p.kind) {
            ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.Gemini -> p.signIn == null
            else -> false
        }
    }
}
