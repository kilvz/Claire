package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The phase of a task as the front desk sees it. */
enum class DeskTaskPhase { Running, Queued, NeedsYou, Done, Failed, Cancelled }

/** Maximum recent progress lines kept for running and needs-you tasks. */
const val DESK_RECENT_LINES = 3

/** Maximum steps kept for a task's whole life (shown when the progress strip is expanded); older ones are dropped. */
const val DESK_STEPS_MAX = 60

/** One step a task went through: [text] with details stripped and clipped, [atMs] when the step was first seen. */
data class DeskStep(val text: String, val atMs: Long)

/**
 * What a task is waiting for the user to do right now: when [action] is null it is asking a question ([prompt], with [options] as the choices when non-empty);
 * otherwise the user must act (confirm, take over, satisfy a prerequisite), and [prompt] is the task's explanation (possibly absent). [prompt] is kept verbatim, not stripped or clipped.
 */
data class DeskNeed(val prompt: String?, val options: List<String>? = null, val action: ActionKind? = null)

/**
 * A task as listed by the front desk: [line] is the current one-liner (details stripped), [position] the queue position when queued (starting at 1);
 * [recent] holds the latest distinct progress lines while running or needing you (details stripped, newest last, at most [DESK_RECENT_LINES]).
 * [goal] is the original words given to the background; [startedAt] when it started (null while queued); [steps] every step so far
 * (at most [DESK_STEPS_MAX], newest last); [need] is what it waits for while needing you; [updatedAt] is the last change.
 */
data class DeskTaskView(
    val taskId: String,
    val title: String,
    val state: DeskTaskPhase,
    val line: String?,
    val position: Int?,
    val recent: List<String> = emptyList(),
    val goal: String? = null,
    val startedAt: Long? = null,
    val steps: List<DeskStep> = emptyList(),
    val need: DeskNeed? = null,
    val updatedAt: Long = 0L,
)

/**
 * A front desk that lives with one conversation: subscribes to each task it dispatched and feeds changes worth saying (see [DeskEvents], [ReportPolicy])
 * into a queue in order of occurrence, delivering one at a time to the entry point currently attached ([DeskSurface]); when no entry point is online it goes to [DeskSink].
 * The front desk does not speak or hold a model itself: each entry point decides how to say it.
 *
 * Delivery rule: one at a time, the next only after the previous was delivered (the entry point finished speaking); if an entry point is interrupted mid-sentence, the report goes to the next entry point that attaches,
 * or to the sink when none does; each report is delivered exactly once.
 */
