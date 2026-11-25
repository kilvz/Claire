package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.agent.UserWords
import com.wangzan101.mika.core.frontdesk.DESK_RESULT_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskEvent
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DeskWords
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.frontdesk.DeskNotifier
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Problem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The three things the chat page needs to attach to the front desk, wired up by the app and replaced with fakes in tests. */
class DeskChatDeps(
    /** This conversation's persistent front desk; the first request reads the conversation record, so call it on io. */
    val desk: (conversationId: String) -> FrontDesk,
    /** The front desk tool executor used by the chat page; [DeskWords] is supplied by the chat page (checks that the fragment really was typed by the user in this conversation and returns the whole message it came from). */
    val executor: (conversationId: String, words: DeskWords) -> DeskToolExecutor,
    /**
     * Build a fresh text front desk for this conversation from the current model in settings; null when no model is configured (the send is blocked and the user is led to model settings).
     * A new one is built for every turn (reply, report), so a model changed in settings takes effect on the next message.
     */
    val brain: (conversationId: String) -> DeskBrain?,
    /** Generate a title from the first message for a conversation that has no title and no stored turn yet (see [com.wangzan101.mika.chat.ConversationTitler.ensure]). */
    val title: suspend (conversationId: String, first: String) -> Unit = { _, _ -> },
)

/**
 * The layer between the chat page and the front desk, following the conversation on screen: it receives this conversation's task board (the cards and the "In progress" row read it);
 * while the page is open it attaches this conversation's front desk and background reports are delivered here; when the page is not open it does not attach and reports go to the fallback (write back to the conversation, send a notification), with no extra throttling here.
 */
internal class ChatDesk(private val vm: ChatViewModel, private val session: ChatSession, private val deps: DeskChatDeps) {
    private val _ui = session._ui
    private val lines = HashMap<String, DeskChatOps>()
    private val shown = MutableStateFlow(false)
    private val children = ConcurrentHashMap<String, Boolean>()

    /** Whether the chat page is on screen (the UI reports it by lifecycle); defaults to false, so reports go to the fallback. */
    fun pageShown(value: Boolean) {
        shown.value = value
    }

    /** The page is open, and this is the conversation on screen. */
    fun showing(conversationId: String) = shown.value && _ui.value.conversationId == conversationId

    /**
     * This conversation is handled by the front desk. Child-task conversations dispatched by the front desk ([com.wangzan101.mika.chat.ConversationStore.deskParent]) do not get a front desk of their own:
     * words typed there still go directly to their task (interjected while running, answered while waiting, starting a new one when idle). A new conversation without an id is handled by the front desk.
     * Whether it is a child conversation is settled when it is dispatched, so this is computed once and remembered.
     */
    fun handles(conversationId: String?): Boolean =
        conversationId == null || !children.getOrPut(conversationId) { vm.store.deskParent(conversationId) != null }

    /** The front desk entry for this conversation; always the same one for a given conversation (it remembers what this conversation's front desk has said). */
    fun line(conversationId: String): DeskChatOps = synchronized(lines) {
        lines.getOrPut(conversationId) { DeskChatOps(conversationId, vm, session, deps, this) }
    }

    /** An option was tapped on a question card: the words go straight back to task [taskId] dispatched by the conversation on screen, bypassing the front desk model. */
    fun answer(taskId: String, words: String) {
        val id = _ui.value.conversationId ?: return
        vm.scope.launch(vm.io) { deps.desk(id).answer(taskId, words) }
    }

    /** Stop was tapped on the progress row or a question card: stop task [taskId] dispatched by the conversation on screen. */
    fun cancel(taskId: String) {
        val id = _ui.value.conversationId ?: return
        vm.scope.launch(vm.io) { deps.desk(id).cancel(taskId) }
    }

    /** 输入框的停止键:停下屏上这条会话里前台还没回完的话,和它派出去、还在跑的每一件。 */
    fun stopAll() {
        val id = _ui.value.conversationId ?: return
        line(id).stopReplies()
        _ui.value.deskTasks.forEach { cancel(it.taskId) }
    }

