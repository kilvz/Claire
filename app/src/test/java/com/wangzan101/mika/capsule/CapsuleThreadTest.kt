package com.wangzan101.mika.capsule

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.chat.ui.Exchange
import com.wangzan101.mika.chat.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleThreadTest {
    @get:Rule val texts = ChineseTexts()

    private fun turn(user: String, outcome: String, status: String = "succeeded", runId: String? = null, steers: List<String> = emptyList()) =
        Turn(user, outcome, status, runId = runId, steers = steers)

    private fun live(key: String, state: TaskState, kind: CapsuleKind, runId: String? = null, draft: String? = null, line: String = "") = CardRun(
        CapsuleRun(key, "c1", state, goal = "问题$key", runId = runId,
            exchange = Exchange("问题$key", emptyList(), ExchangeStatus.Working, live = true, draft = draft)),
        kind, line,
    )

    private fun texts(t: ChatThread) = t.items.map {
        when (it) {
            is ThreadItem.User -> "你:${it.text}"
            is ThreadItem.Note -> "注:${it.text}"
            is ThreadItem.Answer -> if (it.cut) "断:${it.text}" else "答:${it.text}"
        }
    }

    // ---------- How a turn is laid out ----------

    @Test fun chatTurnIsBubbleAndFullAnswer() {
        // The answer keeps its Markdown untouched; layout happens inside the card
        val t = CapsuleThreads.build(listOf(PastTurn(turn("在吗", "# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |\n"))), 10, emptyList())
        assertEquals(listOf("你:在吗", "答:# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |"), texts(t))
        assertEquals("在吗", t.title)
    }

    @Test fun appTurnCollapsesToOneLineThenItsAnswer() {
        val past = listOf(
            PastTurn(turn("买牙膏", "下单了，共 ¥19.9", runId = "r1"), places = listOf("购物")),
            PastTurn(turn("查车票", "没有余票", status = "failed", runId = "r2"), places = listOf("12306", "浏览器", "地图")),
            PastTurn(turn("发消息", "已取消", status = "cancelled"), places = listOf("聊天")),
        )
        assertEquals(listOf(
            "你:买牙膏", "注:✓ 购物 · 办好了", "答:下单了，共 ¥19.9",
            "你:查车票", "注:12306、浏览器 等 · 没办成", "答:没有余票",
            "你:发消息", "注:聊天 · 已停止",
        ), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    @Test fun plainChatFailuresSayItAndSteersAreBubbles() {
        val past = listOf(
            PastTurn(turn("算一下", "模型没回", status = "failed")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", steers = listOf("短一点"))),
        )
        assertEquals(listOf("你:算一下", "注:没办成", "答:模型没回", "你:写首诗", "你:短一点", "注:已停止"), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    // A stopped turn: the line below "Stopped" is the handoff; while the handoff is still being written it shows "Organizing what was done…"
    @Test fun deskRepliesAndReportsReadAsRepliesNotFailures() {
        // Desk replies and desk reports are normal replies; the report turn has no user message, so no user bubble is drawn; a turn that only dispatched a task without replying leaves no empty answer
        val past = listOf(
            PastTurn(turn("帮我查下天气", "好,我让后台去查", status = DeskTurn.STATUS, runId = "desk:1")),
            PastTurn(turn("", "天气查好了:晴", status = DeskTurn.REPORT_STATUS, runId = "desk:2")),
            PastTurn(turn("再订个闹钟", "", status = DeskTurn.STATUS, runId = "desk:3")),
        )
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(listOf("你:帮我查下天气", "答:好,我让后台去查", "答:天气查好了:晴", "你:再订个闹钟"), texts(t))
        assertTrue(t.items.none { it is ThreadItem.Note })
    }

    @Test fun deskTurnThatCouldNotReplyShowsItsReason() {
        val failed = Turn("看看这个文件", "", DeskTurn.STATUS, note = "文件没准备好", runId = "desk:1")
        assertEquals(listOf("你:看看这个文件", "注:没办成", "答:文件没准备好"), texts(CapsuleThreads.build(listOf(PastTurn(failed)), 10, emptyList())))
    }

    @Test fun stoppedTurnShowsItsSummaryUnderTheNote() {
        val past = listOf(
            PastTurn(turn("看设置", "停止前看完了网络、蓝牙;显示、声音还没看。", status = "cancelled", runId = "r1"), places = listOf("设置")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", runId = "r2")),
        )
        assertEquals(listOf(
            "你:看设置", "注:设置 · 已停止", "答:停止前看完了网络、蓝牙;显示、声音还没看。",
            "你:写首诗", "注:已停止", "注:正在整理已做的事…",
        ), texts(CapsuleThreads.build(past, 10, emptyList(), summarizing = setOf("r2"))))
        // If the wrap-up cannot be written, only that line remains
        assertEquals(listOf("你:写首诗", "注:已停止"), texts(CapsuleThreads.build(past.drop(1), 10, emptyList())))
        // The same applies to a run that has not yet been recorded into the conversation
        val r = live("a", TaskState.Cancelled, CapsuleKind.Cancelled, runId = "r3")
        assertEquals(listOf("你:问题a", "注:已停止", "注:正在整理已做的事…"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(r), summarizing = setOf("r3"))))
    }

    @Test fun oldEvidenceSectionIsDropped() {
        val t = CapsuleThreads.build(listOf(PastTurn(turn("几点开门", "九点开门\n依据：官网"))), 10, emptyList())
        assertEquals(listOf("你:几点开门", "答:九点开门"), texts(t))
    }

    @Test fun placesAreAppsAndSitesInOrder() {
        fun act(target: String?) = RunEvent.Activity(0, 1, "act", "tap", target)
        val acts = listOf(act("com.shop.app"), act("网站 example.com"), act("com.shop.app"), act("ls -l"), act(null), act("/workspace/a.txt"))
        assertEquals(listOf("购物", "example.com"), CapsuleThreads.places(acts) { if (it == "com.shop.app") "购物" else it })
    }

    // ---------- The one in progress ----------

    @Test fun streamingAnswerGrowsAfterTheQuestion() {
        val r = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, runId = "ra", draft = "**今天**晴")
        val t = CapsuleThreads.build(listOf(PastTurn(turn("早", "早上好", runId = "r0"))), 10, listOf(r))
        assertEquals(listOf("你:早", "答:早上好", "你:问题a", "答:**今天**晴"), texts(t))
        assertTrue((t.items.last() as ThreadItem.Answer).streaming)
        // No text written yet: say what is being done right now
        val thinking = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, line = "在想下一步")
        assertEquals(listOf("你:问题a", "注:在想下一步"), texts(CapsuleThreads.build(emptyList(), 10, listOf(thinking))))
    }

    @Test fun liveWaitsShowTheQuestionAndAppLine() {
        val ask = live("a", TaskState.WaitingInput("要大的还是小的？"), CapsuleKind.NeedAnswer)
        assertEquals(listOf("你:问题a", "答:要大的还是小的？"), texts(CapsuleThreads.build(emptyList(), 10, listOf(ask))))
        val inApp = live("b", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "在看")
        assertEquals(listOf("你:问题b", "注:购物 · 在干活", "答:在看"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(inApp)) { listOf("购物") }))
        val done = live("c", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals(listOf("你:问题c", "注:✓ 购物 · 办好了", "答:好了"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(done)) { listOf("购物") }))
    }

    @Test fun liveTailSkipsWhatIsAlreadyRecorded() {
        val recorded = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r1")
        val notYet = live("b", TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed, runId = "r2")
        val queuedCancel = live("c", TaskState.Cancelled, CapsuleKind.Cancelled)
        val going = live("d", TaskState.Running(1, "n"), CapsuleKind.Running)
        val other = live("e", TaskState.Running(1, "n"), CapsuleKind.Running).let { it.copy(run = it.run.copy(conversationId = "c2")) }
        // Ones that ended in the card are ordered most recent first; within the exchange, ones that finished earlier come first and in-progress ones last
        val tail = CapsuleThreads.liveTail(listOf(going, other, notYet, recorded, queuedCancel), "c1", setOf("r1"))
        assertEquals(listOf("b", "d"), tail.map { it.run.key })
    }

    @Test fun keysStayWhenTheLiveTurnGetsRecorded() {
        val r = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r9")
        val before = CapsuleThreads.build(emptyList(), 10, listOf(r)).items.map { it.key }
        val after = CapsuleThreads.build(listOf(PastTurn(turn("问题a", "好了", runId = "r9"))), 10, emptyList()).items.map { it.key }
        assertEquals(before, after)
    }

    // ---------- Paging and where it stops when opened ----------

    @Test fun latestTenTurnsFirstThenEarlier() {
        val past = (1..25).map { PastTurn(turn("问$it", "答$it", runId = "r$it")) }
        val first = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList())
        assertTrue(first.hasEarlier)
        assertEquals("你:问16", texts(first).first())
        assertEquals("问1", first.title)
        val more = CapsuleThreads.build(past, CapsuleThreads.PAGE * 2, emptyList())
        assertEquals("你:问6", texts(more).first())
        // The turns added earlier all come first: the keys of the existing items stay unchanged, so the list keeps its position
        assertEquals(first.items.map { it.key }, more.items.takeLast(first.items.size).map { it.key })
        val all = CapsuleThreads.build(past, CapsuleThreads.PAGE * 3, emptyList())
        assertTrue(!all.hasEarlier)
        assertEquals(50, all.items.size)
    }

    @Test fun anchorIsTheLatestQuestion() {
        val past = listOf(PastTurn(turn("一", "甲")), PastTurn(turn("二", "乙\n很长的回答")))
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(2, t.anchor)
        assertEquals(ThreadItem.User("p1-u0", "二"), t.items[t.anchor])
        // The in-progress question is last: reading starts there
        val going = CapsuleThreads.build(past, 10, listOf(live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")))
        assertEquals("你:问题a", texts(going)[going.anchor])
        assertEquals(0, ChatThread(emptyList()).anchor)
    }

    // Steers sent while running follow the original message with their destination written below (the same wording as the chat page); the newest one is where the view lands when opened
    @Test fun liveSteersFollowTheGoalWithWhereTheyWent() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成后天", SteerState.Joined),
            SteerNote("s2", "顺便订午饭", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        val users = t.items.filterIsInstance<ThreadItem.User>()
        assertEquals(listOf("问题a", "改成后天", "顺便订午饭"), users.map { it.text })
        assertEquals(listOf(null, "已加入当前任务", "已收到，下一步调整当前任务"), users.map { it.note })
        assertEquals("你:顺便订午饭", texts(t)[t.anchor])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }

    @Test fun withdrawnSteersAreNotShown() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "已撤回的话", SteerState.Withdrawn),
            SteerNote("s2", "留下的话", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("问题a", "留下的话"), t.items.filterIsInstance<ThreadItem.User>().map { it.text })
    }

    // The part of the reply that a steer interrupted is ordered before that steer (as on the chat page); a recorded turn and an in-progress run are ordered the same way, and keys do not repeat
    @Test fun interruptedReplySitsAboveItsSteer() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴,最高", ""))))
        assertEquals(listOf("你:查天气", "断:今天晴,最高", "你:改成查明天", "你:顺便看看后天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天")
        val run = base.copy(run = base.run.copy(steers = listOf(SteerNote("s1", "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "断:今天晴,最高", "你:改成查明天", "答:明天"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:改成查明天", texts(t)[t.anchor])
    }

    // A reply that had already finished before the steer is ordered before that steer, styled like an answer; a steer not yet handed to the model is ordered after the answer being written
    @Test fun finishedReplyAndWaitingSteerOrder() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。"))))
        assertEquals(listOf("你:查天气", "答:今天晴,最高 25 度。", "你:改成查明天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天多")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成查明天", SteerState.Joined, finished = "今天晴"),
            SteerNote("s2", "简短点", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "答:今天晴", "你:改成查明天", "答:明天多", "你:简短点"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:简短点", texts(t)[t.anchor])
    }
}
