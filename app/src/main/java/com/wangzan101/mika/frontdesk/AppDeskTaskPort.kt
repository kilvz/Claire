package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Maximum number of characters in the note the front desk hands to the backend; longer notes are truncated (the note is supplementary and should not be far longer than the user's own words). */
const val DESK_NOTE_MAX_CHARS = 1_000

/** If a dispatched task is still not registered with the task service after this long (service not running, blocked), it counts as not started. */
const val DESK_START_TIMEOUT_MS = 60_000L

/** Take the first [max] chars without splitting a character in half (emoji and similar take two chars). */
internal fun String.takeWhole(max: Int): String {
    if (length <= max) return this
    val end = if (max > 0 && this[max - 1].isHighSurrogate() && this[max].isLowSurrogate()) max - 1 else max
    return substring(0, end)
}

/** A running or queued backend task as seen by the port; in production it wraps [com.wangzan101.mika.task.TaskRun]. */
interface DeskRun {
    val conversationId: String
    /** This run's id in the task service; successive runs opened in the same child conversation each have a different one. */
    val key: String
    val state: StateFlow<TaskState>

    /** Add a line from the user; returns true when the task accepted it. */
    fun steer(words: String): Boolean
    fun answer(words: String)
    fun cancel()
}

/**
 * The front desk's task port: each job opens a new child conversation (hanging under the front desk conversation) and
 * hands the user's own words and the front desk note separately to the task service; the task id is the child
 * conversation id. A new child conversation never has a task, so the service starts or queues it as a new task and the
 * note is not dropped as a steer.
 *
 * The task service registers tasks asynchronously, so this port keeps its own copy of each job's state: first Idle, then
 * it follows the task once registered and stops at the end; if nothing is registered within [startTimeoutMs] it counts
 * as not started. Child conversations reattached when a conversation is reopened follow their task if it still exists; if
 * the task is gone, the outcome recorded in the child's last turn supplies the terminal state, and when even that is
 * missing (the process died midway) it is recorded as interrupted ([interrupted]) without waiting for registration or
 * taking a parallel slot.
 *
 * [runs]/[current] are the tasks currently running and queued in the task service (queued in order), [start] hands a task
 * to the service, and [parallelLimit] is how many can run at once right now.
 */
class AppDeskTaskPort(
    private val conversations: ConversationStore,
    private val runs: Flow<List<DeskRun>>,
    private val current: () -> List<DeskRun>,
    private val start: (words: String, conversationId: String, brief: String?) -> Unit,
    private val parallelLimit: () -> Int,
    private val scope: CoroutineScope,
    private val notStarted: () -> String,
    private val interrupted: () -> String,
    private val startTimeoutMs: Long = DESK_START_TIMEOUT_MS,
) : DeskTaskPort {
    /** [adopted]: not dispatched here but reattached when a conversation was reopened; whether it is in the service is judged only by the service's copy, and it is not treated as a fresh dispatch placeholder. */
    private class Followed(val state: MutableStateFlow<TaskState>, val queuedAt: Int?, val adopted: Boolean = false) {
        /** Already seen in the task service. */
        @Volatile var seen = false
        /** Which run is being followed (available only after registration in the task service); later runs opened in the child conversation are not it. */
        @Volatile var runKey: String? = null
    }

    private val followed = HashMap<String, Followed>()

    override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome =
        startIn(openChild(parentConversation), words, note)

    /** While files are being placed into the child conversation's workspace this lock is not held (copying may take a while); only once they are in place does it start or queue based on the parallelism at that moment. */
    override suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome {
        val child = openChild(parentConversation)
        val words = try {
            prepare(child)
        } catch (e: CancellationException) {
            runCatching { conversations.delete(child) }
            throw e
        } catch (e: Exception) {
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "prepare_failed")
        }
        if (words.isNullOrBlank()) {
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed("files_not_ready")
        }
        return startIn(child, words, "")
    }

    /** Open a new child conversation under the front desk conversation [parentConversation]. */
    private fun openChild(parentConversation: String): String =
        conversations.newConversation().also { conversations.setDeskParent(it, parentConversation) }

    /** Start (or queue) this job in the child conversation [child]; delete the child conversation if it was not handed to the task service. */
    @Synchronized
    private fun startIn(child: String, words: String, note: String): DispatchOutcome {
        val outcome = predict(child)
        val entry = Followed(MutableStateFlow(TaskState.Idle), (outcome as? DispatchOutcome.Queued)?.position)
        followed[child] = entry
        try {
            start(words, child, note.trim().takeWhole(DESK_NOTE_MAX_CHARS).ifBlank { null })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            followed.remove(child)
            runCatching { conversations.delete(child) }
            return DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "start_failed")
        }
        mirror(child, entry)
        return outcome
    }

    /** Predict by the task service's rules: start if the running count (including jobs just dispatched here but not yet registered) is below the limit, otherwise queue behind what is already queued. */
    private fun predict(child: String): DispatchOutcome {
        val live = current()
        val unseen = followed.values.filter { !it.adopted && !it.seen && it.state.value !is TaskState.Terminal }
        val running = live.count { !it.queued() && it.state.value !is TaskState.Terminal } + unseen.count { it.queuedAt == null }
        val queued = live.count { it.queued() } + unseen.count { it.queuedAt != null }
        return if (running < parallelLimit().coerceAtLeast(1)) DispatchOutcome.Started(child)
        else DispatchOutcome.Queued(child, queued + 1)
    }

    @Synchronized
    override fun state(taskId: String): StateFlow<TaskState>? {
        followed[taskId]?.let { return it.state.asStateFlow() }
        // Not dispatched here: only accept child conversations hanging under some front desk conversation (reattached when a conversation is reopened).
        if (conversations.deskParent(taskId) == null) return null
        val live = current().firstOrNull { it.conversationId == taskId }
        val initial = live?.state?.value ?: ended(taskId) ?: TaskState.Failed(interrupted(), FailKind.Interrupted)
        val entry = Followed(MutableStateFlow(initial), null, adopted = true)
        followed[taskId] = entry
        if (initial !is TaskState.Terminal) mirror(taskId, entry)
        return entry.state.asStateFlow()
    }

    @Synchronized
    override fun followedTaskOf(runKey: String): String? = followed.entries.firstOrNull { it.value.runKey == runKey }?.key

    override fun queuePosition(taskId: String): Int? {
        val live = current()
        val run = live.firstOrNull { it.conversationId == taskId }
        if (run != null) {
            if (!run.queued()) return null
            return live.filter { it.queued() }.indexOfFirst { it.conversationId == taskId } + 1
        }
        val entry = synchronized(this) { followed[taskId] } ?: return null
        return entry.queuedAt?.takeIf { !entry.seen && entry.state.value !is TaskState.Terminal }
    }

    override fun steer(taskId: String, words: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value !is TaskState.Running) return false
        return run.steer(words)
    }

    override fun answer(taskId: String, words: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value !is TaskState.WaitingInput) return false
        run.answer(words)
        return true
    }

    override fun cancel(taskId: String): Boolean {
        val run = live(taskId) ?: return false
        if (run.state.value is TaskState.Terminal) return false
        run.cancel()
        return true
    }

    private fun live(taskId: String): DeskRun? = current().firstOrNull { it.conversationId == taskId }

    /** Wait until the task is registered in the service, then follow its state and stop at the end; when it never registers, use the outcome recorded in the child conversation, or count it as not started if there is none. */
    private fun mirror(child: String, entry: Followed) {
        scope.launch {
            val run = withTimeoutOrNull(startTimeoutMs) {
                runs.mapNotNull { list -> list.firstOrNull { it.conversationId == child } }.first()
            }
            if (run == null) {
                entry.state.value = ended(child) ?: TaskState.Failed(notStarted(), FailKind.Environment)
                return@launch
            }
            entry.runKey = run.key
            entry.seen = true
            run.state.transformWhile { emit(it); it !is TaskState.Terminal }.collect { entry.state.value = it }
        }
    }

    /** The outcome recorded in the child's last turn; null when no turn has been recorded yet. Turns do not store the failure source, so failures are always recorded as environment failures. */
    private fun ended(child: String): TaskState.Terminal? {
        val last: Turn = conversations.turns(child).lastOrNull { it.userText.isNotBlank() } ?: return null
        return when (last.status) {
            "succeeded" -> TaskState.Succeeded(last.outcome)
            "unverified" -> TaskState.Succeeded(last.outcome, verified = false, note = last.note)
            "cancelled" -> TaskState.Cancelled
            else -> TaskState.Failed(last.outcome, FailKind.Environment)
        }
    }

    private fun DeskRun.queued(): Boolean = (state.value as? TaskState.Held)?.reason?.queued == true
}
