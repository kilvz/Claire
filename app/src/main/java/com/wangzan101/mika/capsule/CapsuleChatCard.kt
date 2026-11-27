package com.wangzan101.mika.capsule




import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.chat.ui.UserMessage
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Conversation card: that conversation's back-and-forth scrolls in the middle; when an answer or approval is needed, the buttons are pinned below the thread, and below those come the input field and "Back to Claire".
 * Without a task it is the idle card (the title bar can switch to a new conversation). While idle, "Teach Claire" is tucked into the arrow above the input bar and is not expanded by default.
 */
@Composable
internal fun ChatCard(
    card: CardView.Chat, thread: ChatThread?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit, queuedCount: Int = 0, onQueued: (() -> Unit)? = null,
) {
    val r = card.run
    CardFrame(height) {
        if (r == null) IdleHeader(card, actions, handle)
        else Header(
            ScreenCopy.plain(thread?.title ?: r.run.goal).ifBlank { "Claire" },
            listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · "),
            actions, handle,
        )
        Box(Modifier.padding(top = 8.dp).weight(1f).fillMaxWidth()) {
            // When the conversation changes (the idle card switches to a new conversation), start reading again from that conversation's latest exchange
            if (thread != null) key(card.target) { ThreadList(thread, card.conversationId, actions) }
        }
        if (r != null) Pinned(card.content, r.run.key, actions)
        QueuedLine(queuedCount, onQueued)
        if (r == null) TeachShelf(actions::teach, actions::voiceChat)
        input()
        FooterLinks(actions, card.target?.let { id -> CardCopy.BACK_TO_MIKA to { actions.openMika(id) } })
    }
}

/**
 * "Teach Claire" directly above the idle card's input bar: by default only an arrow is left, and tapping it reveals the entries; tapping again collapses them.
 * The arrow points up when collapsed and down when expanded. Learning overlays do not go through here.
 */
@Composable
private fun TeachShelf(onTeach: () -> Unit, onVoiceChat: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = tr(R.string.teach_menu)
    val expandLabel = tr(R.string.teach_shelf_expand)
    val collapseLabel = tr(R.string.teach_shelf_collapse)
    val action = if (open) collapseLabel else expandLabel
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (open) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TextLink(name, onClick = onTeach)
            TextLink(tr(R.string.voicecall_menu), onClick = onVoiceChat)
        }
        Box(
            Modifier.testTag("capsule-teach-shelf").widthIn(min = 48.dp).height(28.dp).clip(MikaShapes.Pill)
                .clickable(role = Role.Button, onClickLabel = action) { open = !open }
                .semantics {
                    contentDescription = name
                    stateDescription = action
                    if (open) collapse(collapseLabel) { open = false; true } else expand(expandLabel) { open = true; true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MikaIcons.Chevron, null,
                Modifier.size(16.dp).graphicsLayer { rotationZ = if (open) 90f else -90f },
                tint = MikaTheme.colors.ink3,
            )
        }
    }
}

/**
 * The card's bottom line: [primary] ("See process", "Back to Claire"; absent on the idle card when there is no conversation yet) and a gray "Turn off floating capsule".
 * The × in the top right only collapses the card; people who want the capsule to stop appearing turn it off here instead of looking for the setting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FooterLinks(actions: CardActions, primary: Pair<String, () -> Unit>?) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        primary?.let { (text, onClick) ->
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
                TextLink(text, onClick = onClick)
                Text("·", style = Sub, color = MikaTheme.colors.ink3)
            }
        }
        Box(Modifier.align(Alignment.CenterVertically)) {
            TextLink(CardCopy.HIDE_CAPSULE, MikaTheme.colors.ink3, actions::hideCapsule)
        }
    }
}

/**
 * The conversation's back-and-forth (oldest on top): when opened, the latest message you said is parked at the top; scrolling to the top loads more rounds of history, and the list stays put by each item's key;
 * text that grows downwards is only followed when you were already at the bottom (same as the chat page); when a new exchange arrives it scrolls to it. [footer] is placed after the last item.
 */
