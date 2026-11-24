package com.wangzan101.mika.chat.ui
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.unconsumed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.wangzan101.mika.chat.ui.ChatReduce.actionsOf
import com.wangzan101.mika.chat.ui.ChatReduce.active
import com.wangzan101.mika.chat.ui.ChatReduce.needsUser
import com.wangzan101.mika.chat.ui.ChatReduce.reduce
import com.wangzan101.mika.chat.ui.ChatReduce.rows
import com.wangzan101.mika.chat.ui.ChatReduce.state
import com.wangzan101.mika.settings.ui.models.refresh

/** Follows the task of the conversation on screen: state, steps, files and where steers went; parked aside when switching away and reattached when switching back. */
internal class ChatLiveFollow(private val vm: ChatViewModel, private val session: ChatSession) {
    private var autoReadKey by session::autoReadKey
    private val _ui = session._ui
    private val _deleted = session._deleted
    private val parked = session.parked
    private val parkedJobs = session.parkedJobs
    private var liveIndex by session::liveIndex
    private var lingerJob by session::lingerJob
    private var pendingStart by session::pendingStart
    private var screenSeen by session::screenSeen
    private var pipClosed by session::pipClosed
    private val scope = vm.scope
    private val takeoverStage = vm.takeoverStage
    private val actions = vm.actions
    private val appLabel = vm.appLabel
    private val screenOpen = vm.screenOpen
    private val endedRunId = vm.endedRunId
    private val liveRunId = vm.liveRunId
    private val endedRunIds = vm.endedRunIds
    private val shownTask = vm.shownTask
    private val activeConversations = vm.activeConversations
    private val runGoal = vm.runGoal
    private val runState = vm.runState
    private val runNotes = vm.runNotes
    private val sharedFiles = vm.sharedFiles
    private val generatedAudios = vm.generatedAudios
    private val generatedPhotos = vm.generatedPhotos
    private val toolLabels = vm.toolLabels
    private val activities = vm.activities
    private val steers = vm.steers
    private val clock = vm.clock
    private val autoRead = vm.autoRead

    private fun maybeAutoRead(ex: Exchange) {
        if (!autoRead.value) return
        val status = ex.status as? ExchangeStatus.Done ?: return
        val key = ex.runId ?: "${ex.startedAt}-${status.answer.hashCode()}"
        if (key == autoReadKey) return
        autoReadKey = key
        vm.speak(status.answer)
    }

    /** Parks the question-and-answer pair being followed for the conversation on screen (called before switching to another conversation). */
    fun parkLive() {
        val ui = _ui.value
        val id = ui.conversationId ?: return
        val live = ui.exchanges.getOrNull(liveIndex) ?: return
        if (!live.status.active() || (id !in activeConversations.value && pendingStart?.isActive != true)) return
        parked[id] = Parked(live, screenSeen, pipClosed)
        parkedJobs.remove(id)?.cancel()
        val flow = runState(id) ?: return
        parkedJobs[id] = scope.launch {
            flow.collect { s ->
                if (!s.active()) {
                    if (s != TaskState.Idle) { parked.remove(id); parkedJobs.remove(id)?.cancel() }
                    return@collect
                }
                parked[id]?.let { p -> parked[id] = Parked(reduce(p.exchange, s, appLabel, toolLabels::label), p.screenSeen, p.pipClosed) }
            }
        }
    }

    fun unpark(id: String): Parked? {
        parkedJobs.remove(id)?.cancel()
        return parked.remove(id)
    }

