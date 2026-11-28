package com.wangzan101.mika.settings.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.about.DiagnosticsCollector
import com.wangzan101.mika.about.setupInputs
import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellFix
import com.wangzan101.mika.core.device.shell.HelperRestart
import com.wangzan101.mika.core.device.shell.PairingCode
import com.wangzan101.mika.core.device.shell.PairingNotifyIssue
import com.wangzan101.mika.core.device.shell.PhoneSetupFlow
import com.wangzan101.mika.core.device.shell.SetupPhase
import com.wangzan101.mika.core.device.shell.SetupStep
import com.wangzan101.mika.core.device.shell.SetupView
import com.wangzan101.mika.core.device.shell.StepMark
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaRadius
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.StepRow
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.setup.ui.Onboarding
import com.wangzan101.mika.setup.ui.OnboardingStep
import com.wangzan101.mika.ui.DiagnosticsSheet

/** The steps the user has confirmed as done; they survive rotation and going to the background. */
private val StepSetSaver = Saver<Set<SetupStep>, String>(
    save = { set -> set.joinToString(",") { it.name } },
    restore = { s -> s.split(',').mapNotNull { n -> SetupStep.entries.firstOrNull { it.name == n } }.toSet() },
)

/**
 * "Set up phone control" onboarding page: wired to live state. Every step ticks itself off from the system switch, and when it cannot be read or read accurately the user taps "I've turned it on, next";
 * tapping an earlier step in the checklist goes back to redo it. While on the pairing step it starts looking for the pairing window: with accessibility on, Claire reads the pairing code herself, otherwise it posts a notification with an input field.
 * [repair] means the page was entered from "Re-pair" in Settings.
 */
