package com.wangzan101.mika.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.ui.components.GlassBlurSupported
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the long-press menu is placed: the top edge of the lifted row (possibly moved so the menu fits), the top edge of the menu, and whether the menu sits above that row. */
internal data class MenuPlacement(val rowTop: Float, val menuTop: Float, val above: Boolean)

/**
 * Placement of the long-press menu. [top] and [bottom] are the top and bottom edges things can be placed within
 * (status bar, navigation bar and keyboard excluded).
 * The row is first moved into that range; if the menu fits below it, it goes below, otherwise above if it fits there;
 * when neither fits (the keyboard takes up most of the screen), the row is moved up and the menu follows right under it,
 * and if that still is not enough the row is pushed to the very top.
 */
internal fun placeMenu(rowTop: Float, rowHeight: Float, menuHeight: Float, gap: Float, top: Float, bottom: Float): MenuPlacement {
    val row = rowTop.coerceIn(top, max(top, bottom - rowHeight))
    if (row + rowHeight + gap + menuHeight <= bottom) return MenuPlacement(row, row + rowHeight + gap, above = false)
    if (row - gap - menuHeight >= top) return MenuPlacement(row, row - gap - menuHeight, above = true)
    val moved = max(top, bottom - menuHeight - gap - rowHeight)
    return MenuPlacement(moved, moved + rowHeight + gap, above = false)
}

/**
 * Menu shown after long-pressing a conversation in the drawer (see [LongPressMenu]): that row lifts into a white card,
 * and the menu holds pin / unpin, archive / unarchive, rename, and delete in red. An archived conversation is not in the
 * main list, so pinning does not apply to it; drafts never saved and conversations running a task cannot be archived.
 * [bounds] is the row's position and size within the window.
 */
