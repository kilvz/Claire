package com.wangzan101.mika.telegram

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Outbox
import java.security.MessageDigest

data class TelegramOwnerResult(val status: Status, val message: String? = null) {
    enum class Status { Delivered, Uncertain, Failed, Unavailable }
}

/**
 * Owner-result delivery outlet: snapshots the token and delivery target at
 * construction and re-reads them at send time — a reconfiguration, disable or
 * thread change mid-run sends nothing at all. Delivery goes through [Outbox]:
 * the same run and text is only sent once, and an uncertain outcome is never
 * resent automatically.
 *
 * Not yet bound to any agent tool: unlike WeChat there is no core
 * TelegramOwnerPort yet, so no task can call this today. It is ready for a
 * future "Send to my Telegram" tool binding.
 */
class AndroidTelegramOwnerPort internal constructor(
    private val store: TelegramStore,
    private val channel: Channel,
    private val outbox: Outbox,
    private val runKey: String,
) {
    constructor(app: MikaApp, runKey: String) : this(app.telegram.store, app.telegram.channel, app.outbox, runKey)

    private val expectedToken: String? = store.token()
    private val expectedPeer = store.boundOwnerPeer()

    suspend fun send(text: String): TelegramOwnerResult {
        val token = expectedToken
        val peer = expectedPeer
        if (token == null || peer == null) {
            return TelegramOwnerResult(TelegramOwnerResult.Status.Unavailable,
                "No Telegram bot is configured for the owner; nothing was sent.")
        }
        if (store.token() != token || store.boundOwnerPeer() != peer) {
            return TelegramOwnerResult(TelegramOwnerResult.Status.Unavailable,
                "The Telegram setup changed or was paused during this run; nothing was sent.")
        }
        val id = "owner-send:$runKey:${sha256(peer.threadId + text)}"
        val record = outbox.deliver(channel, peer, Delivery.Result(id, peer.threadId, text))
        return when (record.state) {
            DeliveryState.Delivered -> TelegramOwnerResult(TelegramOwnerResult.Status.Delivered)
            DeliveryState.Uncertain -> TelegramOwnerResult(TelegramOwnerResult.Status.Uncertain,
                "The message was handed to the channel but delivery was not confirmed; it was not resent.")
            else -> TelegramOwnerResult(TelegramOwnerResult.Status.Failed,
                "The Telegram channel did not accept the message.")
        }
    }

    private companion object {
        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
    }
}
