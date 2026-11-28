package com.wangzan101.mika.core.device.shell

/** The six guide steps, in order. */
enum class SetupStep { DeveloperOptions, UsbDebugging, Accessibility, WirelessDebugging, PairingCode, Connect }

/** [Skipped]: the user skipped this step (only accessibility can be skipped). */
enum class StepMark { Done, Current, Todo, Failed, Skipped }

enum class SetupPhase {
    /** Waiting for the user to do one of the steps. */
    Guide,
    /** Pairing, connecting or starting: show progress. */
    Working,
    Success,
    /** Something went wrong: [SetupView.failure] gives the cause and the fix, [SetupView.suspect] indicates the step most likely to need redoing. */
    Failed,
    /** There is no way to start the helper in this environment (see [PhoneSetupFlow.unavailable]): say so up front instead of offering steps that cannot work. */
    Unavailable,
}

/** The shape of the pairing notification channel: [Off] is disabled and notifications cannot be posted; [Lowered] was set by the user so it no longer pops up at the top of the screen, but the notification shade can still be pulled down to fill it in. */
enum class PairingChannelHealth { Ready, Lowered, Off }

/** What is not ready on the notification side, when the pairing code has to be filled in the notification. */
enum class PairingNotifyIssue {
    /** Claire is not allowed to post notifications (permission or the App-wide notification switch). */
    NotificationsOff,
    /** The "pairing" notification category is turned off. */
    ChannelOff,
    /** The "pairing" notification will not pop up: this does not block, it only warns. */
    NotHeadsUp,
}

/**
 * Inputs of the guide page: the switches read from system settings, the user's own confirmations, and the helper's status.
 * The switches that were read are only used to tick automatically and give hints; they never hard-block: some phones cannot read them or read them wrongly, so once the user taps "I've turned it on, next" ([confirmed]) it moves on.
 * [wirelessDebugging] being null means this phone cannot read this switch (the setting item is not written the stock way): it does not block and is treated as on.
 * [confirmed] holds the steps the user says are done; [revisit] is a previous step the user tapped in the checklist and wants to redo.
 * [repair] means the user tapped "re-pair" on the settings page: even when already running, it goes back to the pairing step until a new pairing starts.
 * While [usbDebugging] is on, the helper does not stop when Wi-Fi drops or wireless debugging is turned off by the system.
 * [accessibility] means Claire's accessibility service is running: during pairing it reads the pairing code; the user may skip it ([accessibilitySkipped]) and fall back to filling the code in the notification.
 * [restrictedSettings] means the system may block turning accessibility on (an App not installed from an app store must first allow restricted settings in its app info).
 * [notificationsAllowed] and [pairingChannel] decide whether the pairing code can be filled in the notification.
 * The next three decide whether to offer "start with a computer instead" (see [SetupView.usbFallback]):
 * [wirelessMissing] means there is no wireless debugging item in the system at all (stock Android always writes this setting at boot, so if it cannot be read the system does not have this feature);
 * [stalledMs] is how long it has been without progress at the wireless debugging step, at pairing (before the pairing window is found), or at automatic connection (see [PhoneSetupFlow.stallPoint]);
 * [usbAsked] means the user tapped "Can't find wireless debugging?" or the card has already appeared (once shown it is not taken away again, so it does not keep disappearing and reappearing).
 * [restart] is what brings the helper back after it stops (see [HelperRestart]): when it was only ever started by the USB launch command, the wireless debugging and pairing steps count as skipped,
 * and once the helper stops the guide stays on the connect step saying it stopped and giving the command, without falling back to the wireless debugging step.
 * [developerSettingsMissing] means the "Developer options" page cannot be opened in the system (the Intent to it has no activity to handle it): when this holds together with [wirelessMissing],
 * the user can turn on neither USB debugging nor wireless debugging, see [PhoneSetupFlow.unavailable].
 */
data class SetupInputs(
    val developerOptions: Boolean,
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
    val discovery: PairingDiscovery = PairingDiscovery.Idle,
    val notificationsAllowed: Boolean = true,
    val pairingChannel: PairingChannelHealth = PairingChannelHealth.Ready,
    val repair: Boolean = false,
    val usbDebugging: Boolean = true,
    val accessibility: Boolean = true,
    val accessibilitySkipped: Boolean = false,
    val restrictedSettings: Boolean = false,
    val confirmed: Set<SetupStep> = emptySet(),
    val revisit: SetupStep? = null,
    val wirelessMissing: Boolean = false,
    val stalledMs: Long = 0L,
    val usbAsked: Boolean = false,
    val restart: HelperRestart = HelperRestart.None,
    val developerSettingsMissing: Boolean = false,
)