class FrontDesk(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val tasks: DeskTaskPort,
    private val ledger: DeskLedger,
    private val policy: ReportPolicy,
    private val sink: DeskSink,
    /** Clock used to time each step; tests swap in a fake. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val reports = Channel<DeskReport>(Channel.UNLIMITED)
    private val tracked = HashSet<String>()
    private val views = MutableStateFlow<List<DeskTaskView>>(emptyList())

    private val current = MutableStateFlow<DeskSurface?>(null)
    private val surface: DeskSurface? get() = current.value

    /** The entry point attached right now; null after it detaches. Other entry points can use this to attach when free instead of taking over one in use (such as a call). */
    val attached: StateFlow<DeskSurface?> = current.asStateFlow()

    /** Tasks the front desk dispatched in this conversation with their phases, in dispatch order; finished ones stay in the list marked with their final phase. */
    val active: StateFlow<List<DeskTaskView>> = views.asStateFlow()

    init {
        scope.launch { for (report in reports) deliver(report) }
    }

    /** Attach an entry point (replacing the current one); closing the returned handle detaches it and later reports go to the sink. */
    fun attach(surface: DeskSurface): AutoCloseable {
        current.value = surface
        return AutoCloseable { current.compareAndSet(surface, null) }
    }

    /** Start following a just-dispatched task (the dispatcher already recorded it in the ledger); each task is followed once and unknown ids are ignored. */
    fun track(taskId: String, title: String) {
        follow(taskId, title, report = true)
    }

    /** Whether this task is followed by this front desk (handed over at dispatch, or reconnected when the conversation reopened). */
    fun tracks(taskId: String): Boolean = synchronized(tracked) { taskId in tracked }

    /**
     * The user answers a task's question in the UI (taps an option on the question card); tasks not followed by this front desk are refused.
     * Returns whether the task accepted it.
     */
    fun answer(taskId: String, words: String): Boolean = tracks(taskId) && tasks.answer(taskId, words)

    /** The user tapped stop on this task in the UI; tasks not followed by this front desk are refused. Returns whether it stopped. */
    fun cancel(taskId: String): Boolean = tracks(taskId) && tasks.cancel(taskId)

    /**
     * Reconnect tasks dispatched earlier when the conversation reopens: restore the ledger, resubscribe to those still running and report only later changes;
     * finished ones are only marked with their final phase and not reported again.
     */
    fun restore(entries: List<DeskTaskEntry>) {
        for (entry in entries) {
            if (ledger.get(entry.taskId) == null) ledger.add(entry)
            follow(entry.taskId, entry.title, report = false)
        }
    }

    /** When [report] is false the current state only marks the phase and is not reported as a change (these were said before reconnecting). */
    private fun follow(taskId: String, title: String, report: Boolean) {
        val states = tasks.state(taskId) ?: return
        synchronized(tracked) { if (!tracked.add(taskId)) return }
        val current = states.value
        updateView(taskId, title, current)
        // Finished before reconnect: only mark the final phase; one that finished right after dispatch (failed fast) is still reported
        if (!report && current is TaskState.Terminal) return
        scope.launch {
            var previous: TaskState? = if (report) null else current
            // Only say "started" for tasks that were queued: one dispatched and already running was reported in the dispatch result, so saying it again repeats itself.
            var queued = phaseOf(taskId, current) == DeskTaskPhase.Queued
            // Stop subscribing after the final state arrives; later changes to the flow are ignored
            states.transformWhile { emit(it); it !is TaskState.Terminal }.collect { next ->
                val event = DeskEvents.classify(taskId, previous, next)
                previous = next
                updateView(taskId, title, next)
                val wasQueued = queued
                if (phaseOf(taskId, next) == DeskTaskPhase.Queued) queued = true
                // Even a "started" that is not said goes through the reporting rules, so the progress quiet period counts from the start and it does not report progress right away.
                val say = event != null && policy.shouldReport(event) && (event !is DeskEvent.Started || wasQueued)
                if (say) reports.trySend(DeskReport(event!!, title))
            }
        }
    }

    /** Just dispatched but not yet picked up by the task service (Idle): not in the start queue means it is starting, not queued (otherwise the card flashes "queued" and the front desk repeats it). */
    private fun phaseOf(taskId: String, state: TaskState): DeskTaskPhase =
        if (state == TaskState.Idle && tasks.queuePosition(taskId) == null) DeskTaskPhase.Running else deskPhaseOf(state)

    private fun updateView(taskId: String, title: String, state: TaskState) {
        val now = clock()
        views.update { list ->
            val phase = phaseOf(taskId, state)
            val line = lineOf(state)
            val old = list.firstOrNull { it.taskId == taskId }
            val before = old?.recent.orEmpty()
            val moving = phase == DeskTaskPhase.Running || phase == DeskTaskPhase.NeedsYou
            val recent = if (line != null && moving) {
                // A repeated line moves to the end instead of being recorded twice
                (before - line + line).takeLast(DESK_RECENT_LINES)
            } else {
                before
            }
            // Full history: only text different from the previous step counts as a new step
            val steps = old?.steps.orEmpty().let { s ->
                if (line != null && moving && s.lastOrNull()?.text != line) (s + DeskStep(line, now)).takeLast(DESK_STEPS_MAX) else s
            }
            val startedAt = old?.startedAt ?: now.takeIf { phase != DeskTaskPhase.Queued }
            val changed = old == null || old.state != phase || old.line != line
            val view = DeskTaskView(
                taskId, title, phase, line, null, recent,
                goal = old?.goal ?: ledger.get(taskId)?.words?.ifBlank { null },
                startedAt = startedAt, steps = steps, need = needOf(state),
                updatedAt = if (changed) now else old!!.updatedAt,
            )
            val replaced = if (old != null) list.map { if (it.taskId == taskId) view else it } else list + view
            // Starting a task moves the ones behind it up, so queue positions are all re-read every time
            replaced.map { it.copy(position = if (it.state == DeskTaskPhase.Queued) tasks.queuePosition(it.taskId) else null) }
        }
    }

    /** What it waits for while needing you; null otherwise. The explanation is verbatim (the question card shows it in full, keeping quoted options and names). */
    private fun needOf(state: TaskState): DeskNeed? = when (state) {
        is TaskState.WaitingInput -> DeskNeed(state.prompt.trim().ifBlank { null }, state.options?.takeIf { it.isNotEmpty() })
        is TaskState.WaitingConfirmation -> DeskNeed(null, action = ActionKind.Confirm)
        is TaskState.WaitingTakeover -> DeskNeed(state.message.trim().ifBlank { null }, action = ActionKind.Takeover)
        is TaskState.Held -> if (state.reason.needsYou) DeskNeed(state.reason.why.trim().ifBlank { null }, action = ActionKind.Prerequisite) else null
        else -> null
    }

    private suspend fun deliver(report: DeskReport) {
        var interrupted: DeskSurface? = null
        while (true) {
            val target = surface?.takeIf { it !== interrupted }
            if (target == null) {
                record(report)
                return
            }
            try {
                if (!target.deliver(report)) record(report)
                return
            } catch (e: CancellationException) {
                // Exiting normally when the front desk itself is cancelled; otherwise the entry point was interrupted by the user, so deliver this one elsewhere
                currentCoroutineContext().ensureActive()
                interrupted = target
            } catch (e: Exception) {
                record(report)
                return
            }
        }
    }

    private suspend fun record(report: DeskReport) {
        try {
            sink.record(conversationId, report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed fallback write loses only this one report; the delivery coroutine must not stop and lose the rest
        }
    }

    private fun lineOf(state: TaskState): String? = when (state) {
        // Prefer what the model wrote alongside the call, then the call's reason (as the chat row and task chip already do), then task progress
        is TaskState.Running -> deskLine(state.narration)
            ?: deskLine(state.reason)
            ?: deskLine((state.progress as? RunProgress.Task)?.display)
        is TaskState.Held -> if (state.reason.queued) null else deskLine(state.reason.why)
        is TaskState.WaitingInput -> deskLine(state.prompt)
        is TaskState.WaitingTakeover -> deskLine(state.message)
        is TaskState.Succeeded -> deskLine(state.answer)
        is TaskState.Failed -> deskLine(state.reason)
        else -> null
    }
}

/** Which kind a task is to the front desk right now (running, queued, needs-you, done, failed, cancelled); the task board and the phone front desk share this. */
fun deskPhaseOf(state: TaskState): DeskTaskPhase = when (state) {
    TaskState.Idle -> DeskTaskPhase.Queued
    is TaskState.Running -> DeskTaskPhase.Running
    is TaskState.Held -> when {
        state.reason.queued -> DeskTaskPhase.Queued
        state.reason.needsYou -> DeskTaskPhase.NeedsYou
        else -> DeskTaskPhase.Running
    }
    is TaskState.WaitingInput, is TaskState.WaitingConfirmation, is TaskState.WaitingTakeover -> DeskTaskPhase.NeedsYou
    is TaskState.Succeeded -> DeskTaskPhase.Done
    is TaskState.Failed -> DeskTaskPhase.Failed
    TaskState.Cancelled -> DeskTaskPhase.Cancelled
}
