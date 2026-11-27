package com.wangzan101.mika.task

import android.app.Service.STOP_FOREGROUND_REMOVE

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import com.wangzan101.mika.core.cap.web.KeptPageClose
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.ACTION_CLOSE_KEPT_PAGE
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_KEPT
import com.wangzan101.mika.task.TaskService.Companion.REQUEST_CLOSE_KEPT
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.answer

// Task service: finishing runs, kept pages and conversation records
/**
 * Write the summary after a stop and record it as this turn's answer (the outcome stays cancelled). It is written in the background with a process-level scope: it doesn't block teardown, the next job or tapping stop again, and it finishes even if the service exits.
 * Whether it is written or not, the entry is removed from "summarizing".
 */
internal fun TaskService.summarizeStop(run: TaskRun, runId: String, draft: String?, store: ConversationStore) {
    MemoryRuntime.of(this).scope.launch(store.meter(run.conversationId)) {
        try {
            val text = runCatching { run.stopSummary(draft) }.onFailure { Log.w(TAG, "stop summary failed", it) }.getOrNull()
            if (text != null) withContext(Dispatchers.IO) {
                runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = text, outcomeSystemType = null) } }
                    .onFailure { Log.w(TAG, "could not save stop summary", it) }
            }
        } finally {
            taskState._summarizing.update { it - runId }
        }
    }
}

/** The failure notification still gives only the short reason; the conversation gets the confirmed progress appended, so the next "continue" doesn't treat it as never done. */
internal fun TaskService.summarizeFailure(run: TaskRun, runId: String, reason: String, store: ConversationStore) {
    MemoryRuntime.of(this).scope.launch {
        val text = runCatching { run.checkpointSummary() }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "failure summary failed", it) }.getOrNull()
        if (text != null) withContext(Dispatchers.IO) {
            runCatching { store.updateTurn(run.conversationId, runId) { it.copy(outcome = reason + "\n" + text) } }
                .onFailure { Log.w(TAG, "could not save failure summary", it) }
        }
    }
}

/** After a task's teardown: leave the foreground when all are done; if other tasks remain, hand the foreground to them and release queued tasks. */
internal fun TaskService.onRunEnded(run: TaskRun, store: ConversationStore) {
    stopChannelTyping(run.key)
    val wasShown = taskState.shownRun === run
    val started = center.onEnded(run)
    // The screen lock held for the successor during teardown is handed to the first released task; if none was released (the queued one was cancelled, the same conversation still has a task running), release it
    run.wakeHandoff?.let { lease ->
        run.wakeHandoff = null
        started.firstOrNull()?.let { it.wakeHandoff = lease } ?: lease.release()
    }
    publishRuns()
    if (wasShown) started.firstOrNull { it.conversationId == run.conversationId }?.show()
    // Start the next job left for this conversation as soon as it frees up, without waiting for tasks in other conversations
    val ready = center.takeReady(run.conversationId)
    if (center.runs().isEmpty()) {
        if (ready.isNotEmpty()) {
            ready.forEach(::startFollowUp)
            return
        }
        // A page is still running in the background: switch the foreground notification to its own and keep the service in the foreground (the process is held up by it); exiting is handled by [onKeptPage] when it is closed
        val kept = keeper.page.value
        if (kept != null) {
            synchronized(notifyLock) {
                startForeground(ID_KEPT, keptNotification(kept))
                getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run))
            }
            foreground = null
            taskState._busy.value = false
            return
        }
        if (workflowStarts.get() > 0) {
            foreground = null
            taskState._busy.value = false
            keepForeground()
            return
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Take the ID before releasing: a new task arriving after the release brings a newer ID, so this stopSelf won't stop it
        val id = lastStartId
        taskState._busy.value = false
        stopSelf(id)
        return
    }
    // Switch to "preparing" first: a released task may take over the foreground right away, and the notification must not still say queued
    started.forEach { it.dequeue() }
    if (foreground === run) center.running().firstOrNull()?.let { next ->
        foreground = next
        startInForeground(next, progressNotification(next, next.state.value))
    }
    synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_PROGRESS, run)) }
    started.forEach {
        launch(it, store)
        refreshNotifications(it)
    }
    center.queued().forEachIndexed { i, q -> q.queue(i + 1) }
    center.running().forEach(::refreshNotifications)
    ready.forEach(::startFollowUp)
}

