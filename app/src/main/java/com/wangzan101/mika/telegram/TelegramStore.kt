package com.wangzan101.mika.telegram

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class PendingTelegramMessage(
    val messageId: String,
    val peer: ChannelPeer,
    val text: String,
    val replyToAskId: String? = null,
)

@Serializable
private data class TelegramState(
    val enabled: Boolean = false,
    val chatId: String = "",
    val offset: Long = 0,
    val threads: Map<String, String> = emptyMap(),
    val pending: List<PendingTelegramMessage> = emptyList(),
    /** Keep the most recent acknowledgements so a replayed update is never registered twice. */
    val acknowledgedMessages: List<String> = emptyList(),
    /**
     * The "task registered" notice is sent only for the very first channel
     * task after setup; a fresh state (new setup, reconfiguration, removal)
     * starts with it pending again. Missing in state.json files written
     * before 1.3.2, so those also ack once more, then never again.
     */
    val firstTaskAckPending: Boolean = true,
)

/**
 * Bot token and run state are stored separately: the token is encrypted,
 * the chat id, poll offset and unhanded messages are written atomically.
 * The token is never written to any file in plaintext and never logged.
 */
class TelegramStore(
    private val dir: File,
    private val cipher: KeyCipher,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tokenFile = File(dir, "token.enc")
    private val stateFile = File(dir, "state.json")
    @Volatile private var token: String? = readToken()
    private var state = readState()

    @Synchronized fun hasToken() = token != null

    @Synchronized fun token(): String? = token

    @Synchronized fun chatId() = state.chatId

    /** A bot is usable once a token and a target chat id are both saved. */
    @Synchronized fun configured() = token != null && state.chatId.isNotBlank()

    /**
     * The one-time "task registered" notice: true only for the first channel
     * task after setup, then consumed (persisted) so later tasks get no
     * notice. A reconfiguration ([saveBot] with a different token or chat
     * id, [setChatId]) or a removal ([clear]) replaces the state, so the
     * next setup acks once again; re-saving the same setup does not reset it.
     */
    @Synchronized fun consumeFirstTaskAck(): Boolean {
        if (!state.firstTaskAckPending) return false
        val next = state.copy(firstTaskAckPending = false)
        saveState(next)
        state = next
        return true
    }

    /**
     * Save the bot credentials. A changed token or chat id invalidates the
     * poll offset, threads and pending messages of the previous setup, so a
     * reconfigured bot never replays or answers another chat's messages.
     */
    @Synchronized fun saveBot(newToken: String, newChatId: String) {
        require(newToken.isNotBlank()) { "Bot token must not be blank" }
        require(newChatId.isNotBlank()) { "Chat ID must not be blank" }
        if (newToken != token || newChatId != state.chatId) {
            state = TelegramState(enabled = state.enabled, chatId = newChatId)
            saveState()
        }
        writeAtomic(tokenFile, cipher.encrypt(newToken.toByteArray(Charsets.UTF_8)))
        token = newToken
    }

    @Synchronized fun setChatId(value: String) {
        if (value == state.chatId) return
        val next = TelegramState(enabled = state.enabled, chatId = value)
        saveState(next)
        state = next
    }

    @Synchronized fun clear() {
        token = null
        state = TelegramState()
        tokenFile.delete()
        saveState()
    }

    @Synchronized fun enabled() = state.enabled

    @Synchronized fun setEnabled(value: Boolean) {
        val next = state.copy(enabled = value)
        saveState(next)
        state = next
    }

    @Synchronized fun offset() = state.offset

    @Synchronized fun saveOffset(value: Long, expectedToken: String? = null) {
        if (expectedToken != null && (token != expectedToken || !state.enabled)) return
        if (value <= state.offset) return
        val next = state.copy(offset = value)
        saveState(next)
        state = next
    }

    @Synchronized fun accepts(peer: ChannelPeer): Boolean {
        if (token == null || !state.enabled || state.chatId.isBlank()) return false
        return peer.channelId == TelegramChannel.ID && peer.peerId == state.chatId &&
            state.threads[peer.peerId] == peer.threadId
    }

    @Synchronized fun peer(chatId: String): ChannelPeer {
        val thread = state.threads[chatId] ?: "telegram-${UUID.randomUUID()}".also {
            val next = state.copy(threads = state.threads + (chatId to it))
            saveState(next)
            state = next
        }
        return ChannelPeer(TelegramChannel.ID, chatId, thread)
    }

    /** Resolve the configured private chat and refresh its thread under the same check. */
    @Synchronized fun ownerPeer(expectedToken: String? = null): ChannelPeer? {
        val current = token ?: return null
        if (expectedToken != null && expectedToken != current) return null
        if (!state.enabled || state.chatId.isBlank()) return null
        return peer(state.chatId)
    }

    /**
     * Owner-result delivery target: enabled, token and chat id present, and a
     * thread already exists. Missing any one means there is no outlet, and no
     * thread is created just to have one.
     */
    @Synchronized fun boundOwnerPeer(): ChannelPeer? {
        if (token == null || !state.enabled || state.chatId.isBlank()) return null
        val thread = state.threads[state.chatId] ?: return null
        return ChannelPeer(TelegramChannel.ID, state.chatId, thread)
    }

    @Synchronized fun hasMessage(messageId: String) =
        messageId in state.acknowledgedMessages || state.pending.any { it.messageId == messageId }

    /** The same update is only registered once; unacknowledged ones are re-offered on next start. */
    @Synchronized fun journal(messageId: String, peer: ChannelPeer, text: String,
        replyToAskId: String? = null, expectedToken: String? = null): Boolean {
        if (expectedToken != null && (token != expectedToken || !accepts(peer))) return false
        if (hasMessage(messageId)) return false
        val next = state.copy(pending = state.pending +
            PendingTelegramMessage(messageId, peer, text, replyToAskId))
        saveState(next)
        state = next
        return true
    }

    @Synchronized fun pending(): List<PendingTelegramMessage> = state.pending

    @Synchronized fun acknowledge(messageId: String, expectedToken: String? = null) {
        if (expectedToken != null && token != expectedToken) return
        if (messageId in state.acknowledgedMessages && state.pending.none { it.messageId == messageId }) return
        val next = state.copy(
            pending = state.pending.filterNot { it.messageId == messageId },
            acknowledgedMessages = (state.acknowledgedMessages.filterNot { it == messageId } + messageId)
                .takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        saveState(next)
        state = next
    }

    private fun readState(): TelegramState = if (stateFile.isFile) runCatching {
        json.decodeFromString(TelegramState.serializer(), stateFile.readText())
    }.getOrElse { TelegramState() } else TelegramState()

    private fun readToken(): String? = if (tokenFile.isFile) runCatching {
        cipher.decrypt(tokenFile.readBytes()).decodeToString()
    }.getOrNull() else null

    private fun saveState(value: TelegramState = state) =
        writeAtomic(stateFile, json.encodeToString(TelegramState.serializer(), value).toByteArray())

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        internal const val MAX_ACKNOWLEDGED_MESSAGES = 512
    }
}
