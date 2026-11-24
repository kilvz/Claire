package com.wangzan101.mika.capsule

import kotlinx.coroutines.delay

import androidx.compose.runtime.produceState

import com.wangzan101.mika.chat.ui.KeptPageCopy

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.chat.ui.ScreenCopy
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme

/** The card's base: exactly [height] tall when [fixed] (conversation card), otherwise sized to content up to [height] (task card). */
@Composable
internal fun CardFrame(height: Dp, fixed: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.testTag("capsule-card").width(cardWidth()).then(if (fixed) Modifier.height(height) else Modifier.heightIn(max = height))
            .glass(c, CardShape, need = false).padding(12.dp),
        content = content,
    )
}

/** Title bar (holding it drags the card, see [CapsuleCard]'s onHandle): Claire logo on the left, title and state in the middle, collapse on the right. */
@Composable
internal fun Header(title: String, sub: String, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Sub, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(MikaIcons.Power, tr(R.string.quit_title), actions::askQuit)
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

/** Title bar of the idle card: "Continue ‘…’ · New chat" or "New chat · Continue last chat"; when there is no conversation at all, only "New chat". */
@Composable
internal fun IdleHeader(card: CardView.Chat, actions: CardActions, handle: Modifier) {
    val c = MikaTheme.colors
    val continuing = !card.fresh && card.conversationId != null
    Row(handle.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaLogo(28.dp, corner = 14.dp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (continuing) tr(R.string.card_continuing, card.title.orEmpty().let(ScreenCopy::plain).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }) else CardCopy.NEW_CHAT,
                Modifier.weight(1f, fill = false), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (card.conversationId != null) {
                Text(" · ", style = Sub, color = c.ink3)
                TextLink(if (continuing) CardCopy.NEW_CHAT else CardCopy.CONTINUE_LAST) { actions.freshChat(continuing) }
            }
        }
        IconButton(MikaIcons.Power, tr(R.string.quit_title), actions::askQuit)
        IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
    }
}

@Composable
private fun IconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MikaTheme.colors.ink3)
    }
}

/**
 * The "quit completely" confirmation: after the power button in the title bar is tapped it replaces the card content. It spells out what will happen: background tasks stop, the capsule and the notification go away,
 * and Claire comes back the next time it is opened. "Cancel" returns to the previous card, "Quit" is handed to [CardActions.quit].
 * The card is sized to content: at least as tall as a task card and no taller than [height].
 */
@Composable
internal fun QuitCard(actions: CardActions, height: Dp = Dp.Unspecified) {
    val c = MikaTheme.colors
    val quitLabel = tr(R.string.quit_title)
    CardFrame(height, fixed = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MikaLogo(28.dp, corner = 14.dp)
            Text(tr(R.string.quit_title), Modifier.weight(1f), style = Title, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(MikaIcons.Close, CardCopy.COLLAPSE, actions::collapse)
        }
        Text(tr(R.string.quit_confirm_body), Modifier.padding(top = 10.dp), style = Body, color = c.ink2)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).testTag("capsule-quit-cancel").clip(MikaShapes.Pill).background(c.fill)
                    .clickable(role = Role.Button, onClickLabel = tr(R.string.cancel)) { actions.cancelQuit() },
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.cancel), style = Strong, color = c.ink) }
            // Same as "Close page": pale red background with red text, kept apart from the other actions
            Box(
                Modifier.weight(1f).height(40.dp).testTag("capsule-quit").clip(MikaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClickLabel = quitLabel) { actions.quit() },
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.quit_confirm_action), style = Strong, color = c.bad) }
        }
    }
}

