package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverActivity
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.settings.ui.StatusCopy
import kotlinx.coroutines.cancel
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.INPUT_TIMEOUT_MINUTES
import com.wangzan101.mika.task.TaskService.Companion.ACTION_CANCEL
import com.wangzan101.mika.task.TaskService.Companion.ACTION_RESUME
import com.wangzan101.mika.task.TaskService.Companion.ACTION_TAKE_OVER
import com.wangzan101.mika.task.TaskService.Companion.ACTION_HAND_BACK
import com.wangzan101.mika.task.TaskService.Companion.ACTION_FINISHED_MYSELF
import com.wangzan101.mika.task.TaskService.Companion.ACTION_SKIP
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_WAIT_KEY
import com.wangzan101.mika.task.TaskService.Companion.ACTION_ANSWER
import com.wangzan101.mika.task.TaskService.Companion.ACTION_APPROVE
import com.wangzan101.mika.task.TaskService.Companion.ACTION_DECLINE
import com.wangzan101.mika.task.TaskService.Companion.FAILURE_STREAK_NOTICE
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_ANSWER
import com.wangzan101.mika.task.TaskService.Companion.EXTRA_RUN
import com.wangzan101.mika.task.TaskService.Companion.KEY_REPLY
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.ID_PROGRESS
import com.wangzan101.mika.task.TaskService.Companion.SLOT_STRIDE
import com.wangzan101.mika.task.TaskService.Companion.TAKEOVER_QUEUED
import com.wangzan101.mika.task.TaskService.Companion.TAG
import com.wangzan101.mika.task.TaskService.Companion.PREPARING
import com.wangzan101.mika.task.TaskService.Companion.cancel
import com.wangzan101.mika.task.TaskService.Companion.send
import com.wangzan101.mika.task.TaskService.Companion.resultDelivery
import com.wangzan101.mika.task.TaskService.Companion.describe

// Task service: progress, attention and result notifications
/** The brief moment after handing back while the loop hasn't moved on: no more takeover buttons are shown. */
internal fun TaskService.awaitingUser(run: TaskRun, state: TaskState) =
    state !is TaskState.WaitingTakeover || run.takeoverStage.value != Takeover.Stage.None

/** Only one app is handed over on the main screen at a time: no takeover button is offered while this takeover is queued behind another task. */
internal fun TaskService.takeoverFirst(run: TaskRun) = run.takeover?.let(takeoverQueue::isFirst) ?: true

/** Enter the foreground with [run]'s progress notification ID; use the first slot's ID when there is no task object yet. */
internal fun TaskService.startInForeground(run: TaskRun?, n: Notification) {
    val nid = run?.let { id(ID_PROGRESS, it) } ?: ID_PROGRESS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(nid, n)
    }
}

internal fun TaskService.progressNotification(run: TaskRun, state: TaskState): Notification {
    val stage = run.chipStages.current
    val chip = if (Build.VERSION.SDK_INT >= 36) taskChip(run.provenance.origin, state, stage) else null
    val text = if (chip != null && state is TaskState.Running) chipDetail(state, stage)
        else describe(state) { ConfirmationNotifier.appLabel(this, it) }.second
    val warning = run.muteFailedFor?.let { "\n" + tr(R.string.task_may_make_sound) }.orEmpty() +
        (if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) "\n$TAKEOVER_QUEUED" else "")
    val b = Notification.Builder(this, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.task_notif_title, ScreenCopy.plain(run.triggerCondition ?: run.goal)))
        .setContentText(text + warning)
        .setStyle(Notification.BigTextStyle().bigText(text + warning))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(run.conversationId))
    if (Build.VERSION.SDK_INT >= 36 && chip != null) {
        b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        b.setShortCriticalText(chip)
        // The public EXTRA_REQUEST_PROMOTED_ONGOING constant is exposed by SDK 36.1.
        b.extras.putBoolean("android.requestPromotedOngoing", true)
    }
    val active = center.running().size
    if (active >= 2) b.setSubText(trPlural(R.plurals.task_active, active))
    when (state) {
        is TaskState.WaitingTakeover ->
            if (awaitingUser(run, state) && takeoverFirst(run)) takeoverActions(run).forEach(b::addAction)
            else b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
        is TaskState.WaitingInput -> {
            b.addAction(replyAction(run, state.options))
            b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
        }
        else -> {
            if (state is TaskState.Held && state.reason.resumable) b.addAction(action(run, tr(R.string.chat_continue), ACTION_RESUME, 1))
            b.addAction(action(run, tr(R.string.chat_stop), ACTION_CANCEL, 2))
        }
    }
    return b.build()
}

