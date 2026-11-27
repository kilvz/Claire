package com.wangzan101.mika.calls

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.UserManager
import androidx.annotation.MainThread
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.wangzan101.mika.calls.CallRuntime.Companion.STANDBY_START_MS

// Call runtime: background standby foreground service and auto-answer pause
/** Initial authorization comes only from this explicit action in the unlocked phone UI. */
suspend fun CallRuntime.startBackgroundStandby(): CallActionResult = onMain {
    val config = settings.state.value
    if (!interactiveForeground() || !userUnlocked()) return@onMain fail("Unlock the phone and start background standby on the Phone screen")
    if (!CallStandbyPolicy.optedIn(config, userUnlocked())) return@onMain fail("Turn on auto-answer and finish the call setup first")
    autoAnswerBlocker(config)?.let { return@onMain fail(it) }
    if (!CallStandbyService.notificationsAvailable(app, create = true)) return@onMain fail("Allow background standby notifications")
    if (isIncomingStandbyRunning()) return@onMain ok("Background standby started")
    if (standbyStarting) return@onMain fail("Android is starting background standby")
    if (!settings.authorizeBackgroundStandby()) return@onMain fail("Could not save the background answering authorization; try again")
    if (!requestCallForegroundService(incoming = true)) return@onMain fail(standbyStartError ?: "Background standby did not start")
    state.first { !it.backgroundStandbyStarting }
    if (isIncomingStandbyRunning()) ok("Background standby started") else fail(standbyStartError ?: "Background standby did not start")
}

internal fun CallRuntime.requestCallForegroundService(incoming: Boolean): Boolean {
    if (standbyStarting) return !incoming || standbyIncomingRequested
    standbyIncomingRequested = incoming
    standbyStarting = true
    standbyStartError = null
    val startId = UUID.randomUUID().toString()
    standbyStartId = startId
    publish()
    if (runCatching {
        val intent = CallStandbyService.startIntent(app, startId)
        if (standbyService != null) app.startService(intent) else app.startForegroundService(intent)
    }.getOrNull() == null) {
        onStandbyStartFailed("Android did not allow background standby to start; try again in the app")
        return false
    }
    // This deadline belongs to the runtime, even if the initiating screen is dismissed.
    standbyStartTimeout = scope.launch {
        delay(STANDBY_START_MS)
        if (standbyStarting) {
            standbyStartTimeout = null
            onStandbyStartFailed("Android has not confirmed that background standby started; try again")
        }
    }
    return true
}

internal fun CallRuntime.isStandbyOptedIn(): Boolean = CallStandbyPolicy.optedIn(settings.state.value, userUnlocked())

/** A full exit withdraws the grant until the owner opens Mika again, so nothing revives standby meanwhile. */
internal fun CallRuntime.hasAuthorizedIncomingStandby(): Boolean = isStandbyOptedIn() &&
    settings.state.value.backgroundStandbyAuthorized && !FeatureFlags.get(app).quitRequested()

/** Schedules or cancels the periodic recovery check to match the current grant. */
@MainThread
internal fun CallRuntime.refreshStandbyRecovery() {
    CallStandbyRecoveryJob.reconcile(app, hasAuthorizedIncomingStandby())
}

/** Called by the periodic check: restores only the owner's incoming standby, never outgoing work. */
@MainThread
internal fun CallRuntime.restoreAuthorizedStandbyFromSystem() {
    refreshStandbyRecovery()
    if (mayRequestIncomingStandby(StandbyTrigger.Scheduled)) requestCallForegroundService(incoming = true)
}

/** Reads the runtime once and applies the shared start rule for [trigger]. */
internal fun CallRuntime.mayRequestIncomingStandby(trigger: StandbyTrigger): Boolean {
    val authorized = hasAuthorizedIncomingStandby()
    // Cheap answer first: without the grant nothing else needs reading.
    if (!authorized) return false
    return CallStandbyPolicy.mayRequestIncomingStandby(StandbyStartSnapshot(
        authorized = true,
        autoAnswerReady = autoAnswerBlocker(settings.state.value) == null,
        notificationsAllowed = CallStandbyService.notificationsAvailable(app, create = true),
        configuring = configuring,
        starting = standbyStarting,
        running = standbyService?.stickyAutoAnswer == true,
        callWork = work != null,
        lockedDial = lockedDial != null,
        liveCall = liveCalls().isNotEmpty(),
        unobservedManagedCall = unobservedManagedCall,
        outgoingForegroundWork = hasOutgoingForegroundWork(),
        interactiveForeground = trigger == StandbyTrigger.Visible && interactiveForeground(),
        systemAllowsScheduledStart = trigger == StandbyTrigger.Scheduled && CallStandbyRecoveryJob.systemAllowsScheduledStart(app),
    ), trigger)
}

internal fun CallRuntime.isIncomingStandbyRunning(): Boolean = standbyService?.stickyAutoAnswer == true && hasAuthorizedIncomingStandby()

internal fun CallRuntime.hasOutgoingForegroundWork(): Boolean = CallStandbyPolicy.foregroundOutgoing(
    userUnlocked(), settings.state.value.enabled, lockedDial != null, work?.direction,
)

