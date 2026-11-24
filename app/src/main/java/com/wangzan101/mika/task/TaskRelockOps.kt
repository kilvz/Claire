package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import com.wangzan101.mika.R
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import com.wangzan101.mika.task.TaskService.Companion.ensureChannels
import com.wangzan101.mika.task.TaskService.Companion.openApp
import com.wangzan101.mika.task.TaskService.Companion.nextResultId
import com.wangzan101.mika.task.TaskService.Companion.CHANNEL_ATTENTION
import com.wangzan101.mika.task.TaskService.Companion.ID_RELOCK
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_IDLE_GRACE_MS
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_POLL_TRIES
import com.wangzan101.mika.task.TaskService.Companion.RELOCK_POLL_MS
import com.wangzan101.mika.task.TaskService.Companion.cancel

// Task service: unlocking during tasks and re-locking after they end
/** A task-driven unlock has completed: use the main-screen interaction count at this moment as the baseline and post the persistent reminder (see [startUnlockGuard]). */
internal fun TaskService.noteTaskUnlock() = taskRelock.unlocked(
    com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.mainInteractionCount())

/**
 * Called at task teardown ([ignore] being the one finishing). If tasks are still running or queued, wait; only when all are done is a decision made:
 * already locked → settle; can't lock (accessibility unavailable, or a page is still open) → warn; the user touched the main screen while waiting → wait for an idle stretch and decide again;
 * otherwise re-lock through the global action and poll for system confirmation, switching to the persistent warning if two attempts go unconfirmed.
 */
internal suspend fun TaskService.finishTaskRelock(ignore: TaskRun? = null) = relockMutex.withLock {
    val keyguard = getSystemService(android.app.KeyguardManager::class.java)
    fun count() = com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.mainInteractionCount()
    fun runsRemain() = center.runs().any { it !== ignore }
    fun accessibility() = com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility.instance.value
    fun decision() = taskRelock.decide(
        runsRemain = runsRemain(), locked = systemLockConfirmed(keyguard), interactions = count(),
        canLock = accessibility() != null && keeper.page.value == null,
    )
    var result = decision()
    if (result == TaskRelock.Decision.WAIT_IDLE) {
        val before = count()
        delay(RELOCK_IDLE_GRACE_MS)
        if (!taskRelock.acceptIdle(before, count())) { notifyRelockFailed(); return@withLock }
        result = decision()
    }
    when (result) {
        TaskRelock.Decision.NONE -> { clearUnlockGuardIfSafe(); return@withLock }
        TaskRelock.Decision.WAIT -> return@withLock
        TaskRelock.Decision.WARN, TaskRelock.Decision.WAIT_IDLE -> { notifyRelockFailed(); return@withLock }
        TaskRelock.Decision.LOCK -> Unit
    }
    repeat(2) {
        // A new task arrived between retries, or the user touched the phone: this re-lock always yields and the decision is made again
        when (decision()) {
            TaskRelock.Decision.NONE -> { clearUnlockGuardIfSafe(); return@withLock }
            TaskRelock.Decision.WAIT -> return@withLock
            TaskRelock.Decision.WARN, TaskRelock.Decision.WAIT_IDLE -> { notifyRelockFailed(); return@withLock }
            TaskRelock.Decision.LOCK -> Unit
        }
        val accepted = accessibility()?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
        if (accepted) repeat(RELOCK_POLL_TRIES) {
            if (systemLockConfirmed(keyguard)) {
                taskRelock.confirmed()
                clearUnlockGuardIfSafe()
                return@withLock
            }
            delay(RELOCK_POLL_MS)
        }
    }
    notifyRelockFailed()
}

/** Locked in the system's sense: the keyguard is showing; with a secure lock, the device layer must be locked too. */
internal fun TaskService.systemLockConfirmed(keyguard: android.app.KeyguardManager): Boolean =
    keyguard.isKeyguardLocked && (!keyguard.isKeyguardSecure || keyguard.isDeviceLocked)

/**
 * The persistent reminder posted during unlocking: it stays in the notification shade even if the task is interrupted or the process dies, reminding the user to check the lock screen.
 * If notifications can't be sent (permission or channel disabled) the re-lock obligation can't be met, so the caller doesn't unlock on that basis.
 */
internal fun TaskService.startUnlockGuard(): Boolean {
    ensureChannels(this)
    val manager = getSystemService(NotificationManager::class.java)
    val channel = manager.getNotificationChannel(CHANNEL_ATTENTION) ?: return false
    if (!manager.areNotificationsEnabled() || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
    return runCatching {
        manager.notify(ID_RELOCK, Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.relock_guard_title))
            .setContentText(tr(R.string.relock_guard_body))
            .setStyle(Notification.BigTextStyle().bigText(tr(R.string.relock_guard_body)))
            .setContentIntent(openApp()).setOngoing(true).build())
        true
    }.getOrDefault(false)
}

/** Remove the persistent reminder when no re-lock obligation is pending; it must not be removed while one is still pending (unlocked by another task). */
internal fun TaskService.clearUnlockGuardIfSafe() {
    if (!taskRelock.hasPending()) runCatching { getSystemService(NotificationManager::class.java).cancel(ID_RELOCK) }
}

/** Re-locking couldn't be confirmed: the persistent warning takes over under the same ID and stays until the user has seen it. */
internal fun TaskService.notifyRelockFailed() {
    ensureChannels(this)
    runCatching {
        getSystemService(NotificationManager::class.java).notify(ID_RELOCK,
            Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.relock_failed_title))
                .setContentText(tr(R.string.relock_failed_body))
                .setStyle(Notification.BigTextStyle().bigText(tr(R.string.relock_failed_body)))
                .setContentIntent(openApp()).setOngoing(true).build())
    }
}

/** After an unlock failure, switch to waiting for the user to unlock manually: send a reminder that taps back into the conversation ([status] being a credential-free status code). */
internal fun TaskService.notifyUnlockRecoveryNeeded(conversationId: String, status: String) {
    ensureChannels(this)
    val safeStatus = status.takeIf { it.matches(Regex("[a-z_]{1,50}")) }.orEmpty()
    val message = tr(R.string.unlock_recovery_body) + if (safeStatus.isBlank()) "" else " ($safeStatus)"
    runCatching {
        getSystemService(NotificationManager::class.java).notify(nextResultId(),
            Notification.Builder(this, CHANNEL_ATTENTION).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(R.string.unlock_recovery_title)).setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp(conversationId)).setAutoCancel(true).build())
    }
}
