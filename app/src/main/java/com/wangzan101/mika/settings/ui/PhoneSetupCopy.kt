package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.RootGrant
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.core.device.shell.SetupSuspect
import com.wangzan101.mika.ui.components.ValueTone

/**
 * Copy for the "Phone control" onboarding, the settings page and notifications (pure functions, easy to test).
 * Use plain language: only words that already exist in phone settings appear (Developer options, USB debugging, Wireless debugging, pairing code); terms like adb, port and shell are avoided;
 * the exception is the few lines that ask the user to enter the port from the "Wireless debugging" screen when connecting fails.
 */
object PhoneSetupCopy {
    val TITLE: String get() = tr(R.string.phone_title)
    val INTRO: String get() = tr(R.string.phone_intro)
    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val LATER: String get() = tr(R.string.phone_later)
    /** Short title on the onboarding page's top bar. */
    val BAR_TITLE: String get() = tr(R.string.phone_bar_title)
    val SKIP_NOTE: String get() = tr(R.string.phone_skip_note)
    /** Phone control cannot be set up in this environment (see [com.wangzan101.mika.core.device.shell.PhoneSetupFlow.unavailable]): one sentence for the conclusion, one for the reason. */
    val UNAVAILABLE: String get() = tr(R.string.phone_unavailable)
    val UNAVAILABLE_REASON: String get() = tr(R.string.phone_unavailable_reason)

    /** [brief] is the short description for that row when it is not yet its turn in the checklist or is already done; when it is its turn, the full [body] is shown. */
    data class StepCopy(val title: String, val body: String, val action: String?, val done: String, val brief: String)

    fun step(s: SetupStep): StepCopy = when (s) {
        SetupStep.DeveloperOptions -> StepCopy(
            tr(R.string.phone_step_dev),
            tr(R.string.phone_step_dev_body),
            tr(R.string.phone_step_dev_action), tr(R.string.phone_done_on), tr(R.string.phone_step_dev_brief),
        )
        SetupStep.UsbDebugging -> StepCopy(
            tr(R.string.phone_step_usb),
            tr(R.string.phone_step_usb_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_usb_brief),
        )
        SetupStep.Accessibility -> StepCopy(
            tr(R.string.phone_step_a11y),
            tr(R.string.phone_step_a11y_body),
            tr(R.string.phone_step_a11y_action), tr(R.string.phone_done_on), tr(R.string.phone_step_a11y_brief),
        )
        SetupStep.WirelessDebugging -> StepCopy(
            tr(R.string.phone_step_wireless),
            tr(R.string.phone_step_wireless_body),
            tr(R.string.phone_open_dev_options), tr(R.string.phone_done_on), tr(R.string.phone_step_wireless_brief),
        )
        SetupStep.PairingCode -> StepCopy(
            tr(R.string.phone_step_pair),
            tr(R.string.phone_step_pair_body),
            tr(R.string.phone_step_pair_action), tr(R.string.phone_paired), tr(R.string.phone_step_pair_brief),
        )
        SetupStep.Connect -> StepCopy(
            tr(R.string.phone_step_connect),
            tr(R.string.phone_step_connect_body),
            null, tr(R.string.phone_connected), tr(R.string.phone_step_connect_brief),
        )
    }

    val ACCESSIBILITY_SKIP: String get() = tr(R.string.phone_a11y_skip)
    val ACCESSIBILITY_SKIPPED: String get() = tr(R.string.phone_a11y_skipped)
    /** Where to go when the system blocks turning on accessibility (common for apps not installed from an app store). */
    val APP_INFO: String get() = tr(R.string.phone_app_info)

    /** The pairing step: with accessibility on, Claire reads the code herself. */
    val AUTO_READ_HINT: String get() = tr(R.string.phone_auto_read)
    /** Fallback when the code cannot be read: the enter-code notification is quietly waiting in the notification shade from the start. */
    val AUTO_READ_FALLBACK: String get() = tr(R.string.phone_auto_read_fallback, NOTIFY_ENTER_TITLE)
    /** Three-step guide shown when accessibility is off: tap the system's pair button, watch for the notification popping up at the top, and enter the code in the notification. */
    val NOTIFY_GUIDE: List<String> get() = listOf(
        tr(R.string.phone_guide_1), tr(R.string.phone_guide_2), tr(R.string.phone_guide_3, NOTIFY_ACTION),
    )

