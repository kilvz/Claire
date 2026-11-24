package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState

enum class AccessibilityState {
    Off,
    /** It is on in system settings but the service is not running (common after the app is force-stopped); it must be turned off and on again. */
    EnabledNotRunning,
    On,
}

data class SystemStatus(
    val sdkInt: Int,
    /** Status of the built-in helper. */
    val builtinShell: BuiltinShellStatus,
    val accessibility: AccessibilityState,
    val notificationsAllowed: Boolean,
    /** The "needs your attention" notification channel was lowered below high importance by the user or the system (no pop-up, possibly no sound). */
    val attentionLowered: Boolean = false,
    /** Same for the "task results" notification channel. */
    val resultLowered: Boolean = false,
    /** The "pairing" notification channel: the pairing code is entered there, so the onboarding page must say so when the channel is off or set not to pop up. */
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    /** Current importance of the three task notification channels; null means the channel has not been created yet. The notifications page displays based on these. */
    val attentionImportance: Int? = null,
    val resultImportance: Int? = null,
    val progressImportance: Int? = null,
    /** The system's "Developer options" are on. */
    val developerOptions: Boolean = false,
    /** The system's "Wireless debugging" is on. */
    val wirelessDebugging: Boolean = false,
    /** The system has no "Wireless debugging" item at all (the feature is not included): the onboarding page uses this to offer "start it from a computer instead". */
    val wirelessDebuggingMissing: Boolean = false,
    /** The "Developer options" page cannot be opened on this system: together with [wirelessDebuggingMissing] this means phone operation cannot be set up in this environment. */
    val developerSettingsMissing: Boolean = false,
    /** The system's "USB debugging" is on: while it is on, the helper keeps running even when Wi-Fi disconnects. */
    val usbDebugging: Boolean = true,
    /** The user enabled "start the phone helper with root" and it works right now: the watchdog starts the helper via su (without relying on Wi-Fi or wireless debugging). */
    val rootStart: Boolean = false,
    /** The system may block enabling accessibility (since Android 13, apps not installed from an app store must first be allowed restricted settings). */
    val restrictedSettings: Boolean = false,
    /** What brings the helper back after it stops: when it has only ever been started by the USB start command, nothing should point to wireless debugging; state plainly that the command must be run again. */
    val helperRestart: HelperRestart = HelperRestart.None,
    /** The user has not unlocked once since boot ([android.os.UserManager.isUserUnlocked] is false): the accessibility service could not be bound at this point anyway. */
    val userLocked: Boolean = false,
    /** The currently selected helper start channel. */
    val shellTransport: ShellTransport = ShellTransport.Auto,
    /** Shizuku's install, running and authorization state. */
    val shizuku: ShizukuPlatformState = ShizukuPlatformState.Unavailable,
    /** Whether wireless debugging has been paired; null means [builtinShell] is used instead. */
    val wirelessPaired: Boolean? = null,
    /** Whether the helper is currently started via Shizuku in preference. */
    val shizukuStart: Boolean = false,
)

fun SystemStatus.notificationOverview() = NotificationOverview.of(
    notificationsAllowed, attentionImportance, resultImportance, progressImportance,
)

sealed interface Problem {
    /** Claire's phone-operation capability is not available below Android 12. */
    data object SystemTooOld : Problem
    /** The built-in helper is not running (not set up, wireless debugging off, connecting, or errored). */
    data class PhoneHelper(val status: BuiltinShellStatus) : Problem
    /** When [userLocked] is true it is not running because the device has not been unlocked since boot; when [restrictedSettings] is true the system may be blocking the binding. */
    data class Accessibility(val state: AccessibilityState, val userLocked: Boolean = false, val restrictedSettings: Boolean = false) : Problem
    data object NoModel : Problem
    /** Does not block tasks; it only means progress and "needs you" reminders are not seen. */
    data object NotificationsOff : Problem
    /** Notifications for "needs your attention" (takeover, question, confirmation) or "task results" were lowered, so they do not pop up and may be missed; [attention] and [result] tell which one. */
    data class AttentionLowered(val attention: Boolean, val result: Boolean) : Problem
    /** Does not block tasks: on Android 12 and 13 the virtual display has no focus of its own, so background operation may briefly take input focus away from the main screen. */
    data object FocusMayLeak : Problem
}

/** Checks before starting a task; the UI prompts based on them, and the task entry points block based on them. */
object Prerequisites {
    /** Minimum version for operating phone apps; chat, web and the sandbox do not need this capability. */
    const val MIN_SDK = 31
    /** Since Android 14 the virtual display can have its own focus and no longer affects the main screen. */
    const val OWN_FOCUS_SDK = 34
    private fun isWarning(p: Problem) = p == Problem.NotificationsOff || p == Problem.FocusMayLeak || p is Problem.AttentionLowered

    data class Check(val problems: List<Problem>) {
        /** Problems that block tasks. The OS version for phone operation, the built-in helper and accessibility are only needed to operate phone apps. */
        val blockers: List<Problem> get() = problems.filter { !isWarning(it) && !phoneOnly(it) }
        val canStartTask: Boolean get() = blockers.isEmpty()
        /** Phone apps can be operated. */
        val phoneReady: Boolean get() = problems.none(::phoneOnly)
    }

    private fun phoneOnly(p: Problem) = p is Problem.SystemTooOld || p is Problem.PhoneHelper || p is Problem.Accessibility

    fun check(status: SystemStatus, hasMainModel: Boolean): Check = Check(
        buildList {
            if (status.sdkInt < MIN_SDK) add(Problem.SystemTooOld)
            // Phone features are unavailable below Android 12, which SystemTooOld already states
            if (status.sdkInt >= MIN_SDK && status.builtinShell != BuiltinShellStatus.Running) {
                add(Problem.PhoneHelper(status.builtinShell))
            }
            if (status.sdkInt >= MIN_SDK && status.accessibility != AccessibilityState.On) {
                add(Problem.Accessibility(status.accessibility, status.userLocked, status.restrictedSettings))
            }
            if (!hasMainModel) add(Problem.NoModel)
            if (!status.notificationsAllowed) add(Problem.NotificationsOff)
            else if (status.attentionLowered || status.resultLowered) add(Problem.AttentionLowered(status.attentionLowered, status.resultLowered))
            if (status.sdkInt in MIN_SDK until OWN_FOCUS_SDK) add(Problem.FocusMayLeak)
        },
    )
}
