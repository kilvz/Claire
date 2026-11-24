package com.wangzan101.mika.voicecall.ui

import android.text.format.DateFormat
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaSpace
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voicecall.RouteDevice
import com.wangzan101.mika.voicecall.VoiceCallFeedItem
import com.wangzan101.mika.voicecall.VoiceCallState
import com.wangzan101.mika.voicecall.VoiceCallTurn
import com.wangzan101.mika.voicecall.voiceCallStatusText
import kotlin.math.abs
import kotlin.math.sin

/**
 * The voice call page: a call is just a conversation. A full-screen Dialog (neither the back key nor tapping outside dismisses it), following the app's light/dark theme and accent color.
 *
 * Top bar: collapse ⌄ + a small dotted ball (shape follows the state) + "In a call with Claire" and a status phrase (listening to you / thinking / Claire speaking /
 * working on it / muted; terminal states show the end or error text). In the middle is the conversation feed: what you said and what Claire said become bubbles in real time (half-spoken ones are translucent),
 * tasks are step cards, errors are cards with "retry", and while nobody has spoken yet a big ball sits in the center. The bottom console: level bars + mute / type /
 * hang up (with a timer) / speakerphone; after the call ends the console is replaced by "back to chat".
 *
 * The page only reads the various state flows inside [VoiceCallHolder] and does not depend on the foreground service being present (see [VoiceCallController]).
 */
@Composable
fun VoiceCallOverlay(call: VoiceCallHolder, onCollapse: () -> Unit) {
    CallDialog {
        val session = call.session
        val state by session.state.collectAsState()
        val turn by session.turn.collectAsState()
        val muted by session.muted.collectAsState()
        val micLevel by session.micLevel.collectAsState()
        val speakerLevel by session.speakerLevel.collectAsState()
        val busy by call.route.busy.collectAsState()
        val items by call.feed.items.collectAsState()
        val device by call.audioRoute.route.collectAsState()
        val clock = rememberCallClock(state)
        val startedAt = rememberStartedAt(state)
        val active = state is VoiceCallState.Active
        val level = when {
            !active -> 0f
            turn == VoiceCallTurn.Speaking -> speakerLevel
            muted -> 0f
            else -> micLevel
        }
        val shape = when {
            state is VoiceCallState.Connecting || state is VoiceCallState.MicCheck -> VoiceCallShape.Web
            busy -> VoiceCallShape.Orbits
            level > VOICE_WAVE_LEVEL -> VoiceCallShape.Wave
            else -> VoiceCallShape.Globe
        }
        val c = MikaTheme.colors
        val ballColors = ballColors(c, failed = state is VoiceCallState.Failed)
        CallPage(
            onCollapse = onCollapse,
            header = {
                VoiceCallBall(shape, level, Modifier.size(34.dp), dotCount = 110, dotRadius = 0.055f, near = ballColors.first, far = ballColors.second)
            },
            status = statusLine(state, turn, muted, busy),
            statusColor = when (state) {
                is VoiceCallState.Active -> c.ok
                is VoiceCallState.Failed -> c.bad
                else -> c.ink3
            },
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CallFeed(
                    items = items,
                    startedAt = startedAt,
                    failedText = (state as? VoiceCallState.Failed)?.let { voiceCallStatusText(it) },
                    endedText = (state as? VoiceCallState.Ended)?.let { stringResource(R.string.voicecall_ended_after, voiceCallStatusText(it), clock) },
                    onRetry = session::retry,
                )
                // Nobody has spoken yet: put a big ball in the center so the page is not empty
                if (items.isEmpty() && state !is VoiceCallState.Failed && state !is VoiceCallState.Ended) {
                    VoiceCallBall(
                        shape, level, Modifier.align(Alignment.Center).size(200.dp).testTag("voicecall-ball-${shape.name}"),
                        near = ballColors.first, far = ballColors.second,
                    )
                }
            }
            if (state is VoiceCallState.Ended) {
                PillButton(stringResource(R.string.voicecall_back_to_chat), Modifier.navigationBarsPadding().padding(16.dp).testTag("voicecall-back"), onCollapse)
            } else {
                CallDock(
                    active = active,
                    muted = muted,
                    speakerOn = device == RouteDevice.Speaker,
                    level = level,
                    mikaSpeaking = turn == VoiceCallTurn.Speaking,
                    clock = clock,
                    onMute = { session.setMuted(!muted) },
                    onSpeaker = { call.audioRoute.setSpeakerRequested(device != RouteDevice.Speaker) },
                    onSend = session::sendUserText,
                    onHangup = session::hangup,
                )
            }
        }
    }
}