@Composable
internal fun ConversationMenu(
    item: DrawerConversation,
    bounds: Rect,
    progress: () -> Float,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onRename: () -> Unit,
    onTerminal: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_actions), tag = "drawer-menu",
        entries = listOfNotNull(
            MenuEntry(stringResource(R.string.drawer_select), MikaIcons.ListChecks, "drawer-menu-select", onClick = onSelect),
            if (item.archived) null else MenuEntry(stringResource(if (item.pinned) R.string.unpin else R.string.pin), MikaIcons.Pin, "drawer-menu-pin", onClick = onPin),
            when {
                item.archived -> MenuEntry(stringResource(R.string.drawer_unarchive), MikaIcons.Archive, "drawer-menu-unarchive", onClick = onUnarchive)
                item.saved && !item.running -> MenuEntry(stringResource(R.string.drawer_archive), MikaIcons.Archive, "drawer-menu-archive", onClick = onArchive)
                else -> null
            },
            MenuEntry(stringResource(R.string.drawer_rename), MikaIcons.Edit, "drawer-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.terminal_open), MikaIcons.Terminal, "drawer-menu-terminal", onClick = onTerminal),
            MenuEntry(stringResource(R.string.export_conversation), MikaIcons.Share, "drawer-menu-export", onClick = onExport),
            MenuEntry(stringResource(R.string.confirm_delete), MikaIcons.Trash, "drawer-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) { LiftedRow(item) }
}

/** Menu shown after long-pressing a project group header in the drawer: new chat in the project, rename, open terminal, delete project. */
@Composable
internal fun ProjectMenu(
    project: Project,
    bounds: Rect,
    progress: () -> Float,
    onNewChat: () -> Unit,
    onRename: () -> Unit,
    onTerminal: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MikaTheme.colors
    LongPressMenu(
        bounds, progress, pane = stringResource(R.string.drawer_project_actions), tag = "drawer-project-menu",
        entries = listOf(
            MenuEntry(stringResource(R.string.drawer_project_new_chat), MikaIcons.New, "project-menu-new-chat", onClick = onNewChat),
            MenuEntry(stringResource(R.string.drawer_project_rename_title), MikaIcons.Edit, "project-menu-rename", onClick = onRename),
            MenuEntry(stringResource(R.string.terminal_open), MikaIcons.Terminal, "project-menu-terminal", onClick = onTerminal),
            MenuEntry(stringResource(R.string.confirm_delete), MikaIcons.Trash, "project-menu-delete", c.bad, onDelete),
        ),
        onDismiss = onDismiss,
    ) {
        Box(
            Modifier
                .floatShadow(c, NavShape)
                .background(c.surface, NavShape)
                .clip(NavShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Icon(MikaIcons.Folder, null, Modifier.size(16.dp), tint = c.ink2)
                Text(project.name, style = MikaType.Body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One entry in the long-press menu: text, icon on the right, test tag, and color (null means the body text color). */
internal class MenuEntry(val text: String, val icon: ImageVector, val tag: String, val color: Color? = null, val onClick: () -> Unit)

/**
 * Menu that pops up in place after long-pressing something (shared by drawer conversations and chat page messages): the
 * whole screen behind dims (and blurs from Android 12 on, applied to the content behind by the caller, see [MenuBlur]),
 * that thing "lifts" in place ([lifted], drawn by itself as a white card or similar, slightly scaled up), and a narrower
 * white card below it (above it when it does not fit) lists [entries], text on the left and icon on the right.
 * Tapping outside or pressing back dismisses it. [progress] is the appearance progress (0 to 1); the caller decides from
 * whether the closing animation is running whether to animate there or snap straight into place.
 * It is only read while drawing and placing, so it does not recompose during the animation. [bounds] is that thing's
 * position and size within the window: the lifted card is as wide as it, with its height from its own content, at most
 * half the screen (a very long answer only lifts its opening section, which [lifted] crops itself). With [alignEnd] the
 * menu aligns to its right edge (the user's own words sit on the right).
 */
@Composable
internal fun LongPressMenu(
    bounds: Rect,
    progress: () -> Float,
    pane: String,
    tag: String,
    entries: List<MenuEntry>,
    onDismiss: () -> Unit,
    alignEnd: Boolean = false,
    lifted: @Composable () -> Unit,
) {
    val c = MikaTheme.colors
    val insets = WindowInsets.safeDrawing
    val view = LocalView.current
    val close = stringResource(R.string.collapse)
    // When blur is available the background is already blurred, so a light dim is enough; without blur, dimming alone has to push the background back
    val dim = c.floatShadow.copy(alpha = if (GlassBlurSupported) 0.16f else 0.32f)
    BackHandler(onBack = onDismiss)
    Layout(
        content = {
            lifted()
            MenuCard {
                entries.forEachIndexed { i, e ->
                    if (i > 0) MenuDivider(c.hair)
                    MenuItem(e.text, e.icon, e.tag, e.color ?: c.ink, e.onClick)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
            .drawBehind { drawRect(dim.copy(alpha = dim.alpha * progress())) }
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics {
                paneTitle = pane
                isTraversalGroup = true
                dismiss(close) { onDismiss(); true }
            }
            .testTag(tag),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val rowW = bounds.width.roundToInt().coerceIn(0, w)
        val lifted = measurables[0].measure(Constraints(minWidth = rowW, maxWidth = rowW, maxHeight = (h / 2).coerceAtLeast(0)))
        val menuW = min(MenuWidth.roundToPx(), w).coerceAtLeast(0)
        val menu = measurables[1].measure(Constraints(minWidth = menuW, maxWidth = menuW, maxHeight = h))
        layout(w, h) {
            // The row position is in window coordinates; this layer does not necessarily start at the window's top-left corner (when it does not extend under the system bars)
            val origin = coordinates?.positionInWindow() ?: Offset.Zero
            val top = max(0f, insets.getTop(this@Layout) - origin.y)
            val bottom = min(h.toFloat(), view.rootView.height - insets.getBottom(this@Layout) - origin.y)
            val at = placeMenu(bounds.top - origin.y, lifted.height.toFloat(), menu.height.toFloat(), MenuGap.toPx(), top, bottom)
            val x = (bounds.left - origin.x).roundToInt()
            val menuX = (if (alignEnd) x + rowW - menuW else x).coerceIn(0, max(0, w - menuW))
            lifted.placeWithLayer(x, at.rowTop.roundToInt()) {
                scaleX = 1f + LiftScale * progress()
                scaleY = scaleX
            }
            menu.placeWithLayer(menuX, at.menuTop.roundToInt()) {
                val p = progress()
                alpha = p
                scaleX = 0.92f + 0.08f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, if (at.above) 1f else 0f)
            }
        }
    }
}

/** The lifted row: the same content as the row in the drawer, but on a rounded white background with a shadow. */
@Composable
private fun LiftedRow(item: DrawerConversation) {
    val c = MikaTheme.colors
    Box(
        Modifier
            .floatShadow(c, NavShape)
            .background(c.surface, NavShape)
            .clip(NavShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ConversationRowContent(item)
    }
}

@Composable
private fun MenuCard(content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    Column(
        Modifier.floatShadow(c, MikaShapes.Card).clip(MikaShapes.Card)
            .background(c.surface)
            .border(0.5.dp, c.edge, MikaShapes.Card)
            .semantics { popup() },
    ) { content() }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tag: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MikaType.Body, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(icon, null, Modifier.padding(start = 12.dp).size(MikaSize.Icon), tint = color)
    }
}

@Composable
private fun MenuDivider(color: Color) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** How much the content behind is blurred while the menu appears (from Android 12 on). */
internal val MenuBlur = 14.dp
private val MenuWidth = 220.dp
private val MenuGap = 8.dp
private const val LiftScale = 0.03f
