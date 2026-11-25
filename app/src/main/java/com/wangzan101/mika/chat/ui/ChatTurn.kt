package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.GoalChecklist
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.ui.components.DirectionalIcon
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wangzan101.mika.browser.ui.SentPage
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.ui.MarkdownModel
import com.wangzan101.mika.ui.MarkdownText

/** Brief highlight on the jump target row: a full-width tinted background that fades out after about 1.8 seconds. */
@Composable
internal fun JumpHighlight(active: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    val alpha by animateFloatAsState(if (active) 1f else 0f, if (reduceMotion) snap() else tween(260), label = "jump-highlight")
    Box(
        Modifier.fillMaxWidth()
            .background(c.accent.copy(alpha = 0.12f * alpha), MikaShapes.Card),
    ) { content() }
}

internal fun LazyListScope.exchange(
    index: Int, ex: Exchange, conversationId: String?, stage: Takeover.Stage, vm: ChatViewModel, reduceMotion: Boolean, backgroundNote: Boolean,
    openRun: (String) -> Unit,
    kept: KeptPage? = null,
    /** Key of the row to highlight when jumping in from search or the file library. */
    highlightKey: String? = null,
    /** The two new-run entries after a terminal state: true continues from where the summary broke off, false restarts from the original goal. */
    recover: (Boolean) -> Unit = {},
    /** A front-desk reply directly below the previous front-desk reply (Claire sending several in a row): smaller top-left corner and closer to the previous one. */
    joinedAbove: Boolean = false,
    /** The next front-desk reply directly below this one. */
    joinedBelow: Boolean = false,
) {
    // A front desk turn: the user's words and the front desk's reply; it does not go through the task steps and status cards
    if (ex.deskReply != null) {
        deskExchange(index, ex, conversationId, reduceMotion, highlightKey, joinedAbove, joinedBelow)
        return
    }
    if (ex.goal.isNotEmpty()) item("u$index") {
        JumpHighlight(highlightKey == "u$index", reduceMotion) { UserBubble(ex.goal, conversationId, key = "u$index") }
    }
    val paused = ex.status is ExchangeStatus.Paused || ex.status is ExchangeStatus.WaitingUnlock ||
        ex.status is ExchangeStatus.NeedsTakeover || ex.status is ExchangeStatus.NeedsConfirmation ||
        ex.status is ExchangeStatus.NeedsInput || ex.status is ExchangeStatus.Queued
    val active = ex.status.isActive()
    val answering = ex.status == ExchangeStatus.Working && !ex.draft.isNullOrBlank()
    // Steers: while working they are inserted into the steps as a step on the right (see [StepLine]); when steps are not
    // running (finished, or writing the summary) they collapse into a subdued small line right below the user message,
    // setting the subordinate apart from the main conversation before it. Withdrawn steers are not shown.
    val steers = ex.steers.filterNot { it.state == SteerState.Withdrawn }
    if (active && !answering) {
        item("s$index") { WorkBlock(ex, steers.map { it.text }, reduceMotion, backgroundNote, openRun) }
    } else {
        steers.forEach { n ->
            item("m$index-${n.id}") {
                JumpHighlight(highlightKey == "m$index-${n.id}", reduceMotion) { SteerSupplement(n.text) }
            }
        }
    }
    ex.draft?.takeIf(String::isNotBlank)?.let { draft -> item("d$index") { DraftText(draft, ex.waitingForModel) } }
    item("r$index") {
        JumpHighlight(highlightKey == "r$index", reduceMotion) {
            StatusCard(ex.status, stage, vm, ex.recordedRun, "r$index", conversationId = conversationId, recover = recover)
        }
    }
    // The page left running in the background after this turn: a green row while it is still open (and can be closed), and a reason once it is closed
    keptPageRow(index, ex, kept, vm::closeKeptPage)
    if (ex.audios.isNotEmpty()) item("au$index") { GeneratedAudioCards(ex.audios, vm) }
    if (ex.photos.isNotEmpty()) item("p$index") {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { GeneratedPhotos(ex.photos, conversationId, 292.dp) }
    }
    if (ex.files.isNotEmpty()) item("f$index") {
        JumpHighlight(highlightKey == "f$index", reduceMotion) { FileCards(ex.files) }
    }
    learningNotes(index, ex, vm)
}

