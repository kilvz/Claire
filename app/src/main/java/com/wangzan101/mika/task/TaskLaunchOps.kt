package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import android.app.NotificationManager
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationTitler
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import com.wangzan101.mika.pocket.LockTriggerPolicy
import com.wangzan101.mika.task.TaskService.Companion.ID_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.SHELL_DIED
import com.wangzan101.mika.task.TaskService.Companion.start
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.answer
import com.wangzan101.mika.task.TaskService.Companion.describe

// Task service: starting new tasks, naming conversations and launching runs
/** Don't start a task while a demo is being captured or is paused, and don't take queued items away either. */
internal fun TaskService.teachingBlocks(): Boolean {
    val app = application as? com.wangzan101.mika.MikaApp ?: return false
    return app.teachReady && app.teach.blocksTasks()
}

/** A start rejected before a TaskRun exists still has to close its external owners. */
internal fun TaskService.finishRejectedStart(triggerKey: String?, mcpExternalId: String?) {
    val reason = tr(R.string.teach_blocked_task)
    triggerKey?.let { TriggerRuntime.actionFailed(this, it, reason) }
    mcpExternalId?.let {
        (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(
            it, TaskState.Failed(reason, FailKind.Environment), emptyList(),
        )
    }
}

internal fun TaskService.begin(newGoal: String, requestedConversation: String?,
    provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
    triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null, messageId: String? = null,
    channelPeer: ChannelPeer? = null, channelMessageId: String? = null, triggerCondition: String? = null, workflowAttemptId: String? = null,
    brief: String? = null) {
    if (pausedBlocks()) {
        finishPausedStart(provenance, triggerKey, mcpExternalId, workflowAttemptId, channelPeer, channelMessageId, followUp = null)
        return
    }
    if (teachingBlocks()) {
        finishRejectedStart(triggerKey, mcpExternalId)
        if (provenance.origin == RunOrigin.Ui) {
            android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        }
        return
    }
    parallel = FeatureFlags.get(this).speedFlags().parallelTasks
    // When a running or queued task in the same conversation receives new user text, hand it to that task's Inbox.
    requestedConversation?.let(center::byConversation)?.let { existing ->
        if (provenance.origin == RunOrigin.Ui) {
            if (messageId != null) existing.steer(newGoal, messageId) else existing.steer(newGoal)
            keepForeground()
            return
        }
    }
    // The task had already finished when the steer arrived: keep it as the next job for this conversation instead of dropping it because the service is still winding down.
    // It goes through the same persistent queue: even if the conversation happens to be idle, write it to disk first, then claim and start it.
    if (messageId != null && requestedConversation != null) {
        val item = QueuedFollowUp(messageId, requestedConversation, newGoal, System.currentTimeMillis(), QueuedSource.Fallback,
            provenance = provenance, channelPeer = channelPeer, channelMessageId = channelMessageId)
        startFollowUp(item)
        keepForeground()
        return
    }
    if (triggerKey != null && center.runs().any { it.triggerWindowKey == triggerKey &&
            it.conditionCheck == (triggerActionGoal != null) }) {
        keepForeground()
        return
    }
    startNew(newGoal, requestedConversation, provenance, triggerKey, triggerActionGoal, triggerThreadId, mcpExternalId,
        triggerCondition = triggerCondition, channelPeer = channelPeer, channelMessageId = channelMessageId, workflowAttemptId = workflowAttemptId,
        brief = brief)
}

/**
 * Common part after the live-task checks: create or queue a task. [followUp] is an item taken from the queue (or a guided fallback):
 * its persistent queue lifetime ends only when the run actually calls `markRunning`; if this conversation happens to have a task again, tryStart puts it back into the queue under the same lock.
 */
internal fun TaskService.startNew(newGoal: String, requestedConversation: String?,
    provenance: RunProvenance = RunProvenance(), triggerKey: String? = null,
    triggerActionGoal: String? = null, triggerThreadId: String? = null, mcpExternalId: String? = null,
    followUp: QueuedFollowUp? = null, triggerCondition: String? = null,
    channelPeer: ChannelPeer? = null, channelMessageId: String? = null, workflowAttemptId: String? = null,
    brief: String? = null) {
    if (pausedBlocks()) {
        finishPausedStart(provenance, triggerKey, mcpExternalId, workflowAttemptId, channelPeer, channelMessageId, followUp)
        return
    }
    if (teachingBlocks()) {
        finishRejectedStart(triggerKey, mcpExternalId)
        if (followUp != null) center.followUps.add(followUp)
        else if (provenance.origin == RunOrigin.Ui) {
            android.widget.Toast.makeText(this, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
        }
        return
    }
    parallel = FeatureFlags.get(this).speedFlags().parallelTasks
    // When a task in another conversation is running and parallelism is off (or at its limit), nothing is dropped: the registry queues it behind, and it is released when that task finishes.
    // The conversation page waits for the service to go idle before handing over, but between that check and the handover something else (a schedule, the computer) may have started one, so queue it here as well
    val store = ConversationStore.shared(this)
    val conversationId = requestedConversation ?: store.newConversation()
    // Scheduled and computer-handled tasks are never dropped: they queue behind if this conversation still has a task
    val queueHere = provenance.origin == RunOrigin.Trigger || provenance.origin == RunOrigin.Mcp || provenance.origin == RunOrigin.Channel
    when (val r = center.tryStart(conversationId, newGoal, queueInConversation = queueHere,
        channelPeer = channelPeer, channelMessageId = channelMessageId, followUp = followUp)) {
        StartResult.DuplicateChannelMessage -> acknowledgeChannel(channelPeer, channelMessageId)
        StartResult.DuplicateFollowUp -> keepForeground()
        StartResult.AlreadyRunningInConversation -> keepForeground()
        is StartResult.Queued -> {
            nameConversation(store, conversationId, newGoal, provenance)
            r.run.provenance = provenance
            r.run.triggerWindowKey = triggerKey
            r.run.triggerActionGoal = triggerActionGoal
            r.run.triggerCondition = triggerCondition
            r.run.triggerThreadId = triggerThreadId
            if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(r.run.key))?.let {
                center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
            }
            r.run.mcpExternalId = mcpExternalId
            r.run.workflowAttemptId = workflowAttemptId
            r.run.brief = brief
            r.run.channelPeer = channelPeer
            r.run.channelMessageId = channelMessageId
            acceptChannel(r.run)
            acknowledgeChannel(channelPeer, channelMessageId)
            taskState._busy.value = true
            taskState._conversationId.value = conversationId
            // Register before showing: by the time the UI receives its state, the task is already in the list (conversation, user text)
            publishRuns()
            // Clear the success criteria and run ID, and show it as queued
            if (center.running().none { it.conversationId == conversationId }) r.run.show()
            r.run.queue(r.position)
            keepForeground()
        }
        is StartResult.Started -> {
            nameConversation(store, conversationId, newGoal, provenance)
            taskState._busy.value = true
            taskState._conversationId.value = conversationId
            // Clear the success criteria and run ID, and set the state to "preparing"
            val run = r.run
            run.provenance = provenance
            run.triggerWindowKey = triggerKey
            run.triggerActionGoal = triggerActionGoal
            run.triggerCondition = triggerCondition
            run.triggerThreadId = triggerThreadId
            if (triggerKey != null) center.store.get(com.wangzan101.mika.core.run.RunId(run.key))?.let {
                center.store.save(it.copy(triggerWindowKey = triggerKey, userText = triggerCondition))
            }
            run.mcpExternalId = mcpExternalId
            run.workflowAttemptId = workflowAttemptId
            run.brief = brief
            run.channelPeer = channelPeer
            run.channelMessageId = channelMessageId
            acceptChannel(run)
            acknowledgeChannel(channelPeer, channelMessageId)
            // Register before showing: by the time the UI receives its state, the task is already in the list (conversation, user text)
            publishRuns()
            run.show()
            val fg = foreground
            if (fg != null && fg !== run && fg in center.running()) keepForeground()
            else {
                foreground = run
                startInForeground(run, progressNotification(run, run.state.value))
            }
            launch(run, store)
        }
    }
}

/** The first message the user (or the computer) hands over in a new conversation: ask a model in the background for a title and record it (only once; if it fails, nothing is recorded). */
internal fun TaskService.nameConversation(store: ConversationStore, id: String, first: String, provenance: RunProvenance) {
    if (provenance.origin != RunOrigin.Ui && provenance.origin != RunOrigin.Mcp && provenance.origin != RunOrigin.Channel) return
    scope.launch(Dispatchers.IO + store.meter(id)) {
        runCatching {
            val env = TaskEnvironment.of(this@nameConversation)
            // Titling also belongs to this conversation and uses the same session ID as the tasks in it
            val session = ModelSession.of(id)
            ConversationTitler.ensure(store, id, first, {
                val models = env.modelSnapshot(this@nameConversation, session, store.settings(id).main)
                listOfNotNull(models.light, models.main)
            }, language = AppLanguage.resolved(this@nameConversation).tag)
        }.onFailure { if (it is CancellationException) throw it }
    }
}

/** Entering the foreground again while already in it (or about to finish): use the foreground task's progress notification. */
internal fun TaskService.keepForeground() {
    val fg = foreground
    if (fg != null) startInForeground(fg, progressNotification(fg, fg.state.value))
    else startInForeground(null, preparingNotification())
}

internal fun TaskService.launch(run: TaskRun, store: ConversationStore) {
    val service = this
    // This task's own CPU lock: take it right away (the one held by scheduled dispatch is released afterwards), and release it only at teardown (when [run.job] ends)
    val cpu = KeepAwake(cpuWake.lease(), scope, keepThroughEnd = true).also { it.onState(run.state.value) }
    val waitingLockWatcher = scope.launch {
        run.state.collectLatest { state ->
            cpu.onState(state)
            if (LockTriggerPolicy.isWaiting(state)) {
                pocketGuard.lockWhileWaitingIfCovered {
                    LockTriggerPolicy.isWaiting(run.state.value) && run in center.running() &&
                        center.running().none { it !== run } && center.queued().isEmpty()
                }
            } else if (state is TaskState.Running) {
                pocketGuard.onTaskResumed()
            }
        }
    }
    val meter = store.meter(run.conversationId)
    run.job = scope.launch(meter) {
        center.markRunning(run)
        // No one is around for a scheduled task, so nothing stops it if it hangs: stop after the maximum run time from settings (unlimited by default)
        val limitMinutes = if (run.triggerWindowKey != null) FeatureFlags.get(service).triggerRunMinutes() else null
        val result = try {
            val prior = runCatching { store.turns(run.conversationId) }.getOrDefault(emptyList()).map(::withKeptState)
            if (limitMinutes == null) run.execute(prior) else withTimeout(limitMinutes * 60_000L) { run.execute(prior) }
        } catch (e: TimeoutCancellationException) {
            if (limitMinutes != null) TaskState.Failed(tr(R.string.task_trigger_time_limit, limitMinutes), FailKind.TimeLimit)
            else TaskState.Cancelled
        } catch (e: CancellationException) {
            TaskState.Cancelled
        } catch (e: RemoteException) {
            run.shellDied = true
            TaskState.Failed(SHELL_DIED, FailKind.Environment)
        } catch (e: UnattendedLockException) {
            TaskState.Failed(e.message.orEmpty(), FailKind.Environment)
        } catch (e: com.wangzan101.mika.core.device.access.AppAccessDenied) {
            val reason = when (e.code) {
                "app_blocked" -> tr(R.string.task_app_blocked, e.packageName ?: tr(R.string.task_app_this_app))
                "app_access_unavailable" -> tr(R.string.task_app_access_unavailable)
                "app_access_protected" -> tr(R.string.task_app_protected)
                else -> tr(R.string.task_app_unverified)
            }
            TaskState.Failed(reason, FailKind.Environment)
        } catch (e: Exception) {
            TaskState.Failed(tr(R.string.task_error, e.message ?: e::class.simpleName), FailKind.Environment)
        }
        // The shell process is gone, and the virtual display and the apps on it went with it; the task can't continue
        val end = if (run.shellDied && result !is TaskState.Succeeded) TaskState.Failed(SHELL_DIED, FailKind.Environment) else result
        // Stopped: the UI shows stopped on the spot and the summary follows (register "summarizing" first, so the UI knows more is coming when the terminal state arrives)
        val draft = if (end == TaskState.Cancelled) run.draftText() else null
        val stopRunId = run.runId.value?.takeIf { end == TaskState.Cancelled && !run.conditionCheck && run.hasStopMaterial(draft) }
        stopRunId?.let { id -> taskState._summarizing.update { it + id } }
        run.mcpExternalId?.let { (application as com.wangzan101.mika.MikaApp).remoteTasks.ended(it, end, run.steers.value, run.runId.value, run.sharedFiles.value) }
        run.end(end)
        if (!run.conditionCheck) recordTurn(store, run, end, run.runId.value, run.sharedFiles.value, run.audios.value, run.photos.value, run.videos.value, run.joinedSteers(), run.usedGroups, run.joinedInterrupted(), run.joinedFinished())
        // Sequential workflows must know whether the step actually completed before dispatching the next one.
        // Reuse the existing outcome judge once; unknown/partial results remain for the user to resolve.
        val workflowJudged = if (run.workflowAttemptId != null) runCatching { run.settleOutcome(end, store) }.getOrNull() else null
        run.workflowAttemptId?.let { attempt ->
            withContext(Dispatchers.IO) {
                (application as com.wangzan101.mika.MikaApp).workflows.complete(attempt,
                    end is TaskState.Succeeded && workflowJudged?.outcome == RunOutcome.Done,
                    (end as? TaskState.Succeeded)?.answer.orEmpty(),
                    (end as? TaskState.Failed)?.reason ?: workflowJudged?.reason.orEmpty())
            }
        }
        stopRunId?.let { summarizeStop(run, it, draft, store) }
        if (end is TaskState.Failed && !run.conditionCheck) run.runId.value?.let { summarizeFailure(run, it, end.reason, store) }
        if (run.conditionCheck) {
            val outcome = if (end is TaskState.Succeeded) ConditionOutcome.parse(end.answer)
                else ConditionOutcome.Error((end as? TaskState.Failed)?.reason ?: tr(R.string.task_condition_interrupted))
            TriggerRuntime.checked(service, run.triggerWindowKey.orEmpty(), run.triggerActionGoal.orEmpty(),
                run.triggerThreadId.orEmpty(), outcome)
            if (outcome is ConditionOutcome.Error) {
                val failure = TaskState.Failed(tr(R.string.task_check_unfinished, outcome.reason), FailKind.Environment)
                recordTurn(store, run, failure, run.runId.value, audios = run.audios.value, videos = run.videos.value)
                runCatching { notifyResult(run, prepareResult(run, failure, "condition:${run.key}")) }
                    .onFailure { Log.w(TAG, "condition failure notification failed", it) }
            }
            center.markDelivered(run)
            if (outcome is ConditionOutcome.Error) withContext(NonCancellable) {
                pocketGuard.lockAfterStopIfCovered {
                    center.running().none { it !== run } && center.queued().isEmpty()
                }
            }
            onRunEnded(run, store)
            return@launch
        }
        val deliveryId = "result:${run.key}"
        run.clearTakeoverStage()
        synchronized(notifyLock) { getSystemService(NotificationManager::class.java).cancel(id(ID_ATTENTION, run)) }
        // Send the result notification first, then learn in the background (extract memories, record skill outcomes, draft skills); a process-level scope is used so it finishes even if the service exits
        var delivered = false
        val app = applicationContext
        runCatching {
            // Persist the whole delivery before marking the result ready; recovery must know about its images too.
            // A storage failure skips sending but must still release this task's slot below.
            val delivery = prepareResult(run, end, deliveryId)
            center.markResult(run, describe(end).second, deliveryId, successful = end is TaskState.Succeeded)
            RunLearning.launchAfter(MemoryRuntime.of(service).scope + meter, {
                delivered = notifyResult(run, delivery) == DeliveryState.Delivered
                // A failed outcome needs no extra judgement; the trigger window ends as soon as it is delivered.
                if (delivered && end !is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching {
                        TriggerRuntime.actionFailed(app, key, describe(end).second)
                    }.onFailure { Log.w(TAG, "could not record trigger result", it) }
                }
            }) {
                // After handoff: judge the answer against the user's request (recorded in the run record and this turn of the conversation), and learn only from runs that succeeded
                val judged = if (run.workflowAttemptId != null) workflowJudged else runCatching { run.settleOutcome(end, store) }.onFailure { Log.w(TAG, "outcome judging failed", it) }.getOrNull()
                // If the answer honestly says it was not done or only partly done, record it as not done like an abandonment; an unjudged run counts as delivered
                val notDone = judged?.outcome == RunOutcome.Partial || judged?.outcome == RunOutcome.NotPossible
                if (delivered && notDone && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching { TriggerRuntime.actionFailed(app, key, judged?.reason?.ifBlank { null } ?: describe(end).second) }
                        .onFailure { Log.w(TAG, "could not downgrade trigger result", it) }
                } else if (delivered && end is TaskState.Succeeded) run.triggerWindowKey?.let { key ->
                    runCatching { TriggerRuntime.delivered(app, key) }
                        .onFailure { Log.w(TAG, "could not record trigger result", it) }
                }
                if (run.provenance.origin == RunOrigin.Ui && run.workflowAttemptId == null) runCatching { run.learnAfter(end, judged?.outcome, store) }
                    .onFailure { Log.w(TAG, "learning after run failed", it) }
            }
        }.onFailure { Log.w(TAG, "result notification failed", it) }
        if (delivered) center.markDelivered(run)
        if (LockTriggerPolicy.shouldCheck(end)) {
            withContext(NonCancellable) {
                pocketGuard.lockAfterStopIfCovered {
                    center.running().none { it !== run } && center.queued().isEmpty()
                }
            }
        }
        onRunEnded(run, store)
    }
    // When cancelled before teardown (service exits), or stopped before running after being released, the screen lock held on its behalf was neither handed off nor released: release it here
    run.job?.invokeOnCompletion { waitingLockWatcher.cancel(); cpu.close(); run.wakeHandoff?.release(); run.wakeHandoff = null }
}
