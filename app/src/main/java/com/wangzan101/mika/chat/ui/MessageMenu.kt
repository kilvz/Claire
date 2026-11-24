package com.wangzan101.mika.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.QuoteMessage
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.settings.ui.models.edit
import com.wangzan101.mika.ui.Route

/**
 * A message long-pressed in the conversation page: [key] identifies which one it is (this is what "select text" records), [from] is who said it, [text] is the plain text used for copying and quoting
 * (for answers the Markdown symbols are stripped, for the user's own words the attachment, screen and quote lines are removed), [bounds] is its position and size in the window, [lifted] draws the raised copy.
 */
internal class MessageTarget(
    val key: String,
    val from: QuoteMessage.From,
    val text: String,
    val bounds: Rect,
    val lifted: @Composable () -> Unit,
)

/** The long-press menu the conversation page gives messages: [selecting] is the one currently in "select text" mode, [open] pops up the menu. */
internal class MessageMenuHost(val selecting: String?, val open: (MessageTarget) -> Unit)

/** When absent (testing a single component in isolation), long-pressing a message still selects text. */
internal val LocalMessageMenu = compositionLocalOf<MessageMenuHost?> { null }

/**
 * The text part of a message: a long press (with one haptic tick) pops up the copy / quote / select-text menu; the one that chose "select text" goes back to the system's long-press selection ([selectTag] tags the selection).
 * Links in the text still open as before (the tap is handled by the text inside first). File cards and "view process" are not wrapped in here. When [text] is blank no menu is shown.
 * [modifier] is this message's own backing (background, width, padding), and the raised copy is laid out by its position and size; outer margins go outside.
 */
@Composable
internal fun MessageArea(
    key: String,
    from: QuoteMessage.From,
    text: String,
    selectTag: String,
    modifier: Modifier = Modifier,
    lifted: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalMessageMenu.current
    if (host == null || host.selecting == key || text.isBlank()) {
        SelectionContainer(modifier.testTag(selectTag)) { content() }
        return
    }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.chat_message_actions)
    // Position and size in the window (not clipped to the part scrolled out of the list); only read inside the callback, so it does not trigger recomposition
    val bounds = remember { mutableStateOf(Rect.Zero) }
    // The gesture always uses the latest copy of this (streaming answers recompose nonstop), so it does not restart on recomposition or lose a long press that is halfway through
    val open by rememberUpdatedState({ host.open(MessageTarget(key, from, text, bounds.value, lifted)) })
    Box(
        Modifier
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(modifier)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    // One haptic tick, same as a system long press
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    open()
                })
            }
            .semantics { onLongClick(label) { open(); true } }
            .testTag("message-$key"),
    ) { content() }
}

/**
 * The menu after long-pressing a message (same look as long-pressing a conversation in the sidebar, see [LongPressMenu]): that message is raised, and below are copy, quote and select text;
 * an answer that has finished can also "branch from here", and the user's own words can "ask again from here" (see [onBranch], [onBranchRetry]). The user's own words sit on the right, and so does the menu.
 */
@Composable
internal fun MessageMenu(
    target: MessageTarget,
    progress: () -> Float,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onBranchRetry: (() -> Unit)? = null,
    onSaveTask: (() -> Unit)? = null,
    onSaveChecklist: (() -> Unit)? = null,
) {
    LongPressMenu(
        target.bounds, progress, pane = stringResource(R.string.chat_message_actions), tag = "message-menu",
        entries = buildList {
            add(MenuEntry(stringResource(R.string.chat_copy), MikaIcons.Copy, "message-menu-copy", onClick = onCopy))
            add(MenuEntry(stringResource(R.string.chat_quote), MikaIcons.Quote, "message-menu-quote", onClick = onQuote))
            add(MenuEntry(stringResource(R.string.chat_select_text), MikaIcons.SelectText, "message-menu-select", onClick = onSelect))
            onSaveChecklist?.let { add(MenuEntry(stringResource(R.string.checklist_save_reply), MikaIcons.ListChecks, "message-menu-checklist", onClick = it)) }
            onSaveTask?.let { add(MenuEntry(stringResource(R.string.saved_task_save), MikaIcons.Star, "message-menu-save-task", onClick = it)) }
            onBranch?.let { add(MenuEntry(stringResource(R.string.chat_branch), MikaIcons.Route, "message-menu-branch", onClick = it)) }
            onBranchRetry?.let { add(MenuEntry(stringResource(R.string.chat_branch_retry), MikaIcons.Route, "message-menu-branch-retry", onClick = it)) }
            onEdit?.let { add(MenuEntry(stringResource(R.string.chat_edit_message), MikaIcons.Edit, "message-menu-edit", onClick = it)) }
            onWithdraw?.let { add(MenuEntry(stringResource(R.string.chat_withdraw_message), MikaIcons.Close, "message-menu-withdraw", onClick = it)) }
        },
        onDismiss = onDismiss,
        alignEnd = target.from == QuoteMessage.From.You,
        lifted = target.lifted,
    )
}

/** The layer behind while the menu is open: from Android 12 it blurs according to the reveal progress ([MenuBlur]), and accessibility reads only the menu. Adds nothing when the menu is not open. */
internal fun Modifier.behindMenu(open: Boolean, progress: () -> Float): Modifier =
    if (!open) this else this
        .then(
            if (GlassBlurSupported) Modifier.graphicsLayer {
                val r = MenuBlur.toPx() * progress()
                renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            } else Modifier,
        )
        .clearAndSetSemantics {}

/** Puts the message's plain text on the clipboard and shows a brief "copied" toast. */
internal fun copyMessage(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claire", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}

/** How a quote is read out to the screen reader: "quoting Claire: …", "quoting what you said: …". */
@Composable
private fun quoteDescription(q: QuoteMessage.Quote): String =
    stringResource(if (q.from == QuoteMessage.From.Mika) R.string.chat_quoting_mika else R.string.chat_quoting_you, q.text)

/** The pending quote above the input field: a vertical bar on the left, at most two lines of quoted content, an × on the right to remove it. */
@Composable
internal fun QuoteChip(quote: QuoteMessage.Quote, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val remove = stringResource(R.string.chat_quote_remove)
    val said = quoteDescription(quote)
    Row(
        modifier.fillMaxWidth().testTag("quote-chip").cardSurface(c, QuoteShape).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(c.accent, CircleShape))
        Text(
            quote.text, Modifier.weight(1f).padding(start = 10.dp).semantics { contentDescription = said },
            style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = remove, onClick = onRemove)
                .semantics { contentDescription = remove }
                .testTag("quote-remove"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
}

/** The line quoted in the user's own message: a small pale block above the bubble, at most two lines. */
@Composable
internal fun SentQuote(quote: QuoteMessage.Quote, maxWidth: Dp) {
    val c = MikaTheme.colors
    val said = quoteDescription(quote)
    Row(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), QuoteShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .testTag("sent-quote"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(16.dp).background(c.ink3, CircleShape))
        Text(quote.text, Modifier.padding(start = 8.dp), style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val QuoteShape = MikaShapes.Chip
