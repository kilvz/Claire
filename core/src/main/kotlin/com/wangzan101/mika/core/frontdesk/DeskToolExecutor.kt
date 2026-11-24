package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Dispatching the same words again within this time counts as a duplicate and returns the first task. */
const val DISPATCH_DEDUPE_MS = 30_000L

/**
 * Executes front-desk tool calls. All four tools carry the user's original words (words), which first pass [words] (code verifying the passage was really said); a mismatch is rejected;
 * what is handed to the background and recorded in the ledger is the whole sentence containing the passage, not the fragment the front desk picked. Cancelling only accepts the user's words from this turn.
 * Task ids are accepted only from tasks dispatched in this conversation ([ledger]). Dispatched tasks go under [conversationId] and are handed to [onDispatched] once started or queued. [DeskTools.ASK_OWNER] is handled by the phone adapter, not here;
 * any other unknown tool name (including the removed lookup tools) returns unsupported.
 */
class DeskToolExecutor(
    private val tasks: DeskTaskPort,
    private val ledger: DeskLedger,
    private val words: DeskWords,
    private val clock: () -> Long,
    private val conversationId: String,
    private val onDispatched: (DeskTaskEntry) -> Unit = {},
) {
    /** Dedupe and recording must happen together, otherwise concurrent dispatches of the same message both go out. */
    private val dispatching = Mutex()

    suspend fun execute(name: String, args: JsonObject): JsonObject = when (name) {
        DeskTools.DISPATCH -> dispatch(args)
        DeskTools.ADD -> withVerifiedTask(args) { id, words -> accepted(tasks.steer(id, words)) }
        DeskTools.ANSWER -> withVerifiedTask(args) { id, words -> accepted(tasks.answer(id, words)) }
        DeskTools.CANCEL -> cancel(args)
        else -> status("unsupported")
    }

    private suspend fun dispatch(args: JsonObject): JsonObject {
        val fragment = args.string("words").orEmpty()
        val words = verifiedWords(args) ?: return wordsRejected()
        val note = args.string("note").orEmpty()
        val title = args.string("title")?.ifBlank { null } ?: fragment.take(DESK_LINE_MAX_CHARS)
        // Dedupe looks at which task the front desk means (the pick, note and title): one message can dispatch several tasks, each carrying the same whole sentence
        val asked = listOf(fragment.trim(), note.trim(), title.trim()).joinToString("\n")
        return dispatching.withLock {
            val now = clock()
            // When the previous identical task has ended (or the port no longer knows it), it is not a duplicate and is dispatched as new.
            ledger.find(asked, since = now - DISPATCH_DEDUPE_MS)?.takeIf { isUnfinished(it.taskId) }?.let {
                return@withLock buildJsonObject { put("status", "already_dispatched"); put("task_id", it.taskId) }
            }
            val outcome = try {
                tasks.dispatch(words, note, conversationId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "dispatch_error")
            }
            when (outcome) {
                is DispatchOutcome.Started -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now), asked)
                    buildJsonObject { put("status", "started"); put("task_id", outcome.taskId) }
                }
                is DispatchOutcome.Queued -> {
                    record(DeskTaskEntry(outcome.taskId, title, words, now), asked)
                    buildJsonObject { put("status", "queued"); put("task_id", outcome.taskId); put("position", outcome.position) }
                }
                is DispatchOutcome.Failed -> buildJsonObject { put("status", "failed"); put("reason", outcome.reason) }
            }
        }
    }

    /**
     * A message the user sent with files from the input field: dispatched straight as a new task, without the front-desk model (front-desk tools do not take files). [typed] is the whole sentence the user typed;
     * when not blank it still has to pass verification (with files only and no text, the background gets only the file lines the code writes); [prepare] is [DeskTaskPort.dispatchWithFiles].
     * The result has the same shape as dispatch_task. No dedupe: the files differ each time. Placing files does not hold the dispatch lock, so the front desk can dispatch other tasks meanwhile.
     */
    suspend fun dispatchWithFiles(typed: String, title: String, prepare: suspend (child: String) -> String?): JsonObject {
        val words = if (typed.isBlank()) typed else this.words.said(typed) ?: return wordsRejected()
        val outcome = try {
            tasks.dispatchWithFiles(conversationId, prepare)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DispatchOutcome.Failed(e.message ?: e::class.simpleName ?: "dispatch_error")
        }
        val name = title.trim().ifBlank { words }.take(DESK_LINE_MAX_CHARS)
        return when (outcome) {
            is DispatchOutcome.Started -> {
                record(DeskTaskEntry(outcome.taskId, name, words, clock()))
                buildJsonObject { put("status", "started"); put("task_id", outcome.taskId) }
            }
            is DispatchOutcome.Queued -> {
                record(DeskTaskEntry(outcome.taskId, name, words, clock()))
                buildJsonObject { put("status", "queued"); put("task_id", outcome.taskId); put("position", outcome.position) }
            }
            is DispatchOutcome.Failed -> buildJsonObject { put("status", "failed"); put("reason", outcome.reason) }
        }
    }

    private fun record(entry: DeskTaskEntry, asked: String = entry.words) {
        ledger.add(entry, asked)
        onDispatched(entry)
    }

    private fun isUnfinished(taskId: String): Boolean = tasks.state(taskId)?.value?.let { it !is TaskState.Terminal } ?: false

    private inline fun withVerifiedTask(args: JsonObject, action: (String, String) -> JsonObject): JsonObject {
        val id = knownTask(args) ?: return status("unknown_task")
        val words = verifiedWords(args) ?: return wordsRejected()
        return action(id, words)
    }

    /** Stop a task: the id must have been dispatched and the words must be the user's from this turn (reports and task-board text cannot stop it). */
    private fun cancel(args: JsonObject): JsonObject {
        val id = knownTask(args) ?: return status("unknown_task")
        args.string("words")?.takeIf { it.isNotBlank() }?.let(words::saidNow) ?: return wordsRejected()
        return accepted(tasks.cancel(id))
    }

    private fun knownTask(args: JsonObject): String? = args.string("task_id")?.takeIf { ledger.get(it) != null }

    /** The whole verified sentence: whichever sentence the user said contains the front desk's passage is the one handed over. */
    private fun verifiedWords(args: JsonObject): String? = args.string("words")?.takeIf { it.isNotBlank() }?.let(words::said)

    private fun accepted(ok: Boolean) = status(if (ok) "ok" else "not_accepted")

    private fun wordsRejected() = buildJsonObject { put("status", "rejected"); put("reason", "words_not_verified") }

    private fun status(value: String) = buildJsonObject { put("status", value) }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