@Composable
fun PhoneSetupScreen(status: SystemStatus, repair: Boolean, onFix: (Fix) -> Unit, onboarding: Boolean = false, onClose: () -> Unit) {
    val context = LocalContext.current
    val phone = remember { PhoneServices.of(context) }
    val pairing = phone.pairing
    val discovery by phone.shellControl.pairingDiscovery.collectAsState()
    val scanning by phone.shellManager.scanningPorts.collectAsState()
    val shell = status.builtinShell
    var repairStarted by rememberSaveable { mutableStateOf(false) }
    if (repair && !repairStarted) { repairStarted = true; pairing.startRepair() }
    val repairing by pairing.repairing.collectAsState()
    var confirmed by rememberSaveable(stateSaver = StepSetSaver) { mutableStateOf(emptySet<SetupStep>()) }
    var revisit by rememberSaveable { mutableStateOf<SetupStep?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    var accessibilitySkipped by rememberSaveable { mutableStateOf(false) }

    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var usbAsked by rememberSaveable { mutableStateOf(false) }
    var stalledMs by remember { mutableLongStateOf(0L) }

    val inputs = status.setupInputs(discovery, repairing, accessibilitySkipped, confirmed, revisit, stalledMs, usbAsked)
    val view = PhoneSetupFlow.view(inputs)
    // Timing starts when stuck at the same spot with no progress and restarts when the spot changes; once the limit is hit there is no need to keep counting
    val stallPoint = PhoneSetupFlow.stallPoint(view)
    LaunchedEffect(stallPoint) {
        stalledMs = 0L
        if (stallPoint == null) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (stalledMs < PhoneSetupFlow.STALL_MS) {
            delay(1_000L)
            stalledMs = SystemClock.elapsedRealtime() - start
        }
    }
    // Once "Start from a computer instead" has appeared it stays, and is not withdrawn just because connecting has started again
    LaunchedEffect(view.usbFallback) { if (view.usbFallback) usbAsked = true }
    // The step being redone: when it is read as flipping from off to on, go back to the original step automatically, no extra tap needed
    val revisitDetected = revisit?.let { PhoneSetupFlow.detected(it, inputs) }
    var revisitSawOff by rememberSaveable(revisit) { mutableStateOf(false) }
    LaunchedEffect(revisit, revisitDetected) {
        if (revisitDetected == false) revisitSawOff = true
        else if (revisitDetected == true && revisitSawOff) revisit = null
    }
    // Connecting has started or already succeeded: trust the helper's state and stop staying on the revisited step
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Working || view.phase == SetupPhase.Success) revisit = null }
    // Reached the pairing step: start looking for the pairing window; if notifications were not allowed, ask right here (the pairing code has to go into a notification).
    // If the "Pairing" notification channel is turned off it must be enabled in system settings, so do not jump there automatically; offer a button on the page instead
    LaunchedEffect(view.current) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    // Leaving the onboarding page while parked on the pairing step usually means switching to system settings to open the pairing window (not necessarily by tapping "Go to Wireless debugging"): from then on watch for another 10 minutes,
    // so that code reading and the enter-code notification do not stop while the onboarding page sits in the background
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (view.current == SetupStep.PairingCode) pairing.begin()
    }
    // Accessibility gets cut off by the system after reaching the pairing step: do not fall back to the accessibility step,
    // stay on the pairing step like the skipped case and switch to saying to enter the code in the notification; if it reconnects, read it ourselves as before
    LaunchedEffect(view.current) {
        if (view.current == SetupStep.PairingCode) accessibilitySkipped = true
    }
    val notificationsOff = view.notifyIssue == PairingNotifyIssue.NotificationsOff
    LaunchedEffect(notificationsOff) {
        if (notificationsOff && !askedNotifications) {
            askedNotifications = true
            onFix(Fix.AllowNotifications)
        }
    }
    LaunchedEffect(view.phase) { if (view.phase == SetupPhase.Success) phone.setupPrompt.clear() }
    // Prepare the "start from a computer" script as the onboarding page opens (carrying a fresh token while the helper is away)
    LaunchedEffect(Unit) { phone.shellManager.usbScript.refresh() }
    val usbCommand = remember { phone.shellManager.usbScript.command() }

    val copyUsbCommand = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(PhoneSetupCopy.USB_TITLE, usbCommand))
        Toast.makeText(context, PhoneSetupCopy.USB_COPIED, Toast.LENGTH_SHORT).show()
    }
    // Closing without finishing means not setting it up: withdraw the pairing notification and stop looking for the pairing window (the enter-code notification is put in the shade as soon as the pairing step is reached, so it would linger if not withdrawn; end also finishes re-pairing).
    // While pairing or connecting, let it finish; once paired, keep "Paired"
    val close = {
        if (view.phase == SetupPhase.Guide || view.phase == SetupPhase.Failed) pairing.end() else pairing.cancelRepair()
        onClose()
    }
    BackHandler(onBack = close)
    PhoneSetupContent(
        view = view,
        onStepAction = { step ->
            when (step) {
                SetupStep.DeveloperOptions -> onFix(Fix.OpenAboutPhone)
                SetupStep.UsbDebugging -> onFix(Fix.OpenUsbDebugging)
                SetupStep.Accessibility -> onFix(Fix.OpenAccessibility)
                SetupStep.WirelessDebugging -> onFix(Fix.OpenDeveloperOptions)
                SetupStep.PairingCode -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                SetupStep.Connect -> Unit
            }
        },
        onFailureFix = { fix ->
            when (fix) {
                BuiltinShellFix.Retry -> onFix(Fix.RetryPhoneHelper)
                BuiltinShellFix.RePair -> { pairing.begin(); onFix(Fix.OpenWirelessDebugging) }
                BuiltinShellFix.OpenWifi -> onFix(Fix.OpenWifi)
                BuiltinShellFix.OpenWirelessDebugging -> onFix(Fix.OpenWirelessDebugging)
                BuiltinShellFix.RunUsbCommand -> copyUsbCommand()
            }
        },
        onAllowNotifications = { onFix(Fix.AllowNotifications) },
        onOpenPairingChannel = { onFix(Fix.OpenPairingChannel) },
        headsUpLowered = status.notificationsAllowed && (status.attentionLowered || status.resultLowered),
        onOpenHeadsUp = { onFix(if (status.attentionLowered) Fix.OpenAttentionChannel else Fix.OpenResultChannel) },
        onConfirmStep = { step -> confirmed = confirmed + step; revisit = null },
        onSubmit = { code, port -> pairing.submit(code, port) },
        onClose = close,
        onAppInfo = { onFix(Fix.OpenAppInfo) },
        onSkipAccessibility = { accessibilitySkipped = true; revisit = null },
        onRevisit = { step -> revisit = step },
        onboarding = onboarding,
        onDiagnostics = { diagnosticsOpen = true },
        scanning = scanning,
        onConnectPort = { port ->
            phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellManager.connectWithPort(port)
            }
        },
        usbCommand = usbCommand,
        onAskUsb = { usbAsked = true },
        onCopyUsbCommand = copyUsbCommand,
    )
    // Diagnostics can be sent to the author from whichever step you are stuck on: it uses the onboarding page's inputs at this moment, and the steps the user confirmed are included
    if (diagnosticsOpen) {
        DiagnosticsSheet(load = { Diagnostics.render(DiagnosticsCollector.collect(context, status, inputs)) }, onDismiss = { diagnosticsOpen = false })
    }
}

