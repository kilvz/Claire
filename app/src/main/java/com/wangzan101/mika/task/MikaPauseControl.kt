package com.wangzan101.mika.task

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.a11y.MikaAccessibilityService
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.setup.DeveloperSettings
import com.wangzan101.mika.core.device.shell.BuiltinShellControl
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PermissionCommands
import com.wangzan101.mika.core.device.shell.RecoveryCapability
import com.wangzan101.mika.core.device.shell.RepairPhase
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.MikaPause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Pause Claire": the user stops all of Claire by hand until they resume it by hand.
 *
 * Pausing does only these things, and the UI wording promises only these:
 * - Write the persistent pause flag ([MikaPause]); afterwards the task service, scheduling, channels, tasks handed over from the computer, workflows and auto-repair all use it to block new work;
 * - Stop running and queued tasks and clear the persistent queue;
 * - Stop the built-in helper and pairing listener so the watchdog no longer starts it again;
 * - Turn off Claire's accessibility service and wireless debugging.
 * Pairing key, port cache and the "paired" flag are left untouched: pausing is not unpairing, and [BuiltinShellControl.unpair] must never be called here.
 *
 * Two things the app may not be able to do; on resume, whatever cannot be done must be listed as steps for the user:
 * - Accessibility service: when the app has permission to modify system settings, or when the helper reconnects, auto-repair ([com.wangzan101.mika.core.device.shell.AccessibilityRepair])
 *   turns it on; without either path (or if repair runs but does not connect), Android only lets the user turn it on in system settings. So after resume, first wait for the repairing side to produce a result
 *   ([awaitAccessibilityRepair]), then decide whether to list this step;
 * - The wireless debugging switch can only be written with permission to modify system settings; if it cannot be written, the user must turn it on in developer options.
 *
 * Writing the pause flag to disk ([MikaPause.set] writes synchronously) runs on the IO thread so the main thread is not blocked; the remaining steps run on the main thread.
 *
 * Rewritten from "Banking mode" in PR #804 (Vagif Novruzov).
 */
