package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import kotlinx.serialization.Serializable

@Serializable
data class ChannelPeer(val channelId: String, val peerId: String, val threadId: String)

sealed interface Delivery {
    val id: String
    val threadId: String

    data class Ask(
        override val id: String, override val threadId: String, val askId: String,
        val kind: Kind, val text: String,
    ) : Delivery {
        enum class Kind { Input, Confirm, Takeover }
    }

    data class Result(
        override val id: String, override val threadId: String, val text: String,
        val restored: Boolean = false, val title: String = "Claire 任务结果",
    ) : Delivery
    /** A file explicitly exported by this task; hosts resolve the opaque URI, never a model-supplied path. */
    data class Image(
        override val id: String, override val threadId: String,
        val uri: String, val name: String, val size: Long, val sha256: String? = null,
    ) : Delivery
    data class Progress(override val id: String, override val threadId: String, val text: String) : Delivery
    data class Notice(override val id: String, override val threadId: String, val text: String) : Delivery
}

sealed interface ChannelInput {
    val peer: ChannelPeer
    data class Text(
        override val peer: ChannelPeer, val messageId: String, val text: String, val replyToAskId: String? = null,
        val requireMatchingAsk: Boolean = false,
    ) : ChannelInput
    data class Confirm(override val peer: ChannelPeer, val askId: String, val choice: ConfirmChoice) : ChannelInput
    data class Stop(override val peer: ChannelPeer, val runId: RunId) : ChannelInput
}

fun interface ChannelInputSink { suspend fun receive(input: ChannelInput) }

enum class SendOutcome { Delivered, Failed, Uncertain }

/** A channel is responsible for sending and receiving; it does not decide who the owner is or who may approve. */
interface Channel {
    val id: String
    val supportsImages: Boolean get() = false
    /** Can answer payment confirmations in this channel (only payments ask, and approvals are given only on the phone). */
    val confirms: Boolean
    fun isAlive(peer: ChannelPeer): Boolean = true
    fun start(sink: ChannelInputSink): AutoCloseable
    /** Confirms that an inbound message has been persisted as a task; channels without a pending ledger need not implement it. */
    fun acknowledge(messageId: String) = Unit
    suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome
    /** Best-effort "typing..." while a task runs; channels without one ignore the call. */
    suspend fun typing(peer: ChannelPeer) {}
    /**
     * Whether an accepted task from this peer gets the "task registered"
     * notice. Defaults to true; Telegram allows it only for the first task
     * after setup, later tasks get no notice.
     */
    fun shouldAcknowledge(peer: ChannelPeer): Boolean = true
}

/** Pairing does not mean owner; only a bound peer can send messages to a Run. */
interface OwnerIdentity {
    fun isOwner(peer: ChannelPeer): Boolean
}

class MemoryOwnerIdentity : OwnerIdentity {
    private val owners = mutableSetOf<Pair<String, String>>()
    @Synchronized fun bind(peer: ChannelPeer) { owners += peer.channelId to peer.peerId }
    @Synchronized fun unbind(peer: ChannelPeer) { owners -= peer.channelId to peer.peerId }
    @Synchronized override fun isOwner(peer: ChannelPeer): Boolean = peer.channelId to peer.peerId in owners
}
