package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.cap.sandbox.AttachmentFailure
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.ChatReduce.state
import com.wangzan101.mika.chat.ui.ChatReduce.withAttachments

/** Sending: delegates to the front desk, answers, starts tasks with attachments, quotes, plus task buttons such as the queue row, stop, and takeover, and picture-in-picture. */
internal class ChatSendOps(private val vm: ChatViewModel, private val session: ChatSession) {
    private val _ui = session._ui
    private val libraryImports = session.libraryImports
    private var sharedDraftSerial by session::sharedDraftSerial
    private val _deleted = session._deleted
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val scope = vm.scope
    private val actions = vm.actions
    private val store = vm.store
    private val serviceBusy = vm.serviceBusy
    private val activeConversations = vm.activeConversations
    private val importAttachments = vm.importAttachments
    private val clock = vm.clock
    private val branchWorkspace = vm.branchWorkspace
    private val sharedDrafts = vm.sharedDrafts
    private val teaching = vm.teaching
    private val onTeachingBlocked = vm.onTeachingBlocked
    private val projectStore = vm.projectStore
    private val workspaceResolver = vm.workspaceResolver

    /** "Quote" chosen on a long-press: the quoted content goes above the input field and is sent with the next message (carried by new tasks, steers and answers alike). Nothing is quoted when empty. */
    fun quote(from: QuoteMessage.From, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) {
            session.draftConversation()
            _ui.update { it.copy(quote = QuoteMessage.Quote(from, t)) }
        }
    }

    fun clearQuote() = _ui.update { it.copy(quote = null) }

    /**
     * Send the words in the input bar; when there is a quote, it is written before the words (see [QuoteMessage]) and cleared after sending. Returns false when nothing was sent (a condition was not met).
     * Routing in order: to the call when it is waiting for the owner's reply; as an answer when a task is waiting for one; as a new task the front desk follows when there are attachments (bypassing the front desk model);
     * everything else goes to this conversation's front desk (it can still be sent while the front desk is replying, without becoming "task running"). Nothing is sent while teaching the user.
     * A child-task conversation dispatched by the front desk does not go through the front desk (see [ChatDesk.handles]): with a task running the words are interjected into it, when idle a new one starts, and with attachments they open along with the new task.
     */
    fun send(text: String, check: Prerequisites.Check): Boolean {
        if (teaching()) {
            onTeachingBlocked()
            return false
        }
        session.refreshParallel()
        val ui = _ui.value
        if (ui.importing) return false
        val routed = route(text.trim(), ui, check)
        if (routed == Routed.Sent) clearSent(ui, text)
        return routed != Routed.NotSent
    }

    private enum class Routed { NotSent, Sent, Importing }

    private fun route(words: String, ui: ChatUi, check: Prerequisites.Check): Routed {
        val goal = QuoteMessage.compose(ui.quote, words)
        val desk = vm.deskFor(ui.conversationId)
        // A call is waiting for the owner's reply: these words are the reply, so hand them to the call (it records them into this conversation); with attachments they still go to the call first and the attachments stay in the input bar
        if (words.isNotEmpty() && ui.conversationId?.let { vm.ownerAnswer(it, goal) } == true) return Routed.Sent
        // A task is waiting for an answer: these words are the answer
        if (words.isNotEmpty() && ui.composer == ComposerMode.Answer) {
            act({ actions.answer(goal) }) { actions.answerIn(it, goal) }
            return Routed.Sent
        }
        // With attachments: copy them into this conversation's workspace first, then dispatch a new task the front desk follows (the files are copied into the child conversation along with it), whether or not a task is running;
        // without a front desk they start directly with the new task as before, and are not sent while a task is running
        if (ui.attachments.isNotEmpty()) {
            if (desk == null && ui.composer != ComposerMode.Idle) return Routed.NotSent
            return if (sendWithAttachments(goal, ui.attachments, check)) Routed.Importing else Routed.NotSent
        }
        // A quote with no words is not sent
        if (words.isEmpty()) return Routed.NotSent
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return Routed.NotSent
        }
        val id = ui.conversationId ?: store.newConversation()
        if (desk != null) {
            claim(id)
            return if (desk.line(id).send(goal)) Routed.Sent else Routed.NotSent
        }
        // No front desk: with a task running the words are interjected into it (without opening another), when idle a new task starts as before
        if (ui.composer == ComposerMode.Busy) return if (steer(id, goal)) Routed.Sent else Routed.NotSent
        if (ui.composer != ComposerMode.Idle) return Routed.NotSent
        begin(goal, id)
        return Routed.Sent
    }

    /**
     * Interject words into the task running in this conversation: hang them under the current question-and-answer (shown first as "pending delivery") and hand them to the service.
     * Nothing is sent while the task is still waiting for the previous one to yield the service (it has not really started); the words stay in the input bar.
     */
    private fun steer(id: String, text: String): Boolean {
        if (liveIndex !in _ui.value.exchanges.indices || pendingStart?.isActive == true) return false
        val note = SteerNote(java.util.UUID.randomUUID().toString(), text, SteerState.Pending)
        _ui.update { ui ->
            if (liveIndex !in ui.exchanges.indices) return@update ui
            val exchanges = ui.exchanges.toMutableList()
            exchanges[liveIndex] = exchanges[liveIndex].let { it.copy(steers = it.steers + note) }
            ui.copy(exchanges = exchanges)
        }
        actions.steer(id, note.id, text)
        return true
    }

    /** Sent: clear this message and the quote from the input bar; a system-shared draft with no attachments is dropped entirely, while one with attachments only has its text cleared. */
    private fun clearSent(ui: ChatUi, text: String) {
        val shared = ui.sharedDraft
        if (shared != null) {
            if (shared.attachments.isEmpty()) {
                vm.drafting.discardSharedDraft(shared)
            } else {
                val textCleared = shared.copy(text = "")
                sharedDrafts?.save(textCleared)
                _ui.update { if (it.sharedDraft == shared) it.copy(sharedDraft = textCleared) else it }
            }
        }
        _ui.update {
            it.copy(input = if (it.input == text) "" else it.input, quote = null)
        }
    }

    /** The id for this conversation is settled: bind the project chosen when the draft was attached and tell the floating capsule it is in this conversation now (a new conversation only gets an id at this point). */
    private fun claim(id: String) {
        val boundProject = (_ui.value.projectId ?: store.projectId(id))?.takeIf { projectStore == null || projectStore.get(it) != null }
        if (boundProject != null && store.projectId(id) != boundProject) {
            runCatching { store.setProject(id, boundProject) }
        }
        workspaceResolver?.setDraftProject(id, null)
        session.markCurrent(CurrentChat.Open(id))
    }

    /**
     * Sending with attachments: copy the attachments into the conversation workspace's inbox/ first (the input bar shows they are being added meanwhile),
     * and once all copies succeed, write each attachment as a line "Attachment: <path> (<size>)" after the words and hand it to the front desk to dispatch as a new task
     * (see [DeskChatOps.sendFiles]); without a front desk, start the task in this conversation as before.
     * If some attachments were not accepted, keep the draft and list the failures so the user can retry or remove them before sending again.
     * If the conversation changes or another task starts during the copy, nothing is sent (the copied files stay in the original conversation's workspace).
     */
    private fun sendWithAttachments(text: String, items: List<PendingAttachment>, check: Prerequisites.Check): Boolean {
        if (!check.canStartTask) {
            _ui.update { it.copy(blocked = check.blockers) }
            return false
        }
        val id = session.draftConversation()
        val submitted = _ui.value.draft()
        val serialBefore = sharedDraftSerial
        val importingFileNames = submitted.sharedDraft?.attachments?.map { it.fileName }.orEmpty()
        sharedDrafts?.setImporting(importingFileNames, true)
        val importedUris = items.map { it.uri }.distinct()
        importedUris.forEach { uri -> libraryImports[uri] = (libraryImports[uri] ?: 0) + 1 }
        _ui.update { it.copy(importing = true, rejectedAttachments = emptyList(), overLimit = 0) }
        scope.launch {
            val r = try {
                val workspaceItems = items.filter { it.workspacePath != null }
                val checks = if (workspaceItems.all { it.workspaceConversationId == id }) {
                    branchWorkspace?.checkInboxAttachments(id, workspaceItems.map { it.workspacePath!! }).orEmpty()
                } else emptyList()
                if (workspaceItems.isEmpty()) {
                    importAttachments(id, items.map { it.uri })
                } else if (checks.size != workspaceItems.size || checks.any { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null }) {
                    ImportResult(emptyList(), workspaceItems.map { it.name })
                } else {
                    var checked = 0
                    val added = mutableListOf<Pair<String, Long>>()
                    val rejected = mutableListOf<String>()
                    val failures = mutableListOf<AttachmentFailure>()
                    for (item in items) {
                        if (item.workspacePath != null) {
                            val result = checks[checked++]
                            added += result.path to result.sizeBytes!!
                        } else {
                            val result = importAttachments(id, listOf(item.uri))
                            added += result.added
                            rejected += result.rejected
                            failures += result.failures
                        }
                    }
                    ImportResult(added, rejected, failures)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportResult(emptyList(), items.map { it.name })
            } finally {
                sharedDrafts?.setImporting(importingFileNames, false)
                importedUris.forEach { uri ->
                    val count = (libraryImports[uri] ?: 1) - 1
                    if (count == 0) libraryImports.remove(uri) else libraryImports[uri] = count
                }
                session.releaseUnusedLibraryCopies()
            }
            session.updateDraft(id) { it.copy(importing = false, rejected = r.failures) }
            if (r.rejected.isNotEmpty()) return@launch
            val goal = withAttachments(text, r.added)
            val now = _ui.value
            if (goal.isEmpty() || now.conversationId != id || id in _deleted.value) return@launch
            val currentAttachments = now.attachments
            val draftReplaced = sharedDraftSerial != serialBefore
            val keepNewerAttachments = draftReplaced || currentAttachments != submitted.attachments
            val desk = vm.deskFor(id)
            // With a front desk, whether a task is running does not matter; without one, as before: if another task started during the copy nothing is sent, keeping the draft and attachments
            val accepted = desk != null || now.composer == ComposerMode.Idle
            if (accepted && desk != null) {
                if (!keepNewerAttachments) vm.drafting.discardSharedDraft()
                claim(id)
                desk.line(id).sendFiles(text, goal, r.added, deskTitle(text, items))
            } else if (accepted) {
                begin(goal, id, clearDraftAttachments = !keepNewerAttachments)
            }
            if (accepted) {
                if (!draftReplaced) vm.drafting.discardSharedDraft()
                _ui.update {
                    it.copy(
                        input = if (!draftReplaced && it.input == submitted.input) "" else it.input,
                        quote = if (!draftReplaced && it.quote == submitted.quote) null else it.quote,
                        attachments = if (keepNewerAttachments) currentAttachments else emptyList(),
                    )
                }
                session.releaseUnusedLibraryCopies()
            }
        }
        return true
    }

    /** The title on the task card for an attachment send: the user's words (without the quote); when only files were sent, the file names. */
    private fun deskTitle(text: String, items: List<PendingAttachment>): String =
        QuoteMessage.words(text).trim().ifBlank { items.joinToString("、") { it.name } }

    /** Start a new task [goal] in conversation [id]: add a question-and-answer to the UI and hand it to the service (queued behind the current one when parallelism is off and the service is busy). */
    private fun begin(goal: String, id: String, clearDraftAttachments: Boolean = true) {
        lingerJob?.cancel()
        // Once the task has really started, the draft attachments are already in the workspace, so the private staging copies can be cleared.
        if (clearDraftAttachments) vm.drafting.discardSharedDraft()
        claim(id)
        // When the previous task wrapped up it cleared the screen before reporting the end, so what is read here is the state before the new task started;
        // in parallel mode that is another conversation's task screen, and the new task has no screen yet
        // A new message must not inherit the previous run's screen; once this run's screen is really created, screenOpen opens the small window.
        screenSeen = false
        pipClosed = false
        _ui.update {
            liveIndex = it.exchanges.size
            val nextAttachments = if (clearDraftAttachments) emptyList() else it.attachments
            it.copy(
                conversationId = id,
                exchanges = it.exchanges + Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, startedAt = clock()),
                blocked = null,
                attachments = nextAttachments,
                pip = session.gated(PipUi(visible = true)),
                composer = ComposerMode.Busy,
                // If this message was already shown optimistically on the queue row and has now started, it must not be shown again as a row
                queued = it.queued.filterNot { q -> q.text == goal },
            )
        }
        // In parallel mode, hand it over directly even if the service is busy (another conversation's task is running)
        if (serviceBusy.value && !session.parallel()) {
            // During the wait, spell out what it is waiting for so it does not look stuck; once handed to the service, the task's own state continues the line
            _ui.update { ui ->
                val exchanges = ui.exchanges.toMutableList()
                exchanges[liveIndex] = exchanges[liveIndex].copy(line = tr(R.string.chat_wait_previous_task))
                ui.copy(exchanges = exchanges)
            }
            // By the time it is handed over, something elsewhere (a schedule, the computer) may have just started first: the service queues it behind that one, so it is not lost
            pendingStart = scope.launch {
                serviceBusy.first { !it }
                pendingStart = null
                actions.start(goal, id)
                // Queued items were persisted long ago; the service takes the original-words task out in FIFO order when it wraps up, so do not rewrite or jump the queue here
            }
        } else {
            actions.start(goal, id)
        }
    }

    /** When no task is running, start the first queued item ("start now" on the queue row). */
    fun startQueued() {
        val id = _ui.value.conversationId ?: return
        actions.drainFollowUps(id)
    }

    /** Cancels one persistent queued item that has not started; returns false once its turn has come. */
    fun cancelQueued(id: String): Boolean {
        val conversationId = _ui.value.conversationId ?: return false
        if (!actions.cancelFollowUp(conversationId, id)) return false
        _ui.update { it.copy(queued = it.queued.filterNot { q -> q.id == id }) }
        return true
    }

    /**
     * Pulls back a queued item: cancel it first, and only on success put the text back into the input field (the quote is
     * parsed as usual). Ones carrying screen/page markers can only be cancelled, not edited.
     */
    fun editQueued(id: String): String? {
        val ui = _ui.value
        if (ui.input.isNotBlank() || ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val item = ui.queued.firstOrNull { it.id == id } ?: return null
        if (!editableSteer(item.text)) return null
        if (!cancelQueued(id)) return null
        val parsed = QuoteMessage.parse(item.text)
        _ui.update { it.copy(input = parsed.words, quote = parsed.quote) }
        return parsed.words
    }

    fun withdrawSteer(messageId: String): Boolean {
        val ui = _ui.value
        val id = ui.conversationId ?: return false
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return false
        if (!note.state.unconsumed || note.remote) return false
        if (!actions.withdrawSteer(id, messageId)) return false
        _ui.update { now -> now.copy(exchanges = now.exchanges.map { ex ->
            ex.copy(steers = ex.steers.map { if (it.id == messageId) it.copy(state = SteerState.Withdrawn) else it })
        }) }
        return true
    }

    fun editSteer(messageId: String): String? {
        val ui = _ui.value
        if (ui.input.isNotBlank() || ui.quote != null || ui.attachments.isNotEmpty() || ui.importing) return null
        val note = ui.exchanges.getOrNull(liveIndex)?.steers?.firstOrNull { it.id == messageId } ?: return null
        if (!editableSteer(note.text) || !withdrawSteer(messageId)) return null
        val parsed = QuoteMessage.parse(note.text)
        _ui.update { it.copy(input = parsed.words, quote = parsed.quote) }
        return parsed.words
    }

    fun dismissBlocked() = _ui.update { it.copy(blocked = null) }

    /** Stop: discards the task together with any steers not yet handed to it (those steers are no longer treated as the next item). */
    fun cancel() {
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].let { ex ->
                ex.copy(steers = ex.steers.map { if (it.state.unconsumed) it.copy(state = SteerState.Cancelled) else it })
            }
            ui.copy(exchanges = exchanges)
        }
        val pending = pendingStart
        if (pending == null || !pending.isActive) {
            // If this conversation has a task in the service (running or queued), stop only that one: a stop without an id
            // applies to the service's shown task, and if this one is still queued behind a task started elsewhere, that task would be stopped instead
            val id = _ui.value.conversationId
            if (id != null && id in activeConversations.value) actions.cancelIn(id) else act({ actions.cancel() }) { actions.cancelIn(it) }
            return
        }
        // The task has not been handed to the service yet: discard this start; what is wrapping up in the service is the previous task, so leave it alone
        pending.cancel()
        pendingStart = null
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(status = ExchangeStatus.Cancelled())
            // The explicitly queued next item has its own persistent lifecycle; cancelling the start of the original wording does not delete it.
            ui.copy(exchanges = exchanges, pip = PipUi(), composer = ComposerMode.Idle)
        }
        liveIndex = -1
    }

    fun resume() = act({ actions.resume() }) { actions.resumeIn(it) }

    fun takeOver() = act({ actions.takeOver() }) { actions.takeOverIn(it) }

    fun handBack() = act({ actions.handBack() }) { actions.handBackIn(it) }

    fun finishedMyself() = act({ actions.finishedMyself() }) { actions.finishedMyselfIn(it) }

    fun skip() = act({ actions.skip() }) { actions.skipIn(it) }

    fun answer(text: String) = act({ actions.answer(text) }) { actions.answerIn(it, text) }

    fun approve(key: Int) = act({ actions.approve(key) }) { actions.approveIn(it, key) }

    fun decline(key: Int) = act({ actions.decline(key) }) { actions.declineIn(it, key) }

    /**
     * In-app buttons: with the switch off they act on the service's shown task (M1); in parallel mode they carry the
     * conversation that was on screen at the moment of the press and act only on its task, and nothing is sent if that
     * conversation has no task.
     */
    private inline fun act(serial: () -> Unit, scoped: (String) -> Unit) {
        if (!session.parallel()) return serial()
        val id = _ui.value.conversationId ?: return
        if (id in activeConversations.value) scoped(id)
    }

    fun expandPip() = _ui.update { if (it.pip.visible) it.copy(pip = it.pip.copy(expanded = true)) else it }

    /** Tapping picture-in-picture when it needs you: collapse it and let the UI scroll to the card that needs you. */
    fun attendPip() = collapsePip()

    fun collapsePip() = _ui.update { it.copy(pip = it.pip.copy(expanded = false)) }

    /** The user closes this task's small window: only the small button in the bottom-right is left, and the window does not pop up on its own again until the task ends. */
    fun closePip() {
        if (liveIndex < 0) return
        pipClosed = true
        _ui.update { it.copy(pip = session.gated(it.pip)) }
    }

    /** Tapping the small button: reopens this task's small window. */
    fun reopenPip() {
        pipClosed = false
        _ui.update { it.copy(pip = session.gated(it.pip)) }
    }

    /** The overlay's sound toggle: it only sends a real audio action and the direction is determined by the real record in the task rather than faking the toggle state in the UI. */
    fun togglePipSound() {
        val id = _ui.value.conversationId ?: return
        actions.togglePipSoundIn(id)
    }

    /** Before a share switches away, hand the original task that is still waiting for the serial service to become free over to the service's queue; only the waiting coroutine in the UI is cancelled, not the task. */
    fun handOffPendingStart() {
        val pending = pendingStart?.takeIf { it.isActive } ?: return
        val id = _ui.value.conversationId
        val live = _ui.value.exchanges.getOrNull(liveIndex)
        pendingStart = null
        pending.cancel()
        if (id == null || live == null) return
        actions.start(live.goal, id)
    }
}
