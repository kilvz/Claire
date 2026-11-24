package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.DeleteImpact
import com.wangzan101.mika.chat.Project
import com.wangzan101.mika.chat.ProjectDeleteImpact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.settings.ui.models.refresh

/** Conversations and projects: open, switch, create, jump, delete, archive, rename, pin. */
internal class ChatConversationOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val _jump = session._jump
    private var jumpSerial by session::jumpSerial
    private val drafts = session.drafts
    private var draftStateChanged by session::draftStateChanged
    private val deviceImports = session.deviceImports
    private val _deleted = session._deleted
    private val deleting = session.deleting
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val held = session.held
    private val _deleteBatch = session._deleteBatch
    private val scope = vm.scope
    private val actions = vm.actions
    private val store = vm.store
    private val activeConversations = vm.activeConversations
    private val clock = vm.clock
    private val deletionImpact = vm.deletionImpact
    private val removeConversation = vm.removeConversation
    private val sharedDrafts = vm.sharedDrafts
    private val projectStore = vm.projectStore
    private val projectDeletionImpact = vm.projectDeletionImpact
    private val removeProject = vm.removeProject
    private val workspaceResolver = vm.workspaceResolver

    /**
     * Switch to a conversation (possible even while a task runs when parallel mode is on): load its history; when it has a running task, make the service display that task and attach to its current state.
     * When the setting is off, behaves like [openConversation].
     */
    fun switchTo(conversationId: String) {
        session.refreshParallel()
        if (!session.parallel()) return openConversation(conversationId)
        if (conversationId == _ui.value.conversationId) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        lingerJob?.cancel()
        if (conversationId != _ui.value.conversationId) vm.live.parkLive()
        val past = session.saved(conversationId)
        liveIndex = -1
        // A conversation without a task does not carry another task's takeover progress; the input restores only the target conversation's own draft.
        _ui.update { session.restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = session.projectFor(conversationId))) }
        session.markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { vm.live.unpark(conversationId); actions.drainFollowUps(conversationId); return }
        vm.live.follow(conversationId, vm.live.unpark(conversationId))
    }

    /** Open a conversation owned by the personal assistant or a workflow: switch the display only, do not start another job, and keep the user's draft. */
    fun openWorkflowConversation(conversationId: String) {
        if (conversationId == _ui.value.conversationId) { vm.live.refresh(); return }
        session.refreshParallel()
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        lingerJob?.cancel()
        vm.live.parkLive()
        val past = session.saved(conversationId)
        liveIndex = -1
        _ui.update { session.restoreDraft(it.rebuildForConversation(conversationId = conversationId, exchanges = past, projectId = session.projectFor(conversationId))) }
        session.markCurrent(CurrentChat.Open(conversationId))
        if (conversationId !in activeConversations.value) { vm.live.unpark(conversationId); return }
        vm.live.follow(conversationId, vm.live.unpark(conversationId))
    }

    fun openPersonalTask(conversationId: String) {
        openWorkflowConversation(conversationId)
    }

    /**
     * Open a conversation and jump to a given message. When already in this conversation, only the jump is set and nothing is reloaded;
     * when a task is running and parallel mode is off it cannot be opened and the jump is not kept either, so callers can check [ChatUi.conversationId] to see whether it succeeded.
     */
    fun openConversationAt(id: String, target: ChatTarget) {
        if (_ui.value.conversationId != id) openConversation(id)
        if (_ui.value.conversationId == id) _jump.value = ChatJump(id, target, ++jumpSerial)
    }

    /** The chat page clears the request once it has scrolled to the target; a newer request that arrived meanwhile must not be cleared by mistake. */
    fun consumeJump(jump: ChatJump) {
        _jump.update { if (it == jump) null else it }
    }

    /** Create a new project. */
    fun createProject(name: String): Result<Project>? = projectStore?.create(name)

    /** Rename a project. */
    fun renameProject(projectId: String, name: String): Result<Project>? = projectStore?.rename(projectId, name)

    fun projectDeleteImpact(projectId: String): ProjectDeleteImpact = projectDeletionImpact(projectId)

    /** Delete a project together with all its member conversations and its shared workspace `p-<projectId>`. */
    fun deleteProject(projectId: String) {
        val members = store.list(includeArchived = true).filter { it.projectId == projectId }.map { it.id }
        val draftMembers = drafts.value.filterValues { it.projectId == projectId }.keys
        val openMember = _ui.value.conversationId?.takeIf {
            _ui.value.projectId == projectId || store.projectId(it) == projectId
        }
        val targets = (members + draftMembers + listOfNotNull(openMember)).distinct().filter { deleting.add(it) }
        targets.forEach { id ->
            drafts.update { it - id }
            sharedDrafts?.clear(id)
            workspaceResolver?.setDraftProject(id, null)
            _deleted.update { it + id }
            if (id == _ui.value.conversationId) startOver(id) else vm.live.unpark(id)
        }
        if (_ui.value.conversationId == null && _ui.value.projectId == projectId) {
            _ui.update { it.copy(projectId = null) }
        }
        session.releaseUnusedLibraryCopies()
        scope.launch {
            try {
                removeProject(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _deleted.update { it - targets.toSet() }
            } finally {
                deleting.removeAll(targets.toSet())
            }
        }
    }

    /** Switch to a conversation; does not switch while a task is running. */
    fun openConversation(id: String) {
        if (_ui.value.conversationId == id) return
        session.refreshParallel()
        if (session.parallel()) return switchTo(id)
        if (session.busy()) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        val past = session.saved(id)
        liveIndex = -1
        _ui.update {
            session.restoreDraft(it.rebuildForConversation(conversationId = id, exchanges = past, takeoverStage = it.takeoverStage, projectId = session.projectFor(id)))
        }
        session.markCurrent(CurrentChat.Open(id))
        // This conversation's task was started elsewhere (floating pill, scheduled job): attach to it on open
        if (id in activeConversations.value) vm.live.follow(id) else {
            vm.live.unpark(id)
            actions.drainFollowUps(id)
        }
    }

    fun newConversation(projectId: String? = null) {
        val validProject = projectId?.takeIf { projectStore == null || projectStore.get(it) != null }
        session.refreshParallel()
        if (session.parallel()) {
            vm.drafting.cancelPendingSharedDraft()
            session.keepDraft()
            // Another task may still be running: give the new conversation an id first so its state is not taken for this conversation's
            lingerJob?.cancel()
            vm.live.parkLive()
            liveIndex = -1
            val fresh = store.newConversation()
            workspaceResolver?.setDraftProject(fresh, validProject)
            _ui.update { it.rebuildForConversation(conversationId = fresh, projectId = validProject) }
            // Nothing has been said yet: the pill shows a new-conversation card (the id is assigned once something is said, see begin)
            session.markCurrent(CurrentChat.Fresh)
            return
        }
        if (session.busy()) return
        vm.drafting.cancelPendingSharedDraft()
        session.keepDraft()
        liveIndex = -1
        _ui.update { it.rebuildForConversation(conversationId = null, takeoverStage = it.takeoverStage, projectId = validProject) }
        session.markCurrent(CurrentChat.Fresh)
    }

    /** Whether the conversation on screen has a running or queued task right now. */
    fun currentRunning(): Boolean = _ui.value.conversationId?.let { it in activeConversations.value } == true

    /** What deleting [id] entails: the drawer's confirmation dialog uses this to state that tasks are stopped and scheduled jobs deleted. */
    fun deleteImpact(id: String): DeleteImpact = deletionImpact(id)

    /**
     * Delete conversation [id] (confirmed by the user in the drawer): remove it from the drawer immediately; if it is the conversation on screen, replace it with a new empty conversation.
     * Stopping tasks, deleting scheduled jobs and deleting files happen in the background (see `ConversationDeleter`).
     */
    fun deleteConversation(id: String) {
        deleteConversations(listOf(id), report = false)
    }

    fun clearDeleteBatch() {
        _deleteBatch.value = null
    }

    /**
     * Delete [ids] all at once after the user multi-selects them in the drawer: remove them from the drawer immediately, then delete them one by one in the background.
     * During deletion [deleteBatch] records the total count, the number deleted and the number failed, which the drawer uses to show progress and results.
     */
    fun deleteConversations(ids: Collection<String>) {
        deleteConversations(ids, report = true)
    }

    private fun deleteConversations(ids: Collection<String>, report: Boolean) {
        val targets = ids.filter { deleting.add(it) }
        if (targets.isEmpty()) return
        targets.forEach { id ->
            drafts.update { it - id }
            sharedDrafts?.clear(id)
            _deleted.update { it + id }
            if (id == _ui.value.conversationId) startOver(id) else vm.live.unpark(id)
        }
        session.releaseUnusedLibraryCopies()
        if (report) _deleteBatch.value = DeleteBatch(targets.size, 0, 0)
        scope.launch {
            var done = 0
            var failed = 0
            // One that cannot be deleted (storage read/write failure, etc.) does not block the rest; the number of failures is reported truthfully at the end
            targets.forEach { id ->
                try {
                    deviceImports[id]?.join()
                    removeConversation(id)
                } catch (e: CancellationException) {
                    val unfinished = targets.drop(done).toSet()
                    deleting.removeAll(unfinished)
                    _deleted.update { it - unfinished }
                    throw e
                } catch (_: Exception) {
                    failed++
                    _deleted.update { it - id }
                }
                deleting -= id
                done++
                if (report) _deleteBatch.value = DeleteBatch(targets.size, done, failed)
            }
        }
    }

    /** The user renames conversation [id] in the drawer: set it to [title] (trimmed; empty leaves it unchanged). Later generated titles will no longer overwrite it. */
    fun renameConversation(id: String, title: String) {
        if (id in _deleted.value) return
        runCatching { store.rename(id, title) }
    }

    /** Pin or unpin conversation [id] in the drawer. */
    fun setPinned(id: String, pinned: Boolean) {
        if (id in _deleted.value) return
        runCatching { store.setPinned(id, pinned, clock()) }
    }

    /**
     * Archive conversation [id] in the drawer: collapsed from the list, while its content, workspace and scheduled jobs are untouched.
     * When the one archived is the conversation on screen, first make sure switching is possible now, then switch to a new empty conversation (the draft is kept and can be used again after unarchiving).
     * A conversation that is running or has a queued task is not archived and false is returned.
     */
    fun archiveConversation(id: String): Boolean {
        if (id in _deleted.value || id in deleting) return false
        if (id in activeConversations.value) return false
        val current = _ui.value.conversationId == id
        if (current) {
            session.refreshParallel()
            if (session.busy()) return false
        }
        val ok = runCatching { store.setArchived(id, true, clock()) }.getOrDefault(false)
        // Archive before switching: the drawer re-reads when switching to the new conversation, so the old one is already gone from the list
        if (ok && current) newConversation()
        return ok
    }

    /** Unarchive conversation [id] in the drawer; it returns to its previous place (a pinned one returns to the pinned section). */
    fun unarchiveConversation(id: String) {
        if (id in _deleted.value) return
        runCatching { store.setArchived(id, false) }
    }

    /** Multi-select restore in the archive view: unarchive one by one; already-deleted ones are skipped. */
    fun unarchiveConversations(ids: Collection<String>) {
        ids.filter { it !in _deleted.value }.forEach { runCatching { store.setArchived(it, false) } }
    }

    /**
     * Archive [ids] all at once after multi-selecting in the normal drawer: drafts whose first turn is unsaved, running ones, deleted ones and the one currently being viewed are skipped
     * (archiving the current conversation requires switching to a new one, which is not done silently in a batch). Returns how many were actually archived.
     */
    fun archiveConversations(ids: Collection<String>): Int {
        val current = _ui.value.conversationId
        // Matches the drawer's criterion for saved conversations; an ID alone is not enough: unsent drafts also have IDs.
        val savedIds = store.list(includeArchived = true).mapTo(mutableSetOf()) { it.id }
        return ids.count { id ->
            id in savedIds && id != current && id !in _deleted.value && id !in deleting && id !in activeConversations.value &&
                runCatching { store.setArchived(id, true, clock()) }.getOrDefault(false)
        }
    }

    /** The conversation on screen was deleted: stop following its task (a start not yet handed to the service is simply discarded) and switch to a new empty conversation. */
    private fun startOver(id: String) {
        draftStateChanged = true
        vm.drafting.cancelPendingSharedDraft()
        vm.drafting.discardSharedDraft()
        lingerJob?.cancel()
        pendingStart?.cancel()
        pendingStart = null
        held.clear()
        vm.live.unpark(id)
        liveIndex = -1
        screenSeen = false
        pipClosed = false
        val fresh = if (session.parallel()) store.newConversation() else null
        _ui.update { it.rebuildForConversation(conversationId = fresh) }
        // The screen switched to a new empty conversation: the pill no longer continues the deleted one
        session.markCurrent(CurrentChat.Fresh)
    }
}