/** The most likely cause when pairing or connecting did not succeed: the setting for [step] was read as off; [unreadable] is true when this phone simply cannot read it. */
data class SetupSuspect(val step: SetupStep, val unreadable: Boolean)

data class SetupView(
    val phase: SetupPhase,
    val marks: Map<SetupStep, StepMark>,
    /** The step to do right now (for Guide and Failed); null for Working/Success. */
    val current: SetupStep?,
    val failure: BuiltinShellStatus.Failed? = null,
    /** The pairing window has been found: prompt the user to fill the code in the notification. */
    val portFound: Boolean = false,
    /** The notification-side problem while on the pairing step; when [needsNotifications] is true it must be fixed first before the code can be filled in the notification. */
    val notifyIssue: PairingNotifyIssue? = null,
    /** On the pairing step with accessibility on: Claire reads the pairing code itself, no need to fill it in the notification. */
    val autoRead: Boolean = false,
    /** On the accessibility step, offer the entry to "allow restricted settings in app info". */
    val showAppInfo: Boolean = false,
    /** The previous step most likely to need redoing when it fails; the UI offers a go-back button for it. */
    val suspect: SetupSuspect? = null,
    /** The current step is one the user tapped to redo: the UI offers "back to the original step". */
    val revisiting: Boolean = false,
    /** Offer the "start with a computer instead" card on the current step: wireless debugging most likely does not work, or it is stuck. Not offered when things proceed normally. */
    val usbFallback: Boolean = false,
    /** What brings the helper back after it stops (copied from [SetupInputs.restart]): it decides what the success page reminder and the "start with a computer instead" card say. */
    val restart: HelperRestart = HelperRestart.None,
) {
    /** Notifications cannot be posted (not allowed, or the "pairing" notification is off): the pairing code has to be filled in the notification, so fix this first. */
    val needsNotifications: Boolean
        get() = notifyIssue == PairingNotifyIssue.NotificationsOff || notifyIssue == PairingNotifyIssue.ChannelOff
}

/**
 * State machine of the guide page: it recomputes on every input change (system setting switches, user confirmations, helper status, pairing-window discovery progress) and keeps no intermediate state.
 * Success looks only at the helper: once connected it counts as set up, regardless of what was read earlier. Earlier steps are ticked automatically when read as done; when read as not done and the user has not confirmed, it stays on that step,
 * but the user can always tap "I've turned it on, next" to move on, so no step can dead-end.
 * When a switch is read as off and the user has not confirmed it, it falls back to that step no matter what the helper did before.
 */
