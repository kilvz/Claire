package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInteraction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.settings.ui.StatusCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalTime
import com.wangzan101.mika.core.cap.web.KeptPageClose

/**
 * This notification is still posted and still goes into the notification shade, but it makes no sound, does not vibrate and shows no heads-up: it uses the system's group-alert rules
 * (only the group summary alerts, and this is an ordinary notification in a group without a summary), the same trick as the system's own "silent notifications"; no channel switch and no new channel. Available since Android 8, which every system Claire supports has.
 */
internal fun Notification.Builder.alertQuietly(): Notification.Builder =
    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)

internal fun Notification.Builder.applyScheduledQuietHours(context: Context): Notification.Builder =
    if (FeatureFlags.get(context).scheduledQuietHours().isQuietAt(LocalTime.now())) alertQuietly() else this

private const val QUIET_GROUP = "com.wangzan101.mika.quiet"

/**
 * Foreground service: runs tasks on the Agent virtual screen; the state and execution of a single task are in [TaskRun], and running tasks are registered in [TaskRegistry].
 * While the parallelTasks flag is off only one runs at a time, and tasks submitted from other conversations queue behind it (wherever they come from, none is lost);
 * while it is on, tasks from different conversations can run at the same time, each taking a virtual screen, with the limit in [ParallelCapability], and the excess queueing.
 * Each task has its own set of notifications (IDs and button request codes are separated by [TaskRun.slot], and the buttons carry the conversation ID);
 * the foreground permanent notification is the progress notification of one of the tasks, noting "n tasks running" when several run at once.
 * While a task runs the screen stays on ([KeepAwake], without changing system settings or brightness); during the task the target app is muted (the original mode is persisted and restored on the next start after an abnormal exit);
 * the AgentLoop gate is implemented by [TaskGate]: wait for unlock on screen-off/lock, and pause until the user taps "Continue" when the app is moved to the main screen or the phone-operation assistant disconnects.
 * When the Agent requests a takeover or asks a question, an attention-grabbing notification is posted as well; the takeover flow is described in [Takeover].
 *
 * The foreground service type is specialUse: dataSync has a daily 6-hour limit on Android 15 and does not match the semantics;
 * specialUse has no runtime limit and only requires declaring the subtype in the manifest.
 */
class TaskService : Service() {
    internal val taskState: TaskCenterState get() = (application as com.wangzan101.mika.MikaApp).taskState
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The flag read at the most recent start; while it is off the registry limit is always 1. */
    @Volatile internal var parallel = false
    internal val center by lazy {
        val app = application as com.wangzan101.mika.MikaApp
        val wsOf: (String) -> String = { id -> com.wangzan101.mika.chat.WorkspaceResolver.resolveFor(app, id).wire }
        TaskCenter<TaskRun>(
            ::limit, { it.conversationId }, { it.key }, app.runStore,
            app.resources, app.followUpQueue,
            workspaceOf = { wsOf(it.conversationId) },
            resolveWorkspace = wsOf,
        ) { c, g ->
            TaskRun(c, g, this, scope, freeSlot(), parallel)
        }
    }
    internal val outbox get() = (application as com.wangzan101.mika.MikaApp).outbox
    internal val localChannel by lazy { localDeliveryChannel(scheduled = false) }
    internal val scheduledLocalChannel by lazy { localDeliveryChannel(scheduled = true) }

    /** Which task the foreground permanent notification belongs to; kept after teardown until it is switched to another task. */
    @Volatile internal var foreground: TaskRun? = null
    internal val notifyLock = Any()
    internal val workflowStarts = AtomicInteger()
    @Volatile internal var lastStartId = 0

    internal val clipLock get() = center.clipLock
    internal val takeoverQueue get() = center.takeoverQueue
    internal fun rememberRecord(run: TaskRun, recordId: String) = center.markRecord(run, recordId)
    /** The screen-on wake lock shared by several tasks. */
    internal val wakeLock by lazy { SharedWakeLock(ScreenWakeLock(this)) }

