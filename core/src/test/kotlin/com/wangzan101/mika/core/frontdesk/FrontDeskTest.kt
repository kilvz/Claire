package com.wangzan101.mika.core.frontdesk

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrontDeskTest {
    /** Fake port that only supplies state and queue position; the front desk does not use dispatch or add here. */
    private class FakePort : DeskTaskPort {
        val states = HashMap<String, MutableStateFlow<TaskState>>()
        val positions = HashMap<String, Int>()

        fun add(taskId: String, state: TaskState = TaskState.Idle) = MutableStateFlow(state).also { states[taskId] = it }

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome = error("unused")
        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = positions[taskId]
        override fun steer(taskId: String, words: String) = false
        override fun answer(taskId: String, words: String) = false
        override fun cancel(taskId: String) = false
    }

    /** Records the reports it receives; [online] false means offline; when [gate] is non-null, each delivery suspends until it is released (simulating that it is speaking). */
    private class FakeSurface(var online: Boolean = true) : DeskSurface {
        val delivered = mutableListOf<DeskReport>()
        val gates = ArrayDeque<CompletableDeferred<Unit>>()
        var hold = false

        override suspend fun deliver(report: DeskReport): Boolean {
            if (hold) CompletableDeferred<Unit>().also { gates.addLast(it) }.await()
            if (online) delivered += report
            return online
        }
    }

    private class FakeSink : DeskSink {
        val recorded = mutableListOf<Pair<String, DeskReport>>()
        override suspend fun record(conversationId: String, report: DeskReport) {
            recorded += conversationId to report
        }
    }

    private val port = FakePort()
    private val ledger = DeskLedger()
    private val sink = FakeSink()
    private var now = 0L

    private fun TestScope.desk() = FrontDesk("c1", backgroundScope, port, ledger, ReportPolicy(clock = { now }), sink, clock = { now })

    private fun running(narration: String? = null) = TaskState.Running(step = 1, note = "", narration = narration)

    @Test
    fun trackSubscribesAndEmitsReportsToSurface() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        runCurrent()

        state.value = TaskState.WaitingInput("订几点的?", listOf("十点", "十一点"))
        runCurrent()

        assertEquals(listOf(DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", listOf("十点", "十一点")), "订会议室")), surface.delivered)
        assertTrue(sink.recorded.isEmpty())
        assertEquals(DeskTaskPhase.NeedsYou, desk.active.value.single().state)
    }

    @Test
    fun reportsFallBackToSinkWhenNoSurface() = runTest {
        val state = port.add("t1")
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()

        val offline = FakeSurface(online = false)
        desk.attach(offline)
        state.value = TaskState.Failed("会议室都满了", FailKind.GaveUp)
        runCurrent()

        assertEquals(
            listOf(
                "c1" to DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", null), "订会议室"),
                "c1" to DeskReport(DeskEvent.Failed("t1", "会议室都满了"), "订会议室"),
            ),
            sink.recorded,
        )
        assertTrue(offline.delivered.isEmpty())
    }

    @Test
    fun reportWaitsForCurrentReplyAndIsDeliveredOnce() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val first = FakeSurface().apply { hold = true }
        desk.attach(first)
        desk.track("t1", "订会议室")
        runCurrent()

        state.value = running()
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()
        state.value = TaskState.WaitingConfirmation(confirmation())
        runCurrent()
        // The first is being spoken, the next two are queued, and none has been delivered yet.
        assertEquals(1, first.gates.size)
        assertTrue(first.delivered.isEmpty())

        first.gates.removeFirst().complete(Unit)
        runCurrent()
        first.gates.removeFirst().complete(Unit)
        runCurrent()
        assertEquals(
            listOf(DeskEvent.Started("t1"), DeskEvent.NeedsAnswer("t1", "订几点的?", null)),
            first.delivered.map { it.event },
        )

        // The third is interrupted halfway through: the newly attached inlet receives it again, and neither the original inlet nor the sink receives it.
        val second = FakeSurface()
        desk.attach(second)
        first.gates.removeFirst().completeExceptionally(CancellationException("用户开口了"))
        runCurrent()
        assertEquals(listOf(DeskEvent.NeedsAction("t1", ActionKind.Confirm)), second.delivered.map { it.event })
        assertEquals(2, first.delivered.size)
        assertTrue(sink.recorded.isEmpty())

        // If no new inlet attaches when interrupted, it goes to the sink, again only once.
        second.hold = true
        state.value = TaskState.Succeeded("订好了十点的会议室")
        runCurrent()
        desk.attach(FakeSurface(online = false)).close()
        second.gates.removeFirst().completeExceptionally(CancellationException("用户开口了"))
        runCurrent()
        assertEquals(listOf(DeskEvent.Succeeded("t1", "订好了十点的会议室")), sink.recorded.map { it.second.event })
        assertEquals(1, second.delivered.size)
    }

    // Shows who holds the desk at the moment: a newly attached inlet replaces the previous one; closing the previous handle again does not affect the new one, and closing the new one leaves it empty.
    @Test
    fun attachedShowsWhoHoldsTheDesk() = runTest {
        val desk = desk()
        val first = FakeSurface()
        val second = FakeSurface()
        assertEquals(null, desk.attached.value)
        val a = desk.attach(first)
        assertTrue(desk.attached.value === first)
        val b = desk.attach(second)
        a.close()
        assertTrue(desk.attached.value === second)
        b.close()
        assertEquals(null, desk.attached.value)
    }

    @Test
    fun terminalStopsSubscriptionAndActiveDropsAfterDone() = runTest {
        val state = port.add("t1", running("打开日历"))
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        runCurrent()
        assertEquals(DeskTaskView("t1", "订会议室", DeskTaskPhase.Running, "打开日历", null, listOf("打开日历"), startedAt = 0L, steps = listOf(DeskStep("打开日历", 0L))), desk.active.value.single())

        state.value = TaskState.Succeeded("订好了")
        runCurrent()
        state.value = TaskState.Failed("不该再看到", FailKind.GaveUp)
        runCurrent()

        assertEquals(listOf(DeskEvent.Started("t1"), DeskEvent.Succeeded("t1", "订好了")), surface.delivered.map { it.event })
        assertEquals(DeskTaskPhase.Done, desk.active.value.single().state)
    }

    @Test
    fun activeKeepsLastThreeDistinctCleanedProgressLines() = runTest {
        val state = port.add("t1", running("打开「日历」 [10,20]"))
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        assertEquals(listOf("打开"), desk.active.value.single().recent)

        // Lines that differ only in coordinates or quote details do not count as a new line; repeats move to the end and are not recorded twice.
        for (narration in listOf("打开「日程」 [1,2]", "找日程", "选时间", "找日程", "填标题")) {
            state.value = running(narration)
            runCurrent()
        }
        val view = desk.active.value.single()
        assertEquals(listOf("选时间", "找日程", "填标题"), view.recent)
        assertEquals("填标题", view.line)

        state.value = TaskState.WaitingInput("要订哪一间?", null)
        runCurrent()
        assertEquals(listOf("找日程", "填标题", "要订哪一间?"), desk.active.value.single().recent)
    }

    @Test
    fun activeKeepsEveryStepWithItsTimeAndWhatItWaitsOn() = runTest {
        ledger.add(DeskTaskEntry("t1", "订会议室", "帮我订明天十点的会议室", 0L))
        now = 1_000L
        val state = port.add("t1", running("打开「日历」 [10,20]"))
        val desk = desk()
        desk.track("t1", "订会议室")
        runCurrent()
        for ((at, narration) in listOf(3_000L to "打开「日程」 [1,2]", 5_000L to "找日程", 9_000L to "选时间", 12_000L to "找日程")) {
            now = at
            state.value = running(narration)
            runCurrent()
        }
        now = 15_000L
        state.value = TaskState.WaitingInput("要订「三楼」还是「五楼」那间?", listOf("三楼", "五楼"))
        runCurrent()

        val view = desk.active.value.single()
        assertEquals("帮我订明天十点的会议室", view.goal)
        assertEquals(1_000L, view.startedAt)
        // Steps that differ only in details do not count as a new step; saying it again after a gap does.
        assertEquals(
            listOf(DeskStep("打开", 1_000L), DeskStep("找日程", 5_000L), DeskStep("选时间", 9_000L), DeskStep("找日程", 12_000L),
                DeskStep("要订还是那间?", 15_000L)),
            view.steps,
        )
        assertEquals(DeskNeed("要订「三楼」还是「五楼」那间?", listOf("三楼", "五楼")), view.need)
        assertEquals(15_000L, view.updatedAt)

        now = 20_000L
        state.value = TaskState.WaitingTakeover("pay", "要你本人确认付款")
        runCurrent()
        assertEquals(DeskNeed("要你本人确认付款", action = ActionKind.Takeover), desk.active.value.single().need)

        state.value = TaskState.Succeeded("x".repeat(DESK_RESULT_MAX_CHARS + 5))
        runCurrent()
        val done = desk.active.value.single()
        assertEquals(null, done.need)
        assertEquals(6, done.steps.size)
    }

    @Test
    fun queuedTaskShowsPositionAndReportsStartWhenItsTurnComes() = runTest {
        val state = port.add("t2", TaskState.Held(GateReason.queued("前面还有 1 个")))
        port.positions["t2"] = 1
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t2", "查快递")
        runCurrent()
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 1), desk.active.value.single().copy(line = null))

        port.positions.remove("t2")
        state.value = running()
        runCurrent()
        assertEquals(listOf(DeskEvent.Started("t2")), surface.delivered.map { it.event })
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Running, null, null, startedAt = 0L), desk.active.value.single())
    }

    /**
     * A task just dispatched that the task service has not picked up yet (state still Idle) and that is not in the start queue: it is starting, not queued.
     * If it were recorded as queued, the card would momentarily flash "queued", and the front desk, watching the task board, would also tell the user "still queued".
     */
    @Test
    fun startingTaskIsNotShownAsQueued() = runTest {
        val state = port.add("t1")
        val desk = desk()
        desk.track("t1", "看设置首页")
        runCurrent()
        assertEquals(DeskTaskView("t1", "看设置首页", DeskTaskPhase.Running, null, null, startedAt = 0L), desk.active.value.single())

        state.value = running()
        runCurrent()
        assertEquals(DeskTaskPhase.Running, desk.active.value.single().state)
    }

    /** Tasks being followed (dispatched or taken back) return true for [FrontDesk.tracks]; tasks the port does not know and that were never handed to it return false. */
    @Test
    fun tracksOnlyTasksItFollows() = runTest {
        port.add("t1")
        val desk = desk()
        assertFalse(desk.tracks("t1"))
        desk.track("t1", "看设置首页")
        desk.track("nope", "不认识")
        assertTrue(desk.tracks("t1"))
        assertFalse(desk.tracks("nope"))
    }

    /** Queued right after dispatch by prediction (the port gave a position): even when Idle it is still queued, with the position. */
    @Test
    fun idleTaskWithAQueuePositionIsQueued() = runTest {
        port.add("t2")
        port.positions["t2"] = 2
        val desk = desk()
        desk.track("t2", "查快递")
        runCurrent()
        assertEquals(DeskTaskView("t2", "查快递", DeskTaskPhase.Queued, null, 2), desk.active.value.single())
    }

    @Test
    fun restoreResubscribesRunningTasks() = runTest {
        val live = port.add("t1", running("打开日历"))
        port.add("t2", TaskState.Succeeded("查到了"))
        port.add("t3", TaskState.Failed("没网", FailKind.GaveUp))
        val entries = listOf(
            DeskTaskEntry("t1", "订会议室", "帮我订会议室", 1),
            DeskTaskEntry("t2", "查快递", "查一下快递", 2),
            DeskTaskEntry("t3", "看天气", "明天天气", 3),
        )
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.restore(entries)
        runCurrent()

        assertEquals(
            listOf(DeskTaskPhase.Running, DeskTaskPhase.Done, DeskTaskPhase.Failed),
            desk.active.value.map { it.state },
        )
        assertEquals(listOf("t1", "t2", "t3"), desk.active.value.map { it.taskId })
        assertTrue(surface.delivered.isEmpty())
        assertTrue(sink.recorded.isEmpty())
        assertEquals(entries, ledger.all())

        live.value = TaskState.WaitingInput("订几点的?")
        runCurrent()
        assertEquals(listOf(DeskReport(DeskEvent.NeedsAnswer("t1", "订几点的?", null), "订会议室")), surface.delivered)
    }

    @Test
    fun trackingATaskThatAlreadyEndedStillReportsItsResult() = runTest {
        port.add("t1", TaskState.Failed("没网", FailKind.GaveUp))
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "看天气")
        runCurrent()

        assertEquals(listOf(DeskReport(DeskEvent.Failed("t1", "没网"), "看天气")), surface.delivered)
        assertEquals(DeskTaskPhase.Failed, desk.active.value.single().state)
    }

    @Test
    fun trackingTheSameTaskTwiceSubscribesOnce() = runTest {
        val state = port.add("t1")
        val desk = desk()
        val surface = FakeSurface()
        desk.attach(surface)
        desk.track("t1", "订会议室")
        desk.track("t1", "订会议室")
        runCurrent()
        state.value = TaskState.WaitingInput("订几点的?")
        runCurrent()

        assertEquals(1, surface.delivered.size)
        assertEquals(1, desk.active.value.size)
        assertTrue(ledger.all().isEmpty())
    }

    private fun confirmation() = ConfirmationRequest(IrrevAction.order, target = "会议室", content = "十点", amount = null, app = "calendar")
}
