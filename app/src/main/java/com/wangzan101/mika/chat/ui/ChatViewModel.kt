package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationSettings
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryNote
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.skills.SkillNote
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.core.assembly.knownTools
import kotlin.coroutines.CoroutineContext
import com.wangzan101.mika.chat.ui.ChatReduce.active
import com.wangzan101.mika.chat.ui.ChatReduce.reduce
import com.wangzan101.mika.chat.ui.ChatReduce.sizeLabel
import com.wangzan101.mika.chat.ui.ChatReduce.where
import com.wangzan101.mika.chat.ui.ChatReduce.withAttachments
import com.wangzan101.mika.files.ui.LibraryAddPlan
import com.wangzan101.mika.files.ui.LibraryAttachmentResult
import com.wangzan101.mika.files.ui.LibraryFileHandle
import com.wangzan101.mika.files.ui.LibraryMergeResult
import com.wangzan101.mika.files.ui.LibraryStaged
import com.wangzan101.mika.settings.ui.models.refresh
import com.wangzan101.mika.ui.ToolLabels

/**
 * State of the chat UI. The step list comes from what actually ran in the run events (the activity); the progress line is what the model said in this step,
 * and while waiting for the model only the status is shown. Text sent from the input bar goes to this conversation's front desk (see [DeskChatOps]), and tasks it dispatches run in their own child conversations.
 * There is one instance for the whole process, so when the UI is closed and reopened the steps of a running task are still there.
 */
