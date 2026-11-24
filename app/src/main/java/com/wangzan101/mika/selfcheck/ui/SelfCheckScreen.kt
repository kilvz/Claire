package com.wangzan101.mika.selfcheck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.core.selfcheck.Advice
import com.wangzan101.mika.core.selfcheck.CheckOutcome
import com.wangzan101.mika.core.selfcheck.CheckResult
import com.wangzan101.mika.core.selfcheck.CheckStatus
import com.wangzan101.mika.core.selfcheck.SelfCheck
import com.wangzan101.mika.core.selfcheck.SelfCheckReport
import com.wangzan101.mika.core.selfcheck.SelfCheckRunner
import com.wangzan101.mika.core.selfcheck.SelfCheckState
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.selfcheck.AndroidSelfCheck
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wangzan101.mika.chat.ui.isActive
import com.wangzan101.mika.ui.openInBrowser

/** Text of the compatibility self-check page (pure functions, so they are easy to test): each item's name, its status, and the plain-language sentence below it. */
object SelfCheckCopy {
    fun name(c: SelfCheck): String = tr(
        when (c) {
            SelfCheck.DEVICE -> R.string.selfcheck_check_device
            SelfCheck.PERMISSIONS -> R.string.selfcheck_check_permissions
            SelfCheck.HELPER -> R.string.selfcheck_check_helper
            SelfCheck.VIRTUAL_DISPLAY -> R.string.selfcheck_check_display
            SelfCheck.APP_LAUNCH -> R.string.selfcheck_check_launch
            SelfCheck.SCREEN_READING -> R.string.selfcheck_check_reading
            SelfCheck.ACTIONS -> R.string.selfcheck_check_actions
            SelfCheck.STAYS_ALIVE -> R.string.selfcheck_check_alive
            SelfCheck.MAIN_SCREEN -> R.string.selfcheck_check_main
            SelfCheck.BROWSER -> R.string.selfcheck_check_browser
            SelfCheck.SANDBOX -> R.string.selfcheck_check_sandbox
            SelfCheck.APP_COPIES -> R.string.selfcheck_check_copies
        },
    )

    fun status(s: CheckStatus): String = tr(
        when (s) {
            CheckStatus.Pending -> R.string.selfcheck_status_pending
            CheckStatus.Running -> R.string.selfcheck_status_running
            CheckStatus.Pass -> R.string.selfcheck_status_pass
            CheckStatus.Partial -> R.string.selfcheck_status_partial
            CheckStatus.Fail -> R.string.selfcheck_status_fail
            CheckStatus.Skipped -> R.string.selfcheck_status_skipped
        },
    )

    /** The sentence below an item: when it did not pass, the reason and the next step; when it was skipped because a previous item did not pass, which item is missing. */
    fun line(r: CheckResult): String {
        r.blockedBy?.let { return tr(R.string.selfcheck_line_needs, name(it)) }
        r.advice?.let { return advice(it) }
        return tr(
            when (r.status) {
                CheckStatus.Pending -> R.string.selfcheck_line_pending
                CheckStatus.Running -> R.string.selfcheck_line_running
                CheckStatus.Pass -> R.string.selfcheck_line_pass
                else -> R.string.selfcheck_line_other
            },
        )
    }

