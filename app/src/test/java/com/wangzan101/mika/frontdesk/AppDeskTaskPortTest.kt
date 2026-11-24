package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppDeskTaskPortTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeRun(override val conversationId: String, initial: TaskState, override val key: String = "run-$conversationId") : DeskRun {
        val flow = MutableStateFlow(initial)
        override val state: StateFlow<TaskState> get() = flow
        val steered = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var cancelled = false
        override fun steer(words: String): Boolean { steered += words; return true }
        override fun answer(words: String) { answered += words }
        override fun cancel() { cancelled = true }
    }

    private data class Start(val words: String, val conversationId: String, val brief: String?)

    private inner class Rig(scope: TestScope, limit: Int = 2, timeoutMs: Long = 60_000) {
        val store = ConversationStore(tmp.newFolder())
        val runs = MutableStateFlow<List<DeskRun>>(emptyList())
        val starts = mutableListOf<Start>()
        val port = AppDeskTaskPort(store, runs, { runs.value }, { w, c, b -> starts += Start(w, c, b) }, { limit },
            scope.backgroundScope, { "没开始" }, { "中断了" }, timeoutMs)
    }

    @Test fun dispatchCreatesLinkedChildAndStartsWithBrief() = runTest {
        val rig = Rig(this)
        val outcome = rig.port.dispatch("帮我订明早的票", "用户偏好靠窗", "p")
        val child = (outcome as DispatchOutcome.Started).taskId
        assertEquals("p", rig.store.deskParent(child))
        assertEquals(listOf(Start("帮我订明早的票", child, "用户偏好靠窗")), rig.starts)
        // Every item goes into a freshly created empty child conversation, so it never lands in an existing task's conversation and gets treated as a steer.
        val second = rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started
        assertTrue(second.taskId != child)
        assertNull(rig.starts.last().brief)
    }

    @Test fun noteIsCappedAtThisLayer() = runTest {
        val rig = Rig(this)
        rig.port.dispatch("查天气", "说".repeat(DESK_NOTE_MAX_CHARS + 50), "p")
        assertEquals(DESK_NOTE_MAX_CHARS, rig.starts.single().brief!!.length)
    }

    // Truncation never splits a character's two halves (emoji and the like occupy two chars).
    @Test fun noteCapKeepsSurrogatePairsWhole() = runTest {
        val rig = Rig(this)
        rig.port.dispatch("查天气", "a" + "\uD83D\uDE00".repeat(DESK_NOTE_MAX_CHARS), "p")
        val brief = rig.starts.single().brief!!
        assertEquals(DESK_NOTE_MAX_CHARS - 1, brief.length)
        assertFalse(brief.last().isHighSurrogate())
    }

    @Test fun queuedWhenParallelLimitReached() = runTest {
        val rig = Rig(this, limit = 1)
        rig.runs.value = listOf(FakeRun("other", TaskState.Running(1, "在做")))
        val outcome = rig.port.dispatch("查天气", "", "p")
        assertEquals(DispatchOutcome.Queued((outcome as DispatchOutcome.Queued).taskId, 1), outcome)
        assertEquals(1, rig.port.queuePosition(outcome.taskId))
    }

    // One just dispatched but not yet registered by the service also occupies a slot, so two dispatches in a row are not both treated as started.
    @Test fun unregisteredDispatchCountsTowardLimit() = runTest {
        val rig = Rig(this, limit = 1)
        assertTrue(rig.port.dispatch("查天气", "", "p") is DispatchOutcome.Started)
        assertEquals(1, (rig.port.dispatch("订票", "", "p") as DispatchOutcome.Queued).position)
    }

    @Test fun stateFollowsRegisteredRunUntilTerminal() = runTest {
        val rig = Rig(this)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val state = rig.port.state(child)!!
        assertEquals(TaskState.Idle, state.value)
        val run = FakeRun(child, TaskState.Running(1, "打开天气"))
        rig.runs.value = listOf(run)
        runCurrent()
        assertEquals(TaskState.Running(1, "打开天气"), state.value)

        run.flow.value = TaskState.WaitingInput("哪个城市?")
        assertTrue(rig.port.answer(child, "上海"))
        assertEquals(listOf("上海"), run.answered)
        assertFalse(rig.port.steer(child, "顺便看明天"))

        run.flow.value = TaskState.Running(2, "查上海")
        runCurrent()
        assertTrue(rig.port.steer(child, "顺便看明天"))
        assertTrue(rig.port.cancel(child))
        assertTrue(run.cancelled)

        run.flow.value = TaskState.Succeeded("晴")
        runCurrent()
        assertEquals(TaskState.Succeeded("晴"), state.value)
        rig.runs.value = emptyList()
        assertFalse(rig.port.cancel(child))
    }

    @Test fun queuePositionFollowsServiceQueue() = runTest {
        val rig = Rig(this, limit = 1)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val queued = TaskState.Held(GateReason.queued("排队"))
        rig.runs.value = listOf(FakeRun("a", TaskState.Running(1, "x")), FakeRun("b", queued), FakeRun(child, queued))
        assertEquals(2, rig.port.queuePosition(child))
        assertNull(rig.port.queuePosition("a"))
    }

    @Test fun neverRegisteredBecomesFailed() = runTest {
        val rig = Rig(this, timeoutMs = 1_000)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        val state = rig.port.state(child)!!
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(TaskState.Failed("没开始", FailKind.Environment), state.value)
    }

    @Test fun startFailureReportsFailedAndLeavesNoChild() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val port = AppDeskTaskPort(store, MutableStateFlow(emptyList()), { emptyList() }, { _, _, _ -> error("blocked") }, { 2 },
            backgroundScope, { "没开始" }, { "中断了" })
        assertEquals(DispatchOutcome.Failed("blocked"), port.dispatch("查天气", "", "p"))
    }

    // Reattaching when the conversation reopens: unknown ids are null; those attached to a front-desk conversation whose task is gone get a terminal state from the outcome recorded on the child conversation.
    @Test fun restoredChildUsesRecordedOutcome() = runTest {
        val rig = Rig(this)
        assertNull(rig.port.state("stranger"))
        rig.store.append("c1", Turn("查天气", "晴", "succeeded"))
        rig.store.setDeskParent("c1", "p")
        assertEquals(TaskState.Succeeded("晴"), rig.port.state("c1")!!.value)
        rig.store.append("c2", Turn("订票", "", "cancelled"))
        rig.store.setDeskParent("c2", "p")
        assertEquals(TaskState.Cancelled, rig.port.state("c2")!!.value)
    }

    // A child conversation whose process died midway: the task is gone and no outcome was recorded, so it gets the interrupted terminal state at once, without waiting for registration and without reporting "not started" a minute later.
    @Test fun adoptedChildWithoutRunOrRecordIsInterruptedAtOnce() = runTest {
        val rig = Rig(this, timeoutMs = 1_000)
        rig.store.setDeskParent("c3", "p")
        val state = rig.port.state("c3")!!
        assertEquals(TaskState.Failed("中断了", FailKind.Interrupted), state.value)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(TaskState.Failed("中断了", FailKind.Interrupted), state.value)
    }

    // An adopted child conversation does not count as a "just dispatched, not yet registered" placeholder: with a limit of 1 a new one can still start.
    @Test fun adoptedChildDoesNotHoldASlot() = runTest {
        val rig = Rig(this, limit = 1)
        rig.store.setDeskParent("c3", "p")
        rig.port.state("c3")
        assertTrue(rig.port.dispatch("查天气", "", "p") is DispatchOutcome.Started)
    }

    // When reattaching, if the task is still in the service, count only the service's copy; do not count it again as unregistered.
    // The port only recognizes the run it follows: another item started in the child conversation after it wrapped up (a different run) does not count as the one it follows.
    @Test fun followedTaskOfIsBoundToTheRunItMirrors() = runTest {
        val rig = Rig(this)
        val child = (rig.port.dispatch("查天气", "", "p") as DispatchOutcome.Started).taskId
        assertNull(rig.port.followedTaskOf("k1"))
        val first = FakeRun(child, TaskState.Running(1, "打开天气"), key = "k1")
        rig.runs.value = listOf(first)
        runCurrent()
        assertEquals(child, rig.port.followedTaskOf("k1"))

        first.flow.value = TaskState.Succeeded("晴")
        runCurrent()
        rig.runs.value = listOf(FakeRun(child, TaskState.Running(1, "再查一次"), key = "k2"))
        runCurrent()
        assertNull(rig.port.followedTaskOf("k2"))
        assertNull(rig.port.followedTaskOf("nope"))
    }

    // Adopted ones: those whose task is still running follow that run; those already finished follow no run.
    @Test fun adoptedChildIsBoundOnlyWhileItsRunIsLive() = runTest {
        val rig = Rig(this)
        rig.runs.value = listOf(FakeRun("c5", TaskState.Running(1, "在做"), key = "k5"))
        rig.store.setDeskParent("c5", "p")
        rig.store.setDeskParent("c6", "p")
        rig.store.append("c6", Turn("查天气", "晴", "succeeded"))
        rig.port.state("c5")
        rig.port.state("c6")
        runCurrent()
        assertEquals("c5", rig.port.followedTaskOf("k5"))
        assertNull(rig.port.followedTaskOf("run-c6"))
    }

    @Test fun adoptedLiveChildIsCountedOnce() = runTest {
        val rig = Rig(this, limit = 2)
        rig.runs.value = listOf(FakeRun("c4", TaskState.Running(1, "在做")))
        rig.store.setDeskParent("c4", "p")
        assertEquals(TaskState.Running(1, "在做"), rig.port.state("c4")!!.value)
        assertTrue(rig.port.dispatch("查天气", "", "p") is DispatchOutcome.Started)
    }
    // Dispatch with files: open the child conversation attached to the front-desk conversation first, then start once the files are in place; the service gets the words produced after preparation, with no note
    @Test fun dispatchWithFilesOpensTheChildFirstAndStartsAfterPrepare() = runTest {
        val rig = Rig(this)
        var prepared: String? = null
        val outcome = rig.port.dispatchWithFiles("p") { child ->
            prepared = child
            assertEquals("p", rig.store.deskParent(child))
            assertTrue(rig.starts.isEmpty())
            "看合同\n附件:/workspace/inbox/a.pdf(1 B)"
        }
        val child = (outcome as DispatchOutcome.Started).taskId
        assertEquals(prepared, child)
        assertEquals(listOf(Start("看合同\n附件:/workspace/inbox/a.pdf(1 B)", child, null)), rig.starts)
        // It occupies a parallel slot like any other dispatch
        assertTrue(rig.port.dispatch("查天气", "", "p") is DispatchOutcome.Started)
        assertTrue(rig.port.dispatch("订票", "", "p") is DispatchOutcome.Queued)
    }

    // Files not placed (or an error): do not hand it to the service, delete the child conversation
    @Test fun dispatchWithFilesThatFailToPrepareLeaveNoChild() = runTest {
        val rig = Rig(this)
        var child: String? = null
        val outcome = rig.port.dispatchWithFiles("p") { child = it; null }
        assertTrue(outcome is DispatchOutcome.Failed)
        assertTrue(rig.starts.isEmpty())
        assertNull(rig.store.deskParent(child!!))
        val thrown = rig.port.dispatchWithFiles("p") { error("disk full") }
        assertEquals(DispatchOutcome.Failed("disk full"), thrown)
        assertTrue(rig.starts.isEmpty())
    }
}