    /** Fallback for the steps that read a switch: some phones cannot read it or read it inaccurately, so once the user confirms it is on, move on. */
    val TURNED_ON_NEXT: String get() = tr(R.string.phone_turned_on_next)
    /** When tapping back to an earlier step to redo it: if nothing was changed, go back. */
    val REVISIT_BACK: String get() = tr(R.string.phone_revisit_back)
    val REVISIT_NOTE: String get() = tr(R.string.phone_revisit_note)

    /** How to word the step most likely to need redoing when pairing or connecting fails. */
    fun suspect(s: SetupSuspect): String = when {
        s.step == SetupStep.DeveloperOptions -> tr(R.string.phone_suspect_dev)
        s.unreadable -> tr(R.string.phone_suspect_wireless_unreadable)
        else -> tr(R.string.phone_suspect_wireless_off)
    }

    fun goBackTo(s: SetupStep): String = tr(R.string.phone_suspect_go_back, step(s).title)
    val PORT_FOUND: String get() = tr(R.string.phone_port_found)
    val NEED_NOTIFICATIONS: String get() = tr(R.string.phone_need_notif)
    val ALLOW_NOTIFICATIONS: String get() = tr(R.string.explain_notif)
    /** The "Pairing" notification is off: the pairing code cannot be entered, so turn it on first. */
    val PAIRING_CHANNEL_OFF: String get() = tr(R.string.phone_pairing_channel_off, CHANNEL_NAME)
    val OPEN_PAIRING_CHANNEL: String get() = tr(R.string.phone_open_pairing_channel, CHANNEL_NAME)
    /** The user set the "Pairing" notification to no longer pop up: the shade still works for entering the code, so just mention it and offer an entry point. */
    val PAIRING_LOWERED: String get() = tr(R.string.phone_pairing_lowered, CHANNEL_NAME)
    /** Notifications are allowed, but "Needs you" and "Task results" are set to no longer pop up: this does not block later steps, but it must be made clear. */
    val HEADS_UP_LOWERED: String get() = tr(R.string.phone_heads_up_lowered)
    val OPEN_HEADS_UP: String get() = tr(R.string.heads_up_action)
    val MANUAL_TOGGLE: String get() = tr(R.string.phone_manual_toggle)
    val MANUAL_HINT: String get() = tr(R.string.phone_manual_hint)
    val CODE_LABEL: String get() = tr(R.string.phone_code_label)
    val PORT_LABEL: String get() = tr(R.string.phone_port_label)
    val PORT_HINT: String get() = tr(R.string.phone_port_hint)
    val SUBMIT: String get() = tr(R.string.phone_submit)
    val CODE_INVALID: String get() = tr(R.string.phone_code_invalid)
    val PORT_INVALID: String get() = tr(R.string.phone_port_invalid)

    val WORKING: String get() = tr(R.string.phone_working)
    /** None of the earlier approaches found a port, so the device itself is being scanned. */
    val SCANNING_PORTS: String get() = tr(R.string.phone_scanning_ports)
    /** When connecting fails, have the user enter the port shown on the "Wireless debugging" screen. */
    val CONNECT_PORT_LABEL: String get() = tr(R.string.phone_connect_port_label)
    val CONNECT_PORT_HINT: String get() = tr(R.string.phone_connect_port_hint)
    val CONNECT_PORT_SUBMIT: String get() = tr(R.string.phone_connect_port_submit)
    val SUCCESS_LINE: String get() = tr(R.string.phone_success)
    /** Reminder at the bottom of the success page: how to bring the helper back after a phone restart depends on how it was last started. */
    fun successNote(restart: HelperRestart): String = when (restart) {
        HelperRestart.UsbCommand -> tr(R.string.phone_success_note_usb)
        HelperRestart.Shizuku -> tr(R.string.phone_success_note_shizuku)
        HelperRestart.Wireless, HelperRestart.None -> tr(R.string.phone_success_note)
    }
    /** Bottom button after the last onboarding step succeeds; when entered from Settings it is [SUCCESS_DONE]. */
    val START_USING: String get() = tr(R.string.phone_start_using)
    val SUCCESS_DONE: String get() = tr(R.string.done)

