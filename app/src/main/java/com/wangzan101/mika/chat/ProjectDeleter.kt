package com.wangzan101.mika.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/** What deleting a project entails: how many conversations, how many running or queued tasks, and how many schedules are bound to those conversations (the schedules themselves are kept). */
data class ProjectDeleteImpact(val conversations: Int, val runningTasks: Int, val schedules: Int)

/**
 * Deletes a project: stops the running and queued tasks of all conversations in it and waits for them to wind down, deletes those conversations' history and the project definition,
 * then deletes the project's shared workspace (`p-<projectId>`) and cleans up the corresponding import records. Schedules are kept.
 */
class ProjectDeleter(
    private val projects: ProjectStore,
    private val conversations: ConversationStore,
    private val active: StateFlow<List<String>>,
    private val cancelTasks: (conversationId: String) -> Unit,
    private val schedulesOf: (conversationId: String) -> List<String>,
    private val deleteProjectWorkspace: suspend (projectId: String) -> Unit,
    private val stopWaitMs: Long = 15_000,
    private val io: CoroutineContext = Dispatchers.IO,
    private val forgetRemoteTasks: (String) -> Unit = {},
    private val onDeleted: suspend (projectId: String, conversationIds: List<String>) -> Unit = { _, _ -> },
) {
    fun impact(projectId: String): ProjectDeleteImpact {
        val memberIds = memberConversationIds(projectId)
        val memberSet = memberIds.toSet()
        val runningCount = active.value.count { it in memberSet }
        val scheduleCount = memberIds.sumOf { id -> runCatching { schedulesOf(id).size }.getOrDefault(0) }
        return ProjectDeleteImpact(memberIds.size, runningCount, scheduleCount)
    }

    suspend fun delete(projectId: String) {
        val memberIds = withContext(io) { memberConversationIds(projectId) }
        val memberSet = memberIds.toSet()
        val activeMembers = active.value.filter { it in memberSet }
        if (activeMembers.isNotEmpty()) {
            activeMembers.forEach(cancelTasks)
            withTimeoutOrNull(stopWaitMs) { active.first { current -> current.none { it in memberSet } } }
        }
        val deletedIds = withContext(io) {
            // Collect the members once more after the tasks have stopped (new sidecar files may have been written meanwhile), then delete the records and the project definition
            val allMembers = (memberIds + memberConversationIds(projectId)).distinct()
            for (id in allMembers) {
                forgetRemoteTasks(id)
                conversations.delete(id)
            }
            projects.delete(projectId)
            allMembers
        }
        try { deleteProjectWorkspace(projectId) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        try { onDeleted(projectId, deletedIds) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    /**
     * The conversations in the project: those whose first turn is stored and whose sidecar file points to this project, plus those with a running or queued task whose first turn is not stored yet.
     * A new conversation writes its `.project` sidecar file when the first message is sent, but the first turn is only written into the conversation file when the task winds down, so it is not yet in
     * [ConversationStore.list]; missing it would mean its task is not stopped when the project is deleted, and winding down would write the conversation back.
     */
    fun memberConversationIds(projectId: String): List<String> =
        (conversations.list(includeArchived = true).filter { it.projectId == projectId }.map { it.id } +
            active.value.filter { conversations.projectId(it) == projectId }).distinct()
}
