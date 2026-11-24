package com.wangzan101.mika.task

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.i18n.tr
import android.app.ActivityManager
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.task.TaskService.Companion.nextResultId
import com.wangzan101.mika.task.TaskService.Companion.notifyDelivery
import com.wangzan101.mika.task.TaskService.Companion.ID_RESULT
import com.wangzan101.mika.task.TaskService.Companion.start

// Task service: concurrency slots, follow-up queueing and channel receipts
internal fun TaskService.localDeliveryChannel(scheduled: Boolean) = LocalChannel { _, delivery ->
    if (delivery is Delivery.Ask) {
        center.byConversation(delivery.threadId)?.let(::refreshNotifications)
        SendOutcome.Delivered
    } else {
        val run = center.byConversation(delivery.threadId)
        val resultId = if (delivery is Delivery.Result && run?.parallel == false) ID_RESULT else nextResultId()
        val own = delivery !is Delivery.Result ||
            ownResultNotice(delivery, run?.key, run?.state?.value ?: TaskState.Idle, (application as MikaApp).deskReportsEnding)
        notifyDelivery(this, delivery, resultId, scheduled, ownResult = own)
    }
}

/**
 * Whether a task sends its own "Task complete" notification when it finishes: when run [runKey] wraps up with [end] and a front desk is currently following it and will speak that outcome
 * ([deskReportsEnding]), nothing is sent and the front desk's line is the only voice; ones redelivered after the process died ([Delivery.Result.restored]) and
 * ones whose run cannot be found send their own as in every other case.
 */
internal fun ownResultNotice(
    delivery: Delivery.Result,
    runKey: String?,
    end: TaskState,
    deskReportsEnding: (runKey: String, end: TaskState) -> Boolean,
): Boolean = delivery.restored || runKey == null || !deskReportsEnding(runKey, end)

internal fun TaskService.limit(): ParallelLimit {
    if (!parallel) return ParallelLimit(1, null)
    val lowRam = getSystemService(ActivityManager::class.java).isLowRamDevice
    return ParallelCapability.detect(Build.VERSION.SDK_INT, lowRam, FeatureFlags.get(this).parallelMax(), taskState.secondDisplayFailed)
        .also { taskState._parallelLimit.value = it }
}

/** The lowest slot nobody uses in the registry; only called under the registry lock. */
internal fun TaskService.freeSlot(): Int {
    val used = center.runs().map { it.slot }.toSet()
    return generateSequence(0) { it + 1 }.first { it !in used }
}

/** If this conversation is idle, start right away; if it still has a task, leave it in the persistent queue and hand it out when that task finishes ([onRunEnded]). The check and the queueing happen under the same lock. */
internal fun TaskService.deferMessage(threadId: String, text: String, messageId: String) {
    val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), QueuedSource.Fallback)
    if (center.deferOrStart(threadId, text, messageId, QueuedSource.Fallback)) startFollowUp(item)
    else keepForeground()
}

/** The user explicitly chose "queue": if this conversation still has a task, write it into the persistent queue; if it just became idle, start it right away. */
internal fun TaskService.queueFollowUp(threadId: String, text: String, messageId: String, source: QueuedSource) {
    val item = QueuedFollowUp(messageId, threadId, text, System.currentTimeMillis(), source)
    if (center.deferOrStart(threadId, text, messageId, source)) {
        // This conversation just went idle: skip enqueueing and go straight to startNew; if another task arrives in between, tryStart atomically puts it back into the queue
        startFollowUp(item)
    } else {
        keepForeground()
    }
}

/** Take the next item for this conversation; only one is taken, and startNew either registers it or puts it back. */
internal fun TaskService.drainReady(conversationId: String?) {
    if (teachingBlocks()) return
    center.takeReady(conversationId).forEach(::startFollowUp)
}

