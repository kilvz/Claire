package com.wangzan101.mika.task.ui

import com.wangzan101.mika.settings.DeviceAccessConfig

/** Battery policy the system gives Claire. When restricted, alarms and foreground services may both be blocked, and a plan may not run when due. */
internal enum class PlanBatteryPolicy { Restricted, Managed, Unrestricted, Unknown }

/**
 * Whether phone steps in a scheduled task can unlock by themselves while the screen is locked (the mechanism from #852: unlocking is allowed when either the scheduled task's local or remote switch is on,
 * with the local switch taking priority; when neither is on, phone steps wait for the user to unlock).
 */
internal enum class PlanUnlockReadiness {
    /** The switch is on; when a lock screen password is set, credentials are also stored. */
    Ready,
    /** The switch is on, but a lock screen password is set without stored credentials: unlocking will fail when due and fall back to waiting for the user to unlock. */
    NeedsCredential,
    /** Disabled after the last unlock failure; the user must re-verify credentials. */
    Blocked,
    /** Neither switch is on: phone steps wait for the user to unlock; reminders, web and sandbox steps are unaffected. */
    Off,
}

/** The three conditions for whether a plan can run on time with the screen off. Reads the system and settings only and changes nothing. */
internal data class ScreenOffReadiness(
    val exactAlarm: Boolean,
    val battery: PlanBatteryPolicy,
    val unlock: PlanUnlockReadiness,
)

/** Mirrors the routing decision actually taken at task run time: the local switch takes the local path, otherwise check the remote switch (see RunPhoneStage.assembleGate). */
internal fun unlockReadiness(config: DeviceAccessConfig, deviceSecure: Boolean): PlanUnlockReadiness {
    if (!config.localEnabled && !config.enabled) return PlanUnlockReadiness.Off
    val blocked = if (config.localEnabled) config.localBlocked else config.blocked
    return when {
        blocked -> PlanUnlockReadiness.Blocked
        deviceSecure && config.kind == null -> PlanUnlockReadiness.NeedsCredential
        else -> PlanUnlockReadiness.Ready
    }
}