    fun start() {
        vm.scope.launch {
            combine(_ui.map { it.conversationId }.distinctUntilChanged(), shown) { id, s -> id to s }
                .distinctUntilChanged()
                .collectLatest { (id, s) -> if (id != null) follow(id, s) }
        }
        // The conversation was deleted: drop its front desk entry too (the front desk itself is released by the front desk registry; background tasks are untouched)
        vm.scope.launch { vm.store.deletions.collect { id -> synchronized(lines) { lines.remove(id) }; children.remove(id) } }
    }

    private suspend fun follow(id: String, attach: Boolean) = coroutineScope {
        // Child-task conversations get no front desk and are not attached
        if (!withContext(vm.io) { handles(id) }) return@coroutineScope
        val desk = withContext(vm.io) { deps.desk(id) }
        val surface = line(id)
        var handle: AutoCloseable? = null
        try {
            // Attach the front desk only while the page is open; do not steal it while another entry point (such as a call) has it attached, and attach again once it disconnects and frees up
            if (attach) launch { desk.attached.collect { if (it == null) handle = desk.attach(surface) } }
            // Combine with the UI state: when the page is rebuilt after switching conversations the task board is cleared, so refill it even if the id has not changed
            combine(desk.active, _ui) { views, _ -> views }.collect { views ->
                _ui.update { if (it.conversationId == id && it.deskTasks != views) it.copy(deskTasks = views) else it }
            }
        } finally {
            handle?.close()
        }
    }
}

/**
 * The front desk of one conversation on the chat page: the user's typed words go to this conversation's text front desk, and its reply streams turn by turn into that question-and-answer ([Exchange.deskReply]),
 * with dispatched tasks hanging as cards under the same turn ([Exchange.deskTasks]); every turn is stored as a turn with `status = desk` carrying the tasks dispatched in it.
 * When the backend has something worth saying and the page is showing this conversation, the front desk says it and it is stored as a `desk_report` row; when the page is not open this returns false and the fallback takes over.
 *
 * Replies and reports go one at a time (the next only after the previous reply finishes): the input bar can still send while the front desk is replying, and what is sent queues behind it, so dispatches are not lost to interruptions.
 * Words the front desk hands to the backend are verified: they only count if the user said them in this conversation (in this message or an earlier one, whole or in part), and the whole message is what the backend receives;
 * cancellation only recognizes the message the user is currently replying to. A report turn was not opened by the user, so the front desk's tools never accept words there (it can only tell the user about things).
 *
 * The front desk follows the conversation: every turn builds a fresh front desk from the current model, seeded with the turns stored for this conversation (the same path as opening a call),
 * so turns written during a call, reports written by the fallback while the page was away, and a model changed in settings are all taken into account on the next message; words said during a call also count as the user's own words.
 * A message with attachments bypasses the front desk model and is dispatched directly as a new task ([sendFiles]).
 */
