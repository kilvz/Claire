package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.memory.RunNotes
import com.wangzan101.mika.task.SteerNote
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.wangzan101.mika.chat.ui.ChatReduce.fromTurn
import com.wangzan101.mika.files.ui.LibraryFileHandle

/** Shared state: the UI state of the conversation on screen, per-conversation drafts, the task being followed, conversations being deleted, plus the draft and conversation helpers every part needs. */
internal class ChatSession(private val vm: ChatViewModel) {
    private val store = vm.store
    private val parallelFlag = vm.parallelFlag
    private val shownTask = vm.shownTask
    private val activeConversations = vm.activeConversations
    private val runNotes = vm.runNotes
    private val summarizing = vm.summarizing
    private val sharedDrafts = vm.sharedDrafts
    private val sharedDraftUri = vm.sharedDraftUri
    private val projectStore = vm.projectStore
    private val workspaceResolver = vm.workspaceResolver

    /** The answer already read aloud automatically; the same one is not read again. */
    var autoReadKey: String? = null
    val _ui = MutableStateFlow(ChatUi())

    /** One-shot jump coming from search or the file library; cleared once the chat page has scrolled to the target. */
    val _jump = MutableStateFlow<ChatJump?>(null)
    var jumpSerial = 0L
    val drafts = MutableStateFlow<Map<String, ComposerDraft>>(emptyMap())
    var draftStateChanged = false
    val libraryCopies = mutableMapOf<String, () -> Unit>()
    val librarySources = mutableMapOf<String, LibraryFileHandle>()
    val libraryImports = mutableMapOf<String, Int>()
    val deviceImports = mutableMapOf<String, kotlinx.coroutines.Job>()

    /** Reclaims only copies registered by this process; URIs that are importing or referenced by any draft are kept. */
    fun releaseUnusedLibraryCopies() {
        val referenced = _ui.value.attachments.map { it.uri }.toSet() +
            drafts.value.values.flatMap { it.attachments.map(PendingAttachment::uri) } + libraryImports.keys
        val unused = libraryCopies.keys.filter { it !in referenced }
        unused.forEach { uri ->
            librarySources.remove(uri)
            libraryCopies.remove(uri)?.let { runCatching { it() } }
        }
    }

    /** Incremented each time a share is placed into the current conversation; async operations use it to tell whether the draft was replaced by a new share or the user only edited the text. */
    var sharedDraftSerial = 0L
    var pendingSharedFailures: List<AttachmentFailure> = emptyList()
    var pendingSharedOverLimit: Int = 0

    fun draftConversation(): String {
        draftStateChanged = true
        return _ui.value.conversationId ?: store.newConversation().also { id ->
            workspaceResolver?.setDraftProject(id, _ui.value.projectId)
            _ui.update { it.copy(conversationId = id) }
            markCurrent(CurrentChat.Open(id))
        }
    }

    fun keepDraft() {
        draftStateChanged = true
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val shared = ui.sharedDraft?.copy(text = ui.input)?.takeIf { it.text.isNotBlank() || it.attachments.isNotEmpty() }
        if (ui.sharedDraft != null) {
            if (shared == null) sharedDrafts?.clear(id)
            else sharedDrafts?.save(shared)
        }
        val draft = ui.draft().copy(sharedDraft = shared)
        drafts.update { if (draft.hasContent) it + (id to draft) else it - id }
    }

    fun restoreDraft(ui: ChatUi): ChatUi {
        val id = ui.conversationId ?: return ui
        val draft = drafts.value[id] ?: return ui
        drafts.update { it - id }
        return draft.applyTo(ui)
    }

    fun updateDraft(id: String, change: (ComposerDraft) -> ComposerDraft) {
        if (id in _deleted.value) return
        if (_ui.value.conversationId == id) _ui.update { change(it.draft()).applyTo(it) }
        else drafts.update { saved ->
            val draft = change(saved[id] ?: ComposerDraft())
            if (draft.hasContent) saved + (id to draft) else saved - id
        }
    }

    /** Conversations that were deleted (or are being deleted): the drawer stops listing them immediately instead of waiting for the delete to finish. */
    val _deleted = MutableStateFlow<Set<String>>(emptySet())

    /** Those whose deletion has not finished yet. */
    val deleting = mutableSetOf<String>()

    /** Result of the most recent branch workspace copy (over the limit, target already has an inbox, etc.); call [clearBranchCopy] once the UI has shown the hint. */
    val _branchCopy = MutableStateFlow<BranchCopyResult?>(null)
    val parked = mutableMapOf<String, Parked>()
    val parkedJobs = mutableMapOf<String, Job>()

    /** The switch is re-read when there is no task, and also when a task starts or the conversation changes ([refreshParallel]): toggling it mid-task here does not change this turn's behavior. */
    var parallelOn = parallelFlag()