/** Task card: the item currently operating an app or web page; sized to content, with the middle section taking whichever is taller, the small screen on the left or the text on the right (steps are at most as tall as the small screen). */
@Composable
internal fun Detail(
    card: CardView.Detail, preview: Bitmap?, actions: CardActions, elapsed: @Composable (Long?) -> String?,
    height: Dp, handle: Modifier, input: @Composable () -> Unit, queuedCount: Int = 0, onQueued: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    val r = card.run
    val sub = listOfNotNull(CapsuleCards.status(r.kind), elapsed(r.startedAt)).joinToString(" · ")
    CardFrame(height, fixed = false) {
        Header(ScreenCopy.plain(r.run.goal).ifBlank { "Claire" }, sub, actions, handle)
        val screen = preview?.takeIf { CapsuleCards.showsScreen(card.content) }
        // The maximum height of the steps section = the small screen's height; with no screen yet, derive the same height from the main screen's aspect ratio so nothing jumps when the screen arrives
        val config = LocalConfiguration.current
        val stepsMax = ScreenWidth / (screen?.let(::ratio) ?: ratio(config.screenWidthDp, config.screenHeightDp))
        Row(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (screen != null) MiniScreen(screen) { actions.zoom(true) }
            Column(Modifier.weight(1f)) {
                val content = card.content
                // Steps while working are laid out separately (they scroll on their own, at most as tall as the small screen) and have no buttons for you to tap
                if (content is CardContent.Working) Steps(content.steps, stepsMax) else {
                    // If the text does not fit it scrolls within this section; the buttons you tap sit below and do not scroll away
                    Column(
                        Modifier.testTag("capsule-scroll").weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (content) {
                            // Working steps are laid out separately above
                            is CardContent.Working -> Unit
                            is CardContent.Takeover -> {
                                Text(content.need, style = Strong, color = c.warn)
                                if (content.body.isNotEmpty()) Text(content.body, style = Body, color = c.ink2)
                                content.queueLine?.let { Text(it, style = Sub, color = c.ink3) }
                            }
                            is CardContent.Confirm -> {
                                Text(content.title, style = Strong, color = c.warn)
                                Text(content.body, style = Body, color = c.ink2)
                                Text(ConfirmationCopy.NOTE, style = Sub, color = c.ink3)
                            }
                            is CardContent.Question -> Text(content.prompt, style = Body, color = c.ink)
                            is CardContent.Held -> Text(content.why, style = Body, color = c.ink2)
                            // Finished results are conversation cards and do not appear here
                            is CardContent.Result -> Unit
                        }
                    }
                    Pinned(content, r.run.key, actions)
                }
            }
        }
        QueuedLine(queuedCount, onQueued)
        input()
        FooterLinks(actions, CardCopy.SEE_PROCESS to { actions.openMika(r.run.conversationId) })
    }
}

/**
 * Card for the page left running in the background: title "Background page · 1 hour 52 minutes left", a live view (the latest frame shown in its area; tap to enlarge),
 * the site and which conversation it came from, and below that "Open conversation" and "Close page". Sized to content. The remaining time advances once per minute.
 */
@Composable
internal fun KeptCard(card: CardView.Kept, preview: Bitmap?, actions: CardActions, height: Dp, handle: Modifier) {
    val c = MikaTheme.colors
    val p = card.page
    val now by produceState(System.currentTimeMillis(), p.id) {
        while (true) {
            delay(KEPT_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    CardFrame(height, fixed = false) {
        Header(tr(R.string.kept_card_title), tr(R.string.kept_card_left, KeptPageCopy.duration(p.closesAt - now)), actions, handle)
        Column(Modifier.padding(top = 10.dp).weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null) {
                val image = remember(preview) { preview.asImageBitmap() }
                Box(
                    Modifier.testTag("kept-live").fillMaxWidth().aspectRatio(ratio(preview)).clip(ScreenShape)
                        .border(1.dp, c.glassEdge, ScreenShape).background(Color.Black)
                        .clickable(role = Role.Image, onClickLabel = CardCopy.SCREEN) { actions.zoom(true) }
                        .semantics { contentDescription = tr(R.string.kept_card_live) },
                ) {
                    Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Text(p.host, style = Strong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(tr(R.string.kept_card_from, ScreenCopy.plain(p.goal).let { if (it.length > TITLE_MAX) it.take(TITLE_MAX) + "…" else it }), tr(R.string.kept_card_live))
                    .joinToString(" · "),
                style = Sub, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).testTag("kept-open")) { Action(tr(R.string.kept_open_chat), ButtonKind.Tonal) { actions.openMika(p.conversationId) } }
            // Close is a pale red button with red text, kept apart from the other buttons
            Box(
                Modifier.weight(1f).height(40.dp).testTag("kept-close").clip(MikaShapes.Pill).background(c.badSoft)
                    .clickable(role = Role.Button, onClick = actions::closeKeptPage),
                contentAlignment = Alignment.Center,
            ) { Text(tr(R.string.kept_close), style = Strong, color = c.bad) }
        }
    }
}

private const val KEPT_TICK_MS = 30_000L

/** The buttons you tap (takeover, approve, options, resume), pinned below the card's middle section and not scrolling with it. */
@Composable
internal fun Pinned(content: CardContent?, key: String, actions: CardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content) {
            is CardContent.Takeover -> {
                Spacer(Modifier.height(0.dp))
                if (content.canTakeOver) Action(CardCopy.TAKE_OVER, ButtonKind.Primary) { actions.takeOver(key) }
                Action(CardCopy.STOP, ButtonKind.Tonal) { actions.stop(key) }
            }
            is CardContent.Confirm -> {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.APPROVE_ONCE_ONLY, ButtonKind.Primary) { actions.approve(key, content.key) }
                Action(CardCopy.DECLINE, ButtonKind.Tonal) { actions.decline(key, content.key) }
            }
            is CardContent.Question -> if (content.options.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                content.options.forEach { o -> Action(o, ButtonKind.Tonal) { actions.answer(key, o) } }
            }
            is CardContent.Held -> if (content.resumable) {
                Spacer(Modifier.height(0.dp))
                Action(CardCopy.RESUME, ButtonKind.Primary) { actions.resume(key) }
            }
            else -> Unit
        }
    }
}