/** The phrase in the top bar: once connected it says whose turn it is, otherwise it is the connecting/ended/error text. */
@Composable
private fun statusLine(state: VoiceCallState, turn: VoiceCallTurn, muted: Boolean, busy: Boolean): String = when {
    state !is VoiceCallState.Active -> voiceCallStatusText(state)
    muted -> stringResource(R.string.voicecall_status_muted)
    turn == VoiceCallTurn.Speaking -> stringResource(R.string.voicecall_status_speaking)
    // While working on a task it is waiting on the task, not thinking: prefer "working on it · you can keep chatting"
    busy -> stringResource(R.string.voicecall_status_working)
    turn == VoiceCallTurn.Thinking -> stringResource(R.string.voicecall_status_thinking)
    else -> stringResource(R.string.voicecall_status_listening)
}

/** Near/far colors of the dotted ball: from the accent family, bright up close and blending into the background far away; on error they switch to the error color. */
private fun ballColors(c: MikaColors, failed: Boolean): Pair<Color, Color> {
    val tone = if (failed) c.bad else c.accent
    return if (c.isDark) lerp(tone, Color.White, 0.45f) to lerp(c.bg, tone, 0.35f)
    else tone to lerp(c.bg, tone, 0.22f)
}

/** Call timer: runs once connected, and stops at the last value on end/failure (that is this call's duration). */
@Composable
private fun rememberCallClock(state: VoiceCallState): String {
    val last = remember { mutableStateOf("0:00") }
    val clock by produceState(last.value, (state as? VoiceCallState.Active)?.startedAt) {
        val startedAt = (state as? VoiceCallState.Active)?.startedAt ?: return@produceState
        while (true) {
            value = ChatCopy.clock(System.currentTimeMillis() - startedAt)
            last.value = value
            kotlinx.coroutines.delay(250)
        }
    }
    return clock
}

/** The connect time is kept after a terminal state too (the "connected · time" line at the top of the feed does not disappear). */
@Composable
private fun rememberStartedAt(state: VoiceCallState): Long? {
    val kept = remember { mutableStateOf<Long?>(null) }
    (state as? VoiceCallState.Active)?.let { kept.value = it.startedAt }
    return kept.value
}

/** Full-screen Dialog that cannot be dismissed by tapping outside; the status bar / navigation bar icons follow the light/dark theme. */
@Composable
private fun CallDialog(content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dark = MikaTheme.colors.isDark
        val bg = MikaTheme.colors.bg
        val view = LocalView.current
        SideEffect {
            val window = view.dialogWindow() ?: return@SideEffect
            // The whole page is opaque, so nothing behind needs dimming: drop the dim flag (just setting the brightness to 0 is not enough — when the system sees a dimmed window it forces the status bar icons to white),
            // and paint the system bars with the page background so no dimmed old screen shows through
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION")
            window.statusBarColor = bg.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = bg.toArgb()
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        content()
    }
}