    fun advice(a: Advice): String = tr(
        when (a) {
            Advice.PhoneTooOld -> R.string.selfcheck_advice_too_old
            Advice.NoWirelessDebugging -> R.string.selfcheck_advice_no_wireless
            Advice.PhoneControlUnavailable -> R.string.selfcheck_advice_phone_unavailable
            Advice.TurnOnAccessibility -> R.string.selfcheck_advice_a11y_off
            Advice.RestartAccessibility -> R.string.selfcheck_advice_a11y_restart
            Advice.AllowNotifications -> R.string.selfcheck_advice_notifications
            Advice.AllowBattery -> R.string.selfcheck_advice_battery
            Advice.SetUpPhoneControl -> R.string.selfcheck_advice_setup
            Advice.DisplayFailed -> R.string.selfcheck_advice_display
            Advice.FocusShared -> R.string.selfcheck_advice_focus
            Advice.LaunchFailed -> R.string.selfcheck_advice_launch
            Advice.OpenedOnMainScreen -> R.string.selfcheck_advice_on_main
            Advice.SettingsInUse -> R.string.selfcheck_advice_settings_in_use
            Advice.ReadFailed -> R.string.selfcheck_advice_read
            Advice.BlankFrames -> R.string.selfcheck_advice_blank
            Advice.ActionFailed -> R.string.selfcheck_advice_action
            Advice.FramesStopped -> R.string.selfcheck_advice_frames
            Advice.MainScreenChanged -> R.string.selfcheck_advice_main
            Advice.NoWebView -> R.string.selfcheck_advice_no_webview
            Advice.WebFailed -> R.string.selfcheck_advice_web
            Advice.SandboxUnsupported -> R.string.selfcheck_advice_sandbox_unsupported
            Advice.SandboxNotPrepared -> R.string.selfcheck_advice_sandbox_not_prepared
            Advice.SandboxFailed -> R.string.selfcheck_advice_sandbox_failed
            Advice.TimedOut -> R.string.selfcheck_advice_timeout
            Advice.Error -> R.string.selfcheck_advice_error
            Advice.OutOfTime -> R.string.selfcheck_advice_out_of_time
            Advice.Cancelled -> R.string.selfcheck_advice_cancelled
        },
    )
}

/**
 * Settings › Recovery and repair › Compatibility self-check: hooked up to the real self-check. Leaving this page cancels it, and cancelling releases the virtual display, the self-check page and the web tabs on the spot.
 * It cannot be started while a task is running (the two would compete for attention on the same main screen). [status] is the system status at this moment.
 * There is also a manual screen-off self-check: after the main screen is off and locked it verifies whether the stack the tasks use still works; the steps are explained on the page before it starts.
 */
