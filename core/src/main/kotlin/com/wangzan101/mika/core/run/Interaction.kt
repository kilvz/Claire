package com.wangzan101.mika.core.run

/** The question is emitted by AgentLoop and delivered by the current channel; the answer still only comes back through RunInbox. */
interface Interaction {
    suspend fun publish(ask: Delivery.Ask): DeliveryState
    fun resolved(askId: String)
}

/** Today the UI and notifications are shown by app state subscription, so publishing is treated as visible. */
object LocalInteraction : Interaction {
    override suspend fun publish(ask: Delivery.Ask) = DeliveryState.Delivered
    override fun resolved(askId: String) = Unit
}

class ChannelInteraction(
    private val outbox: Outbox,
    private val channel: Channel,
    private val peer: ChannelPeer,
) : Interaction {
    override suspend fun publish(ask: Delivery.Ask): DeliveryState = outbox.deliver(channel, peer, ask).state
    override fun resolved(askId: String) = Unit
}
