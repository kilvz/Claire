package com.wangzan101.mika.acceptance

import com.wangzan101.mika.chat.ui.ChatUi
import com.wangzan101.mika.chat.ui.ExchangeStatus
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Watches the chat page's desk from the side during on-device acceptance (read-only, no changes): when each desk turn (a sentence the user sends, or a report the desk says) appears,
 * when its first character comes out, when it dispatches its first task, and when it finishes; what it said, what it dispatched, and the tool chips at the top;
 * the task board (the one shown by the cards and the "in progress" bar) records a line on every change. All times are milliseconds from [clock].
 */
internal class DeskWatch(private val ui: StateFlow<ChatUi>, private val note: (String) -> Unit, private val clock: () -> Long) {
    /** A desk turn: an empty [goal] means a report spoken by the desk. */
    class Turn(val runId: String, val goal: String, val seenAt: Long) {
        @Volatile var firstWordAt: Long? = null
        @Volatile var firstTaskAt: Long? = null
        @Volatile var doneAt: Long? = null
        @Volatile var reply: String = ""
        @Volatile var tasks: List<String> = emptyList()
        @Volatile var tool: String? = null
        @Volatile var status: String = "working"
        val isReport get() = goal.isEmpty()

        fun describe(sentAt: Long? = null): String {
            val base = sentAt ?: seenAt
            fun ms(t: Long?) = t?.let { (it - base).toString() } ?: "-"
            return "firstWordMs=${ms(firstWordAt)} firstTaskMs=${ms(firstTaskAt)} doneMs=${ms(doneAt)} status=$status " +
                "tasks=${tasks.joinToString(",")} tool=${tool ?: "-"} reply=${reply.replace('\n', ' ').take(300)}"
        }
    }

    private val turns = LinkedHashMap<String, Turn>()
    private var board: List<DeskTaskView> = emptyList()
    private var job: Job? = null

    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
        job = scope.launch { ui.collect(::observe) }
    }

    fun stop() {
        job?.cancel()
    }

    /** The turns recorded so far, in order of appearance. */
    fun turns(): List<Turn> = synchronized(turns) { turns.values.toList() }

    /** The task board at this moment. */
    fun board(): List<DeskTaskView> = synchronized(turns) { board }

    /** The turn where the user said [text] that appeared after [after]. */
    fun turnFor(text: String, after: Set<String>): Turn? = turns().firstOrNull { it.runId !in after && it.goal == text }

    private fun observe(state: ChatUi) = synchronized(turns) {
        val now = clock()
        for (ex in state.exchanges) {
            val runId = ex.runId?.takeIf { it.startsWith(DeskTurn.RUN_PREFIX) } ?: continue
            val turn = turns.getOrPut(runId) { Turn(runId, ex.goal, now).also { note("desk turn ${if (it.isReport) "report" else "user"} $runId goal=${ex.goal.take(80)}") } }
            val reply = ex.deskReply.orEmpty()
            if (reply.isNotBlank() && turn.firstWordAt == null) turn.firstWordAt = now
            if (ex.deskTasks.isNotEmpty() && turn.firstTaskAt == null) turn.firstTaskAt = now
            turn.reply = reply
            turn.tasks = ex.deskTasks
            turn.tool = ex.deskTool
            val status = when (val s = ex.status) {
                ExchangeStatus.Working -> "working"
                is ExchangeStatus.Done -> "done"
                is ExchangeStatus.Failed -> "failed(${s.reason})"
                else -> s::class.simpleName.orEmpty()
            }
            if (status != turn.status) {
                turn.status = status
                if (status != "working" && turn.doneAt == null) {
                    turn.doneAt = now
                    note("desk turn $runId ended: ${turn.describe()}")
                }
            }
        }
        if (state.deskTasks != board) {
            board = state.deskTasks
            note("board " + board.joinToString(" ; ") { "${it.taskId} ${it.state}${it.position?.let { p -> "#$p" } ?: ""} ${it.title.take(40)} | ${it.line.orEmpty().take(60)}" })
        }
    }
}
