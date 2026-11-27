package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.ChannelLevel
import com.wangzan101.mika.settings.ChannelRow
import com.wangzan101.mika.settings.NotificationSummary
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.ui.components.ValueTone

/** Fix actions the settings page can carry out directly. */
enum class Fix {
    OpenAccessibility, AllowNotifications, AddModel,
    /** Open the settings page of the "Needs you" / "Task results" / "Task progress" channel directly and turn on "Pops up"; if that cannot be opened, fall back to the app's notification overview page. */
    OpenAttentionChannel, OpenResultChannel, OpenProgressChannel,
    OpenLiveTaskSettings,
    /** Settings page of the "Pairing" notification channel: the pairing code has to be entered in that kind of notification. */
    OpenPairingChannel,
    /** This app's "App info" page: when the system blocks turning on accessibility, allow restricted settings there. */
    OpenAppInfo,
    // Built-in helper
    OpenPhoneSetup, RepairPhoneHelper, RetryPhoneHelper, UnpairPhoneHelper, OpenPhoneOps,
    OpenAboutPhone, OpenDeveloperOptions, OpenUsbDebugging, OpenWirelessDebugging, OpenWifi,
    /** 打开设置页(「暂停 Mika」在设置首页就有入口):Mika 暂停着时横幅点的就是它。 */
    OpenPause,
}

/** One line of text for the top banner plus the link on its right. */
data class BannerCopy(val title: String, val detail: String, val link: String)

/** Explanation shown when a problem is tapped open: plain language covering what it is and what to do; an empty [fix] means there is no one-tap fix. */
data class Explanation(
    val title: String,
    val body: String,
    val action: String?,
    val fix: Fix?,
    val secondaryAction: Pair<String, Fix>? = null,
)

object StatusCopy {
    /** Currently calling through an MCP connector (task card, the capsule's "what it's doing"). */
    fun callingMcp(connector: String): String = tr(R.string.progress_calling_mcp, connector)

    /** Has already called through an MCP connector (step list, timeline). */
    fun calledMcp(connector: String): String = tr(R.string.progress_called_mcp, connector)

    /** Wording on the card and notification when several steps run in a row without the model. */
    fun progress(p: RunProgress): String = when (p) {
        is RunProgress.Replay -> tr(R.string.progress_replay, p.step, p.total)
        is RunProgress.Batch -> trPlural(R.plurals.progress_batch, p.steps)
        is RunProgress.Task -> p.goals?.let(::goals) ?: p.display
        // Only waiting for another task; the user does not need to do anything
        is RunProgress.WaitingForApp -> tr(R.string.progress_waiting_app, p.app)
    }

    fun goals(goals: GoalChecklist) = tr(
        if (goals.stale) R.string.chat_last_confirmed_goals else R.string.chat_confirmed_goals,
        goals.confirmed.size, goals.items.size,
    )

