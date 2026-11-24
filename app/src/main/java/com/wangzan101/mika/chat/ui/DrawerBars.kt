package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.components.DirectionalIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.about.ui.UpdateDot

/** Small button at the top of the drawer: "Select" normally, and "Cancel" while selecting. */
@Composable
internal fun HeaderAction(text: String, tag: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag), contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(text, style = MikaType.Label, color = color)
    }
}

/** Row at the top of the archive view: goes back to the normal drawer, with how many were put away written on the right. */
@Composable
internal fun ArchiveHeader(count: Int, onBack: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(NavShape).clickable(role = Role.Button, onClick = onBack)
            .padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
            .testTag("drawer-archive-back"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(MikaIcons.Back, null, Modifier.size(16.dp), tint = c.ink2)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = SectionText, color = c.ink3)
        if (count > 0) {
            Text(stringResource(R.string.drawer_archived_count, count), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        }
    }
}

/** There is nothing at all in the archive view. */
@Composable
internal fun ArchiveEmpty() {
    Text(
        stringResource(R.string.drawer_archive_empty),
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 16.dp),
        style = MikaType.Body, color = MikaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}

/** "Archived" entry at the bottom of the drawer: opens the conversations that were put away. */
@Composable
internal fun ArchiveEntry(count: Int, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .clip(NavShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("drawer-archive"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(16.dp), tint = c.ink3)
        Text(stringResource(R.string.drawer_archived), Modifier.weight(1f), style = MikaType.Body, color = c.ink2, maxLines = 1)
        Text("$count", Modifier.testTag("drawer-archive-count"), style = MikaType.Hint, color = c.ink3, maxLines = 1, softWrap = false)
        DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
    }
}

/** Bottom action bar while multi-selecting: how many are selected, select all / deselect all, delete; the normal drawer also has "Archive", which becomes "Unarchive" in the archive view. */
@Composable
internal fun SelectBar(
    selectedCount: Int, allSelected: Boolean, onSelectAll: () -> Unit,
    archived: Boolean, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-select-bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            tr(R.string.drawer_selected_count, selectedCount), Modifier.weight(1f),
            style = MikaType.Label, color = c.ink2, maxLines = 1, softWrap = false,
        )
        TextButton(onClick = onSelectAll, modifier = Modifier.testTag("drawer-select-all")) {
            Text(stringResource(if (allSelected) R.string.drawer_select_none else R.string.drawer_select_all), style = MikaType.Label, color = c.accent)
        }
        if (archived) {
            TextButton(onClick = onUnarchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-unarchive-selected")) {
                Text(stringResource(R.string.drawer_unarchive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        } else {
            TextButton(onClick = onArchive, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-archive-selected")) {
                Text(stringResource(R.string.drawer_archive), style = MikaType.Label, color = if (selectedCount > 0) c.accent else c.ink3)
            }
        }
        TextButton(onClick = onDelete, enabled = selectedCount > 0, modifier = Modifier.testTag("drawer-delete-selected")) {
            Text(stringResource(R.string.confirm_delete), style = MikaType.Label, color = if (selectedCount > 0) c.bad else c.ink3)
        }
    }
}

/** Result of a batch archive: how many were archived; if some were left alone because they are running a task or are the current conversation, it states exactly how many were not archived. */
@Composable
internal fun ArchiveStatusBar(batch: ArchiveBatch) {
    val c = MikaTheme.colors
    val text = when {
        batch.archived <= 0 -> trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
        batch.skipped <= 0 -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived)
        else -> trPlural(R.plurals.drawer_archive_many_done, batch.archived, batch.archived) + " · " +
            trPlural(R.plurals.drawer_archive_many_skipped, batch.skipped, batch.skipped)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-archive-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = if (batch.archived > 0) c.ok else c.ink3)
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

/** One conversation just archived: the bottom bar states which conversation was put away, and it can be undone within a few seconds. */
@Composable
internal fun ArchiveUndoBar(undo: ArchiveUndo, onUndo: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp).testTag("drawer-archive-undo"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Archive, null, Modifier.size(14.dp), tint = c.ink3)
        Text(
            tr(R.string.drawer_archive_done, undo.title), Modifier.weight(1f),
            style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onUndo, modifier = Modifier.testTag("drawer-archive-undo-action")) {
            Text(stringResource(R.string.undo), style = MikaType.Label, color = c.accent)
        }
    }
}

/** Progress and result of a batch delete: while deleting it reads "Deleting N conversations…", and when done it reports how many succeeded and how many failed. */
@Composable
internal fun DeleteStatusBar(batch: DeleteBatch) {
    val c = MikaTheme.colors
    val deleted = batch.done - batch.failed
    val text = when {
        !batch.finished -> trPlural(R.plurals.drawer_delete_progress, batch.total, batch.total)
        batch.failed == 0 -> trPlural(R.plurals.drawer_delete_done, deleted, deleted)
        else -> trPlural(R.plurals.drawer_delete_partial, deleted, deleted, batch.failed)
    }
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
            .padding(horizontal = 20.dp, vertical = 10.dp).testTag("drawer-delete-status"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (batch.finished) {
            Icon(
                if (batch.failed == 0) MikaIcons.Check else MikaIcons.Exclaim, null, Modifier.size(14.dp),
                tint = if (batch.failed == 0) c.ok else c.bad,
            )
        }
        Text(text, style = MikaType.Hint, color = c.ink2, maxLines = 2)
    }
}

@Composable
internal fun NavEntry(
    icon: ImageVector, text: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, dot: Boolean = false, onClick: () -> Unit,
) {
    val c = MikaTheme.colors
    Row(
        modifier.height(46.dp).clip(NavShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(MikaSize.Icon), tint = c.ink2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MikaType.Body, color = c.ink, maxLines = 1, softWrap = false)
            if (dot) UpdateDot()
        }
    }
}

/** New conversation button to the right of the drawer search box: neutral round background + dark plus, matching the top bar's ✚. */
@Composable
internal fun NewConversationButton(enabled: Boolean, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(c.fill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.chat_new), onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MikaIcons.New, stringResource(R.string.chat_new), Modifier.size(18.dp), tint = c.ink)
    }
}

/** Section heading for "Running", with a "Background screens ›" link on the right that opens the background screens page (this section only exists while a task is running). */
@Composable
internal fun RunningLabel(onScreens: () -> Unit) {
    val c = MikaTheme.colors
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.drawer_running)) }
        Row(
            Modifier.clip(NavShape).clickable(role = Role.Button, onClick = onScreens)
                .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 4.dp).testTag("drawer-screens"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(MikaIcons.Screens, null, Modifier.size(14.dp), tint = c.ink3)
            Text(stringResource(R.string.drawer_screens), style = MikaType.Hint, color = c.ink2)
            DirectionalIcon(MikaIcons.Chevron, null, Modifier.size(10.dp), tint = c.ink3)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 6.dp),
        style = SectionText, color = MikaTheme.colors.ink3,
    )
}
