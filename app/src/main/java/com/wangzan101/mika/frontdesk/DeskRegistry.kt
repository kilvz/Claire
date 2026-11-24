package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.frontdesk.DESK_LINE_MAX_CHARS
import com.wangzan101.mika.core.frontdesk.DeskBackground
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskTaskEntry
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskWords
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.core.frontdesk.deskBackground
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * One resident front desk per conversation, lazily created by conversation id: on creation it reattaches tasks
 * previously dispatched, from the `deskTasks` recorded in the conversation.
 * Each entry point (chat page, voice call, …) gets the same front desk, its tool executor, and the background for each
 * model call from here; when a conversation is deleted its front desk is torn down too.
 * Each front desk runs in its own [SupervisorJob] scope, so a coroutine failure in one does not affect the others.
 */
class DeskRegistry(
    private val conversations: ConversationStore,
    private val tasks: DeskTaskPort,
    private val sink: DeskSink,
    private val memory: suspend (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    context: CoroutineContext = Dispatchers.Default,
) {
    constructor(app: MikaApp) : this(app, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private constructor(app: MikaApp, scope: CoroutineScope) : this(
        app.conversations,
        AppDeskTaskPort(
            app.conversations,
            app.taskState.runs.map { list -> list.map { TaskRunDesk(app, it) } },
            { app.taskState.runs.value.map { TaskRunDesk(app, it) } },
            { words, child, brief -> TaskService.start(app, words, child, brief = brief) },
            // The limit the task service computes and publishes each time it decides to start or queue; both sides read the same number.
            { app.taskState.parallelLimit.value.max },
            scope,
            { tr(R.string.desk_task_not_started) },
            { tr(R.string.task_interrupted) },
        ),
        DeskNotifier(app.conversations) { DeskNotifier.post(app, it) },
        AppDeskMemory(
            { app.memory.settings().switches.value.useMemory },
            { app.memory.store().let { it.reloadIfNeeded(); it.items.value } },
        )::recall,
    )

    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + context)

    private class Desk(val desk: FrontDesk, val ledger: DeskLedger, val scope: CoroutineScope)

    private val desks = HashMap<String, Desk>()

    init {
        scope.launch { conversations.deletions.collect(::forget) }
    }

    /** This conversation's front desk; created on first use, reattaching the tasks recorded in the conversation. */
    fun desk(conversationId: String): FrontDesk = entry(conversationId).desk

    /**
     * A front desk tool executor for one entry point: shares the ledger with the front desk, and dispatched tasks are
     * followed by that front desk.
     * [words] is supplied by the entry point (verifies this really is something the user said at this entry point and
     * hands back the whole sentence it belongs to).
     */
    fun executor(conversationId: String, words: DeskWords): DeskToolExecutor {
        val entry = entry(conversationId)
        return DeskToolExecutor(tasks, entry.ledger, words, clock, conversationId) { entry.desk.track(it.taskId, it.title) }
    }

    /** Background prepared for the front desk before each model call: this conversation's task board and memories related to this utterance. */
    fun background(conversationId: String): DeskBackground =
        deskBackground({ desk(conversationId).active.value }, memory)

    /**
     * When run [runKey] ends with [end], is there a front desk currently following this run that will speak this
     * ending (in the chat if the page is open, otherwise via a fallback notification)? If so the front desk is the only
     * voice and the task no longer posts its own "task complete".
     * Not counted: another run opened directly in the child conversation (the port is not following it), the front desk
     * conversation was deleted (the front desk is gone), not reattached after a process restart, cancelled by the user
     * (the front desk stays silent), or not yet ended.
     */
    fun reportsEnding(runKey: String, end: TaskState): Boolean {
        if (end !is TaskState.Terminal || !ReportPolicy.saysEnding(end)) return false
        val taskId = tasks.followedTaskOf(runKey) ?: return false
        return synchronized(desks) { desks.values.any { it.desk.tracks(taskId) } }
    }

    /** The conversation was deleted: tear down its front desk (leaving backend tasks alone). */
    fun forget(conversationId: String) {
        val removed = synchronized(desks) { desks.remove(conversationId) } ?: return
        removed.scope.cancel()
    }

    private fun entry(conversationId: String): Desk {
        synchronized(desks) { desks[conversationId]?.let { return it } }
        val restored = restoredEntries(conversationId)
        val entry = synchronized(desks) {
            // If two entry points ask at the same time, keep only the one inserted first.
            desks[conversationId]?.let { return it }
            val deskScope = CoroutineScope(scope.coroutineContext + SupervisorJob(root))
            val ledger = DeskLedger()
            Desk(FrontDesk(conversationId, deskScope, tasks, ledger, ReportPolicy(clock), sink, clock), ledger, deskScope)
                .also { desks[conversationId] = it }
        }
        // Reattaching needs to read child conversations, so do not hold the lock; reattaching is repeatable, and an entry point that already got the front desk is unaffected.
        entry.desk.restore(restored)
        return entry
    }

    /**
     * Tasks recorded in the conversation that the front desk dispatched, in order of first appearance. The title comes
     * from the child conversation's title, else the child's first sentence, else what the user said on the dispatch turn;
     * the original words come from the child's first sentence.
     */
    private fun restoredEntries(conversationId: String): List<DeskTaskEntry> {
        val firstSeen = LinkedHashMap<String, com.wangzan101.mika.core.agent.Turn>()
        for (turn in conversations.turns(conversationId)) for (id in turn.deskTasks) firstSeen.putIfAbsent(id, turn)
        return firstSeen.map { (child, parentTurn) ->
            val first = conversations.turns(child).firstOrNull()
            val words = first?.userText.orEmpty()
            val title = (conversations.title(child) ?: words.ifBlank { parentTurn.userText }).takeWhole(DESK_LINE_MAX_CHARS)
            DeskTaskEntry(child, title, words, first?.atMs ?: parentTurn.atMs ?: 0L)
        }
    }

}

/**
 * Add a line to a running task through the local steer path: accepted if the inbox took it (carried with the next step)
 * or if it was turned into the next item after the task ends; not accepted only if the task has stopped, the line became
 * invalid, or it was never recorded. Does not use remote append: that requires the task to have an MCP external id,
 * which child tasks dispatched by the front desk do not have.
 */
internal fun steerLocally(words: String, steer: (text: String, messageId: String) -> Unit, notes: () -> List<SteerNote>): Boolean {
    val id = UUID.randomUUID().toString()
    steer(words, id)
    return when (notes().firstOrNull { it.id == id }?.state) {
        null, SteerState.Cancelled, SteerState.Withdrawn -> false
        else -> true
    }
}

/** A task in the task service, for the front desk port. */
private class TaskRunDesk(private val app: MikaApp, private val run: TaskRun) : DeskRun {
    override val conversationId: String get() = run.conversationId
    override val key: String get() = run.key
    override val state: StateFlow<TaskState> get() = run.state
    override fun steer(words: String): Boolean = steerLocally(words, run::steer) { run.steers.value }
    override fun answer(words: String) = TaskService.answer(app, words, run.key)
    override fun cancel() = TaskService.cancel(app, run.key)
}
