package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.AttachmentFailureReason
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.pageBackground
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.GlassBackdrop
import com.wangzan101.mika.ui.components.GlassBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.voice.ui.VoiceBarCover
import com.wangzan101.mika.voice.ui.VoiceConverting
import com.wangzan101.mika.voice.ui.VoiceFloat
import com.wangzan101.mika.voice.ui.VoicePlaceholder
import com.wangzan101.mika.voice.ui.VoiceSheetHost
import com.wangzan101.mika.voice.ui.holdToTalk
import com.wangzan101.mika.voice.ui.rememberVoiceHold
import com.wangzan101.mika.voice.ui.voiceState

/**
 * Glass capsule input bar: a "+" on the left, send on the right (grey when empty); while a task is running a stop button is added, and follow-up messages queue above the input bar.
 * Chat goes through the front desk: whatever the front desk is working on shows as one row above the input bar ([DeskTray]), and the input bar does not get busier because of background tasks.
 * "+" opens the feature panel ([onPlus]; on the conversation page it is a list rising from the bottom). Pending attachments sit in a row above the input bar ([PendingTray]).
 */
@Composable
internal fun Composer(
    modifier: Modifier,
    text: String,
    onText: (String) -> Unit,
    mode: ComposerMode,
    hasHistory: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** The front desk is replying or work it dispatched is still running: like [ComposerMode.Busy], the right side is Stop when there is no text. */
    working: Boolean = false,
    /** The next item explicitly queued for this conversation that has not started yet. */
    queued: List<com.wangzan101.mika.task.QueuedFollowUp> = emptyList(),
    onQueueActions: (com.wangzan101.mika.task.QueuedFollowUp) -> Unit = {},
    onCancelQueued: (String) -> Unit = {},
    onEditQueued: (String) -> Unit = {},
    onStartQueued: () -> Unit = {},
    /** "+" exists once the sandbox is ready; tapping it opens the feature panel. */
    onPlus: (() -> Unit)? = null,
    /** Attachments are enabled but cannot be added right now: "+" is still there, tapping it only shows a hint. */
    attachBlocked: Boolean = false,
    attachments: List<PendingAttachment> = emptyList(),
    importing: Boolean = false,
    rejected: List<AttachmentFailure> = emptyList(),
    overLimit: Int = 0,
    onRemove: (String) -> Unit = {},
    onDismissRejected: () -> Unit = {},
    /** 输入框上方的本对话主模型入口。 */
    mainModel: MainModelUi = MainModelUi(),
    onMainModel: () -> Unit = {},
    /** 要随下一句话发出去的引用;有时输入框上方一张小条,× 去掉。 */
    quote: QuoteMessage.Quote? = null,
    onRemoveQuote: () -> Unit = {},
    editRequest: Int = 0,
    /** A snapshot of the content behind the composer; when null the composer is not blurred and uses an opaque background. */
    backdrop: GlassBackdrop? = null,
    reduceMotion: Boolean = false,
    /** Hold-to-talk opens its session from here; when null the field can only be typed in. */
    voice: VoiceSource? = null,
    /** Text produced by speaking; the caller appends it to the existing text and does not send automatically. */
    onVoiceText: (String) -> Unit = {},
    /** Text finished in the "listening" card ("release to send"): the caller appends it to the existing text and sends immediately. When absent there is no "release to send". */
    onVoiceSend: ((String) -> Unit)? = null,
    /** The "choose" action in "voice input not available yet": pick speech-to-text in settings. */
    onPickSpeech: () -> Unit = {},
    /** "Download an offline voice pack" in the "voice input not available yet" notice: opens the offline voice packs page. */
    onOpenVoicePacks: () -> Unit = {},
    /** Handset button: starts a voice call; hidden when null. */
    /** Who receives what is said while a task runs: the card header shows "Interject to '…'". */
    steerTarget: String? = null,
    /** True while holding to talk: the conversation content blurs along with it. */
    onVoiceHolding: (Boolean) -> Unit = {},
    sendFromIme: Boolean = false,
    draftKey: String? = null,
    /** Tasks this conversation's front desk has dispatched: if any are running, a progress row appears above the input bar. */
    deskTasks: List<com.wangzan101.mika.core.frontdesk.DeskTaskView> = emptyList(),
    /** The "Stop" button for one item in the progress row. */
    onStopTask: (String) -> Unit = {},
    /** The "View details" button for one item in the progress row. */
    onTaskDetail: (String) -> Unit = {},
) {
    val c = MikaTheme.colors
    var fieldFocused by remember { mutableStateOf(false) }
    // The bottom fades from the page background to transparent, and the composer is glass floating above it
    val fade = remember(c) { Brush.verticalGradient(0f to c.bg.copy(alpha = 0f), 0.25f to c.bg, 1f to c.bg) }
    val busy = mode == ComposerMode.Busy
    val context = LocalContext.current
    // Keep "+" while attachments are enabled; when they cannot be added right now it only shows a hint and does not open the panel
    val showAttach = onPlus != null || attachBlocked
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    // "Release to send" sends directly, just like the send button
    val vh = androidx.compose.runtime.key(draftKey) { rememberVoiceHold(voice, onVoiceText, onVoiceSend?.let { send -> { text -> send(text) } }) }
    val vState = voiceState(vh)
    val voiceHolding = vh?.holding == true
    LaunchedEffect(voiceHolding) { onVoiceHolding(voiceHolding) }
    LaunchedEffect(editRequest) {
        if (editRequest > 0) {
            field.requestFocus()
            keyboard?.show()
        }
    }
    // While the field is focused, long-press stays with the system's text selection and paste (also for floating, split and hardware keyboards); when unfocused a hold-to-talk gesture layer covers the field
    val voiceGesture = vh != null && (!fieldFocused || voiceHolding)
    // Keyboard going from shown to hidden (back key, the keyboard's own hide key): the field loses focus too, so hold-to-talk works again. Only keyboard changes are observed; a hardware keyboard (which never shows) is unaffected
    LaunchedEffect(ime, density, vh != null) {
        if (vh == null) return@LaunchedEffect
        var wasUp = false
        snapshotFlow { ime.getBottom(density) > 0 }.collect { up ->
            if (wasUp && !up && fieldFocused) focus.clearFocus()
            wasUp = up
        }
    }
    // When the text from outside changes (speech filled in, an example tapped), put the cursor at the end so continuing by voice or typing appends
    var fieldValue by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val shownValue = if (fieldValue.text == text) fieldValue else TextFieldValue(text, TextRange(text.length))
    Box(
        modifier.fillMaxWidth()
            .pageBackground(c.bg, fade)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = ComposerInset, end = ComposerInset, top = 10.dp, bottom = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            DeskTray(deskTasks, reduceMotion, backdrop, onStopTask, onTaskDetail)
            FollowUpTray(
                queued, active = mode != ComposerMode.Idle,
                onEdit = onEditQueued, onCancel = onCancelQueued, onStartNow = onStartQueued, onActions = onQueueActions,
            )
            quote?.let { QuoteChip(it, onRemoveQuote, Modifier.padding(bottom = 6.dp)) }
            MainModelChip(mainModel, busy = busy || working, onClick = onMainModel, modifier = Modifier.padding(bottom = 6.dp))
            if (attachments.isNotEmpty()) PendingTray(attachments, onRemove, Modifier.padding(bottom = 2.dp))
            if (importing || rejected.isNotEmpty() || overLimit > 0) {
                Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (importing) Text(AttachCopy.IMPORTING, style = MikaType.Caption, color = c.ink3)
                    if (rejected.isNotEmpty() || overLimit > 0) {
                        // The over-limit hint states the current limit instead of hardcoding 200 MiB
                        val ctx = LocalContext.current
                        val attachmentLimitLabel = remember(ctx) {
                            trPlural(R.plurals.settings_attachment_file_limit_mb_n, com.wangzan101.mika.settings.FeatureFlags.get(ctx).attachmentMaxMb())
                        }
                        val why = listOfNotNull(
                            rejected.takeIf { it.isNotEmpty() }?.map { failure ->
                                when (failure.reason) {
                                    AttachmentFailureReason.TOO_LARGE -> stringResource(R.string.chat_attach_too_large, failure.name, attachmentLimitLabel)
                                    AttachmentFailureReason.LOW_DEVICE_SPACE -> stringResource(R.string.chat_attach_low_device_space, failure.name)
                                    AttachmentFailureReason.WORKSPACE_LIMIT -> stringResource(R.string.chat_attach_workspace_limit, failure.name)
                                    AttachmentFailureReason.IO_ERROR -> stringResource(R.string.chat_attach_io_error, failure.name)
                                }
                            }?.joinToString("\n"),
                            overLimit.takeIf { it > 0 }?.let(AttachCopy::overLimit),
                        ).joinToString("\n")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(why, Modifier.weight(1f), style = MikaType.Caption, color = c.warn)
                            TextLink(stringResource(R.string.ok_got_it), onDismissRejected)
                        }
                    }
                }
            }
            if (vh != null) {
                val title = if (busy && !steerTarget.isNullOrBlank()) stringResource(R.string.voice_steer_to, steerTarget)
                else stringResource(R.string.voice_listening)
                VoiceFloat(vh, vState, title, backdrop, reduceMotion)
            }
            Box(Modifier.fillMaxWidth()) {
            // Solid card: translucent glass over two rows would be backed by different colors and show two tones, so the composer does not use a Gaussian blur
            GlassBar(Modifier.fillMaxWidth().keepsKeyboard()) {
                val canSend = AttachTray.canSend(text, attachments, importing)
                // Two rows: the first is pure input, the second holds the buttons; the button layout does not move no matter how much text there is
                Column(Modifier.weight(1f)) {
                    // start 14dp: the text's left edge aligns with the visible left edge of the + icon (a 20dp icon centered in a 40dp button, with the glyph itself inset about 4dp more)
                    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 4.dp)) {
                        val converting = vState is HoldToTalk.State.Converting
                        if (text.isEmpty() && !converting) {
                            if (vh != null) VoicePlaceholder(mode)
                            else Text(ChatCopy.placeholder(mode, hasHistory), style = MikaType.Body, color = c.ink3, maxLines = 1)
                        }
                        BasicTextField(
                            shownValue,
                            { v -> fieldValue = v; if (v.text != text) onText(v.text) },
                            Modifier.fillMaxWidth().testTag("composer-field").focusRequester(field)
                                .alpha(if (converting) 0f else 1f)
                                .onFocusChanged { fieldFocused = it.isFocused },
                            textStyle = MikaType.Body.copy(color = c.ink, textDirection = TextDirection.Content),
                            cursorBrush = SolidColor(c.accent),
                            maxLines = 5,
                            keyboardOptions = if (sendFromIme) KeyboardOptions(imeAction = ImeAction.Send) else KeyboardOptions.Default,
                            keyboardActions = if (sendFromIme) KeyboardActions(onSend = {
                                if (AttachTray.canSend(text, attachments, importing)) onSend()
                            }) else KeyboardActions.Default,
                        )
                        // The provider is transcribing after release: a spinner inside the field, and the transcribed text is filled in
                        if (converting) VoiceConverting(Modifier.align(Alignment.CenterStart))
                        vh?.takeIf { voiceGesture }?.let { hold ->
                            Box(Modifier.matchParentSize().holdToTalk(hold) { field.requestFocus(); keyboard?.show() })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (showAttach) {
                            val label = AttachCopy.ADD
                            val blockedHint = stringResource(R.string.attach_blocked_running)
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(enabled = !importing, onClickLabel = label, onClick = {
                                        if (attachBlocked) {
                                            Toast.makeText(context, blockedHint, Toast.LENGTH_SHORT).show()
                                        } else onPlus?.invoke()
                                    }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Plus, label, Modifier.size(20.dp), tint = c.ink)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // The microphone is always present: listening only while held, and a tap explains holding (or opens the explanation page when the engine is unavailable); after the keyboard appears, long-pressing the input is left to the system for text selection
                        if (vh != null) {
                            val hint = stringResource(R.string.voice_hold_to_talk)
                            Box(
                                Modifier.size(MikaSize.IconButton).testTag("composer-mic").holdToTalk(vh) { if (vh.engineUsable) vh.toast = hint else vh.explainUnavailable() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Mic, hint, Modifier.size(MikaSize.Icon), tint = c.ink)
                            }
                        }
                        // While working (a task is running, Claire is replying, or dispatched work is running), show Stop when there is no text; once text is entered, Stop gives way to Send, and the message goes to Claire
                        if ((busy || working) && !canSend) {
                            Box(
                                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                                    .clickable(onClickLabel = stringResource(R.string.chat_stop), onClick = onStop),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(26.dp), tint = c.ink)
                            }
                        }
                        if (!(busy || working) || canSend) {
                            // When sendable, a white arrow on black (primary action); empty input gets a grey arrow on grey—sending is the main action, and the empty state also has a circular base and +/microphone for hierarchy, but the grey arrow shows it is not available yet. All use tokens, so dark theme inverts automatically
                            Box(
                                Modifier.size(MikaSize.IconButton)
                                    .clip(CircleShape)
                                    .background(if (canSend) c.ink else c.neutralSoft)
                                    .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) { onSend() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(20.dp), tint = if (canSend) c.bg else c.ink3)
                            }
                        }
                    }
                }
            }
            if (vh != null) VoiceBarCover(vh, vState, reduceMotion, Modifier.matchParentSize())
            }
            if (vh != null) VoiceSheetHost(vh, onPickSpeech, onOpenVoicePacks)
        }
    }
}