/** Must enter the foreground right away when busy but without a task object yet (e.g. teardown was interrupted). */
internal fun TaskService.preparingNotification(): Notification = Notification.Builder(this, CHANNEL_PROGRESS)
    .setSmallIcon(R.drawable.ic_notification)
    .setContentTitle("Claire")
    .setContentText(PREPARING)
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setContentIntent(openApp())
    .build()

internal fun TaskService.answerTooLate() = Handler(Looper.getMainLooper()).post {
    Toast.makeText(this, getString(R.string.input_too_late, INPUT_TIMEOUT_MINUTES), Toast.LENGTH_LONG).show()
}

/**
 * The prominent extra notification sent while waiting for a takeover or an answer; none for the other states.
 * It is still sent while the floating capsule is showing on a lit screen, but quietly into the notification shade (the capsule has already turned orange, see [alertQuietly]); when the screen is locked, or the capsule is off or can't be drawn, it pops up as usual.
 */
internal fun TaskService.attentionNotification(run: TaskRun, state: TaskState): Notification? {
    if (!awaitingUser(run, state)) return null
    val quiet = (application as com.wangzan101.mika.MikaApp).capsule.showing.value
    if (state is TaskState.WaitingConfirmation) {
        return ConfirmationNotifier.build(
            this, CHANNEL_ATTENTION, state, TaskService::class.java, ACTION_APPROVE, ACTION_DECLINE, openApp(run.conversationId),
            codeOffset = SLOT_STRIDE * run.slot, runExtra = EXTRA_RUN to run.key, quiet = quiet,
        )
    }
    if (state is TaskState.WaitingTakeover && !takeoverFirst(run)) return null
    val (title, text) = when (state) {
        is TaskState.WaitingTakeover -> {
            val extra = when (run.takeover?.problem?.value) {
                Takeover.Problem.MoveToMainFailed -> getString(R.string.takeover_move_to_main_failed)
                Takeover.Problem.WebBusy -> getString(R.string.takeover_web_busy)
                Takeover.Problem.MoveBackFailed -> getString(R.string.takeover_move_back_failed)
                null -> if (run.takeoverStage.value == Takeover.Stage.HandedOver) getString(R.string.takeover_handed_over_hint) else null
            }
            val payment = StatusCopy.paymentLines(state.item, state.amount, state.address)
            val behind = run.takeover?.let(takeoverQueue::behind) ?: 0
            val waiting = if (behind > 0) trPlural(R.plurals.task_behind, behind) else null
            takeoverTitle(state.reason) to listOfNotNull(state.message, payment, extra, waiting).joinToString("\n")
        }
        is TaskState.WaitingInput -> getString(R.string.input_title) to
            "${state.prompt}\n${getString(R.string.no_answer_note, INPUT_TIMEOUT_MINUTES)}"
        else -> return null
    }
    val b = Notification.Builder(this, CHANNEL_ATTENTION)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setCategory(Notification.CATEGORY_REMINDER)
        .setOnlyAlertOnce(true) // don't ring again when buttons change within the same wait; it is removed when leaving the wait, so the next one alerts anew
        .setContentIntent(openApp(run.conversationId))
    if (quiet) b.alertQuietly()
    when (state) {
        is TaskState.WaitingTakeover -> takeoverActions(run).forEach(b::addAction)
        is TaskState.WaitingInput -> {
            b.addAction(replyAction(run, state.options))
            // Android notifications show at most 3 buttons, so this is "Reply" plus the first two options;
            // all options go into the reply's quick choices (RemoteInput choices), so the extra ones can be picked there
            state.options.orEmpty().take(2).forEachIndexed { i, o -> b.addAction(answerAction(run, o, i)) }
        }
        else -> Unit
    }
    return b.build()
}

internal fun TaskService.takeoverTitle(reason: String): String {
    val label = when (reason) {
        "captcha" -> R.string.takeover_reason_captcha
        "login" -> R.string.takeover_reason_login
        "access_blocked" -> R.string.takeover_reason_access_blocked
        "payment" -> R.string.takeover_reason_payment
        "decision" -> R.string.takeover_reason_decision
        else -> return getString(R.string.takeover_title)
    }
    return getString(R.string.takeover_title_reason, getString(label))
}

