package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.frontdesk.DeskBrain
import com.wangzan101.mika.core.frontdesk.DeskLedger
import com.wangzan101.mika.core.frontdesk.DeskReport
import com.wangzan101.mika.core.frontdesk.DeskSink
import com.wangzan101.mika.core.frontdesk.DeskSurface
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.frontdesk.DeskTaskPort
import com.wangzan101.mika.core.frontdesk.DeskToolExecutor
import com.wangzan101.mika.core.frontdesk.DeskTools
import com.wangzan101.mika.core.frontdesk.DispatchOutcome
import com.wangzan101.mika.core.frontdesk.FrontDesk
import com.wangzan101.mika.core.frontdesk.ReplyContext
import com.wangzan101.mika.core.frontdesk.ReportPolicy
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The chat page goes through the front desk: typed words go to the front desk, replies stream into that exchange, dispatched tasks hang as cards under the same round, and reports are spoken by the front desk while the page is open. */
@OptIn(ExperimentalCoroutinesApi::class)
class DeskChatOpsTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val calls = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text" }
        override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean): Boolean {
            calls += "queue:$text"
            return true
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) { calls += "answer:$text" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    /** Background task port: each dispatched task has an editable state flow, numbered child-1, child-2, ... */
    private class FakePort : DeskTaskPort {
        val states = LinkedHashMap<String, MutableStateFlow<TaskState>>()
        val dispatched = mutableListOf<String>()
        val answered = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()

        override fun dispatch(words: String, note: String, parentConversation: String): DispatchOutcome {
            val id = "child-${states.size + 1}"
            states[id] = MutableStateFlow(TaskState.Running(1, ""))
            dispatched += words
            return DispatchOutcome.Started(id)
        }

        override suspend fun dispatchWithFiles(parentConversation: String, prepare: suspend (child: String) -> String?): DispatchOutcome {
            val id = "child-${states.size + 1}"
            val words = prepare(id) ?: return DispatchOutcome.Failed("not_prepared")
            states[id] = MutableStateFlow(TaskState.Running(1, ""))
            dispatched += words
            return DispatchOutcome.Started(id)
        }

        override fun state(taskId: String): StateFlow<TaskState>? = states[taskId]
        override fun queuePosition(taskId: String): Int? = null
        override fun steer(taskId: String, words: String) = true
        override fun answer(taskId: String, words: String): Boolean { answered += taskId to words; return true }
        override fun cancel(taskId: String): Boolean { cancelled += taskId; return true }
    }

    /** Front-desk brain that replies from a script. */
    private class ScriptBrain(
        val onReply: suspend (String, ReplyContext) -> Unit,
        val onNotice: suspend (String, ReplyContext) -> Unit = { _, _ -> },
    ) : DeskBrain {
        val replies = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val seeded = mutableListOf<List<Turn>>()
        override suspend fun reply(userText: String, ctx: ReplyContext) { replies += userText; onReply(userText, ctx) }
        override suspend fun lateResult(name: String, result: JsonObject, ctx: ReplyContext) {}
        override suspend fun notice(text: String, ctx: ReplyContext) { notices += text; onNotice(text, ctx) }
        override fun remember(assistantText: String) {}
        override fun seed(prior: List<Turn>) { seeded += prior }
    }

    private class Harness(
        val vm: ChatViewModel, val store: ConversationStore, val port: FakePort, val sunk: MutableList<Pair<String, DeskReport>>,
        val desk: (String) -> FrontDesk,
    )

    private fun TestScope.harness(
        brain: DeskBrain?,
        store: ConversationStore = ConversationStore(tmp.newFolder()),
        brains: () -> DeskBrain? = { brain },
        importAttachments: suspend (String, List<String>) -> ImportResult = { _, _ -> ImportResult(emptyList(), emptyList()) },
        branchWorkspace: BranchWorkspace? = null,
        title: suspend (String, String) -> Unit = { _, _ -> },
    ): Harness {
        val port = FakePort()
        val sunk = mutableListOf<Pair<String, DeskReport>>()
        val sink = object : DeskSink {
            override suspend fun record(conversationId: String, report: DeskReport) { sunk += conversationId to report }
        }
        val desks = HashMap<String, Pair<FrontDesk, DeskLedger>>()
        fun entry(id: String) = desks.getOrPut(id) {
            val ledger = DeskLedger()
            FrontDesk(id, backgroundScope, port, ledger, ReportPolicy({ 0L }), sink) to ledger
        }
        val deps = DeskChatDeps(
            desk = { entry(it).first },
            executor = { id, words ->
                val (desk, ledger) = entry(id)
                DeskToolExecutor(port, ledger, words, { 0L }, id) { desk.track(it.taskId, it.title) }
            },
            brain = { brains() },
            title = title,
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
            io = StandardTestDispatcher(testScheduler), desk = deps, sandboxReady = MutableStateFlow(true),
            importAttachments = importAttachments, branchWorkspace = branchWorkspace).also { runCurrent() }
        return Harness(vm, store, port, sunk) { entry(it).first }
    }

    private fun dispatch(words: String, title: String) = buildJsonObject { put("words", words); put("title", title) }

    @Test fun sendStreamsDeskReplyAndPersistsTurn() = runTest {
        val more = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ _, ctx ->
            ctx.say("好,")
            more.await()
            ctx.say("我让后面去订。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        h.vm.input("帮我订明天的日料")
        assertTrue(h.vm.send(h.vm.ui.value.input, ok))
        // Sending immediately shows a user bubble and a "thinking" front-desk row, and the composer is cleared
        val first = h.vm.ui.value.exchanges.single()
        assertEquals("帮我订明天的日料", first.goal)
        assertEquals("", first.deskReply)
        assertEquals("", h.vm.ui.value.input)
        runCurrent()
        // The reply streams into the same row piece by piece
        assertEquals("好,", h.vm.ui.value.exchanges.single().deskReply)
        assertEquals(ExchangeStatus.Working, h.vm.ui.value.exchanges.single().status)
        more.complete(Unit)
        runCurrent()
        val done = h.vm.ui.value.exchanges.single()
        assertEquals("好,我让后面去订。", done.deskReply)
        assertTrue(done.status is ExchangeStatus.Done)
        assertEquals(listOf("帮我订明天的日料"), brain.replies)
        // Stored as one front-desk round with the same id; re-reading the conversation gives the same row
        val saved = h.store.turns("c").single()
        assertEquals(DeskTurn.STATUS, saved.status)
        assertEquals("帮我订明天的日料", saved.userText)
        assertEquals("好,我让后面去订。", saved.outcome)
        assertTrue(saved.runId!!.startsWith(DeskTurn.RUN_PREFIX))
        assertEquals(saved.runId, done.runId)
        assertNull(done.recordedRun)
        assertEquals(1, h.vm.ui.value.exchanges.size)
        // No task started, no steer inserted
        assertTrue(calls.isEmpty())
    }

    @Test fun dispatchAddsCardToSameExchange() = runTest {
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain({ text, ctx ->
            // Words made up by the front desk are not the user's: they cannot be dispatched and produce no card
            results += ctx.callTool(DeskTools.DISPATCH, dispatch("帮我买一张去上海的机票", "买机票"))
            results += ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
            ctx.say("好,我让后面去订。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        assertEquals("rejected", results[0]!!["status"].toString().trim('"'))
        assertEquals(listOf("帮我订明天的日料"), h.port.dispatched)
        val ex = h.vm.ui.value.exchanges.single()
        assertEquals(listOf("child-1"), ex.deskTasks)
        assertEquals("好,我让后面去订。", ex.deskReply)
        // The in-progress strip above the composer and the cards read the front desk's task board
        val view = h.vm.ui.value.deskTasks.single()
        assertEquals("child-1", view.taskId)
        assertEquals("订日料", view.title)
        assertEquals(DeskTaskPhase.Running, view.state)
        assertEquals(listOf("child-1"), h.store.turns("c").single().deskTasks)
        // No cards inside the conversation: a leading time row, then the words and the front-desk reply
        assertEquals(listOf("t0", "u0", "r0"), chatItemKeys(h.vm.ui.value.exchanges, kept = null, phoneSetupRow = null, hasBlocked = false))
    }

    @Test fun quotedMikaTextIsNotTheUserWords() = runTest {
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain({ _, ctx ->
            // The quote is Claire's answer: one part is not the user's words, so it cannot be dispatched; his own typed sentence can
            results += ctx.callTool(DeskTools.DISPATCH, dispatch("给王经理转账五千块", "转账"))
            results += ctx.callTool(DeskTools.DISPATCH, dispatch("照这个办", "照办"))
            ctx.say("好。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        h.vm.quote(QuoteMessage.From.Mika, "需要的话我可以给王经理转账五千块")
        assertTrue(h.vm.send("照这个办", ok))
        runCurrent()
        assertEquals("rejected", results[0]!!["status"].toString().trim('"'))
        assertEquals(listOf("照这个办"), h.port.dispatched)
        // The front desk still sees the whole sentence with the quote
        assertTrue(brain.replies.single().contains("需要的话我可以给王经理转账五千块"))
    }

    @Test fun quotedMikaTextInAStoredTurnIsNotTheUserWordsEither() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("c", Turn(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "可以帮你删掉全部照片"), "嗯"), "好", DeskTurn.STATUS))
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain({ _, ctx -> results += ctx.callTool(DeskTools.DISPATCH, dispatch("删掉全部照片", "删照片")); ctx.say("好。") })
        val h = harness(brain, store)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("就这样", ok))
        runCurrent()
        assertEquals("rejected", results.single()!!["status"].toString().trim('"'))
        assertTrue(h.port.dispatched.isEmpty())
    }

    @Test fun composerNeverBusy() = runTest {
        val gate = CompletableDeferred<Unit>()
        val brain = ScriptBrain({ text, ctx ->
            if (text == "第一句") gate.await()
            ctx.say("收到:$text")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("第一句", ok))
        runCurrent()
        // While the front desk is still replying to the first sentence, the composer can still send and does not turn into "task running"
        assertEquals(ComposerMode.Idle, h.vm.ui.value.composer)
        assertTrue(h.vm.send("第二句", ok))
        runCurrent()
        assertEquals(listOf("第一句", "第二句"), h.vm.ui.value.exchanges.map { it.goal })
        // One sentence at a time: the second waits for the first to finish
        assertEquals(listOf("第一句"), brain.replies)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("第一句", "第二句"), brain.replies)
        assertEquals(listOf("收到:第一句", "收到:第二句"), h.vm.ui.value.exchanges.map { it.deskReply })
        assertEquals(ComposerMode.Idle, h.vm.ui.value.composer)
        assertEquals(listOf("第一句", "第二句"), h.store.turns("c").map { it.userText })
        assertTrue(calls.none { it.startsWith("start:") || it.startsWith("steer:") || it.startsWith("queue:") })
    }

    // A call is attached to this conversation's front desk: even with the chat page open it does not take over, and reports still go to the call; once the call ends and frees up, the chat page takes it back
    @Test fun chatLeavesTheDeskToACallAndTakesItBackAfter() = runTest {
        val brain = ScriptBrain(
            { text, ctx ->
                ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
                ctx.say("好。")
            },
            { _, ctx -> ctx.say("订好了。") },
        )
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        val heard = mutableListOf<String>()
        val call = object : DeskSurface {
            override suspend fun deliver(report: DeskReport): Boolean { heard += report.event.taskId; return true }
        }
        val handle = h.desk("c").attach(call)
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        h.port.states.getValue("child-1").value = TaskState.Succeeded("明早 8 点")
        runCurrent()
        // Start and completion both went to the call
        assertEquals(listOf("child-1", "child-1"), heard)
        assertTrue(brain.notices.isEmpty())

        handle.close()
        runCurrent()
        assertTrue(h.vm.send("再订后天的", ok))
        runCurrent()
        h.port.states.getValue("child-2").value = TaskState.Succeeded("后天 9 点")
        runCurrent()
        // The call ended and the chat page took back over: the raw result becomes a one-line report, without going through the front-desk model
        assertTrue(brain.notices.isEmpty())
        assertEquals("后天 9 点", h.vm.ui.value.exchanges.last().deskReply)
        assertEquals(listOf("child-1", "child-1"), heard)
        assertTrue(h.sunk.isEmpty())
    }

    @Test fun reportWhileOpenBecomesDeskRow() = runTest {
        val brain = ScriptBrain(
            { text, ctx ->
                ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
                ctx.say("好,我让后面去订。")
            },
            { _, ctx -> ctx.say("订好了,明早 8 点。") },
        )
        val h = harness(brain)
        h.vm.pageShown(true)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        // Started: visible on the card, the front desk says nothing extra and writes no notification
        assertTrue(brain.notices.isEmpty())
        assertTrue(h.sunk.isEmpty())
        h.port.states.getValue("child-1").value = TaskState.Succeeded("明早 8 点")
        runCurrent()
        // Completed: with the page open on this conversation, the background result becomes a one-line report, not paraphrased by the front-desk model
        assertTrue(brain.notices.isEmpty())
        val row = h.vm.ui.value.exchanges.last()
        assertEquals("", row.goal)
        assertEquals("明早 8 点", row.deskReply)
        assertEquals(listOf("child-1"), row.deskTasks)
        val saved = h.store.turns("c").last()
        assertEquals(DeskTurn.REPORT_STATUS, saved.status)
        assertEquals("明早 8 点", saved.outcome)
        assertEquals(listOf("child-1"), saved.deskTasks)
        assertTrue(h.sunk.isEmpty())
        assertEquals(DeskTaskPhase.Done, h.vm.ui.value.deskTasks.single().state)
        // The page is gone: the front desk stays silent and hands off to the fallback (write back to the conversation, send a notification)
        assertTrue(h.vm.send("再帮我订后天的", ok))
        runCurrent()
        h.vm.pageShown(false)
        runCurrent()
        h.port.states.getValue("child-2").value = TaskState.Succeeded("后天 9 点")
        runCurrent()
        assertTrue(brain.notices.isEmpty())
        assertEquals("child-2", h.sunk.single().second.event.taskId)
    }

    @Test fun noModelShowsBlockedWithGuide() = runTest {
        val h = harness(brain = null)
        h.vm.openConversation("c")
        runCurrent()
        h.vm.input("帮我订明天的日料")
        // No model configured: the entry point is still there, sending is blocked and points to model settings; the words stay in the composer
        assertFalse(h.vm.send(h.vm.ui.value.input, ok))
        assertEquals(listOf<Problem>(Problem.NoModel), h.vm.ui.value.blocked)
        assertTrue(h.vm.ui.value.exchanges.isEmpty())
        assertEquals("帮我订明天的日料", h.vm.ui.value.input)
        assertTrue(calls.isEmpty())
    }

    @Test fun pendingCallQuestionTakesTheMessageBeforeTheDeskEvenWithAttachments() = runTest {
        val answered = mutableListOf<Pair<String, String>>()
        val brain = ScriptBrain({ _, ctx -> ctx.say("不该走到前台") })
        val port = FakePort()
        val deps = DeskChatDeps(
            desk = { FrontDesk(it, backgroundScope, port, DeskLedger(), ReportPolicy({ 0L }), object : DeskSink {
                override suspend fun record(conversationId: String, report: DeskReport) {}
            }) },
            executor = { id, words -> DeskToolExecutor(port, DeskLedger(), words, { 0L }, id) },
            brain = { brain },
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), io = StandardTestDispatcher(testScheduler), desk = deps,
            sandboxReady = MutableStateFlow(true),
            ownerAnswer = { id, text -> answered += id to text; true }).also { runCurrent() }
        vm.openConversation("a")
        runCurrent()
        vm.attach(listOf("content://x/a.pdf"))
        runCurrent()
        vm.input("周日也行")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(listOf("a" to "周日也行"), answered)
        assertTrue(brain.replies.isEmpty())
        assertTrue(calls.isEmpty())
        assertEquals("", vm.ui.value.input)
    }

    // A conversation for a subtask dispatched by the front desk: no nested front desk, typed words are steered straight into its running task and no new item is dispatched
    @Test fun aChildTaskConversationSteersItsTaskWithoutANestedDesk() = runTest {
        val brain = ScriptBrain({ _, ctx -> ctx.say("不该走到前台") })
        val port = FakePort()
        val made = mutableListOf<String>()
        val deps = DeskChatDeps(
            desk = { id -> made += id; FrontDesk(id, backgroundScope, port, DeskLedger(), ReportPolicy({ 0L }), object : DeskSink {
                override suspend fun record(conversationId: String, report: DeskReport) {}
            }) },
            executor = { id, words -> made += "executor:$id"; DeskToolExecutor(port, DeskLedger(), words, { 0L }, id) },
            brain = { brain },
        )
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("child-1", "c")
        val running = MutableStateFlow<TaskState>(TaskState.Running(1, "working"))
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions, store,
            io = StandardTestDispatcher(testScheduler), desk = deps, sandboxReady = MutableStateFlow(true),
            parallelFlag = { true }, activeConversations = MutableStateFlow(listOf("child-1")),
            runGoal = { "帮我订明天的日料" }, runState = { running }).also { runCurrent() }
        vm.pageShown(true)
        vm.switchTo("child-1")
        runCurrent()
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        vm.input("改成订周六的")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(listOf("steer:改成订周六的"), calls)
        assertEquals(listOf("改成订周六的"), vm.ui.value.exchanges.last().steers.map { it.text })
        assertEquals("", vm.ui.value.input)
        // No front desk was created and no grandchild task dispatched; the front-desk model never touched this sentence
        assertTrue(made.isEmpty())
        assertTrue(port.dispatched.isEmpty())
        assertTrue(brain.replies.isEmpty())
    }

    @Test fun teachingKeepsTheOldBlock() = runTest {
        var blocked = 0
        val brain = ScriptBrain({ _, ctx -> ctx.say("不该走到前台") })
        val port = FakePort()
        val deps = DeskChatDeps(
            desk = { FrontDesk(it, backgroundScope, port, DeskLedger(), ReportPolicy({ 0L }), object : DeskSink {
                override suspend fun record(conversationId: String, report: DeskReport) {}
            }) },
            executor = { id, words -> DeskToolExecutor(port, DeskLedger(), words, { 0L }, id) },
            brain = { brain },
        )
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), io = StandardTestDispatcher(testScheduler), desk = deps,
            teaching = { true }, onTeachingBlocked = { blocked++ }).also { runCurrent() }
        vm.openConversation("a")
        assertFalse(vm.send("帮我订明天的日料", ok))
        runCurrent()
        assertEquals(1, blocked)
        assertTrue(brain.replies.isEmpty())
    }
    // A sentence with attachments also goes through front-desk dispatch: whether or not a task is running, it becomes a new background task (files copied into its child conversation), and the card hangs under this round
    @Test fun attachmentsWhileATaskRunsBecomeANewTrackedCard() = runTest {
        val brain = ScriptBrain({ text, ctx ->
            ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
            ctx.say("好。")
        })
        val inbox = "/workspace/inbox/合同.pdf"
        val copies = mutableListOf<Triple<String, String, List<String>>>()
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? = null
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> {
                copies += Triple(sourceId, targetId, paths)
                return paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 12L) }
            }
        }
        val imported = mutableListOf<String>()
        val h = harness(brain, importAttachments = { id, uris -> imported += id; ImportResult(uris.map { inbox to 12L }, emptyList()) },
            branchWorkspace = workspace)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        runCurrent()
        assertEquals(DeskTaskPhase.Running, h.vm.ui.value.deskTasks.single().state)

        h.vm.attach(listOf("content://x/合同.pdf"))
        runCurrent()
        assertEquals(1, h.vm.ui.value.attachments.size)
        h.vm.input("把这份合同也看一下")
        assertTrue(h.vm.send(h.vm.ui.value.input, ok))
        runCurrent()
        // Files go into this conversation's workspace first (visible in the bubble), then are copied into the new task's child conversation; what goes to the background is the user's words plus the file lines
        assertEquals(listOf("c"), imported)
        assertEquals(listOf(Triple("c", "child-2", listOf(inbox))), copies)
        assertEquals(2, h.port.dispatched.size)
        val words = h.port.dispatched[1]
        assertTrue(words, words.startsWith("把这份合同也看一下") && inbox in words)
        // The card hangs under this round and the front-desk task board tracks it too; the composer is not busy and the draft is cleared
        val row = h.vm.ui.value.exchanges.last()
        assertTrue(row.goal.startsWith("把这份合同也看一下"))
        assertEquals(listOf("child-2"), row.deskTasks)
        assertFalse(deskReplyShown(row))
        assertEquals(listOf("child-1", "child-2"), h.vm.ui.value.deskTasks.map { it.taskId })
        assertEquals(ComposerMode.Idle, h.vm.ui.value.composer)
        assertTrue(h.vm.ui.value.attachments.isEmpty())
        assertEquals("", h.vm.ui.value.input)
        val saved = h.store.turns("c").last()
        assertEquals(DeskTurn.STATUS, saved.status)
        assertEquals(listOf("child-2"), saved.deskTasks)
        assertTrue(inbox in saved.userText)
        // No old-path task started, and it was not handed to the front-desk model to answer
        assertTrue(calls.isEmpty())
        assertEquals(listOf("帮我订明天的日料"), brain.replies)
    }

    // Turns written into this conversation by a call are known to the front desk on the next send from the chat page (same conversation record as when the call opened), and words said in the call count as the user's own words
    @Test fun nextSendKnowsTheTurnsACallWrote() = runTest {
        val made = mutableListOf<ScriptBrain>()
        val results = mutableListOf<JsonObject?>()
        val h = harness(null, brains = {
            ScriptBrain({ text, ctx ->
                if (text == "就按电话里说的办") results += ctx.callTool(DeskTools.DISPATCH, dispatch("帮我订后天的日料", "订日料"))
                ctx.say("好。")
            }).also { made += it }
        })
        h.vm.openConversation("c")
        h.vm.pageShown(true)
        runCurrent()
        assertTrue(h.vm.send("你好", ok))
        runCurrent()
        // Made a call: the call attaches to the front desk, writes back two sentences, then hangs up
        val call = h.desk("c").attach(object : DeskSurface {
            override suspend fun deliver(report: DeskReport) = true
        })
        h.store.append("c", Turn("帮我订后天的日料", "好,我让后面去订。", DeskTurn.STATUS))
        runCurrent()
        call.close()
        runCurrent()
        assertTrue(h.vm.send("就按电话里说的办", ok))
        runCurrent()
        val seeded = made.last { it.replies.isNotEmpty() }.seeded.flatten()
        assertTrue(seeded.map { it.userText }.toString(), seeded.any { it.userText == "帮我订后天的日料" })
        assertEquals("started", results.single()!!["status"].toString().trim('"'))
        assertEquals(listOf("帮我订后天的日料"), h.port.dispatched)
    }

    // The front-desk model changed in settings: the next sentence uses the new one, and earlier words are still carried along
    @Test fun nextSendUsesTheModelChosenSinceTheLastOne() = runTest {
        var model = "A"
        val made = mutableListOf<Pair<String, ScriptBrain>>()
        val h = harness(null, brains = {
            val name = model
            ScriptBrain({ _, ctx -> ctx.say("$name 回") }).also { made += name to it }
        })
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("第一句", ok))
        runCurrent()
        model = "B"
        assertTrue(h.vm.send("第二句", ok))
        runCurrent()
        assertEquals(listOf("A 回", "B 回"), h.vm.ui.value.exchanges.map { it.deskReply })
        val b = made.filter { it.first == "B" }.map { it.second }.single { it.replies.isNotEmpty() }
        assertEquals(listOf("第二句"), b.replies)
        assertTrue(b.seeded.flatten().any { it.userText == "第一句" && it.outcome == "A 回" })
        assertTrue(made.filter { it.first == "A" }.none { "第二句" in it.second.replies })
    }

    // The first sentence of a new conversation through the front desk: it starts a title just like a directly started task (while this round has not been persisted yet), and later sentences do not
    @Test fun theFirstDeskTurnNamesTheConversation() = runTest {
        val brain = ScriptBrain({ _, ctx -> ctx.say("好。") })
        val titled = mutableListOf<Triple<String, String, Int>>()
        lateinit var h: Harness
        h = harness(brain, title = { id, first -> titled += Triple(id, first, h.store.turns(id).size) })
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我查明天的天气", ok))
        runCurrent()
        assertTrue(h.vm.send("后天呢", ok))
        runCurrent()
        assertEquals(listOf(Triple("c", "帮我查明天的天气", 0)), titled)
    }

    // The front desk excerpted a fragment (dropping "do not"): what goes to the background is still the user's whole sentence, not the fragment
    @Test fun aFragmentReachesTheBackendAsTheWholeMessage() = runTest {
        val brain = ScriptBrain({ _, ctx ->
            ctx.callTool(DeskTools.DISPATCH, dispatch("删除那些照片", "看照片"))
            ctx.say("好。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("不要删除那些照片，先帮我看看有多少张", ok))
        runCurrent()
        assertEquals(listOf("不要删除那些照片，先帮我看看有多少张"), h.port.dispatched)
        assertFalse(h.port.dispatched.contains("删除那些照片"))
    }

    // A report round was not opened by the user: the front desk must not use the user's earlier words to answer for him, add words, dispatch, or cancel; it can only speak to him
    @Test fun aReportTurnCannotActOnEarlierWords() = runTest {
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain(
            { text, ctx ->
                ctx.callTool(DeskTools.DISPATCH, dispatch(text, "打开应用"))
                ctx.say("好。")
            },
            { _, ctx ->
                results += ctx.callTool(DeskTools.ANSWER, buildJsonObject { put("task_id", "child-1"); put("words", "计算器") })
                results += ctx.callTool(DeskTools.ADD, buildJsonObject { put("task_id", "child-1"); put("words", "时钟都行") })
                results += ctx.callTool(DeskTools.DISPATCH, dispatch("打开计算器或者时钟都行", "再开一个"))
                results += ctx.callTool(DeskTools.CANCEL, buildJsonObject { put("task_id", "child-1"); put("words", "打开计算器") })
                ctx.say("它问你打开哪一个。")
            },
        )
        val h = harness(brain)
        h.vm.pageShown(true)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("打开计算器或者时钟都行", ok))
        runCurrent()
        // Failure is spoken by the front desk (problems needing your attention get a problem card, bypassing the front desk)
        h.port.states.getValue("child-1").value = TaskState.Failed("不知道打开哪一个", com.wangzan101.mika.core.agent.FailKind.GaveUp)
        runCurrent()
        assertEquals(1, brain.notices.size)
        assertEquals(List(4) { "rejected" }, results.map { it!!["status"].toString().trim('"') })
        assertTrue(h.port.answered.isEmpty())
        assertTrue(h.port.cancelled.isEmpty())
        assertEquals(listOf("打开计算器或者时钟都行"), h.port.dispatched)
    }

    // Cancellation only counts the words in the user's current round: "do not book it" was said earlier, this round asks something else, and the front desk must not use that sentence to stop the task
    @Test fun cancelNeedsWhatTheUserJustSaid() = runTest {
        val results = mutableListOf<JsonObject?>()
        val brain = ScriptBrain({ text, ctx ->
            when (text) {
                "帮我订明天的日料" -> ctx.callTool(DeskTools.DISPATCH, dispatch(text, "订日料"))
                else -> results += ctx.callTool(DeskTools.CANCEL, buildJsonObject { put("task_id", "child-1"); put("words", "别订了") })
            }
            ctx.say("好。")
        })
        val h = harness(brain)
        h.vm.openConversation("c")
        runCurrent()
        assertTrue(h.vm.send("帮我订明天的日料", ok))
        assertTrue(h.vm.send("别订了", ok))
        assertTrue(h.vm.send("明天天气怎么样", ok))
        runCurrent()
        assertEquals(listOf("ok", "rejected"), results.map { it!!["status"].toString().trim('"') })
        assertEquals(listOf("child-1"), h.port.cancelled)
    }

    // The user only sees Claire: when a notification, the small window or an old link opens the child conversation Claire uses for a task, open the chat it belongs to
    @Test fun openingAChildConversationOpensItsParent() = runTest {
        val h = harness(ScriptBrain({ _, _ -> }))
        h.store.setDeskParent("child-9", "c")
        h.vm.openConversation("child-9")
        runCurrent()
        assertEquals("c", h.vm.ui.value.conversationId)
    }
}