/**
 * Steers split into two parts: those before the first one not yet handed to the model ([SteerState.unconsumed]), and that
 * one together with everything after it. The answer currently growing is placed between the two parts.
 */
internal fun steersAroundDraft(steers: List<SteerNote>): Pair<List<SteerNote>, List<SteerNote>> {
    val visible = steers.filterNot { it.state == SteerState.Withdrawn }
    val first = visible.indexOfFirst { it.state.unconsumed }.let { if (it < 0) visible.size else it }
    return visible.take(first) to visible.drop(first)
}

/**
 * When the long-pressed [key] is a phone steer in the running question-answer that the model has not claimed yet, returns
 * it, so the menu can offer withdraw and edit (edit is additionally gated by [editableSteer]).
 * Ones appended from the computer ([SteerNote.remote]) are not returned: editing would turn words that belong only to this
 * task into a phone steer and could move them to the next item; whether to withdraw is decided on the computer.
 */
internal fun withdrawableSteer(exchanges: List<Exchange>, key: String): SteerNote? = exchanges.mapIndexedNotNull { i, ex ->
    if (ex.status.isActive()) ex.steers.firstOrNull { key == "m$i-${it.id}" && it.state.unconsumed && !it.remote } else null
}.firstOrNull()

internal fun ExchangeStatus.isActive() =
    this !is ExchangeStatus.Done && this !is ExchangeStatus.Failed && this !is ExchangeStatus.Cancelled

/**
 * The user's words: a pale blue bubble (dark text), 20 corner radius with a 6 tail corner at the bottom right, at most 80%
 * wide. [note] is where a steer went.
 * When it carries the user's current screen, above it are the screenshot thumbnail and "your current screen · app · time",
 * with the accompanying text in the bubble below (see [UserMessage]).
 * When it carries attachments, above it are photo tiles and file cards (see [SentAttachments]), opened in the in-app viewer;
 * the written text goes in a separate bubble below, and the path lines are not shown.
 * When it quotes an earlier message, the quoted one comes first (see [SentQuote]). Long-pressing the bubble brings up copy /
 * quote / select text ([key] identifies which message, see [MessageArea]).
 * For one asking about a page in the browser, at the top are "web page · site" and the selected text (see [SentPage]), and the page-note lines are not shown.
 */
@Composable
internal fun UserBubble(text: String, conversationId: String?, note: String? = null, key: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val paged = remember(text) { AttachedPage.parse(text) }
    val quoted = remember(text) { QuoteMessage.parse(paged.words) }
    val attached = remember(text) { AttachmentMessage.parse(quoted.words) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        Column(horizontalAlignment = Alignment.End) {
            paged.page?.let { SentPage(it, max) }
            quoted.quote?.let { SentQuote(it, max) }
            if (attached.hasAttachments) {
                SentAttachments(attached, conversationId, max) { item ->
                    val id = conversationId ?: return@SentAttachments
                    scope.launch { if (!AttachFiles.openSent(context, id, item.path)) Toast.makeText(context, AttachCopy.GONE, Toast.LENGTH_SHORT).show() }
                }
            }
            if (attached.hasAttachments && attached.words.isNotEmpty()) Spacer(Modifier.size(6.dp))
            if ((!attached.hasAttachments || attached.words.isNotEmpty()) && (paged.page == null || quoted.words.isNotEmpty())) UserMessage(conversationId, attached.words.takeIf { attached.hasAttachments } ?: quoted.words, ChatThumbWidth) { words ->
                // The user bubble is a solid light accent with dark text: the selected background uses a slightly stronger accent, and the handle stays accent-colored (drawn outside the bubble)
                val colors = MikaTheme.colors
                val bubble = colors.userBubble
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f))) {
                    MessageArea(
                        key, QuoteMessage.From.You, words, "selectable-user",
                        Modifier.widthIn(max = max).background(bubble, MikaShapes.UserBubble),
                        lifted = { LiftedMessage(MikaShapes.UserBubble, bubble) { UserBubbleText(words) } },
                    ) { UserBubbleText(words) }
                }
            }
            note?.let { Text(it, Modifier.padding(top = 3.dp, end = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3) }
        }
    }
}

@Composable
private fun UserBubbleText(words: String) {
    Text(words, Modifier.padding(horizontal = 13.dp, vertical = 9.dp), style = BubbleText, color = MikaTheme.colors.ink)
}