    fun onActivities(list: List<RunEvent.Activity>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) {
                val ex = exchanges[liveIndex].copy(activities = list)
                exchanges[liveIndex] = ex.copy(
                    steps = rows(ex, toolLabels::label, appLabel),
                    stepActions = actionsOf(list, appLabel, toolLabels::label),
                )
            }
            ui.copy(exchanges = exchanges)
        }
    }

    /**
     * Steer destinations are updated by id on the question-and-answer pair they belong to (after a task wraps up that may no longer be the one being followed).
     * Text steered into the followed task from elsewhere (the floating pill) that is not here yet is attached under the pair being followed.
     */
    fun onSteers(list: List<SteerNote>) {
        val byId = list.associateBy { it.id }
        _ui.update { ui ->
            val shown = ui.exchanges.flatMap { ex -> ex.steers.map { it.id } }.toSet()
            val added = list.filter { it.id !in shown }
            ui.copy(exchanges = ui.exchanges.mapIndexed { i, ex ->
                val updated = if (ex.steers.none { it.id in byId }) ex
                    else ex.copy(steers = ex.steers.map { n -> byId[n.id]?.let { n.copy(state = it.state, interrupted = it.interrupted ?: n.interrupted, finished = it.finished ?: n.finished) } ?: n })
                if (i == liveIndex && added.isNotEmpty() && ex.status.active()) updated.copy(steers = updated.steers + added) else updated
            })
        }
    }

    fun onAudios(audios: List<com.wangzan101.mika.core.chat.GeneratedAudio>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(audios = audios)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onSharedFiles(files: List<SharedFile>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(files = files)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onPhotos(photos: List<com.wangzan101.mika.core.chat.GeneratedPhoto>) {
        if (liveIndex < 0) return
        _ui.update { ui ->
            val exchanges = ui.exchanges.toMutableList()
            if (liveIndex in exchanges.indices) exchanges[liveIndex] = exchanges[liveIndex].copy(photos = photos)
            ui.copy(exchanges = exchanges)
        }
    }

    fun onScreenOpened() {
        if (liveIndex < 0 || screenSeen) return
        screenSeen = true
        _ui.update { ui ->
            val live = ui.exchanges.getOrNull(liveIndex)
            if (live != null && live.status.active()) ui.copy(pip = session.gated(ui.pip.copy(visible = true))) else ui
        }
    }

    /**
     * Accepts the state only of the conversation on screen's task; when the UI has just started with no conversation yet, attaches the running one (along with the turns saved for that conversation).
     * When the service switches to showing another conversation's task (one just started elsewhere), the followed task here is switched back to if it is still running, so it does not stall halfway.
     */
    fun onShown(conversationId: String, s: TaskState) {
        // A deleted conversation's task is stopping: do not attach it to the screen
        if (conversationId in _deleted.value) return
        val current = _ui.value.conversationId
        when {
            current == null -> {
                if (!s.active()) return
                _ui.update {
                    it.copy(
                        conversationId = conversationId,
                        exchanges = session.saved(conversationId),
                        projectId = session.projectFor(conversationId),
                    )
                }
            }
            current != conversationId -> {
                val live = _ui.value.exchanges.getOrNull(liveIndex)
                if (live != null && live.status.active() && current in activeConversations.value && pendingStart?.isActive != true) actions.show(current)
                return
            }
        }
        onState(s)
    }

    /**
     * Attaches to the running task of the conversation on screen (no matter where it started: here, the floating pill, a scheduled job). When not following yet, first set up the question-and-answer pair
     * (the parked [p] from switching away, or a new one from the task's original message), then make the service display it, and fill in from the service's current outward values.
     */
    fun follow(conversationId: String, p: Parked? = null) {
        val fresh = liveIndex < 0
        if (fresh) {
            // Set up this conversation's question-and-answer pair first, then make the service display it: as soon as the service switches, the state may arrive right away (onShown),
            // and by then this pair must already exist, otherwise a new one without the original message would be created
            val live = p?.exchange ?: Exchange(runGoal(conversationId).orEmpty(), emptyList(), ExchangeStatus.Working, live = true, startedAt = clock())
            screenSeen = p?.screenSeen ?: false
            pipClosed = p?.pipClosed ?: false
            _ui.update {
                liveIndex = it.exchanges.size
                it.copy(exchanges = it.exchanges + live, composer = ComposerMode.Busy, pip = session.gated(PipUi(visible = true)),
                    queued = it.queued.filterNot { q -> q.text == live.goal })
            }
        }
        actions.show(conversationId)
        val shown = shownTask.value?.takeIf { it.first == conversationId } ?: return
        // Do not end the followed pair with a finished state from the service (it may belong to the previous task)
        if (!fresh && !shown.second.active()) return
        // These outward values of the service now all belong to this task: fill in from them (state flows only emit on change, and switching over may not re-emit)
        if (!screenSeen && screenOpen.value) screenSeen = true
        _ui.update { it.copy(takeoverStage = takeoverStage.value) }
        onSharedFiles(sharedFiles.value)
        onAudios(generatedAudios.value)
        onPhotos(generatedPhotos.value)
        onActivities(activities.value)
        onSteers(steers.value)
        onState(shown.second)
    }

    /**
     * When the conversation page becomes visible again (back to the foreground, returning from another page): turns saved into the conversation on screen from elsewhere are filled in;
     * if that conversation has a running task (started from the floating pill or a scheduled job) that is not being followed here, or the service switched to showing another task, attach to it.
     */
    fun refresh() {
        session.refreshParallel()
        val id = _ui.value.conversationId ?: return
        syncSaved(id)
        if (id !in activeConversations.value) { actions.drainFollowUps(id); return }
        if (pendingStart?.isActive == true) return
        // With the setting off and no task followed here, switching conversations waits for the task to end: then the conversation on screen's task is the only one running in the service
        follow(id, if (session.parallel()) unpark(id) else null)
    }

    /**
     * The stored version of this conversation changed (a turn was saved from elsewhere, or one was edited): re-lay out the screen from the stored copy. Turns that were followed here are matched by run id
     * (by original message when there is no id), the copy from here (with steps and elapsed time) is used instead of duplicating them; turns not saved yet are appended; the followed pair stays last.
     */
    fun syncSaved(conversationId: String) {
        val stored = session.saved(conversationId)
        _ui.update { ui ->
            if (ui.conversationId != conversationId) return@update ui
            val live = ui.exchanges.getOrNull(liveIndex)
            val tracked = ui.exchanges.filterIndexed { i, ex -> ex.live && i != liveIndex }.toMutableList()
            // Match from the most recent backwards: when the same message was said several times, the most recent one is matched
            val merged = stored.asReversed().map { s ->
                val mine = tracked.lastOrNull { if (s.runId != null) it.runId == s.runId else it.runId == null && it.goal == s.goal }
                    ?: return@map s
                tracked.remove(mine)
                // The explanation added after a stop is recorded in the stored copy
                val m = if (mine.status is ExchangeStatus.Cancelled && s.status is ExchangeStatus.Cancelled && s.status.summary != null) mine.copy(status = s.status) else mine
                // The kept-page record (the site kept, the reason it was later closed) also exists only in the stored copy
                val k = if (s.keptPage != null) m.copy(keptPage = s.keptPage, keptPageClosed = s.keptPageClosed) else m
                if (k.goal.isEmpty()) k.copy(goal = s.goal) else k
            }.asReversed()
            val exchanges = merged + tracked + listOfNotNull(live)
            if (exchanges == ui.exchanges) return@update ui
            liveIndex = if (live != null) exchanges.lastIndex else -1
            ui.copy(exchanges = exchanges)
        }
    }

    fun onState(s: TaskState) {
        if (liveIndex < 0) {
            // The task did not start here (e.g. it was already running before the UI was recreated): keep showing it, just without the user's message
            if (!s.active()) return
            screenSeen = screenOpen.value
            pipClosed = false
            _ui.update {
                liveIndex = it.exchanges.size
                // The original message and files are taken by conversation: this state belongs to the conversation on screen's task
                val goal = it.conversationId?.let(runGoal).orEmpty()
                val files = if (session.ownTask()) sharedFiles.value else emptyList()
                val audios = if (session.ownTask()) generatedAudios.value else emptyList()
                val photos = if (session.ownTask()) generatedPhotos.value else emptyList()
                val ex = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, files = files, audios = audios, photos = photos, startedAt = clock())
                it.copy(exchanges = it.exchanges + ex,
                    queued = it.queued.filterNot { q -> q.text == goal })
            }
        }
        _ui.update { ui ->
            val old = ui.exchanges[liveIndex]
            val ex = reduce(withGoal(old, ui.conversationId), s, appLabel, toolLabels::label).let {
                if (it.status.active() || !old.status.active()) {
                    // Attach this conversation's task id while running so run details are available right away; the finishing moment still takes the branch below
                    val live = if (it.status.active() && it.runId == null) ui.conversationId?.let(liveRunId) else null
                    if (live != null) it.copy(runId = live) else it
                }
                // A steer not yet handed to the task at wrap-up: the service treats it as the conversation's next item
                else session.withSummarizing(session.withNotes(it.copy(runId = endedRunFor(ui.conversationId), elapsedMs = elapsedOf(old),
                    steers = it.steers.map { n -> if (n.state.unconsumed) n.copy(state = SteerState.Next) else n }), runNotes.value))
            }
            val exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = ex }
            ui.copy(
                exchanges = exchanges,
                pip = session.gated(pipFor(ui.pip, old.status, ex)),
                composer = when {
                    ex.status is ExchangeStatus.NeedsInput -> ComposerMode.Answer
                    ex.status.active() -> ComposerMode.Busy
                    else -> ComposerMode.Idle
                },
            )
        }
        _ui.value.exchanges.getOrNull(liveIndex)?.let(::maybeAutoRead)
        if (!s.active() && s != TaskState.Idle) {
            liveIndex = -1
            // This turn may already have been saved into the conversation and added to the screen: merge it by run id
            _ui.value.conversationId?.let(::syncSaved)
            if (s is TaskState.Succeeded) {
                lingerJob = scope.launch {
                    delay(ChatViewModel.DONE_LINGER_MS)
                    _ui.update { it.copy(pip = it.pip.copy(visible = false, expanded = false)) }
                }
            }
        }
    }

    /** The followed pair has no original message yet (it was not registered when attaching to a task started elsewhere): fill it in from the conversation on screen's task. */
    fun fillGoal() = _ui.update { ui ->
        val ex = ui.exchanges.getOrNull(liveIndex) ?: return@update ui
        val filled = withGoal(ex, ui.conversationId)
        if (filled === ex) ui else ui.copy(exchanges = ui.exchanges.toMutableList().also { it[liveIndex] = filled })
    }

    private fun withGoal(ex: Exchange, conversationId: String?): Exchange {
        if (ex.goal.isNotEmpty() || !ex.status.active()) return ex
        return conversationId?.let(runGoal)?.takeIf(String::isNotEmpty)?.let { ex.copy(goal = it) } ?: ex
    }

    private fun elapsedOf(ex: Exchange): Long? = ex.startedAt.takeIf { it > 0 }?.let { (clock() - it).coerceAtLeast(0) }

    private fun endedRunFor(conversationId: String?): String? {
        // With no conversation id (attaching to a task that was already running before the UI was recreated), only the currently shown one can be used
        val byConversation = endedRunIds ?: return endedRunId.value
        return if (conversationId == null) endedRunId.value else byConversation.value[conversationId]
    }

    private fun pipFor(old: PipUi, before: ExchangeStatus, ex: Exchange): PipUi {
        val st = ex.status
        return when (st) {
            is ExchangeStatus.Done -> PipUi(true, PipMode.Done, false)
            is ExchangeStatus.Failed, is ExchangeStatus.Cancelled -> PipUi()
            else -> {
                val needs = st.needsUser()
                // Expand automatically only when a takeover just started (a question or pause must keep the composer and card buttons in view); collapse when the takeover ends;
                // do not pop open again within the same takeover after the user collapsed it
                val takeover = st is ExchangeStatus.NeedsTakeover
                val wasTakeover = before is ExchangeStatus.NeedsTakeover
                val expanded = when {
                    takeover && !wasTakeover -> true
                    !takeover && wasTakeover -> false
                    else -> old.expanded
                }
                PipUi(true, if (needs) PipMode.NeedsYou else PipMode.Running, expanded)
            }
        }
    }
}
