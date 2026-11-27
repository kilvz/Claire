package com.wangzan101.mika.telegram

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Telegram Bot API error: [httpCode] is 401/403 for an invalid or revoked token. */
class TelegramApiException(message: String, val httpCode: Int = 0) : IOException(message)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
)

@Serializable
private data class SendMessageResponse(
    val ok: Boolean = false,
    val description: String? = null,
    val result: SentMessage? = null,
)

@Serializable
private data class SendChatActionRequest(
    @SerialName("chat_id") val chatId: String,
    val action: String,
)

@Serializable
private data class SendChatActionResponse(
    val ok: Boolean = false,
    val description: String? = null,
    val result: Boolean = false,
)

@Serializable
private data class SentMessage(@SerialName("message_id") val messageId: Long = 0)

@Serializable
data class TelegramUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TelegramMessage? = null,
)

@Serializable
data class TelegramMessage(
    @SerialName("message_id") val messageId: Long = 0,
    val from: TelegramUser? = null,
    val chat: TelegramChat? = null,
    val text: String? = null,
)

@Serializable
data class TelegramUser(val id: Long = 0, val username: String? = null)

@Serializable
data class TelegramChat(val id: Long = 0, val type: String? = null)

@Serializable
private data class GetUpdatesResponse(
    val ok: Boolean = false,
    val description: String? = null,
    val result: List<TelegramUpdate> = emptyList(),
)

@Serializable
private data class GetMeResponse(
    val ok: Boolean = false,
    val description: String? = null,
    val result: TelegramUser? = null,
)

/**
 * Minimal Telegram Bot API client: sendMessage and getUpdates long polling.
 * The bot token travels in the request path, as the Bot API requires; it is
 * never logged and never written to any file. Only outcomes are logged.
 */
class TelegramApi(private val http: OkHttpClient, private val baseUrl: String = BASE_URL) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonBody = "application/json; charset=utf-8".toMediaType()

    /** Sends [text] to [chatId]; returns the sent message id. Throws on any failure. */
    suspend fun sendMessage(token: String, chatId: String, text: String): Long {
        val payload = json.encodeToString(SendMessageRequest.serializer(), SendMessageRequest(chatId, text))
            .toRequestBody(jsonBody)
        val request = Request.Builder().url("$baseUrl/bot$token/sendMessage").post(payload).build()
        val response = executeCancellable(request, SendMessageResponse.serializer())
        if (!response.ok || response.result == null) {
            throw TelegramApiException(response.description ?: "Telegram sendMessage failed")
        }
        return response.result.messageId
    }

    /**
     * Shows a chat action (e.g. "typing") for about 5 seconds; call again
     * to keep it visible. Throws on any failure; callers that only want a
     * best-effort indicator must swallow the failure.
     */
    suspend fun sendChatAction(token: String, chatId: String, action: String = "typing") {
        val payload = json.encodeToString(SendChatActionRequest.serializer(), SendChatActionRequest(chatId, action))
            .toRequestBody(jsonBody)
        val request = Request.Builder().url("$baseUrl/bot$token/sendChatAction").post(payload).build()
        val response = executeCancellable(request, SendChatActionResponse.serializer())
        if (!response.ok) {
            throw TelegramApiException(response.description ?: "Telegram sendChatAction failed")
        }
    }

    /** Long-polls for updates after [offset]; returns updates in order. Throws on any failure. */
    suspend fun getUpdates(token: String, offset: Long, timeoutSeconds: Int = LONG_POLL_SECONDS): List<TelegramUpdate> {
        val url = "$baseUrl/bot$token/getUpdates".toHttpUrl().newBuilder()
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("timeout", timeoutSeconds.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        val response = executeCancellable(request, GetUpdatesResponse.serializer())
        if (!response.ok) throw TelegramApiException(response.description ?: "Telegram getUpdates failed")
        return response.result
    }

    /**
     * Returns the bot's @username from getMe, or null when Telegram returns
     * none. Throws on any failure, including an invalid token (401).
     */
    suspend fun getMe(token: String): String? {
        val request = Request.Builder().url("$baseUrl/bot$token/getMe").get().build()
        val response = executeCancellable(request, GetMeResponse.serializer())
        if (!response.ok) throw TelegramApiException(response.description ?: "Telegram getMe failed")
        return response.result?.username?.takeIf { it.isNotBlank() }
    }

    private suspend fun <T> executeCancellable(request: Request, serializer: kotlinx.serialization.KSerializer<T>): T {
        val call = http.newCall(request)
        val (code, body) = suspendCancellableCoroutine<Pair<Int, String?>> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // Read the body before resuming: the response is closed when this block returns.
                    response.use {
                        val text = runCatching { it.body?.string() }.getOrNull()
                        if (cont.isActive) cont.resume(it.code to text)
                    }
                }
            })
        }
        if (code !in 200..299) throw TelegramApiException("Telegram HTTP $code", code)
        val text = body ?: throw TelegramApiException("Empty Telegram response")
        return json.decodeFromString(serializer, text)
    }

    companion object {
        const val BASE_URL = "https://api.telegram.org"
        const val LONG_POLL_SECONDS = 30
    }
}