object PhoneSetupFlow {
    /** The kinds of failure during pairing or connection that may come from an earlier switch being off (a wrong pairing code means the pairing window is open, so it does not count). */
    private val SWITCH_RELATED = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.Unknown)

    /** The steps the user can tap to redo: the automatic connection step has nothing for the user to do. */
    val REVISITABLE: Set<SetupStep> = SetupStep.entries.toSet() - SetupStep.Connect

    /** The steps with an "I've turned it on, next" option: these read system switches (accessibility has its own skip instead). */
    val CONFIRMABLE: Set<SetupStep> = setOf(SetupStep.DeveloperOptions, SetupStep.UsbDebugging, SetupStep.WirelessDebugging)

    /** After no progress at [stallPoint] for this long, offer "start with a computer instead". */
    const val STALL_MS = 45_000L

    /** These kinds of failure mean the wireless debugging path does not work and "start with a computer instead" is offered: no pairing window found, cannot connect, the computer-started helper stopped. */
    private val USB_FAILURES = setOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped)

    fun view(i: SetupInputs): SetupView {
        if (unavailable(i)) return SetupView(SetupPhase.Unavailable, SetupStep.entries.associateWith { StepMark.Todo }, null, restart = i.restart)
        return steps(i).let { it.copy(usbFallback = usbFallback(it, i), restart = i.restart) }
    }

    /**
     * Whether phone control can be set up in this environment: when the developer options page cannot be opened and there is no wireless debugging item, there is no way to start the helper
     * (both USB debugging and wireless debugging live under developer options, and computer launch also needs USB debugging). If the helper is already running, is starting, or was started before,
     * that means some other path exists and this does not apply.
     */
    fun unavailable(developerSettingsMissing: Boolean, wirelessMissing: Boolean, status: BuiltinShellStatus, restart: HelperRestart): Boolean =
        developerSettingsMissing && wirelessMissing && restart == HelperRestart.None &&
            status != BuiltinShellStatus.Running && status != BuiltinShellStatus.Connecting

    fun unavailable(i: SetupInputs): Boolean =
        unavailable(i.developerSettingsMissing, i.wirelessMissing, i.status, i.restart)

    /**
     * Which point the stall timer is counted at: stuck on the wireless debugging step, stuck on the pairing step before the pairing window is found (the system never offered a pairing service), or auto-connecting.
     * The earlier steps require the user to go find things in system settings, where any amount of time is normal and does not count; moving to a different point restarts the count.
     */
    fun stallPoint(v: SetupView): SetupStep? = when {
        v.phase == SetupPhase.Working -> SetupStep.Connect
        v.phase != SetupPhase.Guide -> null
        v.current == SetupStep.WirelessDebugging -> SetupStep.WirelessDebugging
        v.current == SetupStep.PairingCode && !v.portFound -> SetupStep.PairingCode
        else -> null
    }

    /**
     * Whether to offer "start with a computer instead": no wireless debugging in the system and the user saying they cannot find it are only offered after reaching the wireless debugging step (computer launch also requires the first two steps to be done);
     * also when pairing/connection failed in the kinds above, or the user says it is on while the helper reads it as off; and when stalled at [stallPoint] for [STALL_MS] or more. Not offered otherwise.
     */
    private fun usbFallback(v: SetupView, i: SetupInputs): Boolean {
        if (v.phase == SetupPhase.Success) return false
        val reachedWireless = v.phase == SetupPhase.Working || (v.current != null && v.current >= SetupStep.WirelessDebugging)
        return (reachedWireless && (i.wirelessMissing || i.usbAsked)) ||
            v.failure?.reason in USB_FAILURES ||
            (v.phase == SetupPhase.Failed && v.suspect?.step == SetupStep.WirelessDebugging) ||
            (stallPoint(v) != null && i.stalledMs >= STALL_MS)
    }

    private fun steps(i: SetupInputs): SetupView {
        val status = effectiveStatus(i.status, i.repair)
        if (status == BuiltinShellStatus.Running) return SetupView(SetupPhase.Success, marks(SetupStep.entries.size, i), null)
        val natural = natural(i, status)
        // While connecting, progress takes precedence; when an earlier step is read as off, go back to that step first
        val back = i.revisit?.takeIf { it in REVISITABLE && status != BuiltinShellStatus.Connecting } ?: return natural
        val earlier = natural.phase == SetupPhase.Guide && natural.current != null && natural.current < back
        if (earlier) return natural
        // Other steps are still ticked or marked failed, and the step that was current first turns back into to-do
        val marks = natural.marks.mapValues { (_, m) -> if (m == StepMark.Current) StepMark.Todo else m } + (back to StepMark.Current)
        return guide(back, i).copy(marks = marks, revisiting = true)
    }

    /**
     * The view when not redoing an earlier step. While connecting (including a helper swap, or a freshly started one awaiting handoff) progress takes precedence, and it does not fall back to that step just because wireless debugging was read as off:
     * with a computer-started helper, or when wireless debugging can be turned on for the user, the user does not have to turn it on. A stopped helper started by the USB command likewise does not point to wireless debugging.
     */
    private fun natural(i: SetupInputs, status: BuiltinShellStatus): SetupView {
        if (blocks(SetupStep.DeveloperOptions, i)) return guide(SetupStep.DeveloperOptions, i)
        if (blocks(SetupStep.UsbDebugging, i)) return guide(SetupStep.UsbDebugging, i)
        if (blocks(SetupStep.Accessibility, i)) return guide(SetupStep.Accessibility, i)
        if (status == BuiltinShellStatus.Connecting) return working(i)
        if (status is BuiltinShellStatus.Failed && status.reason == BuiltinShellFailure.UsbHelperStopped) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                failure = status,
            )
        }
        // When the switch cannot be read, the helper's word is authoritative; if the user confirmed, do not block
        val wirelessConfirmed = SetupStep.WirelessDebugging in i.confirmed
        val wirelessOff = !wirelessConfirmed &&
            (i.wirelessDebugging == false || (i.wirelessDebugging == null && status == BuiltinShellStatus.WirelessDebuggingOff))
        if (wirelessOff) return guide(SetupStep.WirelessDebugging, i)
        // The user says it is on but the helper reads it as off and will not connect: do not spin forever; point out which step and offer a go-back button
        if (status == BuiltinShellStatus.WirelessDebuggingOff && wirelessConfirmed && i.wirelessDebugging != true) {
            return SetupView(
                SetupPhase.Failed, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect, mark = StepMark.Failed), SetupStep.Connect,
                suspect = SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null),
            )
        }
        return when (status) {
            BuiltinShellStatus.Running -> error("handled above")
            BuiltinShellStatus.Connecting -> error("handled above")
            // Paired and wireless debugging is on but the status has not caught up yet: the implementation connects automatically right away, so show "connecting" first
            BuiltinShellStatus.WirelessDebuggingOff -> working(i)
            BuiltinShellStatus.NotPaired -> guide(SetupStep.PairingCode, i)
            // The user paused Claire: this is not a setup failure. Stay on the "connect" step: resuming reconnects here.
            BuiltinShellStatus.Paused -> guide(SetupStep.Connect, i)
            is BuiltinShellStatus.Failed -> {
                val step = if (status.reason.duringPairing || status.fix == BuiltinShellFix.RePair) SetupStep.PairingCode else SetupStep.Connect
                SetupView(
                    SetupPhase.Failed, marks(step.ordinal, i, current = step, mark = StepMark.Failed), step, failure = status,
                    portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
                    notifyIssue = notifyIssue(step, i),
                    autoRead = step == SetupStep.PairingCode && i.accessibility,
                    suspect = if (status.reason in SWITCH_RELATED) suspect(i) else null,
                )
            }
        }
    }

    private fun working(i: SetupInputs) = SetupView(SetupPhase.Working, marks(SetupStep.Connect.ordinal, i, current = SetupStep.Connect), null)

    /** This step was read as not done and the user has not confirmed it (accessibility may still be skipped): stay on this step. */
    private fun blocks(step: SetupStep, i: SetupInputs): Boolean =
        detected(step, i) == false && step !in i.confirmed && !(step == SetupStep.Accessibility && i.accessibilitySkipped)

    /** Whether this step was read as done from system settings; null when it cannot be read or the step is not a switch. */
    fun detected(step: SetupStep, i: SetupInputs): Boolean? = when (step) {
        SetupStep.DeveloperOptions -> i.developerOptions
        SetupStep.UsbDebugging -> i.usbDebugging
        SetupStep.Accessibility -> i.accessibility
        SetupStep.WirelessDebugging -> i.wirelessDebugging
        SetupStep.PairingCode, SetupStep.Connect -> null
    }

    /** Pairing and connecting depend on developer options and wireless debugging: find the first one, in order, that is read as off (the user must have confirmed it to get this far) or cannot be read. */
    private fun suspect(i: SetupInputs): SetupSuspect? = when {
        !i.developerOptions -> SetupSuspect(SetupStep.DeveloperOptions, unreadable = false)
        i.wirelessDebugging != true -> SetupSuspect(SetupStep.WirelessDebugging, unreadable = i.wirelessDebugging == null)
        else -> null
    }

    private fun guide(step: SetupStep, i: SetupInputs) = SetupView(
        SetupPhase.Guide, marks(step.ordinal, i, current = step), step,
        portFound = step == SetupStep.PairingCode && i.discovery is PairingDiscovery.Found,
        notifyIssue = notifyIssue(step, i),
        autoRead = step == SetupStep.PairingCode && i.accessibility,
        showAppInfo = step == SetupStep.Accessibility && i.restrictedSettings,
    )

    private fun notifyIssue(step: SetupStep, i: SetupInputs): PairingNotifyIssue? = when {
        step != SetupStep.PairingCode -> null
        !i.notificationsAllowed -> PairingNotifyIssue.NotificationsOff
        i.pairingChannel == PairingChannelHealth.Off -> PairingNotifyIssue.ChannelOff
        i.pairingChannel == PairingChannelHealth.Lowered -> PairingNotifyIssue.NotHeadsUp
        else -> null
    }

    /** The two steps not walked through when the helper was only ever started by the USB launch command. */
    private val WIRELESS_STEPS = setOf(SetupStep.WirelessDebugging, SetupStep.PairingCode)

    /** The first [done] steps are done (a skipped accessibility, or the wireless debugging and pairing steps left out for USB launch, count as skipped), the [current] step gets [mark], and the rest are to-do. */
    private fun marks(done: Int, i: SetupInputs, current: SetupStep? = null, mark: StepMark = StepMark.Current): Map<SetupStep, StepMark> =
        SetupStep.entries.associateWith { s ->
            when {
                s.ordinal < done && s == SetupStep.Accessibility && !i.accessibility && s !in i.confirmed -> StepMark.Skipped
                s.ordinal < done && s in WIRELESS_STEPS && i.restart == HelperRestart.UsbCommand -> StepMark.Skipped
                s.ordinal < done -> StepMark.Done
                s == current -> mark
                else -> StepMark.Todo
            }
        }

    /** When re-pairing, whether the helper is running does not matter before pairing begins ([BuiltinShellStatus.Connecting]): it is treated as not paired. */
    fun effectiveStatus(status: BuiltinShellStatus, repair: Boolean): BuiltinShellStatus =
        if (repair && status !is BuiltinShellStatus.Connecting) BuiltinShellStatus.NotPaired else status
}
