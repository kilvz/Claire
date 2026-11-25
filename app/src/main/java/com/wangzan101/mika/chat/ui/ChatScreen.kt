package com.wangzan101.mika.chat.ui

import androidx.compose.foundation.gestures.scrollBy
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.ui.components.DirectionalIcon
import com.wangzan101.mika.ui.components.GlassSurface
import com.wangzan101.mika.voicecall.ui.CallRecordsSheet
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import com.wangzan101.mika.chat.ChecklistSource
import com.wangzan101.mika.chat.SavedChecklistStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.TaskTemplate
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ScrollToBottomButton
import com.wangzan101.mika.ui.components.glassSource
import com.wangzan101.mika.ui.components.rememberGlassBackdrop
import com.wangzan101.mika.ui.components.scrollListToBottom
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaMotion
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.ui.theme.WordmarkWeight
import com.wangzan101.mika.voice.HoldToTalk
import com.wangzan101.mika.voice.VoiceSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.wangzan101.mika.files.ui.DeviceFileBrowser
import com.wangzan101.mika.settings.ui.PhoneSetupLine
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.task.ui.RecommendedTaskDialog
import com.wangzan101.mika.task.ui.SavedChecklistsScreen
import com.wangzan101.mika.task.ui.TaskFillDialog
import com.wangzan101.mika.ui.LocalAdaptiveLayout
import com.wangzan101.mika.voice.ui.voiceDim
import com.wangzan101.mika.workflow.ui.WorkflowsScreen