/**
 * Stateless onboarding page, easy to test. The steps are a numbered checklist: completed ones are ticked, the current one is highlighted and carries a button, later ones are dimmed.
 * The steps that read a switch all have [onConfirmStep] ("I've turned it on, next"); completed steps can be tapped and [onRevisit] goes back to redo them, passing null returns to the original step.
 * When [onboarding] is true this is the last step of first-time setup and a row of page dots is added at the bottom. The code passed to [onSubmit] has already been validated as 6 digits.
 * When the pairing step cannot post notifications ([SetupView.needsNotifications]), fixing notifications is the primary button: [onAllowNotifications] or [onOpenPairingChannel].
 * When [onDiagnostics] is not null there is always "Copy diagnostics" at the bottom, and the failing step has one too when something fails.
 * Except on success, every phase has "Not now" at the bottom, and back always gets you out: the user is never trapped on this page.
 * When [scanning] is true the connect step says a port is being looked for on this device; when connecting fails, [onConnectPort] receives the wireless debugging port the user enters (already validated 1–65535).
 * "Start from a computer instead" only exists when [usbCommand] is not null: it normally does not appear, only as a "Can't find Wireless debugging?" line on the wireless debugging step ([onAskUsb]);
 * when [SetupView.usbFallback] is true, the current step shows a card with one sentence of explanation, the command and a copy button ([onCopyUsbCommand]).
 * With [SetupPhase.Unavailable] this environment cannot set up phone control: only one explanation card and "Got it", with no steps listed.
 */
