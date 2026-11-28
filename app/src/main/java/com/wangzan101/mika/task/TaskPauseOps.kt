package com.wangzan101.mika.task

import android.widget.Toast
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.MikaPause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

// Task service: block new tasks and stop the current ones when the user paused Claire

/**
 * The user paused Claire ([MikaPause]): start no new task and take nothing from the queue.
 * Each entry point already checked once before sending the request; this is the second gate at the service door, blocking those where the user paused after the check and before the service.
 */
internal fun TaskService.pausedBlocks(): Boolean = MikaPause.get(this).paused()

/**
 * A start request blocked at the door during a pause: settle its source one by one, and do not leave it to start by itself after resume.
 * This scheduled run is recorded as "skipped due to pause"; a task handed over by the computer is reported as failed; a workflow step is recorded as failed (left pending confirmation, for the user to decide after resume);
 * a queued item is removed from the persistent queue; a channel message is acknowledged first, then a best-effort "paused" reply is sent; a UI-initiated one gets a toast.
 */
internal fun TaskService.finishPausedStart(
    provenance: RunProvenance,
    triggerKey: String?,
    mcpExternalId: String?,
    workflowAttemptId: String?,
    channelPeer: ChannelPeer?,
    channelMessageId: String?,
    followUp: QueuedFollowUp?,
) {
    val app = application as MikaApp
    triggerKey?.let { TriggerRuntime.skippedPaused(this, it) }
    mcpExternalId?.let {
        app.remoteTasks.ended(it, TaskState.Failed(com.wangzan101.mika.core.run.PauseFlag.REMOTE_MESSAGE, FailKind.Environment), emptyList())
    }
    workflowAttemptId?.let(app::workflowPaused)
    followUp?.let { center.followUps.remove(it.id) }
    val peer = channelPeer ?: followUp?.channelPeer
    val messageId = channelMessageId ?: followUp?.channelMessageId
    if (peer != null && messageId != null) {
        val channel = runCatching { channelFor(peer) }.getOrNull()
        if (channel != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            channel.acknowledge(messageId)
            replyPaused(outbox, channel, peer, messageId)
        }
    }
    if (provenance.origin == RunOrigin.Ui && followUp == null) {
        Toast.makeText(this, tr(R.string.pause_blocked_task), Toast.LENGTH_LONG).show()
    }
    // Honour the foreground start contract; withdraw immediately when no task is in the foreground, so the "preparing" notification does not flash and disappear
    if (foreground != null || taskState._busy.value) keepForeground()
    else {
        startInForeground(null, preparingNotification())
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }
}

/**
 * The user just paused: stop all running and queued tasks, and also remove the next item in the persistent queue (leave no hidden queue that starts by itself after resume).
 * Queued ones are recorded as cancelled directly; running ones go through normal cancellation, and at teardown each settles scheduling, computer, workflow and channel.
 */
internal fun TaskService.stopForPause() {
    center.followUps.clear()
    for (run in center.runs()) {
        if (center.cancelIfQueued(run)) cancelQueued(run) else run.cancel()
    }
}

/**
 * The channel owner sent a stop command (#861): stop the running and queued tasks in this channel conversation. Only tasks in the same channel and conversation are touched;
 * tasks on the phone, handed over by a computer, or in other conversations are left alone; a task waiting for the user on the phone goes through cancellation teardown as usual.
 */
internal fun TaskService.stopChannelThread(channelId: String, threadId: String) {
    for (run in center.runs()) {
        val peer = run.channelPeer ?: continue
        if (peer.channelId != channelId || peer.threadId != threadId) continue
        if (center.cancelIfQueued(run)) cancelQueued(run) else run.cancel()
    }
    // Also drop same-conversation messages queued behind a stuck task, or they would start running after the stop
    for (item in center.followUps.pendingFor(threadId)) {
        if (item.channelPeer?.channelId == channelId) center.followUps.cancel(item.id)
    }
}

internal fun ChannelPeer?.sameThread(peer: ChannelPeer) =
    this != null && channelId == peer.channelId && threadId == peer.threadId

/** The stop reply: say how many were stopped, or say honestly that there was none. Sent through the reliable delivery ledger, replying only once per command; if it cannot be sent, so be it. */
internal suspend fun replyStopped(outbox: Outbox, channel: Channel, peer: ChannelPeer, messageId: String, stopped: Int) {
    val text = if (stopped > 0) tr(R.string.channel_stop_reply, stopped) else tr(R.string.channel_stop_none)
    try {
        outbox.deliver(channel, peer, Delivery.Notice("stopped:${peer.channelId}:$messageId", peer.threadId, text))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}

/**
 * New channel messages during a pause (the caller has acknowledged receipt): make a best-effort reply "Claire is paused, this one was not started". Sent through the reliable delivery ledger,
 * replying only once per message; if it cannot be sent, so be it: do not let it block acknowledgment or make the channel redeliver.
 */
internal suspend fun replyPaused(outbox: Outbox, channel: Channel, peer: ChannelPeer, messageId: String) {
    try {
        outbox.deliver(channel, peer, Delivery.Notice("paused:${peer.channelId}:$messageId", peer.threadId, tr(R.string.pause_channel_reply)))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }
}