    /** Item, amount and address of a payment takeover, one per line; null when all are absent. Shared by the card and the notification. */
    fun paymentLines(item: String?, amount: String?, address: String?): String? =
        listOfNotNull(
            item?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_item, it) },
            amount?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_amount, it) },
            address?.takeIf { it.isNotBlank() }?.let { tr(R.string.payment_address, it) },
        ).joinToString("\n").ifEmpty { null }

    /** Accessibility is still unreachable when the task starts: enabled and not enabled are handled differently, so they cannot both be described as off. */
    fun accessibilityUnavailable(enabled: Boolean): String =
        tr(if (enabled) R.string.task_a11y_not_running else R.string.task_no_a11y)

    fun accessibilityValue(s: AccessibilityState): Pair<String, ValueTone> = when (s) {
        AccessibilityState.On -> tr(R.string.state_on) to ValueTone.Ok
        AccessibilityState.Off -> tr(R.string.state_off) to ValueTone.Bad
        AccessibilityState.EnabledNotRunning -> tr(R.string.state_not_running) to ValueTone.Bad
    }

    /** Problems that are fixed directly on tap, with no explanation needed. */
    fun directFix(p: Problem): Fix? = when (p) {
        Problem.NotificationsOff -> Fix.AllowNotifications
        is Problem.AttentionLowered -> headsUpFix(p)
        Problem.NoModel -> Fix.AddModel
        // The onboarding page explains every step, so no explanation is popped up again
        is Problem.PhoneHelper -> when {
            // Mika 暂停着:这不是故障,是用户亲手要的,去能恢复的那页。
            p.status == BuiltinShellStatus.Paused -> Fix.OpenPause
            PhoneSetupCopy.canReconnect(p.status) -> Fix.RetryPhoneHelper
            (p.status as? BuiltinShellStatus.Failed)?.reason == BuiltinShellFailure.ShizukuNotInstalled ||
                (p.status as? BuiltinShellStatus.Failed)?.reason == BuiltinShellFailure.ShizukuPermissionNeeded -> Fix.OpenPhoneOps
            // Wireless debugging is not allowed on this Wi-Fi yet: go turn it on by hand once (the system will ask whether to allow it)
            (p.status as? BuiltinShellStatus.Failed)?.fix == BuiltinShellFix.OpenWirelessDebugging -> Fix.OpenWirelessDebugging
            else -> Fix.OpenPhoneSetup
        }
        else -> null
    }

    // ---- Settings › Notifications ----
    /** The small line shown after stopping, while the summary has not been written yet (same on the chat page and the floating card). */
    val STOP_SUMMARIZING: String get() = tr(R.string.stop_summarizing)
    val NOTIFICATIONS: String get() = tr(R.string.notifications_title)
    val PROGRESS_CHANNEL: String get() = tr(R.string.channel_progress)

    fun notificationSummary(s: NotificationSummary): Pair<String, ValueTone> = when (s) {
        NotificationSummary.Normal -> tr(R.string.notif_normal) to ValueTone.Ok
        is NotificationSummary.Lowered -> trPlural(R.plurals.notif_lowered, s.count) to ValueTone.Warn
        NotificationSummary.AllOff -> tr(R.string.state_turned_off) to ValueTone.Bad
    }

    fun channelLevelValue(row: ChannelRow): Pair<String, ValueTone> = when (row.level) {
        ChannelLevel.Off -> tr(R.string.state_turned_off) to if (row.warn) ValueTone.Warn else ValueTone.Plain
        ChannelLevel.HeadsUp -> tr(R.string.notif_heads_up) to ValueTone.Ok
        ChannelLevel.Quiet -> tr(R.string.notif_quiet) to if (row.warn) ValueTone.Warn else ValueTone.Plain
    }

    val NOTIF_APP_SUBTITLE_ON: String get() = tr(R.string.notif_app_on)
    val NOTIF_APP_SUBTITLE_OFF: String get() = tr(R.string.notif_app_off)
    val NOTIF_ATTENTION_WHEN: String get() = tr(R.string.notif_attention_when)
    val NOTIF_RESULT_WHEN: String get() = tr(R.string.notif_result_when)
    val NOTIF_PROGRESS_WHEN: String get() = tr(R.string.notif_progress_when)
    val NOTIF_FOOTER: String get() = tr(R.string.notif_footer)

    val ATTENTION_CHANNEL: String get() = tr(R.string.channel_attention)
    val RESULT_CHANNEL: String get() = tr(R.string.channel_result)
    val HEADS_UP_ACTION: String get() = tr(R.string.heads_up_action)

    /** Of the two lowered channels, handle "Needs you" first. */
    fun headsUpFix(p: Problem.AttentionLowered): Fix = if (p.attention) Fix.OpenAttentionChannel else Fix.OpenResultChannel

    private fun headsUpTitle(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_title, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    private fun headsUpBody(p: Problem.AttentionLowered): String {
        val lead = if (p.attention) tr(R.string.heads_up_lead_attention) else tr(R.string.heads_up_lead_result)
        val also = if (p.attention && p.result) tr(R.string.heads_up_also, RESULT_CHANNEL) else ""
        return tr(R.string.heads_up_body, lead, also, HEADS_UP_ACTION)
    }

    private fun headsUpPath(p: Problem.AttentionLowered) =
        tr(R.string.heads_up_path, if (p.attention) ATTENTION_CHANNEL else RESULT_CHANNEL)

    fun banner(p: Problem): BannerCopy = when (p) {
        Problem.SystemTooOld -> BannerCopy(tr(R.string.banner_too_old), tr(R.string.banner_too_old_detail), tr(R.string.learn_more))
        is Problem.PhoneHelper -> when (val st = p.status) {
            BuiltinShellStatus.NotPaired -> BannerCopy(tr(R.string.banner_not_paired), tr(R.string.banner_not_paired_detail), tr(R.string.chat_go_settings))
            BuiltinShellStatus.WirelessDebuggingOff -> BannerCopy(tr(R.string.banner_wireless_off), tr(R.string.banner_wireless_off_detail), tr(R.string.heads_up_action))
            BuiltinShellStatus.Connecting -> BannerCopy(tr(R.string.banner_connecting), tr(R.string.banner_connecting_detail), tr(R.string.view))
            is BuiltinShellStatus.Failed ->
                if (st.reason == BuiltinShellFailure.NoWifi) BannerCopy(tr(R.string.banner_paused), PhoneSetupCopy.NO_WIFI_LINE, tr(R.string.view))
                // The computer-started helper stopped: state it plainly in the title, and say how to start it again in the line below
                else if (st.reason == BuiltinShellFailure.UsbHelperStopped) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body, tr(R.string.view),
                )
                // "Start with root" is unusable: the title gives the reason and the line below says what to do; for a denial or no answer, offer retry directly
                else if (st.reason in PhoneSetupCopy.ROOT_FAILURES) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.view),
                )
                else if (st.reason in PhoneSetupCopy.SHIZUKU_FAILURES) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.failure(st.reason).body,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.fix_it),
                )
                else if (st.reason == BuiltinShellFailure.WifiNotAllowed) BannerCopy(
                    PhoneSetupCopy.failure(st.reason).title, PhoneSetupCopy.WIFI_NOT_ALLOWED_LINE, tr(R.string.heads_up_action),
                )
                else BannerCopy(
                    tr(R.string.banner_failed), PhoneSetupCopy.failure(st.reason).title,
                    if (PhoneSetupCopy.canReconnect(st)) PhoneSetupCopy.RECONNECT else tr(R.string.fix_it),
                )
            // Mika 暂停着:标题说在暂停,下面一句说停了什么。
            BuiltinShellStatus.Paused -> BannerCopy(
                tr(R.string.pause_paused_title),
                tr(R.string.pause_paused_body),
                tr(R.string.view),
            )
            BuiltinShellStatus.Running -> error("not a problem")
        }
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> BannerCopy(
                tr(R.string.banner_a11y_off),
                tr(if (p.restrictedSettings) R.string.banner_a11y_restricted_detail else R.string.banner_a11y_off_detail),
                tr(R.string.turn_on_go),
            )
            // The service cannot bind before the first unlock after boot anyway: it recovers by itself after unlocking, so do not make the user turn it off and on
            AccessibilityState.EnabledNotRunning -> when {
                p.userLocked -> BannerCopy(tr(R.string.banner_a11y_locked), tr(R.string.banner_a11y_locked_detail), tr(R.string.view))
                p.restrictedSettings -> BannerCopy(
                    tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_restricted_detail), tr(R.string.fix_it),
                )
                else -> BannerCopy(tr(R.string.banner_a11y_not_running), tr(R.string.banner_a11y_not_running_detail), tr(R.string.fix_it))
            }
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> BannerCopy(tr(R.string.banner_no_model), tr(R.string.banner_no_model_detail), tr(R.string.add))
        Problem.NotificationsOff -> BannerCopy(tr(R.string.banner_notif_off), tr(R.string.banner_notif_off_detail), tr(R.string.allow))
        is Problem.AttentionLowered -> BannerCopy(headsUpTitle(p), headsUpBody(p) + "\n" + headsUpPath(p), HEADS_UP_ACTION)
        Problem.FocusMayLeak -> BannerCopy(tr(R.string.banner_focus), tr(R.string.banner_focus_detail), tr(R.string.learn_more))
    }

    fun explain(p: Problem, androidRelease: String): Explanation = when (p) {
        Problem.SystemTooOld -> Explanation(
            tr(R.string.explain_too_old),
            tr(R.string.explain_too_old_body, androidRelease),
            null, null,
        )
        is Problem.PhoneHelper -> Explanation(
            PhoneSetupCopy.TITLE, PhoneSetupCopy.INTRO, PhoneSetupCopy.DEGRADED_ACTION, Fix.OpenPhoneSetup,
        )
        is Problem.Accessibility -> when (p.state) {
            AccessibilityState.Off -> Explanation(
                tr(R.string.explain_a11y_off),
                tr(if (p.restrictedSettings) R.string.explain_a11y_restricted_off_body else R.string.explain_a11y_off_body),
                tr(R.string.chat_go_settings), Fix.OpenAccessibility,
                secondaryAction = if (p.restrictedSettings) PhoneSetupCopy.APP_INFO to Fix.OpenAppInfo else null,
            )
            AccessibilityState.EnabledNotRunning -> when {
                p.userLocked -> Explanation(
                    tr(R.string.banner_a11y_locked), tr(R.string.banner_a11y_locked_detail), null, null,
                )
                p.restrictedSettings -> Explanation(
                    tr(R.string.banner_a11y_not_running), tr(R.string.explain_a11y_restricted_body),
                    PhoneSetupCopy.APP_INFO, Fix.OpenAppInfo,
                )
                else -> Explanation(
                    tr(R.string.banner_a11y_not_running), tr(R.string.explain_a11y_not_running_body),
                    tr(R.string.chat_go_settings), Fix.OpenAccessibility,
                )
            }
            AccessibilityState.On -> error("not a problem")
        }
        Problem.NoModel -> Explanation(
            tr(R.string.explain_no_model), tr(R.string.explain_no_model_body),
            tr(R.string.add), Fix.AddModel,
        )
        Problem.FocusMayLeak -> Explanation(
            tr(R.string.explain_focus),
            tr(R.string.explain_focus_body, androidRelease),
            null, null,
        )
        is Problem.AttentionLowered -> Explanation(
            headsUpTitle(p), headsUpBody(p) + "\n\n" + headsUpPath(p), HEADS_UP_ACTION, headsUpFix(p),
        )
        Problem.NotificationsOff -> Explanation(
            tr(R.string.explain_notif), tr(R.string.explain_notif_body),
            tr(R.string.allow), Fix.AllowNotifications,
        )
    }

    /**
     * Description of the run-command step on the task card: "Working on files: <first 40 characters of the command>", with "online" noted when networking is allowed.
     * The runtime environment is invisible to users, so the text never says sandbox.
     */
    fun sandboxCommand(command: String, network: Boolean): String = sandboxLine(tr(R.string.sandbox_command_prefix), command, 40, network)

    /** While running a skill script: "Running a script". */
    fun sandboxScript(network: Boolean): String = tr(R.string.sandbox_script) + if (network) " · $SANDBOX_ONLINE" else ""

    /** Progress reported by `mika progress` inside a command. */
    fun sandboxProgress(text: String, network: Boolean): String = sandboxLine("", text, 60, network)

    val SANDBOX_ONLINE: String get() = tr(R.string.sandbox_online)

    /** When a tool is waiting for the runtime environment to be ready. */
    val RUNTIME_PREPARING: String get() = tr(R.string.runtime_preparing)

    private fun sandboxLine(prefix: String, text: String, max: Int, network: Boolean): String {
        // Strip control characters, zero-width and bidi override characters just like `mika progress`, so the card shows the actual command
        val one = com.wangzan101.mika.cap.sandbox.BridgeProtocol.cleanProgress(text.replace(Regex("\\s+"), " "))
        val cut = if (one.codePointCount(0, one.length) > max) one.substring(0, one.offsetByCodePoints(0, max)) + "…" else one
        return prefix + cut + if (network) " · $SANDBOX_ONLINE" else ""
    }
}
