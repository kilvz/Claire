package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.cap.web.KeptPage
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.RunProgress
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.takeover.Takeover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleModelTest {
    @get:Rule val texts = ChineseTexts()

    private val model = CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null })

    private fun inputs(
        vararg runs: CapsuleRun,
        mode: CapsuleMode = CapsuleMode.DuringTasks,
        foreground: Boolean = false,
        interactive: Boolean = true,
        service: Boolean = true,
        cardOpen: Boolean = false,
        kept: KeptPage? = null,
    ) = CapsuleInputs(runs.toList(), mode, foreground, interactive, service, cardOpen, kept)

    private val kept = KeptPage(1, "c-k", "r-k", "把这门课点开,挂着别关", "course.example.com", null, null, 0, 7_200_000)

    private fun run(key: String, state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) = CapsuleRun(key, "c-$key", state, stage)
    private fun running(said: String? = null, step: Int = 3, reason: String? = null, progress: RunProgress? = null) =
        TaskState.Running(step, "note", "tap", progress, reason, narration = said)
    private val takeover = TaskState.WaitingTakeover("payment", "请输入支付密码")
    private val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))

    private fun shown(v: CapsuleView): CapsuleView.Shown = v as? CapsuleView.Shown ?: error("expected shown, got $v")

    // ---------- When it is not shown ----------

    @Test fun hiddenWhenOffForegroundLockedOrNoService() {
        val r = run("a", running("在搜牙膏"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, mode = CapsuleMode.Off), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, interactive = false), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(r, service = false), 0))
        assertEquals("在搜牙膏", shown(model.update(inputs(r), 0)).text)
    }

    @Test fun noTasksHiddenDuringTasksIdleIconWhenAlways() {
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 0))
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), 0)).kind)
        // Even with always-show it is still hidden inside the Claire UI and when the screen is locked
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Always, interactive = false), 0))
    }

    // ---------- The page kept running in the background ----------

    @Test fun keptPageStaysShownWhenNoTaskRuns() {
        // No tasks: it expands into "web page running in background" and refers to the conversation it came from; with always-show it is also this, not the idle icon
        for (mode in listOf(CapsuleMode.DuringTasks, CapsuleMode.Always)) {
            val v = shown(model.update(inputs(mode = mode, kept = kept), 0))
            assertEquals(CapsuleKind.KeptPage, v.kind)
            assertEquals("后台网页运行中", v.text)
            assertEquals("c-k", v.conversationId)
            assertTrue(v.pill)
        }
        // With the switch set to never-show, inside Claire, or while the screen is locked it is still hidden (the notification covers it)
        assertEquals(CapsuleView.Hidden, model.update(inputs(mode = CapsuleMode.Off, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(foreground = true, kept = kept), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(interactive = false, kept = kept), 0))
    }

    @Test fun runningTaskAndFreshResultComeBeforeKeptPage() {
        assertEquals(CapsuleKind.Running, shown(model.update(inputs(run("a", running("在搜")), kept = kept), 0)).kind)
        // A freshly finished result lingers for a while; only after that does the kept page show
        val done = run("a", TaskState.Succeeded("好了"))
        assertEquals(CapsuleKind.Done, shown(model.update(inputs(done, kept = kept), 10)).kind)
        assertEquals(CapsuleKind.KeptPage, shown(model.update(inputs(done, kept = kept), 10 + CapsuleModel.LINGER_MS)).kind)
        assertTrue(!CapsuleKind.KeptPage.active && !CapsuleKind.KeptPage.needsYou)
    }

    @Test fun modeParsesWithAlwaysDefault() {
        assertEquals(CapsuleMode.Always, CapsuleMode.parse(null))
        assertEquals(CapsuleMode.Always, CapsuleMode.parse("something-old"))
        CapsuleMode.entries.forEach { assertEquals(it, CapsuleMode.parse(it.name)) }
    }

    // ---------- That single line of text ----------

    @Test fun runningTextNarrationThenReasonThenFallback() {
        assertEquals("在搜 牙膏", CapsuleModel.runningText(running("  在搜\n牙膏 ", reason = "找搜索框")))
        assertEquals("找搜索框", CapsuleModel.runningText(running(" ", reason = "找搜索框")))
        assertEquals(CapsuleModel.THINKING, CapsuleModel.runningText(running()))
        assertEquals(CapsuleModel.PREPARING, CapsuleModel.runningText(running(step = 0)))
        assertEquals("照着路线做 3/7", CapsuleModel.runningText(running("在搜牙膏", progress = RunProgress.Replay(3, 7))))
        assertEquals("一次做 4 步", CapsuleModel.runningText(running(progress = RunProgress.Batch(4))))
        assertEquals("已保存 · 36/36 · 第 36 条", CapsuleModel.runningText(running(progress = RunProgress.Task("已保存", 36, 36, "第 36 条"))))
    }

    @Test fun eachStateHasItsLine() {
        fun line(state: TaskState, stage: Takeover.Stage = Takeover.Stage.None) =
            shown(CapsuleModel(reasonLabel = { if (it == "payment") "付款" else null }).update(inputs(run("a", state, stage)), 0))
        assertEquals(CapsuleKind.NeedTakeover to "需要你：付款", line(takeover, Takeover.Stage.Waiting).let { it.kind to it.text })
        assertEquals("需要你：请登录", line(TaskState.WaitingTakeover("login", "请登录"), Takeover.Stage.Waiting).text)
        assertEquals(CapsuleKind.NeedConfirm to "要你同意：发送", line(confirm).let { it.kind to it.text })
        assertEquals(CapsuleKind.NeedAnswer to "Claire 问你：要哪个颜色？", line(TaskState.WaitingInput("要哪个颜色？")).let { it.kind to it.text })
        assertEquals(CapsuleKind.Held to "先等等：你打开了这个 App", line(TaskState.Held(GateReason("paused", "你打开了这个 App"))).let { it.kind to it.text })
        // You have to act on the phone (pick one in the system chooser): that counts as needing you
        val pick = line(TaskState.Held(GateReason("waiting_user", "「聊天」在问用哪个，请在屏幕上选一下", needsYou = true)))
        assertEquals(CapsuleKind.NeedStep to "需要你：「聊天」在问用哪个，请在屏幕上选一下", pick.kind to pick.text)
        assertTrue(pick.kind.needsYou)
        assertEquals("先等等", line(TaskState.Held(GateReason("waiting_unlock"))).text)
        val bar = line(takeover, Takeover.Stage.HandedOver)
        assertEquals(CapsuleKind.HandedOver, bar.kind)
        assertEquals(CapsuleModel.HANDED_OVER, bar.text)
        assertEquals("付款 · 弄好后点右边", bar.detail)
        // Already handed back and the loop has not moved on yet: no longer needs you
        assertEquals(CapsuleKind.Running, line(takeover).kind)
    }

    // ---------- Priority and item counts ----------

    @Test fun highestPriorityWinsAndCountsTasks() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", TaskState.WaitingInput("要哪个？"))), 0))
        assertEquals(CapsuleKind.NeedAnswer, v.kind)
        assertEquals("b", v.runKey)
        assertEquals("c-b", v.conversationId)
        assertEquals(2, v.count)
        assertEquals(1, v.needCount)
        assertEquals("1 件要你处理 · 共 2 件", v.text)
    }

    @Test fun severalRunningAddCount() {
        val v = shown(model.update(inputs(run("a", running("在搜牙膏")), run("b", running("在看天气"))), 0))
        // When equally urgent, the one that started first is mentioned
        assertEquals("a", v.runKey)
        assertEquals("在搜牙膏 · 共 2 件", v.text)
        assertEquals(0, v.needCount)
    }

    @Test fun priorityOrder() {
        val held = TaskState.Held(GateReason("paused", "等一下"))
        val all = listOf(
            run("run", running("x")), run("held", held), run("ask", TaskState.WaitingInput("?")), run("ok", confirm),
            run("need", takeover, Takeover.Stage.Waiting), run("hand", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.HandedOver),
        )
        val expected = listOf("hand", "need", "ok", "ask", "held", "run")
        for (i in expected.indices) {
            val left = all.filter { it.key in expected.drop(i) }
            assertEquals(expected[i], shown(CapsuleModel().update(inputs(*left.toTypedArray()), 0)).runKey)
        }
    }

    @Test fun handedOverBarSaysWhoIsWaitingBehind() {
        val v = shown(model.update(inputs(
            run("a", takeover, Takeover.Stage.HandedOver), run("b", TaskState.WaitingTakeover("login", "登录"), Takeover.Stage.Waiting),
        ), 0))
        assertEquals(CapsuleKind.HandedOver, v.kind)
        assertEquals("处理好这件后，还有 1 件等你", v.detail)
    }

    // ---------- Each sentence stays for at least 1.5 seconds ----------

    @Test fun sentenceStaysAtLeastMinimumAndLatestWins() {
        assertEquals("一", shown(model.update(inputs(run("a", running("一"))), 0)).text)
        assertEquals("一", shown(model.update(inputs(run("a", running("二"))), 500)).text)
        val v = shown(model.update(inputs(run("a", running("三"))), 900))
        assertEquals("一", v.text)
        assertEquals(CapsuleModel.MIN_SENTENCE_MS, model.nextChangeAt)
        // Then switch to whichever sentence is newest at that moment; the middle sentence never appears
        assertEquals("三", shown(model.update(inputs(run("a", running("三"))), CapsuleModel.MIN_SENTENCE_MS)).text)
        assertNull(model.nextChangeAt)
    }

    @Test fun stateChangeSwitchesAtOnce() {
        model.update(inputs(run("a", running("一"))), 0)
        val v = shown(model.update(inputs(run("a", TaskState.WaitingInput("要哪个？"))), 100))
        assertEquals("Claire 问你：要哪个？", v.text)
    }

    // ---------- Only widens, never narrows ----------

    @Test fun widthEpochHoldsWithinStateAndRenewsOnChange() {
        val first = shown(model.update(inputs(run("a", running("一"))), 0)).widthEpoch
        assertEquals(first, shown(model.update(inputs(run("a", running("长一点的一句话"))), 2_000)).widthEpoch)
        assertEquals(first, shown(model.update(inputs(run("a", running("短"))), 4_000)).widthEpoch)
        val need = shown(model.update(inputs(run("a", TaskState.WaitingInput("?"))), 5_000)).widthEpoch
        assertNotEquals(first, need)
        val back = shown(model.update(inputs(run("a", running("一"))), 6_000)).widthEpoch
        assertNotEquals(need, back)
        // Switching to another task also fixes the width anew
        assertNotEquals(back, shown(model.update(inputs(run("b", running("一"))), 7_000)).widthEpoch)
    }

    // ---------- A ball or expanded ----------

    @Test fun runningRestsAsBallAndStepsNeverExpandIt() {
        val first = shown(model.update(inputs(run("a", running("在搜牙膏"))), 0))
        assertEquals(CapsuleKind.Running to false, first.kind to first.pill)
        // After several step changes: still a single ball
        for ((t, said) in listOf(2_000L to "在比价", 4_000L to "在看运费", 6_000L to "在下单")) {
            assertTrue(!shown(model.update(inputs(run("a", running(said))), t)).pill)
        }
        assertTrue(!shown(model.update(inputs(run("a", TaskState.Held(GateReason("paused", "等一下")))), 7_000)).pill)
    }

    @Test fun severalRunsCountOnTheBall() {
        val v = shown(model.update(inputs(run("a", running("x")), run("b", running("y")), run("c", TaskState.Held(GateReason("paused")))), 0))
        assertEquals(3, v.count)
        assertTrue(!v.pill)
    }

    @Test fun needYouStaysAPillUntilTheCardIsOpened() {
        model.update(inputs(run("a", running("x"))), 0)
        val need = shown(model.update(inputs(run("a", confirm)), 100))
        assertEquals(CapsuleKind.NeedConfirm to true, need.kind to need.pill)
        // It does not collapse on its own and schedules no next change
        assertNull(model.nextChangeAt)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 600_000)).pill)
        // The card was opened and seen, so after collapsing it is a ball (still orange)
        model.update(inputs(run("a", confirm), cardOpen = true), 600_100)
        val ball = shown(model.update(inputs(run("a", confirm)), 600_200))
        assertEquals(CapsuleKind.NeedConfirm to false, ball.kind to ball.pill)
    }

    @Test fun aNewNeedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        assertTrue(!shown(model.update(inputs(run("a", confirm)), 20)).pill)
        // The same task switched to a different kind of wait: expand
        val ask = TaskState.WaitingInput("要哪个颜色？")
        assertTrue(shown(model.update(inputs(run("a", ask)), 30)).pill)
        model.update(inputs(run("a", ask), cardOpen = true), 40)
        assertTrue(!shown(model.update(inputs(run("a", ask)), 50)).pill)
        // Another task also needs you: expand and show "2 items need you"
        val two = shown(model.update(inputs(run("a", ask), run("b", takeover, Takeover.Stage.Waiting)), 60))
        assertEquals("2 件要你处理 · 共 2 件" to true, two.text to two.pill)
    }

    @Test fun aNeedThatComesBackAfterBeingResolvedExpandsAgain() {
        model.update(inputs(run("a", confirm)), 0)
        model.update(inputs(run("a", confirm), cardOpen = true), 10)
        model.update(inputs(run("a", running("x"))), 20)
        assertTrue(shown(model.update(inputs(run("a", confirm)), 30)).pill)
    }

    // ---------- After it is done ----------

    @Test fun doneExpandsThenBallWithBadgeThenGone() {
        model.update(inputs(run("a", running("x"))), 0)
        val done = shown(model.update(inputs(run("a", TaskState.Succeeded("好了"))), 1_000))
        assertEquals(CapsuleKind.Done to true, done.kind to done.pill)
        assertEquals("c-a", done.conversationId)
        assertEquals(1_000 + CapsuleModel.PILL_MS, model.nextChangeAt)
        // The task has been dropped from the list and the model remembers it by itself; it shrinks back to a ball and still reads done (badge)
        val ball = shown(model.update(inputs(), 1_000 + CapsuleModel.PILL_MS))
        assertEquals(CapsuleKind.Done to false, ball.kind to ball.pill)
        assertEquals(1_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun afterLingerAlwaysModeReturnsToIdle() {
        model.update(inputs(run("a", TaskState.Failed("没找到", FailKind.GaveUp)), mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
    }

    @Test fun endedRunStaysGoneThoughStillListed() {
        // When the task list stops changing, the caller keeps listing that stopped task
        val a = run("a", TaskState.Cancelled)
        assertTrue(shown(model.update(inputs(a), 0)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS))
        // Enter Claire and leave again: "stopped" is not expanded again
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), CapsuleModel.LINGER_MS + 1_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), CapsuleModel.LINGER_MS + 2_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun endedWhileHiddenCountsItsTimeToo() {
        // It ended while inside Claire and the user returned to another App 10 seconds later: it does not appear again
        val a = run("a", TaskState.Succeeded("好了"))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 60_100))
        // The same holds for one that ended while the screen was off or locked
        val b = run("b", TaskState.Cancelled)
        assertEquals(CapsuleView.Hidden, model.update(inputs(b, interactive = false), 100_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_000))
        assertEquals(CapsuleView.Hidden, model.update(inputs(b), 200_100))
    }

    @Test fun failedAndCancelledHaveTheirLines() {
        assertEquals(CapsuleKind.Failed, shown(CapsuleModel().update(inputs(run("a", TaskState.Failed("x", FailKind.Model))), 0)).kind)
        val stopped = shown(CapsuleModel().update(inputs(run("a", TaskState.Cancelled)), 0))
        assertEquals(CapsuleKind.Cancelled to "已停止", stopped.kind to stopped.text)
    }

    @Test fun activeTaskOutranksLingeringDone() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        val v = shown(model.update(inputs(run("b", running("在看天气"))), 100))
        assertEquals(CapsuleKind.Running, v.kind)
        assertEquals("b", v.runKey)
        // Ended ones do not count toward "N items in total"
        assertEquals(1, v.count)
    }

    // ---------- While the card is open ----------

    @Test fun openCardStopsTheClockForEndedRuns() {
        model.update(inputs(run("a", running("x"))), 0)
        model.update(inputs(run("a", TaskState.Succeeded("好了")), cardOpen = true), 1_000)
        // While expanded: it does not disappear even after a long time and schedules no next change
        val v = shown(model.update(inputs(cardOpen = true), 100_000))
        assertEquals(CapsuleKind.Done, v.kind)
        assertNull(model.nextChangeAt)
        assertEquals(listOf("a"), model.cardRuns().map { it.run.key })
        // After collapsing: it has been seen, so it goes straight to a ball with a badge and the remaining time keeps running
        val after = shown(model.update(inputs(), 200_000))
        assertEquals(CapsuleKind.Done to false, after.kind to after.pill)
        assertEquals(200_000 + CapsuleModel.LINGER_MS, model.nextChangeAt)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 200_000 + CapsuleModel.LINGER_MS))
    }

    @Test fun openCardKeepsAlreadyLingeringRunsUntilClosed() {
        model.update(inputs(run("a", TaskState.Succeeded("好了"))), 0)
        model.update(inputs(cardOpen = true), 5_000)
        assertEquals(1, model.cardRuns().size)
        model.update(inputs(cardOpen = true), 1_000_000)
        assertEquals(1, model.cardRuns().size)
        // Time elapsed before expanding still counts: after collapsing only LINGER_MS - 5_000 is left
        assertTrue(!shown(model.update(inputs(), 1_000_000)).pill)
        assertEquals(CapsuleView.Hidden, model.update(inputs(), 1_000_000 + CapsuleModel.LINGER_MS - 5_000))
    }

    @Test fun idleIconOpensTheIdleCardAfterAResultHasGone() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a, mode = CapsuleMode.Always), 0)
        assertEquals(CapsuleKind.Idle, shown(model.update(inputs(a, mode = CapsuleMode.Always), CapsuleModel.LINGER_MS)).kind)
        // Opening the idle icon while the caller still lists that stopped task: the idle card is shown, not that task
        val v = shown(model.update(inputs(a, mode = CapsuleMode.Always, cardOpen = true), CapsuleModel.LINGER_MS + 60_000))
        assertEquals(CapsuleKind.Idle, v.kind)
        val idle = CardView.Chat("c-b", title = "订票")
        assertEquals(idle, CapsuleCards.of(model.cardRuns(), idle = idle))
    }

    @Test fun deletedConversationsEndedRunsLeaveAtOnce() {
        val a = run("a", TaskState.Cancelled)
        val a2 = CapsuleRun("a2", "c-a", TaskState.Succeeded("好了"))
        val b = run("b", TaskState.Succeeded("好了"))
        val r = run("r", running("在搜"))
        model.update(inputs(a, a2, b, r), 0)
        // Every finished run in this conversation is dropped
        model.forget("c-a")
        // In-progress ones are unaffected
        model.forget("c-r")
        // They do not come back even though the caller still lists them
        model.update(inputs(a, a2, b, r), 100)
        assertEquals(listOf("r", "b"), model.cardRuns().map { it.run.key })
        model.forget("c-b")
        // One that was still running when deleted is shown as usual when it ends later (if the conversation delete does not wait for it to wrap up, the round in which its wrap-up is stored recreates the conversation)
        val v = shown(model.update(inputs(a, a2, b, run("r", TaskState.Cancelled)), 200))
        assertEquals(CapsuleKind.Cancelled to "c-r", v.kind to v.conversationId)
        assertEquals(listOf("r"), model.cardRuns().map { it.run.key })
    }

    @Test fun deletedConversationLeavesTheOpenCardToo() {
        val a = run("a", TaskState.Cancelled)
        model.update(inputs(a), 0)
        model.update(inputs(a, cardOpen = true), 1_000)
        // Deleted while the card is open (clock stopped): removed immediately, and it does not come back after collapsing
        model.forget("c-a")
        assertTrue(model.cardRuns().isEmpty())
        model.update(inputs(a, cardOpen = true), 1_100)
        assertTrue(model.cardRuns().isEmpty())
        assertEquals(CapsuleView.Hidden, model.update(inputs(a), 5_000))
        assertNull(model.nextChangeAt)
    }

    @Test fun cardRunsActiveByPriorityThenEndedNewestFirst() {
        model.update(inputs(run("old", TaskState.Succeeded("a")), run("r", running("在搜"))), 0)
        model.update(inputs(run("new", TaskState.Failed("x", FailKind.Model)), run("r", running("在搜")), run("q", TaskState.WaitingInput("要哪个？"))), 10)
        val rows = model.cardRuns()
        assertEquals(listOf("q", "r", "new", "old"), rows.map { it.run.key })
        assertEquals(CapsuleKind.NeedAnswer to "Claire 问你：要哪个？", rows[0].kind to rows[0].line)
        assertEquals(0L, rows[1].startedAt)
        assertNull(rows[0].startedAt)
        assertEquals(CapsuleKind.Failed, rows[2].kind)
    }

    @Test fun cardRunsKnowTakeoverQueueOrder() {
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting)), 0)
        model.update(inputs(run("a", takeover, Takeover.Stage.Waiting), run("b", takeover, Takeover.Stage.Waiting)), 10)
        val rows = model.cardRuns().associateBy { it.run.key }
        assertEquals(0 to 1, rows.getValue("a").ahead to rows.getValue("a").behind)
        assertEquals(1 to 0, rows.getValue("b").ahead to rows.getValue("b").behind)
        // The one in front handed back: the ones behind move to the front
        model.update(inputs(run("a", running("接着做")), run("b", takeover, Takeover.Stage.Waiting)), 20)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- Takeover queue ----------

    private fun queued(key: String, ahead: Int, behind: Int, stage: Takeover.Stage = Takeover.Stage.Waiting) =
        run(key, takeover, stage).copy(queueAhead = ahead, queueBehind = behind)

    @Test fun realQueueOrderBeatsFirstSeen() {
        // a appeared first, but b is ahead in the queue: both the capsule and the card mention b first
        model.update(inputs(queued("a", 1, 0)), 0)
        val v = shown(model.update(inputs(queued("a", 1, 0), queued("b", 0, 1)), 10))
        assertEquals("b", v.runKey)
        val rows = model.cardRuns()
        assertEquals(listOf("b", "a"), rows.map { it.run.key })
        assertEquals(0 to 1, rows[0].ahead to rows[0].behind)
        assertEquals(1 to 0, rows[1].ahead to rows[1].behind)
    }

    @Test fun barCountsWhoIsBehindInTheRealQueue() {
        val v = shown(model.update(inputs(queued("a", 0, 2, Takeover.Stage.HandedOver), queued("b", 1, 1)), 0))
        assertEquals("处理好这件后，还有 2 件等你", v.detail)
    }

    @Test fun afterHandBackTheNextOneNeedsYouAtOnce() {
        model.update(inputs(queued("a", 0, 1, Takeover.Stage.HandedOver), queued("b", 1, 0)), 0)
        // a hands back and continues: b moves to the front and immediately becomes "needs you" (without waiting for sentence throttling)
        val v = shown(model.update(inputs(run("a", running("接着做")), queued("b", 0, 0)), 100))
        assertEquals(CapsuleKind.NeedTakeover to "b", v.kind to v.runKey)
        assertEquals(0, model.cardRuns().first { it.run.key == "b" }.ahead)
    }

    // ---------- Translucency ----------

    @Test fun ballFadesAfterIdleButNeverWhenNeedingYouOrExpanded() {
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS - 1))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Running, 0, pill = false, idleFor = CapsuleFade.IDLE_MS))
        assertEquals(CapsuleFade.BALL, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = false, idleFor = 60_000))
        // Expanded, needs you, another item needs you, or the "your turn" row: no fading
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Done, 0, pill = true, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedConfirm, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.Running, 1, pill = false, idleFor = 60_000))
        assertEquals(1f, CapsuleFade.alpha(CapsuleKind.HandedOver, 0, pill = false, idleFor = 60_000))
    }

    @Test fun configuredIdleOpacityNeverDimsAttentionStates() {
        for (opacity in CapsuleIdleOpacity.entries) {
            assertEquals(opacity.alpha, CapsuleFade.alpha(CapsuleKind.Idle, 0, false, 60_000, opacity.alpha))
            assertEquals(opacity.alpha, CapsuleFade.alpha(CapsuleKind.Running, 0, false, 60_000, opacity.alpha))
            assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedConfirm, 1, false, 60_000, opacity.alpha))
            assertEquals(1f, CapsuleFade.alpha(CapsuleKind.NeedAnswer, 0, true, 60_000, opacity.alpha))
            assertEquals(1f, CapsuleFade.alpha(CapsuleKind.HandedOver, 0, false, 60_000, opacity.alpha))
        }
    }

    // ---------- Screen reader ----------

    @Test fun ballDescribesItsStatus() {
        assertEquals("Claire 在干活：在搜牙膏 · 共 2 件，点开看详情", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Running, "在搜牙膏 · 共 2 件", count = 2)))
        assertEquals("要你同意：发送，点开处理", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.NeedConfirm, "要你同意：发送")))
        assertEquals("Claire 先等等：排队中，点开看详情", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Held, "先等等：排队中")))
        assertEquals("Claire 办好了 · 点开看结果", CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Done, "办好了 · 点开看结果")))
        assertEquals(CapsuleCopy.IDLE, CapsuleCopy.describe(CapsuleView.Shown(CapsuleKind.Idle, "")))
    }

    // ---------- Worth notifying ----------

    private fun notices(n: Int, vararg runs: CapsuleRun, mode: CapsuleMode = CapsuleMode.Always, kept: KeptPage? = null,
        foreground: Boolean = false, interactive: Boolean = true, service: Boolean = true) =
        inputs(*runs, mode = mode, foreground = foreground, interactive = interactive, service = service, kept = kept).copy(notices = n)

    @Test fun noticeShowsOnlyCountOnTheIcon() {
        val v = shown(model.update(notices(3), 0))
        assertEquals(CapsuleKind.Notice, v.kind)
        assertEquals(3, v.count)
        // No text at all is written on the capsule (let alone the text from the notification), and it does not expand or change on a timer
        assertEquals("", v.text)
        assertNull(v.detail)
        assertTrue(!v.pill)
        assertNull(v.runKey)
        assertNull(v.conversationId)
        assertNull(model.nextChangeAt)
        assertTrue(!CapsuleKind.Notice.active && !CapsuleKind.Notice.needsYou)
        // The screen reader only announces the count
        assertEquals("Claire，3 条值得提醒", CapsuleCopy.describe(v))
        // When there are none it returns to the idle icon
        assertEquals(CapsuleKind.Idle, shown(model.update(notices(0), 1)).kind)
    }

    @Test fun noticeRanksAboveIdleBelowEverythingElse() {
        val live = listOf(
            run("a", running("在搜")) to CapsuleKind.Running,
            run("a", takeover, Takeover.Stage.Waiting) to CapsuleKind.NeedTakeover,
            run("a", takeover, Takeover.Stage.HandedOver) to CapsuleKind.HandedOver,
            run("a", confirm) to CapsuleKind.NeedConfirm,
            run("a", TaskState.WaitingInput("哪个?", null)) to CapsuleKind.NeedAnswer,
            run("a", TaskState.Held(GateReason("queue", "排队中"))) to CapsuleKind.Held,
            run("a", TaskState.Held(GateReason("pick", "选一个", needsYou = true))) to CapsuleKind.NeedStep,
        )
        for (mode in listOf(CapsuleMode.Always, CapsuleMode.DuringTasks)) for ((r, kind) in live) {
            assertEquals(kind, shown(CapsuleModel().update(notices(2, r, mode = mode), 0)).kind)
        }
        // A freshly ended result (done, failed, stopped) lingers first; only after that does the notice show
        for (end in listOf(TaskState.Succeeded("好了"), TaskState.Failed("不行", FailKind.Model), TaskState.Cancelled)) {
            val m = CapsuleModel()
            val done = run("a", end)
            assertNotEquals(CapsuleKind.Notice, shown(m.update(notices(2, done), 10)).kind)
            assertEquals(CapsuleKind.Notice, shown(m.update(notices(2, done), 10 + CapsuleModel.LINGER_MS)).kind)
        }
        // The kept page ranks above it
        assertEquals(CapsuleKind.KeptPage, shown(CapsuleModel().update(notices(2, kept = kept), 0)).kind)
        assertTrue(CapsuleKind.KeptPage.ordinal < CapsuleKind.Notice.ordinal && CapsuleKind.Notice.ordinal < CapsuleKind.Idle.ordinal)
    }

    @Test fun noticeOnlyWhenAlwaysAndHiddenUnderEveryHideRule() {
        // "Show during tasks" only covers tasks: with no tasks it does not appear for notices (they are reached from the proactive abilities page)
        assertEquals(CapsuleView.Hidden, model.update(notices(2, mode = CapsuleMode.DuringTasks), 0))
        assertEquals(CapsuleView.Hidden, model.update(notices(2, mode = CapsuleMode.Off), 0))
        assertEquals(CapsuleView.Hidden, model.update(notices(2, foreground = true), 0))
        assertEquals(CapsuleView.Hidden, model.update(notices(2, interactive = false), 0))
        assertEquals(CapsuleView.Hidden, model.update(notices(2, service = false), 0))
        assertNull(model.nextChangeAt)
        // When tasks exist, tasks are shown as before
        assertEquals(CapsuleKind.Running, shown(model.update(notices(2, run("a", running("在搜")), mode = CapsuleMode.DuringTasks), 0)).kind)
    }
}