/** Walk up to the Dialog window hosting this Compose content; there may be several container layers in between, so looking only at the direct parent is not enough. */
private fun View.dialogWindow(): Window? {
    var p: ViewParent? = parent
    while (p != null) {
        if (p is DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}

/** Page skeleton: app background + top bar (collapse, [header] small graphic, title, status sentence) + a hairline, with [content] laid out below. */
@Composable
private fun CallPage(
    onCollapse: () -> Unit,
    header: @Composable () -> Unit,
    status: String?,
    statusColor: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(MikaSize.TopBar).padding(horizontal = MikaSpace.S8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(MikaSize.IconButton).clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.voicecall_collapse), onClick = onCollapse)
                    .testTag("voicecall-collapse"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Chevron, stringResource(R.string.voicecall_collapse), Modifier.size(MikaSize.Icon).graphicsLayer { rotationZ = 90f }, tint = c.ink2)
            }
            Box(Modifier.padding(start = MikaSpace.S4).size(40.dp).background(c.fill, CircleShape), contentAlignment = Alignment.Center) { header() }
            Column(Modifier.weight(1f).padding(start = MikaSpace.S12)) {
                Text(stringResource(R.string.voicecall_title), style = MikaType.BodyStrong, color = c.ink, maxLines = 1)
                if (status != null) {
                    Row(Modifier.testTag("voicecall-status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                        Text(
                            status, style = MikaType.Caption,
                            color = if (statusColor == c.bad) c.bad else c.ink2,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** Conversation feed: connected line + bubbles + step cards; the error card and ended line hang at the end. It scrolls to the bottom automatically as new content arrives. */
@Composable
private fun CallFeed(
    items: List<VoiceCallFeedItem>,
    startedAt: Long?,
    failedText: String?,
    endedText: String?,
    onRetry: () -> Unit,
) {
    val list = rememberLazyListState()
    val lastText = (items.lastOrNull() as? VoiceCallFeedItem.Said)?.text
    LaunchedEffect(items.size, lastText, failedText, endedText) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bubbleMax = maxWidth * 0.8f
        LazyColumn(
            Modifier.fillMaxSize().testTag("voicecall-feed"),
            state = list,
            contentPadding = PaddingValues(horizontal = MikaSpace.Page, vertical = MikaSpace.S12),
            verticalArrangement = Arrangement.spacedBy(MikaSpace.S8),
        ) {
            if (startedAt != null) item("connected") {
                SystemPill(stringResource(R.string.voicecall_connected_at, DateFormat.getTimeFormat(context).format(startedAt)))
            }
            items(items, key = { it.id }) { item ->
                when (item) {
                    is VoiceCallFeedItem.Said -> SaidBubble(item, bubbleMax)
                    is VoiceCallFeedItem.Task -> TaskCard(item)
                }
            }
            if (failedText != null) item("failed") { FailedCard(failedText, onRetry) }
            if (endedText != null) item("ended") { SystemPill(endedText) }
        }
    }
}

@Composable
private fun SystemPill(text: String) {
    val c = MikaTheme.colors
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text, Modifier.background(c.fill, MikaShapes.Chip).padding(horizontal = 10.dp, vertical = 4.dp),
            style = MikaType.Hint, color = c.ink3,
        )
    }
}

/** Bubble for one sentence: yours is right-aligned on a light accent background (same as your bubbles in the chat), Claire's is left-aligned on the card background; half-spoken ones are translucent. */
@Composable
private fun SaidBubble(item: VoiceCallFeedItem.Said, max: androidx.compose.ui.unit.Dp) {
    val c = MikaTheme.colors
    val mine = item.speaker == VoiceCallFeedItem.Speaker.User
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = if (mine) MikaShapes.UserBubble else MikaShapes.AnswerBubble
        val base = Modifier.widthIn(max = max).alpha(if (item.partial) 0.6f else 1f).animateContentSize()
        if (mine) {
            // What you said/typed is shown as-is
            Text(
                if (item.partial) item.text + "…" else item.text,
                base.background(c.accent.copy(alpha = 0.14f), shape).padding(horizontal = 14.dp, vertical = 10.dp).testTag("voicecall-said-user"),
                style = MikaType.Body, color = c.ink,
            )
        } else {
            // Claire's answer may contain Markdown (the result of a typed request is the task's written answer), so render it as in the chat rather than exposing the asterisks
            MarkdownText(
                item.text, MikaType.Body.copy(color = c.ink),
                base.background(c.surface, shape).border(1.dp, c.hair, shape).padding(horizontal = 14.dp, vertical = 10.dp).testTag("voicecall-said-mika"),
                streaming = item.partial,
            )
        }
    }
}

/** Task step card: earlier steps are all checked; the last step spins while running and gets a check when done. */
@Composable
private fun TaskCard(item: VoiceCallFeedItem.Task) {
    val c = MikaTheme.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface, MikaShapes.Card).border(1.dp, c.hair, MikaShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp).animateContentSize().testTag("voicecall-task"),
    ) {
        Text(
            stringResource(if (item.running) R.string.voicecall_task_running else R.string.voicecall_task_done),
            style = MikaType.Label, color = c.ink2,
        )
        item.steps.forEachIndexed { index, step ->
            val running = item.running && index == item.steps.lastIndex
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                    } else {
                        Box(Modifier.size(16.dp).background(c.okSoft, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(MikaIcons.Check, null, Modifier.size(11.dp), tint = c.ok)
                        }
                    }
                }
                // A step line may be the opening of the task answer and contain Markdown; a single small line is not typeset, so strip the markers and join it into one line
                val plain = remember(step) { MarkdownModel.toPlainText(step).replace(LINE_BREAKS, " ") }
                Text(
                    plain, Modifier.padding(start = 10.dp), style = MikaType.Body,
                    color = if (running) c.ink else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FailedCard(text: String, onRetry: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.badSoft, MikaShapes.Card).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Body, color = c.bad)
        Box(
            Modifier.clip(MikaShapes.Button).background(c.bad).clickable(onClick = onRetry)
                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("voicecall-retry"),
        ) {
            Text(stringResource(R.string.voicecall_retry), style = MikaType.Label, color = Color.White)
        }
    }
}