/** The message lifted by a long-press: [color] background with a shadow; when too long (at most half the screen) only the start is shown and the rest is clipped below. */
@Composable
private fun LiftedMessage(shape: Shape, color: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.floatShadow(MikaTheme.colors, shape).background(color, shape).clip(shape)
            .wrapContentHeight(Alignment.Top, unbounded = true),
    ) { content() }
}

/** Width of the screenshot thumbnail on the chat page. */
private val ChatThumbWidth = 110.dp

/** The answer being generated: on the same pale gray background as a finished answer. */
@Composable
private fun DraftText(text: String, generating: Boolean) {
    MarkdownText(
        text, AnswerText.copy(color = MikaTheme.colors.ink),
        Modifier.padding(bottom = ReplyGap).answerSurface(), streaming = generating,
    )
}

/** The subdued small line a steer collapses into after finishing: right-aligned and a touch lighter than the body text, right under the user message, setting the subordinate apart from the main answer. */
@Composable
private fun SteerSupplement(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(text, Modifier.padding(horizontal = 4.dp), style = SupplementText, color = MikaTheme.colors.inkSoft)
    }
}

/** A steer while working, as a "step inserted on the right": a pale blue pill, right-aligned, set apart from the model's steps on the left. */
@Composable
private fun SteerStepPill(text: String) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            Modifier.background(c.accentSoft, SteerPillShape).clip(SteerPillShape).padding(horizontal = 12.dp, vertical = 6.dp),
            style = StepText, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A piece of Claire's answer: long-pressing brings up copy / quote / select text (see [MessageArea]), and copy and quote use
 * the plain text with Markdown symbols stripped. [modifier] is the answer's background, and the lifted card shows the same
 * text on a white card. While selecting text the selection handles live in a separate popup window, so dragging them does not pull out the drawer.
 */
@Composable
internal fun AnswerArea(key: String, answer: String, modifier: Modifier, content: @Composable () -> Unit) {
    val plain = remember(answer) { MarkdownModel.toPlainText(answer) }
    MessageArea(
        key, QuoteMessage.From.Mika, plain, "selectable-answer", modifier,
        lifted = {
            LiftedMessage(MikaShapes.AnswerBubble, MikaTheme.colors.surface) {
                MarkdownText(answer, AnswerText.copy(color = MikaTheme.colors.ink), Modifier.padding(horizontal = 14.dp, vertical = 12.dp), streaming = false)
            }
        },
    ) { content() }
}

/** The spacing between several replies under the same message. */
internal val ReplyGap = 8.dp

/**
 * Background of Claire's answer: a solid light grey in light mode, one level brighter in dark mode; no transparency or shadow. Bottom-left tail corner.
 * Short ones hug the text width, long ones fill the width; front-desk replies, reports and old-style answers all use this one.
 */
@Composable
internal fun Modifier.answerSurface(joinedAbove: Boolean = false): Modifier =
    background(MikaTheme.colors.answer, if (joinedAbove) MikaShapes.AnswerBubbleJoined else MikaShapes.AnswerBubble)
        .padding(horizontal = 13.dp, vertical = 10.dp)

/** Distance between two replies when Claire sends several in a row. */
internal val JoinedReplyGap = 3.dp

/**
 * Whether turn [i]'s front-desk reply directly follows the previous turn's front-desk reply: this turn has no user words, the previous reply was drawn,
 * and no timeline or call line separates them.
 */
internal fun replyJoinsAbove(exchanges: List<Exchange>, i: Int, calls: CallMarks): Boolean {
    if (i <= 0 || i > exchanges.lastIndex) return false
    val ex = exchanges[i]
    val prev = exchanges[i - 1]
    if (ex.deskReply == null || ex.goal.isNotEmpty() || !deskReplyShown(ex) || !deskReplyShown(prev)) return false
    return calls.before(i).isEmpty() && timeMarkAt(exchanges, i, calls) == null
}

// ---------- Progress ----------

/**
 * In progress: one status row (breathing dot + current progress + elapsed time), below it a vertical-rail step list
 * (gray dots for finished steps, a blue dot for the current one; when stopped waiting for you the current step is an orange dot).
 * While operating the phone in the background there is one more small line at the bottom.
 */