internal fun TaskService.takeoverActions(run: TaskRun): List<Notification.Action> {
    val cancel = action(run, getString(R.string.action_cancel_task), ACTION_CANCEL, 2)
    return if (run.takeoverStage.value == Takeover.Stage.HandedOver) listOf(
        takeoverAction(run, getString(R.string.action_hand_back), ACTION_HAND_BACK, 4),
        takeoverAction(run, getString(R.string.action_finished_myself), ACTION_FINISHED_MYSELF, 5),
        cancel,
    ) else listOf(
        takeoverAction(run, getString(R.string.action_take_over), ACTION_TAKE_OVER, 3),
        takeoverAction(run, getString(R.string.action_skip), ACTION_SKIP, 7),
        cancel,
    )
}

/** Goes through an Activity with no UI: launching an Activity from a notification is what collapses the notification shade. */
internal fun TaskService.takeoverAction(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
    val pi = PendingIntent.getActivity(
        this, code + SLOT_STRIDE * run.slot, Intent(this, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_RUN, run.key)
            .apply { run.takeover?.let { putExtra(EXTRA_WAIT_KEY, it.waitKey) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, title, pi).build()
}

internal fun TaskService.replyAction(run: TaskRun, options: List<String>?): Notification.Action {
    val input = RemoteInput.Builder(KEY_REPLY).setLabel(getString(R.string.reply_hint))
        .apply { if (!options.isNullOrEmpty()) setChoices(options.toTypedArray()) }
        .build()
    // The system has to fill the reply into the Intent, so only a mutable PendingIntent works (the Intent is explicit)
    val pi = PendingIntent.getService(
        this, 6 + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_RUN, run.key),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, getString(R.string.action_reply), pi).addRemoteInput(input).build()
}

internal fun TaskService.answerAction(run: TaskRun, option: String, index: Int): Notification.Action {
    val pi = PendingIntent.getService(
        this, 10 + index + SLOT_STRIDE * run.slot,
        Intent(this, TaskService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ANSWER, option).putExtra(EXTRA_RUN, run.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, option, pi).build()
}

internal suspend fun TaskService.prepareResult(run: TaskRun, end: TaskState, deliveryId: String): Delivery.Result {
    val result = resultDelivery(deliveryId, run.conversationId, end, failureStreak(run, end))
    val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
    ResultDelivery.prepare(outbox, channelFor(peer, run.triggerWindowKey != null), peer, result, run.sharedFiles.value)
    return result
}

internal suspend fun TaskService.notifyResult(run: TaskRun, result: Delivery.Result): DeliveryState {
    val peer = run.channelPeer ?: ChannelPeer(LocalChannel.ID, "phone-owner", run.conversationId)
    val started = SystemClock.elapsedRealtime()
    val delivered = ResultDelivery.send(outbox, channelFor(peer, run.triggerWindowKey != null), peer, result)
    val finished = SystemClock.elapsedRealtime()
    val sinceReady = if (run.resultReadyAtMs > 0) finished - run.resultReadyAtMs else -1
    Log.i(TAG, "result $delivered: delivery ${finished - started} ms, since ready $sinceReady ms")
    return delivered
}

/**
 * A scheduled task failed again: once the consecutive failures including this one reach [FAILURE_STREAK_NOTICE] or more, this is the total count (including this one):
 * the notification title states how many times in a row it failed and suggests pausing or editing, so the user doesn't just see an ordinary failure every time. When the record can't be read, no streak is assumed.
 */
internal fun TaskService.failureStreak(run: TaskRun, end: TaskState): Int? {
    val key = run.triggerWindowKey ?: return null
    if (end is TaskState.Succeeded || end == TaskState.Cancelled) return null
    val streak = runCatching { TriggerRuntime.failureStreakBefore(this, key) }.getOrDefault(0) + 1
    return streak.takeIf { it >= FAILURE_STREAK_NOTICE }
}

internal fun TaskService.action(run: TaskRun, title: String, action: String, code: Int): Notification.Action {
    val pi = PendingIntent.getService(
        this, code + SLOT_STRIDE * run.slot, Intent(this, TaskService::class.java).setAction(action).putExtra(EXTRA_RUN, run.key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Action.Builder(null, title, pi).build()
}

internal fun TaskService.openApp(threadId: String? = null) = openApp(this, threadId)