@Composable
fun PhoneSetupContent(
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (code: String, port: Int?) -> Unit,
    onClose: () -> Unit,
    headsUpLowered: Boolean = false,
    onOpenHeadsUp: () -> Unit = {},
    onAppInfo: () -> Unit = {},
    onSkipAccessibility: () -> Unit = {},
    onRevisit: (SetupStep?) -> Unit = {},
    onboarding: Boolean = false,
    onOpenPairingChannel: () -> Unit = {},
    onDiagnostics: (() -> Unit)? = null,
    scanning: Boolean = false,
    onConnectPort: ((Int) -> Unit)? = null,
    usbCommand: String? = null,
    onAskUsb: () -> Unit = {},
    onCopyUsbCommand: () -> Unit = {},
) {
    val canRevisit = view.phase == SetupPhase.Guide || view.phase == SetupPhase.Failed
    fun revisitable(step: SetupStep) = canRevisit && step != view.current && step in PhoneSetupFlow.REVISITABLE &&
        view.marks.getValue(step) in setOf(StepMark.Done, StepMark.Skipped, StepMark.Failed)
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(PhoneSetupCopy.BAR_TITLE, MikaIcons.Back, onClose, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (view.phase == SetupPhase.Unavailable) {
                    // This environment has no way to start the helper: only explain, do not list steps that cannot work
                    GroupCard {
                        Column(Modifier.padding(16.dp).testTag("setup-unavailable")) {
                            Text(PhoneSetupCopy.UNAVAILABLE, style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
                            Text(
                                PhoneSetupCopy.UNAVAILABLE_REASON, Modifier.padding(top = 8.dp),
                                style = MikaType.Body, color = c.ink2,
                            )
                        }
                    }
                } else {
                    if (view.phase == SetupPhase.Success) {
                        Text(
                            PhoneSetupCopy.SUCCESS_LINE, Modifier.padding(top = 4.dp, bottom = 16.dp).testTag("setup-success"),
                            style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ok,
                        )
                    } else {
                        Text(
                            PhoneSetupCopy.INTRO, Modifier.padding(top = 4.dp, bottom = 16.dp),
                            style = MikaType.Body, color = c.ink2,
                        )
                    }
                    GroupCard {
                        SetupStep.entries.forEach { step ->
                            StepRow(
                                step, view.marks.getValue(step), current = step == view.current, first = step.ordinal == 0,
                                onClick = if (revisitable(step)) ({ onRevisit(step) }) else null,
                            ) {
                                if (step == view.current) CurrentStepBody(
                                    step, view, onStepAction, onFailureFix, onAllowNotifications, onOpenPairingChannel, headsUpLowered, onOpenHeadsUp,
                                    onConfirmStep, onSubmit, onAppInfo, onSkipAccessibility, onRevisit, onDiagnostics, onConnectPort,
                                )
                                val working = step == SetupStep.Connect && view.phase == SetupPhase.Working
                                if (working) WorkingLine(scanning)
                                if (usbCommand != null && (step == view.current || working)) {
                                    if (view.usbFallback) UsbStartCard(view.restart, usbCommand, onCopyUsbCommand)
                                    else if (step == SetupStep.WirelessDebugging) LinkText(PhoneSetupCopy.USB_ASK, "setup-usb-ask", onAskUsb)
                                }
                            }
                        }
                    }
                    if (SetupStep.entries.any(::revisitable)) FooterNote(PhoneSetupCopy.REVISIT_NOTE)
                    if (view.phase != SetupPhase.Success) FooterNote(PhoneSetupCopy.SKIP_NOTE)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                if (view.phase == SetupPhase.Success) {
                    Text(
                        PhoneSetupCopy.successNote(view.restart), Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center,
                    )
                }
                if (onboarding) ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Phone), Modifier.padding(bottom = 8.dp))
                when (view.phase) {
                    SetupPhase.Success -> MikaButton(
                        if (onboarding) PhoneSetupCopy.START_USING else PhoneSetupCopy.SUCCESS_DONE, onClose,
                        Modifier.fillMaxWidth().testTag("setup-done"),
                    )
                    SetupPhase.Unavailable -> MikaButton(stringResource(R.string.ok_got_it), onClose, Modifier.fillMaxWidth().testTag("setup-got-it"))
                    // You can leave while connecting or when connecting fails, and pick it up later in Settings
                    SetupPhase.Guide, SetupPhase.Working, SetupPhase.Failed -> MikaButton(
                        PhoneSetupCopy.LATER, onClose, Modifier.fillMaxWidth().testTag("setup-later"),
                        kind = ButtonKind.Text, size = ButtonSize.Medium,
                    )
                }
                onDiagnostics?.let {
                    MikaButton(
                        PhoneSetupCopy.DIAGNOSTICS, it, Modifier.fillMaxWidth().testTag("setup-diagnostics"),
                        kind = ButtonKind.Text, size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

/**
 * The other path: start the helper from a computer over a USB cable. Placed inside the current step with the same pale background as this step's other hints: title, one sentence of explanation, the command and a copy button.
 * When the helper was started this way all along ([HelperRestart.UsbCommand]), the title says "run it again".
 */
@Composable
private fun UsbStartCard(restart: HelperRestart, command: String, onCopy: () -> Unit) {
    val c = MikaTheme.colors
    val (title, body) = PhoneSetupCopy.usbCard(restart)
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, MikaShapes.Chip).padding(12.dp).testTag("setup-usb")) {
        Text(title, style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        Text(body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink2)
        Text(
            command, Modifier.padding(top = 8.dp).fillMaxWidth().background(c.fill, MikaShapes.Tile).padding(10.dp).testTag("setup-usb-command"),
            fontFamily = FontFamily.Monospace, style = MikaType.Hint, color = c.ink,
        )
        PillButton(PhoneSetupCopy.USB_COPY, primary = false, tag = "setup-usb-copy", onClick = onCopy)
    }
}

@Composable
private fun StepRow(step: SetupStep, mark: StepMark, current: Boolean, first: Boolean, onClick: (() -> Unit)?, body: @Composable () -> Unit) {
    val copy = PhoneSetupCopy.step(step)
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).testTag("setup-step-${step.name}")) {
        if (!first) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(0.5.dp).background(c.hair))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepBadge(step.ordinal + 1, mark)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        copy.title, Modifier.weight(1f), style = MikaType.Body,
                        fontWeight = if (mark == StepMark.Current || mark == StepMark.Failed) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.ink,
                    )
                    if (mark == StepMark.Done) RowValue(copy.done, ValueTone.Ok)
                    if (mark == StepMark.Skipped) RowValue(PhoneSetupCopy.ACCESSIBILITY_SKIPPED)
                }
                if (!current) Text(copy.brief, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = if (mark == StepMark.Todo) c.ink3 else c.ink2)
                body()
            }
        }
    }
}