@Composable
fun SelfCheckScreen(status: SystemStatus, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val current = rememberUpdatedState(status)
    val taskBusy by app.taskState.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var runner by remember { mutableStateOf<SelfCheckRunner?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var screenOffJob by remember { mutableStateOf<Job?>(null) }
    var screenOffRunning by remember { mutableStateOf(false) }
    val screenOffResults = remember { MutableStateFlow(emptyList<Pair<Int, CheckOutcome>>()) }
    val screenOffReport by screenOffResults.collectAsState()
    val idle = remember { MutableStateFlow(SelfCheckState()) }
    val state by (runner?.state ?: idle).collectAsState()
    SelfCheckContent(
        state = state,
        taskBusy = taskBusy,
        onStart = {
            val r = SelfCheckRunner(AndroidSelfCheck(context) { current.value })
            runner = r
            // Reading the screen and calling the assistant are blocking calls: do not run them on the main thread
            job = scope.launch(Dispatchers.Default) { r.run() }
        },
        onCancel = { job?.cancel() },
        onCopy = { copyReport(context, render(state), tr(R.string.selfcheck_copied)) },
        onGithub = {
            copyReport(context, render(state), tr(R.string.selfcheck_github_copied))
            openInBrowser(context, SelfCheckReport.issueUrl(MikaLinks.NEW_ISSUE, state.facts))
        },
        onBack = onBack,
        canStart = !screenOffRunning,
        screenOffContent = {
            if (state.phase != SelfCheckState.Phase.Running) {
                MikaButton(
                    stringResource(if (screenOffRunning) R.string.selfcheck_cancel else R.string.selfcheck_screen_off_start),
                    onClick = {
                        if (screenOffRunning) {
                            screenOffJob?.cancel()
                        } else if (job?.isActive != true) {
                            screenOffResults.value = emptyList()
                            screenOffRunning = true
                            screenOffJob = scope.launch {
                                try {
                                    withContext(Dispatchers.Default) {
                                        AndroidSelfCheck(context) { current.value }.checkScreenOff({ app.taskState.busy.value }) { stage, result ->
                                            screenOffResults.update { it + (stage to result) }
                                        }
                                    }
                                } finally {
                                    screenOffRunning = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("selfcheck-screen-off-start"),
                    enabled = screenOffRunning || !taskBusy,
                    kind = ButtonKind.Tonal,
                )
                if (screenOffRunning && screenOffReport.isEmpty()) FooterNote(stringResource(R.string.selfcheck_screen_off_wait))
                if (screenOffReport.isNotEmpty()) {
                    val report = screenOffReport.joinToString("\n") { (stage, result) ->
                        "${tr(stage)} · ${SelfCheckCopy.status(result.status)} — ${result.detail}"
                    }
                    FooterNote(report)
                    if (!screenOffRunning) MikaButton(
                        stringResource(R.string.selfcheck_copy),
                        onClick = { copyReport(context, report, tr(R.string.selfcheck_copied)) },
                        kind = ButtonKind.Tonal,
                    )
                }
            }
        },
    )
}

private fun render(s: SelfCheckState) = SelfCheckReport.render(s, Diagnostics::sanitize)

private fun copyReport(context: Context, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(SelfCheckReport.HEADER, text))
    Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
}

/** The self-check page without state, for easier testing: an intro and start / cancel at the top, one row per item below; when finished it gives the full report plus two buttons for copying and for going to GitHub. */
@Composable
fun SelfCheckContent(
    state: SelfCheckState,
    taskBusy: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onGithub: () -> Unit,
    onBack: () -> Unit,
    canStart: Boolean = true,
    screenOffContent: (@Composable () -> Unit)? = null,
) {
    val running = state.phase == SelfCheckState.Phase.Running
    PageScaffold(stringResource(R.string.selfcheck_title), onBack) {
        FooterNote(stringResource(R.string.selfcheck_intro))
        if (running) {
            MikaButton(stringResource(R.string.selfcheck_cancel), onCancel, Modifier.fillMaxWidth().testTag("selfcheck-cancel"), kind = ButtonKind.Tonal)
        } else {
            MikaButton(
                stringResource(if (state.ended) R.string.selfcheck_again else R.string.selfcheck_start), onStart,
                Modifier.fillMaxWidth().testTag("selfcheck-start"), enabled = !taskBusy && canStart,
            )
            if (taskBusy) FooterNote(stringResource(R.string.selfcheck_task_running), Modifier.padding(top = 8.dp), color = MikaTheme.colors.warn)
        }

        screenOffContent?.invoke()
        GroupLabel(stringResource(R.string.selfcheck_checks), Modifier.padding(top = 12.dp))
        GroupCard {
            state.results.forEachIndexed { i, r ->
                val (icon, tone) = look(r.status)
                ListRow(
                    icon, SelfCheckCopy.name(r.check), SelfCheckCopy.line(r), first = i == 0, tone = tone,
                ) { RowValue(SelfCheckCopy.status(r.status), valueTone(r.status)) }
            }
        }

        if (state.ended) {
            val text = remember(state) { render(state) }
            GroupLabel(stringResource(R.string.selfcheck_report))
            val c = MikaTheme.colors
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp).testTag("selfcheck-report")
                    .background(c.fill, MikaShapes.Chip).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(text, fontFamily = FontFamily.Monospace, style = MikaType.Hint, color = c.ink2)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MikaButton(stringResource(R.string.selfcheck_copy), onCopy, Modifier.weight(1f).testTag("selfcheck-copy"))
                MikaButton(stringResource(R.string.selfcheck_github), onGithub, Modifier.weight(1f).testTag("selfcheck-github"), kind = ButtonKind.Tonal)
            }
            FooterNote(stringResource(R.string.selfcheck_report_note))
        }
    }
}

private fun look(s: CheckStatus): Pair<ImageVector, TileTone> = when (s) {
    CheckStatus.Pending -> MikaIcons.Clock to TileTone.Neutral
    CheckStatus.Running -> MikaIcons.Refresh to TileTone.Accent
    CheckStatus.Pass -> MikaIcons.Check to TileTone.Ok
    CheckStatus.Partial -> MikaIcons.Exclaim to TileTone.Warn
    CheckStatus.Fail -> MikaIcons.Close to TileTone.Bad
    CheckStatus.Skipped -> MikaIcons.More to TileTone.Neutral
}

private fun valueTone(s: CheckStatus): ValueTone = when (s) {
    CheckStatus.Pass -> ValueTone.Ok
    CheckStatus.Partial -> ValueTone.Warn
    CheckStatus.Fail -> ValueTone.Bad
    else -> ValueTone.Plain
}