@Composable
internal fun WorkBlock(ex: Exchange, steers: List<String>, reduceMotion: Boolean, backgroundNote: Boolean, openRun: (String) -> Unit) {
    val c = MikaTheme.colors
    val paused = ChatCopy.paused(ex.status)
    val elapsed = rememberElapsed(ex.startedAt)
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BreathingDot(paused, reduceMotion)
            Text(
                ChatCopy.progressLine(ex), Modifier.weight(1f), style = NowText, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (elapsed != null) Text(ChatCopy.clock(elapsed), style = ClockText, color = c.ink3)
        }
        ex.goals?.let { GoalChecklistRow(it, ex.runId) }
        if (ex.steps.isNotEmpty() || steers.isNotEmpty()) StepLine(ex.steps, ex.stepActions, steers, live = true, paused = paused)
        // The run process can be opened while it is still running (the page keeps growing by the second); after finishing it is folded into the "did N steps · view process" row
        ex.recordedRun?.let { id ->
            Row(
                Modifier.padding(top = 2.dp).clip(MikaShapes.Pill).clickable(role = Role.Button) { openRun(id) }
                    .padding(horizontal = 4.dp, vertical = 4.dp).testTag("work-view-process"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.chat_view_process), style = DoneText, color = c.ink3)
                DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(12.dp), tint = c.ink3)
            }
        }
        if (backgroundNote) {
            Row(
                Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(MikaIcons.Eye, null, Modifier.size(15.dp), tint = c.ink3)
                Text(ChatCopy.BACKGROUND_NOTE, style = NoteText, color = c.ink3)
            }
        }
    }
}

@Composable
private fun GoalChecklistRow(goals: GoalChecklist, runId: String?) {
    val c = MikaTheme.colors
    var expanded by rememberSaveable(runId) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(StatusCopy.goals(goals), style = NoteText, color = c.ink2)
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp).rotate(if (expanded) -90f else 90f), tint = c.ink3)
        }
        if (expanded) goals.items.forEachIndexed { i, goal ->
            Text("${if (i + 1 in goals.confirmed) "✓" else "○"}  $goal", style = NoteText, color = c.ink2)
        }
    }
}

/** Elapsed time since [startedAt], updated every second; null when the start time is unknown (0). */
@Composable
private fun rememberElapsed(startedAt: Long): Long? {
    if (startedAt <= 0L) return null
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L - value % 1_000L)
        }
    }
    return (now - startedAt).coerceAtLeast(0L)
}

private enum class Dot { Done, Current, Paused, Dim }

/**
 * The vertical-rail step list. With [live], the last step is marked with a blue or orange dot according to the task's
 * current state; while running only the latest [ChatCopy.VISIBLE_STEPS] steps are listed, with the earlier ones folded into
 * one row that expands to show them all.
 */