/** Start one queued item; make sure it is already persisted first, and put it back into the queue if the start fails, so it doesn't stay in the claimed state. */
internal fun TaskService.startFollowUp(item: QueuedFollowUp) {
    if (!center.persistFollowUp(item)) return
    runCatching {
        startNew(item.text, item.conversationId, provenance = item.provenance, followUp = item,
            channelPeer = item.channelPeer, channelMessageId = item.channelMessageId)
    }.onFailure { center.followUps.add(item) }
}

internal fun TaskService.channelFor(peer: ChannelPeer, scheduled: Boolean = false): Channel = when (peer.channelId) {
    LocalChannel.ID -> if (scheduled) scheduledLocalChannel else localChannel
    else -> checkNotNull((application as com.wangzan101.mika.MikaApp).wechat.channelFor(peer.channelId)) {
        "unknown channel ${peer.channelId}"
    }
}

internal fun TaskService.acknowledgeChannel(peer: ChannelPeer?, messageId: String?) {
    if (peer != null && messageId != null) channelFor(peer).acknowledge(messageId)
}

/**
 * Each newly registered task is acknowledged only once, and run stages are not forwarded. It goes straight into the reliable delivery ledger and takes its send order,
 * while the network send happens in the background; a duplicate message creates no task, so it is not acknowledged again either.
 * Telegram only acknowledges the first one after it is set up (see TelegramChannel.shouldAcknowledge);
 * tasks after that get no acknowledgement, only typing indicators and the final answer; other channels acknowledge every time.
 */
internal fun TaskService.acceptChannel(run: TaskRun) {
    val peer = run.channelPeer ?: return
    val channel = channelFor(peer)
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        if (channel.shouldAcknowledge(peer)) {
            outbox.deliver(channel, peer,
                Delivery.Notice("accepted:${run.key}", peer.threadId, tr(R.string.channel_task_accepted)))
        }
    }
    startChannelTyping(run)
}

/** Telegram shows "typing..." for about 5 seconds; refresh this often while a channel task runs. */
private const val TYPING_INTERVAL_MS = 4_000L

/**
 * "typing..." while a channel task runs. One loop per run at most (a
 * second accept for the same run replaces the old loop); the loop ends
 * the moment the run leaves the center, and [onRunEnded] cancels it
 * outright. Typing is best-effort — a failure is swallowed inside
 * [Channel.typing] and can never fail, delay or duplicate the task.
 */
private val typingJobs = mutableMapOf<String, Job>()
private val typingLock = Any()

internal fun TaskService.startChannelTyping(run: TaskRun) {
    val peer = run.channelPeer ?: return
    val job = scope.launch {
        while (center.runs().any { it.key == run.key }) {
            runCatching { channelFor(peer).typing(peer) }
                .onFailure { if (it is CancellationException) throw it }
            delay(TYPING_INTERVAL_MS)
        }
    }
    synchronized(typingLock) {
        typingJobs[run.key]?.cancel()
        typingJobs[run.key] = job
    }
    job.invokeOnCompletion {
        synchronized(typingLock) { if (typingJobs[run.key] === job) typingJobs.remove(run.key) }
    }
}

internal fun TaskService.stopChannelTyping(runKey: String) {
    synchronized(typingLock) { typingJobs.remove(runKey) }?.cancel()
}

/** A queued task was cancelled (already removed from the queue): it never started, so record it as cancelled directly; the next item behind it is taken up. */
internal fun TaskService.cancelQueued(run: TaskRun) {
    run.handoffUnconsumed(cancelled = true)
    run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, TaskState.Cancelled, run.steers.value, run.runId.value, run.sharedFiles.value) }
    run.workflowAttemptId?.let { attempt ->
        (application as com.wangzan101.mika.MikaApp).workflowInterrupted(attempt)
    }
    run.triggerWindowKey?.let { key ->
        TriggerRuntime.actionFailed(this, key, Conversation.CANCELLED_OUTCOME)
    }
    run.end(TaskState.Cancelled)
    recordTurn(ConversationStore.shared(this), run, TaskState.Cancelled, null, audios = run.audios.value)
    run.mcpExternalId = null
    publishRuns()
    center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    drainReady(run.conversationId)
}
