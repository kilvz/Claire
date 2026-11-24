package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.delay

/** The few things looked at when deciding whether to restart the helper. */
data class RecoveryInputs(
    /** The pairing key is stored on this device. */
    val paired: Boolean,
    /** The system's wireless debugging switch; some phones cannot read it, in which case it is null. */
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
)

enum class StopReason {
    NotPaired,
    /** Wireless debugging is off: come back when it is turned on (retriggered by whoever watches the switch). */
    WirelessDebuggingOff,
    /** The kind of failure needs the user to act (re-pair, connect to Wi-Fi); retrying is useless. */
    NeedsUser,
    OutOfAttempts,
}

sealed interface RecoveryDecision {
    data object Healthy : RecoveryDecision
    /** Reconnect once with the paired key after [delayMs]. */
    data class Try(val delayMs: Long) : RecoveryDecision
    /** Someone else is connecting: look again after [delayMs] instead of stacking another reconnect. */
    data class Wait(val delayMs: Long) : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

data class RecoveryOutcome(val recovered: Boolean, val attempts: Int, val stoppedBecause: StopReason?)

/**
 * When to restart the helper while it is gone, how long to wait, and how many times. Unplugging USB or an adbd restart takes down every process running as the shell identity,
 * so it does not distinguish "how it died": as long as pairing is still there, wireless debugging has not been confirmed off, and the failure does not need the user to act, it reconnects with backoff.
 * Whether the network is trusted cannot be read, so it always tries first and lets the connection result decide.
 */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** Try immediately the first time, then 2, 4, 8, 16 seconds… capped at 30 seconds. */
    fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BASE_DELAY_MS shl (attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)

    fun decide(inputs: RecoveryInputs, attempts: Int): RecoveryDecision {
        val s = inputs.status
        return when {
            s == BuiltinShellStatus.Running -> RecoveryDecision.Healthy
            !inputs.paired || s == BuiltinShellStatus.NotPaired -> RecoveryDecision.Stop(StopReason.NotPaired)
            inputs.wirelessDebugging == false -> RecoveryDecision.Stop(StopReason.WirelessDebuggingOff)
            s is BuiltinShellStatus.Failed && needsUser(s.reason) -> RecoveryDecision.Stop(StopReason.NeedsUser)
            attempts >= MAX_ATTEMPTS -> RecoveryDecision.Stop(StopReason.OutOfAttempts)
            s == BuiltinShellStatus.Connecting -> RecoveryDecision.Wait(backoffMs(attempts).coerceAtLeast(BASE_DELAY_MS))
            else -> RecoveryDecision.Try(backoffMs(attempts))
        }
    }

    private fun needsUser(f: BuiltinShellFailure) = f.defaultFix != BuiltinShellFix.Retry

    /**
     * Keeps trying per [decide] until recovered or it should stop. [attempt] is one reconnect (in the real implementation `BuiltinShellControl.retry()`,
     * which does not throw and writes its result into the status); [inputs] is re-read every round.
     */
    suspend fun recover(
        inputs: suspend () -> RecoveryInputs,
        attempt: suspend () -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): RecoveryOutcome {
        var attempts = 0
        while (true) {
            when (val d = decide(inputs(), attempts)) {
                RecoveryDecision.Healthy -> return RecoveryOutcome(recovered = true, attempts = attempts, stoppedBecause = null)
                is RecoveryDecision.Stop -> return RecoveryOutcome(recovered = false, attempts = attempts, stoppedBecause = d.reason)
                is RecoveryDecision.Wait -> { sleep(d.delayMs); attempts++ }
                is RecoveryDecision.Try -> { sleep(d.delayMs); attempt(); attempts++ }
            }
        }
    }
}

/** Whether "restore after reboot" should act at boot: it looks at whether the capability is in effect (on by default, including ones filled in automatically), not at whether the user ever flipped the switch. */
object BootRestore {
    const val MIN_SDK = 31

    enum class Gate { Act, Skip, PermissionMissing }

    fun gate(sdkInt: Int, consent: RecoveryConsent, holdsPermission: Boolean): Gate = when {
        sdkInt < MIN_SDK || !consent.isApplied(RecoveryCapability.RestoreAfterReboot) -> Gate.Skip
        !holdsPermission -> Gate.PermissionMissing
        else -> Gate.Act
    }
}

/** The result of the last automatic restore at boot, shown honestly on the settings page. */
enum class RestoreOutcome {
    Restored,
    /** This capability was turned on, but the permission to modify system settings is gone. */
    PermissionMissing,
    /** Writing the wireless debugging switch was rejected by the system or had no effect: this phone does not support turning it on automatically at boot. */
    SettingUnavailable,
    /** The switch was turned on, but it could not connect (for example not on a trusted Wi-Fi). */
    NotRecovered,
}
