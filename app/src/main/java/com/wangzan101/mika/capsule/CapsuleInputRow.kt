package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.chat.ui.FollowUpMode
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.ScreenThumb
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme

/**
 * The bottom input row: normally a single hint line (the overlay does not take the keyboard); tapping it opens a box you can type in and raises the IME; Enter or the button on the right sends.
 * With [stop] there is always a black stop key on the right (same as the conversation screen's input bar); once there is text there is also a send key.
 */
@Composable
internal fun InputRow(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)? = null) {
    ui.screen?.let { ScreenChip(it, actions::dropScreen) }
    // When 'Queue' is selected the placeholder switches to 'Next task' instead of still saying it supplements the current task
    val shown = if (field.kind == CardInputKind.Steer && ui.followUpMode == FollowUpMode.Queue)
        field.copy(hint = tr(R.string.chat_placeholder_queue)) else field
    Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
        // Only 'supplement / change requirements' needs a choice between steering the current task and queueing the next one; answers, continuations, and new tasks do not show it
        if (field.kind == CardInputKind.Steer) CapsuleFollowUpMode(ui.followUpMode, actions::setFollowUpMode)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CameraKey(actions::captureScreen)
            Box(Modifier.weight(1f)) { InputField(shown, ui, actions, stop) }
        }
    }
}

/** The capture button left of the input row: a piece of screen inside a circular key. */
@Composable
private fun CameraKey(onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-camera").size(38.dp).clip(MikaShapes.Pill).background(c.ink.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = ScreenCopy.CAMERA, onClick = onClick)
            .semantics { contentDescription = ScreenCopy.CAMERA },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Camera, null, Modifier.size(18.dp), tint = c.ink2)
    }
}

/** The strip above the input row: thumbnail, 'your current screen', app · time, and × on the right to drop it; pages that block capture say 'not captured'. */
@Composable
private fun ScreenChip(p: PendingScreen, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    val s = p.shot.screen
    Row(
        Modifier.testTag("capsule-attachment").padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.05f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.blocked) Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { Icon(MikaIcons.Lock, null, Modifier.size(16.dp), tint = c.ink2) }
        else ScreenThumb(p.thumb, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(if (s.blocked) ScreenCopy.BLOCKED else ScreenCopy.TAG, style = Strong.copy(fontSize = 12.5.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ScreenCopy.detail(s), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.testTag("capsule-attachment-remove").size(26.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = ScreenCopy.REMOVE, onClick = onRemove)
                .semantics { contentDescription = ScreenCopy.REMOVE },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3)
        }
    }
}

/** Send key: blue when there is something to send, otherwise a grey one that cannot be tapped. */
@Composable
private fun SendKey(enabled: Boolean, onSend: () -> Unit, start: Dp = 0.dp) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-send").padding(start = start).size(KeySize).clip(MikaShapes.Pill)
            .background(if (enabled) c.accent else c.ink3.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = CardCopy.SEND, onClick = onSend)
            .semantics { contentDescription = CardCopy.SEND },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Up, null, Modifier.size(17.dp), tint = Color.White)
    }
}

@Composable
private fun InputField(field: CardInput, ui: CardInputUi, actions: CardActions, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (!ui.open) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
                .padding(end = if (stop != null || ui.screen != null) 4.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.testTag("capsule-input").weight(1f).heightIn(min = 42.dp)
                    .clickable(role = Role.Button, onClickLabel = field.hint, onClick = actions::openInput).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(ui.draft.ifEmpty { field.hint }, style = Body, color = if (ui.draft.isEmpty()) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stop?.let { StopKey(it) }
            // With a screen attached you can send directly without opening the input row
            if (ui.screen != null) SendKey(true, actions::send, start = if (stop != null) 4.dp else 0.dp)
        }
        return
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    // Same as the conversation screen: on open, and whenever the outside text changes (a reply comes in and continues), the cursor goes to the end so typing continues there
    var fieldValue by remember { mutableStateOf(TextFieldValue(ui.draft, TextRange(ui.draft.length))) }
    val shownValue = if (fieldValue.text == ui.draft) fieldValue else TextFieldValue(ui.draft, TextRange(ui.draft.length))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.ink.copy(alpha = 0.05f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (ui.draft.isEmpty()) Text(field.hint, style = Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                shownValue, { v -> fieldValue = v; if (v.text != ui.draft) actions.draft(v.text) },
                Modifier.testTag("capsule-field").fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.hint },
                textStyle = Body.copy(color = c.ink, textDirection = TextDirection.Content), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ui.canSend) actions.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || ui.canSend) SendKey(ui.canSend, actions::send, start = if (stop != null) 4.dp else 0.dp)
    }
}

/** The compact two-choice control inside the capsule card: steer the current task / queue the next one. */
@Composable
private fun CapsuleFollowUpMode(mode: FollowUpMode, onMode: (FollowUpMode) -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("capsule-follow-up-mode"), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        CapsuleModePill(FollowUpMode.Steer, mode, onMode, tr(R.string.follow_up_steer), "capsule-follow-up-steer")
        CapsuleModePill(FollowUpMode.Queue, mode, onMode, tr(R.string.follow_up_queue), "capsule-follow-up-queue")
    }
}

@Composable
private fun CapsuleModePill(value: FollowUpMode, selected: FollowUpMode, onMode: (FollowUpMode) -> Unit, label: String, tag: String) {
    val c = MikaTheme.colors
    val on = value == selected
    Box(
        Modifier.testTag(tag).heightIn(min = 26.dp).clip(MikaShapes.Pill)
            .background(if (on) c.accent else c.fill)
            .clickable(role = Role.Tab, onClickLabel = label) { onMode(value) }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Sub, color = if (on) c.onAccent else c.ink2, maxLines = 1)
    }
}

/** A 'N queued' line at the bottom of the capsule card; tapping it opens the conversation screen to manage them. */
@Composable
internal fun QueuedLine(count: Int, onClick: (() -> Unit)?) {
    if (count <= 0) return
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(MikaShapes.Pill).background(c.fill)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp).testTag("capsule-queued"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MikaIcons.Clock, null, Modifier.size(14.dp), tint = c.ink3)
        Text(
            tr(R.string.follow_up_count, count) + " · " + tr(R.string.follow_up_queue_hint),
            Modifier.weight(1f), style = Sub, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The stop key right of the input row: a square inside a black circular key, same as the conversation screen's input bar. */
@Composable
internal fun StopKey(onStop: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.testTag("capsule-stop").size(KeySize).clip(MikaShapes.Pill).background(c.ink)
            .clickable(role = Role.Button, onClickLabel = CardCopy.STOP, onClick = onStop)
            .semantics { contentDescription = CardCopy.STOP },
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.Stop, null, Modifier.size(20.dp), tint = c.bg)
    }
}

internal val KeySize = 34.dp

internal const val TITLE_MAX = 12
