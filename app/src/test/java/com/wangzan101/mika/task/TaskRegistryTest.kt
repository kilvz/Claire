package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRegistryTest {
    private data class Run(val conversationId: String, val goal: String) {
        val key = "$conversationId/$goal"
    }

    private var limit = ParallelLimit(2, null)
    private val registry = TaskRegistry({ limit }, { it.conversationId }, { it.key }) { c, g -> Run(c, g) }

    @Test fun secondConversationStartsWhenLimitTwo() {
        val a = registry.tryStart("a", "设置版本号")
        val b = registry.tryStart("b", "新建闹钟")
        assertTrue(a is StartResult.Started)
        assertTrue(b is StartResult.Started)
        assertEquals(listOf("a", "b"), registry.running().map { it.conversationId })
        assertEquals(Run("b", "新建闹钟"), registry.byConversation("b"))
    }

    @Test fun sameConversationQueues() {
        registry.tryStart("a", "一")
        // A task already exists in this conversation: per the M1 rule it is left to the conversation to queue it, and the registry does not accept it
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("a", "二"))
        assertEquals(1, registry.runs().size)
    }

    @Test fun scheduledRunsQueueBehindSameConversationEvenWithFreeParallelSlot() {
        val first = (registry.tryStart("a", "现在的任务") as StartResult.Started).run
        val scheduled = registry.tryStart("a", "到点的任务", queueInConversation = true) as StartResult.Queued
        assertEquals(listOf(first), registry.running())
        assertEquals(listOf(scheduled.run), registry.queued())
        assertEquals(listOf(scheduled.run), registry.onEnded(first))
    }

    @Test fun overLimitQueues() {
        limit = ParallelLimit(1, null)
        registry.tryStart("a", "一")
        val b = registry.tryStart("b", "二")
        val c = registry.tryStart("c", "三")
        assertEquals(1, (b as StartResult.Queued).position)
        assertEquals(2, (c as StartResult.Queued).position)
        assertEquals(listOf("a"), registry.running().map { it.conversationId })
        assertEquals(listOf("b", "c"), registry.queued().map { it.conversationId })
        assertTrue(registry.isQueued(b.run))
        // A conversation that already has a queued task cannot accept a second one either
        assertSame(StartResult.AlreadyRunningInConversation, registry.tryStart("c", "四"))
    }

    @Test fun endedRunStartsQueued() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        registry.tryStart("c", "三")
        assertEquals(listOf(b), registry.onEnded(a))
        assertEquals(listOf("b"), registry.running().map { it.conversationId })
        assertEquals(listOf("c"), registry.queued().map { it.conversationId })
        assertNull(registry.byConversation("a"))
        // A queued task is cancelled: nothing new is released, and the queue moves forward
        val c = registry.byConversation("c")!!
        assertEquals(emptyList<Run>(), registry.onEnded(c))
        assertEquals(emptyList<Run>(), registry.queued())
    }

    // Two tasks want to open the same app at once: only the one that reserved it first may open it; the other gets "in use by another task"
    @Test fun sameAppReservedByFirstRun() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Started).run
        assertTrue(registry.reserve("settings", a.key) { true })
        assertTrue(registry.reserve("settings", a.key) { true })
        assertFalse(registry.reserve("settings", b.key) { true })
        // The holding task is no longer using it (the app closed itself): released to the later one
        assertTrue(registry.reserve("settings", b.key) { false })
        assertFalse(registry.reserve("settings", a.key) { true })
        // The task ends, and its reserved packages are released with it
        registry.onEnded(b)
        assertTrue(registry.reserve("settings", a.key) { true })
    }

    @Test fun centerUsesItsResourceSchedulerForMainScreenAndClipboard() {
        assertSame(registry.resources.takeoverQueue, registry.takeoverQueue)
        assertSame(registry.resources.clipboard, registry.clipLock)
    }

    @Test fun cancelIfQueuedOnlyTakesQueuedRuns() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertFalse(registry.cancelIfQueued(a))
        assertTrue(registry.cancelIfQueued(b))
        assertFalse(registry.cancelIfQueued(b))
        assertEquals(emptyList<Run>(), registry.onEnded(a))
        assertEquals(RunPhase.Ended, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun centerStoresTransitionsBeforeAndAfterDelivery() {
        limit = ParallelLimit(1, null)
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        val b = (registry.tryStart("b", "二") as StartResult.Queued).run
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(a.key))?.phase)
        assertEquals(RunPhase.Queued, registry.store.get(RunId(b.key))?.phase)
        registry.markRunning(a)
        assertEquals(true, registry.store.get(RunId(a.key))?.roundInProgress)
        registry.markResult(a, "好了")
        assertEquals("好了", registry.store.get(RunId(a.key))?.reply)
        registry.markDelivered(a)
        assertEquals(RunPhase.Ended, registry.store.get(RunId(a.key))?.phase)
        registry.onEnded(a)
        assertEquals(RunPhase.Preparing, registry.store.get(RunId(b.key))?.phase)
    }

    @Test fun channelMessageIsExecutedOnlyOnceAndKeepsItsReplyAddress() {
        val peer = ChannelPeer("wechat", "owner", "thread")
        val first = registry.tryStart("thread", "任务", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        val duplicate = registry.tryStart("thread", "任务", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        assertTrue(first is StartResult.Started)
        assertSame(StartResult.DuplicateChannelMessage, duplicate)
        val key = (first as StartResult.Started).run.key
        assertEquals(peer, registry.store.get(RunId(key))?.channelPeer)
        assertEquals("message-1", registry.store.get(RunId(key))?.channelMessageId)
        assertEquals(1, registry.runs().size)
    }

    @Test fun consecutiveChannelMessagesQueueInOrderWithoutMergingOrRepeatingRedeliveries() {
        val peer = ChannelPeer("wechat", "owner", "thread")
        val first = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-1")
        val second = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-2")
        val redelivered = registry.tryStart("thread", "相同的话", queueInConversation = true,
            channelPeer = peer, channelMessageId = "message-2")

        assertTrue(first is StartResult.Started)
        assertTrue(second is StartResult.Queued)
        assertSame(StartResult.DuplicateChannelMessage, redelivered)
        assertEquals(listOf("相同的话", "相同的话"), registry.runs().map { it.goal })
    }

    @Test fun messagesAtFinishingBoundaryAreKeptInOrder() {
        val run = (registry.tryStart("thread", "原来的") as StartResult.Started).run
        assertFalse(registry.deferOrStart("thread", "first", "id-1"))
        assertFalse(registry.deferOrStart("thread", "second", "id-2"))
        // The conversation still has a task: nothing is handed out
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady())
        registry.onEnded(run)
        // Only one item is taken per conversation at a time; the second stays queued until the first finishes
        assertEquals(listOf("first"), registry.takeReady().map { it.text })
        assertEquals(listOf("second"), registry.takeReady().map { it.text })
        assertEquals(emptyList<QueuedFollowUp>(), registry.takeReady())
    }

    // Finishing and leaving a message race: either it starts right away or it is left and handed out on finish; it is never lost on both ends
    @Test fun deferRacingWithEndNeverStrandsTheMessage() {
        repeat(200) { i ->
            val run = (registry.tryStart("t$i", "原来的") as StartResult.Started).run
            var startedNow = false
            val t = Thread { startedNow = registry.deferOrStart("t$i", "插话", "id-$i") }
            t.start()
            registry.onEnded(run)
            t.join()
            val handedOut = registry.takeReady().any { it.conversationId == "t$i" }
            assertTrue("message $i lost or duplicated", startedNow != handedOut)
        }
    }

    // A deferred message waits only for its own conversation to be free, not for tasks in other conversations
    @Test fun deferredMessageStartsWhenItsConversationIsFree() {
        val a = (registry.tryStart("a", "一") as StartResult.Started).run
        registry.tryStart("b", "别的对话")
        assertFalse(registry.deferOrStart("a", "下一件", "id-a"))
        registry.onEnded(a)
        assertEquals(listOf("下一件"), registry.takeReady().map { it.text })
        assertTrue(registry.deferOrStart("c", "空着的对话", "id-c"))
    }

    @Test fun sameWorkspaceConversationsSerializeWhileOtherWorkspacesRunInParallel() {
        val workspaces = mapOf("p1-a" to "p:proj-1", "p1-b" to "p:proj-1", "p1-c" to "p:proj-1", "other" to "other")
        val shared = TaskRegistry<Run>(
            { ParallelLimit(2, null) },
            { it.conversationId },
            { it.key },
            workspaceOf = { workspaces[it.conversationId] ?: it.conversationId },
            resolveWorkspace = { workspaces[it] ?: it },
        ) { c, g -> Run(c, g) }

        val first = (shared.tryStart("p1-a", "步1") as StartResult.Started).run
        val second = (shared.tryStart("p1-b", "步2") as StartResult.Queued)
        val third = (shared.tryStart("p1-c", "步3") as StartResult.Queued)
        val other = (shared.tryStart("other", "独立任务") as StartResult.Started).run

        assertEquals(1, second.position)
        assertEquals(2, third.position)
        assertEquals(listOf(first, other), shared.running())

        // When other ends, p1-b/p1-c still have to wait for p1-a to finish and must not run concurrently on p:proj-1
        assertEquals(emptyList<Run>(), shared.onEnded(other))
        assertEquals(listOf(first), shared.running())

        // When p1-a ends, only p1-b is released, in FIFO order; p1-c keeps queuing
        assertEquals(listOf(second.run), shared.onEnded(first))
        assertEquals(listOf(second.run), shared.running())
        assertEquals(listOf(third.run), shared.queued())

        assertEquals(listOf(third.run), shared.onEnded(second.run))
        assertEquals(listOf(third.run), shared.running())
    }
}