    internal fun reservePackage(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.reserve(pkg, runKey, stillUsed)
    internal fun packageFree(pkg: String, runKey: String, stillUsed: () -> Boolean) = center.appFree(pkg, runKey, stillUsed)
    internal suspend fun awaitPackage(pkg: String, runKey: String, timeoutMs: Long, free: suspend () -> Boolean) =
        center.awaitApp(pkg, runKey, timeoutMs, free)

    internal fun interaction(threadId: String, source: ChannelPeer? = null): ChannelInteraction {
        val peer = source ?: ChannelPeer(LocalChannel.ID, "phone-owner", threadId)
        return ChannelInteraction(outbox, channelFor(peer), peer)
    }

    /** The page left in the background after the task ends (see [com.wangzan101.mika.cap.web.PageKeeper]): while it is kept the service does not stop, and the foreground notification is switched to it. */
    internal val keeper get() = (application as com.wangzan101.mika.MikaApp).pageKeeper

    internal fun otherRunHasDisplay(run: TaskRun) = center.running().any { it !== run && it.displayId.value >= 0 }
    internal fun hasActiveRuns() = center.running().isNotEmpty()
    internal fun hasQueuedRuns() = center.queued().isNotEmpty()

    // ---------- Automatic relock after a task unlocks the phone ----------

    /** Relock obligation: kept until the last running or queued task ends; held until the system confirms the phone is locked. */
    internal val taskRelock = TaskRelock()
    internal val relockMutex = Mutex()

    internal val pocketGuard by lazy { com.wangzan101.mika.pocket.PocketGuardManager(this) }

    internal fun publishRuns() {
        val r = center.runs()
        pocketGuard.setTasksActive(center.running().isNotEmpty())
        taskState._runs.value = r
        taskState._activeConversations.value = r.map { it.conversationId }
    }

    internal fun id(base: Int, run: TaskRun) = base + SLOT_STRIDE * run.slot

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        // The kept page changed: the notification follows it; when it is closed and no task is running, leave the foreground and stop the service
        // Only follow "kept → replaced / closed": the initial "none" does not count, so it does not race a starting task to stop the service
        scope.launch {
            var had = false
            keeper.page.collect { p ->
                if (p == null && !had) return@collect
                had = p != null
                onKeptPage(p)
            }
        }
        // Unfinished runs were already handled when the app started; this adds one more pass (done only once per process), excluding the runs that are running right now
        if (!FeatureFlags.get(this).quitRequested()) {
            (application as com.wangzan101.mika.MikaApp).runRecovery.recover(center.runs().map { RunId(it.key) }.toSet())
        }
    }

