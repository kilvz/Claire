package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice

/**
 * Channel inbound requests verify the owner, question ID and confirmation permission here; ordinary new messages always start a new task.
 * When the user paused Claire ([paused]), a new message does not start a task and is not held for resume: acknowledge receipt first, then make a best effort to reply "paused" ([Route.paused]);
 * Even if the reply cannot be sent, the acknowledgement stands, the channel will not redeliver this message, and it will not start automatically after resuming; resend the request after resuming.
 */
class ChannelGateway(
    private val owners: OwnerIdentity,
    private val activeAsk: (String) -> Delivery.Ask?,
    private val route: Route,
    private val paused: PauseFlag = PauseFlag.Never,
) {
    interface Route {
        suspend fun newTask(peer: ChannelPeer, messageId: String, text: String)
        /** A new message received while paused (already acknowledged): make a best effort to tell the other side that Claire is paused and this message was not started. If it cannot be sent, let it go without retrying. */
        suspend fun paused(peer: ChannelPeer, messageId: String)
        suspend fun answer(threadId: String, askId: String, text: String): Boolean
        suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean
        suspend fun stop(runId: RunId)
        suspend fun cancelAsk(threadId: String)
    }

    fun start(channel: Channel): AutoCloseable = channel.start(ChannelInputSink { input ->
        if (input.peer.channelId != channel.id || !owners.isOwner(input.peer)) return@ChannelInputSink
        when (input) {
            is ChannelInput.Stop -> route.stop(input.runId)
            is ChannelInput.Text -> {
                if (paused.paused()) {
                    channel.acknowledge(input.messageId)
                    try { route.paused(input.peer, input.messageId) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { }
                    return@ChannelInputSink
                }
                val ask = activeAsk(input.peer.threadId)
                if (input.replyToAskId != null && ask?.askId == input.replyToAskId && ask.kind == Delivery.Ask.Kind.Input) {
                    if (route.answer(input.peer.threadId, ask.askId, input.text)) channel.acknowledge(input.messageId)
                } else if (input.replyToAskId != null && input.requireMatchingAsk) {
                    channel.acknowledge(input.messageId)
                } else {
                    if (ask != null) route.cancelAsk(input.peer.threadId)
                    route.newTask(input.peer, input.messageId, input.text)
                }
            }
            is ChannelInput.Confirm -> {
                if (paused.paused()) return@ChannelInputSink
                val ask = activeAsk(input.peer.threadId)
                if (ask?.askId == input.askId && ask.kind == Delivery.Ask.Kind.Confirm && channel.confirms) {
                    route.confirm(input.peer.threadId, ask.askId, input.choice)
                }
            }
        }
    })
}