    /** The other path: start the helper from a computer over a USB cable (the command itself is not translated). Shown only when needed; [USB_ASK] is the line in the wireless debugging step that lets the user bring it up. */
    val USB_ASK: String get() = tr(R.string.phone_usb_ask)
    val USB_TITLE: String get() = tr(R.string.phone_usb_title)
    val USB_BODY: String get() = tr(R.string.phone_usb_body)
    val USB_COPY: String get() = tr(R.string.phone_usb_copy)
    val USB_COPIED: String get() = tr(R.string.phone_usb_copied)

    /** Title and description of the "Start from a computer instead" card: when the helper was started with this command all along, say to run it again rather than "Wireless debugging won't turn on". */
    fun usbCard(restart: HelperRestart): Pair<String, String> =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usb_again_title) to tr(R.string.phone_usb_again_body)
        else USB_TITLE to USB_BODY

    data class FailureCopy(val title: String, val body: String)

    fun failure(f: BuiltinShellFailure): FailureCopy = when (f) {
        BuiltinShellFailure.WrongCode -> FailureCopy(
            tr(R.string.fail_wrong_code),
            tr(R.string.fail_wrong_code_body),
        )
        BuiltinShellFailure.PairingWindowClosed -> FailureCopy(
            tr(R.string.fail_window_closed),
            tr(R.string.fail_window_closed_body),
        )
        BuiltinShellFailure.NoWifi -> FailureCopy(
            tr(R.string.fail_no_wifi),
            tr(R.string.fail_no_wifi_body),
        )
        BuiltinShellFailure.WifiNotAllowed -> FailureCopy(
            tr(R.string.fail_wifi_not_allowed),
            tr(R.string.fail_wifi_not_allowed_body),
        )
        BuiltinShellFailure.ConnectFailed -> FailureCopy(
            tr(R.string.fail_connect),
            tr(R.string.fail_connect_body),
        )
        BuiltinShellFailure.NotLocal -> FailureCopy(
            tr(R.string.fail_not_local),
            tr(R.string.fail_not_local_body),
        )
        BuiltinShellFailure.AuthorizationRevoked -> FailureCopy(
            tr(R.string.fail_revoked),
            tr(R.string.fail_revoked_body),
        )
        BuiltinShellFailure.KeyUnreadable -> FailureCopy(
            tr(R.string.fail_key_unreadable),
            tr(R.string.fail_key_unreadable_body),
        )
        BuiltinShellFailure.HelperStartFailed -> FailureCopy(
            tr(R.string.fail_helper),
            tr(R.string.fail_helper_body),
        )
        BuiltinShellFailure.UsbHelperStopped -> FailureCopy(
            tr(R.string.fail_usb_stopped),
            tr(R.string.fail_usb_stopped_body),
        )
        BuiltinShellFailure.RootDenied -> FailureCopy(
            tr(R.string.fail_root_denied),
            tr(R.string.fail_root_denied_body),
        )
        BuiltinShellFailure.RootNoAnswer -> FailureCopy(
            tr(R.string.fail_root_no_answer),
            tr(R.string.fail_root_no_answer_body),
        )
        BuiltinShellFailure.RootUnavailable -> FailureCopy(
            tr(R.string.fail_root_gone),
            tr(R.string.fail_root_gone_body),
        )
        BuiltinShellFailure.RootNoShellUid -> FailureCopy(
            tr(R.string.fail_root_no_shell),
            tr(R.string.fail_root_no_shell_body),
        )
        BuiltinShellFailure.ShizukuNotInstalled -> FailureCopy(
            tr(R.string.fail_shizuku_not_installed),
            tr(R.string.fail_shizuku_not_installed_body),
        )
        BuiltinShellFailure.ShizukuNotRunning -> FailureCopy(
            tr(R.string.fail_shizuku_not_running),
            tr(R.string.fail_shizuku_not_running_body),
        )
        BuiltinShellFailure.ShizukuPermissionNeeded -> FailureCopy(
            tr(R.string.fail_shizuku_permission),
            tr(R.string.fail_shizuku_permission_body),
        )
        BuiltinShellFailure.ShizukuBindFailed -> FailureCopy(
            tr(R.string.fail_shizuku_bind_failed),
            tr(R.string.fail_shizuku_bind_failed_body),
        )
        BuiltinShellFailure.Unknown -> FailureCopy(
            tr(R.string.fail_unknown),
            tr(R.string.fail_unknown_body),
        )
    }

    fun fixLabel(fix: BuiltinShellFix): String = when (fix) {
        BuiltinShellFix.Retry -> tr(R.string.retry)
        BuiltinShellFix.RePair -> tr(R.string.phone_repair)
        BuiltinShellFix.OpenWifi -> tr(R.string.phone_open_wifi)
        BuiltinShellFix.OpenWirelessDebugging -> tr(R.string.phone_open_wireless)
        BuiltinShellFix.RunUsbCommand -> USB_COPY
    }

    // ---------- Settings page ----------

    val RECONNECT: String get() = tr(R.string.phone_reconnect)

    /** Already paired, just not connected or the helper dropped: one tap reconnects with the saved pairing key, no need to go back to the onboarding page. */
    fun canReconnect(s: BuiltinShellStatus): Boolean =
        s is BuiltinShellStatus.Failed &&
            s.fix == BuiltinShellFix.Retry &&
            s.reason != BuiltinShellFailure.ShizukuNotInstalled &&
            s.reason != BuiltinShellFailure.ShizukuPermissionNeeded

    val ROW_TITLE: String get() = tr(R.string.settings_phone_ops)
    val ROW_SUBTITLE: String get() = tr(R.string.phone_row_sub)

    /** Full note at the bottom of Settings › Phone control: when the helper was only ever started with the USB start command, say the command has to be run again after a restart instead of telling the user to turn on Wireless debugging. */
    fun usageNote(restart: HelperRestart): String = when (restart) {
        HelperRestart.UsbCommand -> tr(R.string.phone_usage_note_usb)
        HelperRestart.Shizuku -> tr(R.string.phone_usage_note_shizuku)
        HelperRestart.Wireless, HelperRestart.None -> tr(R.string.phone_usage_note)
    }

    /** Summary of the note above, permanently one line; the full text is collected under "Details". */
    fun usageSummary(restart: HelperRestart): String =
        if (restart == HelperRestart.UsbCommand) tr(R.string.phone_usage_summary_usb) else tr(R.string.phone_usage_summary)

    fun statusValue(s: BuiltinShellStatus): Pair<String, ValueTone> = when (s) {
        BuiltinShellStatus.Running -> tr(R.string.sandbox_ready) to ValueTone.Ok
        BuiltinShellStatus.NotPaired -> tr(R.string.settings_not_set) to ValueTone.Bad
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_wireless_off) to ValueTone.Bad
        BuiltinShellStatus.Connecting -> tr(R.string.phone_connecting) to ValueTone.Plain
        // Mika 暂停着:不是故障,用户亲手停的。
        BuiltinShellStatus.Paused -> tr(R.string.pause_helper_paused) to ValueTone.Plain
        is BuiltinShellStatus.Failed -> tr(R.string.phone_error) to ValueTone.Bad
    }

    /**
     * Description for the status row in "Advanced". When [usbDebugging] is off, even a running helper gets a reminder: it stops once Wi-Fi drops (helpers started via root or Shizuku do not rely on Wi-Fi, so no reminder).
     * [rootStart]: the watchdog starts the helper via su (the user turned on "Start with root" and it works); while connecting, say it is starting with root.
     * [shizukuStart]: the helper is currently started preferentially via Shizuku.
     */
    fun statusLine(
        s: BuiltinShellStatus,
        usbDebugging: Boolean = true,
        rootStart: Boolean = false,
        shizukuStart: Boolean = false,
    ): String = when (s) {
        BuiltinShellStatus.Running -> when {
            shizukuStart -> SHIZUKU_RUNNING_LINE
            usbDebugging || rootStart -> tr(R.string.phone_line_running)
            else -> tr(R.string.phone_line_running_no_usb)
        }
        BuiltinShellStatus.NotPaired -> tr(R.string.phone_line_not_paired)
        BuiltinShellStatus.WirelessDebuggingOff -> tr(R.string.phone_line_wireless_off)
        BuiltinShellStatus.Connecting -> when {
            shizukuStart -> SHIZUKU_STARTING_LINE
            rootStart -> ROOT_STARTING_LINE
            else -> tr(R.string.phone_connecting)
        }
        // Mika 暂停着:说清是用户亲手停的、停了什么。
        BuiltinShellStatus.Paused -> tr(R.string.pause_paused_body)
        is BuiltinShellStatus.Failed -> when (s.reason) {
            BuiltinShellFailure.NoWifi -> NO_WIFI_LINE
            BuiltinShellFailure.WifiNotAllowed -> tr(R.string.phone_line_joined, failure(s.reason).title, WIFI_NOT_ALLOWED_LINE)
            // It stopped and needs the user to act: say directly how to start it again
            BuiltinShellFailure.UsbHelperStopped, in ROOT_FAILURES, in SHIZUKU_FAILURES -> failure(s.reason).body
            else -> failure(s.reason).title
        }
    }

    val NO_WIFI_LINE: String get() = tr(R.string.phone_no_wifi_line)
    val ROOT_STARTING_LINE: String get() = tr(R.string.phone_root_line_starting)
    val SHIZUKU_STARTING_LINE: String get() = tr(R.string.phone_shizuku_line_starting)
    val SHIZUKU_RUNNING_LINE: String get() = tr(R.string.phone_shizuku_line_running)

    /** The reasons "Start with root" cannot be used: both the status line and the banner say plainly why and what to do. */
    val ROOT_FAILURES = setOf(
        BuiltinShellFailure.RootDenied, BuiltinShellFailure.RootNoAnswer, BuiltinShellFailure.RootUnavailable, BuiltinShellFailure.RootNoShellUid,
    )

    val SHIZUKU_FAILURES = setOf(
        BuiltinShellFailure.ShizukuNotInstalled,
        BuiltinShellFailure.ShizukuNotRunning,
        BuiltinShellFailure.ShizukuPermissionNeeded,
        BuiltinShellFailure.ShizukuBindFailed,
    )

    // ---------- root ----------

    val ROOT_GROUP: String get() = tr(R.string.phone_root_group)
    val ROOT_STATUS_TITLE: String get() = tr(R.string.phone_root_status)
    val ROOT_AVAILABLE: String get() = tr(R.string.phone_root_available)
    val ROOT_AUTO: String get() = tr(R.string.phone_root_auto)
    val ROOT_START: String get() = tr(R.string.phone_root_start)
    val ROOT_COMMANDS_GROUP: String get() = tr(R.string.phone_root_commands_group)
    val ROOT_COMMANDS: String get() = tr(R.string.phone_root_commands)
    val ROOT_COMMANDS_TITLE: String get() = tr(R.string.phone_root_commands_title)
    val ROOT_COMMANDS_WARNING: String get() = tr(R.string.phone_root_commands_warning)
    val ROOT_COMMANDS_CONFIRM: String get() = tr(R.string.phone_root_commands_confirm)
    val ROOT_NOTE: String get() = tr(R.string.phone_root_note)
    val ROOT_COMMANDS_NOTE: String get() = tr(R.string.phone_root_commands_note)
    val ROOT_PENDING: String get() = tr(R.string.phone_root_pending)

    /** The Root entry under Advanced & other: auto-starting the helper is a capability, and the command switch state is described separately. */
    fun rootEntryLine(commands: Boolean): String =
        tr(if (commands) R.string.phone_root_entry_on else R.string.phone_root_entry_off)

    /** The Root status card only covers the capability and the helper's authorization; retry actions after a failure live on the tappable helper row below, so no "tap here" in this card. */
    fun rootStatusLine(grant: RootGrant): String =
        tr(if (grant == RootGrant.Granted) R.string.phone_root_authorized else R.string.phone_root_unauthorized)

    /** The auto-start row: when unauthorized it explains to tap here to authorize, once authorized it explains automatic restore, after a failure it explains how to retry. */
    fun rootStartLine(grant: RootGrant): String = when (grant) {
        RootGrant.Pending -> ROOT_PENDING
        RootGrant.Denied -> tr(R.string.phone_root_denied_sub)
        RootGrant.NoAnswer -> tr(R.string.phone_root_no_answer_sub)
        RootGrant.NoShellUid -> tr(R.string.phone_root_no_shell_sub)
        RootGrant.Unknown -> tr(R.string.phone_root_authorize)
        RootGrant.Granted -> tr(R.string.phone_root_start_sub)
    }

    /** Description below the "Allow Claire to run root commands" row. */
    fun rootCommandsLine(on: Boolean, grant: RootGrant): String = when {
        grant == RootGrant.Pending -> ROOT_PENDING
        !on && (grant == RootGrant.Denied || grant == RootGrant.NoAnswer) -> tr(R.string.phone_root_commands_denied)
        on -> tr(R.string.phone_root_commands_on)
        else -> tr(R.string.phone_root_commands_sub)
    }
    val WIFI_NOT_ALLOWED_LINE: String get() = tr(R.string.phone_wifi_not_allowed_line)

    val REPAIR: String get() = tr(R.string.phone_repair)
    val REPAIR_SUBTITLE: String get() = tr(R.string.phone_repair_sub)
    val UNPAIR: String get() = tr(R.string.phone_unpair)
    val UNPAIR_SUBTITLE: String get() = tr(R.string.phone_unpair_sub)
    val UNPAIR_TITLE: String get() = tr(R.string.phone_unpair_title)
    val UNPAIR_BODY: String get() = tr(R.string.phone_unpair_body)
    val UNPAIR_CONFIRM: String get() = tr(R.string.phone_unpair_confirm)

    // ---------- Chat page (when phone control is unavailable) ----------

    val DEGRADED_LINE: String get() = tr(R.string.phone_degraded)
    val DEGRADED_ACTION: String get() = tr(R.string.chat_go_settings)

    // ---------- Notifications ----------

    val CHANNEL_NAME: String get() = tr(R.string.phone_channel)
    val NOTIFY_ENTER_TITLE: String get() = tr(R.string.phone_notify_enter_title)
    val NOTIFY_ENTER_TEXT: String get() = tr(R.string.phone_notify_enter_text)
    val NOTIFY_WINDOW_FOUND: String get() = tr(R.string.phone_notify_window_found)
    /** What was entered is not 6 digits: say so right there in the notification, do not attempt it. */
    val NOTIFY_CODE_INVALID: String get() = tr(R.string.phone_notify_code_invalid)

    /** What the notification says when the pairing stage fails: a wrong code, an expired code or a closed window all mean fetching a new code and entering it again. */
    fun notifyRetry(f: BuiltinShellFailure): String = when (f) {
        BuiltinShellFailure.WrongCode -> tr(R.string.phone_notify_retry_code)
        BuiltinShellFailure.PairingWindowClosed -> tr(R.string.phone_notify_retry_window)
        else -> failure(f).title
    }
    val NOTIFY_ACTION: String get() = tr(R.string.phone_notify_action)
    val NOTIFY_INPUT_LABEL: String get() = tr(R.string.phone_notify_input)
    val NOTIFY_WORKING: String get() = tr(R.string.phone_notify_working)
    val NOTIFY_DONE_TITLE: String get() = tr(R.string.phone_notify_done_title)
    val NOTIFY_DONE_TEXT: String get() = tr(R.string.phone_notify_done_text)
    val NOTIFY_PROBLEM_TEXT: String get() = tr(R.string.phone_notify_problem)
}