/**
 * The chat page: top bar (drawer on the left, conversation title and what it is doing right now in the middle, new
 * conversation on the right), with the conversation scrolling between the top bar and the glass input bar.
 * While running it is one status row plus a vertical-rail step list, without a card; when it needs you it stops at the
 * current step with a white card below; once finished the process folds into a row and the answer sits directly on the background.
 * The agent's virtual screen covers the conversation as a floating small window ([AgentPip]) without taking layout space.
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    check: Prerequisites.Check,
    frames: StateFlow<Bitmap?>,
    webOpen: StateFlow<Boolean>,
    /** Entry point for operating directly in the small window (taps/swipes/back injected into the virtual screen); null while the screen is not set up or the task has ended. */
    pipInput: StateFlow<com.wangzan101.mika.cap.phone.display.PipInput?> = MutableStateFlow(null),
    /** Conversations whose task currently has a virtual screen (or an open web page): the small window follows it when a dispatched job has a picture. */
    screens: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** Whether the user has restored the sound of the small window's target app: the sound button then reads "Mute", otherwise "Restore sound". */
    pipSoundOn: StateFlow<Boolean> = MutableStateFlow(false),
    /** The user enabled "allow me to operate directly in the small window" in settings. */
    pipOperateEnabled: Boolean = false,
    reduceMotion: Boolean,
    /** Menu at the top left: opens the drawer. */
    onOpenDrawer: () -> Unit,
    /** The "go to settings" in the blocked hint. */
    openSettings: () -> Unit,
    /** "Browser" in the "+" panel: opens the built-in browser. */
    onOpenBrowser: () -> Unit = {},
    openRun: (String) -> Unit = {},
    /** A task needs the phone but phone operation is not ready: put a "go to settings" row under that question-answer (attached at the end when it cannot be matched to a specific turn). */
    phoneSetupNeed: com.wangzan101.mika.cap.phone.setup.PhoneSetupPrompt.Need? = null,
    openPhoneSetup: () -> Unit = {},
    /** Holding to talk in the input bar opens a session from here; when null only typing is possible. */
    voice: VoiceSource? = null,
    /** The "choose" in "voice input is not available yet". */
    openSpeechModel: () -> Unit = {},
    /** The unsent text in the input field, handed to "teach Claire" as a prefill; it does not start on its own. */
    onOpenTeach: (String) -> Unit = {},
    /** Recommended tasks for the home screen. When not passed, the locally saved set is used; tests pass a separate store so the user's data is not modified. */
    recommendations: RecommendedTaskStore? = null,
    checklists: SavedChecklistStore? = null,
    workflows: com.wangzan101.mika.workflow.WorkflowRuntime? = null,
    /** "Manage personas" in the persona picker: opens the personas page in settings. */
    onOpenPersonas: () -> Unit = {},
    /** The earpiece button in the input bar: enters a voice call; hidden when null (normally always present; when unavailable it leads to a gating page instead). */
    onVoiceCall: (() -> Unit)? = null,
    /** "View details" for a job dispatched by the front desk: opens the process of this run, or its conversation when the run cannot be found (the phase decides which run counts). */
    openDeskRun: (taskId: String, phase: com.wangzan101.mika.core.frontdesk.DeskTaskPhase) -> Unit = { _, _ -> },
    /** "Call settings" in the ⋯ menu at the top right. */
    openCallSettings: () -> Unit = {},
) {
    val checklistStore = checklists ?: (LocalContext.current.applicationContext as? MikaApp)?.savedChecklists
    val workflowRuntime = workflows ?: (LocalContext.current.applicationContext as? MikaApp)?.workflows
    val recommendationStore = recommendations
        ?: (LocalContext.current.applicationContext as? MikaApp)?.recommendedTasks
    val appContext = LocalContext.current.applicationContext
    val recommendedTasksFlow = remember(recommendationStore) {
        recommendationStore?.also {
            it.ensureLoaded(seed = { ChatCopy.recommendationSeeds() }, retired = { ChatCopy.retiredRecommendationSeeds(appContext) })
        }?.tasks
            ?: MutableStateFlow<List<RecommendedTask>>(emptyList())
    }
    val recommendedTasks by recommendedTasksFlow.collectAsState()
    val ui by vm.ui.collectAsState()
    val projects by vm.projects.collectAsState()
    val currentProject = remember(projects, ui.projectId) { ui.projectId?.let { pid -> projects.firstOrNull { it.id == pid } } }
    var deviceBrowserConversation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.conversationId, deviceBrowserConversation) {
        if (deviceBrowserConversation != null && deviceBrowserConversation != vm.ui.value.conversationId) deviceBrowserConversation = null
    }
    deviceBrowserConversation?.takeIf { it == ui.conversationId }?.let { id ->
        DeviceFileBrowser(vm, id) { deviceBrowserConversation = null }
    }
    val pendingJump by vm.jump.collectAsState()
    val callLog by remember(ui.conversationId) { vm.calls(ui.conversationId) }.collectAsState(emptyList())
    val calls = remember(ui.exchanges, callLog) { callMarks(ui.exchanges, callLog) }
    val others by vm.otherRunning.collectAsState()
    val keptAny by vm.keptPage.collectAsState()
    // Only consider the page kept by this conversation
    val kept = keptAny?.takeIf { it.conversationId == ui.conversationId }
    val live by frames.collectAsState()
    val webLive by webOpen.collectAsState()
    val pipInputLive by pipInput.collectAsState()
    // The virtual screen is released as soon as the task ends; on completion the small window still lingers a few seconds to show a check, so keep showing the last frame
    var lastFrame by remember { mutableStateOf<Bitmap?>(null) }
    var lastFrameWasWeb by remember { mutableStateOf(false) }
    LaunchedEffect(live, webLive, ui.pip.mode) {
        live?.let {
            lastFrame = it
            if (webLive) lastFrameWasWeb = true
            else if (ui.pip.mode != PipMode.Done) lastFrameWasWeb = false
        }
    }
    val frame = live ?: lastFrame.takeIf { ui.pip.visible && ui.pip.mode == PipMode.Done }
    val webContent = if (ui.pip.mode == PipMode.Done) lastFrameWasWeb else webLive
    // When a conversation with a front desk attached has no task of its own running, the small window follows what the front desk dispatched: among the jobs that are running or need you and have a picture, needing you wins, then the most recently updated;
    // have the service show it and the picture follows. Expanded and closed are remembered per page, separately per job; when more than one is running, the small window shows which one.
    val screenTasks by screens.collectAsState()
    val deskPip = if (ui.pip.visible) null else ui.deskTasks
        .filter { (it.state == DeskTaskPhase.Running || it.state == DeskTaskPhase.NeedsYou) && it.taskId in screenTasks }
        .sortedWith(compareBy<DeskTaskView> { if (it.state == DeskTaskPhase.NeedsYou) 0 else 1 }.thenByDescending { it.updatedAt })
        .firstOrNull()
    LaunchedEffect(deskPip?.taskId) { deskPip?.let { vm.showDeskScreen(it.taskId) } }
    var deskPipExpanded by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var deskPipClosed by remember(ui.conversationId) { mutableStateOf(emptySet<String>()) }
    val pip = deskPip?.let {
        PipUi(
            visible = true, mode = if (it.state == DeskTaskPhase.NeedsYou) PipMode.NeedsYou else PipMode.Running,
            expanded = deskPipExpanded == it.taskId && it.taskId !in deskPipClosed, closed = it.taskId in deskPipClosed,
        )
    } ?: ui.pip
    val pipLabel = deskPip?.title?.takeIf { trayOrder(ui.deskTasks).size > 1 }
    // Register Android state saving for the text; normally the in-process ViewModel manages the full conversation draft.
    rememberSaveable(vm, saver = androidx.compose.runtime.saveable.listSaver(
        save = { model: ChatViewModel -> model.saveDraftTexts() },
        restore = { saved -> vm.also { it.restoreDraftTexts(saved) } },
    )) { vm }
    val input = ui.input
    // A recommended task can be filled straight into an empty input field; when there is already content, confirm first so a stray tap cannot replace the draft.
    var pendingExample by rememberSaveable(ui.conversationId) { mutableStateOf<String?>(null) }
    var showUsage by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showRecords by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showTasks by rememberSaveable(ui.conversationId) { mutableStateOf(false) }
    var showChecklists by remember(ui.conversationId) { mutableStateOf(false) }
    var checklistIncoming by remember(ui.conversationId) { mutableStateOf<ChecklistSource?>(null) }
    var pendingChecklistDraft by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var showWorkflows by rememberSaveable { mutableStateOf(false) }
    // While the panel is open, picking a photo promotes the home-screen draft into a conversation (the id goes from null to a new value), so this is not keyed on the conversation id; switching conversations closes it first anyway
    var showPlus by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var saveTaskPrompt by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    var fillTaskPrompt by rememberSaveable(ui.conversationId) { mutableStateOf<String?>(null) }
    var fillTaskTitle by rememberSaveable(ui.conversationId) { mutableStateOf("") }
    val fillDraft: (String) -> Unit = { prompt ->
        if (vm.ui.value.input.isEmpty()) vm.input(prompt) else pendingExample = prompt
    }
    val pickTask: (RecommendedTask) -> Unit = { task ->
        if (task.fillIn && TaskTemplate.fields(task.prompt).isNotEmpty()) {
            fillTaskTitle = task.title
            fillTaskPrompt = task.prompt
        } else fillDraft(task.prompt)
    }
    // 推广卡点了直接发;输入框里已经有草稿、附件或引用,或者此刻发不出去(还没配好模型、正在学习……)时,退回填进输入框
    val sendTask: (RecommendedTask) -> Unit = { task ->
        val now = vm.ui.value
        val clean = now.input.isEmpty() && now.attachments.isEmpty() && now.quote == null
        if (!clean || !vm.send(task.prompt, check)) pickTask(task)
    }
    var retryFailures by remember { mutableStateOf<Pair<String, String>?>(null) }
    var voiceHolding by remember { mutableStateOf(false) }
    var editRequest by remember { mutableIntStateOf(0) }
    var composerHeight by rememberSaveable { mutableIntStateOf(0) }
    var topHeight by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // When jumping in from search or the file library, briefly highlight the target row; a new jump carries a new serial, which restarts the timer.
    var jumpHighlight by remember { mutableStateOf<ChatJump?>(null) }
    LaunchedEffect(jumpHighlight) {
        val active = jumpHighlight ?: return@LaunchedEffect
        delay(JUMP_HIGHLIGHT_MS)
        if (jumpHighlight == active) jumpHighlight = null
    }

    var cardFlash by remember { mutableStateOf<CardFlash?>(null) }
    LaunchedEffect(cardFlash) {
        val active = cardFlash ?: return@LaunchedEffect
        delay(JUMP_HIGHLIGHT_MS)
        if (cardFlash == active) cardFlash = null
    }

    // Keep following only if we were already following the latest message. This state is saved across page switches together with the list position.
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var scrollingToBottom by remember(ui.conversationId) { mutableStateOf(false) }
    var scrollToBottomJob by remember(ui.conversationId) { mutableStateOf<Job?>(null) }
    var previousScrollMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var previousConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val tailThresholdPx = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(listState, tailThresholdPx) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.nearTail(tailThresholdPx) }.collect { (scrolling, nearTail) ->
            if (!scrollingToBottom && (scrolling || wasScrolling)) followingTail = nearTail
            wasScrolling = scrolling
        }
    }

    // Returning to the chat does not scroll again for the same content; new progress scrolls only while following the end.
    // The target row is computed directly from the current UI state: layoutInfo still holds the previous frame's measurement results, so reading it right after sending would scroll to the old end.
    val last = ui.exchanges.lastOrNull()
    val setupRow = phoneSetupRow(phoneSetupNeed, ui.conversationId, ui.exchanges)
    // Jobs dispatched by the front desk that are waiting on you right now: one question card at the end of the conversation each
    val needs = ui.deskTasks.filter { it.need != null }.map { it.taskId }
    val rowCount = chatItemCount(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs)
    val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }
    val showScrollToBottom = ui.exchanges.isNotEmpty() && !followingTail && canScrollForward
    val scrollMarker = "${ui.conversationId}:${ui.exchanges.size}:${last?.steps?.size}:${last?.steers?.size}:${last?.status?.hashCode()}:${ui.blocked?.hashCode()}" +
        ":${callLog.size}:${callLog.lastOrNull()?.endMs}"
    val jump = pendingJump?.takeIf { it.conversationId == ui.conversationId }
    // Highlight of the target row: only counted while the target is still the current conversation.
    val highlightKey = cardFlash?.takeIf { it.conversationId == ui.conversationId }?.key ?: jumpHighlight
        ?.takeIf { it.conversationId == ui.conversationId }
        ?.let { chatTargetKey(ui.exchanges, it.target) }
    val openDeskTask: (String) -> Unit = { id ->
        val view = ui.deskTasks.firstOrNull { it.taskId == id }
        openDeskRun(id, view?.state ?: DeskTaskPhase.Done)
    }
    // Scroll to the question card of a dispatched task and flash it briefly; returns false when it is not waiting on you right now (no question card)
    val jumpToNeed: (String) -> Boolean = { taskId ->
        val key = needCardKey(taskId)
        val index = chatItemKeys(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs).indexOf(key)
        if (index < 0) false
        else {
            scrollToBottomJob?.cancel()
            followingTail = false
            scope.launch { if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
            cardFlash = CardFlash(ui.conversationId, key, System.nanoTime())
            true
        }
    }
    LaunchedEffect(scrollMarker, jump) {
        // Coming from search or the file library: scroll to the target message first and do not follow the end meanwhile; if the target is gone, fall back to the original logic.
        if (jump != null) {
            val keys = chatItemKeys(ui.exchanges, kept, setupRow, hasBlocked = ui.blocked != null, calls, needs)
            val index = chatTargetKey(ui.exchanges, jump.target)?.let(keys::indexOf) ?: -1
            if (index >= 0) {
                previousScrollMarker = scrollMarker
                previousConversation = ui.conversationId
                scrollToBottomJob?.cancel()
                followingTail = false
                listState.scrollToItem(index)
                jumpHighlight = jump
                vm.consumeJump(jump)
                return@LaunchedEffect
            }
            vm.consumeJump(jump)
        }
        if (previousScrollMarker == scrollMarker) return@LaunchedEffect
        val firstEntry = previousScrollMarker == null
        val changedConversation = previousConversation != ui.conversationId
        previousScrollMarker = scrollMarker
        previousConversation = ui.conversationId
        if (changedConversation) {
            scrollToBottomJob?.cancel()
            followingTail = true
        }
        if (!firstEntry && !changedConversation && !followingTail) return@LaunchedEffect
        if (rowCount > 0) {
            if (firstEntry || changedConversation || reduceMotion) listState.scrollToItem(rowCount - 1)
            else listState.animateScrollToItem(rowCount - 1)
        }
    }
    // Stick to the bottom while streamed text grows; do not pull the user back when they have deliberately scrolled up to review the process.
    LaunchedEffect(last?.draft?.length) {
        if (last?.draft.isNullOrEmpty() || !followingTail || listState.isScrollInProgress) return@LaunchedEffect
        val layout = listState.layoutInfo
        val end = layout.totalItemsCount - 1
        val draft = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return@LaunchedEffect
        val tailGap = draft.offset + draft.size - layout.viewportEndOffset
        if (tailGap > with(density) { 240.dp.roundToPx() }) return@LaunchedEffect
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val offset = (draft.size - viewport + with(density) { 12.dp.roundToPx() }).coerceAtLeast(0)
        listState.scrollToItem(end, offset)
    }

    // The input bar grew taller (usually the IME appearing, possibly attachments or multi-line input): when it was following
    // the latest message, the conversation is pushed up by the same distance so the last message still touches the input
    // bar; nothing moves while the user is scrolling up to review. When it shrinks, the list settles back at the bottom on its own.
    var lastComposerHeight by remember { mutableIntStateOf(composerHeight) }
    LaunchedEffect(composerHeight) {
        val grown = composerHeight - lastComposerHeight
        lastComposerHeight = composerHeight
        if (grown > 0 && followingTail && !listState.isScrollInProgress) listState.scrollBy(grown.toFloat())
    }

    val context = LocalContext.current

    val c = MikaTheme.colors
    val backdrop = rememberGlassBackdrop()
    /** The long-pressed message; the menu is open (cleared only after the collapse animation finishes). */
    var menu by remember { mutableStateOf<MessageTarget?>(null) }
    var queueActions by remember(ui.conversationId) { mutableStateOf<com.wangzan101.mika.task.QueuedFollowUp?>(null) }
    /** The message whose "select text" was chosen: it switches to the system's long-press selection; cleared when switching conversations. */
    var selecting by remember(ui.conversationId) { mutableStateOf<String?>(null) }
    /** Progress of the menu appearing: 0 means absent, 1 fully out; with animations off it snaps straight into place. */
    val lift = remember { Animatable(0f) }
    val adaptive = LocalAdaptiveLayout.current
    val screenDocked = LocalOperationScreenDocked.current
    LaunchedEffect(adaptive) {
        menu = null
        lift.snapTo(0f)
    }
    LaunchedEffect(menu) {
        if (menu != null) {
            if (reduceMotion) lift.snapTo(1f) else lift.animateTo(1f, tween(MENU_IN_MS, easing = MikaMotion.Decelerate))
        }
    }
    LaunchedEffect(ui.pendingSharedDraft, ui.sharedDraft) {
        if (ui.pendingSharedDraft != null || ui.sharedDraft != null) {
            pendingExample = null
            fillTaskPrompt = null
            pendingChecklistDraft = null
            showChecklists = false
            showWorkflows = false
            if (menu != null) {
                lift.snapTo(0f)
                menu = null
            }
        }
    }
    /** Collapses the menu: plays the appearance animation backwards, then clears it; [then] runs immediately. */
    fun closeMenu(then: () -> Unit = {}) {
        then()
        scope.launch {
            if (reduceMotion) lift.snapTo(0f) else lift.animateTo(0f, tween(MENU_OUT_MS, easing = MikaMotion.Decelerate))
            menu = null
        }
    }
    val menuHost = remember(selecting) { MessageMenuHost(selecting) { menu = it } }
    val menuOpen = menu != null
    /** Empty conversation: only the greeting and recommendation cards, without the message list's side padding, so the cards align with the input bar themselves. */
    val emptyConversation = ui.exchanges.isEmpty() && ui.blocked == null && calls.isEmpty()
    PageBackground {
        CompositionLocalProvider(LocalMessageMenu provides menuHost) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("chat-list").behindMenu(menuOpen) { lift.value }.voiceDim(voiceHolding, reduceMotion).glassSource(backdrop),
                state = listState,
                contentPadding = with(density) {
                    PaddingValues(
                        start = if (emptyConversation) 0.dp else 16.dp, end = if (emptyConversation) 0.dp else 16.dp,
                        top = topHeight.toDp() + 4.dp, bottom = composerHeight.toDp() + 12.dp,
                    )
                },
            ) {
                if (emptyConversation) {
                    item("empty") {
                        EmptyState(ui.conversationId, Modifier.fillParentMaxHeight(), reduceMotion = reduceMotion, onPick = pickTask, onSend = sendTask)
                    }
                }
                ui.exchanges.forEachIndexed { i, ex ->
                    callMarkRows(calls.before(i), highlightKey, reduceMotion)
                    timeMarkAt(ui.exchanges, i, calls)?.let { at -> item(timeMarkKey(i)) { TimeMarkLine(at, timeMarkKey(i)) } }
                    val backgroundNote = i == ui.exchanges.lastIndex && ui.operatingPhone && !screenDocked
                    exchange(
                        i, ex, ui.conversationId, ui.takeoverStage, vm, reduceMotion, backgroundNote, openRun, kept,
                        highlightKey = highlightKey,
                        joinedAbove = replyJoinsAbove(ui.exchanges, i, calls),
                        joinedBelow = replyJoinsAbove(ui.exchanges, i + 1, calls),
                        recover = { resume ->
                            val label = if (resume) R.string.chat_resume_request else R.string.chat_restart_request
                            vm.send(context.getString(label, ex.goal), check)
                        },
                    )
                    // "Go to settings" follows the turn where the phone was found not ready; when it cannot be matched to a turn it stays at the end as before (below)
                    if (setupRow == i) item("ps$i") { PhoneSetupLine(openPhoneSetup) }
                }
                callMarkRows(calls.tail, highlightKey, reduceMotion)
                deskNeedRows(ui.deskTasks, highlightKey, reduceMotion, vm::answerDeskTask, vm::cancelDeskTask, vm)
                if (setupRow == -1) item("phone-setup") { PhoneSetupLine(openPhoneSetup) }
                ui.blocked?.let { problems ->
                    item("blocked") {
                        NeedCard(
                            stringResource(R.string.chat_cannot_start),
                            stringResource(R.string.chat_missing, problems.joinToString(stringResource(R.string.list_sep)) { StatusCopy.banner(it).title }),
                        ) {
                            CardButton(stringResource(R.string.ok_got_it), onClick = vm::dismissBlocked)
                            CardButton(stringResource(R.string.chat_go_settings), primary = true, onClick = { vm.dismissBlocked(); openSettings() })
                        }
                    }
                }
            }
        }

        // The status bar and area behind the top bar fade from the page background to transparent: content scrolling up does not overlap the clock or battery
        val topFade = MikaTheme.colors.bg.let { bg -> remember(bg) { Brush.verticalGradient(0f to bg, 0.4f to bg, 1f to bg.copy(alpha = 0f)) } }
        Column(Modifier.behindMenu(menuOpen) { lift.value }.background(topFade).onSizeChanged { topHeight = it.height }) {
            // Three floating glass blocks: left group (menu, new chat) / title / right group (call, more), not a full-width bar
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                // The title is centered across the whole row instead of being squeezed by both sides
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    // One glass block on each side, shared by the icons in the group: menu + new chat on the left, call + more on the right
                    GlassSurface(backdrop = backdrop, modifier = Modifier.align(Alignment.CenterStart), shape = TopBarShape) {
                        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Another conversation still has a task running (in parallel): a small dot at the top right of the menu, and the sidebar's "In progress" lets you switch to it
                            if (!adaptive.hasNavigation || screenDocked) {
                                TopBarIcon(MikaIcons.Menu, stringResource(R.string.chat_menu), "chat-menu", badge = others.isNotEmpty(), onClick = onOpenDrawer)
                            }
                            TopBarIcon(MikaIcons.New, stringResource(R.string.chat_new), "chat-new", enabled = vm.canSwitch()) { vm.newConversation() }
                        }
                    }
                    GlassSurface(backdrop = backdrop, modifier = Modifier.align(Alignment.Center), shape = TopBarShape) {
                        Row(Modifier.clip(TopBarShape).clickable(role = Role.Button, onClickLabel = stringResource(R.string.chat_usage_title)) { showUsage = true }
                            .testTag("chat-usage-title").height(MikaSize.IconButton + 4.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ChatCopy.APP_TITLE, style = MikaType.Title.copy(fontWeight = WordmarkWeight), color = c.ink, maxLines = 1)
                        }
                    }
                    GlassSurface(backdrop = backdrop, modifier = Modifier.align(Alignment.CenterEnd), shape = TopBarShape) {
                        Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Voice calling concerns the whole conversation, so its entry is in the top bar rather than squeezed into the input bar
                            if (onVoiceCall != null) {
                                TopBarIcon(MikaIcons.Handset, stringResource(R.string.voicecall_menu), "chat-call", onClick = onVoiceCall)
                            }
                            ChatMoreMenu(
                                backdrop, canSwitch = vm.canSwitch(),
                                onNew = { vm.newConversation() },
                                onRecords = { showRecords = true },
                                onTasks = { showTasks = true },
                                onCallSettings = openCallSettings,
                                bare = true,
                            )
                        }
                    }
                }
                if (currentProject != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        GlassSurface(backdrop = backdrop, modifier = Modifier.testTag("chat-project-pill")) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(MikaIcons.Folder, stringResource(R.string.chat_project_shared), Modifier.size(12.dp), tint = c.ink2)
                                Text(currentProject.name, style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        Composer(
            Modifier.align(Alignment.BottomCenter).behindMenu(menuOpen) { lift.value }.onSizeChanged { composerHeight = it.height },
            backdrop = backdrop,
            text = input,
            draftKey = ui.conversationId,
            onText = vm::input,
            mode = ui.composer,
            hasHistory = ui.exchanges.isNotEmpty(),
            onSend = { vm.send(input, check) },
            onStop = vm::stopAll,
            working = ui.deskTasks.isNotEmpty() || ui.exchanges.any { it.deskReply != null && it.status == ExchangeStatus.Working },
            queued = ui.queued,
            onQueueActions = { queueActions = it },
            onCancelQueued = { id ->
                if (!vm.cancelQueued(id)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
            },
            onEditQueued = { id -> vm.editQueued(id)?.let { editRequest++ } },
            onStartQueued = vm::startQueued,
            onPlus = if (ui.attachEnabled) ({
                focusManager.clearFocus()
                keyboardController?.hide()
                showPlus = true
            }) else null,
            // With a front desk attached, attachments can be added at any time; without one, attachments follow a task that starts directly, and cannot be added while this conversation is running its own task (see [ChatSession.canAttach])
            attachBlocked = ui.attachEnabled && !ui.importing && vm.deskFor(ui.conversationId) == null && ui.composer != ComposerMode.Idle,
            attachments = ui.attachments,
            importing = ui.importing,
            rejected = ui.rejectedAttachments,
            overLimit = ui.overLimit,
            reduceMotion = reduceMotion,
            onRemove = vm::removeAttachment,
            onDismissRejected = vm::dismissRejected,
            quote = ui.quote,
            onRemoveQuote = vm::clearQuote,
            voice = voice,
            // Spoken words are appended after the existing text; while a task is running they are first queued as a follow-up
            onVoiceText = { vm.input(HoldToTalk.join(vm.ui.value.input, it)) },
            // Releasing on the "listening" card: append to the existing text and send straight away; if it cannot be sent (e.g. attachments are still importing), it stays in the input field
            onVoiceSend = {
                val text = HoldToTalk.join(vm.ui.value.input, it)
                vm.input(text)
                vm.send(text, check)
            },
            onPickSpeech = openSpeechModel,
            steerTarget = null,
            onVoiceHolding = { voiceHolding = it },
            editRequest = editRequest,
            deskTasks = ui.deskTasks,
            onStopTask = vm::cancelDeskTask,
            onTaskDetail = openDeskTask,
        )

        val pipOccupiesCorner = !screenDocked && pip.visible && pip.mode != PipMode.Done
        val composerBottomDp = with(density) { composerHeight.toDp() }
        ScrollToBottomButton(
            visible = showScrollToBottom,
            onClick = {
                if (rowCount <= 0) return@ScrollToBottomButton
                scrollToBottomJob?.cancel()
                followingTail = true
                scrollToBottomJob = scope.launch {
                    scrollingToBottom = true
                    try {
                        scrollListToBottom(listState, density, reduceMotion)
                        followingTail = listState.nearTail(tailThresholdPx)
                    } finally {
                        scrollingToBottom = false
                    }
                }
            },
            modifier = Modifier
                .align(if (pipOccupiesCorner) Alignment.BottomCenter else Alignment.BottomEnd)
                .behindMenu(menuOpen) { lift.value }
                .voiceDim(voiceHolding, reduceMotion)
                .padding(
                    end = if (pipOccupiesCorner) 0.dp else 16.dp,
                    bottom = composerBottomDp + 8.dp,
                ),
            backdrop = backdrop,
            reduceMotion = reduceMotion,
        )

        if (showPlus) {
            ChatPlusSheet(
                selected = ui.attachments,
                onDismiss = { showPlus = false },
                onPicked = { uris ->
                    vm.attach(uris, ui.conversationId)
                    showPlus = false
                },
                onToggle = vm::toggleAttachment,
                onFull = { Toast.makeText(context, AttachCopy.FULL, Toast.LENGTH_SHORT).show() },
                onDeviceFiles = if (FeatureFlags.get(context).deviceFiles()) ({
                    showPlus = false
                    deviceBrowserConversation = vm.libraryTargetConversation()
                }) else null,
                onPaste = {
                    val pasted = readClipboardText(context)
                    if (pasted.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(R.string.plus_clipboard_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        showPlus = false
                        val now = vm.ui.value.input
                        vm.input(if (now.isEmpty()) pasted else now.trimEnd() + "\n" + pasted)
                        editRequest++
                    }
                },
                onTeach = if (ui.composer == ComposerMode.Idle) ({
                    showPlus = false
                    onOpenTeach(vm.ui.value.input.trim())
                }) else null,
                onChecklists = { showPlus = false; showChecklists = true },
                onWorkflows = { showPlus = false; showWorkflows = true },
                tasks = recommendedTasks,
                onPickTask = { showPlus = false; pickTask(it) },
                onPickSkill = { skill ->
                    showPlus = false
                    val prefix = context.getString(R.string.plus_skill_directive, skill.display)
                    vm.input(prefix + vm.ui.value.input)
                    editRequest++
                },
            )
        }

        if (showUsage) ConversationUsageSheet(ui.conversationId) { showUsage = false }
    if (showRecords) {
        CallRecordsSheet(
            callLog, current = callLog.lastOrNull { it.endMs == null }?.id.orEmpty(),
            onPick = { call -> showRecords = false; ui.conversationId?.let { vm.openConversationAt(it, ChatTarget.Call(call)) } },
            onDismiss = { showRecords = false },
        )
    }
    if (showTasks) {
        val items = remember(ui.exchanges, ui.deskTasks) { deskTaskItems(ui.exchanges, ui.deskTasks) }
        val titles by produceState(emptyMap<String, String>(), items) { value = vm.deskTaskTitles(items.map { it.taskId }) }
        DeskTasksSheet(items, titles, onPick = { showTasks = false; openDeskTask(it.taskId) }, onDismiss = { showTasks = false })
    }

        if (!screenDocked) AgentPip(
            pip = pip,
            frame = frame,
            webContent = webContent,
            pipInput = pipInputLive,
            soundOn = pipSoundOn.collectAsState().value,
            operateEnabled = pipOperateEnabled,
            reduceMotion = reduceMotion,
            topInset = topHeight.toFloat(),
            bottomInset = composerHeight.toFloat(),
            onExpand = { if (deskPip != null) deskPipExpanded = deskPip.taskId else vm.expandPip() },
            onCollapse = { if (deskPip != null) deskPipExpanded = null else vm.collapsePip() },
            // Close the small window: only the small button in the bottom-right remains until this task ends; a toast says where it can still be viewed
            onClose = {
                if (deskPip != null) {
                    deskPipClosed = deskPipClosed + deskPip.taskId
                    deskPipExpanded = null
                } else vm.closePip()
                Toast.makeText(context, context.getString(R.string.pip_closed_hint), Toast.LENGTH_SHORT).show()
            },
            onReopen = { if (deskPip != null) deskPipClosed = deskPipClosed - deskPip.taskId else vm.reopenPip() },
            onToggleSound = { if (deskPip != null) vm.togglePipSoundIn(deskPip.taskId) else vm.togglePipSound() },
            // Tapping the small window when something needs you: collapse and scroll to the card that needs you. For a running task, scroll to its question card (or open its process record if there is none);
            // a card for your own task is in the last question and answer, which can only be followed by file cards and the go-to-settings row
            onAttention = {
                if (deskPip != null) {
                    deskPipExpanded = null
                    if (!jumpToNeed(deskPip.taskId)) openDeskTask(deskPip.taskId)
                } else {
                    vm.attendPip()
                    val count = listState.layoutInfo.totalItemsCount
                    val after = (if (last?.files?.isNotEmpty() == true) 1 else 0) + (if (setupRow == ui.exchanges.lastIndex || setupRow == -1) 1 else 0)
                    val card = count - 1 - after
                    if (card >= 0) scope.launch { if (reduceMotion) listState.scrollToItem(card) else listState.animateScrollToItem(card) }
                }
            },
            label = pipLabel,
        )

        queueActions?.let { item ->
            ActionSheet(
                ChatCopy.plainTitle(item.text),
                buildList {
                    if (editableSteer(item.text)) add(SheetAction(stringResource(R.string.follow_up_edit)) {
                        vm.editQueued(item.id)?.let { editRequest++ }
                    })
                    add(SheetAction(stringResource(R.string.follow_up_cancel), destructive = true) {
                        if (!vm.cancelQueued(item.id)) Toast.makeText(context, context.getString(R.string.follow_up_already_started), Toast.LENGTH_SHORT).show()
                    })
                },
                onDismiss = { queueActions = null },
            )
        }

        // The long-press message menu covers everything on top: copy the plain text; quote it above the input field; select
        // text to switch that message back to the system's long-press selection; a turn already answered can also be branched from here
        menu?.let { m ->
            val pending = withdrawableSteer(ui.exchanges, m.key)
            val canEdit = input.isBlank() && ui.quote == null && ui.attachments.isEmpty() && !ui.importing
            val branchTurn = vm.branchTurn(m.key)
            val retryTurn = vm.retryTurn(m.key)
            MessageMenu(
                m, progress = { lift.value },
                onSaveTask = if (recommendationStore != null && m.from == QuoteMessage.From.You && m.text.isNotBlank()) ({
                    closeMenu { saveTaskPrompt = m.text }
                }) else null,
                onSaveChecklist = if (checklistStore != null && m.from == QuoteMessage.From.Mika && m.text.isNotBlank() && ui.conversationId != null) ({
                    closeMenu {
                        checklistIncoming = ChecklistSource(ui.conversationId!!, m.key, m.text)
                        showChecklists = true
                    }
                }) else null,
                onCopy = { closeMenu { copyMessage(context, m.text) } },
                onQuote = { closeMenu { vm.quote(m.from, m.text) } },
                onSelect = {
                    closeMenu {
                        selecting = m.key
                        Toast.makeText(context, context.getString(R.string.chat_select_hint), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { closeMenu() },
                onBranch = branchTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val id = vm.branchFrom(turn)
                            val copy = vm.branchCopy.value
                            val message = when {
                                id == null -> R.string.chat_branch_failed
                                copy?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onBranchRetry = retryTurn?.let { turn -> {
                    closeMenu {
                        scope.launch {
                            val result = vm.branchBefore(turn)
                            val message = when {
                                result == null -> R.string.chat_branch_failed
                                result.needsReattach -> R.string.chat_branch_attachments_readd
                                vm.branchCopy.value?.truncated == true -> R.string.chat_branch_attachments_skipped
                                else -> R.string.chat_branch_done
                            }
                            if (result != null && result.failures.isNotEmpty() && vm.ui.value.conversationId == result.conversationId) {
                                val details = result.failures.joinToString("\n") { failure ->
                                    val reason = when (failure.status) {
                                        com.wangzan101.mika.chat.RetryAttachmentStatus.MISSING -> R.string.chat_retry_missing
                                        com.wangzan101.mika.chat.RetryAttachmentStatus.OVER_LIMIT -> R.string.chat_retry_limit
                                        else -> R.string.chat_retry_failed
                                    }
                                    context.getString(reason, failure.path.substringAfterLast('/'))
                                }
                                val screenHint = if (result.screenNeedsReattach) "\n" + context.getString(R.string.chat_branch_attachments_readd) else ""
                                retryFailures = result.conversationId to (details + screenHint)
                            } else if (result == null || vm.ui.value.conversationId == result.conversationId) {
                                Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                            }
                            vm.clearBranchCopy()
                        }
                    }
                } },
                onEdit = if (pending != null && canEdit && editableSteer(pending.text)) ({ closeMenu {
                    vm.editSteer(pending.id)?.let { editRequest++ }
                } }) else null,
                onWithdraw = pending?.let { n -> ({ closeMenu { vm.withdrawSteer(n.id) } }) },
            )
        }
    }

    retryFailures?.takeIf { it.first == ui.conversationId }?.let { (_, details) ->
        MikaDialog(
            onDismissRequest = { retryFailures = null },
            title = { Text(stringResource(R.string.chat_branch_attachments_skipped)) },
            text = { Text(details) },
            confirmButton = { TextButton({ retryFailures = null }) { Text(stringResource(R.string.ok_got_it)) } },
        )
    }

    if (showChecklists && checklistStore != null) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showChecklists = false; checklistIncoming = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            SavedChecklistsScreen(checklistStore, incoming = checklistIncoming,
                onBack = { showChecklists = false; checklistIncoming = null },
                onUseDraft = { text ->
                    showChecklists = false; checklistIncoming = null
                    if (vm.ui.value.input.isEmpty()) vm.input(text) else pendingChecklistDraft = text
                })
        }
    }
    pendingChecklistDraft?.let { text ->
        MikaDialog(
            onDismissRequest = { pendingChecklistDraft = null },
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.share_draft_replace_body)) },
            confirmButton = { TextButton({ vm.input(text); pendingChecklistDraft = null }, Modifier.testTag("checklist-replace-draft")) {
                Text(stringResource(R.string.chat_example_replace_action))
            } },
            dismissButton = { TextButton({ pendingChecklistDraft = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showWorkflows && workflowRuntime != null) androidx.compose.ui.window.Dialog(
        onDismissRequest = { showWorkflows = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            WorkflowsScreen(workflowRuntime, onBack = { showWorkflows = false },
                onOpenConversation = { id -> showWorkflows = false; vm.openWorkflowConversation(id) },
                onUseResult = { text ->
                    showWorkflows = false
                    if (vm.ui.value.input.isEmpty()) vm.input(text) else pendingExample = text
                })
        }
    }
    saveTaskPrompt?.let { prompt ->
        recommendationStore?.let { store ->
            RecommendedTaskDialog(store, initial = null, onDismiss = { saveTaskPrompt = null }, seedPrompt = prompt)
        }
    }

    fillTaskPrompt?.let { prompt ->
        TaskFillDialog(fillTaskTitle, prompt, onDismiss = { fillTaskPrompt = null }, onFill = { filled ->
            fillTaskPrompt = null
            fillDraft(filled)
        })
    }

    pendingExample?.let { example ->
        MikaDialog(
            onDismissRequest = { pendingExample = null },
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.chat_example_replace_body)) },
            confirmButton = {
                TextButton(
                    onClick = { vm.input(example); pendingExample = null },
                    Modifier.testTag("confirm-replace-draft"),
                ) { Text(stringResource(R.string.chat_example_replace_action)) }
            },
            dismissButton = { TextButton({ pendingExample = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (ui.pendingSharedDraft != null) {
        MikaDialog(
            onDismissRequest = vm::cancelPendingSharedDraft,
            title = { Text(stringResource(R.string.chat_example_replace_title)) },
            text = { Text(stringResource(R.string.share_draft_replace_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!vm.confirmPendingSharedDraft()) {
                            Toast.makeText(context, R.string.share_draft_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    Modifier.testTag("confirm-replace-shared-draft"),
                ) { Text(stringResource(R.string.chat_example_replace_action)) }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::cancelPendingSharedDraft,
                    Modifier.testTag("cancel-replace-shared-draft"),
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The last item may be a very long answer, and merely seeing its beginning does not mean the user is looking at the end. */
private fun LazyListState.nearTail(thresholdPx: Int): Boolean {
    val layout = layoutInfo
    val end = layout.totalItemsCount - 1
    val tail = layout.visibleItemsInfo.firstOrNull { it.index == end } ?: return false
    return tail.offset + tail.size - layout.viewportEndOffset <= thresholdPx
}

// ---------- Empty conversation ----------

/** Brief highlight after jumping to a task card: which conversation, which row; [at] makes tapping the same card again restart the timer. */
private data class CardFlash(val conversationId: String?, val key: String, val at: Long)