/**
 * The kept page changed: if it is still kept, post (or replace) its persistent notification — the foreground notification when no task is running; when it is closed, remove the notification, and
 * if no task is running at that point, leave the foreground and stop the service.
 */
internal fun TaskService.onKeptPage(page: com.wangzan101.mika.cap.web.KeptPage?) {
    val nm = getSystemService(NotificationManager::class.java)
    synchronized(notifyLock) {
        val idle = center.runs().isEmpty()
        if (page != null) {
            if (idle && foreground == null) startForeground(ID_KEPT, keptNotification(page)) else nm.notify(ID_KEPT, keptNotification(page))
            return
        }
        nm.cancel(ID_KEPT)
        if (!idle || taskState._busy.value || workflowStarts.get() > 0) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }
}

/** The persistent notification for the page kept in the background: the site, how long until it auto-closes (countdown), and the "Close page" / "Open conversation" buttons. */
internal fun TaskService.keptNotification(page: com.wangzan101.mika.cap.web.KeptPage): Notification {
    val close = PendingIntent.getService(
        this, REQUEST_CLOSE_KEPT, Intent(this, TaskService::class.java).setAction(ACTION_CLOSE_KEPT_PAGE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.kept_notif_title))
        .setContentText(page.host)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        // The timestamp counts down to the auto-close moment (so the notification text doesn't go stale)
        .setWhen(page.closesAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        .setContentIntent(openApp(page.conversationId))
        .addAction(Notification.Action.Builder(null, tr(R.string.kept_close), close).build())
        .addAction(Notification.Action.Builder(null, tr(R.string.kept_open_chat), openApp(page.conversationId)).build())
        .build()
}

/**
 * In the context given to the model, whether the page of a turn that kept one is still open right now: one recorded as "kept" but no longer present (Claire exited in between) counts as closed.
 */
internal fun TaskService.withKeptState(t: Turn): Turn =
    if (t.keptPage != null && t.keptPageClosed == null && keeper.page.value?.runId != t.runId) t.copy(keptPageClosed = KeptPageClose.AppExit.wire) else t

/** Record a turn when the task ends so later messages can carry it as a reference. A write failure doesn't affect the task result. */
internal fun TaskService.recordTurn(store: ConversationStore, run: TaskRun, end: TaskState, runId: String?, files: List<SharedFile> = emptyList(),
    audios: List<com.wangzan101.mika.core.chat.GeneratedAudio> = emptyList(),
    photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto> = emptyList(),
    videos: List<com.wangzan101.mika.core.chat.GeneratedVideo> = emptyList(),
    steers: List<String> = emptyList(), groups: List<String> = emptyList(), interrupted: List<String> = emptyList(),
    finished: List<String> = emptyList()) {
    val goal = run.triggerCondition ?: run.goal
    val turn = when (end) {
        // Unverified ones (chat, or answers with nothing to verify) are recorded as succeeded just like verified ones; only those reviewed but not verifiable are recorded as unverified
        is TaskState.Succeeded -> if (end.doubtful) Turn(goal, end.answer, "unverified", end.note, unconfirmed = end.unconfirmed, verified = false)
            else Turn(goal, end.answer, "succeeded", verified = end.verified.takeIf { end.reviewed })
        is TaskState.Failed -> Turn(goal, end.reason, "failed")
        TaskState.Cancelled -> Turn(goal, Conversation.CANCELLED_OUTCOME, "cancelled", outcomeSystemType = "task_cancelled")
        else -> return
    }.copy(runId = runId, remoteTaskId = run.mcpExternalId, steers = steers, groups = groups, interruptedReplies = interrupted, finishedReplies = finished,
        audios = audios, photos = photos, videos = videos)
        // 这一轮留下了一页:记下站点(已经关了的连同原因),对话页那一轮下面据此显示
        .let { t -> keeper.recordFor(runId)?.let { (host, closed) -> t.copy(keptPage = host, keptPageClosed = closed) } ?: t }
    runCatching { store.append(run.conversationId, turn, files) }.onFailure { Log.w(TAG, "could not save conversation turn", it) }
}
