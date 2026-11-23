package com.wangzan101.mika.telegram

import android.util.Log
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Telegram only adapts sending and receiving; owner checks, question routing and task execution live in the shared channel layer. */
class TelegramChannel(
    private val store: TelegramStore,
    private val api: TelegramApi,
    private val activeAsk: (String) -> Delivery.Ask? = { null },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Channel {
    override val id = ID
    override val confirms = false
    override val supportsImages = false
    @Volatile private var sink: ChannelInputSink? = null
    private val pollLock = Mutex()

    override fun isAlive(peer: ChannelPeer): Boolean {
        return store.accepts(peer)
    }

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    override fun acknowledge(messageId: String) = store.acknowledge(messageId)

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        if (peer.channelId != id || !isAlive(peer)) return SendOutcome.Failed
        if (delivery is Delivery.Image) return SendOutcome.Failed
        val token = store.token() ?: return SendOutcome.Failed
        val text = when (delivery) {
            is Delivery.Ask -> delivery.text
            is Delivery.Result -> delivery.text
            is Delivery.Notice -> delivery.text
            is Delivery.Progress -> delivery.text
            is Delivery.Image -> error("handled above")
        }
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val outcome = try {
                // Re-check between queueing and the real API call: a pause or
                // reconfiguration in between must not send the old message.
                if (!store.accepts(peer)) return@withContext SendOutcome.Failed
                api.sendMessage(token, peer.peerId, text)
                SendOutcome.Delivered
            } catch (e: CancellationException) {
                throw e
            } catch (_: TelegramApiException) {
                SendOutcome.Failed
            } catch (_: IOException) {
                SendOutcome.Uncertain
            }
            log("${delivery::class.simpleName} send $outcome in ${(System.nanoTime() - started) / 1_000_000} ms")
            outcome
        }
    }

    /** First re-offer unacknowledged messages from last time, then fetch new updates. */
    suspend fun pollOnce() = pollLock.withLock {
        val input = sink ?: return
        val token = store.token()?.takeIf { store.enabled() } ?: return
        val chatId = store.chatId().takeIf { it.isNotBlank() } ?: return
        store.pending().forEach { pending ->
            if (!current(token, input) || !store.accepts(pending.peer)) return
            dispatchPending(pending, input)
        }
        val updates = api.getUpdates(token, store.offset())
        // A reconfiguration or stop while polling must not journal or advance the new setup's offset.
        if (store.token() != token || sink !== input || !store.enabled()) return
        var nextOffset = store.offset()
        for (update in updates) {
            nextOffset = maxOf(nextOffset, update.updateId + 1)
            val message = update.message
            if (!acceptsMessage(message, chatId)) continue
            val text = message?.text.orEmpty()
            if (text.isBlank()) continue
            val messageId = update.updateId.toString()
            if (store.hasMessage(messageId)) continue
            if (store.token() != token || sink !== input || !store.enabled()) return
            val peer = store.ownerPeer(token) ?: return
            val askId = activeInputAskId(peer.threadId)
            store.journal(messageId, peer, text, askId, expectedToken = token)
            if (!current(token, input)) return
            store.pending().firstOrNull { it.messageId == messageId }?.let { dispatchPending(it, input) }
        }
        if (nextOffset > store.offset() && current(token, input)) store.saveOffset(nextOffset, token)
    }

    /**
     * Only the configured private chat is accepted: the update must come from
     * the configured chat, and in a private chat the sender id equals the chat
     * id. Group and channel messages are never tasks.
     */
    private fun acceptsMessage(message: TelegramMessage?, chatId: String): Boolean {
        if (message == null) return false
        val chat = message.chat ?: return false
        if (chat.id.toString() != chatId) return false
        return message.from?.id == chat.id
    }

    private fun current(token: String, input: ChannelInputSink) =
        store.token() == token && sink === input && store.enabled()

    private fun activeInputAskId(threadId: String): String? =
        activeAsk(threadId)?.takeIf { it.kind == Delivery.Ask.Kind.Input }?.askId

    private suspend fun dispatchPending(pending: PendingTelegramMessage, input: ChannelInputSink) {
        if (!store.accepts(pending.peer)) return
        input.receive(ChannelInput.Text(pending.peer, pending.messageId, pending.text, pending.replyToAskId))
    }

    companion object {
        const val ID = "telegram"
        private const val TAG = "MikaTelegram"
    }
}