/**
 * Bottom console: level bars (accent color while you speak, secondary ink while Claire speaks, flat when muted), an expandable type line,
 * and a row of buttons: mute, type, hang up (with a timer), speakerphone. Mute and type are unavailable before the call connects.
 */
@Composable
private fun CallDock(
    active: Boolean,
    muted: Boolean,
    speakerOn: Boolean,
    level: Float,
    mikaSpeaking: Boolean,
    clock: String,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onSend: (String) -> Unit,
    onHangup: () -> Unit,
) {
    val c = MikaTheme.colors
    var typing by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
            .padding(horizontal = MikaSpace.S12, vertical = MikaSpace.S8)
            .background(c.surface, MikaShapes.Sheet).border(1.dp, c.hair, MikaShapes.Sheet)
            .padding(MikaSpace.S12),
    ) {
        LevelBars(level, color = if (mikaSpeaking) c.ink2 else if (muted) c.ink3 else c.accent, Modifier.fillMaxWidth().height(30.dp))
        if (typing && active) TypeLine(onSend, Modifier.padding(top = MikaSpace.S8))
        Row(Modifier.fillMaxWidth().padding(top = MikaSpace.S8), verticalAlignment = Alignment.CenterVertically) {
            RoundToggle(
                if (muted) MikaIcons.MicOff else MikaIcons.Mic,
                stringResource(if (muted) R.string.voicecall_unmute else R.string.voicecall_mute),
                on = muted, enabled = active, tag = "voicecall-mute", onClick = onMute,
            )
            Spacer(Modifier.size(MikaSpace.S8))
            RoundToggle(
                MikaIcons.Keyboard, stringResource(R.string.voicecall_keyboard),
                on = typing && active, enabled = active, tag = "voicecall-keyboard",
            ) { typing = !typing }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.height(48.dp).clip(CircleShape).background(c.bad)
                    .clickable(onClickLabel = stringResource(R.string.voicecall_hangup), onClick = onHangup)
                    .padding(horizontal = 20.dp).testTag("voicecall-hangup"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MikaIcons.HangUp, stringResource(R.string.voicecall_hangup), Modifier.size(22.dp), tint = Color.White)
                Text(clock, Modifier.padding(start = 6.dp).testTag("voicecall-clock"), style = MikaType.BodyStrong, color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp + MikaSpace.S8))
            RoundToggle(MikaIcons.Sound, stringResource(R.string.voicecall_speaker), on = speakerOn, enabled = true, tag = "voicecall-speaker", onClick = onSpeaker)
        }
    }
}

/** Circular toggle button: normally a light background with an ink-colored icon; when on it inverts (ink background, background-colored icon). */
@Composable
private fun RoundToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(48.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape)
            .background(if (on) c.ink else c.fill)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(MikaSize.Icon), tint = if (on) c.bg else c.ink)
    }
}

/** Type line: typing takes the same path as speaking, so sending it enters the conversation feed just like saying it. */
@Composable
private fun TypeLine(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    var text by remember { mutableStateOf("") }
    Row(
        modifier.fillMaxWidth().background(c.fill, MikaShapes.Composer).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(stringResource(R.string.voicecall_input_hint), style = MikaType.Body, color = c.ink3, maxLines = 1)
            BasicTextField(
                text, { text = it }, Modifier.fillMaxWidth().testTag("voicecall-input"),
                textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent), maxLines = 3,
            )
        }
        val canSend = text.isNotBlank()
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(if (canSend) c.accent else Color.Transparent)
                .clickable(enabled = canSend, onClickLabel = stringResource(R.string.chat_send)) {
                    onSend(text)
                    text = ""
                }
                .testTag("voicecall-send"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Up, stringResource(R.string.chat_send), Modifier.size(18.dp), tint = if (canSend) c.onAccent else c.ink3)
        }
    }
}