@Composable
private fun StepLine(steps: List<StepRow>, actions: Map<Int, List<StepAction>>, steers: List<String>, live: Boolean, paused: Boolean) {
    val c = MikaTheme.colors
    var all by rememberSaveable { mutableStateOf(!live) }
    // Which steps are expanded: recorded by index within this screen so it stays consistent as the list grows
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
    // Only offer the expand/collapse toggle when there are enough steps to be worth folding (more than the recent steps listed on this screen)
    val collapsible = steps.size > ChatCopy.VISIBLE_STEPS
    val hidden = if (all) 0 else (steps.size - ChatCopy.VISIBLE_STEPS).coerceAtLeast(0)
    Column(
        Modifier.padding(top = 6.dp).fillMaxWidth().drawBehind {
            val x = StepRail.toPx()
            drawLine(c.hair, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        },
    ) {
        // Top toggle: "N earlier steps" while collapsed, "Collapse" while expanded; tapping switches back and forth
        if (collapsible) {
            val label = if (all) stringResource(R.string.collapse) else ChatCopy.earlierSteps(hidden)
            StepItem(label, "", Dot.Dim, current = false, link = true, onClick = { all = !all })
        }
        val visible = steps.drop(hidden)
        visible.forEachIndexed { i, row ->
            val key = hidden + i
            val isLast = key == steps.lastIndex
            // The user's steer: a step on the right, inserted between the finished steps and the current step
            if (isLast) steers.forEach { SteerStepPill(it) }
            val dot = when {
                live && isLast && paused -> Dot.Paused
                live && isLast && row.state == StepState.Run -> Dot.Current
                row.state == StepState.Dim -> Dot.Dim
                else -> Dot.Done
            }
            val open = expanded[key] == true
            val detail = actions[row.step].orEmpty()
            StepItem(
                row.text, row.subtitle, dot, current = dot == Dot.Current || dot == Dot.Paused,
                actions = detail, open = open,
                // Only expandable when there are concrete actions: expand to see what this step actually did, on what, and with what result
                onClick = if (detail.isEmpty()) null else ({ expanded[key] = !open }),
            )
        }
        // When there are no steps at all yet (a steer was just handed to the task), show only the steer step
        if (visible.isEmpty()) steers.forEach { SteerStepPill(it) }
        // "Collapse" at the bottom: once expanded with many steps, it can be collapsed at the end without scrolling back up
        if (all && collapsible) {
            StepItem(stringResource(R.string.collapse), "", Dot.Dim, current = false, link = true, onClick = { all = false })
        }
    }
}

@Composable
private fun StepItem(
    text: String, subtitle: String, dot: Dot, current: Boolean, link: Boolean = false, onClick: (() -> Unit)? = null,
    actions: List<StepAction> = emptyList(), open: Boolean = false,
) {
    val c = MikaTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = stringResource(if (open) R.string.collapse else R.string.chat_expand_steps), onClick = onClick)
                } else Modifier,
            )
            .heightIn(min = 28.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(StepRail * 2), contentAlignment = Alignment.Center) { StepDot(dot) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text, style = if (current) StepCurrentText else StepText,
                color = when {
                    link -> c.accent
                    current -> c.ink
                    else -> c.ink2
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) Text(subtitle, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Expanded: the few things this step actually did, one per line as "what · on what · result", worded the same as on the run progress page
            if (open) actions.forEach { a ->
                Text(
                    ChatCopy.actionLine(a), Modifier.padding(top = 2.dp),
                    style = MikaType.Hint, color = if (a.failed) c.bad else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Only the ones with actions can be expanded: a small arrow on the right, pointing down when expanded
        if (actions.isNotEmpty()) DirectionalIcon(
            MikaIcons.Chevron, null,
            Modifier.padding(start = 6.dp).size(12.dp).graphicsLayer { rotationZ = if (open) if (rtl) -90f else 90f else 0f },
            tint = c.ink3,
        )
    }
}

/** Step dot: 6dp filled; the current step has a 3dp pale ring around it. */
@Composable
private fun StepDot(dot: Dot) {
    val c = MikaTheme.colors
    val (fill, ring) = when (dot) {
        Dot.Done -> c.ink3 to null
        Dot.Dim -> c.hair to null
        Dot.Current -> c.accent to c.accentSoft
        Dot.Paused -> c.warnSolid to c.warnSoft
    }
    // The background covers the rail first so the dot stays clean
    val bg = c.bg
    Box(
        Modifier.size(12.dp).drawBehind {
            val center = Offset(size.width / 2, size.height / 2)
            if (ring != null) {
                drawCircle(bg, 6.dp.toPx(), center)
                drawCircle(ring, 6.dp.toPx(), center)
            }
            drawCircle(fill, 3.dp.toPx(), center)
        },
    )
}

/** Spinner: a pale ring with one solid segment at the top. When [spinning] is false (animations off, stopped waiting for you) it stays put. */
@Composable
internal fun Spinner(modifier: Modifier, spinning: Boolean, track: Color, head: Color) {
    val turn = if (!spinning) 0f else rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    ).value
    Box(
        modifier.graphicsLayer { rotationZ = turn }
            .border(2.dp, track, CircleShape)
            .border(
                2.dp,
                Brush.sweepGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 0.63f to head, 0.88f to head, 0.89f to Color.Transparent),
                CircleShape,
            ),
    )
}

/** The "alive" signal on the working row: a breathing dot (pale to solid, slightly scaling up); it turns orange and still when stopped waiting for you. */
@Composable
private fun BreathingDot(paused: Boolean, reduceMotion: Boolean) {
    val c = MikaTheme.colors
    val active = !paused && !reduceMotion
    val breathe = if (active) rememberInfiniteTransition(label = "breathe").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    ).value else 1f
    val scale = if (active) 0.8f + 0.38f * breathe else 1f
    val alpha = if (active) 0.28f + 0.72f * breathe else 1f
    Box(
        Modifier.padding(top = 5.dp).size(9.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .background(if (paused) c.warnSolid else c.accent, CircleShape),
    )
}