    fun parallel(): Boolean {
        if (liveIndex < 0 && activeConversations.value.isEmpty()) parallelOn = parallelFlag()
        return parallelOn
    }

    /**
     * Re-reads the switch when a task starts or the conversation changes (as long as this screen is not following a
     * task): a switch just changed in settings takes effect without recreating the screen, matching the service, which
     * re-reads it every time a task starts. Tasks in other conversations keep running as before, following in the service
     * the switch each of them had when it started.
     */
    fun refreshParallel() {
        if (liveIndex < 0) parallelOn = parallelFlag()
    }

    /** Position of the task being followed within exchanges; -1 means none. */
    var liveIndex = -1
    var lingerJob: Job? = null

    /** A start queued behind the service's cleanup that has not been handed to the service yet; tapping stop during this window discards it outright. */
    var pendingStart: Job? = null

    /** The task being followed opened the screen; once it has, picture-in-picture is shown until the task ends (including the few seconds it lingers after finishing). */
    var screenSeen = false

    /** The user closed the small window of the task being followed (cleared when switching tasks, see [PipUi.closed]). */
    var pipClosed = false

    fun attachmentUri(attachment: SharedDraftAttachment): String? =
        runCatching { sharedDraftUri(attachment) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun sharedAttachments(draft: SharedDraft): List<PendingAttachment> = draft.attachments.mapNotNull { attachment ->
        attachmentUri(attachment)?.let { PendingAttachment(it, attachment.name, attachment.size, attachment.photo) }
    }

    /** A turn that was stopped: marked "organizing" while the summary is still being written; cleared once it is written or has failed. */
    fun withSummarizing(ex: Exchange, set: Set<String> = summarizing.value): Exchange {
        val st = ex.status as? ExchangeStatus.Cancelled ?: return ex
        val on = st.summary == null && ex.runId != null && ex.runId in set
        return if (st.summarizing == on) ex else ex.copy(status = st.copy(summarizing = on))
    }

    /** Whether the items the service exposes (takeover progress, whether there is a screen, exported files) currently belong to the task of the conversation on screen: in parallel mode this checks which one is being shown. */
    fun ownTask() = !parallel() || shownTask.value?.first == _ui.value.conversationId

    fun withNotes(ex: Exchange, notes: Map<String, RunNotes>): Exchange {
        val n = ex.runId?.let(notes::get) ?: return ex
        return ex.copy(memoryNotes = n.memoryNotes, usedMemory = n.usedMemory, skillNotes = n.skillNotes)
    }

    fun canAttach(ui: ChatUi) = ui.attachEnabled && !ui.importing &&
        (ui.composer == ComposerMode.Idle || ui.followUpMode == FollowUpMode.Queue)

    /**
     * When the small window is shown: the screen must have been opened (the other fields are still computed and are
     * used directly at the moment the screen opens). When the user has closed this task's small window, only the small
     * button remains and the window no longer expands automatically (it also does not pop open when a takeover starts;
     * whatever needs your attention is in the chat card, and the small button turns orange).
     */
    fun gated(p: PipUi) = when {
        !screenSeen -> p.copy(visible = false, expanded = false, closed = false)
        pipClosed -> p.copy(expanded = false, closed = true)
        else -> p.copy(closed = false)
    }

    /** Steers added while the task with the original wording has not been handed to the service yet. */
    val held = mutableListOf<SteerNote>()

    /** Turns saved for this conversation, together with each turn's exported files and the current memory and skill notes. */
    fun saved(conversationId: String): List<Exchange> =
        runCatching { store.records(conversationId) }.getOrDefault(emptyList()).map { (t, files) -> withSummarizing(withNotes(fromTurn(t).copy(files = files), runNotes.value)) }

    /** Project id saved for this conversation; falls back to null when the project does not exist. */
    fun projectFor(conversationId: String?): String? =
        conversationId?.let { store.projectId(it) }?.takeIf { projectStore == null || projectStore.get(it) != null }

    /**
     * Tells the floating pill which conversation the user is in right now ([CurrentChat]): the pill's idle card continues
     * from that instead of guessing from whoever last wrote to the conversation files.
     * Failing to write does not affect the UI.
     */
    fun markCurrent(current: CurrentChat) {
        runCatching { store.setCurrent(current) }
    }

    /** Progress and result of one batch delete; the drawer shows it at the bottom, and [clearDeleteBatch] is called once the hint is shown. */
    val _deleteBatch = MutableStateFlow<DeleteBatch?>(null)

    fun busy() = _ui.value.composer != ComposerMode.Idle
}

/**
 * A conversation switched away from in parallel mode while its task is still running: its question-answer (original
 * wording, criteria, steps, files) and whether the screen was opened are all kept, steps keep updating in the background
 * from that task's own state, and switching back reconnects it as-is. Dropped when the task ends (the service saves that
 * turn into the conversation).
 */
internal class Parked(val exchange: Exchange, val screenSeen: Boolean, val pipClosed: Boolean = false)