/** Level bars: a row of vertical bars, tall in the middle and short at the edges, whose height follows [level]; with system animations off they only vary with the level and do not animate on their own. */
@Composable
private fun LevelBars(level: Float, color: Color, modifier: Modifier = Modifier) {
    val reduceMotion = MikaTheme.reduceMotion
    var timeNanos by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) withFrameNanos { timeNanos = it }
        }
    }
    val smooth = remember { floatArrayOf(0f) }
    Canvas(modifier) {
        smooth[0] += (level - smooth[0]) * 0.3f
        val t = timeNanos / 1e9f
        val bars = BAR_COUNT
        val barW = 3.dp.toPx()
        val gap = 3.dp.toPx()
        val total = bars * barW + (bars - 1) * gap
        val startX = (size.width - total) / 2f
        val minH = 4.dp.toPx()
        for (i in 0 until bars) {
            val centerFall = 1f - abs(i - (bars - 1) / 2f) / (bars / 1.6f)
            val wobble = if (reduceMotion) 1f else 0.45f + 0.55f * abs(sin(i * 1.7f + t * 9f))
            val h = (minH + smooth[0] * 2.2f * centerFall * wobble * size.height).coerceIn(minH, size.height)
            drawRoundRect(
                color, Offset(startX + i * (barW + gap), (size.height - h) / 2f), Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f),
            )
        }
    }
}

private const val BAR_COUNT = 28
private val LINE_BREAKS = Regex("\\s*\\n+\\s*")

@Composable
private fun PillButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        modifier.clip(MikaShapes.Button).background(c.accent).clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Text(label, style = MikaType.BodyStrong, color = c.onAccent)
    }
}

/**
 * The gate before entering a call: no permission, permission previously denied, no credentials configured, or the configured protocol does not support calls — each gets its own page;
 * all of them offer a button within the page and never pop the system dialog automatically.
 */
enum class VoiceCallGateStage { NeedPermission, PermissionDenied, NeedCredentials, UnsupportedProtocol, NeedListenSpeak }

/**
 * Gate page: the same skeleton as the call page (app background, same top bar), with a quiet ball in the center plus an explanation and a primary button.
 * [stage] decides the text and the primary button: request permission / go to settings / go to configuration.
 */
@Composable
fun VoiceCallGate(
    stage: VoiceCallGateStage,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onGoConfigure: () -> Unit,
    onCollapse: () -> Unit,
) {
    CallDialog {
        val c = MikaTheme.colors
        val ballColors = ballColors(c, failed = false)
        CallPage(
            onCollapse = onCollapse,
            header = { VoiceCallBall(VoiceCallShape.Globe, 0f, Modifier.size(34.dp), dotCount = 110, dotRadius = 0.055f, near = ballColors.first, far = ballColors.second) },
            status = null,
            statusColor = c.ink3,
        ) {
            val title: String
            val body: String
            val action: String
            val go: () -> Unit
            when (stage) {
                VoiceCallGateStage.NeedPermission -> {
                    title = stringResource(R.string.voicecall_mic_needed_title)
                    body = stringResource(R.string.voicecall_mic_needed_body)
                    action = stringResource(R.string.voicecall_mic_allow)
                    go = onRequestPermission
                }
                VoiceCallGateStage.PermissionDenied -> {
                    title = stringResource(R.string.voicecall_mic_needed_title)
                    body = stringResource(R.string.voicecall_mic_denied_body)
                    action = stringResource(R.string.voice_permission_open)
                    go = onOpenSettings
                }
                VoiceCallGateStage.NeedCredentials -> {
                    title = stringResource(R.string.voicecall_setup_title)
                    body = stringResource(R.string.voicecall_setup_body)
                    action = stringResource(R.string.voicecall_go_setup)
                    go = onGoConfigure
                }
                VoiceCallGateStage.NeedListenSpeak -> {
                    title = stringResource(R.string.voicecall_standard_title)
                    body = stringResource(R.string.voicecall_standard_body)
                    action = stringResource(R.string.voicecall_go_setup)
                    go = onGoConfigure
                }
                VoiceCallGateStage.UnsupportedProtocol -> {
                    title = stringResource(R.string.voicecall_protocol_title)
                    body = stringResource(R.string.voicecall_protocol_body)
                    action = stringResource(R.string.voicecall_go_setup)
                    go = onGoConfigure
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                VoiceCallBall(VoiceCallShape.Globe, 0f, Modifier.size(160.dp).alpha(0.7f), near = ballColors.first, far = ballColors.second)
                Text(title, Modifier.padding(top = MikaSpace.S24), style = MikaType.Title, color = c.ink, textAlign = TextAlign.Center)
                Text(body, Modifier.padding(top = 10.dp), style = MikaType.Body, color = c.ink2, textAlign = TextAlign.Center)
                PillButton(action, Modifier.padding(top = MikaSpace.S20).testTag("voicecall-gate-action"), go)
            }
        }
    }
}
