package com.wangzan101.mika.chat.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable

import com.wangzan101.mika.R
import com.wangzan101.mika.export.ExportConversationDialog
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import androidx.compose.runtime.mutableStateOf
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.chat.ConversationSearch
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.ui.components.GlassBlurSupported
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.roundToInt
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.browser.ui.findActivity
import com.wangzan101.mika.ui.LocalAdaptiveLayout

/**
 * The chat page and its drawer: the menu at the top left slides the drawer out from the left over a scrim; tapping the
 * scrim, swiping left or pressing back all close it.
 * The conversation list is warmed up and refreshed in the background. After going to another page and coming back,
 * [reopen] restores the drawer immediately. [content] receives "open drawer" to put on the menu button.
 */
@Composable
fun ChatWithDrawer(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    reduceMotion: Boolean,
    conversations: () -> List<ConversationSummary>,
    /** Conversations changed in storage (see `ConversationStore.changes`); while the drawer is open, conversations saved elsewhere are listed right away. */
    conversationChanges: Flow<String> = emptyFlow(),
    /** A title already given to a conversation; even while the current conversation is not saved into the list yet (a task just started) it can still be named. */
    conversationTitle: (String) -> String? = { null },
    /** Index used for drawer search; when absent, no search box is shown in the drawer. */
    search: ConversationSearch? = null,
    onPersonal: () -> Unit = {},
    onPlans: () -> Unit = {},
    onMemory: () -> Unit = {},
    onBrowser: () -> Unit = {},
    onTeach: () -> Unit = {},
    onCalls: () -> Unit = {},
    onScreens: () -> Unit = {},
    onSettings: () -> Unit,
    onAbout: () -> Unit = {},
    /** A new version is available: "About Claire" in the drawer carries a small red dot. */
    updateAvailable: Boolean = false,
    /** A page entered from the drawer has just been exited: open the drawer immediately, back to how it was before entering. */
    reopen: Boolean = false,
    onReopened: () -> Unit = {},
    frames: StateFlow<Bitmap?>? = null,
    operationRun: (String) -> com.wangzan101.mika.task.TaskRun? = { null },
    chatActive: Boolean = true,
    onRegularChat: () -> Unit = {},
    /** Changes when returning to the chat page from outside: if the drawer was left expanded because of the back key, collapse it immediately. */
    resetKey: Int = 0,
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    // The chat page uses a pure white background, and Claire's answers are white bubbles lifted by shadows
    val chatColors = MikaTheme.colors.let { base -> androidx.compose.runtime.remember(base) { base.forChat() } }
    androidx.compose.runtime.CompositionLocalProvider(
        com.wangzan101.mika.ui.theme.LocalMikaColors provides chatColors,
        com.wangzan101.mika.ui.LocalMarkdownLook provides androidx.compose.runtime.remember(chatColors) { chatMarkdownLook(chatColors) },
    ) {
    val adaptive = LocalAdaptiveLayout.current
    val permanent = adaptive.hasNavigation
    val ui by vm.ui.collectAsState()
    val runId = ui.exchanges.lastOrNull()?.runId
    var preferConversations by rememberSaveable(ui.conversationId, runId) { mutableStateOf(false) }
    val screenAvailable = chatActive && permanent && frames != null && ui.pip.visible
    val screenDocked = screenAvailable && !ui.pip.closed && !preferConversations
    // When returning from a drawer page, create it directly in the expanded state, preserving the upstream fix's first-frame behavior.
    val drawer = rememberChatDrawerState(if (reopen && !permanent) DrawerValue.Open else DrawerValue.Closed)
    val backProgress = remember { Animatable(0f) }
    var drawerWidthPx by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reopen, permanent) {
        if (permanent) drawer.snapTo(DrawerValue.Closed)
        if (reopen) {
            preferConversations = true
            onReopened()
        }
    }
    val others by vm.otherRunning.collectAsState()
    fun close(then: () -> Unit = {}) {
        scope.launch {
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
        }
        then()
    }
    /** Go to another page: close the drawer immediately first, so it is closed when you come back. */
    fun leave(go: () -> Unit) {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    // Prepare the list as soon as the finger starts dragging, instead of jumping from blank to history halfway through; only clear search and other state once it is fully closed.
    val open by remember(drawer, permanent) {
        derivedStateOf {
            permanent || drawer.targetValue == DrawerValue.Open || drawer.isOpen ||
                (drawerWidthPx > 0 && drawer.currentOffset.isFinite() && drawer.currentOffset > -drawerWidthPx + 0.5f)
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open, permanent) {
        if (open && !permanent) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    /** The text in the search box and the results obtained so far; cleared when the drawer closes. */
    var query by remember { mutableStateOf("") }
    var searchOutcome by remember { mutableStateOf<SearchOutcome?>(null) }
    /** Archive filter for the search results; returns to "All" when the drawer closes. */
    var searchFilter by remember { mutableStateOf(SearchArchiveFilter.All) }
    /** Multi-select mode: once entered, every row can be checked and the bottom becomes a batch-delete action bar; it is exited when the drawer closes. */
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Archive view: the list switches to the put-away conversations; it is exited when the drawer closes. */
    var archivedView by remember { mutableStateOf(false) }
    /** A batch delete awaiting the user's confirmation (conversation ids). */
    var confirmingMany by remember { mutableStateOf<List<String>?>(null) }
    val deleteBatch by vm.deleteBatch.collectAsState()
    /** Result of a batch archive; stays at the bottom for a few seconds. */
    var archiveBatch by remember { mutableStateOf<ArchiveBatch?>(null) }
    LaunchedEffect(archiveBatch) {
        if (archiveBatch != null) {
            delay(3_000)
            archiveBatch = null
        }
    }
    /** The item just archived; offers "Undo" at the bottom for a few seconds. */
    var archiveUndo by remember { mutableStateOf<ArchiveUndo?>(null) }
    LaunchedEffect(archiveUndo) {
        if (archiveUndo != null) {
            delay(4_000)
            archiveUndo = null
        }
    }
    /** Archives one item and offers an "Undo"; when archiving did not succeed (a task is running), no hint is shown. */
    fun archiveWithUndo(id: String, title: String) {
        if (vm.archiveConversation(id)) archiveUndo = ArchiveUndo(id, title)
    }
    // The delete result stays at the bottom for a few seconds; it is left alone until the next batch starts
    LaunchedEffect(deleteBatch) {
        val batch = deleteBatch ?: return@LaunchedEffect
        if (batch.finished) {
            delay(if (batch.failed == 0) 2_500L else 5_000L)
            vm.clearDeleteBatch()
        }
    }
    LaunchedEffect(open, search) {
        if (!open) {
            query = ""
            searchOutcome = null
            searchFilter = SearchArchiveFilter.All
            selecting = false
            selected = emptySet()
            archivedView = false
            archiveBatch = null
            archiveUndo = null
            // The body-text index is only useful while the drawer is open, so release it once it closes; long use then does not keep every conversation's body text in memory
            search?.clear()
        }
    }
    // Back first exits multi-select/archive/search, then collapses the drawer. When it is closed, back goes back to the
    // system; back is never treated as opening the menu. Panels, picture-in-picture and other back handling inside the chat page are in inner layers and processed before this.
    val activity = LocalContext.current.findActivity()
    val context = LocalContext.current
    val nestedDrawerView = selecting || archivedView || query.isNotEmpty()
    BackHandler(enabled = open && nestedDrawerView) {
        when {
            selecting -> {
                selecting = false
                selected = emptySet()
            }
            archivedView -> archivedView = false
            query.isNotEmpty() -> query = ""
            else -> close()
        }
    }
    // When swiping left from the edge on a chat page revealed from the right, the system may take over back first. Follow
    // the back progress directly instead of only starting another collapse animation once the finger is lifted; when back is cancelled, return smoothly to the expanded position.
    PredictiveBackHandler(enabled = !permanent && open && !nestedDrawerView) { progress ->
        try {
            progress.collect { backProgress.snapTo(it.progress) }
            if (reduceMotion) drawer.snapTo(DrawerValue.Closed) else drawer.close()
            backProgress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            scope.launch {
                if (reduceMotion) backProgress.snapTo(0f)
                else backProgress.animateTo(0f, tween(180, easing = MikaMotion.Decelerate))
            }
            throw cancelled
        }
    }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(conversationChanges) { conversationChanges.collect { revision++ } }
    val deleted by vm.deleted.collectAsState()
    /** The long-pressed item and its position in the window; the menu is open (cleared only after the collapse animation finishes). */
    var acting by remember { mutableStateOf<Pair<DrawerConversation, Rect>?>(null) }
    var actingProject by remember { mutableStateOf<Pair<Project, Rect>?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<Project?>(null) }
    var confirmingProject by remember { mutableStateOf<Pair<Project, ProjectDeleteImpact>?>(null) }
    /** Progress of the menu appearing: 0 means absent, 1 fully out; with animations off it snaps straight into place. */
    val lift = remember { Animatable(0f) }
    LaunchedEffect(adaptive) {
        acting = null
        actingProject = null
        lift.snapTo(0f)
    }
    LaunchedEffect(acting, actingProject) {
        if (acting != null || actingProject != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    /** Collapses the menu: plays the appearance animation backwards, then clears it; [then] runs immediately (the rename and delete dialogs do not wait for the menu to finish collapsing). */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            acting = null
            actingProject = null
        }
    }
    /** The item being renamed. */
    var renaming by remember { mutableStateOf<DrawerConversation?>(null) }
    var exporting by remember { mutableStateOf<DrawerConversation?>(null) }
    /** The item to delete and what deleting it also affects; the confirmation dialog is open. */
    var confirming by remember { mutableStateOf<Pair<DrawerConversation, DeleteImpact>?>(null) }
    var lastReset by remember { mutableIntStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != lastReset) {
            lastReset = resetKey
            acting = null
            actingProject = null
            creatingProject = false
            renamingProject = null
            confirmingProject = null
            renaming = null
            confirming = null
            selecting = false
            selected = emptySet()
            archivedView = false
            confirmingMany = null
            archiveBatch = null
            archiveUndo = null
            query = ""
            searchOutcome = null
            searchFilter = SearchArchiveFilter.All
            drawer.snapTo(DrawerValue.Closed)
        }
    }
    val menuOpen = acting != null || actingProject != null
    val projects by vm.projects.collectAsState()
    val collapsedProjects by vm.collapsedProjects.collectAsState()
    val drawerColors = MikaTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(MikaTheme.colors.bg)) {
        // Match the reference at 85% of the phone width; in narrow windows at least 48dp is left for tapping / swiping left to close.
        val drawerWidth = minOf(DrawerWidth, maxWidth * 0.85f, (maxWidth - 48.dp).coerceAtLeast(0.dp))
        AdaptiveChatNavigation(
            layout = adaptive,
            // While the menu is open the whole screen behind (chat page and drawer) is blurred (from Android 12 on; older versions only dim), and accessibility reads only the menu
            modifier = if (!menuOpen) Modifier else Modifier
                .then(
                    if (GlassBlurSupported) Modifier.graphicsLayer {
                        val r = MenuBlur.toPx() * lift.value
                        renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
                    } else Modifier,
                )
                .clearAndSetSemantics {},
            drawerContent = {
                // Warm up the list on the IO thread ahead of time, then keep the latest snapshot; the first frame after dragging starts no longer walks the conversation files synchronously.
                val readHistory by rememberUpdatedState(conversations)
                val history by produceState(emptyList<ConversationSummary>(), open, revision) {
                    value = withContext(Dispatchers.IO) { readHistory() }
                }
                val drafts by vm.draftConversations.collectAsState()
                val cached by produceState(emptyList<ConversationSummary>(), history, drafts, revision) {
                    // Draft metadata is merged in the background too; while typing a draft only the snapshot is merged, without re-walking the conversation directory for every character.
                    value = withContext(Dispatchers.IO) { vm.withDrafts(history) }
                }
                val all = remember(open, cached) { if (open) cached else emptyList() }
                val readTitle by rememberUpdatedState(conversationTitle)
                val stored by produceState<String?>(null, open, revision, ui.conversationId) {
                    val id = ui.conversationId
                    value = if (open && id != null) withContext(Dispatchers.IO) { readTitle(id) } else null
                }
                val last = ui.exchanges.lastOrNull()
                val running = vm.busy()
                val current = CurrentConversation(
                    ui.conversationId, running,
                    stored ?: ChatCopy.title(ui.exchanges),
                    if (running) ChatCopy.subtitle(last, last?.let(vm::livePlace))?.first.orEmpty() else "",
                )
                val canSwitch = vm.canSwitch()
                // Search candidates: titles converted to what the drawer displays (attachment lines and page notes stripped),
                // matching what the row shows; then filtered by archive state.
                // Whether there is anything searchable looks at everything, not the current filter — when the filter yields nothing the search box and filter row must stay, otherwise there is no way back
                val searchable = remember(all, deleted) { all.any { it.id !in deleted } }
                val candidates = remember(all, deleted, searchFilter) { drawerSearchCandidates(all, deleted, searchFilter) }
                val engine = search
                LaunchedEffect(open, query, revision, candidates, engine) {
                    val q = query.trim()
                    if (engine == null || !open || q.isEmpty()) {
                        searchOutcome = null
                        return@LaunchedEffect
                    }
                    // Under this filter there are no conversations to search: give an empty result directly instead of leaving the UI stuck on "searching"
                    if (candidates.isEmpty()) {
                        searchOutcome = SearchOutcome(q, emptyList(), searching = false)
                        return@LaunchedEffect
                    }
                    // Title matches do not need to wait for the body text, so list them first; once the body scan finishes, replace the whole set
                    searchOutcome = SearchOutcome(q, engine.titleMatches(q, candidates), searching = true)
                    delay(180)
                    val result = withContext(Dispatchers.IO) { engine.search(q, candidates) }
                    searchOutcome = SearchOutcome(q, result.hits, searching = false, truncated = result.truncated)
                }
                val byId = remember(candidates) { candidates.associateBy { it.id } }
                val now = System.currentTimeMillis()
                val runningIds = remember(others, ui.conversationId, running) {
                    (others + listOfNotNull(ui.conversationId?.takeIf { running })).toSet()
                }
                val projectNames = remember(projects) { projects.associate { it.id to it.name } }
                val searchView = searchOutcome?.takeIf { it.query == query.trim() }?.let { outcome ->
                    DrawerSearch(
                        outcome.hits.mapNotNull { hit ->
                            val s = byId[hit.id] ?: return@mapNotNull null
                            val time = if (s.updatedAt != 0L) conversationTime(s.updatedAt, now) else ""
                            drawerEntry(
                                s, ui.conversationId, canSwitch, running = hit.id in runningIds,
                                time = time, subtitle = hit.snippet.orEmpty(), target = hit.target,
                                projectName = s.projectId?.let(projectNames::get),
                            )
                        },
                        outcome.searching,
                        outcome.truncated,
                    )
                }
                // Deleted conversations are not listed right away; there is no waiting for the background delete to finish
                val sections = remember(all, current, others, canSwitch, deleted, projects) {
                    drawerSections(all.filter { it.id !in deleted }, current, others.filter { it !in deleted }, vm::runningGoal, canSwitch, System.currentTimeMillis(), projects = projects)
                }
                // Rows in the archive view: newest archive time first, most recently put away at the top; the subtitle states the owning project and how long ago it was archived
                val archivedRows = remember(all, current, canSwitch, deleted, runningIds, projectNames) {
                    val now = System.currentTimeMillis()
                    all.filter { it.id !in deleted && it.archivedAt != null }
                        .sortedByDescending { it.archivedAt }
                        .map { s ->
                            drawerEntry(
                                s, current.id, canSwitch, running = s.id in runningIds,
                                time = if (s.updatedAt != 0L) conversationTime(s.updatedAt, now) else "",
                                subtitle = s.archivedAt?.let { archivedAgo(it, now) }.orEmpty(),
                                projectName = s.projectId?.let(projectNames::get),
                            )
                        }
                }
                // Only the conversations listed in the drawer right now can be checked; when the list changes or something is deleted elsewhere, selections that no longer exist are dropped immediately. In the archive view this becomes the archived conversations
                val selectableIds = remember(sections, archivedRows, archivedView) {
                    drawerSelectableIds(sections, archivedRows, archivedView)
                }
                LaunchedEffect(selectableIds) { selected = selected intersect selectableIds }
                // Switching between the normal drawer and the archive view does not carry the selection over
                LaunchedEffect(archivedView) {
                    selecting = false
                    selected = emptySet()
                }
                ChatDrawer(
                    check, sections, canSwitch,
                    modifier = (if (permanent) Modifier else Modifier.width(drawerWidth)).onSizeChanged { drawerWidthPx = it.width }.offset {
                        val offset = drawer.currentOffset
                        val reveal = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                        IntOffset(-(reveal * backProgress.value).roundToInt(), 0)
                    },
                    // Share the background with the outer layer so the shadow outside the chat card's top and bottom corners shows completely.
                    containerColor = Color.Transparent,
                    permanent = permanent,
                    onShowScreen = if (screenAvailable) ({ preferConversations = false; vm.expandPip() }) else null,
                    onPersonal = { leave(onPersonal) },
                    onPlans = { leave(onPlans) },
                    onMemory = { leave(onMemory) },
                    onBrowser = { leave(onBrowser) },
                    onTeach = { leave(onTeach) },
                    onCalls = { leave(onCalls) },
                    query = query,
                    onQueryChange = { query = it },
                    search = searchView,
                    canSearch = searchable,
                    searchFilter = searchFilter,
                    onSearchFilterChange = { searchFilter = it },
                    onNew = { close { vm.newConversation(); onRegularChat() } },
                    onSettings = { leave(onSettings) },
                    onScreens = { leave(onScreens) },
                    onAbout = { leave(onAbout) },
                    aboutDot = updateAvailable,
                    onActions = { item, bounds -> if (item.id != null) acting = item to bounds },
                    onOpen = { item ->
                        close {
                            onRegularChat()
                            val id = item.id
                            when {
                                id == null -> Unit
                                item.target != null -> vm.openConversationAt(id, item.target)
                                id == ui.conversationId -> Unit
                                item.running -> vm.switchTo(id)
                                else -> vm.openConversation(id)
                            }
                        }
                    },
                    selecting = selecting,
                    selected = selected,
                    onExitSelect = {
                        selecting = false
                        selected = emptySet()
                    },
                    onToggleSelect = { item ->
                        item.id?.let { id -> selected = if (id in selected) selected - id else selected + id }
                    },
                    onSelectAll = {
                        selected = if (selectableIds.isNotEmpty() && selectableIds.all { it in selected }) emptySet() else selectableIds
                    },
                    onDeleteSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) confirmingMany = ids
                    },
                    onUnarchiveSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) {
                            selecting = false
                            selected = emptySet()
                            vm.unarchiveConversations(ids)
                        }
                    },
                    onArchiveSelected = {
                        val ids = selectableIds.filter { it in selected }
                        if (ids.isNotEmpty()) {
                            selecting = false
                            selected = emptySet()
                            val archived = vm.archiveConversations(ids)
                            archiveBatch = ArchiveBatch(archived, ids.size - archived)
                        }
                    },
                    deleteBatch = deleteBatch,
                    archiveBatch = archiveBatch,
                    archiveUndo = archiveUndo,
                    onUndoArchive = {
                        val undo = archiveUndo
                        archiveUndo = null
                        undo?.let { vm.unarchiveConversation(it.id) }
                    },
                    archived = archivedRows,
                    archivedView = archivedView,
                    onOpenArchive = { archivedView = true },
                    onExitArchive = {
                        archivedView = false
                        selecting = false
                        selected = emptySet()
                    },
                    showProjectsSection = true,
                    collapsedProjects = collapsedProjects,
                    onToggleProject = vm::toggleProjectCollapsed,
                    onNewProject = { creatingProject = true },
                    onNewInProject = { pid -> close { vm.newConversation(pid); onRegularChat() } },
                    onProjectActions = { project, bounds -> actingProject = project to bounds },
                )
            },
            sideContent = if (screenDocked) ({
                val run = ui.conversationId?.let(operationRun)
                val liveFrame by (run?.frames ?: frames!!).collectAsState()
                var lastFrame by remember(ui.conversationId, runId) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(liveFrame) { liveFrame?.let { lastFrame = it } }
                val previewFrame = liveFrame ?: lastFrame.takeIf { ui.pip.mode == PipMode.Done }
                val showConversations = { preferConversations = true }
                val closeScreen = { preferConversations = true; vm.closePip() }
                if (run != null) InteractiveOperationScreen(run, previewFrame, ui.pip.mode, showConversations, closeScreen)
                else OperationScreenPane(previewFrame, ui.pip.mode, showConversations, closeScreen)
            }) else null,
            drawerGesturesEnabled = chatActive,
            compactContent = { navigation, detail ->
                ChatNavigationDrawer(
                    drawerContent = navigation,
                    drawerState = drawer,
                    contentOffset = {
                        val offset = drawer.currentOffset
                        val reveal = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                        reveal * (1f - backProgress.value)
                    },
                    onDismiss = { close() },
                    // On the chat page, swiping right pulls out the drawer (tracking the finger); content that scrolls
                    // sideways (wide tables, code blocks, etc.) scrolls itself first. The screen edge is reserved for the
                    // system's back gesture; back collapses the drawer while it is open and goes back to the system while it is closed.
                    gesturesEnabled = !menuOpen,
                ) { detail() }
            },
        ) {
            // Use the drawer's same live offset so the chat page keeps its width and translates with the drawer as the finger moves; it cannot only animate after the finger is lifted.
            // offset is mirrored automatically for RTL, matching the Material drawer's starting edge.
            Box(
                (if (permanent) Modifier else Modifier.offset {
                    val offset = drawer.currentOffset
                    val push = if (offset.isFinite()) (drawerWidthPx + offset).coerceIn(0f, drawerWidthPx.toFloat()) else 0f
                    IntOffset((push * (1f - backProgress.value)).roundToInt(), 0)
                }.fillMaxSize().graphicsLayer {
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    // The chat page recedes into a lifted card like current Kimi: it only shortens slightly vertically, so the text is not squeezed narrower horizontally.
                    scaleY = 1f - 0.04f * progress
                    shape = DrawerCardShape(progress)
                    clip = progress > 0f
                    shadowElevation = 16.dp.toPx() * progress
                    // The reference has a soft shadow all around, so tone down Android's default heavy downward shadow.
                    ambientShadowColor = drawerColors.floatShadow
                    spotShadowColor = drawerColors.floatShadow.copy(alpha = 0.18f)
                }.background(MikaTheme.colors.bg).drawWithContent {
                    drawContent()
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    // Shadows are barely visible on dark themes, so a thin stroke in the theme color preserves the card's boundary.
                    if (drawerColors.isDark && progress > 0f) drawRoundRect(
                        color = drawerColors.edge.copy(alpha = drawerColors.edge.alpha * progress),
                        cornerRadius = CornerRadius(32.dp.toPx() * progress, 32.dp.toPx() * progress / (1f - 0.04f * progress)),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                    .then(if (open) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("drawer-chat")).fillMaxSize(),
            ) {
                CompositionLocalProvider(LocalOperationScreenDocked provides screenDocked) {
                    content {
                        preferConversations = true
                        if (!permanent) scope.launch { if (reduceMotion) drawer.snapTo(DrawerValue.Open) else drawer.open() }
                    }
                }
                // Fade the chat content as the finger moves, keeping visual focus on the drawer; the offset is read in the draw phase so the whole page does not recompose every frame.
                if (!permanent) Box(Modifier.fillMaxSize().drawBehind {
                    val offset = drawer.currentOffset
                    val progress = if (drawerWidthPx > 0 && offset.isFinite())
                        ((drawerWidthPx + offset) / drawerWidthPx).coerceIn(0f, 1f) * (1f - backProgress.value) else 0f
                    drawRect(color = drawerColors.bg, alpha = 0.45f * progress)
                })
            }
        }
        acting?.let { (item, bounds) ->
            val id = item.id ?: return@let
            ConversationMenu(
                item, bounds, progress = { lift.value },
                onPin = { closeMenu { vm.setPinned(id, !item.pinned) } },
                onArchive = { closeMenu { archiveWithUndo(id, item.title) } },
                onUnarchive = { closeMenu { vm.unarchiveConversation(id) } },
                onRename = { closeMenu { renaming = item } },
                onTerminal = { closeMenu { activity?.let { a -> com.wangzan101.mika.ui.terminal.TerminalLauncher.open(a, id, item.title) } } },
                onExport = { closeMenu { exporting = item } },
                onDelete = { closeMenu { confirming = item to vm.deleteImpact(id) } },
                onSelect = { closeMenu { archiveUndo = null; selecting = true; selected = setOf(id) } },
                onDismiss = { closeMenu() },
            )
        }
        actingProject?.let { (project, bounds) ->
            ProjectMenu(
                project, bounds, progress = { lift.value },
                onNewChat = { closeMenu { close { vm.newConversation(project.id); onRegularChat() } } },
                onRename = { closeMenu { renamingProject = project } },
                onTerminal = {
                    closeMenu {
                        activity?.let { a ->
                            com.wangzan101.mika.ui.terminal.TerminalLauncher.openWorkspace(a, WorkspaceKey.Project(project.id), project.name)
                        }
                    }
                },
                onDelete = { closeMenu { confirmingProject = project to vm.projectDeleteImpact(project.id) } },
                onDismiss = { closeMenu() },
            )
        }
    }
    if (creatingProject) {
        ProjectNameDialog(
            title = stringResource(R.string.drawer_project_new_title),
            initial = "",
            confirmTag = "confirm-create-project",
            onSave = { name ->
                creatingProject = false
                vm.createProject(name)?.getOrNull()?.let { p ->
                    close { vm.newConversation(p.id); onRegularChat() }
                }
            },
            onDismiss = { creatingProject = false },
        )
    }
    renamingProject?.let { project ->
        ProjectNameDialog(
            title = stringResource(R.string.drawer_project_rename_title),
            initial = project.name,
            confirmTag = "confirm-rename-project",
            onSave = { name ->
                renamingProject = null
                vm.renameProject(project.id, name)
            },
            onDismiss = { renamingProject = null },
        )
    }
    confirmingProject?.let { (project, impact) ->
        DeleteProjectDialog(
            project = project,
            impact = impact,
            onConfirm = {
                confirmingProject = null
                vm.deleteProject(project.id)
            },
            onDismiss = { confirmingProject = null },
        )
    }
    renaming?.let { item ->
        RenameConversationDialog(
            item.title,
            onSave = { t ->
                renaming = null
                item.id?.let { vm.renameConversation(it, t) }
            },
            onDismiss = { renaming = null },
        )
    }
    confirming?.let { (item, impact) ->
        DeleteConversationDialog(
            item.title, impact,
            onConfirm = {
                confirming = null
                item.id?.let(vm::deleteConversation)
            },
            onDismiss = { confirming = null },
        )
    }
    confirmingMany?.let { ids ->
        // The dialog must state how many tasks will be stopped and how many schedules are kept; computing it once when opened is enough
        val impacts = remember(ids) { ids.map(vm::deleteImpact) }
        DeleteManyConversationsDialog(
            count = ids.size,
            runningTasks = impacts.count { it.runningTask },
            schedules = impacts.sumOf { it.schedules },
            onConfirm = {
                confirmingMany = null
                selecting = false
                selected = emptySet()
                vm.deleteConversations(ids)
            },
            onDismiss = { confirmingMany = null },
        )
    }
    exporting?.let { item ->
        item.id?.let { id -> ExportConversationDialog(id, item.title, onDismiss = { exporting = null }) }
    }
}
}
