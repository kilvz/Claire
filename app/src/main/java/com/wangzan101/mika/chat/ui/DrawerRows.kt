package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.ExperimentalFoundationApi
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.toSize
import com.wangzan101.mika.ui.components.keepsKeyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.wangzan101.mika.ui.components.MikaTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.browser.ui.PageAskCopy
import com.wangzan101.mika.ui.Route

/**
 * A conversation: tap to open it; long-press (with a short vibration) pops up its action menu in place (pin, rename,
 * delete). When conversations cannot be switched (a task is running and parallelism is off) it cannot be tapped open, but
 * long-press still works. Ones not yet saved into the conversation list (no id) have nothing to change and do not respond
 * to long-press. A pinned one shows a small pin before the title.
 * In multi-select mode ([selecting]) tapping a row checks/unchecks it and a checkbox appears on the left; drafts never saved do not take part.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationEntry(
    item: DrawerConversation,
    onOpen: (DrawerConversation) -> Unit,
    onActions: (DrawerConversation, Rect) -> Unit,
    /** Keywords highlighted in search results; empty in the normal list. */
    terms: List<String> = emptyList(),
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: (DrawerConversation) -> Unit = {},
    /** Whether to show the small archived icon before the title; every row in the archive view is archived, so it does not need marking again. */
    showArchivedMark: Boolean = true,
    /** In lists that are not grouped by project (running/search/archived), mark the owning project with a small folder. */
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val deletable = item.id != null
    val selectable = selecting && deletable
    val haptic = LocalHapticFeedback.current
    // This row's position and size within the window (not clipped to the part scrolled out of the list); the menu places the lifted row from it, and it is only read in the callback, so it does not cause recomposition
    val bounds = remember { mutableStateOf(Rect.Zero) }
    val row: @Composable () -> Unit = {
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
                // When swiping sideways, the foreground must cover the archive layer underneath, so the buttons cannot show through the title and time.
                .background(c.bg, NavShape)
                // The current conversation is filled with light grey instead of a shadowed card
                .then(if (item.current) Modifier.background(c.fill, NavShape) else Modifier)
                .clip(NavShape)
                .then(
                    when {
                        selectable -> Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggleSelect(item) })
                        // In multi-select mode, drafts never saved do not respond, so a tap does not dismiss the drawer
                        selecting -> Modifier
                        deletable -> Modifier.combinedClickable(
                            onClickLabel = null,
                            onLongClickLabel = stringResource(R.string.drawer_actions),
                            // Vibrate once, like a system long-press does
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onActions(item, bounds.value) },
                            onClick = { if (item.enabled) onOpen(item) },
                        )
                        else -> Modifier.clickable(enabled = item.enabled, onClick = { onOpen(item) })
                    },
                )
                .testTag("conversation-row")
                .alpha(if (item.enabled || selectable) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selectable) SelectMark(selected)
                Box(Modifier.weight(1f)) { ConversationRowContent(item, terms, showArchivedMark, showProjectMark) }
            }
        }
    }
    row()
}

/** The checkbox on the left of each row in multi-select mode: an empty circle when unselected, a filled circle with a white check when selected. */
@Composable
private fun SelectMark(selected: Boolean) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(c.accent) else Modifier)
            .border(1.5.dp, if (selected) c.accent else c.edge, CircleShape)
            .testTag("conversation-check"),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(MikaIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
    }
}