class MikaPauseControl private constructor(
    private val app: Context,
    private val pause: MikaPause,
    private val shell: BuiltinShellControl,
    private val canWriteSecureSettings: () -> Boolean,
) {
    companion object {
        private const val HANDOFF_MS = 1_500L
        /** Worst case of helper reconnect (connecting) plus auto-repair, with ample margin; past that, list the steps truthfully. */
        private const val REPAIR_WAIT_MS = 60_000L

        fun create(context: Context): MikaPauseControl {
            val app = context.applicationContext
            return MikaPauseControl(app, MikaPause.get(app), PhoneServices.of(app).shellControl) {
                app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /** Real state at this moment: accessibility, wireless debugging and helper are all read directly from the system, not from cache. */
    fun snapshot(): PauseState {
        val instanceRunning = ScreenAccessibility.instance.value != null
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, MikaAccessibilityService::class.java)
        val accessibility = when {
            instanceRunning -> AccessibilityState.On
            me in enabled -> AccessibilityState.EnabledNotRunning
            else -> AccessibilityState.Off
        }
        return PauseState(
            paused = pause.paused(),
            accessibility = accessibility,
            wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
            helper = shell.status.value,
            helperPaired = shell.paired,
        )
    }

    /**
     * Pause: first write the flag to disk (if that fails, do nothing and return null), then stop tasks, stop the helper, turn off accessibility and turn off wireless debugging.
     * The flag goes first, so every later asynchronous hand-off point re-reads "paused", and a mid-way error still leaves it paused.
     * Call on the main thread ([ScreenAccessibility.disableSelf] is a method of the accessibility service); the disk write switches to the IO thread.
     * Once started it runs to completion (not cancelled when the UI leaves), so there is no half-done state where the flag is written but later steps are not.
     */
    suspend fun pause(): PauseResult? = withContext(NonCancellable) { pauseNow() }

    private suspend fun pauseNow(): PauseResult? {
        val tasksRunning = (app as? MikaApp)?.taskState?.runs?.value?.isNotEmpty() ?: false
        if (!withContext(Dispatchers.IO) { pause.set(true) }) return null
        val before = snapshot()
        TaskService.pauseAll(app)
        (app as? MikaApp)?.workflows?.haltForPause()
        // Stop the helper and pairing listener: from this moment the watchdog no longer restarts it; pairing key and port cache stay untouched.
        shell.pauseChanged()
        runCatching { PhoneServices.of(app).pairing.end() }
        // Accessibility: a running service can turn itself off; if it is listed but not running, only the user can turn it off in system settings.
        var a11yOff = before.accessibility == AccessibilityState.Off
        var a11yNeedsManual = false
        if (!a11yOff) {
            val instance = ScreenAccessibility.instance.value
            if (instance != null) {
                a11yOff = runCatching { instance.disableSelf() }.isSuccess
                a11yNeedsManual = !a11yOff
            } else {
                a11yNeedsManual = true
            }
        }
        // Wireless debugging: only turn off the switch, do not unpair; when it cannot be written, tell the user to turn it off by hand in developer options.
        var wirelessOff = !before.wirelessDebugging
        var wirelessNeedsManual = false
        if (!wirelessOff) {
            wirelessOff = canWriteSecureSettings() &&
                DeveloperSettings.turnOffWirelessDebugging(app.contentResolver) &&
                !DeveloperSettings.wirelessDebugging(app.contentResolver)
            wirelessNeedsManual = !wirelessOff
        }
        return PauseResult(
            tasksStopped = tasksRunning,
            accessibilityOff = a11yOff,
            accessibilityNeedsManual = a11yNeedsManual,
            wirelessOff = wirelessOff,
            wirelessNeedsManual = wirelessNeedsManual,
            helperPaused = shell.status.value == BuiltinShellStatus.Paused,
        )
    }

    /**
     * Resume: first clear the flag (if that fails, return null and stay paused), turn wireless debugging on when paired (if it can be written), then have the helper reconnect using the paired connection.
     * Re-arm scheduled alarms once (windows that came due during the pause are still recorded as skipped and advanced to the next one), without catching up.
     * Schedules skipped during the pause, refused messages and tasks are not caught up. Pairing again is needed only if pairing was lost; normally never take that step.
     * On return, accessibility may still be auto-repairing: to wait for its result before listing steps, call [awaitAccessibilityRepair] next.
     * Call on the main thread; disk writes, switch writes and alarm scheduling switch to the IO thread. Like [pause], it runs to completion once started.
     */
    suspend fun resume(): ResumeResult? = withContext(NonCancellable) { resumeNow() }

    private suspend fun resumeNow(): ResumeResult? {
        if (!withContext(Dispatchers.IO) { pause.set(false) }) return null
        val before = snapshot()
        if (!before.wirelessDebugging && before.helperPaired && canWriteSecureSettings()) {
            withContext(Dispatchers.IO) { DeveloperSettings.turnOnWirelessDebugging(app.contentResolver) }
        }
        shell.pauseChanged()
        withContext(Dispatchers.IO) { runCatching { TriggerRuntime.rearm(app) } }
        // Read the system again: the switch write may be reverted by the system on disallowed Wi-Fi, so trust the measurement.
        return ResumeResult(planResumeSteps(snapshot()))
    }

    /** Whether auto-repair can turn on accessibility for the user right now: the user has not turned off "turn on accessibility for me", and the app can write system settings directly or the helper is paired and running. */
    private fun accessibilityRepairPossible(): Boolean {
        val phone = PhoneServices.of(app)
        if (!phone.recoverySettings.consent.value.wants(RecoveryCapability.Accessibility)) return false
        return canWriteSecureSettings() || shell.paired || shell.status.value == BuiltinShellStatus.Running
    }

    /**
     * After resume, waits for the accessibility auto-repair to produce a result: the service connected, or the repairing side stopped (gave up, no path to repair, helper not connected), for at most [REPAIR_WAIT_MS].
     * Returns immediately when there is no path to repair. Right after the pause flag is cleared, the repairing side needs a moment to take over, so first give it [HANDOFF_MS].
     */
    suspend fun awaitAccessibilityRepair() {
        if (ScreenAccessibility.instance.value != null || !accessibilityRepairPossible()) return
        val repair = PhoneServices.of(app).recovery.accessibilityRepair
        val handedOff = flow { emit(false); delay(HANDOFF_MS); emit(true) }
        withTimeoutOrNull(REPAIR_WAIT_MS) {
            combine(ScreenAccessibility.instance, shell.status, repair.phase, handedOff) { service, helper, phase, waited ->
                service != null || (waited && phase == RepairPhase.Idle && helper != BuiltinShellStatus.Connecting)
            }.first { it }
        }
    }

}

/** Real state related to pause. */
data class PauseState(
    val paused: Boolean,
    val accessibility: AccessibilityState,
    val wirelessDebugging: Boolean,
    val helper: BuiltinShellStatus,
    val helperPaired: Boolean,
)

/** After one pause completes, what was turned off and what the user still has to do by hand. */
data class PauseResult(
    val tasksStopped: Boolean,
    val accessibilityOff: Boolean,
    val accessibilityNeedsManual: Boolean,
    val wirelessOff: Boolean,
    val wirelessNeedsManual: Boolean,
    val helperPaused: Boolean,
) {
    /** Everything agreed to be turned off is off. */
    val complete: Boolean get() = accessibilityOff && wirelessOff && helperPaused
}

/** After one resume completes, which steps the user still has to do by hand. */
data class ResumeResult(val steps: List<ResumeStep>)

/** Steps the app cannot do itself on resume and the user must do by hand. */
enum class ResumeStep {
    /** Open Claire's accessibility service in system settings: needed only when auto-repair has no path or ran without connecting. */
    OpenAccessibilitySettings,
    /** Open wireless debugging in developer options: needed only when the app cannot write the switch. */
    OpenWirelessDebuggingPage,
    /** Pairing is already lost: run the pairing flow again. Normally does not appear. */
    RePairHelper,
}

/** Pure function: from the real state after resume, works out which steps the user still has to do by hand. Wireless debugging is required only when the helper relies on its paired connection; a helper started via Shizuku or root does not mention pairing. */
fun planResumeSteps(state: PauseState): List<ResumeStep> = buildList {
    if (state.accessibility != AccessibilityState.On) add(ResumeStep.OpenAccessibilitySettings)
    if (state.helperPaired && !state.wirelessDebugging) add(ResumeStep.OpenWirelessDebuggingPage)
    if (state.helper == BuiltinShellStatus.NotPaired) add(ResumeStep.RePairHelper)
}