@Composable
private fun StepBadge(n: Int, mark: StepMark) {
    val c = MikaTheme.colors
    val bg = when (mark) {
        StepMark.Done -> c.ok
        StepMark.Current -> c.accent
        StepMark.Failed -> c.bad
        StepMark.Todo, StepMark.Skipped -> c.greySoft
    }
    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Done -> Icon(MikaIcons.Check, null, Modifier.size(15.dp), tint = c.onAccent)
            StepMark.Failed -> Icon(MikaIcons.Exclaim, null, Modifier.size(15.dp), tint = c.onAccent)
            else -> Text("$n", style = MikaType.Caption.copy(fontWeight = FontWeight.Bold), color = if (mark == StepMark.Todo || mark == StepMark.Skipped) c.ink3 else c.onAccent)
        }
    }
}

@Composable
private fun CurrentStepBody(
    step: SetupStep,
    view: SetupView,
    onStepAction: (SetupStep) -> Unit,
    onFailureFix: (BuiltinShellFix) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenPairingChannel: () -> Unit,
    headsUpLowered: Boolean,
    onOpenHeadsUp: () -> Unit,
    onConfirmStep: (SetupStep) -> Unit,
    onSubmit: (String, Int?) -> Unit,
    onAppInfo: () -> Unit,
    onSkipAccessibility: () -> Unit,
    onRevisit: (SetupStep?) -> Unit,
    onDiagnostics: (() -> Unit)?,
    onConnectPort: ((Int) -> Unit)?,
) {
    val copy = PhoneSetupCopy.step(step)
    Text(copy.body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
    view.failure?.let { f ->
        val fc = PhoneSetupCopy.failure(f.reason)
        Column(
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.badSoft, MikaShapes.Chip).padding(12.dp),
        ) {
            Text(fc.title, style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.bad)
            Text(fc.body, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        }
        // When the USB command has to be run again, the card below already has the command and a copy button, so do not repeat one here
        if (f.fix != BuiltinShellFix.RunUsbCommand || !view.usbFallback) {
            PillButton(PhoneSetupCopy.fixLabel(f.fix), primary = true, tag = "setup-fix") { onFailureFix(f.fix) }
        }
        // No port found automatically: have the user enter it as shown on the "Wireless debugging" screen
        if (step == SetupStep.Connect && f.reason == BuiltinShellFailure.ConnectFailed) onConnectPort?.let { ConnectPort(it) }
        if (view.phase == SetupPhase.Failed) onDiagnostics?.let { LinkText(PhoneSetupCopy.DIAGNOSTICS, "setup-diagnostics-failed", it) }
    }
    // Which earlier step is the most likely cause when it fails: say it plainly and offer a button to go back
    view.suspect?.let { s ->
        Text(
            PhoneSetupCopy.suspect(s),
            Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, MikaShapes.Chip).padding(12.dp)
                .testTag("setup-suspect"),
            style = MikaType.Caption, color = MikaTheme.colors.ink,
        )
        PillButton(PhoneSetupCopy.goBackTo(s.step), primary = view.failure == null, tag = "setup-suspect-back") { onRevisit(s.step) }
    }
    // Without accessibility the pairing code can only be entered in the notification: if the notification cannot be posted, fix that first, and make it this step's primary button
    val fixNotificationsFirst = step == SetupStep.PairingCode && view.needsNotifications && !view.autoRead
    if (step == SetupStep.PairingCode) {
        when (view.notifyIssue) {
            PairingNotifyIssue.NotificationsOff -> {
                Text(PhoneSetupCopy.NEED_NOTIFICATIONS, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.ALLOW_NOTIFICATIONS, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onAllowNotifications() }
            }
            PairingNotifyIssue.ChannelOff -> {
                Text(PhoneSetupCopy.PAIRING_CHANNEL_OFF, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.warn)
                PillButton(PhoneSetupCopy.OPEN_PAIRING_CHANNEL, primary = fixNotificationsFirst, tag = "setup-notify-fix") { onOpenPairingChannel() }
            }
            // Pulling down the shade still works for entering the code: only mention it and do not take over the primary button; with accessibility on the notification is not needed, so say nothing
            PairingNotifyIssue.NotHeadsUp -> if (!view.autoRead) {
                Text(
                    PhoneSetupCopy.PAIRING_LOWERED,
                    Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, MikaShapes.Chip).padding(12.dp)
                        .testTag("setup-pairing-lowered"),
                    style = MikaType.Caption, color = MikaTheme.colors.ink,
                )
                PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-pairing-lowered-open") { onOpenPairingChannel() }
            }
            null -> Unit
        }
        if (headsUpLowered && !view.needsNotifications) {
            Column(
                Modifier.padding(top = 10.dp).fillMaxWidth().background(MikaTheme.colors.warnSoft, MikaShapes.Chip).padding(12.dp)
                    .testTag("setup-heads-up"),
            ) {
                Text(PhoneSetupCopy.HEADS_UP_LOWERED, style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.warn)
            }
            PillButton(PhoneSetupCopy.OPEN_HEADS_UP, primary = false, tag = "setup-heads-up-open") { onOpenHeadsUp() }
        }
        if (view.autoRead) AutoReadHint() else NotifyGuide()
        if (view.portFound && !view.autoRead) Text(PhoneSetupCopy.PORT_FOUND, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ok)
    }
    // The accessibility step is best skipped: Claire turns it on herself after pairing and is not blocked by "Restricted settings"
    val a11y = step == SetupStep.Accessibility
    if (a11y) PillButton(PhoneSetupCopy.ACCESSIBILITY_SKIP, primary = true, tag = "setup-skip-a11y", onClick = onSkipAccessibility)
    if (view.failure == null && copy.action != null) {
        PillButton(copy.action, primary = !a11y && !fixNotificationsFirst, tag = "setup-action") { onStepAction(step) }
    }
    if (a11y && view.showAppInfo) PillButton(PhoneSetupCopy.APP_INFO, primary = false, tag = "setup-app-info", onClick = onAppInfo)
    // The switch reading is only a reference: once the user says it is on, move on, so no step can get stuck
    if (step in PhoneSetupFlow.CONFIRMABLE) PillButton(PhoneSetupCopy.TURNED_ON_NEXT, primary = false, tag = "setup-confirm") { onConfirmStep(step) }
    if (step == SetupStep.PairingCode) ManualCode(askPort = !view.portFound, onSubmit = onSubmit)
    if (view.revisiting) LinkText(PhoneSetupCopy.REVISIT_BACK, "setup-revisit-back") { onRevisit(null) }
}