/** The contents of a conversation row: the running dot, pin, title and time, plus a second line when there is a subtitle. Used by both the drawer row and the card lifted by a long-press. */
@Composable
internal fun ConversationRowContent(
    item: DrawerConversation,
    terms: List<String> = emptyList(),
    showArchivedMark: Boolean = true,
    showProjectMark: Boolean = false,
) {
    val c = MikaTheme.colors
    val title = remember(item.title, terms, c.accent) { highlighted(item.title, terms, c.accent) }
    val showProjectName = showProjectMark && !item.projectName.isNullOrBlank()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (item.running) Box(Modifier.size(7.dp).background(c.accent, CircleShape))
            if (item.pinned) Icon(MikaIcons.Pin, stringResource(R.string.drawer_pinned_mark), Modifier.size(14.dp).testTag("conversation-pin"), tint = c.ink3)
            if (showProjectMark && item.projectId != null && !showProjectName) Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(14.dp).testTag("conversation-project"), tint = c.ink3)
            if (item.branch) Icon(MikaIcons.Route, stringResource(R.string.chat_branch_origin), Modifier.size(14.dp).testTag("conversation-branch"), tint = c.ink3)
            if (item.archived && showArchivedMark) Icon(MikaIcons.Archive, stringResource(R.string.drawer_archived), Modifier.size(14.dp).testTag("conversation-archived"), tint = c.ink3)
            Text(title, Modifier.weight(1f, fill = item.site == null), style = if (item.current) ConversationTitleCurrent else ConversationTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.site != null) {
                Text(
                    PageAskCopy.WEB, Modifier.background(c.accentSoft, RoundedCornerShape(MikaRadius.BubbleTail)).padding(horizontal = 6.dp).testTag("conversation-web"),
                    style = ConversationTag, color = c.accent, maxLines = 1,
                )
                Box(Modifier.weight(1f))
            }
            if (item.time.isNotEmpty()) {
                Text(item.time, Modifier.padding(start = 5.dp).testTag("conversation-time"), style = ConversationTime, color = c.ink3, maxLines = 1, softWrap = false)
            }
        }
        val sub = item.subtitle.ifEmpty { item.site.orEmpty() }
        if (showProjectName || sub.isNotEmpty()) {
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showProjectName) {
                    Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(12.dp).testTag("conversation-project"), tint = c.ink2)
                    Text(
                        item.projectName!!,
                        Modifier.weight(1f, fill = false),
                        style = ConversationTime, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (sub.isNotEmpty()) Text("·", style = ConversationTime, color = c.ink3)
                }
                if (sub.isNotEmpty()) {
                    val styled = remember(sub, terms, c.accent) { highlighted(sub, terms, c.accent) }
                    Text(styled, Modifier.weight(1f, fill = !showProjectName), style = ConversationTime, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Rename a conversation: the field holds the current title (fully selected, so typing replaces it); an empty one cannot be saved, and leading/trailing whitespace is trimmed. */
@Composable
internal fun RenameConversationDialog(title: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val ok = value.text.isNotBlank()
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            KeyboardDismissArea {
                MikaTextField(
                    value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus).keepsKeyboard().testTag("rename-conversation-field"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onSave(value.text.trim()) }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text.trim()) }, Modifier.testTag("confirm-rename-conversation"), enabled = ok) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Confirmation before deleting a conversation: states which conversation is being deleted; a running task will be
 * stopped, and if schedules are attached it states that the schedule will be kept.
 * Like other delete confirmations: "Delete" in red on the right, "Cancel" on the left.
 */
@Composable
internal fun DeleteConversationDialog(title: String, impact: DeleteImpact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val body = listOfNotNull(
        stringResource(R.string.drawer_delete_body, title),
        if (impact.runningTask) stringResource(R.string.drawer_delete_running) else null,
        if (impact.schedules > 0) trPlural(R.plurals.drawer_delete_schedules, impact.schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = { Text(body, Modifier.testTag("delete-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Confirmation before deleting conversations in bulk: states how many will be deleted; any running tasks among them will
 * be stopped, and if schedules are attached it states that the schedule will be kept.
 * Same as the single-delete confirmation: "Delete" in red on the right, "Cancel" on the left.
 */
@Composable
internal fun DeleteManyConversationsDialog(
    count: Int, runningTasks: Int, schedules: Int,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    val body = listOfNotNull(
        trPlural(R.plurals.drawer_delete_many_body, count, count),
        if (runningTasks > 0) trPlural(R.plurals.drawer_delete_many_running, runningTasks, runningTasks) else null,
        if (schedules > 0) trPlural(R.plurals.drawer_delete_many_schedules, schedules, schedules) else null,
    ).joinToString("\n")
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_many_title)) },
        text = { Text(body, Modifier.testTag("delete-many-conversation-body")) },
        confirmButton = {
            TextButton(onClick = onConfirm, Modifier.testTag("confirm-delete-many-conversation")) {
                Text(stringResource(R.string.confirm_delete), color = MikaTheme.colors.bad)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, Modifier.testTag("dismiss-delete-many-conversation")) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
