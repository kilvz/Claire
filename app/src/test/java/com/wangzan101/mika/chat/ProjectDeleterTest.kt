package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun impactAndDeleteStopActiveMembersDeleteHistoryAndRemoveProjectWorkspace() = runTest {
        val projects = ProjectStore(tmp.newFolder("projects"))
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val p1 = projects.create("科研").getOrThrow()
        val p2 = projects.create("日常").getOrThrow()

        conversations.append("c1", Turn("q1", "a1", "succeeded"))
        conversations.setProject("c1", p1.id)
        conversations.append("c2", Turn("q2", "a2", "succeeded"))
        conversations.setProject("c2", p1.id)
        conversations.append("other", Turn("q3", "a3", "succeeded"))
        conversations.setProject("other", p2.id)

        val active = MutableStateFlow(listOf("c1", "other"))
        val cancelled = mutableListOf<String>()
        val deletedWorkspaces = mutableListOf<String>()
        var onDeletedArgs: Pair<String, List<String>>? = null
        val deleter = ProjectDeleter(
            projects, conversations, active,
            cancelTasks = { cancelled += it },
            schedulesOf = { id -> if (id == "c2") listOf("sched-1", "sched-2") else emptyList() },
            deleteProjectWorkspace = { deletedWorkspaces += it },
            stopWaitMs = 5_000,
            io = EmptyCoroutineContext,
            onDeleted = { pid, ids -> onDeletedArgs = pid to ids },
        )

        assertEquals(ProjectDeleteImpact(conversations = 2, runningTasks = 1, schedules = 2), deleter.impact(p1.id))

        val emittedDeletion = async { projects.deletions.take(1).let { flow ->
            var got: String? = null
            flow.collect { got = it }
            got
        } }
        runCurrent()

        val job = launch { deleter.delete(p1.id) }
        runCurrent()
        assertEquals(listOf("c1"), cancelled)
        assertTrue(deletedWorkspaces.isEmpty())

        active.value = listOf("other")
        job.join()

        assertEquals(p1.id, emittedDeletion.await())
        assertNull(projects.get(p1.id))
        assertEquals(listOf("other"), conversations.list().map { it.id })
        assertNull(conversations.projectId("c1"))
        assertNull(conversations.projectId("c2"))
        assertEquals(listOf(p1.id), deletedWorkspaces)
        assertEquals(p1.id, onDeletedArgs?.first)
        assertEquals(setOf("c1", "c2"), onDeletedArgs?.second?.toSet())
        assertFalse(projects.get(p2.id) == null)
    }

    /** A new conversation whose first message was sent while the task is still running: it has a `.project` sidecar and its first turn is written to the conversation file only after the task wraps up, so it still counts as a conversation in the project. */
    @Test fun deleteAlsoStopsAndRemovesAMemberWhoseFirstTurnIsNotSavedYet() = runTest {
        val projects = ProjectStore(tmp.newFolder("projects"))
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val p = projects.create("科研").getOrThrow()
        val p2 = projects.create("日常").getOrThrow()
        // Same as ChatViewModel.begin: write the sidecar before handing the message to the service
        conversations.setProject("fresh", p.id)
        // One in another project whose first turn is likewise not saved yet does not count
        conversations.setProject("elsewhere", p2.id)
        conversations.append("other", Turn("q", "a", "succeeded"))

        val active = MutableStateFlow(listOf("fresh", "elsewhere", "other"))
        val cancelled = mutableListOf<String>()
        var onDeletedArgs: Pair<String, List<String>>? = null
        val deleter = ProjectDeleter(
            projects, conversations, active,
            cancelTasks = { cancelled += it },
            schedulesOf = { emptyList() },
            deleteProjectWorkspace = {},
            stopWaitMs = 5_000,
            io = EmptyCoroutineContext,
            onDeleted = { pid, ids -> onDeletedArgs = pid to ids },
        )

        assertEquals(listOf("fresh"), deleter.memberConversationIds(p.id))
        assertEquals(ProjectDeleteImpact(conversations = 1, runningTasks = 1, schedules = 0), deleter.impact(p.id))

        val job = launch { deleter.delete(p.id) }
        runCurrent()
        assertEquals(listOf("fresh"), cancelled)
        // When the task stops, this turn is still written in as usual, and only then does it exit
        conversations.append("fresh", Turn("q", "", "cancelled"))
        active.value = listOf("elsewhere", "other")
        job.join()

        assertNull(projects.get(p.id))
        assertNull(conversations.projectId("fresh"))
        assertEquals(listOf("other"), conversations.list(includeArchived = true).map { it.id })
        assertEquals(listOf("fresh"), onDeletedArgs?.second)
        assertEquals(p2.id, conversations.projectId("elsewhere"))
    }
}