internal fun CallRuntime.isStandbyStartRequested(requestId: String?): Boolean = standbyStarting && requestId != null && requestId == standbyStartId

internal fun CallRuntime.isIncomingStandbyStartRequested(): Boolean = standbyIncomingRequested

internal fun CallRuntime.onStandbyStarted(service: CallStandbyService) {
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyService = service
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyStartError = null
    standbyRunning.value = true
    publish()
}

internal fun CallRuntime.onStandbyStartFailed(reason: String) {
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyStartError = reason
    lockedDial?.let { finishLockedDial(it, "The background phone service did not start; the locked-screen dial was not performed") }
    publish()
}

internal fun CallRuntime.onStandbyStopped(service: CallStandbyService) {
    if (standbyService !== service) return
    standbyService = null
    standbyRunning.value = false
    lockedDial?.let { finishLockedDial(it, "The background phone service stopped; the locked-screen dial was not performed") }
    if (work?.let { (it.direction == CallDirection.OUTGOING) || (it.automatic && it.answerSent && !interactiveForeground()) } == true) {
        cancelWork("The background phone service stopped; the system is handling the call")
    }
    cancelPendingIfStandbyLost()
    publish()
}

internal fun CallRuntime.stopBackgroundStandby() {
    val running = standbyService != null
    standbyStartTimeout?.cancel()
    standbyStartTimeout = null
    standbyStarting = false
    standbyIncomingRequested = false
    standbyStartId = null
    standbyService = null
    standbyRunning.value = false
    // Android can crash the process if a startForegroundService request is stopped before
    // it is acknowledged with startForeground. An in-flight request acknowledges then stops itself.
    if (running) runCatching { app.stopService(CallStandbyService.startIntent(app)) }
    cancelPendingIfStandbyLost()
}

internal fun CallRuntime.stopBackgroundStandbyIfUnneeded() {
    if (!hasOutgoingForegroundWork() && (standbyService != null || standbyStarting) &&
        !(hasAuthorizedIncomingStandby() && (standbyService?.stickyAutoAnswer == true || standbyIncomingRequested))) {
        stopBackgroundStandby()
    }
}

internal fun CallRuntime.cancelPendingIfStandbyLost() {
    if (!canAutomaticallyAnswerHere() && work?.let { it.automatic && !it.answerSent && it.stopReason.value == null } == true) {
        cancelWork("Background standby is unavailable; the system phone app is handling this incoming call")
    }
}

internal fun CallRuntime.userUnlocked(): Boolean =
    runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked == true }.getOrDefault(false)

internal fun CallRuntime.interactiveForeground(): Boolean = runCatching {
    CallStandbyPolicy.interactiveForeground(
        visible = app.foreground.visible.value,
        interactive = app.getSystemService(PowerManager::class.java)?.isInteractive == true,
        keyguardShowing = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
    )
}.getOrDefault(false)

internal fun CallRuntime.keyguardLocked(): Boolean =
    runCatching { app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }.getOrDefault(false)

internal fun CallRuntime.canAutomaticallyAnswerHere(): Boolean = CallStandbyPolicy.allowsAutomaticAnswer(
    userUnlocked = userUnlocked(), interactiveForeground = interactiveForeground(), serviceRunning = isIncomingStandbyRunning(),
    notificationsAllowed = standbyService != null && CallStandbyService.notificationsAvailable(app),
)

suspend fun CallRuntime.pauseAutoAnswer(minutes: Int): CallActionResult = onMain {
    if (minutes !in 1..CallPolicy.MAX_AUTO_ANSWER_PAUSE_MINUTES) {
        return@onMain fail("The pause must be between 1 minute and 24 hours")
    }
    if (!settings.state.value.enabled || !settings.state.value.autoAnswer) {
        return@onMain fail("Auto-answer is not on yet")
    }
    saveAutoPause(System.currentTimeMillis() + minutes * 60_000L)
}

suspend fun CallRuntime.resumeAutoAnswer(): CallActionResult = onMain { saveAutoPause(null) }

internal suspend fun CallRuntime.saveAutoPause(until: Long?): CallActionResult {
    if (autoPauseWriting) return fail("Auto-answer settings are being saved")
    autoPauseWriting = true
    if (until != null) {
        // Existing calls handled by AI stay connected; a pending auto-answer is left alone.
        if (work?.let { it.automatic && !it.answerSent } == true) {
            cancelWork("Auto-answer is paused; the system phone app is handling this incoming call")
        }
        liveCalls().filter { it.phase == CallPhase.RINGING }.forEach { it.autoTried = true }
    }
    val saved = try {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                synchronized(settings) {
                    settings.save(settings.state.value.copy(autoAnswerPausedUntilMs = until))
                }
            }.isSuccess
        }
    } finally {
        autoPauseWriting = false
        publish()
    }
    return if (!saved) fail("Could not save the auto-answer pause setting")
    else ok(if (until == null) "Auto-answer settings restored" else "Auto-answer paused")
}