@Composable
private fun LinkText(text: String, tag: String?, onClick: () -> Unit) {
    Text(
        text, Modifier.padding(top = 6.dp).clickable(onClick = onClick).padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        style = MikaType.Caption, color = MikaTheme.colors.accent,
    )
}

/** Accessibility is on: say one line before going to system settings, since Claire will read the pairing code herself. */
@Composable
private fun AutoReadHint() {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.okSoft, MikaShapes.Chip)
            .padding(12.dp).testTag("setup-pairing-hint"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Check, null, Modifier.size(20.dp), tint = c.ok)
        Column {
            Text(PhoneSetupCopy.AUTO_READ_HINT, style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            // Some systems stop Claire's accessibility and freeze the background as soon as you enter Settings: if the code cannot be read, the enter-code notification is already in the shade
            Text(PhoneSetupCopy.AUTO_READ_FALLBACK, Modifier.padding(top = 4.dp), style = MikaType.Caption, color = c.ink2)
        }
    }
}

/** Accessibility is off: three numbered mini-steps explain how to enter the pairing code into the notification without leaving the pairing window. */
@Composable
private fun NotifyGuide() {
    val c = MikaTheme.colors
    val icons = listOf(MikaIcons.Phone, MikaIcons.Bell, MikaIcons.Key)
    Column(
        Modifier.padding(top = 10.dp).fillMaxWidth().background(c.accentSoft, MikaShapes.Chip)
            .padding(12.dp).testTag("setup-pairing-guide"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhoneSetupCopy.NOTIFY_GUIDE.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = MikaType.Hint.copy(fontWeight = FontWeight.Bold), color = c.onAccent)
                }
                Icon(icons[i], null, Modifier.size(18.dp), tint = c.accent)
                Text(line, Modifier.weight(1f), style = MikaType.Body.copy(fontWeight = FontWeight.Medium), color = c.ink)
            }
        }
    }
}

