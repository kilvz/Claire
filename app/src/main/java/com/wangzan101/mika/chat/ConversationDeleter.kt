package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** What deleting a conversation entails: whether it has running or queued tasks (they will be stopped), and how many schedules are bound to it (they are kept). */
data class DeleteImpact(val runningTask: Boolean, val schedules: Int)

/** Progress of one batch delete: [total] in all, [done] already processed, of which [failed] could not be deleted. */
data class DeleteBatch(val total: Int, val done: Int, val failed: Int) {
    /** Everything has been processed (whether or not it succeeded). */
    val finished: Boolean get() = done >= total
}

/**
 * Deletes a conversation: stops its running and queued tasks and waits for them to wind down (a turn saved while winding down is still within the delete's scope),
 * then deletes the conversation record, then cleans up its workspace (attachments, screens the user sent, files written by tasks).
 * Schedules have their own management page and are not deleted with the conversation history; the next trigger leaves its result under the original conversation id again.
 * Run records (used for run statistics) are global and are not deleted.
 */
class ConversationDeleter(
    private val store: ConversationStore,
    /** Conversations with a running or queued task. */
    private val active: StateFlow<List<String>>,
    /** Stops all running and queued tasks of this conversation. */
    private val cancelTasks: (conversationId: String) -> Unit,
    /** Ids of the schedules bound to this conversation; used only for the warning before deleting, the schedules themselves are kept. */
    private val schedulesOf: (conversationId: String) -> List<String>,
    /** Deletes this conversation's workspace. */
    private val deleteWorkspace: suspend (conversationId: String) -> Unit,
    /** After stopping the tasks, wait at most this long for them to wind down; past that, delete anyway. */
    private val stopWaitMs: Long = 15_000,
    /** Where reading and writing schedules and conversation records happens. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetRemoteTasks: (String) -> Unit = {},
    private val isSharedWorkspace: (String) -> Boolean = { store.projectId(it) != null },
    private val onDeleted: suspend (String) -> Unit = {},
) {
    fun impact(conversationId: String): DeleteImpact =
        DeleteImpact(conversationId in active.value, runCatching { schedulesOf(conversationId).size }.getOrDefault(0))

    suspend fun delete(conversationId: String) {
        if (conversationId in active.value) {
            cancelTasks(conversationId)
            withTimeoutOrNull(stopWaitMs) { active.first { conversationId !in it } }
        }
        val sharedWorkspace = withContext(io) {
            val shared = isSharedWorkspace(conversationId)
            forgetRemoteTasks(conversationId)
            store.delete(conversationId)
            shared
        }
        // Only clean up standalone attachments after history is removed; shared project workspaces stay intact.
        if (!sharedWorkspace) {
            try { deleteWorkspace(conversationId) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
        }
        try { onDeleted(conversationId) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }
}