class ChatViewModel(
    internal val scope: CoroutineScope,
    taskState: StateFlow<TaskState>,
    internal val takeoverStage: StateFlow<Takeover.Stage>,
    internal val actions: TaskActions,
    internal val store: ConversationStore,
    /**
     * The service still has a task (the previous task of this conversation is still wrapping up, or a task started
     * elsewhere is running); with parallelism off, a new task waits for it to finish before being handed to the service,
     * so a new sentence in this conversation is not taken as a steer for the previous task. While waiting, the message says
     * "wait for the previous task to finish before starting" below it.
     */
    internal val serviceBusy: StateFlow<Boolean> = MutableStateFlow(false),
    /** Converts a package name to the app's display name (used by confirmation cards). */
    internal val appLabel: (String) -> String = { it },
    /**
     * Whether the current task has a virtual screen. The screen is created only when an app is first opened, so tasks that
     * never created one (pure conversation, answerable without the phone) show no picture-in-picture, and their questions
     * and confirmations stay in the chat card.
     */
    internal val screenOpen: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * Run record id of the task that just ended, written by the service before it reports completion; when the finished
     * state arrives it is attached to that question-answer, so the card has "View process". Later changes to it do not touch
     * the one already finished.
     */
    internal val endedRunId: StateFlow<String?> = MutableStateFlow(null),
    /**
     * Run record id of the task currently running in a conversation (there from the very start of the task; null until it
     * is assigned). Looked up per conversation and, like [endedRunIds], ignoring the service's "shown task", which in
     * parallel mode may belong to another conversation. Attached while running, so "run process" opens immediately.
     */
    internal val liveRunId: (conversationId: String) -> String? = { null },
    /** Run record id of the most recently ended task per conversation; when provided it is looked up by the card's conversation instead of using the one above (which only follows the shown task). */
    internal val endedRunIds: StateFlow<Map<String, String?>>? = null,
    /** The parallelTasks switch: when on, tasks in different conversations can run at the same time and the UI only follows the task of the conversation on screen. Re-read whenever the UI is not following a task. */
    internal val parallelFlag: () -> Boolean = { false },
    /** The service's shown task: (conversation, state), written together; in parallel mode it replaces [taskState], so other conversations' states do not come in. */
    internal val shownTask: StateFlow<Pair<String, TaskState>?> = MutableStateFlow(null),
    /** Conversations that have a running or queued task. */
    internal val activeConversations: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    /** The original wording of the task running in that conversation; used to keep showing it after switching over. */
    internal val runGoal: (String) -> String? = { null },
    /** The running task's own state for that conversation (regardless of who the service is showing right now); after switching away it keeps that question-answer's steps updated. */
    internal val runState: (String) -> StateFlow<TaskState>? = { null },
    /** The takeover progress of the task running in this conversation; used by the main chat's question card when drawing takeover buttons for a running task. */
    internal val runStage: (String) -> StateFlow<Takeover.Stage>? = { null },
    /** Memory and skill notes added after a task ends, by run id. */
    internal val runNotes: StateFlow<Map<String, RunNotes>> = MutableStateFlow(emptyMap()),
    internal val learning: LearningActions? = null,
    /** Memory items as of now (read from encrypted storage); the note text and the "used" contents are fetched from here, and the conversation file stores no content. */
    internal val memoryItems: StateFlow<List<MemoryItem>> = MutableStateFlow(emptyList()),
    /** Whether the sandbox is ready. */
    internal val sandboxReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** Files exported by the current task; cleared by the service when a task starts. */
    internal val sharedFiles: StateFlow<List<SharedFile>> = MutableStateFlow(emptyList()),
    /** Audio generated by the current task and left in the workspace; cleared by the service when a task starts. */
    internal val generatedAudios: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedAudio>> = MutableStateFlow(emptyList()),
    /** Images generated by the current task and left in the workspace; cleared by the service when a task starts. */
    internal val generatedPhotos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedPhoto>> = MutableStateFlow(emptyList()),
    /** 当前任务生成并留在工作区里的视频;任务开始时由服务清空。 */
    internal val generatedVideos: StateFlow<List<com.wangzan101.mika.core.chat.GeneratedVideo>> = MutableStateFlow(emptyList()),
    /** 把选中的文件(内容 URI 字符串)复制进对话工作区的 inbox/。 */
    internal val importAttachments: suspend (conversationId: String, uris: List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
    /** Reads the name, size and whether it is a photo for the selected files (content URI strings); the caller moves disk work onto a background thread. */
    internal val describeAttachments: suspend (uris: List<String>) -> List<PendingAttachment> = { uris -> uris.map { PendingAttachment(it, it.substringAfterLast('/'), 0L) } },
    /** The tool wording used in step rows. */
    internal val toolLabels: ToolLabels = ToolLabels(knownTools()),
    /** What the shown task has actually done (activities in the run events). */
    internal val activities: StateFlow<List<RunEvent.Activity>> = MutableStateFlow(emptyList()),
    /** Steers the shown task has received, and where they went. */
    internal val steers: StateFlow<List<SteerNote>> = MutableStateFlow(emptyList()),
    /** While a call is waiting for the owner to reply, the next message sent in this conversation goes to the call (returns true, without starting a task or interjecting); returns false when not waiting. */
    internal val ownerAnswer: (conversationId: String, text: String) -> Boolean = { _, _ -> false },
    /** The process-wide queue; here only items for the current conversation that the service has not taken yet. */
    private val followUps: StateFlow<List<QueuedFollowUp>> = MutableStateFlow(emptyList()),
    /** The runs (run ids) whose write-up is still being written after a stop; that turn shows "organizing what was done…" below it. */
    internal val summarizing: StateFlow<Set<String>> = MutableStateFlow(emptySet()),
    /** Clock used to compute elapsed time (milliseconds). */
    internal val clock: () -> Long = System::currentTimeMillis,
    /** What deleting a conversation also affects (see `ConversationDeleter.impact`). */
    internal val deletionImpact: (String) -> DeleteImpact = { DeleteImpact(runningTask = false, schedules = 0) },
    /** Deletes a conversation and everything belonging to it (see `ConversationDeleter.delete`). */
    internal val removeConversation: suspend (String) -> Unit = {},
    /** The page left running in the background after a task ends (at most one page globally); the chat page shows a row under the turn it came from, and the top bar says "1 web page kept in the background". */
    val keptPage: StateFlow<KeptPage?> = MutableStateFlow(null),
    /** Closes the kept page (the user tapped "Close" on that row). */
    private val closeKept: () -> Unit = {},
    /** Copies the source conversation workspace's inbox/ (attachments) when branching; when not injected, only turns are copied. */
    internal val branchWorkspace: BranchWorkspace? = null,
    /** Branch title: appends a localized suffix to the source title or first sentence (truncated when too long); unchanged by default. */
    internal val branchTitle: (String) -> String = { it },
    /** Persistence for system share drafts; internal image file names become content URIs of this app's provider. */
    internal val sharedDrafts: SharedDraftStore? = null,
    internal val sharedDraftUri: (SharedDraftAttachment) -> String = { it.fileName },
    /** Where conversation records are read and written and where branches are copied. */
    internal val io: CoroutineContext = Dispatchers.IO,
    /** While learning from the user, do not start a separate task, nor record this sentence as already started. */
    internal val teaching: () -> Boolean = { false },
    internal val onTeachingBlocked: () -> Unit = {},
    /** 用户暂停了 Mika:这句话不发出去(不进前台、不开任务),提示一声。 */
    internal val paused: () -> Boolean = { false },
    internal val onPausedBlocked: () -> Unit = {},
    /** 朗读一条回答;没配置声音模型时不做。 */
    private val speakText: suspend (String) -> Unit = {},
    private val stopVoice: () -> Unit = {},
    private val voicePlayback: StateFlow<com.wangzan101.mika.voice.VoicePlayback> =
        MutableStateFlow(com.wangzan101.mika.voice.VoicePlayback.Idle),
    private val voiceReady: StateFlow<Boolean> = MutableStateFlow(false),
    /** Automatically read out every completed answer; off by default. */
    internal val autoRead: StateFlow<Boolean> = MutableStateFlow(false),
    /** Plays an audio clip in the workspace (conversation id, path); does nothing by default. */
    private val playAudio: suspend (String, String) -> Unit = { _, _ -> },
    /** 用系统播放器打开工作区里的一段视频(对话编号, 路径);默认不做。 */
    private val playVideo: suspend (String, String) -> Unit = { _, _ -> },
    /** 取工作区一段视频的首帧当封面(对话编号, 路径);取不到时为 null。 */
    private val videoPosterLoader: suspend (String, String) -> android.graphics.Bitmap? = { _, _ -> null },
    private val voiceFailed: (String) -> Unit = {},
    /** Project group list and the delete-project entry; when null, everything is shown as ordinary conversations only. */
    internal val projectStore: ProjectStore? = null,
    internal val projectDeletionImpact: (String) -> ProjectDeleteImpact = { ProjectDeleteImpact(0, 0, 0) },
    internal val removeProject: suspend (String) -> Unit = {},
    internal val workspaceResolver: WorkspaceResolver? = null,
    /** 对话主模型的只读目录;未注入时(旧测试/预览)不显示模型入口。 */
    internal val mainModels: MainModelCatalog? = null,
    /** 聊天走前台:打的字交给这条会话的前台,派出的任务挂成卡片;没给时(只在测试里)照旧直接开任务。 */
    desk: DeskChatDeps? = null,
) {
    internal val session = ChatSession(this)
    internal val live = ChatLiveFollow(this, session)
    internal val chats = ChatConversationOps(this, session)
    internal val branching = ChatBranchOps(this, session)
    internal val attaching = ChatAttachmentOps(this, session)
    internal val drafting = ChatDraftOps(this, session)
    internal val sending = ChatSendOps(this, session)
    internal val noting = ChatNoteOps(this, session)
    internal val desking: ChatDesk? = desk?.let { ChatDesk(this, session, it) }

    /** The front desk attached to this conversation; null when none is attached or this is a child-task conversation dispatched by the front desk (talk to the task directly as before, see [ChatDesk.handles]). */
    internal fun deskFor(conversationId: String?): ChatDesk? = desking?.takeIf { it.handles(conversationId) }
    private var autoReadKey by session::autoReadKey
    private val _ui = session._ui
    private val _jump = session._jump
    private val drafts = session.drafts
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private val _branchCopy = session._branchCopy
    private val _deleteBatch = session._deleteBatch

    /** Project group list; when empty or not injected, project groups are not shown. */
    val projects: StateFlow<List<Project>> = projectStore?.projects ?: MutableStateFlow(emptyList())
    private val _collapsedProjects = MutableStateFlow<Set<String>>(emptySet())
    val collapsedProjects: StateFlow<Set<String>> = _collapsedProjects.asStateFlow()

    fun toggleProjectCollapsed(projectId: String) {
        _collapsedProjects.update { if (projectId in it) it - projectId else it + projectId }
    }

    /** The user closes the page kept in the background from the chat page. */
    fun closeKeptPage() = closeKept()

    /** Speaking state; the "read aloud / stop" control under an answer is shown according to it. */
    val voice: StateFlow<com.wangzan101.mika.voice.VoicePlayback> get() = voicePlayback

    /** Whether a usable voice model is available; when not, no read-aloud entry is shown. */
    val canSpeak: StateFlow<Boolean> get() = voiceReady

    fun speak(text: String) {
        scope.launch {
            try {
                speakText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voiceFailed(tr(R.string.task_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun stopSpeaking() = stopVoice()

    /** Plays an audio clip generated in this turn; does nothing when there is no conversation id. */
    fun playAudio(path: String) {
        val id = _ui.value.conversationId ?: return
        scope.launch { playAudio(id, path) }
    }

    /** 用系统播放器打开这一轮生成的一段视频;没有对话编号时不做。 */
    fun playVideo(path: String) {
        val id = _ui.value.conversationId ?: return
        scope.launch { playVideo(id, path) }
    }

    /** 视频卡片的封面(首帧);取不到返回 null。 */
    suspend fun loadVideoPoster(path: String): android.graphics.Bitmap? {
        val id = _ui.value.conversationId ?: return null
        return videoPosterLoader(id, path)
    }

    val ui: StateFlow<ChatUi> = _ui.asStateFlow()
    val jump: StateFlow<ChatJump?> = _jump.asStateFlow()

    /** 未物化对话的选择由进程级 [ConversationStore] 保存；首句被收下时才写入旁文件。 */
    private val materializedMainChoices = mutableSetOf<String>()

    internal fun refreshMainModel() {
        val catalog = mainModels
        if (catalog == null) {
            _ui.update { it.copy(mainModel = MainModelUi()) }
            return
        }
        val id = _ui.value.conversationId
        val saved = when {
            store.hasPendingMainChoice(id) -> store.pendingMainChoice(id)
            id != null && (id in materializedMainChoices || store.exists(id)) -> runCatching { store.settings(id).main }.getOrNull()
            else -> null
        }
        val usable = catalog.usable(saved)
        val global = catalog.globalLabel()
        val options = catalog.options()
        val model = MainModelUi(
            available = global != null || options.isNotEmpty() || usable != null,
            override = saved,
            label = if (saved != null && usable != null) catalog.label(usable) else global,
            group = usable?.let(catalog::isGroup) == true,
            fallback = saved != null && usable == null,
            globalLabel = global,
            options = options,
        )
        _ui.update { it.copy(mainModel = model) }
    }

    /** 选择只影响下一次任务；正在执行的任务继续使用启动时冻结的模型快照。 */
    fun chooseMainModel(choice: ModelChoice?) {
        val catalog = mainModels ?: return
        if (choice != null && catalog.usable(choice) == null) return
        val id = _ui.value.conversationId
        if (id != null && (id in materializedMainChoices || store.exists(id))) {
            if (!store.setSettings(id, ConversationSettings(choice))) return
            store.clearPendingMainChoice(id)
        } else {
            store.setPendingMainChoice(id, choice)
        }
        refreshMainModel()
    }

    /** 前台构造模型时读取这一刻的有效选择；未落盘的首句选择也包含在内。 */
    internal fun effectiveMainModelChoice(id: String?): ModelChoice? =
        if (store.hasPendingMainChoice(id)) store.pendingMainChoice(id)
        else id?.let { runCatching { store.settings(it).main }.getOrNull() }

    /** 草稿编号确定时绑定选择；前台拒绝后再移回 null 键。 */
    internal fun bindPendingMainModel(id: String) = store.movePendingMainChoice(null, id)

    internal fun unbindPendingMainModel(id: String) = store.movePendingMainChoice(id, null)

    internal fun flushPendingMainModel(id: String) {
        if (!store.hasPendingMainChoice(id)) return
        val choice = store.pendingMainChoice(id)
        if (store.setSettings(id, ConversationSettings(choice))) {
            store.clearPendingMainChoice(id)
            materializedMainChoices += id
        }
    }

    internal fun clearUnnumberedMainChoice() = store.clearPendingMainChoice(null)

    /** 尚未发送第一句话的草稿也能从侧栏找回来;已有历史的对话由侧栏沿用原标题。 */
    val draftConversations = combine(drafts, _ui) { saved, current ->
        val all = saved + listOfNotNull(current.conversationId?.let { it to current.draft() }).toMap()
        all.filterValues { it.hasContent }.map { (id, draft) ->
            ConversationSummary(id,
                ChatCopy.plainTitle(draft.input).takeIf { it.isNotBlank() }
                    ?: draft.attachments.firstOrNull()?.name ?: tr(R.string.chat_new), 0L,
                projectId = session.projectFor(id) ?: draft.projectId)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val deleted: StateFlow<Set<String>> = _deleted.asStateFlow()
    val branchCopy: StateFlow<BranchCopyResult?> = _branchCopy.asStateFlow()

    /** Conversations other than the one on screen that have a running or queued task (the "n other tasks in progress" at the top). */
    val otherRunning: StateFlow<List<String>> = combine(activeConversations, _ui.map { it.conversationId }.distinctUntilChanged()) { active, current ->
        active.filter { it != current }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // Even with the switch off, go by the conversation carried in the state: tasks of other conversations (started from the floating pill or by schedules) are not attached to the conversation on screen
        scope.launch { taskState.collect { s -> if (!session.parallel()) shownTask.value?.let { live.onShown(it.first, s) } ?: live.onState(s) } }
        scope.launch { shownTask.collect { t -> if (t != null && session.parallel()) live.onShown(t.first, t.second) } }
        // Queue bar: show only items of the current conversation that are explicitly queued and not yet claimed by the service; the fallback is represented by the SteerState.Next line of an inserted message.
        // Combined with the whole ChatUi (not just conversationId): when switching conversations or rebuilding ChatUi the queue must be restored even if the id did not change.
        scope.launch {
            combine(followUps, _ui) { list, ui ->
                val id = ui.conversationId
                if (id == null) emptyList() else list.filter {
                    it.conversationId == id && !it.claimed && it.source == QueuedSource.Explicit
                }
            }.collect { queued -> _ui.update { if (it.queued == queued) it else it.copy(queued = queued) } }
        }
        // Whoever saved into the conversation on screen (floating pill, schedules, here), fill it in from what was saved
        scope.launch {
            store.changes.collect { id ->
                if (id == _ui.value.conversationId) {
                    live.syncSaved(id)
                    refreshMainModel()
                }
                // 删掉的对话又有人往里存了:重新列出来
                if (id in _deleted.value && id !in deleting && store.turns(id).isNotEmpty()) _deleted.update { it - id }
            }
        }
        // In parallel mode, only accept takeover progress from the task of the conversation on screen
        scope.launch {
            takeoverStage.collect { s -> if (session.ownTask()) _ui.update { it.copy(takeoverStage = s) } }
        }
        // Only accept criteria listed during the running task: the value at subscription time may belong to the previous task
        // Whether there is a screen is per task, like the state: in parallel mode only accept it from the task of the conversation on screen
        scope.launch { screenOpen.collect { if (it && session.ownTask()) live.onScreenOpened() } }
        scope.launch { sandboxReady.collect { r -> _ui.update { it.copy(attachEnabled = r) } } }
        scope.launch { sharedFiles.collect { if (session.ownTask()) live.onSharedFiles(it) } }
        scope.launch { autoRead.collect { if (!it) autoReadKey = null } }
        scope.launch { generatedAudios.collect { if (session.ownTask()) live.onAudios(it) } }
        scope.launch { generatedPhotos.collect { if (session.ownTask()) live.onPhotos(it) } }
        scope.launch { generatedVideos.collect { if (session.ownTask()) live.onVideos(it) } }
        scope.launch { runNotes.collect { notes -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { session.withNotes(it, notes) }) } } }
        scope.launch { activities.collect { if (session.ownTask()) live.onActivities(it) } }
        scope.launch { steers.collect { if (session.ownTask()) live.onSteers(it) } }
        // A task being registered in the task list (so its original wording can be fetched) may come later than its first state: the question-answer attached at that time then gets its original wording filled in
        scope.launch { activeConversations.collect { live.fillGoal() } }
        scope.launch { summarizing.collect { set -> _ui.update { ui -> ui.copy(exchanges = ui.exchanges.map { session.withSummarizing(it, set) }) } } }
        mainModels?.let { catalog -> scope.launch { catalog.changes.collect { refreshMainModel() } } }
        // 分享附件有私有副本,可以恢复;只把当前对话那份放到输入框,其余留在各自草稿里。
        sharedDrafts?.readAll()?.forEach { draft ->
            drafts.update { it + (draft.conversationId to ComposerDraft(input = draft.text,
                attachments = session.sharedAttachments(draft), sharedDraft = draft)) }
        }
        desking?.start()
        val current = store.currentChat() as? CurrentChat.Open
        val pending = sharedDrafts?.readPending()?.takeIf { current != null && it.conversationId == current.id }
        if (current != null && (current.id in drafts.value || pending != null)) {
            _ui.value = session.restoreDraft(_ui.value.rebuildForConversation(conversationId = current.id, exchanges = session.saved(current.id)))
                .copy(pendingSharedDraft = pending)
        }
        refreshMainModel()
    }

    val deleteBatch: StateFlow<DeleteBatch?> = _deleteBatch.asStateFlow()

    /** The app or site currently being operated (the most recent thing with a target); used by the top bar subtitle "operating …". Null when there is none. */
    fun livePlace(ex: Exchange): String? =
        ex.activities.lastOrNull { !it.target.isNullOrBlank() }?.target?.let { where(it, appLabel) }

    /** The original wording of that conversation's running task; used as the title for the "Running" entry in the drawer that is not yet saved into the conversation list. */
    fun runningGoal(conversationId: String): String? = runGoal(conversationId)

    /** Whether other conversations can be switched to: at any time in parallel mode, otherwise only after this conversation's task ends. */
    fun canSwitch() = session.parallel() || !session.busy()

    // ChatSession
    fun busy() = session.busy()

    // ChatLiveFollow
    fun refresh() = live.refresh()

    // ChatConversationOps
    fun switchTo(conversationId: String) = chats.switchTo(conversationId)
    fun openWorkflowConversation(conversationId: String) = chats.openWorkflowConversation(conversationId)
    fun openPersonalTask(conversationId: String) = chats.openPersonalTask(conversationId)
    fun openConversationAt(id: String, target: ChatTarget) = chats.openConversationAt(id, target)

    /** The voice call records of this conversation (used by the chat page to draw call start/end lines); empty when there is no conversation. */
    fun calls(conversationId: String?): kotlinx.coroutines.flow.Flow<List<com.wangzan101.mika.chat.ConversationCall>> =
        conversationId?.let(store::observeCalls) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    /** The title each dispatched job's conversation has generated (used by "Work in this conversation"); jobs without one are absent. */
    suspend fun deskTaskTitles(ids: List<String>): Map<String, String> =
        kotlinx.coroutines.withContext(io) { ids.mapNotNull { id -> store.title(id)?.let { id to it } }.toMap() }
    fun consumeJump(jump: ChatJump) = chats.consumeJump(jump)
    fun createProject(name: String): Result<Project>? = chats.createProject(name)
    fun renameProject(projectId: String, name: String): Result<Project>? = chats.renameProject(projectId, name)
    fun projectDeleteImpact(projectId: String): ProjectDeleteImpact = chats.projectDeleteImpact(projectId)
    fun deleteProject(projectId: String) = chats.deleteProject(projectId)
    fun openConversation(id: String) = chats.openConversation(id)
    fun newConversation(projectId: String? = null) = chats.newConversation(projectId)
    fun currentRunning(): Boolean = chats.currentRunning()
    fun deleteImpact(id: String): DeleteImpact = chats.deleteImpact(id)
    fun deleteConversation(id: String) = chats.deleteConversation(id)
    fun clearDeleteBatch() = chats.clearDeleteBatch()
    fun deleteConversations(ids: Collection<String>) = chats.deleteConversations(ids)
    fun renameConversation(id: String, title: String) = chats.renameConversation(id, title)
    fun setPinned(id: String, pinned: Boolean) = chats.setPinned(id, pinned)
    fun archiveConversation(id: String): Boolean = chats.archiveConversation(id)
    fun unarchiveConversation(id: String) = chats.unarchiveConversation(id)
    fun unarchiveConversations(ids: Collection<String>) = chats.unarchiveConversations(ids)
    fun archiveConversations(ids: Collection<String>): Int = chats.archiveConversations(ids)

    // ChatBranchOps
    fun branchTurn(key: String): Int? = branching.branchTurn(key)
    fun retryTurn(key: String): Int? = branching.retryTurn(key)
    fun clearBranchCopy() = branching.clearBranchCopy()
    suspend fun branchFrom(throughTurn: Int): String? = branching.branchFrom(throughTurn)
    suspend fun branchBefore(turn: Int): BranchRetry? = branching.branchBefore(turn)

    // ChatAttachmentOps
    fun attach(uris: List<String>, conversationId: String? = _ui.value.conversationId) = attaching.attach(uris, conversationId)
    internal fun importDeviceAttachments(conversationId: String, paths: List<String>, copy: suspend (String, String) -> com.wangzan101.mika.core.workspace.DeviceFileImport) = attaching.importDeviceAttachments(conversationId, paths, copy)
    fun libraryTargetConversation(): String = attaching.libraryTargetConversation()

    /** The current conversation's id; for a still-blank new conversation, reserves the next one (the chat page stays on it), for entry points such as calls that need to land in the current conversation. */
    fun claimConversation(): String = session.draftConversation()
    fun planLibraryAttachments(conversationId: String, sources: List<LibraryFileHandle>): LibraryAddPlan? = attaching.planLibraryAttachments(conversationId, sources)
    fun addLibraryAttachments(conversationId: String, items: List<LibraryStaged>, overLimit: Int = 0): LibraryMergeResult = attaching.addLibraryAttachments(conversationId, items, overLimit)
    fun addLibraryAttachment(conversationId: String, item: PendingAttachment, source: LibraryFileHandle? = null, release: () -> Unit = {}): LibraryAttachmentResult = attaching.addLibraryAttachment(conversationId, item, source, release)
    fun toggleAttachment(item: PendingAttachment): Boolean = attaching.toggleAttachment(item)
    fun removeAttachment(uri: String) = attaching.removeAttachment(uri)
    fun dismissRejected() = attaching.dismissRejected()

    // ChatDraftOps
    fun withDrafts(history: List<ConversationSummary>): List<ConversationSummary> = drafting.withDrafts(history)
    fun input(text: String) = drafting.input(text)
    internal fun saveDraftTexts(): List<String> = drafting.saveDraftTexts()
    internal fun restoreDraftTexts(saved: List<String>) = drafting.restoreDraftTexts(saved)
    fun openSharedDraft(
        prepared: SharedDraft,
        inCurrentConversation: Boolean = false,
        rejected: List<AttachmentFailure> = emptyList(),
        overLimit: Int = 0,
    ): Boolean = drafting.openSharedDraft(prepared, inCurrentConversation, rejected, overLimit)
    fun confirmPendingSharedDraft(): Boolean = drafting.confirmPendingSharedDraft()
    fun cancelPendingSharedDraft() = drafting.cancelPendingSharedDraft()

    // ChatSendOps
    fun quote(from: QuoteMessage.From, text: String) = sending.quote(from, text)
    fun clearQuote() = sending.clearQuote()
    fun send(text: String, check: Prerequisites.Check): Boolean = sending.send(text, check)
    fun startQueued() = sending.startQueued()
    fun cancelQueued(id: String): Boolean = sending.cancelQueued(id)
    fun editQueued(id: String): String? = sending.editQueued(id)
    fun withdrawSteer(messageId: String): Boolean = sending.withdrawSteer(messageId)
    fun editSteer(messageId: String): String? = sending.editSteer(messageId)
    fun dismissBlocked() = sending.dismissBlocked()
    fun cancel() = sending.cancel()
    fun resume() = sending.resume()
    fun takeOver() = sending.takeOver()
    fun handBack() = sending.handBack()
    fun finishedMyself() = sending.finishedMyself()
    fun skip() = sending.skip()
    fun answer(text: String) = sending.answer(text)
    fun approve(key: Int) = sending.approve(key)
    fun decline(key: Int) = sending.decline(key)
    fun expandPip() = sending.expandPip()
    fun attendPip() = sending.attendPip()
    fun collapsePip() = sending.collapsePip()
    fun closePip() = sending.closePip()
    fun reopenPip() = sending.reopenPip()
    fun togglePipSound() = sending.togglePipSound()

    /** Have the service show the task [taskId] dispatched by the front desk (its conversation id is the task id): the picture-in-picture window follows it while the on-screen conversation stays the same. */
    fun showDeskScreen(taskId: String) = actions.show(taskId)

    /** The sound toggle in the picture-in-picture window of that dispatched task. */
    fun togglePipSoundIn(taskId: String) = actions.togglePipSoundIn(taskId)

    // ChatDesk
    /** Whether the chat page is on screen: when it is, background reports are delivered by the front desk on the page; when not, they go to the fallback (written back to the conversation and sent as a notification). */
    fun pageShown(shown: Boolean) { desking?.pageShown(shown) }

    /** A dispatched task is asking a question and the user tapped [words] on the question card. */
    fun answerDeskTask(taskId: String, words: String) { desking?.answer(taskId, words) }

    /** The composer's stop button: stop everything running in this conversation—directly run tasks, the front desk's unfinished replies, and every piece of work the front desk dispatched. */
    fun stopAll() {
        if (ui.value.composer == ComposerMode.Busy) cancel()
        desking?.stopAll()
    }

    /** Stop one piece of work dispatched by the front desk. */
    fun cancelDeskTask(taskId: String) { desking?.cancel(taskId) }

    /** When the running task ([taskId] is the conversation it runs in) needs you to act, the button on the question card: it acts only on that task; stopping still goes through Claire. */
    internal fun deskNeedOps(taskId: String) = NeedOps(
        cancel = { cancelDeskTask(taskId) }, resume = { actions.resumeIn(taskId) }, takeOver = { actions.takeOverIn(taskId) },
        handBack = { actions.handBackIn(taskId) }, finishedMyself = { actions.finishedMyselfIn(taskId) }, skip = { actions.skipIn(taskId) },
        approve = { actions.approveIn(taskId, it) }, decline = { actions.declineIn(taskId, it) },
    )

    // ChatNoteOps
    fun noteText(note: MemoryNote): String = noting.noteText(note)
    fun undoNote(runId: String, note: MemoryNote) = noting.undoNote(runId, note)
    fun approveNote(runId: String, note: MemoryNote) = noting.approveNote(runId, note)
    fun rejectNote(runId: String, note: MemoryNote) = noting.rejectNote(runId, note)
    fun usedMemoryTexts(ids: List<String>): List<String> = noting.usedMemoryTexts(ids)
    fun viewSkill(note: SkillNote) = noting.viewSkill(note)
    fun restoreSkill(runId: String, note: SkillNote) = noting.restoreSkill(runId, note)
    fun deleteSkill(runId: String, note: SkillNote) = noting.deleteSkill(runId, note)

    companion object {
        const val DONE_LINGER_MS = 3_000L

        internal fun reduce(ex: Exchange, s: TaskState, appLabel: (String) -> String = { it }, toolLabel: (String) -> String = { it }): Exchange =
            ChatReduce.reduce(ex, s, appLabel, toolLabel)

        internal fun withAttachments(text: String, files: List<Pair<String, Long>>): String = ChatReduce.withAttachments(text, files)

        fun sizeLabel(bytes: Long): String = ChatReduce.sizeLabel(bytes)
    }
}
