package com.wangzan101.mika.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A bot setup read from a scanned QR code.
 *
 * Accepted code contents (whitespace trimmed):
 * - a bare bot token from @BotFather, e.g. `123456:ABC-DEF...`
 * - `token|chatId` (or token on one line, chat ID on the next)
 * - JSON `{"token": "...", "chat_id": "..."}` (also accepts `chatId`)
 *
 * A token is `<bot id>:<35-char secret>`. A chat ID is a plain integer.
 * When the token is valid but the chat ID part is malformed, the chat ID is
 * dropped and the token alone is returned.
 */
data class TelegramQrSetup(val token: String, val chatId: String?)

@Serializable
private data class TelegramQrJson(
    val token: String? = null,
    @SerialName("chat_id") val chatIdSnake: String? = null,
    val chatId: String? = null,
)

private val tokenPattern = Regex("""^\d+:[A-Za-z0-9_-]{35}$""")
private val chatIdPattern = Regex("""^-?\d+$""")

private val qrJson = Json { ignoreUnknownKeys = true }

fun parseTelegramQr(content: String): TelegramQrSetup? {
    val text = content.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("{")) {
        return runCatching {
            val parsed = qrJson.decodeFromString<TelegramQrJson>(text)
            val token = parsed.token?.trim().orEmpty()
            if (!tokenPattern.matches(token)) return null
            val chatId = (parsed.chatIdSnake ?: parsed.chatId)?.trim()
                ?.takeIf { chatIdPattern.matches(it) }
            TelegramQrSetup(token, chatId)
        }.getOrNull()
    }
    val parts = text.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val token = parts.firstOrNull().orEmpty()
    if (!tokenPattern.matches(token)) return null
    val chatId = parts.getOrNull(1)?.takeIf { chatIdPattern.matches(it) }
    return TelegramQrSetup(token, chatId)
}