@Composable
internal fun ThreadList(thread: ChatThread, conversationId: String?, actions: CardActions, footer: (@Composable () -> Unit)? = null) {
    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    val density = LocalDensity.current
    val reduceMotion = MikaTheme.reduceMotion
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = thread.anchor)
    var following by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    var scrollingToBottom by remember { mutableStateOf(false) }
    var scrollToBottomJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        following = !state.canScrollForward
        settled = true
        var wasScrolling = false
        snapshotFlow { state.isScrollInProgress to !state.canScrollForward }.collect { (scrolling, atEnd) ->
            if (!scrollingToBottom && (scrolling || wasScrolling)) following = atEnd
            wasScrolling = scrolling
        }
    }
    val canScrollForward by remember { derivedStateOf { state.canScrollForward } }
    val showScrollToBottom = settled && thread.items.isNotEmpty() && !following && canScrollForward
    val anchorKey = thread.items.getOrNull(thread.anchor)?.key
    var lastAnchor by remember { mutableStateOf(anchorKey) }
    LaunchedEffect(anchorKey) {
        if (anchorKey == lastAnchor) return@LaunchedEffect
        lastAnchor = anchorKey
        scrollToBottomJob?.cancel()
        state.scrollToItem(thread.anchor)
        following = !state.canScrollForward
    }
    // Answers are laid out as Markdown (parsed in the background while streaming), so the height changes only once layout is done: follow based on the item count and the laid-out height of the last item, not on the character count
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }?.size
        }.collect { (n, _) ->
            if (following && !state.isScrollInProgress && n > 0) state.scrollToItem(n - 1, END_OFFSET)
        }
    }
    if (thread.hasEarlier) LaunchedEffect(thread.items.size) {
        snapshotFlow { state.firstVisibleItemIndex == 0 }.first { it }
        actions.loadEarlier()
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.testTag("capsule-thread").fillMaxSize().glassSource(backdrop), state = state) {
            items(thread.items, key = { it.key }) { item ->
                when (item) {
                    // The message that carries a screen: the thumbnail plus "your screen right now · App · time", with any accompanying note in a separate bubble
                    is ThreadItem.User -> BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                        val maxWidth = maxWidth
                        Column(horizontalAlignment = Alignment.End) {
                            UserMessage(conversationId, item.text, ThreadThumbWidth) { text ->
                                Text(
                                    text,
                                    Modifier.widthIn(max = maxWidth * 0.8f).background(c.accent, BubbleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                                    style = Body, color = c.onAccent,
                                )
                            }
                            // Where the steered message went (received, queued, next item)
                            item.note?.let { Text(it, Modifier.padding(top = 2.dp, end = 4.dp), style = Sub, color = c.ink3) }
                        }
                    }
                    is ThreadItem.Note -> Text(
                        item.text, Modifier.padding(bottom = 4.dp), style = Sub,
                        color = if (item.kind.needsYou) c.warn else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    // Tables and code blocks scroll horizontally within their own area instead of widening the card; links open in a separate task and are clickable inside the overlay too
                    is ThreadItem.Answer -> if (!item.cut) {
                        MarkdownText(item.text, Body.copy(color = c.ink), Modifier.fillMaxWidth().padding(bottom = 8.dp), streaming = item.streaming)
                    } else Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        // The part cut off by a steered message: lighter color, with a small line below (the same sentence as on the chat page)
                        MarkdownText(item.text, Body.copy(color = c.ink2), Modifier.fillMaxWidth(), streaming = false)
                        Text(tr(R.string.steer_interrupted_reply), Modifier.padding(top = 2.dp), style = Sub, color = c.ink3)
                    }
                }
            }
            // A section appended after the thread (the current steps in the browser page panel): it follows the last item and moves down with it when following the bottom
            footer?.let { f -> item(FOOTER_KEY) { f() } }
        }
        ScrollToBottomButton(
            visible = showScrollToBottom,
            onClick = {
                scrollToBottomJob?.cancel()
                following = true
                scrollToBottomJob = scope.launch {
                    scrollingToBottom = true
                    try {
                        scrollListToBottom(state, density, reduceMotion)
                        following = !state.canScrollForward
                    } finally {
                        scrollingToBottom = false
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 6.dp),
            backdrop = backdrop,
            reduceMotion = reduceMotion,
            buttonSize = KeySize,
            iconSize = 18.dp,
            tag = "capsule-scroll-to-bottom",
        )
    }
}

private const val FOOTER_KEY = "thread-footer"

/** Width of the screenshot thumbnail in the card's thread. */
private val ThreadThumbWidth = 78.dp

/** When following the bottom, scroll this many pixels past the last item; the list clamps it to the real bottom. */
private const val END_OFFSET = 1_000_000