/** Fallback when the notification did not appear: enter the code directly in the app (plus the port when the pairing window cannot be found). */
@Composable
private fun ManualCode(askPort: Boolean, onSubmit: (String, Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text(
        PhoneSetupCopy.MANUAL_TOGGLE, Modifier.padding(top = 8.dp).clickable { open = !open }.padding(vertical = 4.dp).testTag("setup-manual"),
        style = MikaType.Caption, color = MikaTheme.colors.accent,
    )
    if (!open) return
    Text(PhoneSetupCopy.MANUAL_HINT, style = MikaType.Hint, color = MikaTheme.colors.ink3)
    MikaTextField(
        code, { code = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-code"),
        label = { Text(PhoneSetupCopy.CODE_LABEL) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
    if (askPort) {
        MikaTextField(
            port, { port = it; error = null }, Modifier.fillMaxWidth().padding(top = 6.dp).keepsKeyboard().testTag("setup-port"),
            label = { Text(PhoneSetupCopy.PORT_LABEL) }, supportingText = { Text(PhoneSetupCopy.PORT_HINT) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    error?.let { Text(it, Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.bad) }
    PillButton(PhoneSetupCopy.SUBMIT, primary = true, tag = "setup-submit") {
        val c = PairingCode.normalize(code)
        val p = if (askPort && port.isNotBlank()) PairingCode.port(port) else null
        error = when {
            c == null -> PhoneSetupCopy.CODE_INVALID
            askPort && port.isNotBlank() && p == null -> PhoneSetupCopy.PORT_INVALID
            else -> null
        }
        if (error == null && c != null) onSubmit(c, p)
    }
}

/** The wireless debugging port the user enters: the whole "IP address and port" may have been pasted (take what follows the last colon), and it may contain spaces or full-width digits; null when outside 1–65535. */
internal object ConnectPortInput {
    fun parse(text: String): Int? {
        val tail = text.substringAfterLast(':').substringAfterLast('：')
        val digits = tail.filterNot { it.isWhitespace() }.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")
        return PairingCode.port(digits)
    }
}

/** Fallback when connecting fails: enter the port from the "Wireless debugging" screen and tap "Connect". */
@Composable
private fun ConnectPort(onConnect: (Int) -> Unit) {
    var port by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    MikaTextField(
        port, { port = it; invalid = false }, Modifier.fillMaxWidth().padding(top = 10.dp).keepsKeyboard().testTag("setup-connect-port"),
        label = { Text(PhoneSetupCopy.CONNECT_PORT_LABEL) },
        supportingText = { Text(if (invalid) PhoneSetupCopy.PORT_INVALID else PhoneSetupCopy.CONNECT_PORT_HINT, color = if (invalid) MikaTheme.colors.bad else MikaTheme.colors.ink3) },
        singleLine = true, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    PillButton(PhoneSetupCopy.CONNECT_PORT_SUBMIT, primary = false, tag = "setup-connect-port-submit") {
        val p = ConnectPortInput.parse(port)
        invalid = p == null
        if (p != null) onConnect(p)
    }
}

@Composable
private fun WorkingLine(scanning: Boolean) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MikaTheme.colors.accent, strokeWidth = 2.dp)
        Text(
            if (scanning) PhoneSetupCopy.SCANNING_PORTS else PhoneSetupCopy.WORKING, Modifier.testTag("setup-working"),
            style = MikaType.Caption, color = MikaTheme.colors.ink2,
        )
    }
}

@Composable
private fun PillButton(text: String, primary: Boolean, tag: String? = null, onClick: () -> Unit) {
    MikaButton(
        text, onClick, Modifier.padding(top = 10.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        kind = if (primary) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small,
    )
}

// ---------- Settings page "Advanced" ----------

// ---------- 对话页 ----------

/** One line below a task card when the task needs the phone but phone control is not ready. */
@Composable
fun PhoneSetupLine(onOpen: () -> Unit) {
    val shape = MikaShapes.Card
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).cardSurface(MikaTheme.colors, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(PhoneSetupCopy.DEGRADED_LINE, Modifier.weight(1f), style = MikaType.Caption, color = MikaTheme.colors.ink2)
        Text(
            PhoneSetupCopy.DEGRADED_ACTION, Modifier.clickable(onClick = onOpen).padding(4.dp).testTag("phone-setup-open"),
            style = MikaType.Body.copy(fontWeight = FontWeight.SemiBold), color = MikaTheme.colors.accent,
        )
    }
}
