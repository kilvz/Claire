package com.wangzan101.mika.chat.ui


import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.Project
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.settings.Prerequisites
import androidx.compose.ui.geometry.Rect
import com.wangzan101.mika.ui.components.MikaLogo
import com.wangzan101.mika.ui.components.lazyListScrollbar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.WordmarkWeight

/**
 * Drawer: slides out from the start edge together with the chat card. The top holds the wordmark and status; then the navigation entries; the middle holds the running section (the section header's right side opens the "background screens" page) and recent conversations; the very bottom has settings and about side by side.
 * Opening and closing are managed by the drawer outside; this only lays out content and hands taps to callbacks.
 */
@Composable
fun ChatDrawer(
    check: Prerequisites.Check,
    sections: DrawerSections,
    canSwitch: Boolean,
    /** Text in the search field; when non-blank the drawer switches to search results and the entry area gives way to them. */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** Results for the current query; [DrawerSearch.searching] is true while the search is still running. */
    search: DrawerSearch? = null,
    /** The search field is shown only when there are conversations to search. */
    canSearch: Boolean = false,
    /** Archive filter for search results: all / only unarchived / only archived. */
    searchFilter: SearchArchiveFilter = SearchArchiveFilter.All,
    onSearchFilterChange: (SearchArchiveFilter) -> Unit = {},
    onNew: () -> Unit,
    onPersonal: () -> Unit = {},
    onPlans: () -> Unit = {},
    onMemory: () -> Unit = {},
    onBrowser: () -> Unit = {},
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onOpen: (DrawerConversation) -> Unit,
    onAbout: () -> Unit = {},
    /** A new version is available: "About Claire" gets a small red dot. */
    aboutDot: Boolean = false,
    /** Long-press on a saved conversation: pop up the action menu (pin, rename, delete) in place; carries that row's position and size in the window. */
    onActions: (DrawerConversation, Rect) -> Unit = { _, _ -> },
    /** Selection mode: a checkbox appears on the left of each row and tapping a row toggles it; the bottom becomes "N selected / Select all / Delete". */
    selecting: Boolean = false,
    selected: Set<String> = emptySet(),
    onExitSelect: () -> Unit = {},
    onToggleSelect: (DrawerConversation) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /** "Unarchive" after multi-selecting in the archive view; unused in the normal drawer. */
    onUnarchiveSelected: () -> Unit = {},
    /** "Archive" after multi-selecting in the normal drawer; unused in the archive view. */
    onArchiveSelected: () -> Unit = {},
    /** Progress and results of a batch delete; shown at the bottom and cleared from outside a few seconds after it finishes. */
    deleteBatch: DeleteBatch? = null,
    /** Result of a batch archive; shown at the bottom and cleared from outside a few seconds later. */
    archiveBatch: ArchiveBatch? = null,
    /** The item just archived; the bottom shows "Undo" and it is cleared from outside a few seconds later. */
    archiveUndo: ArchiveUndo? = null,
    onUndoArchive: () -> Unit = {},
    /** Conversations listed in the archive view; newest archived first. */
    archived: List<DrawerConversation> = emptyList(),
    /** The archive view is showing: the list becomes archived conversations and the search field and navigation entries are collapsed. */
    archivedView: Boolean = false,
    onOpenArchive: () -> Unit = {},
    onExitArchive: () -> Unit = {},
    showProjectsSection: Boolean = false,
    collapsedProjects: Set<String> = emptySet(),
    onToggleProject: (String) -> Unit = {},
    onNewProject: () -> Unit = {},
    onNewInProject: (String) -> Unit = {},
    onProjectActions: (Project, Rect) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    containerColor: Color = MikaTheme.colors.bg,
    permanent: Boolean = false,
    onShowScreen: (() -> Unit)? = null,
) {
    val c = MikaTheme.colors
    // Only conversations currently listed in the drawer and already saved can be checked (a current draft not saved yet has no id and cannot be deleted); in the archive view it is the archived ones
    val selectableIds = remember(sections, archived, archivedView) {
        drawerSelectableIds(sections, archived, archivedView)
    }
    val selectedCount = selected.count { it in selectableIds }
    val allSelected = selectableIds.isNotEmpty() && selectedCount == selectableIds.size
    // While searching, the entries collapse to give their height to the results, and return as they were once cleared. The search field sits above the entries, so nothing jumps
    val searchActive = canSearch && !selecting && !archivedView && query.isNotBlank()
    Column(
        modifier.fillMaxHeight().then(if (permanent) Modifier else Modifier.widthIn(max = DrawerWidth)).fillMaxWidth()
            .background(containerColor)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.navigationBars))
            .testTag("drawer"),
    ) {
        val (state, ok) = readiness(check)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MikaLogo(36.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text("Claire", style = MikaType.Title.copy(fontWeight = WordmarkWeight), color = c.ink)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).background(if (ok) c.ok else c.warnSolid, CircleShape))
                    Text(state, style = MikaType.Hint, fontWeight = FontWeight.Medium, color = c.ink3)
                }
            }
            // Selection is entered from the long-press menu; once in, this becomes "Cancel", and the back key exits as well
            if (selecting) HeaderAction(stringResource(R.string.cancel), "drawer-select-cancel", c.ink2, onExitSelect)
        }
        if (!searchActive && !selecting && !archivedView && permanent) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                onShowScreen?.let { NavEntry(MikaIcons.Screens, stringResource(R.string.screens_preview), onClick = it) }
                NavEntry(MikaIcons.Message, stringResource(R.string.drawer_personal), onClick = onPersonal)
                NavEntry(MikaIcons.Clock, stringResource(R.string.drawer_plans), onClick = onPlans)
                NavEntry(MikaIcons.Heart, stringResource(R.string.memory_and_skills), onClick = onMemory)
                NavEntry(MikaIcons.Globe, stringResource(R.string.drawer_browser), onClick = onBrowser)
            }
        }
        // The search field and new conversation share a row: the field takes the width and a neutral round plus sits on the right (only when not searching); the plus stays even when there is nothing to search
        val showNew = !searchActive && !selecting && !archivedView
        if (canSearch && !selecting && !archivedView) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrawerSearchField(query, onQueryChange, Modifier.weight(1f))
                if (showNew) NewConversationButton(canSwitch, onNew)
            }
        } else if (showNew) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                NewConversationButton(canSwitch, onNew)
            }
        }
        // While searching, the row below the field filters by archive state; the archive view has no search, so naturally no such row either
        if (searchActive) SearchArchiveFilterRow(searchFilter, onSearchFilterChange)
        // While the drawer is closed the list holds only the trailing item; when it opens, conversations are inserted before it and the list would stay pinned at the bottom on that former item. When the topmost item changes, scroll back to the top
        val list = rememberLazyListState()
        val first = when {
            archivedView -> "h-archive"
            searchActive -> "h-search"
            sections.running.isNotEmpty() -> "h-running"
            showProjectsSection -> "h-projects"
            sections.pinned.isNotEmpty() -> "h-pinned"
            sections.recent.isNotEmpty() -> "h-recent"
            else -> "end"
        }
        LaunchedEffect(first) { list.requestScrollToItem(0) }
        val terms = remember(query) { ConversationSearch.termsOf(query) }
        // The archive view groups by this week / this month / earlier; groups live in the same list
        val archiveGrouping = remember(archived) { archiveGroups(archived, System.currentTimeMillis()) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).lazyListScrollbar(list), state = list) {
            if (searchActive) {
                item("h-search") { SectionLabel(stringResource(R.string.drawer_search_results)) }
                if (!canSwitch) {
                    item("search-busy") {
                        Text(
                            stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                            style = MikaType.Hint, color = c.ink3,
                        )
                    }
                }
                // The hint stays while bodies are being scanned; it also shows when title hits are already listed, indicating results will be filled in further
                if (search?.searching != false) item("searching") { SearchHint(stringResource(R.string.drawer_searching)) }
                items(search?.hits.orEmpty(), key = { "s-${it.id}" }) {
                    // With "only archived" every row is archived so no mark is needed; other filters mark collapsed conversations with the small archive icon
                    ConversationEntry(it, onOpen, onActions, terms, showArchivedMark = searchFilter != SearchArchiveFilter.Archived, showProjectMark = true)
                }
                if (search != null && !search.searching && search.hits.isEmpty()) {
                    item("search-empty") { SearchEmpty(query) }
                }
                if (search?.truncated == true) {
                    item("search-more") { SearchHint(stringResource(R.string.drawer_search_truncated, ConversationSearch.MAX_HITS)) }
                }
            } else if (archivedView) {
                item("h-archive") { ArchiveHeader(archived.size, onExitArchive) }
                if (archived.isEmpty()) item("archive-empty") { ArchiveEmpty() }
                archiveGrouping.forEach { group ->
                    item("h-archive-${group.period.name}") { SectionLabel(archivePeriodLabel(group.period)) }
                    items(group.rows, key = { "a-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, showArchivedMark = false, showProjectMark = true,
                            selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect,
                        )
                    }
                }
            } else {
                if (sections.running.isNotEmpty()) {
                    // The right side of the "running" section opens the "background screens" page: see which app each running task is using and its live frame
                    item("h-running") { RunningLabel(onScreens) }
                    items(sections.running, key = { "r-${it.id}" }) {
                        ConversationEntry(it, onOpen, onActions, selecting = selecting, selected = it.id in selected, onToggleSelect = onToggleSelect, showProjectMark = true)
                    }
                }
                if (showProjectsSection) {
                    item("h-projects") { ProjectsHeader(enabled = canSwitch, onNewProject = onNewProject) }
                    sections.projects.forEach { group ->
                        val collapsed = group.project.id in collapsedProjects
                        item("proj-h-${group.project.id}") {
                            ProjectGroupHeader(
                                project = group.project,
                                count = group.rows.size,
                                collapsed = collapsed,
                                canCreate = canSwitch,
                                onToggle = { onToggleProject(group.project.id) },
                                onNewChat = { onNewInProject(group.project.id) },
                                onActions = { bounds -> onProjectActions(group.project, bounds) },
                            )
                        }
                        if (!collapsed) {
                            if (group.rows.isEmpty()) {
                                item("proj-empty-${group.project.id}") {
                                    Text(
                                        stringResource(R.string.drawer_project_empty),
                                        Modifier.padding(start = 36.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
                                        style = MikaType.Hint, color = c.ink3,
                                    )
                                }
                            } else {
                                items(group.rows, key = { "proj-${group.project.id}-${it.id}" }) {
                                    ConversationEntry(
                                        it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                                        onToggleSelect = onToggleSelect,
                                    )
                                }
                            }
                        }
                    }
                }
                // The note explaining why switching is impossible sits above the first section of saved conversations (pinned or recent)
                val busyNote: LazyListScope.() -> Unit = {
                    if (!canSwitch) {
                        item("busy") {
                            Text(
                                stringResource(R.string.drawer_busy), Modifier.padding(start = 24.dp, end = 24.dp, bottom = 6.dp),
                                style = MikaType.Hint, color = c.ink3,
                            )
                        }
                    }
                }
                if (sections.pinned.isNotEmpty()) {
                    item("h-pinned") { SectionLabel(stringResource(R.string.drawer_pinned)) }
                    busyNote()
                    items(sections.pinned, key = { "p-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                            onToggleSelect = onToggleSelect,
                        )
                    }
                }
                if (sections.recent.isNotEmpty()) {
                    item("h-recent") { SectionLabel(stringResource(R.string.drawer_recent)) }
                    if (sections.pinned.isEmpty()) busyNote()
                    items(sections.recent, key = { "c-${it.id}" }) {
                        ConversationEntry(
                            it, onOpen, onActions, selecting = selecting, selected = it.id in selected,
                            onToggleSelect = onToggleSelect,
                        )
                    }
                }
            }
            if (!searchActive && !selecting && !archivedView && archived.isNotEmpty()) {
                item("archive-entry") { ArchiveEntry(archived.size, onOpenArchive) }
            }
            item("end") { Box(Modifier.height(12.dp)) }
        }
        // Batch delete progress / results linger at the bottom for a few seconds
        deleteBatch?.let { DeleteStatusBar(it) }
        archiveBatch?.let { ArchiveStatusBar(it) }
        if (!selecting) archiveUndo?.let { ArchiveUndoBar(it, onUndoArchive) }
        if (selecting) {
            SelectBar(selectedCount, allSelected, onSelectAll, archivedView, onArchiveSelected, onUnarchiveSelected, onDeleteSelected)
        } else {
            // Settings is pinned at the bottom-left of the drawer; only when a new version exists does "Update" with a red dot appear on the right, tapping it opens "About Claire"
            Row(
                Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 0.5.dp.toPx()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("drawer-footer"),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                NavEntry(MikaIcons.Gear, stringResource(R.string.settings_title), Modifier, onClick = onSettings)
                if (aboutDot) NavEntry(MikaIcons.Refresh, stringResource(R.string.drawer_update), Modifier, dot = true, onClick = onAbout)
            }
        }
    }
}