    internal fun waitKeyOf(intent: Intent) = intent.getIntExtra(EXTRA_WAIT_KEY, -1).takeIf { it >= 0 }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // The system re-delivering an old start request, a notification button or an external entry point may all land here after a full quit: do not handle the action and do not keep the service resident
        if (FeatureFlags.get(this).quitRequested()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Notification buttons carry a task ID and act only on that task (no effect once it has ended); in-app buttons act on the shown task
        val run = if (intent?.hasExtra(EXTRA_RUN) == true) intent.getStringExtra(EXTRA_RUN)?.let(center::byKey) else taskState.shownRun
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                messageId = intent.getStringExtra(EXTRA_MESSAGE_ID), brief = intent.getStringExtra(EXTRA_BRIEF))
            ACTION_ENQUEUE_FOLLOWUP -> {
                val thread = intent.getStringExtra(EXTRA_CONVERSATION)
                val text = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                val id = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: java.util.UUID.randomUUID().toString()
                val source = QueuedSource.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SOURCE) }
                    ?: QueuedSource.Explicit
                if (thread != null && text.isNotBlank()) queueFollowUp(thread, text, id, source)
            }
            ACTION_DRAIN_FOLLOWUPS -> drainReady(intent.getStringExtra(EXTRA_CONVERSATION))
            ACTION_START_TRIGGER -> begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
                triggerKey = intent.getStringExtra(EXTRA_TRIGGER_KEY),
                triggerActionGoal = intent.getStringExtra(EXTRA_TRIGGER_ACTION),
                triggerThreadId = intent.getStringExtra(EXTRA_TRIGGER_THREAD),
                triggerCondition = intent.getStringExtra(EXTRA_TRIGGER_CONDITION))
            ACTION_START_WORKFLOW -> {
                workflowStarts.incrementAndGet()
                keepForeground()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val workflows = (application as com.wangzan101.mika.MikaApp).workflows
                    val attemptId = intent.getStringExtra(EXTRA_WORKFLOW_ATTEMPT).orEmpty()
                    try {
                        val dispatch = withContext(Dispatchers.IO) {
                            workflows.store.claim(attemptId)?.also {
                                ConversationStore.shared(this@TaskService).setTitle(it.conversationId, it.step.title)
                            }
                        }
                        if (dispatch != null) {
                            if (teachingBlocks()) withContext(Dispatchers.IO) { workflows.complete(attemptId, false, "", "start_failed") }
                            else withContext(Dispatchers.Main) {
                                begin(getString(R.string.workflow_step_prompt, dispatch.step.instruction, dispatch.input, dispatch.previous),
                                    dispatch.conversationId, workflowAttemptId = dispatch.attemptId)
                            }
                        }
                    } catch (e: CancellationException) {
                        (application as com.wangzan101.mika.MikaApp).workflowInterrupted(attemptId)
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.IO) { workflows.complete(attemptId, false, "", "start_failed") }
                    } finally {
                        if (workflowStarts.decrementAndGet() == 0 && !taskState._busy.value && keeper.page.value == null) stopSelf(lastStartId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id ->
                if ((application as com.wangzan101.mika.MikaApp).remoteTasks.claimStart(id)) {
                    begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION),
                        provenance = RunProvenance(RunOrigin.Mcp,
                            Attendance.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_ATTENDANCE) } ?: Attendance.Unattended),
                        mcpExternalId = id)
                }
            }
            ACTION_START_CHANNEL -> {
                val peer = ChannelPeer(intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty(),
                    intent.getStringExtra(EXTRA_PEER_ID).orEmpty(), intent.getStringExtra(EXTRA_CONVERSATION).orEmpty())
                begin(intent.getStringExtra(EXTRA_GOAL).orEmpty(), peer.threadId,
                    provenance = RunProvenance(RunOrigin.Channel, Attendance.Reachable),
                    channelPeer = peer, channelMessageId = intent.getStringExtra(EXTRA_CHANNEL_MESSAGE_ID))
            }
            ACTION_CANCEL_MCP -> intent.getStringExtra(EXTRA_MCP_ID)?.let { id -> center.runs().firstOrNull { it.mcpExternalId == id } }
                ?.let { r -> if (center.cancelIfQueued(r)) cancelQueued(r) else r.cancel() }
            ACTION_CANCEL -> if (run != null && center.cancelIfQueued(run)) cancelQueued(run) else run?.cancel()
            ACTION_RESUME -> run?.resume()
            ACTION_TAKE_OVER -> run?.takeOver(waitKeyOf(intent))
            ACTION_HAND_BACK -> run?.handBack(waitKeyOf(intent))
            ACTION_FINISHED_MYSELF -> run?.finishedMyself(waitKeyOf(intent))
            ACTION_SKIP -> run?.skip(waitKeyOf(intent))
            ACTION_PUT_ASIDE -> run?.putAside(waitKeyOf(intent))
            ACTION_ANSWER -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                    ?: intent.getStringExtra(EXTRA_ANSWER)
                if (!text.isNullOrBlank()) run?.answer(text.trim(), ::answerTooLate)
            }
            ACTION_CLOSE_KEPT_PAGE -> keeper.close(KeptPageClose.User)
            ACTION_APPROVE, ACTION_DECLINE -> {
                val key = if (intent.hasExtra(ConfirmationNotifier.EXTRA_REQUEST)) intent.getIntExtra(ConfirmationNotifier.EXTRA_REQUEST, 0) else null
                run?.confirm(if (intent.action == ACTION_APPROVE) ConfirmChoice.Once else ConfirmChoice.Deny, key)
            }
        }
        if (workflowStarts.get() == 0 && !taskState._busy.value && keeper.page.value == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    /**
     * Redraws notifications according to the current state. Several coroutines call it, so it is serialized and reads the latest state inside the lock:
     * otherwise a party holding an old state could post the notification again after a new state has removed it.
     */
    internal fun refreshNotifications(run: TaskRun) {
        synchronized(notifyLock) {
            val shown = run.shownState() ?: return
            if (!shown.isActive() || center.isQueued(run)) return
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(id(ID_PROGRESS, run), progressNotification(run, shown))
            val attention = attentionNotification(run, shown)
            if (attention != null) nm.notify(id(ID_ATTENTION, run), attention) else nm.cancel(id(ID_ATTENTION, run))
        }
    }

    override fun onDestroy() {
        pocketGuard.close()
        scope.cancel()
        // Without the service the process cannot be kept alive: close the kept page honestly and record it in the conversation
        keeper.close(KeptPageClose.AppExit)
        getSystemService(NotificationManager::class.java).cancel(ID_KEPT)
        if (center.runs().isNotEmpty()) {
            // After marking as interrupted, settle on the spot (trigger windows, re-delivery, notifications, writing into the conversation): the process is still alive, so do not wait for the next launch; use the app-level scope so it finishes even after the service is gone
            center.store.interruptUnfinished()
            val app = application as com.wangzan101.mika.MikaApp
            center.runs().forEach { r -> r.workflowAttemptId?.let(app::workflowInterrupted) }
            center.runs().forEach { r -> r.mcpExternalId?.let { app.remoteTasks.ended(it, TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted), r.steers.value, r.runId.value, r.sharedFiles.value) } }
            (application as com.wangzan101.mika.MikaApp).runRecovery.settleInterrupted()
            taskState._state.value = TaskState.Failed(tr(R.string.task_interrupted), FailKind.Interrupted)
            taskState._runs.value = emptyList()
            taskState._activeConversations.value = emptyList()
            taskState.shownRun = null
        }
        taskState._busy.value = false // Even if teardown is cancelled, this must not keep blocking new tasks
        runCatching { TriggerRuntime.recoverAfterServiceStop(this) }
            .onFailure { Log.w(TAG, "could not recover trigger windows after service stop", it) }
        super.onDestroy()
    }

    companion object {
        /** Creates notification channels; creating them repeatedly has no side effects. Also used by recovery at app start, so it does not depend on a service instance. */
        internal fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val names = mapOf(CHANNEL_PROGRESS to tr(R.string.channel_progress), CHANNEL_RESULT to tr(R.string.channel_result), CHANNEL_ATTENTION to tr(R.string.channel_attention))
            // The old result channel was created at a lower importance and an app cannot raise it; switch to a new ID and delete the old one so settings does not show an extra entry
            nm.deleteNotificationChannel(OLD_CHANNEL_RESULT)
            CHANNEL_IMPORTANCE.forEach { (id, importance) -> nm.createNotificationChannel(NotificationChannel(id, names.getValue(id), importance)) }
        }

        internal fun openApp(context: Context, threadId: String? = null): PendingIntent = PendingIntent.getActivity(
            context, threadId?.hashCode() ?: 0, openAppIntent(context, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Opens Claire; when [threadId] is given it goes straight to that conversation. Shared by notifications and the floating capsule. */
        internal fun openAppIntent(context: Context, threadId: String? = null): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (threadId != null) setIdentifier(threadId).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId) }

        /** The next ID for result notifications when parallel (reused cyclically). */
        internal fun nextResultId() = ID_RESULT_FIRST + resultCount.getAndIncrement() % RESULT_IDS

        /**
         * Send results and prompts as notifications (shared by the service and by app-start recovery); [resultId] is the result notification's id.
         * When [ownResult] is false the result is not notified: the front desk is following this run and reports it (see [ownResultNotice]).
         */
        internal fun notifyDelivery(context: Context, delivery: Delivery, resultId: Int, scheduled: Boolean = false, ownResult: Boolean = true): SendOutcome {
            val nm = context.getSystemService(NotificationManager::class.java)
            when (delivery) {
                // While Claire is in the foreground (the user is looking at the chat page) do not pop a task-complete notification; show the result in-app only and keep the notification for when the user switches to another app (#731).
                is Delivery.Result -> if (!MainActivity.foreground && ownResult) nm.notify(resultId, Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(delivery.title)
                    .setContentText(delivery.text)
                    .setStyle(Notification.BigTextStyle().bigText(delivery.text))
                    .setContentIntent(openApp(context, delivery.threadId))
                    .setAutoCancel(true)
                    .apply { if (scheduled) applyScheduledQuietHours(context) }
                    .build())
                is Delivery.Notice -> nm.notify(nextResultId(), Notification.Builder(context, CHANNEL_RESULT)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("Claire")
                    .setContentText(delivery.text).setContentIntent(openApp(context, delivery.threadId)).setAutoCancel(true)
                    .apply { if (scheduled) applyScheduledQuietHours(context) }
                    .build())
                is Delivery.Ask, is Delivery.Progress, is Delivery.Image -> {}
            }
            return SendOutcome.Delivered
        }

        /** The time limit for follow-up questions waiting for an answer: passed to AgentLoop, and the minutes written on notifications and cards come from here too. */
        const val INPUT_TIMEOUT_MS = AgentLoop.DEFAULT_INPUT_TIMEOUT_MS
        const val INPUT_TIMEOUT_MINUTES = INPUT_TIMEOUT_MS / 60_000
        const val ACTION_START = "com.wangzan101.mika.task.START"
        const val ACTION_ENQUEUE_FOLLOWUP = "com.wangzan101.mika.task.ENQUEUE_FOLLOWUP"
        const val ACTION_DRAIN_FOLLOWUPS = "com.wangzan101.mika.task.DRAIN_FOLLOWUPS"
        const val ACTION_START_TRIGGER = "com.wangzan101.mika.task.START_TRIGGER"
        internal const val ACTION_START_WORKFLOW = "com.wangzan101.mika.task.START_WORKFLOW"
        internal const val EXTRA_WORKFLOW_ATTEMPT = "workflow_attempt"
        const val ACTION_START_MCP = "com.wangzan101.mika.task.START_MCP"
        const val ACTION_START_CHANNEL = "com.wangzan101.mika.task.START_CHANNEL"
        const val ACTION_CANCEL_MCP = "com.wangzan101.mika.task.CANCEL_MCP"
        const val ACTION_CANCEL = "com.wangzan101.mika.task.CANCEL"
        const val ACTION_RESUME = "com.wangzan101.mika.task.RESUME"
        const val ACTION_TAKE_OVER = "com.wangzan101.mika.task.TAKE_OVER"
        const val ACTION_HAND_BACK = "com.wangzan101.mika.task.HAND_BACK"
        const val ACTION_FINISHED_MYSELF = "com.wangzan101.mika.task.FINISHED_MYSELF"
        const val ACTION_SKIP = "com.wangzan101.mika.task.SKIP"
        /** The system back key was pressed on the web-takeover UI: put it aside and go back to waiting for takeover. */
        const val ACTION_PUT_ASIDE = "com.wangzan101.mika.task.PUT_ASIDE"
        /** The "which wait" carried by takeover notification buttons, see [Takeover.waitKey]. */
        const val EXTRA_WAIT_KEY = "com.wangzan101.mika.task.WAIT_KEY"
        const val ACTION_ANSWER = "com.wangzan101.mika.task.ANSWER"
        const val ACTION_APPROVE = "com.wangzan101.mika.task.APPROVE"
        /** Closes the page left in the background after the task ends (a button on the notification). */
        const val ACTION_CLOSE_KEPT_PAGE = "com.wangzan101.mika.task.CLOSE_KEPT_PAGE"
        const val ACTION_DECLINE = "com.wangzan101.mika.task.DECLINE"
        /** From this many consecutive failures of a scheduled task (including the current one) on, the result notification states the consecutive failures. */
        internal const val FAILURE_STREAK_NOTICE = 3
        internal const val EXTRA_GOAL = "goal"
        internal const val EXTRA_MESSAGE_ID = "message_id"
        internal const val EXTRA_BRIEF = "brief"
        internal const val EXTRA_SOURCE = "source"
        internal const val EXTRA_CONVERSATION = "conversation"
        internal const val EXTRA_TRIGGER_KEY = "trigger_key"
        internal const val EXTRA_TRIGGER_ACTION = "trigger_action"
        internal const val EXTRA_TRIGGER_THREAD = "trigger_thread"
        internal const val EXTRA_TRIGGER_CONDITION = "trigger_condition"
        internal const val EXTRA_MCP_ID = "mcp_id"
        internal const val EXTRA_ATTENDANCE = "attendance"
        internal const val EXTRA_CHANNEL_ID = "channel_id"
        internal const val EXTRA_PEER_ID = "peer_id"
        internal const val EXTRA_CHANNEL_MESSAGE_ID = "channel_message_id"
        internal const val EXTRA_ANSWER = "answer"
        /** The ID of the task a notification button belongs to ([TaskRun.key]). */
        const val EXTRA_RUN = "com.wangzan101.mika.task.RUN"
        /** Wait at most this long for the user to answer a confirmation, then treat it as declined (a task does not hang forever when the phone is away, and never auto-approves). */
        const val CONFIRM_TIMEOUT_MS = 15 * 60_000L
        internal const val KEY_REPLY = "reply"
        const val CHANNEL_ATTENTION = "task_attention"
        const val CHANNEL_PROGRESS = "task_progress"
        const val CHANNEL_RESULT = "task_result_v2"
        internal const val OLD_CHANNEL_RESULT = "task_result"
        /** Importance of each notification channel at creation; "needs you" (takeover, question, confirmation) and results must pop up, progress stays quiet. If the user lowers them afterwards, the app cannot raise them back. */
        internal val CHANNEL_IMPORTANCE = mapOf(
            CHANNEL_PROGRESS to NotificationManager.IMPORTANCE_LOW,
            CHANNEL_RESULT to NotificationManager.IMPORTANCE_HIGH,
            CHANNEL_ATTENTION to NotificationManager.IMPORTANCE_HIGH,
        )
        internal const val ID_PROGRESS = 1
        internal const val ID_RESULT = 2
        internal const val ID_ATTENTION = 3
        /** The permanent notification for the page kept in the background (there is only one page globally, not per slot). */
        internal const val ID_KEPT = 4
        /** The ID shared by the unlock guard and the relock-failure warning (one globally; the warning replaces the guard). */
        internal const val ID_RELOCK = 5
        /** Grace period to wait for the main screen to be idle before relocking: if the user touched the main screen during the wait, lock only after this long with no further touches. */
        internal const val RELOCK_IDLE_GRACE_MS = 5_000L
        /** How many times and at what interval to wait for system confirmation after the relock action is issued. */
        internal const val RELOCK_POLL_TRIES = 20
        internal const val RELOCK_POLL_MS = 100L
        internal const val REQUEST_CLOSE_KEPT = 99
        /** Task notification IDs and request codes of slot n are offset by n × this; the first slot is the same as when only one task runs. */
        internal const val SLOT_STRIDE = 100
        /** The ID range for result notifications when parallel, outside the slot IDs; used cyclically, with at most this many kept at once. */
        internal const val ID_RESULT_FIRST = 10_000
        internal const val RESULT_IDS = 1_000
        internal val resultCount = AtomicInteger()
        internal val TAKEOVER_QUEUED: String get() = tr(R.string.task_takeover_queued)
        internal const val TAG = "MikaTask"
        val TOO_OLD: String get() = tr(R.string.task_too_old)
        internal val PREPARING: String get() = tr(R.string.runtime_preparing)
        val SHELL_DIED: String get() = tr(R.string.task_shell_died)

        /** Which of the loop's states may be written out by publishing. */
        internal fun loopMayPublish(state: TaskState) = state.isActive()

        /** Starts only the persisted, claimable workflow attempt named by this ID. */
        fun startWorkflow(context: Context, attemptId: String) {
            check(!FeatureFlags.get(context).quitRequested())
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_WORKFLOW)
                .putExtra(EXTRA_WORKFLOW_ATTEMPT, attemptId))
        }

        /**
         * Start a task; queue it when another conversation is busy. A non-empty messageId is handled as an interjection.
         * [goal] is the user's own words; [brief] is the supplementary note attached when the front desk dispatches work, which enters the task context as Claire's note rather than the user's words.
         */
        fun start(context: Context, goal: String, conversationId: String? = null, messageId: String? = null, brief: String? = null) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(
                Intent(context, TaskService::class.java).setAction(ACTION_START)
                    .apply { startExtras(goal, conversationId, messageId, brief).forEach { (k, v) -> putExtra(k, v) } },
            )
        }

        /** The fields carried by the request [start] sends; absent ones are omitted. */
        internal fun startExtras(goal: String, conversationId: String?, messageId: String?, brief: String?): List<Pair<String, String>> =
            listOfNotNull(EXTRA_GOAL to goal, conversationId?.let { EXTRA_CONVERSATION to it },
                messageId?.let { EXTRA_MESSAGE_ID to it }, brief?.let { EXTRA_BRIEF to it })

        /**
         * The user explicitly chose "queue": first write the user's words into the persistent ledger on the caller side, then let the service claim it.
         * If the system blocks the background service start, the queued item is already safely stored and runs the next time the conversation is opened.
         * With [startIfIdle] false it only persists: the original task is still waiting for a previous unrelated task to tear down, so it must not start ahead of it.
         */
        fun enqueueFollowUp(context: Context, conversationId: String, messageId: String, text: String,
            source: QueuedSource = QueuedSource.Explicit, startIfIdle: Boolean = true): Boolean {
            if (text.isBlank() || FeatureFlags.get(context).quitRequested()) return false
            val app = context.applicationContext as? com.wangzan101.mika.MikaApp ?: return false
            val item = QueuedFollowUp(messageId, conversationId, text, System.currentTimeMillis(), source)
            if (!app.followUpQueue.add(item)) return false
            if (startIfIdle) runCatching {
                context.startService(
                    Intent(context, TaskService::class.java).setAction(ACTION_ENQUEUE_FOLLOWUP)
                        .putExtra(EXTRA_GOAL, text).putExtra(EXTRA_CONVERSATION, conversationId)
                        .putExtra(EXTRA_MESSAGE_ID, messageId).putExtra(EXTRA_SOURCE, source.name),
                )
            }
            return true
        }

        /** When a conversation is opened: if it still has a queued next item and no task is running, have the service take one and start it. */
        fun drainFollowUps(context: Context, conversationId: String) {
            if (FeatureFlags.get(context).quitRequested()) return
            val app = context.applicationContext as? com.wangzan101.mika.MikaApp ?: return
            if (!app.followUpQueue.contains(conversationId)) return
            runCatching {
                context.startService(
                    Intent(context, TaskService::class.java).setAction(ACTION_DRAIN_FOLLOWUPS)
                        .putExtra(EXTRA_CONVERSATION, conversationId),
                )
            }
        }

        /** [checkGoal] is the read-only check goal, and [condition] is the user's own condition text meant for people; the action still uses [goal]. */
        fun startTrigger(context: Context, goal: String, threadId: String, windowKey: String,
            checkGoal: String? = null, condition: String? = null) {
            if (FeatureFlags.get(context).quitRequested()) return
            val checking = checkGoal != null
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_TRIGGER)
                .putExtra(EXTRA_GOAL, checkGoal ?: goal).putExtra(EXTRA_CONVERSATION, threadId)
                .putExtra(EXTRA_TRIGGER_KEY, windowKey).putExtra(EXTRA_TRIGGER_THREAD, threadId)
                .apply { if (checking) putExtra(EXTRA_TRIGGER_ACTION, goal).putExtra(EXTRA_TRIGGER_CONDITION, condition) })
        }

        /** Tasks submitted from a channel keep their reply address; the message ID is persisted by the registry for de-duplication. */
        fun startChannel(context: Context, peer: ChannelPeer, messageId: String, goal: String) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_CHANNEL)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, peer.threadId)
                .putExtra(EXTRA_CHANNEL_ID, peer.channelId).putExtra(EXTRA_PEER_ID, peer.peerId)
                .putExtra(EXTRA_CHANNEL_MESSAGE_ID, messageId))
        }

        /** A task submitted from a computer ([RemoteTaskHub]): recorded in the [conversation] conversation, with the origin recorded as MCP. */
        fun startMcp(context: Context, goal: String, conversation: String, externalId: String, attendance: Attendance) {
            if (FeatureFlags.get(context).quitRequested()) return
            context.startForegroundService(Intent(context, TaskService::class.java).setAction(ACTION_START_MCP)
                .putExtra(EXTRA_GOAL, goal).putExtra(EXTRA_CONVERSATION, conversation)
                .putExtra(EXTRA_MCP_ID, externalId).putExtra(EXTRA_ATTENDANCE, attendance.name))
        }

        fun cancelMcp(context: Context, externalId: String) {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            if (state.runs.value.none { it.mcpExternalId == externalId }) return
            context.startService(Intent(context, TaskService::class.java).setAction(ACTION_CANCEL_MCP)
                .putExtra(EXTRA_MCP_ID, externalId))
        }

        /** [runKey] see [send]; in-app buttons carry the task ID of the conversation on screen, so they never land on another task. */
        fun cancel(context: Context, runKey: String? = null) = send(context, ACTION_CANCEL, runKey = runKey)

        /** The user tapped "Continue": the app that was moved away is moved back to Claire's screen. */
        fun resume(context: Context, runKey: String? = null) = send(context, ACTION_RESUME, runKey = runKey)

        /** "I'll handle it": moves the app to the main screen and restores its sound. */
        fun takeOver(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_TAKE_OVER, waitKey, runKey)

        /** "Handled": moves it back to the virtual screen and mutes it again, and the Agent reads the screen to verify before continuing. */
        fun handBack(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_HAND_BACK, waitKey, runKey)

        /** "I finished it myself": moves it back to the virtual screen, and the Agent only verifies the result and ends. */
        fun finishedMyself(context: Context, waitKey: Int? = null, runKey: String? = null) =
            send(context, ACTION_FINISHED_MYSELF, waitKey, runKey)

        /** "Skip": this step is not handled, and the Agent, receiving "no hand-back happened", finds another way or reports honestly. */
        fun skip(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_SKIP, waitKey, runKey)

        fun putAside(context: Context, waitKey: Int? = null, runKey: String? = null) = send(context, ACTION_PUT_ASIDE, waitKey, runKey)

        /** Answers the Agent's question (ask_user). */
        fun answer(context: Context, text: String, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, text)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** Approves the request numbered [key] ([ConfirmationNotifier.requestKey]); it does not count if a different request has already replaced it. */
        fun approve(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_APPROVE, key, runKey)

        /** Decline: the Agent stops trying this operation, reports the current situation and ends. */
        fun decline(context: Context, key: Int, runKey: String? = null) = confirm(context, ACTION_DECLINE, key, runKey)

        internal fun confirm(context: Context, action: String, key: Int, runKey: String?) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).putExtra(ConfirmationNotifier.EXTRA_REQUEST, key)
                    .apply { if (runKey != null) putExtra(EXTRA_RUN, runKey) },
            )
        }

        /** The task's state right now; Idle when the task is gone (the action is void). With a null [runKey] it is the shown task. */
        internal fun stateFor(context: Context, runKey: String?): TaskState {
            val state = (context.applicationContext as com.wangzan101.mika.MikaApp).taskState
            return if (runKey == null) state.state.value else state.runs.value.firstOrNull { it.key == runKey }?.state?.value ?: TaskState.Idle
        }

        /** With a null [runKey] it acts on the shown task, otherwise only on that task. */
        internal fun send(context: Context, action: String, waitKey: Int? = null, runKey: String? = null) {
            if (!stateFor(context, runKey).isActive()) return
            context.startService(
                Intent(context, TaskService::class.java).setAction(action).apply {
                    if (waitKey != null) putExtra(EXTRA_WAIT_KEY, waitKey)
                    if (runKey != null) putExtra(EXTRA_RUN, runKey)
                },
            )
        }

        internal fun TaskState.isActive() = when (this) {
            TaskState.Idle, is TaskState.Succeeded, is TaskState.Failed, TaskState.Cancelled -> false
            else -> true
        }

        internal fun resultDelivery(deliveryId: String, threadId: String, end: TaskState, failureStreak: Int?): Delivery.Result {
            val (title, text) = describe(end)
            return if (failureStreak == null) Delivery.Result(deliveryId, threadId, text, title = title)
            else Delivery.Result(deliveryId, threadId, "$text\n${tr(R.string.trigger_failed_streak_hint)}",
                title = tr(R.string.trigger_failed_streak, failureStreak))
        }

        /** Title and body for notifications; [appLabel] turns a package name into an app name. */
        internal fun describe(state: TaskState, appLabel: (String) -> String = { it }): Pair<String, String> = when (state) {
            TaskState.Idle -> "Claire" to ""
            is TaskState.Running -> tr(R.string.task_running) to (state.progress?.let(StatusCopy::progress) ?: when {
                state.step == 0 -> state.note
                else -> tr(R.string.task_step_note, state.step, state.note).trimEnd(':', ' ')
            })
            is TaskState.Held -> when {
                state.reason == TaskGate.LOCKED -> tr(R.string.task_wait_unlock) to tr(R.string.task_wait_unlock_body)
                state.reason.queued -> tr(R.string.chat_queued) to state.reason.why
                state.reason.resumable -> tr(R.string.chat_paused) to state.reason.why
                else -> tr(R.string.chat_waiting_for_you) to state.reason.why
            }
            is TaskState.WaitingTakeover -> tr(R.string.takeover_title) to state.message
            is TaskState.WaitingInput -> tr(R.string.input_title) to state.prompt
            is TaskState.WaitingConfirmation -> ConfirmationCopy.title(state.request) to ConfirmationCopy.body(state.request, appLabel(state.request.app))
            is TaskState.Succeeded ->
                if (!state.doubtful) tr(R.string.task_done) to state.answer
                else tr(R.string.chat_unverified) to (state.note?.let { tr(R.string.task_reason, it) + "\n" }.orEmpty() + state.answer)
            is TaskState.Failed -> tr(R.string.task_not_done) to state.reason
            TaskState.Cancelled -> tr(R.string.task_stopped) to tr(R.string.task_stopped_body)
        }
    }
}