internal class DeskChatOps(
    private val conversationId: String,
    private val vm: ChatViewModel,
    session: ChatSession,
    private val deps: DeskChatDeps,
    private val page: ChatDesk,
) : DeskSurface {
    private val _ui = session._ui
    private val turns = Mutex()
    private var executor: DeskToolExecutor? = null

    /** The executor used for a report turn: it accepts no words at all (see [DeskWords.None]). */
    private var reportExecutor: DeskToolExecutor? = null

    /** Words the user has said in this conversation: the stored ones (re-read before every turn, including those said during calls) and those sent since this opening. */
    private val said = CopyOnWriteArrayList<String>()

    /** The message the front desk is currently replying to (the part the user said themselves); null when not replying. Cancellation only recognizes it. */
    @Volatile private var current: String? = null

    /** 还没回完的几句(在回的和排在后面的);输入框的停止键一起停掉。 */
    private val replies = java.util.concurrent.ConcurrentHashMap.newKeySet<kotlinx.coroutines.Job>()

    /** 停下前台还没回完的话:在回的这句停在已说的地方,排着的不再回。 */
    fun stopReplies() { replies.toList().forEach { it.cancel() } }

    /** 起标题这件事这条入口做过没有。 */
    @Volatile private var named = false

    /** The word check the chat page gives the executor: which of the user's messages the fragment falls in (the current reply first, then newest to oldest), returning that whole message. */
    private val words = object : DeskWords {
        override fun said(fragment: String): String? {
            val w = fragment.trim().ifEmpty { return null }
            current?.takeIf { w in it }?.let { return it }
            return said.lastOrNull { w in it }
        }

        override fun saidNow(fragment: String): String? {
            val w = fragment.trim().ifEmpty { return null }
            return current?.takeIf { w in it }
        }
    }

    /**
     * Hand [text] (with the quote already included) to the front desk: immediately add a user bubble and "thinking", and the reply streams in from the background.
     * When no model is configured nothing is sent; the UI blocks it and points to model settings, returning false.
     */
    fun send(text: String): Boolean {
        val brain = deps.brain(conversationId)
        if (brain == null) {
            _ui.update { it.copy(blocked = listOf(Problem.NoModel)) }
            return false
        }
        remember(text)
        name(text)
        val runId = newRunId()
        val startedAt = vm.clock()
        val row = Exchange(text, emptyList(), ExchangeStatus.Working, live = true, runId = runId, startedAt = startedAt, deskReply = "")
        _ui.update {
            if (it.conversationId != null && it.conversationId != conversationId) it
            else it.copy(conversationId = conversationId, exchanges = it.exchanges + row, blocked = null)
        }
        val job = vm.scope.launch { turns.withLock { answer(brain, text, runId, startedAt) } }
        replies += job
        job.invokeOnCompletion { cause ->
            replies -= job
            // 停在排队里、还没开始回的一句:这一轮也收尾,不一直转圈
            if (cause != null) update(runId) { if (it.status == ExchangeStatus.Working) it.copy(status = ExchangeStatus.Done(it.deskReply.orEmpty(), null)) else it }
        }
        return true
    }

    /**
     * A message sent with attachments: [goal] is [text] plus the file lines, and the files are already in this conversation's workspace (the bubble and "ask again" read from here).
     * It bypasses the front desk model (the front desk's tools do not take files) and is dispatched directly as a new background task: the files are copied into its child conversation and the card hangs under this turn.
     * Whether or not a task is running it works the same, and the input bar does not become busy because of it. [title] is the title on the card.
     */
    fun sendFiles(text: String, goal: String, files: List<Pair<String, Long>>, title: String) {
        val typed = remember(text)
        name(text)
        val runId = newRunId()
        val startedAt = vm.clock()
        val row = Exchange(goal, emptyList(), ExchangeStatus.Working, live = true, runId = runId, startedAt = startedAt, deskReply = "")
        _ui.update {
            if (it.conversationId != null && it.conversationId != conversationId) it
            else it.copy(conversationId = conversationId, exchanges = it.exchanges + row, blocked = null)
        }
        vm.scope.launch {
            val result = try {
                executor(words).dispatchWithFiles(typed, title) { child -> copyFiles(child, files)?.let { ChatReduce.withAttachments(text, it) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val status = result?.string("status")
            val taskId = result?.string("task_id")?.takeIf { status == "started" || status == "queued" }
            val failure = if (taskId == null) result?.string("reason") ?: status ?: "dispatch_error" else null
            val tasks = listOfNotNull(taskId)
            update(runId) { it.copy(deskTasks = tasks, status = failure?.let(ExchangeStatus::Failed) ?: ExchangeStatus.Done("", null)) }
            // Store it on the same line as front desk replies so re-reading the conversation keeps the order
            turns.withLock { persist(Turn(goal, "", DeskTurn.STATUS, note = failure, runId = runId, atMs = startedAt, deskTasks = tasks)) }
        }
    }

    /** Copy the files from this conversation's workspace into child conversation [child]'s workspace; if any copy fails, nothing is dispatched (returns null). */
    private suspend fun copyFiles(child: String, files: List<Pair<String, Long>>): List<Pair<String, Long>>? {
        val workspace = vm.branchWorkspace ?: return null
        val copied = workspace.copyInboxForRetry(conversationId, child, files.map { it.first })
        if (copied.size != files.size || copied.any { it.status != RetryAttachmentStatus.COPIED || it.sizeBytes == null }) return null
        return copied.map { it.path to it.sizeBytes!! }
    }

    /**
     * Say a report while the page is showing this conversation. Starting, queuing, and progress can be seen live in the progress row, and needing you shows a question card in the conversation, so none of them get a spoken line;
     * on success the complete result text from the backend is written verbatim as a report line, without the front desk model paraphrasing it; on failure the front desk says it (reason and next step), also written as a report line.
     * Returns false when the page is not open, no model is configured, or the front desk said nothing (the fallback takes over); if the front desk fails, the line is removed and the error is rethrown to the fallback.
     */
    override suspend fun deliver(report: DeskReport): Boolean {
        if (!page.showing(conversationId)) return false
        when (val event = report.event) {
            is DeskEvent.Started, is DeskEvent.Queued, is DeskEvent.Progress,
            is DeskEvent.NeedsAnswer, is DeskEvent.NeedsAction -> return true
            is DeskEvent.Succeeded -> return turns.withLock { result(event.taskId, DeskNotifier.rowText(report).orEmpty()) }
            else -> Unit
        }
        val brain = deps.brain(conversationId) ?: return false
        return turns.withLock {
            if (!page.showing(conversationId)) return@withLock false
            val taskId = report.event.taskId
            val runId = newRunId()
            val startedAt = vm.clock()
            _ui.update {
                if (it.conversationId != conversationId) it
                else it.copy(exchanges = it.exchanges + Exchange("", emptyList(), ExchangeStatus.Working, live = true, runId = runId,
                    startedAt = startedAt, deskReply = "", deskTasks = listOf(taskId)))
            }
            val reply = StringBuilder()
            val tasks = mutableListOf(taskId)
            try {
                prepare(brain)
                brain.notice(noticeText(report), context(runId, reply, tasks, executor(DeskWords.None)))
            } catch (e: Throwable) {
                remove(runId)
                throw e
            }
            val text = reply.toString()
            if (text.isBlank() && tasks.size == 1) {
                remove(runId)
                return@withLock false
            }
            update(runId) { it.copy(deskReply = text, status = ExchangeStatus.Done(text, null)) }
            persist(Turn("", text, DeskTurn.REPORT_STATUS, runId = runId, atMs = startedAt, deskTasks = tasks))
            true
        }
    }

    /** Write the verbatim result text of a success as a report line (skipped if the page has switched away, returning false to the fallback). */
    private suspend fun result(taskId: String, text: String): Boolean {
        if (!page.showing(conversationId) || text.isBlank()) return false
        val runId = newRunId()
        val at = vm.clock()
        _ui.update {
            if (it.conversationId != conversationId) it
            else it.copy(exchanges = it.exchanges + Exchange("", emptyList(), ExchangeStatus.Done(text, null), live = true, runId = runId,
                startedAt = at, deskReply = text, deskTasks = listOf(taskId)))
        }
        persist(Turn("", text, DeskTurn.REPORT_STATUS, runId = runId, atMs = at, deskTasks = listOf(taskId)))
        return true
    }

    private suspend fun answer(brain: DeskBrain, text: String, runId: String, startedAt: Long) {
        val reply = StringBuilder()
        val tasks = mutableListOf<String>()
        var failure: String? = null
        try {
            prepare(brain)
            current = UserWords.saidToDesk(text).ifBlank { null }
            brain.reply(text, context(runId, reply, tasks, executor(words)))
        } catch (e: CancellationException) {
            current = null
            // 被停下:已经说出来的留着,这一轮收尾
            val spoken = reply.toString()
            update(runId) { it.copy(deskReply = spoken, status = ExchangeStatus.Done(spoken, null)) }
            // 派出去的任务得记进这一轮,下次打开会话才接得回来
            withContext(NonCancellable) { persist(Turn(text, reply.toString(), DeskTurn.STATUS, runId = runId, atMs = startedAt, deskTasks = tasks)) }
            throw e
        } catch (e: Exception) {
            failure = e.message ?: e::class.simpleName ?: "error"
        }
        current = null
        val spoken = reply.toString()
        update(runId) {
            it.copy(deskReply = spoken, status = failure?.let(ExchangeStatus::Failed) ?: ExchangeStatus.Done(spoken, null))
        }
        persist(Turn(text, spoken, DeskTurn.STATUS, note = failure, runId = runId, atMs = startedAt, deskTasks = tasks))
    }

    private fun context(runId: String, reply: StringBuilder, tasks: MutableList<String>, exec: DeskToolExecutor) = object : ReplyContext {
        override suspend fun say(delta: String) {
            reply.append(delta)
            val now = reply.toString()
            update(runId) { it.copy(deskReply = now) }
        }

        override suspend fun callTool(name: String, arguments: JsonObject): JsonObject {
            val result = exec.execute(name, arguments)
            val status = result.string("status")
            val taskId = result.string("task_id")
            if (name == DeskTools.DISPATCH && taskId != null && (status == "started" || status == "queued")) {
                tasks += taskId
                update(runId) { it.copy(deskTasks = it.deskTasks + taskId) }
            } else if (name != DeskTools.DISPATCH && status == "ok") {
                toolLabel(name, arguments.string("task_id"))?.let { label -> update(runId) { it.copy(deskTool = label) } }
            }
            return result
        }
    }

    /**
     * Before every reply and report turn: re-read the turns stored for this conversation (including ones written by other entry points), seed them into the freshly built front desk, and record the words the user said among them.
     */
    private suspend fun prepare(brain: DeskBrain) {
        val prior = withContext(vm.io) { runCatching { vm.store.turns(conversationId) }.getOrDefault(emptyList()) }
        said.addAllAbsent(prior.map { UserWords.saidToDesk(it.userText) }.filter { it.isNotBlank() })
        brain.seed(prior)
    }

    /** The tool executor shared with the front desk: [check] is [words] for a turn the user opened, or [DeskWords.None] for a report turn; one is built for each. */
    private suspend fun executor(check: DeskWords): DeskToolExecutor {
        val report = check === DeskWords.None
        synchronized(this) { if (report) reportExecutor else executor }?.let { return it }
        val made = withContext(vm.io) { deps.executor(conversationId, check) }
        return synchronized(this) {
            if (report) reportExecutor ?: made.also { reportExecutor = it } else executor ?: made.also { executor = it }
        }
    }

    /**
     * Record what the user themselves said in this message ([UserWords.saidToDesk]): a quoted Claire answer, screen and page descriptions, and attachment lines are not recorded,
     * because the front desk cannot verify dispatches from them. Returns what was recorded (possibly empty).
     */
    private fun remember(text: String): String =
        UserWords.saidToDesk(text).also { if (it.isNotBlank()) said += it }

    /** A report to the front desk: it carries the task id (for continuing the conversation or adding notes) followed by what changed for this task. */
    private fun noticeText(report: DeskReport) =
        "task_id ${report.event.taskId} · " + tr(R.string.desk_report_line, report.title, DeskNotifier.line(report.event).take(DESK_RESULT_MAX_CHARS))

    /** The small tag on that row after the front desk adds a note to, replies to, or cancels an existing task. */
    private fun toolLabel(name: String, taskId: String?): String? {
        val title = _ui.value.deskTasks.firstOrNull { it.taskId == taskId }?.title.orEmpty()
        return when (name) {
            DeskTools.ADD -> tr(R.string.desk_tool_added, title)
            DeskTools.ANSWER -> tr(R.string.desk_tool_answered, title)
            DeskTools.CANCEL -> tr(R.string.desk_tool_cancelled, title)
            else -> null
        }
    }

    private fun update(runId: String, change: (Exchange) -> Exchange) = _ui.update { ui ->
        if (ui.conversationId != conversationId) ui
        else ui.copy(exchanges = ui.exchanges.map { if (it.runId == runId) change(it) else it })
    }

    private fun remove(runId: String) = _ui.update { ui ->
        if (ui.conversationId != conversationId) ui else ui.copy(exchanges = ui.exchanges.filterNot { it.runId == runId })
    }

    /**
     * The first message sent through this entry point: ask the model to title the conversation (ensure skips it itself when the conversation already has a title or stored turns).
     * It runs in parallel with the reply; "no turn stored yet" was already checked before this turn was persisted, and it is only tried once, so later messages do not trigger it.
     */
    private fun name(first: String) {
        if (named) return
        named = true
        vm.scope.launch(vm.io) {
            try {
                deps.title(conversationId, first)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // If no title can be generated, record nothing and keep using the first message in the UI
            }
        }
    }

    private suspend fun persist(turn: Turn) {
        withContext(vm.io) { runCatching { vm.store.append(conversationId, turn) } }
    }

    private fun newRunId() = DeskTurn.RUN_PREFIX + UUID.randomUUID()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        /** The run id prefix for a front desk turn (see [DeskTurn.RUN_PREFIX]): used only to identify this turn; there is no run record to view. */
        const val RUN_PREFIX = DeskTurn.RUN_PREFIX
    }
}
