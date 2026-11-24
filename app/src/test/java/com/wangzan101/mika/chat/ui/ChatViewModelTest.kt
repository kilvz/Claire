package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.task.TaskGate

import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.chat.BranchCopyResult
import com.wangzan101.mika.chat.BranchOrigin
import com.wangzan101.mika.chat.BranchWorkspace
import com.wangzan101.mika.chat.RetryAttachmentResult
import com.wangzan101.mika.chat.RetryAttachmentStatus
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.DeleteBatch
import com.wangzan101.mika.chat.SharedDraft
import com.wangzan101.mika.chat.SharedDraftAttachment
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.Problem
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import com.wangzan101.mika.task.QueuedFollowUp
import com.wangzan101.mika.task.QueuedSource
import com.wangzan101.mika.chat.ui.PipPhysics.Corner
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.screen.user.UserScreenMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.wangzan101.mika.files.ui.LibraryAttachmentResult
import com.wangzan101.mika.files.ui.LibraryFileHandle
import com.wangzan101.mika.files.ui.LibraryStaged
import com.wangzan101.mika.settings.ui.models.Page
import com.wangzan101.mika.settings.ui.models.refresh

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()

    @Test fun readAloudReportsFailureAndAllowsRetry() = runTest {
        val errors = mutableListOf<String>()
        var attempts = 0
        val app = com.wangzan101.mika.MikaApp()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            speakText = { text -> app.speak(text) { if (++attempts == 1) throw java.io.IOException("offline") } },
            voiceFailed = errors::add)
        vm.speak("你好")
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("offline"))
        vm.speak("再试一次")
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, errors.size)
    }

    @Test fun readAloudCancellationDoesNotBecomeAPlaybackError() = runTest {
        val errors = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            speakText = { throw kotlinx.coroutines.CancellationException("stopped") },
            voiceFailed = errors::add)
        vm.speak("你好")
        runCurrent()
        assertTrue(errors.isEmpty())
    }

    private val state = MutableStateFlow<TaskState>(TaskState.Idle)
    private val stage = MutableStateFlow(Takeover.Stage.None)
    private val calls = mutableListOf<String>()
    private val acts = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    private val steerFlow = MutableStateFlow<List<SteerNote>>(emptyList())
    private val followUpFlow = MutableStateFlow<List<QueuedFollowUp>>(emptyList())
    private val steerIds = mutableListOf<String>()
    private var canWithdraw = true
    private var queueSaveSucceeds = true
    private val queueStartIfIdle = mutableListOf<Boolean>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text"; steerIds += messageId }
        override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean): Boolean {
            calls += "queue:$text"; steerIds += messageId; queueStartIfIdle += startIfIdle
            if (!queueSaveSucceeds) return false
            followUpFlow.value = followUpFlow.value + QueuedFollowUp(messageId, conversationId, text)
            return true
        }
        override fun cancelFollowUp(conversationId: String, messageId: String): Boolean {
            calls += "cancelQueued:$messageId"
            followUpFlow.value = followUpFlow.value.filterNot { it.id == messageId }
            return true
        }
        override fun drainFollowUps(conversationId: String) { calls += "drain:$conversationId" }
        override fun withdrawSteer(conversationId: String, messageId: String): Boolean {
            calls += "withdraw:$messageId"
            return canWithdraw
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() { calls += "resume" }
        override fun takeOver() { calls += "takeOver" }
        override fun handBack() { calls += "handBack" }
        override fun finishedMyself() { calls += "finishedMyself" }
        override fun skip() { calls += "skip" }
        override fun answer(text: String) { calls += "answer:$text" }
        override fun approve(key: Int) { calls += "approve:$key" }
        override fun decline(key: Int) { calls += "decline:$key" }
        override fun show(conversationId: String) { calls += "show:$conversationId" }
        override fun cancelIn(conversationId: String) { calls += "cancel@$conversationId" }
        override fun resumeIn(conversationId: String) { calls += "resume@$conversationId" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approveIn(conversationId: String, key: Int) { calls += "approve@$conversationId:$key" }
        override fun togglePipSoundIn(conversationId: String) { calls += "toggle-sound@$conversationId" }
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private fun TestScope.vm(store: ConversationStore = ConversationStore(tmp.newFolder())) =
        ChatViewModel(backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it },
            activities = acts, steers = steerFlow, followUps = followUpFlow).also {
            it.setFollowUpMode(FollowUpMode.Steer)
            runCurrent()
        }

    /** Like [vm], but lets you specify whether this screen is open. */
    private fun TestScope.vmWithScreen(open: MutableStateFlow<Boolean>) =
        ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            appLabel = { if (it == "com.chat") "聊天" else it }, activities = acts, steers = steerFlow,
            screenOpen = open, followUps = followUpFlow).also { runCurrent() }

    private fun TestScope.emit(s: TaskState) {
        state.value = s
        runCurrent()
    }

    /** One more thing that was actually done has been added to the run events. */
    private fun TestScope.did(step: Int, kind: String, tool: String, target: String?, effect: String = "completed", title: String? = null, failed: Boolean = false) {
        acts.value = acts.value + RunEvent.Activity(0, step, kind, tool, target, effect, title = title, failed = failed)
        runCurrent()
    }

    // The notice turn (written straight into the conversation by Claire, with no task run): it has no user bubble and no run record to open; opening it twice on the same day gives two turns, each on its own
    @Test fun noticeTurnsHaveNoUserBubbleAndNoRunToOpen() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("talk", Turn("", "我从通知里挑出 1 条", "succeeded", runId = "notice:n1"))
        store.append("talk", Turn("帮我回一下", "已回复", "succeeded", runId = "run-1"))
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, store, parallelFlag = { false }, sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("talk")
        runCurrent()
        val (notice, reply) = vm.ui.value.exchanges
        assertEquals("", notice.goal)
        assertEquals("notice:n1", notice.runId)
        assertNull(notice.recordedRun)
        assertEquals("我从通知里挑出 1 条", (notice.status as ExchangeStatus.Done).answer)
        assertEquals("run-1", reply.recordedRun)
        // The number of rows on the page matches what is drawn: the notice turn has only the answer row, an ordinary turn has the user's words and the answer as two rows (viewing the process moved into the answer card's action area)
        assertEquals(1 + 2, chatItemCount(vm.ui.value.exchanges, kept = null, phoneSetupRow = null, hasBlocked = false))
        // Another notice turn is written in while the conversation is open: it is appended after the others, the earlier ones stay put and are not merged
        store.append("talk", Turn("", "我从通知里挑出 2 条", "succeeded", runId = "notice:n2"))
        runCurrent()
        assertEquals(listOf("notice:n1", "run-1", "notice:n2"), vm.ui.value.exchanges.map { it.runId })
        assertEquals(listOf("我从通知里挑出 1 条", "已回复", "我从通知里挑出 2 条"), vm.ui.value.exchanges.map { (it.status as ExchangeStatus.Done).answer })
    }

    @Test fun workflowConversationCanOpenWhileRunningAndPreservesDraft() = runTest {
        val active = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            activeConversations = active, parallelFlag = { false }, runGoal = { "workflow step" })
        runCurrent()
        vm.openConversation("original")
        vm.input("draft to keep")
        vm.quote(QuoteMessage.From.Mika, "quoted result")
        active.value = listOf("step")
        emit(TaskState.Running(1, "working"))
        vm.openWorkflowConversation("step")
        assertEquals("step", vm.ui.value.conversationId)
        assertTrue(calls.contains("show:step"))
        assertFalse(calls.any { it.startsWith("start:") })
        active.value = emptyList()
        emit(TaskState.Idle)
        vm.openWorkflowConversation("original")
        assertEquals("draft to keep", vm.ui.value.input)
        assertEquals("quoted result", vm.ui.value.quote?.text)
    }

    @Test fun draftsStayWithTheirConversationInSerialAndParallelModes() = runTest {
        for (parallel in listOf(false, true)) {
            val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions,
                ConversationStore(tmp.newFolder()), parallelFlag = { parallel }, sandboxReady = MutableStateFlow(true))
            runCurrent()
            vm.openConversation("a")
            vm.input("draft A")
            vm.quote(QuoteMessage.From.Mika, "answer A")
            vm.attach(listOf("content://files/a.pdf"))
            runCurrent()
            vm.openConversation("b")
            assertEquals("", vm.ui.value.input)
            assertTrue(vm.ui.value.attachments.isEmpty())
            assertNull(vm.ui.value.quote)
            assertTrue(vm.ui.value.attachEnabled)
            vm.input("draft B")
            vm.openConversation("a")
            assertEquals("draft A", vm.ui.value.input)
            assertEquals("answer A", vm.ui.value.quote?.text)
            assertEquals(listOf("content://files/a.pdf"), vm.ui.value.attachments.map { it.uri })
            vm.openConversation("a")
            assertEquals("draft A", vm.ui.value.input)
            vm.openConversation("b")
            assertEquals("draft B", vm.ui.value.input)
        }
    }

    @Test fun unsentNewConversationCanBeReopenedAndDeleted() = runTest {
        val vm = vm()
        vm.input("\nnot sent yet")
        val first = vm.ui.value.conversationId!!
        vm.newConversation()
        vm.input("another draft")
        val second = vm.ui.value.conversationId!!
        runCurrent()
        assertEquals(setOf(first, second), vm.draftConversations.value.map { it.id }.toSet())
        assertEquals("not sent yet", vm.draftConversations.value.first { it.id == first }.title)
        vm.openConversation(first)
        assertEquals("\nnot sent yet", vm.ui.value.input)
        vm.deleteConversation(first)
        runCurrent()
        assertEquals(listOf(second), vm.draftConversations.value.map { it.id })
        vm.openConversation(second)
        assertEquals("another draft", vm.ui.value.input)
    }

    @Test fun openingAtAMessageKeepsTheTargetForTheChatPage() = runTest {
        val vm = vm()
        vm.openConversation("a")
        vm.openConversationAt("b", ChatTarget.Message(1, null))
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(ChatTarget.Message(1, null), vm.jump.value?.target)

        val first = vm.jump.value!!
        vm.consumeJump(first)
        assertNull(vm.jump.value)

        // It must be able to jump again even inside this same conversation; serial makes the chat page treat the same place as a new request
        vm.openConversationAt("b", ChatTarget.Status(2))
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(ChatTarget.Status(2), vm.jump.value?.target)
        assertTrue(vm.jump.value!!.serial > first.serial)
    }

    @Test fun parallelRunningTasksKeepTheirDraftsAndFollowUpModes() = runTest {
        val running = MutableStateFlow<TaskState>(TaskState.Running(1, "working"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { true }, activeConversations = MutableStateFlow(listOf("a", "b")),
            runGoal = { "task $it" }, runState = { running }, sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.switchTo("a")
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("A next task")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        vm.switchTo("b")
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals("", vm.ui.value.input)
        vm.input("B correction")
        vm.switchTo("a")
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals("A next task", vm.ui.value.input)
        assertEquals("a.pdf", vm.ui.value.attachments.single().name)
        vm.switchTo("b")
        assertEquals("B correction", vm.ui.value.input)
        assertTrue(calls.none { it.startsWith("start:") || it.startsWith("steer:") || it.startsWith("queue:") })
    }

    @Test fun failedSendKeepsDraftAndSuccessfulSendOnlyClearsItsConversation() = runTest {
        val vm = vm()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        assertFalse(vm.send(vm.ui.value.input, Prerequisites.check(ready, hasMainModel = false)))
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.send(vm.ui.value.input, ok))
        assertEquals("", vm.ui.value.input)
        emit(TaskState.Succeeded("done"))
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
    }

    @Test fun unsentDraftsKeepTheirSidebarActionsAndStayAheadOfOldHistory() = runTest {
        val store = ConversationStore(tmp.newFolder())
        repeat(55) { store.append("old-$it", Turn("old $it", "done", "succeeded")) }
        val vm = vm(store)
        vm.input("unsent")
        val id = vm.ui.value.conversationId!!
        vm.renameConversation(id, "renamed draft")
        vm.setPinned(id, true)
        runCurrent()
        val all = vm.withDrafts(store.list())
        assertEquals(id, all.first().id)
        assertEquals("renamed draft", all.first().title)
        assertTrue(all.first().pinnedAt != null)
        val rows = drawerSections(all, CurrentConversation(id, false, "", ""), emptyList(), { null }, true, 1L)
        assertEquals(id, rows.pinned.single().id)
        assertEquals("", rows.pinned.single().time)
        vm.setPinned(id, false)
        assertEquals(id, vm.withDrafts(store.list()).first().id)
    }

    @Test fun delayedAttachmentDescriptionReturnsToItsOriginalDraftAndIgnoresDeletion() = runTest {
        var described = CompletableDeferred<List<PendingAttachment>>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), describeAttachments = { described.await() })
        runCurrent()
        vm.openConversation("a")
        vm.attach(listOf("content://a.pdf"))
        vm.openConversation("b")
        vm.input("B")
        described.complete(listOf(PendingAttachment("content://a.pdf", "a.pdf", 1)))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals("B", vm.ui.value.input)
        vm.openConversation("a")
        assertEquals("a.pdf", vm.ui.value.attachments.single().name)
        vm.attach(listOf("content://stale.pdf"), "b")
        runCurrent()
        assertEquals(1, vm.ui.value.attachments.size)
        described = CompletableDeferred()
        vm.attach(listOf("content://late.pdf"))
        runCurrent()
        vm.deleteConversation("a")
        described.complete(listOf(PendingAttachment("content://late.pdf", "late.pdf", 1)))
        runCurrent()
        assertTrue(vm.draftConversations.value.none { it.id == "a" })
        assertTrue(vm.ui.value.attachments.isEmpty())
        described = CompletableDeferred()
        vm.attach(listOf("content://late.pdf"))
        runCurrent()
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("already started", ok))
        described.complete(listOf(PendingAttachment("content://late.pdf", "late.pdf", 1)))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun deviceImportKeepsOriginalDraftAndReportsPartialFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.importDeviceAttachments("a", listOf("/good.pdf", "/bad.pdf")) { path, id ->
            assertEquals("a", id)
            gate.await()
            if (path.contains("bad")) throw IllegalStateException()
            com.wangzan101.mika.core.workspace.DeviceFileImport("/workspace/inbox/good.pdf", 12)
        }
        runCurrent()
        assertTrue(vm.ui.value.importing)
        assertFalse(vm.send("hello", ok))
        vm.openConversation("b")
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertFalse(vm.ui.value.importing)
        assertEquals("a", vm.ui.value.attachments.single().workspaceConversationId)
        assertEquals("bad.pdf", vm.ui.value.rejectedAttachments.single().name)
    }

    @Test fun deviceImportOnlyCopiesAvailableAttachmentSlots() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        repeat(8) { vm.addLibraryAttachment("a", PendingAttachment("content://$it", "$it.pdf", 1)) }
        val copied = mutableListOf<String>()
        vm.importDeviceAttachments("a", listOf("/one", "/two")) { path, _ ->
            copied += path
            com.wangzan101.mika.core.workspace.DeviceFileImport("/workspace/inbox/one", 1)
        }
        runCurrent()
        assertEquals(listOf("/one"), copied)
        assertEquals(9, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
    }

    @Test fun deviceImportFinishesBeforeConversationDeletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        var deleted = false
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), removeConversation = { deleted = true })
        runCurrent()
        vm.openConversation("a")
        vm.importDeviceAttachments("a", listOf("/file")) { _, _ ->
            gate.await()
            com.wangzan101.mika.core.workspace.DeviceFileImport("/workspace/inbox/file", 1)
        }
        runCurrent()
        vm.deleteConversation("a")
        runCurrent()
        assertFalse(deleted)
        gate.complete(Unit)
        runCurrent()
        assertTrue(deleted)
        assertTrue(vm.draftConversations.value.none { it.id == "a" })
    }

    @Test fun libraryAttachmentMergesIntoTargetDraftWithoutSwitchingOrOverwriting() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        val attachment = PendingAttachment("content://library/a", "a.pdf", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", attachment))
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
        assertEquals(listOf(attachment), vm.ui.value.attachments)
        repeat(8) { assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", PendingAttachment("content://library/$it", "$it.pdf", 1))) }
        assertEquals(LibraryAttachmentResult.Full, vm.addLibraryAttachment("a", PendingAttachment("content://library/overflow", "overflow.pdf", 1)))
        assertEquals(9, vm.ui.value.attachments.size)
        vm.deleteConversation("a")
        assertEquals(LibraryAttachmentResult.Unavailable, vm.addLibraryAttachment("a", attachment))
    }

    @Test fun librarySourceIsAddedOncePerDraftAndDuplicateCopyIsReleased() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("existing draft")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        val first = PendingAttachment("content://library/first", "a.pdf", 1)
        val duplicate = PendingAttachment("content://library/second", "a.pdf", 1)
        var firstReleased = 0
        var duplicateReleased = 0
        val existing = PendingAttachment("content://other/file", "other.txt", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", existing))
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", first, source) { firstReleased++ })
        vm.openConversation("b")
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", duplicate, source) { duplicateReleased++ })
        assertEquals(1, duplicateReleased)
        assertEquals(0, firstReleased)
        vm.openConversation("a")
        assertEquals("existing draft", vm.ui.value.input)
        assertEquals(listOf(existing, first), vm.ui.value.attachments)
        vm.removeAttachment(first.uri)
        assertEquals(1, firstReleased)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", duplicate, source))
        assertEquals(listOf(existing, duplicate), vm.ui.value.attachments)
    }

    @Test fun libraryCopiesRemainWhileReferencedOrImportingAndReleaseAfterward() = runTest {
        val imported = CompletableDeferred<ImportResult>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> imported.await() })
        runCurrent()
        vm.openConversation("a")
        var released = 0
        val item = PendingAttachment("content://library/copy", "copy.pdf", 1)
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("a", item) { released++ })
        assertEquals(LibraryAttachmentResult.Added, vm.addLibraryAttachment("b", item))
        vm.removeAttachment(item.uri)
        assertEquals(0, released)
        vm.openConversation("b")
        assertTrue(vm.send("send", ok))
        runCurrent()
        vm.deleteConversation("b")
        assertEquals(0, released)
        imported.complete(ImportResult(listOf("/workspace/inbox/copy.pdf" to 1L), emptyList()))
        runCurrent()
        assertEquals(1, released)
    }

    @Test fun replacingSharedDraftAndChangingFollowUpModeReleaseLibraryCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), sharedDrafts = SharedDraftStore(tmp.newFolder()))
        runCurrent()
        vm.openConversation("a")
        var replacedReleased = 0
        val first = PendingAttachment("content://library/first", "first.pdf", 1)
        vm.addLibraryAttachment("a", first) { replacedReleased++ }
        assertTrue(vm.openSharedDraft(SharedDraft("", "replacement"), inCurrentConversation = true))
        assertEquals(0, replacedReleased)
        assertTrue(vm.confirmPendingSharedDraft())
        assertEquals(1, replacedReleased)
        assertEquals("replacement", vm.ui.value.input)

        vm.setFollowUpMode(FollowUpMode.Queue)
        var modeReleased = 0
        val second = PendingAttachment("content://library/second", "second.pdf", 1)
        vm.addLibraryAttachment("a", second) { modeReleased++ }
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertEquals(1, modeReleased)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals("replacement", vm.ui.value.input)
    }

    @Test fun libraryCopyIsReleasedAfterSuccessfulSend() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList()) })
        runCurrent()
        vm.openConversation("a")
        var released = 0
        vm.addLibraryAttachment("a", PendingAttachment("content://library/a", "a.pdf", 1)) { released++ }
        assertTrue(vm.send("send", ok))
        runCurrent()
        assertEquals(1, released)
    }

    @Test fun libraryBatchAddsFilesInOrderWithoutSwitchingDrafts() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        vm.input("draft A")
        vm.openConversation("b")
        vm.input("draft B")
        val handles = (1..3).map { LibraryFileHandle.Workspace("origin", "/workspace/inbox/$it.pdf") }
        val plan = vm.planLibraryAttachments("a", handles)
        assertNotNull(plan)
        assertEquals(handles, plan!!.accepted)
        var released = 0
        val staged = handles.mapIndexed { index, source ->
            LibraryStaged(PendingAttachment("content://library/$index", "${index + 1}.pdf", 1), source) { released++ }
        }
        val result = vm.addLibraryAttachments("a", staged, plan.overLimit)
        assertEquals(3, result.added)
        assertEquals(0, result.duplicates)
        assertEquals(0, result.full)
        assertFalse(result.unavailable)
        assertEquals(0, released)
        assertEquals("draft B", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation("a")
        assertEquals("draft A", vm.ui.value.input)
        assertEquals(listOf("1.pdf", "2.pdf", "3.pdf"), vm.ui.value.attachments.map { it.name })
    }

    @Test fun libraryBatchPlanSkipsDuplicateSourcesAndStopsAtCapacity() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val existing = LibraryFileHandle.Workspace("origin", "/workspace/inbox/existing.pdf")
        assertEquals(LibraryAttachmentResult.Added,
            vm.addLibraryAttachment("a", PendingAttachment("content://library/existing", "existing.pdf", 1), existing))
        repeat(7) { index ->
            assertEquals(LibraryAttachmentResult.Added,
                vm.addLibraryAttachment("a", PendingAttachment("content://library/fill-$index", "fill-$index.pdf", 1)))
        }
        val more = listOf(existing) + (1..2).map { LibraryFileHandle.Workspace("origin", "/workspace/inbox/new-$it.pdf") }
        val plan = vm.planLibraryAttachments("a", more)!!
        assertEquals(1, plan.duplicates)
        assertEquals(listOf(more[1]), plan.accepted)
        assertEquals(1, plan.overLimit)
    }

    @Test fun libraryBatchCommitRechecksCapacityAndReleasesRejectedCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        repeat(8) { index ->
            vm.addLibraryAttachment("a", PendingAttachment("content://library/fill-$index", "fill-$index.pdf", 1))
        }
        val first = LibraryFileHandle.Workspace("origin", "/workspace/inbox/first.pdf")
        val second = LibraryFileHandle.Workspace("origin", "/workspace/inbox/second.pdf")
        val plan = vm.planLibraryAttachments("a", listOf(first, second))!!
        assertEquals(1, plan.accepted.size)
        // The last slot is taken during the copy: the merge re-checks.
        vm.addLibraryAttachment("a", PendingAttachment("content://other/last", "last.pdf", 1))
        val released = mutableListOf<String>()
        val staged = listOf(first, second).mapIndexed { index, source ->
            LibraryStaged(PendingAttachment("content://library/$index", "$index.pdf", 1), source) { released += "$index" }
        }
        val result = vm.addLibraryAttachments("a", staged, plan.overLimit)
        assertEquals(0, result.added)
        assertEquals(2, result.full)
        assertEquals(setOf("0", "1"), released.toSet())
        assertEquals(9, vm.ui.value.attachments.size)
    }

    @Test fun libraryBatchCommitSkipsDuplicatesAndReleasesTheirCopies() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        vm.addLibraryAttachment("a", PendingAttachment("content://library/existing", "a.pdf", 1), source)
        var released = 0
        val result = vm.addLibraryAttachments("a",
            listOf(LibraryStaged(PendingAttachment("content://library/copy", "a.pdf", 1), source) { released++ }))
        assertEquals(0, result.added)
        assertEquals(1, result.duplicates)
        assertEquals(1, released)
    }

    @Test fun libraryBatchReleasesEverythingWhenTargetIsGone() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        assertNotNull(vm.planLibraryAttachments("a", listOf(source)))
        vm.deleteConversation("a")
        var released = 0
        val result = vm.addLibraryAttachments("a",
            listOf(LibraryStaged(PendingAttachment("content://library/a", "a.pdf", 1), source) { released++ }), 0)
        assertTrue(result.unavailable)
        assertEquals(1, released)
    }

    @Test fun libraryBatchCopiesAreReleasedWhenRemoved() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.openConversation("a")
        val source = LibraryFileHandle.Workspace("origin", "/workspace/inbox/a.pdf")
        var released = 0
        val item = PendingAttachment("content://library/a", "a.pdf", 1)
        val result = vm.addLibraryAttachments("a", listOf(LibraryStaged(item, source) { released++ }))
        assertEquals(1, result.added)
        assertEquals(0, released)
        vm.removeAttachment(item.uri)
        assertEquals(1, released)
    }

    @Test fun failedAttachmentImportAndQueueWriteKeepTheComposerDraft() = runTest {
        var result = ImportResult(emptyList(), listOf("a.pdf"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> result }, followUps = followUpFlow)
        runCurrent()
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(1, vm.ui.value.attachments.size)
        assertTrue(calls.isEmpty())
        vm.removeAttachment("content://a.pdf")
        assertTrue(vm.send("first task", ok))
        emit(TaskState.Running(1, ""))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("next task")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        result = ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList())
        queueSaveSucceeds = false
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals("next task", vm.ui.value.input)
        assertEquals(1, vm.ui.value.attachments.size)
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun attachmentSendInterruptedBySwitchRetainsTheOriginalDraftWithoutSendingInAnotherChat() = runTest {
        val imported = CompletableDeferred<ImportResult>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { true }, sandboxReady = MutableStateFlow(true), importAttachments = { _, _ -> imported.await() })
        runCurrent()
        vm.openConversation("a")
        vm.input("A with file")
        vm.attach(listOf("content://a.pdf"))
        runCurrent()
        assertTrue(vm.send(vm.ui.value.input, ok))
        vm.openConversation("b")
        vm.input("B untouched")
        imported.complete(ImportResult(emptyList(), listOf("a.pdf")))
        runCurrent()
        assertEquals("B untouched", vm.ui.value.input)
        assertTrue(calls.none { it.startsWith("start:") })
        vm.openConversation("a")
        assertEquals("A with file", vm.ui.value.input)
        assertFalse(vm.ui.value.importing)
        assertEquals(1, vm.ui.value.attachments.size)
    }

    @Test fun androidSavedStateRestoresTextsButDoesNotOverwriteLiveDrafts() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.openConversation("a")
        vm.input("A")
        vm.openConversation("b")
        vm.input("B")
        val saved = vm.saveDraftTexts()
        vm.input("newer B")
        vm.restoreDraftTexts(saved)
        assertEquals("newer B", vm.ui.value.input)
        val restored = vm(store)
        restored.restoreDraftTexts(saved)
        assertEquals("b", restored.ui.value.conversationId)
        assertEquals("B", restored.ui.value.input)
        restored.openConversation("a")
        assertEquals("A", restored.ui.value.input)
    }

    @Test fun consecutiveSharesRetainEditedTextAndPrivateImagesInTheirOwnConversations() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val shared = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        shared.attachmentFile("one.jpg")!!.writeBytes(byteArrayOf(1))
        val image = SharedDraftAttachment("one.jpg", "one.jpg", 1)
        vm.openSharedDraft(SharedDraft("", "first share", image))
        val first = vm.ui.value.conversationId!!
        vm.input("edited share")
        vm.openSharedDraft(SharedDraft("", "second share"))
        val second = vm.ui.value.conversationId!!
        vm.openConversation(first)
        assertEquals("edited share", vm.ui.value.input)
        assertEquals("one.jpg", vm.ui.value.attachments.single().name)
        assertTrue(shared.attachmentFile("one.jpg")!!.exists())
        vm.openConversation(second)
        assertEquals("second share", vm.ui.value.input)
        vm.deleteConversation(first)
        assertFalse(shared.attachmentFile("one.jpg")!!.exists())
        assertEquals("second share", vm.ui.value.input)
    }

    @Test fun clearingSharedTextBeforeSwitchDoesNotRestoreTheOldShareOnRestart() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val shared = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        vm.openSharedDraft(SharedDraft("", "clear me"))
        val id = vm.ui.value.conversationId!!
        vm.input("")
        vm.newConversation()
        assertTrue(shared.readAll().isEmpty())
        val restored = ChatViewModel(backgroundScope, state, stage, actions, store, sharedDrafts = shared)
        runCurrent()
        restored.openConversation(id)
        assertEquals("", restored.ui.value.input)
        assertNull(restored.ui.value.sharedDraft)
    }

    @Test fun blocksWhenPrerequisiteMissing() = runTest {
        val vm = vm()
        val check = Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = false)
        assertFalse(vm.send("查天气", check))
        assertTrue(calls.isEmpty())
        // Accessibility is only needed for operating phone apps and does not block tasks; only a missing model blocks
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // Notifications being off does not count as blocking
        assertTrue(vm.send("查天气", Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)))
        assertNull(vm.ui.value.blocked)
        assertEquals(listOf("start:查天气"), calls)
    }

    @Test fun showsStepsFromState() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())

        emit(TaskState.Running(1, ""))
        var steps = vm.ui.value.exchanges.single().steps
        assertTrue(steps.isEmpty())
        assertEquals("正在处理", liveLine(vm.ui.value.exchanges.single()))

        // A tool has been chosen but not used yet: no row is listed by tool name; list it once it is actually done
        emit(TaskState.Running(1, "我先打开设置", "phone_launch_app"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())
        did(1, "act", "phone_launch_app", "com.chat", "place_changed")
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "打开 App · 聊天", "", StepState.Run), steps.single())
        assertEquals("打开 App · 聊天", liveLine(vm.ui.value.exchanges.single()))

        // Reads: "look at the screen" never appears; consecutive reads of the same place merge into one row; reads with no known place are not listed
        emit(TaskState.Running(2, "我先打开设置"))
        emit(TaskState.Running(2, "phone_observe", "phone_observe"))
        did(2, "observe", "phone_observe", "com.chat", title = "会话列表")
        did(2, "observe", "phone_diagnose", "com.chat", title = "会话列表")
        did(2, "observe", "phone_observe", null)
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("打开 App · 聊天", "看了聊天的会话列表"), steps.map { it.text })
        assertEquals(listOf(StepState.Ok, StepState.Run), steps.map { it.state })

        emit(TaskState.WaitingTakeover("captcha", "出现滑块验证码"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals("需要你处理", liveLine(ex))
        assertEquals(ExchangeStatus.NeedsTakeover("captcha", "出现滑块验证码"), ex.status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.takeOver()
        assertEquals("takeOver", calls.last())
    }

    /** Calling an external connector over MCP: while running it says which one is being called; when done a separate row names the connector config, and a failure still shows which connector it was. */
    @Test fun mcpConnectorNamedOnLiveLineAndSteps() = runTest {
        val vm = vm()
        vm.send("查一下团队笔记", ok)
        emit(TaskState.Running(1, "我去笔记里找找", mcpConnector = "Team notes"))
        assertEquals("正在调用 Team notes MCP…", liveLine(vm.ui.value.exchanges.single()))

        acts.value = acts.value + RunEvent.Activity(0, 1, "act", "mcp_demo__search", null, "completed", mcpConnector = "Team notes")
        runCurrent()
        acts.value = acts.value + RunEvent.Activity(0, 2, "act", "mcp_demo__search", null, "some_error", failed = true, mcpConnector = "Team notes")
        runCurrent()
        emit(TaskState.Succeeded("找到了"))
        val steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "已调用 Team notes MCP", "", StepState.Ok), steps[0])
        assertEquals(StepRow(2, "已调用 Team notes MCP", tr(R.string.step_failed), StepState.Ok), steps[1])
        // Error codes are not shown to the user
        assertFalse(steps.joinToString().contains("some_error") || steps.joinToString().contains("mcp_demo"))
    }

    @Test fun paymentTakeoverCarriesDetailsAndCanBeSkipped() = runTest {
        val vm = vm()
        vm.send("买一杯咖啡", ok)
        emit(TaskState.WaitingTakeover("payment", "请付款", item = "拿铁 大杯", amount = "¥18", address = "公司前台"))
        assertEquals(
            ExchangeStatus.NeedsTakeover("payment", "请付款", "拿铁 大杯", "¥18", "公司前台"),
            vm.ui.value.exchanges.single().status,
        )
        vm.skip()
        assertEquals("skip", calls.last())
    }

    // The answer is shown as-is; the source is not split out of the answer but comes from the copy recorded by code in the run record
    @Test fun finalAnswerShownAsIs() = runTest {
        val vm = vm()
        vm.send("电量多少", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        did(1, "observe", "phone_observe", "com.chat")
        emit(TaskState.Running(2, "phone_observe")) // It ended while the next step was still waiting on the model: this row is not kept
        emit(TaskState.Succeeded("电量 86%,正在充电。来源:设置 › 电池"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("电量 86%,正在充电。来源:设置 › 电池", null), ex.status)
        assertEquals(listOf(StepRow(1, "看了聊天", "", StepState.Ok)), ex.steps)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        assertEquals("没有依据段落" to null, splitEvidence("没有依据段落"))
        // The form used in old records (conversation files): the last separate line starts with the evidence label, and it is still split out as before
        assertEquals("已把亮度调到 50%。\n自动亮度已关闭。" to "设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关",
            splitEvidence("已把亮度调到 50%。\n自动亮度已关闭。\n依据: 设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关"))
        assertEquals("结论。" to "来自搜索结果第一条", splitEvidence("结论。证据：来自搜索结果第一条"))
    }

    /** Every step carries the action it actually performed: only by opening the step can you see which tool was used, on what, and with what result. */
    @Test fun eachStepCarriesWhatItActuallyDid() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        did(1, "act", "phone_tap", "com.chat", "changed")
        did(1, "act", "phone_type_text", "com.other", "unknown_code", failed = true)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(
            listOf(
                // Uses the UI wording for the tool, without the tool name
                StepAction("点击", "聊天", "changed", false),
                StepAction("输入文字", "com.other", "unknown_code", true),
            ),
            ex.stepActions.getValue(1),
        )
        // Attached only to the step that has actions
        assertEquals(setOf(1), ex.stepActions.keys)
        // Opened, each action is one line: what was done · on what · result; error codes are not shown, it just says it failed
        val lines = ex.stepActions.getValue(1).map(ChatCopy::actionLine)
        assertEquals("点击 · 聊天 · ${tr(R.string.result_effective)}", lines[0])
        assertEquals("输入文字 · com.other · ${tr(R.string.step_failed)}", lines[1])
        lines.forEach { assertFalse(it, it.contains("phone_") || it.contains("unknown_code")) }
    }

    @Test fun unverifiedIsMarkedOnTheResult() = runTest {
        val vm = vm()
        vm.send("查余额", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元", verified = false, note = "结果页受系统防截屏保护"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("余额 10 元", null, verified = false, note = "结果页受系统防截屏保护"), ex.status)
        assertEquals("办完了,但没能核实:结果页受系统防截屏保护", (ex.status as ExchangeStatus.Done).unverifiedLine)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)

        vm.send("再查", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元"))
        assertNull((vm.ui.value.exchanges.last().status as ExchangeStatus.Done).unverifiedLine)
    }

    @Test fun liveLineUsesTheModelsReason() = runTest {
        var now = 1_000L
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen, clock = { now },
            activities = acts).also { runCurrent() }
        vm.send("查上海到杭州的车次", ok)
        emit(TaskState.Running(1, "", "phone_launch_app", reason = "打开 12306"))
        did(1, "act", "phone_launch_app", "cn.railway")
        screen.value = true
        runCurrent()
        var ex = vm.ui.value.exchanges.single()
        assertEquals(StepRow(1, "打开 App · cn.railway", "", StepState.Run, "打开 12306"), ex.steps.single())
        assertEquals("打开 12306", liveLine(ex))
        assertTrue(vm.ui.value.operatingPhone)

        // While a model request is in flight it shows a neutral status and does not invent new steps.
        emit(TaskState.Running(2, ""))
        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.steps.size)
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(2, "", "phone_tap", reason = "正在 12306 搜索上海到杭州的车次"))
        assertEquals("正在 12306 搜索上海到杭州的车次", liveLine(vm.ui.value.exchanges.single()))
        did(2, "act", "phone_tap", "cn.railway", "changed")

        // Waiting for unlock does not count as operating in the background
        emit(TaskState.Held(TaskGate.LOCKED))
        assertFalse(vm.ui.value.operatingPhone)

        emit(TaskState.Running(3, "正在核实结果", reason = "正在核实结果"))
        did(3, "observe", "phone_observe", "cn.railway", title = "查询结果")
        now = 81_000L
        emit(TaskState.Succeeded("有 3 趟\n依据:12306 查询结果页"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(80_000L, ex.elapsedMs)
        assertFalse(vm.ui.value.operatingPhone)
    }

    /** The top bar subtitle for "operating ...": the app of the most recent action that had a target (package name replaced by the app name). */
    @Test fun livePlaceIsTheLatestTargetedApp() = runTest {
        val vm = vm()
        vm.send("看聊天", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertNull(vm.livePlace(vm.ui.value.exchanges.single()))
        did(1, "act", "phone_launch_app", "com.chat")
        did(2, "act", "phone_wait", null)
        val ex = vm.ui.value.exchanges.single()
        assertEquals("聊天", vm.livePlace(ex))
        assertEquals("正在操作聊天" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, vm.livePlace(ex)))
    }

    // The progress note written along with the call takes precedence over the intent in the call
    @Test fun liveLinePrefersNarration() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("查车次", ok)
        emit(TaskState.Running(1, "先打开 12306 看看", "phone_launch_app", reason = "打开 12306", narration = "先打开 12306 看看"))
        assertEquals("先打开 12306 看看", liveLine(vm.ui.value.exchanges.single()))
        emit(TaskState.Running(2, "", "phone_tap", reason = "点搜索"))
        assertEquals("点搜索", liveLine(vm.ui.value.exchanges.single()))
    }

    @Test fun streamedTextIsVisibleDuringRequestAndClearedAtCompletion() = runTest {
        val vm = vm()
        vm.send("解释这个概念", ok)
        emit(TaskState.Running(1, "", preview = "第一段"))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("第一段", ex.draft)
        assertTrue(ex.steps.isEmpty())
        assertEquals("正在处理", liveLine(ex))

        emit(TaskState.Running(1, "", preview = "第一段和第二段"))
        assertEquals("第一段和第二段", vm.ui.value.exchanges.single().draft)

        emit(TaskState.Succeeded("完整答复"))
        ex = vm.ui.value.exchanges.single()
        assertNull(ex.draft)
        assertEquals(ExchangeStatus.Done("完整答复", null), ex.status)
    }

    // The few items still unmatched after one retry: listed one by one on the result card, without a green check
    @Test fun unconfirmedItemsAreListedOnTheCard() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("看价格", ok)
        emit(TaskState.Running(1, "", "phone_observe"))
        emit(TaskState.Succeeded("咖啡豆 ¥79", verified = false, note = "未确认:79", unconfirmed = listOf("79")))
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals("咖啡豆 ¥79", done.answer)
        assertNull(done.evidence)
        assertEquals("这几处没能确认:79", done.unverifiedLine)
    }

    @Test fun phoneHintOnlyWhileTheScreenIsOpen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("算一下 1+1", ok)
        emit(TaskState.Running(1, "", "run_command"))
        // The screen was never opened (file, web or chat-only tasks): it does not say it is operating the phone in the background
        assertFalse(vm.ui.value.operatingPhone)
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.operatingPhone)
        emit(TaskState.Succeeded("2"))
        assertFalse(vm.ui.value.operatingPhone)
    }

    @Test fun answerPartsPrefersAStructuredEvidence() {
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池"))
        assertEquals("电量 86%" to "电池页", answerParts("电量 86% ", evidence = " 电池页 "))
        assertEquals("电量 86%\n依据:设置 › 电池" to "电池页", answerParts("电量 86%\n依据:设置 › 电池", evidence = "电池页"))
        // When an empty one is given, the answer is still split as before
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池", evidence = " "))
        assertEquals("没有依据" to null, answerParts("没有依据"))
    }

    // New records: the sources are found by code in the evidence ledger (sources event) and listed one by one; the screenshots are those of that read step
    @Test fun evidenceSourceUsesRecordedSources() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Sources(3, listOf(
                com.wangzan101.mika.core.safety.verify.AnswerSource(1, "screen", "com.chat", null, 1, listOf(1)),
                com.wangzan101.mika.core.safety.verify.AnswerSource(4, "web", "https://www.12306.cn/index", "车次查询", 3, emptyList()),
            )),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(1), listOf("第 1 步 · 聊天", "第 3 步 · www.12306.cn · 车次查询")), source)
        assertEquals("来源 · www.12306.cn · 1 张截图", sourceLabel(source))
    }

    @Test fun evidenceSourceIsTheLastSavedScreen() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "网站 www.12306.cn"),
            RunEvent.Media(3, 3, 3, null),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(2, 3)), source)
        assertEquals("来源 · www.12306.cn · 2 张截图", sourceLabel(source))
        assertEquals(EvidenceSource("聊天", listOf(1)), evidenceSource(events.take(1)) { if (it == "com.chat") "聊天" else it })
        // With no screenshots (file or chat-only tasks), or before they have been taken, it only writes "source"
        assertEquals(EvidenceSource(null, emptyList()), evidenceSource(emptyList()))
        assertEquals("来源", sourceLabel(EvidenceSource(null, emptyList())))
        assertEquals("来源", sourceLabel(null))
    }

    @Test fun livePreviewVisibleOnlyWhileRunning() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        assertFalse(vm.ui.value.pip.visible)
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)

        emit(TaskState.Succeeded("好了"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()
        assertFalse(vm.ui.value.pip.visible)

        screen.value = false
        runCurrent()
        vm.send("再来", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        emit(TaskState.Failed("出错了", FailKind.GaveUp))
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun pipOnlyOnceTheTaskHasAScreen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("打开设置看安卓版本", ok)
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        assertFalse(vm.ui.value.pip.visible)
        vm.expandPip()
        assertFalse(vm.ui.value.pip.expanded)
        // A question asked before the app is opened stays in the chat card only
        emit(TaskState.WaitingInput("哪个?", null))
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(2, "phone_launch_app", "phone_launch_app"))

        screen.value = true // The first phone_launch_app created the screen
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)
        // Takeover after the screen has been opened still needs you as usual
        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        // Wrap-up drops the screen before reporting the end: the few seconds it lingers after completion still show it
        screen.value = false
        runCurrent()
        emit(TaskState.Succeeded("安卓 14\n依据:关于手机页"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
    }

    @Test fun chatOnlyTaskNeverShowsPip() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = MutableStateFlow(false)).also { runCurrent() }
        vm.send("你好", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Running(2, ""))
        emit(TaskState.Succeeded("你好!", verified = false, reviewed = false))
        assertFalse(vm.ui.value.pip.visible)
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals(ExchangeStatus.Done("你好!", null, verified = false, reviewed = false), done)
        // A chat where nothing was verified: no mark
        assertNull(done.unverifiedLine)
    }

    @Test fun pipSnapsToNearestCorner() {
        val b = PipPhysics.Bounds(1080f, 2400f, 200f, 420f, margin = 30f, top = 300f, bottom = 250f)
        assertEquals(850f to 330f, b.position(Corner.TopEnd))
        assertEquals(30f to 1700f, b.position(Corner.BottomStart))
        // Resting near the top-right with no velocity: back to the top-right
        assertEquals(Corner.TopEnd, PipPhysics.snapCorner(700f, 500f, 0f, 0f, b))
        // Flinging downward from the same position: the projected landing is in the lower half of the screen
        assertEquals(Corner.BottomEnd, PipPhysics.snapCorner(700f, 500f, 0f, 2500f, b))
        // Flinging toward the bottom-left
        assertEquals(Corner.BottomStart, PipPhysics.snapCorner(700f, 500f, -2000f, 2500f, b))
        assertEquals(499f, PipPhysics.project(1000f), 0.01f)
        // The window is too short (e.g. the keyboard is up): it does not tuck under the top bar, and the bottom corner retreats to the same place as the top one
        val short = b.copy(height = 900f)
        assertEquals(30f to 330f, short.position(Corner.BottomStart))

        // Critically damped: it converges on the target without overshooting
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(0f, 0f)
        var max = 0f
        repeat(120) {
            m = spring.step(m, 100f, 1f / 60)
            max = maxOf(max, m.position)
        }
        assertTrue(spring.settled(m, 100f))
        assertTrue("overshoot $max", max <= 100.5f)
    }

    @Test fun pipAutoExpandsWhenNeedsUser() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded)

        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        vm.collapsePip()
        assertFalse(vm.ui.value.pip.expanded)
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded) // It does not pop open repeatedly during the same wait
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)

        emit(TaskState.Running(2, "", null))
        assertFalse(vm.ui.value.pip.expanded)
        // Only takeover expands it automatically: when asking a question the composer and the options must stay in view
        emit(TaskState.WaitingInput("选哪个?", listOf("A", "B")))
        assertFalse(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        assertTrue(vm.send("A", ok))
        assertEquals("answer:A", calls.last())
    }

    @Test fun sharedDraftIsFreshPersistentAndNeverRunsBeforeExplicitSend() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()

        assertTrue(vm.openSharedDraft(SharedDraft("", "https://example.com")))
        val first = vm.ui.value.conversationId!!
        assertEquals("https://example.com", vm.ui.value.sharedDraft?.text)
        assertEquals(CurrentChat.Open(first), conversations.currentChat())
        assertTrue(conversations.list().isEmpty())
        assertTrue(calls.isEmpty())

        // Sharing the same text again is still another new conversation, leaving no two empty conversation records.
        assertTrue(vm.openSharedDraft(SharedDraft("", "https://example.com")))
        val second = vm.ui.value.conversationId!!
        assertTrue(first != second)
        assertEquals(second, drafts.read()?.conversationId)
        assertTrue(conversations.list().isEmpty())
        assertTrue(calls.isEmpty())

        // A process rebuild restores only the one matching the current conversation and still does not execute it.
        val restored = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertEquals(second, restored.ui.value.conversationId)
        assertEquals("https://example.com", restored.ui.value.sharedDraft?.text)
        assertTrue(calls.isEmpty())

        assertTrue(restored.send("https://example.com", ok))
        assertEquals(listOf("start:https://example.com"), calls)
        assertEquals(listOf(first), drafts.readAll().map { it.conversationId })
    }

    @Test fun restoringSharedDraftKeepsAlreadyReadAttachmentCapability() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val initial = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts).also { runCurrent() }
        assertTrue(initial.openSharedDraft(SharedDraft("", "分享草稿")))

        // On a real device the main thread receives the initial sandboxReady value immediately and then restores the draft; restoring must not overwrite the capability already read.
        val immediate = kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
        )
        val restored = ChatViewModel(
            immediate, state, stage, actions, conversations,
            sandboxReady = MutableStateFlow(true), sharedDrafts = drafts,
        )
        assertEquals("分享草稿", restored.ui.value.sharedDraft?.text)
        assertTrue(restored.ui.value.attachEnabled)
    }

    @Test fun sharedDraftImageUsesTheExistingAttachmentImportAndSendPath() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("shared.jpg")!!.writeBytes(byteArrayOf(1, 2, 3))
        val imported = mutableListOf<List<String>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, uris ->
                imported += uris
                ImportResult(listOf("/workspace/inbox/photo.jpg" to 3L), emptyList())
            },
        )
        runCurrent()
        val image = SharedDraftAttachment("shared.jpg", "photo.jpg", 3)
        assertTrue(vm.openSharedDraft(SharedDraft("", "说明这张图", image)))
        assertEquals(PendingAttachment("content://mika.shared/shared.jpg", "photo.jpg", 3, photo = true),
            vm.ui.value.attachments.single())
        assertTrue(imported.isEmpty())
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("说明这张图", ok))
        runCurrent()
        assertEquals(listOf(listOf("content://mika.shared/shared.jpg")), imported)
        assertEquals(listOf("start:说明这张图\n附件:/workspace/inbox/photo.jpg(3 B)"), calls)
        assertNull(drafts.read())
        assertFalse(drafts.attachmentFile("shared.jpg")!!.exists())
    }

    @Test fun sharedDocumentRestoresAsAFileAndSurvivesAFailedSend() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val document = SharedDraftAttachment("unique.pdf", "周报.pdf", 3, photo = false)
        val file = drafts.attachmentFile(document.fileName)!!
        file.writeBytes(byteArrayOf(1, 2, 3))
        val initial = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertTrue(initial.openSharedDraft(SharedDraft("", "", document)))

        val pending = PendingAttachment("content://mika.shared/unique.pdf", "周报.pdf", 3, photo = false)
        var attempts = 0
        val restored = ChatViewModel(
            backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, uris ->
                assertEquals(listOf(pending.uri), uris)
                if (++attempts == 1) ImportResult(emptyList(), listOf(document.name))
                else ImportResult(listOf("/workspace/inbox/周报.pdf" to document.size), emptyList())
            },
        )
        runCurrent()
        assertEquals(listOf(pending), restored.ui.value.attachments)
        assertEquals(0, attempts)
        assertTrue(calls.isEmpty())

        assertTrue(restored.send("", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(document, drafts.read()?.attachment)
        assertEquals(listOf(pending), restored.ui.value.attachments)

        assertTrue(restored.send("", ok))
        runCurrent()
        assertEquals(listOf("start:附件:/workspace/inbox/周报.pdf(3 B)"), calls)
        assertNull(drafts.read())
        assertFalse(file.exists())
    }

    @Test fun olderAttachmentImportCannotClearANewerSharedDraft() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("first.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.attachmentFile("second.jpg")!!.writeBytes(byteArrayOf(2))
        val releaseImport = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, _ ->
                releaseImport.await()
                com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/first.jpg" to 1L), emptyList())
            },
        )
        runCurrent()
        val first = SharedDraftAttachment("first.jpg", "first.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "第一份", first)))
        val firstId = vm.ui.value.conversationId
        assertTrue(vm.send("第一份", ok))
        assertTrue(vm.ui.value.importing)

        val second = SharedDraftAttachment("second.jpg", "second.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "", second)))
        val secondId = vm.ui.value.conversationId
        releaseImport.complete(Unit)
        runCurrent()
        assertEquals(secondId, vm.ui.value.conversationId)
        assertEquals(second, vm.ui.value.sharedDraft?.attachment)
        assertEquals(listOf("content://mika.shared/second.jpg"), vm.ui.value.attachments.map { it.uri })
        assertTrue(calls.isEmpty())

        // A pure-image draft disappears entirely once the image is removed, leaving no blank share metadata.
        vm.removeAttachment("content://mika.shared/second.jpg")
        assertNull(vm.ui.value.sharedDraft)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals(listOf(firstId), drafts.readAll().map { it.conversationId })
        assertTrue(drafts.attachmentFile("first.jpg")!!.exists())
        assertFalse(drafts.attachmentFile("second.jpg")!!.exists())
    }

    @Test fun inactiveSharedDraftDoesNotReplaceAnotherCurrentConversation() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertTrue(vm.openSharedDraft(SharedDraft("", "draft")))
        assertFalse(vm.openSharedDraft(SharedDraft("", " ")))
        assertEquals("draft", vm.ui.value.sharedDraft?.text)

        conversations.setCurrent(CurrentChat.Fresh)
        val recreated = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertNull(recreated.ui.value.conversationId)
        assertNull(recreated.ui.value.sharedDraft)
        assertEquals("draft", drafts.read()?.text)
        assertTrue(calls.isEmpty())
    }

    @Test fun shareDuringPendingStartHandsOffOnlyTheOldTaskAndItsSteers() = runTest {
        val busy = MutableStateFlow(true)
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, busy, sharedDrafts = drafts)
        runCurrent()
        assertTrue(vm.send("原来的任务", ok))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("原来的插话", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        val old = vm.ui.value.conversationId!!

        assertTrue(vm.openSharedDraft(SharedDraft("", "新草稿")))
        assertEquals(listOf("start:原来的任务", "steer:原来的插话"), calls)
        assertFalse(calls.any { it.startsWith("cancel") })
        assertTrue(old != vm.ui.value.conversationId)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // Even if the waiting coroutine later sees the service idle, it must not start the original task again or send the new conversation's words over.
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:原来的任务", "steer:原来的插话"), calls)
        assertTrue(calls.isNotEmpty())
    }

    @Test fun shareInCurrentConversationPrefillsDirectlyAndPreservesHistoryOnRestart() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val existing = conversations.newConversation()
        conversations.append(existing, Turn("上一轮问题", "上一轮回答", "succeeded"))
        val drafts = SharedDraftStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()

        // When you are in an already open conversation with no draft, a background share goes straight into the current conversation: no new conversation is created and no history turns are lost.
        vm.openConversation(existing)
        assertEquals(existing, vm.ui.value.conversationId)
        assertEquals(1, vm.ui.value.exchanges.size)
        assertTrue(vm.openSharedDraft(SharedDraft("", "后台分享文字"), inCurrentConversation = true))
        assertEquals(existing, vm.ui.value.conversationId)
        assertNull(vm.ui.value.pendingSharedDraft)
        assertEquals("后台分享文字", vm.ui.value.input)
        assertEquals("后台分享文字", vm.ui.value.sharedDraft?.text)
        assertEquals(1, vm.ui.value.exchanges.size)
        assertEquals("上一轮问题", vm.ui.value.exchanges.single().goal)

        // When the shared text is edited in the current conversation without switching conversations, the edit is synced to disk in real time; a restart restores the edited text along with the existing history turns.
        vm.input("修改后的分享文字")
        val restored = ChatViewModel(backgroundScope, state, stage, actions, conversations, sharedDrafts = drafts)
        runCurrent()
        assertEquals(existing, restored.ui.value.conversationId)
        assertEquals("修改后的分享文字", restored.ui.value.input)
        assertEquals(listOf("上一轮问题"), restored.ui.value.exchanges.map { it.goal })
    }

    @Test fun shareInCurrentConversationPromptsWhenDraftExistsAndSupportsReplaceOrCancel() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("old.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.attachmentFile("cancelled.jpg")!!.writeBytes(byteArrayOf(2))
        drafts.attachmentFile("replaced.jpg")!!.writeBytes(byteArrayOf(3))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
        )
        runCurrent()

        val oldImage = SharedDraftAttachment("old.jpg", "old.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "原有草稿", oldImage), inCurrentConversation = true))
        val currentId = vm.ui.value.conversationId!!
        vm.quote(QuoteMessage.From.Mika, "被引用的回复")

        // A background share arrives while a draft already exists: it is parked in pendingSharedDraft for the user to choose, and the original draft is untouched.
        val cancelledImage = SharedDraftAttachment("cancelled.jpg", "cancelled.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "将被取消的分享", cancelledImage), inCurrentConversation = true))
        assertEquals("将被取消的分享", vm.ui.value.pendingSharedDraft?.text)
        assertEquals("原有草稿", vm.ui.value.input)
        assertNotNull(vm.ui.value.quote)

        val missingImage = cancelledImage.copy(fileName = "missing.jpg")
        assertFalse(vm.openSharedDraft(SharedDraft("", "无效的替换", missingImage), inCurrentConversation = true))
        assertEquals("将被取消的分享", vm.ui.value.pendingSharedDraft?.text)
        assertEquals(vm.ui.value.pendingSharedDraft, drafts.readPending())
        assertTrue(drafts.attachmentFile("cancelled.jpg")!!.isFile)

        // Tapping "cancel": the original draft and image are kept, and the temp image copy of the new share is deleted.
        vm.cancelPendingSharedDraft()
        assertNull(vm.ui.value.pendingSharedDraft)
        assertEquals("原有草稿", vm.ui.value.input)
        assertNotNull(vm.ui.value.quote)
        assertTrue(drafts.attachmentFile("old.jpg")!!.exists())
        assertFalse(drafts.attachmentFile("cancelled.jpg")!!.exists())

        // Another background share: if the process was reclaimed and rebuilt while the dialog was up, cleanup does not wrongly delete the image awaiting confirmation, and "replace" can still be tapped after the rebuild.
        val replacedImage = SharedDraftAttachment("replaced.jpg", "replaced.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "确认替换的分享", replacedImage), inCurrentConversation = true))
        drafts.cleanup()
        assertTrue(drafts.attachmentFile("replaced.jpg")!!.exists())
        val restored = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
        )
        runCurrent()
        assertEquals("确认替换的分享", restored.ui.value.pendingSharedDraft?.text)
        assertTrue(restored.confirmPendingSharedDraft())
        assertNull(restored.ui.value.pendingSharedDraft)
        assertEquals(currentId, restored.ui.value.conversationId)
        assertEquals("确认替换的分享", restored.ui.value.input)
        assertNull(restored.ui.value.quote)
        assertEquals(listOf("content://mika.shared/replaced.jpg"), restored.ui.value.attachments.map { it.uri })
        assertFalse(drafts.attachmentFile("old.jpg")!!.exists())
        assertTrue(drafts.attachmentFile("replaced.jpg")!!.exists())
    }

    @Test fun shareImageInBusyConversationAutomaticallySwitchesToQueueMode() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("busy.jpg")!!.writeBytes(byteArrayOf(1, 2))
        drafts.attachmentFile("answer.jpg")!!.writeBytes(byteArrayOf(3, 4))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/answer.jpg" to 2L), emptyList()) },
        )
        runCurrent()

        assertTrue(vm.send("正在运行的任务", ok))
        state.value = TaskState.Running(1, "step")
        runCurrent()
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)

        // A background image share arrives while a task is running: it switches to queue mode automatically.
        val image = SharedDraftAttachment("busy.jpg", "busy.jpg", 2)
        assertTrue(vm.openSharedDraft(SharedDraft("", "排队看这张图", image), inCurrentConversation = true))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertEquals(listOf("content://mika.shared/busy.jpg"), vm.ui.value.attachments.map { it.uri })

        // After editing the text the user switches back to steer mode manually: the image copy is cleaned up and the edited text remains in the draft store.
        vm.input("改成纯文字插话")
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertFalse(drafts.attachmentFile("busy.jpg")!!.exists())
        assertEquals("改成纯文字插话", drafts.read()?.text)

        // After the steer is sent, the share draft is cleared from the store as well.
        assertTrue(vm.send("改成纯文字插话", ok))
        assertNull(vm.ui.value.sharedDraft)
        assertNull(drafts.read())

        // A background image share arrives in Answer mode (NeedsInput), even with no text: it likewise switches to queue mode and queues the attachments for sending normally.
        state.value = TaskState.WaitingInput("选哪个?", listOf("A", "B"))
        runCurrent()
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        val answerImg = SharedDraftAttachment("answer.jpg", "answer.jpg", 2)
        assertTrue(vm.openSharedDraft(SharedDraft("", "", answerImg), inCurrentConversation = true))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        assertTrue(vm.send("", ok))
        runCurrent()
        assertTrue(calls.contains("queue:附件:/workspace/inbox/answer.jpg(2 B)"))
        assertFalse(drafts.attachmentFile("answer.jpg")!!.exists())
    }

    @Test fun shareIntoCurrentConversationDuringAttachmentImportDoesNotPromptOrDeleteImportingFile() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("first.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.attachmentFile("second.jpg")!!.writeBytes(byteArrayOf(2))
        val releaseImport = CompletableDeferred<Unit>()
        var firstFileExistedDuringImport = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, _ ->
                releaseImport.await()
                firstFileExistedDuringImport = drafts.attachmentFile("first.jpg")!!.exists()
                ImportResult(listOf("/workspace/inbox/first.jpg" to 1L), emptyList())
            },
        )
        runCurrent()

        val first = SharedDraftAttachment("first.jpg", "first.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", first), inCurrentConversation = true))
        assertTrue(vm.send("看图", ok))
        assertTrue(vm.ui.value.importing)

        // A new share for the same conversation arrives while the previous shared image is still importing asynchronously: no stale replace-confirmation dialog appears, and the importing first.jpg is not deleted early.
        val second = SharedDraftAttachment("second.jpg", "second.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", second), inCurrentConversation = true))
        assertNull(vm.ui.value.pendingSharedDraft)
        assertTrue(drafts.attachmentFile("first.jpg")!!.exists())

        releaseImport.complete(Unit)
        runCurrent()
        assertTrue(firstFileExistedDuringImport)
        assertFalse(drafts.attachmentFile("first.jpg")!!.exists())
        assertTrue(drafts.attachmentFile("second.jpg")!!.exists())
        assertEquals("看图", vm.ui.value.input)
        assertEquals(listOf("content://mika.shared/second.jpg"), vm.ui.value.attachments.map { it.uri })
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun editingSharedTextDuringImportStillClearsSentAttachmentsAndSharedDraft() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("shot.jpg")!!.writeBytes(byteArrayOf(1))
        val releaseImport = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, _ ->
                releaseImport.await()
                ImportResult(listOf("/workspace/inbox/shot.jpg" to 1L), emptyList())
            },
        )
        runCurrent()

        val shot = SharedDraftAttachment("shot.jpg", "shot.jpg", 1)
        assertTrue(vm.openSharedDraft(SharedDraft("", "看图", shot), inCurrentConversation = true))
        assertTrue(vm.send("看图", ok))
        assertTrue(vm.ui.value.importing)

        // Only the text was edited during the import, which does not count as being replaced by a new share: the sent image must not remain above the composer, and the share draft and image copy are cleared as usual.
        vm.input("看图改")
        releaseImport.complete(Unit)
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertNull(vm.ui.value.sharedDraft)
        assertNull(drafts.read())
        assertFalse(drafts.attachmentFile("shot.jpg")!!.exists())
    }

    @Test fun multiFileSharedDraftSupportsPartialRemovalOverLimitAndSendingAll() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("a.jpg")!!.writeBytes(byteArrayOf(1, 2))
        drafts.attachmentFile("b.pdf")!!.writeBytes(byteArrayOf(3, 4, 5))
        drafts.attachmentFile("c.png")!!.writeBytes(byteArrayOf(6))
        val imported = mutableListOf<List<String>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, uris ->
                imported += uris
                ImportResult(listOf("/workspace/inbox/a.jpg" to 2L, "/workspace/inbox/c.png" to 1L), emptyList())
            },
        )
        runCurrent()
        val a = SharedDraftAttachment("a.jpg", "a.jpg", 2, photo = true)
        val b = SharedDraftAttachment("b.pdf", "b.pdf", 3, photo = false)
        val c = SharedDraftAttachment("c.png", "c.png", 1, photo = true)
        val failure = com.wangzan101.mika.cap.sandbox.AttachmentFailure(
            "huge.zip", com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.TOO_LARGE,
        )
        assertTrue(vm.openSharedDraft(SharedDraft("", "一起看", listOf(a, b, c)), rejected = listOf(failure), overLimit = 2))
        assertEquals(listOf("a.jpg", "b.pdf", "c.png"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf(failure), vm.ui.value.rejectedAttachments)
        assertEquals(2, vm.ui.value.overLimit)

        // Only the middle attachment is removed: the other two attachments and their disk copies are kept and can be restored after a restart.
        vm.removeAttachment("content://mika.shared/b.pdf")
        assertEquals(listOf("a.jpg", "c.png"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf(a, c), vm.ui.value.sharedDraft?.attachments)
        assertFalse(drafts.attachmentFile("b.pdf")!!.exists())
        assertTrue(drafts.attachmentFile("a.jpg")!!.exists())
        assertTrue(drafts.attachmentFile("c.png")!!.exists())
        assertEquals(listOf(a, c), SharedDraftStore(drafts.attachmentFile("a.jpg")!!.parentFile!!).read()?.attachments)

        // Send the remaining two attachments and clear all private copies.
        assertTrue(vm.send("一起看", ok))
        runCurrent()
        assertEquals(listOf(listOf("content://mika.shared/a.jpg", "content://mika.shared/c.png")), imported)
        assertEquals(listOf("start:一起看\n附件:/workspace/inbox/a.jpg(2 B)\n附件:/workspace/inbox/c.png(1 B)"), calls)
        assertNull(drafts.read())
        assertFalse(drafts.attachmentFile("a.jpg")!!.exists())
        assertFalse(drafts.attachmentFile("c.png")!!.exists())
    }

    @Test fun multiFilePendingSharePreservesWarningsOnConfirmAndProtectsAllDuringImport() = runTest {
        val conversations = ConversationStore(tmp.newFolder())
        val drafts = SharedDraftStore(tmp.newFolder())
        drafts.attachmentFile("p1.jpg")!!.writeBytes(byteArrayOf(1))
        drafts.attachmentFile("p2.pdf")!!.writeBytes(byteArrayOf(2))
        drafts.attachmentFile("n1.jpg")!!.writeBytes(byteArrayOf(3))
        val releaseImport = CompletableDeferred<Unit>()
        var bothExisted = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            sharedDrafts = drafts,
            sharedDraftUri = { "content://mika.shared/${it.fileName}" },
            importAttachments = { _, _ ->
                releaseImport.await()
                bothExisted = drafts.attachmentFile("p1.jpg")!!.exists() && drafts.attachmentFile("p2.pdf")!!.exists()
                ImportResult(listOf("/workspace/inbox/p1.jpg" to 1L, "/workspace/inbox/p2.pdf" to 1L), emptyList())
            },
        )
        runCurrent()
        vm.input("已有输入")

        val p1 = SharedDraftAttachment("p1.jpg", "p1.jpg", 1, photo = true)
        val p2 = SharedDraftAttachment("p2.pdf", "p2.pdf", 1, photo = false)
        val failure = com.wangzan101.mika.cap.sandbox.AttachmentFailure(
            "bad.bin", com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.IO_ERROR,
        )
        assertTrue(vm.openSharedDraft(SharedDraft("", "新多图", listOf(p1, p2)), inCurrentConversation = true, rejected = listOf(failure), overLimit = 1))
        assertNotNull(vm.ui.value.pendingSharedDraft)
        assertTrue(vm.confirmPendingSharedDraft())
        assertEquals(listOf("p1.jpg", "p2.pdf"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf(failure), vm.ui.value.rejectedAttachments)
        assertEquals(1, vm.ui.value.overLimit)

        // A new share for the same conversation arrives while these two files are importing: neither importing copy is deleted early.
        assertTrue(vm.send("新多图", ok))
        assertTrue(vm.ui.value.importing)
        val n1 = SharedDraftAttachment("n1.jpg", "n1.jpg", 1, photo = true)
        assertTrue(vm.openSharedDraft(SharedDraft("", "覆盖", listOf(n1)), inCurrentConversation = true))
        assertTrue(drafts.attachmentFile("p1.jpg")!!.exists())
        assertTrue(drafts.attachmentFile("p2.pdf")!!.exists())

        releaseImport.complete(Unit)
        runCurrent()
        assertTrue(bothExisted)
        assertFalse(drafts.attachmentFile("p1.jpg")!!.exists())
        assertFalse(drafts.attachmentFile("p2.pdf")!!.exists())
        assertTrue(drafts.attachmentFile("n1.jpg")!!.exists())
    }

    @Test fun startWaitsUntilServiceFinishedPreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
    }

    @Test fun stopWhileQueuedBehindServiceCancelsTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        vm.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.toString(), calls.isEmpty()) // It never started, and it does not cancel the previous task that is wrapping up in the service
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
    }

    // While waiting for the service to free up, the message says clearly that it is waiting for the previous task instead of looking stuck; after being handed to the service it is replaced by the task's own progress
    @Test fun waitingMessageSaysItWaitsForThePreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        val waiting = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, waiting.status)
        assertEquals("等前一个任务做完再开始", ChatCopy.progressLine(waiting))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
        emit(TaskState.Running(1, "打开设置", reason = "打开设置"))
        assertEquals("打开设置", ChatCopy.progressLine(vm.ui.value.exchanges.single()))
    }

    // The chat page sees the service idle and hands the message over; before it arrives, somewhere else (a schedule, the computer) starts one first: the service queues this one behind it (nothing is lost),
    // and the chat shows it queued; when that task wraps up this one starts as usual and runs to completion
    private fun TestScope.raceVm(): ChatViewModel {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), MutableStateFlow(false),
            shownTask = shown, activeConversations = active).also { runCurrent() }
        assertTrue(vm.send("订会议室", ok))
        assertEquals(listOf("start:订会议室"), calls)
        val mine = vm.ui.value.conversationId!!
        // A task from elsewhere gets in first and is shown in the service: it is not attached to this conversation
        active.value = listOf("remote")
        shown.value = "remote" to TaskState.Running(3, "查快递")
        emit(TaskState.Running(3, "查快递"))
        assertEquals("订会议室", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // This one is queued behind it, and the service shows its queued state
        val queued = TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务"))
        active.value = listOf("remote", mine)
        shown.value = mine to queued
        emit(queued)
        return vm
    }

    @Test fun messageQueuedBehindATaskStartedElsewhereStartsAfterIt() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        assertEquals(ExchangeStatus.Queued("排队中:前面还有 1 个任务"), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // That task wraps up, this one is released to start and runs until it is done
        active.value = listOf(mine)
        shown.value = mine to TaskState.Running(1, "打开日历", reason = "打开日历")
        emit(TaskState.Running(1, "打开日历", reason = "打开日历"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        shown.value = mine to TaskState.Succeeded("订好了")
        emit(TaskState.Succeeded("订好了"))
        assertEquals("订好了", (vm.ui.value.exchanges.single().status as ExchangeStatus.Done).answer)
    }

    // Tapping stop while queued: only this conversation's message is stopped, and no stop without an id is sent (that would stop the task shown in the service, started elsewhere)
    @Test fun stopWhileQueuedInServiceCancelsOnlyThisMessage() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        vm.cancel()
        assertEquals("cancel@$mine", calls.last())
        assertFalse("cancel" in calls)
    }

    @Test fun historyLoadsWhenOpeningConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        store.append(id, Turn("订票", "验证码过不去", "failed"))
        store.append(id, Turn("查余额", "余额 10 元\n依据:钱包页", "unverified", note = "防截屏"))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges
        assertEquals(listOf("查天气", "订票", "查余额"), ex.map { it.goal })
        assertEquals(ExchangeStatus.Done("余额 10 元", "钱包页", verified = false, note = "防截屏"), ex[2].status)
        assertEquals(ExchangeStatus.Done("晴", "天气 App"), ex[0].status)
        assertEquals(ExchangeStatus.Failed("验证码过不去"), ex[1].status)

        vm.send("再查一次", ok)
        assertEquals("start:再查一次", calls.last())
        assertEquals(id, vm.ui.value.conversationId)
    }

    @Test fun confirmationCardShowsButtons() = runTest {
        val vm = vm()
        assertTrue(vm.send("给张三发明天见", ok))
        emit(TaskState.Running(3, "打字", "phone_type_text"))
        did(3, "act", "phone_type_text", "com.chat", "changed")
        val waiting = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        emit(waiting)
        val ex = vm.ui.value.exchanges.last()
        assertEquals(ExchangeStatus.NeedsConfirmation("要你同意:发送", "在 聊天\n对象:张三\n内容:明天见", ConfirmationNotifier.requestKey(waiting)), ex.status)
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals("等你同意", liveLine(ex))
        assertFalse(vm.ui.value.operatingPhone)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // Back to running after the answer
        emit(TaskState.Running(4, "", null))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    @Test fun approveCallsAction() = runTest {
        val vm = vm()
        // The card's buttons carry the request they belong to
        vm.approve(7)
        vm.decline(8)
        assertEquals(listOf("approve:7", "decline:8"), calls)
    }

    @Test fun endedTaskCarriesItsRunId() = runTest {
        val endedRunId = MutableStateFlow<String?>("r-before")
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r-old"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId)
        runCurrent()
        vm.openConversation(id)
        assertEquals("r-old", vm.ui.value.exchanges.single().runId)

        vm.send("查电量", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        assertNull(vm.ui.value.exchanges.last().runId) // No entry point is offered while it is still running
        // The service records this run's id first, then reports the end
        endedRunId.value = "r-new"
        emit(TaskState.Succeeded("85%"))
        assertEquals("r-new", vm.ui.value.exchanges.last().runId)

        // The next task's id arrives later: the finished one does not change with it
        vm.send("再查", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        endedRunId.value = "r-next"
        runCurrent()
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
        emit(TaskState.Failed("没找到", FailKind.GaveUp))
        assertEquals("r-next", vm.ui.value.exchanges[2].runId)
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
    }

    // Stop: it is stopped on the spot and the composer clears; while the wrap-up summary is still being written it is marked as organizing, and once written into the conversation it becomes this turn's answer
    @Test fun stoppedTurnShowsSummaryWhenItArrives() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.openConversation(id)
        vm.send("看四个设置页", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        vm.cancel()
        assertEquals("cancel", calls.last())
        // The service first registers "organizing" and records the id, then reports the end, and then records the cancelled turn
        summarizing.value = setOf("r1")
        endedRunId.value = "r1"
        emit(TaskState.Cancelled)
        store.append(id, Turn("看四个设置页", "已取消", "cancelled", runId = "r1"))
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        store.updateTurn(id, "r1") { it.copy(outcome = "停止前看完了网络、蓝牙;显示、声音还没看。") }
        runCurrent()
        summarizing.value = emptySet()
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), ex.status)
        assertEquals("r1", ex.runId)
        // Reopening the conversation: the wrap-up summary is read back from the stored copy
        vm.newConversation()
        vm.openConversation(id)
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), vm.ui.value.exchanges.single().status)
    }

    // The summary cannot be written (or there is nothing to summarize): the organizing mark is removed and only stopped remains
    @Test fun stoppedTurnWithoutSummaryDropsTheHint() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.send("看设置", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        summarizing.value = setOf("r2")
        endedRunId.value = "r2"
        emit(TaskState.Cancelled)
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        summarizing.value = emptySet()
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
    }

    @Test fun conversationSwitchesKeepOwnAttachmentsAndCapability() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("第一件事", "好了", "succeeded"))
            store.append("b", Turn("第二件事", "好了", "succeeded"))
            val vm = ChatViewModel(
                backgroundScope, state, stage, actions, store,
                sandboxReady = MutableStateFlow(true),
                parallelFlag = { parallel }, shownTask = shown, activeConversations = active,
            ).also { runCurrent() }
            vm.openConversation("a")
            vm.attach(listOf("content://draft/$parallel"))
            runCurrent()
            val pending = vm.ui.value.attachments
            assertEquals("parallel=$parallel", listOf("content://draft/$parallel"), pending.map { it.uri })

            vm.openConversation("b")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertTrue("parallel=$parallel", vm.ui.value.attachments.isEmpty())
            vm.openConversation("a")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertEquals("parallel=$parallel", pending, vm.ui.value.attachments)

            // A new conversation has its own empty composer; neither the device capability nor the pending attachments of the original conversation may be lost.
            vm.newConversation()
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertTrue("parallel=$parallel", vm.ui.value.attachments.isEmpty())
            vm.openConversation("a")
            assertTrue("parallel=$parallel", vm.ui.value.attachEnabled)
            assertEquals("parallel=$parallel", pending, vm.ui.value.attachments)
        }
    }

    @Test fun partialAttachmentFailureKeepsDraftUntilFailedFileIsRemoved() = runTest {
        val sandbox = MutableStateFlow(false)
        val imported = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = sandbox,
            importAttachments = { id, uris ->
                imported += id to uris
                ImportResult(
                    listOf("/workspace/inbox/报表 (1).csv" to 2048L),
                    if (uris.size > 1) listOf("big.iso") else emptyList(),
                    if (uris.size > 1) listOf(com.wangzan101.mika.cap.sandbox.AttachmentFailure("big.iso", com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.TOO_LARGE)) else emptyList(),
                )
            },
            describeAttachments = { uris -> uris.map { PendingAttachment(it, if (it.endsWith("1")) "报表.csv" else "big.iso", 10L) } },
        ).also { runCurrent() }
        // Sandbox not ready: there is no attach button and nothing is accepted
        assertFalse(vm.ui.value.attachEnabled)
        vm.attach(listOf("content://x/1"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())

        sandbox.value = true
        runCurrent()
        assertTrue(vm.ui.value.attachEnabled)
        // When picked they only sit above the composer and are not copied
        vm.attach(listOf("content://x/1", "content://x/2"))
        runCurrent()
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())

        // On partial failure it does not start; the text and the whole batch of attachments are kept so the user can choose how to continue.
        vm.input("汇总一下")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        val id = vm.ui.value.conversationId!!
        assertEquals(listOf(id to listOf("content://x/1", "content://x/2")), imported)
        assertTrue(calls.isEmpty())
        assertEquals("汇总一下", vm.ui.value.input)
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertEquals(listOf("big.iso"), vm.ui.value.rejectedAttachments.map { it.name })
        assertEquals(com.wangzan101.mika.cap.sandbox.AttachmentFailureReason.TOO_LARGE, vm.ui.value.rejectedAttachments.single().reason)
        assertFalse(vm.ui.value.importing)

        // After the user explicitly removes the failed item, the remaining attachments are sent.
        vm.removeAttachment("content://x/2")
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(id to listOf("content://x/1"), imported.last())
        assertEquals(listOf("start:汇总一下\n附件:/workspace/inbox/报表 (1).csv(2 KB)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertTrue(vm.ui.value.input.isEmpty())
        // Attachments are not accepted in explicit steer mode
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.attach(listOf("content://x/3"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun attachmentsAloneCanBeSent() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 3L * 1024 * 1024), emptyList()) },
        ).also { runCurrent() }
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(listOf("start:附件:/workspace/inbox/a.jpg(3.0 MB)"), calls)
    }

    @Test fun attachmentsAreImportedBeforeTheirQueuedTaskIsPersisted() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), followUps = followUpFlow,
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 1024), emptyList()) },
        ).also { runCurrent() }
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("看下一张", ok))
        runCurrent()

        assertEquals(listOf("queue:看下一张\n附件:/workspace/inbox/a.jpg(1 KB)"), calls.filter { it.startsWith("queue:") })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
        assertEquals(listOf("看下一张\n附件:/workspace/inbox/a.jpg(1 KB)"), vm.ui.value.queued.map { it.text })
    }

    @Test fun failedImportWithTextKeepsDraftAndCanBeRetried() = runTest {
        var fails = true
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ ->
                if (fails) throw java.io.IOException("read failed")
                ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), emptyList())
            },
        ).also { runCurrent() }
        vm.input("分析这个文件")
        vm.toggleAttachment(PendingAttachment("content://a", "a.pdf", 1L))
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals("分析这个文件", vm.ui.value.input)
        assertEquals(listOf("a.pdf"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf("a.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
        fails = false
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertEquals(listOf("start:分析这个文件\n附件:/workspace/inbox/a.pdf(1 B)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
    }

    @Test fun failedQueuedAttachmentImportDoesNotEnqueueAndKeepsDraft() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true), followUps = followUpFlow,
            importAttachments = { _, _ -> ImportResult(listOf("/workspace/inbox/a.pdf" to 1L), listOf("b.pdf")) },
        ).also { runCurrent() }
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.input("分析文件")
        vm.toggleAttachment(PendingAttachment("content://a", "a.pdf", 1L))
        vm.toggleAttachment(PendingAttachment("content://b", "b.pdf", 1L))
        assertTrue(vm.send(vm.ui.value.input, ok))
        runCurrent()
        assertTrue(calls.none { it.startsWith("queue:") })
        assertTrue(vm.ui.value.queued.isEmpty())
        assertEquals("分析文件", vm.ui.value.input)
        assertEquals(listOf("a.pdf", "b.pdf"), vm.ui.value.attachments.map { it.name })
        assertEquals(listOf("b.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
    }

    @Test fun nothingIsSentWhenEveryAttachmentFailsAndThereAreNoWords() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.wangzan101.mika.cap.sandbox.ImportResult(emptyList(), listOf("huge.mov")) },
        ).also { runCurrent() }
        vm.toggleAttachment(PendingAttachment("content://m/1", "huge.mov", 1L))
        vm.send("", ok)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(listOf("huge.mov"), vm.ui.value.rejectedAttachments.map { it.name })
    }

    @Test fun pickingPhotosStopsAtTheLimit() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            describeAttachments = { uris -> uris.map { PendingAttachment(it, "p.jpg", 1L) } },
        ).also { runCurrent() }
        repeat(AttachTray.LIMIT) { assertTrue(vm.toggleAttachment(PendingAttachment("content://m/$it", "p.jpg", 1L))) }
        assertFalse(vm.toggleAttachment(PendingAttachment("content://m/x", "p.jpg", 1L)))
        // Tapping an already selected one deselects it
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/0", "p.jpg", 1L)))
        assertEquals(AttachTray.LIMIT - 1, vm.ui.value.attachments.size)
        vm.attach(listOf("content://f/1", "content://f/2"))
        runCurrent()
        assertEquals(AttachTray.LIMIT, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
        vm.dismissRejected()
        assertEquals(0, vm.ui.value.overLimit)
    }

    @Test fun sharedFilesShowOnTheLiveExchangeAndAreRestored() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val store = ConversationStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedFiles = files).also { runCurrent() }
        vm.send("导出表格", ok)
        emit(TaskState.Running(1, "整理"))
        val f = com.wangzan101.mika.chat.SharedFile("表.xlsx", 5000, "Download/Claire/表.xlsx", "content://media/1")
        files.value = listOf(f)
        runCurrent()
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)
        emit(TaskState.Succeeded("好了\n依据: 已导出"))
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)

        val id = vm.ui.value.conversationId!!
        store.append(id, Turn("导出表格", "好了", "succeeded"), listOf(f))
        vm.openConversation(id)
        assertEquals(listOf(f), vm.ui.value.exchanges.single().files)
    }

    @Test fun sizeLabels() {
        assertEquals("12 B", ChatViewModel.sizeLabel(12))
        assertEquals("2 KB", ChatViewModel.sizeLabel(2048))
        assertEquals("1.5 MB", ChatViewModel.sizeLabel(1_572_864))
    }

    // ---- Parallel tasks (with the parallelTasks switch on) ----
    private val shown = MutableStateFlow<Pair<String, TaskState>?>(null)
    private val active = MutableStateFlow<List<String>>(emptyList())

    private fun TestScope.parallelVm(
        store: ConversationStore,
        files: MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>> = MutableStateFlow(emptyList()),
        sandbox: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ) = ChatViewModel(
        backgroundScope, state, stage, actions, store, serviceBusy = MutableStateFlow(true),
        parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = { if (it == "b") "查快递" else null },
        sharedFiles = files, sandboxReady = sandbox,
    ).also { runCurrent() }

    private fun TestScope.show(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        runCurrent()
    }

    /** Deleting the conversation shown on screen: it becomes a new empty conversation immediately, the sidebar no longer lists it, and the actual deletion is handed to the background. */
    @Test fun deletingTheOpenConversationShowsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { removed += it; store.delete(it) }).also { runCurrent() }
        vm.openConversation("a")
        assertEquals(1, vm.ui.value.exchanges.size)

        vm.deleteConversation("a")
        runCurrent()
        assertEquals(listOf("a"), removed)
        assertEquals(setOf("a"), vm.deleted.value)
        assertNull(vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        // Deleting another conversation: the one on screen stays put
        vm.openConversation("b")
        vm.deleteConversation("x")
        runCurrent()
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(listOf("订票"), vm.ui.value.exchanges.map { it.goal })
    }

    /** Batch deletion: several are deleted together and the progress advances one by one; one that cannot be deleted does not block the rest, and at the end it reports truthfully how many failed. */
    @Test fun deletingABatchReportsProgressAndFailures() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        listOf("a", "b", "c").forEach { store.append(it, Turn("问题 $it", "好", "succeeded")) }
        val blocker = java.io.File(dir, "b.json.tmp")
        assertTrue(blocker.mkdir())
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            removeConversation = { id ->
                store.delete(id)
                removed += id
            },
        ).also { runCurrent() }

        vm.deleteConversations(listOf("a", "b", "c"))
        // They are removed from the sidebar immediately after submission, without waiting for the background deletion to finish
        assertEquals(setOf("a", "b", "c"), vm.deleted.value)
        assertEquals(DeleteBatch(3, 0, 0), vm.deleteBatch.value)

        runCurrent()
        assertEquals(listOf("a", "c"), removed)
        assertEquals(setOf("a", "c"), vm.deleted.value)
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(DeleteBatch(3, 3, 1), vm.deleteBatch.value)
        assertTrue(vm.deleteBatch.value!!.finished)

        assertTrue(blocker.delete())
        vm.deleteConversations(listOf("b"))
        runCurrent()
        assertEquals(listOf("a", "c", "b"), removed)
        assertTrue(store.list().isEmpty())
        assertEquals(DeleteBatch(1, 1, 0), vm.deleteBatch.value)
        vm.clearDeleteBatch()
        assertNull(vm.deleteBatch.value)
    }

    /** Renaming and pinning in the sidebar: they are saved and the sidebar re-sorts by what is stored (pinned ones in the pinned section, most recently pinned first); a deleted conversation is no longer changed. */
    @Test fun renameAndPinReorderTheDrawer() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("写周报", "好了", "succeeded"))
        var now = 1_000L
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, clock = { now }, removeConversation = { store.delete(it) }).also { runCurrent() }
        fun sections() = drawerSections(store.list(), CurrentConversation(null, false, "", ""), emptyList(), vm::runningGoal, vm.canSwitch(), 0L)

        vm.renameConversation("b", " 回家的票 ")
        vm.setPinned("c", true)
        now = 2_000L
        vm.setPinned("b", true)
        val s = sections()
        assertEquals(listOf("回家的票", "写周报"), s.pinned.map { it.title })
        assertEquals(listOf("a"), s.recent.map { it.id })

        vm.setPinned("b", false)
        assertEquals(listOf("c"), sections().pinned.map { it.id })
        assertEquals(setOf("a", "b"), sections().recent.mapNotNull { it.id }.toSet())

        vm.deleteConversation("c")
        runCurrent()
        vm.renameConversation("c", "不该写进去")
        vm.setPinned("c", true)
        assertNull(store.title("c"))
        assertNull(store.pinnedAt("c"))
    }

    /** Someone writes into a deleted conversation again (the floating capsule continues in that id): the sidebar lists it again. */
    @Test fun aDeletedConversationThatIsWrittenAgainIsListedAgain() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { store.delete(it) }).also { runCurrent() }
        vm.deleteConversation("a")
        runCurrent()
        assertEquals(setOf("a"), vm.deleted.value)
        store.append("a", Turn("再来", "好", "succeeded"))
        runCurrent()
        assertTrue(vm.deleted.value.isEmpty())
    }

    /** Deleting the conversation shown on screen while its task is running during parallel work: it stops following that task, and its later states are not attached to the new conversation. */
    @Test fun deletingARunningOpenConversationStopsFollowingIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = parallelVm(store)
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        active.value = listOf("a")
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.deleteConversation("a")
        runCurrent()
        val fresh = vm.ui.value.conversationId
        assertTrue(fresh != null && fresh != "a")
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
        show("a", TaskState.Running(2, "看屏幕"))
        show("a", TaskState.Cancelled)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(fresh, vm.ui.value.conversationId)
    }

    @Test fun otherRunningExcludesCurrent() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        vm.switchTo("a")
        active.value = listOf("a", "b", "c")
        runCurrent()
        assertEquals(listOf("b", "c"), vm.otherRunning.value)
        vm.switchTo("b")
        runCurrent()
        assertEquals(listOf("a", "c"), vm.otherRunning.value)
    }

    @Test fun switchToLoadsThatConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        val vm = parallelVm(store)
        // Conversation a's task is running
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // Conversation b also has a running task: switching over first has the service show it, loads history and attaches its current state
        active.value = listOf("a", "b")
        show("b", TaskState.Running(2, "看屏幕"))
        vm.switchTo("b")
        assertEquals("b", vm.ui.value.conversationId)
        assertTrue("show:b" in calls)
        assertEquals(listOf("查天气", "查快递"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        // a's state does not enter b's conversation
        show("a", TaskState.Succeeded("done"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)

        // The screen shows b: stop goes only to b's task, without changing what is displayed first
        calls.clear()
        vm.cancel()
        assertEquals(listOf("cancel@b"), calls)

        // A new conversation with no task: another task can still be started while others are running, without waiting for the service to be idle
        vm.newConversation()
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    /** Switching conversations during parallel work rebuilds the whole ChatUi: attachment support is a device capability that does not vary by conversation, so the plus button must not disappear after switching once. */
    @Test fun switchToKeepsAttachmentsEnabled() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴", "succeeded"))
        val vm = parallelVm(store, sandbox = MutableStateFlow(true))
        assertTrue(vm.ui.value.attachEnabled)

        // Switching back and forth between idle conversations
        vm.switchTo("a")
        assertTrue(vm.ui.value.attachEnabled)
        vm.switchTo("b")
        assertTrue(vm.ui.value.attachEnabled)

        // Switching to another one while a conversation's task is running
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        vm.switchTo("b")
        assertTrue(vm.ui.value.attachEnabled)
    }

    private val learnCalls = mutableListOf<String>()
    private val savedNotes = mutableListOf<com.wangzan101.mika.memory.RunNotes>()
    private val learning = object : LearningActions {
        override suspend fun undo(token: String): Boolean { learnCalls += "undo:$token"; return token != "expired" }
        override suspend fun approve(pendingId: String): Boolean { learnCalls += "approve:$pendingId"; return true }
        override suspend fun forget(memoryId: String): Boolean { learnCalls += "forget:$memoryId"; return true }
        override fun viewSkill(draftId: String) { learnCalls += "view:$draftId" }
        override suspend fun restoreSkill(name: String) { learnCalls += "restore:$name" }
        override suspend fun deleteSkill(name: String) { learnCalls += "delete:$name" }
        override suspend fun save(notes: com.wangzan101.mika.memory.RunNotes) { savedNotes += notes }
    }
    private val notesFlow = MutableStateFlow<Map<String, com.wangzan101.mika.memory.RunNotes>>(emptyMap())
    private fun item(id: String, content: String) = com.wangzan101.mika.core.learn.memory.MemoryItem(
        id, com.wangzan101.mika.core.learn.memory.MemoryKind.preference, content, emptyList(),
        com.wangzan101.mika.core.learn.memory.MemorySource("manual", atMs = 0), com.wangzan101.mika.core.learn.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0,
    )
    private val items = MutableStateFlow(listOf(item("a", "用户点外卖不要香菜"), item("b", "用户喝咖啡不加糖"), item("p1", "点外卖先比价")))

    private fun TestScope.vmWithHistory(): ChatViewModel {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("点外卖", "已下单\n依据: 订单页", "succeeded", runId = "r1"))
        return ChatViewModel(backgroundScope, state, stage, actions, store, runNotes = notesFlow, learning = learning, memoryItems = items).also {
            runCurrent(); it.openConversation("c1"); runCurrent()
        }
    }

    private val remembered = com.wangzan101.mika.core.learn.memory.MemoryNote(com.wangzan101.mika.core.learn.memory.NoteKind.added, "a", "t1", null)
    private val procedure = com.wangzan101.mika.core.learn.memory.MemoryNote(com.wangzan101.mika.core.learn.memory.NoteKind.pending, "p1", "t2", "p1")

    @Test fun memoryNotesShownUnderResult() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered)))
        runCurrent()
        val note = vm.ui.value.exchanges.single().memoryNotes.single()
        assertEquals("Remembered: 用户点外卖不要香菜", vm.noteText(note))
        // The content is fetched live: once that item is deleted it writes the deleted-memory placeholder
        items.value = items.value.filter { it.id != "a" }
        assertEquals("Remembered: a deleted memory", vm.noteText(note))
        // Reopening the conversation reads it back from the stored turn (only the kind and the id are stored)
        val store = ConversationStore(tmp.newFolder())
        store.append("c2", Turn("点外卖", "ok", "succeeded", runId = "r2", memoryNotes = listOf(remembered), usedMemory = listOf("a")))
        val vm2 = ChatViewModel(backgroundScope, state, stage, actions, store).also { runCurrent(); it.openConversation("c2"); runCurrent() }
        assertEquals(listOf(remembered), vm2.ui.value.exchanges.single().memoryNotes)
    }

    @Test fun undoRemovesNote() = runTest {
        val vm = vmWithHistory()
        val expired = remembered.copy(undoToken = "expired")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(remembered, expired)))
        runCurrent()
        vm.undoNote("r1", remembered); runCurrent()
        assertEquals(listOf("undo:t1"), learnCalls)
        assertEquals(listOf(expired), vm.ui.value.exchanges.single().memoryNotes)
        // The token has expired: the note is not deleted, only the undo action is removed
        vm.undoNote("r1", expired); runCurrent()
        assertEquals(listOf(expired.copy(undoToken = null)), vm.ui.value.exchanges.single().memoryNotes)
        assertEquals(listOf(expired.copy(undoToken = null)), savedNotes.last().memoryNotes)
    }

    @Test fun pendingProcedureShowsYesNo() = runTest {
        val vm = vmWithHistory()
        val second = procedure.copy(undoToken = "expired", pendingId = "p2", memoryId = "p2")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", listOf(procedure, second)))
        runCurrent()
        assertEquals("Should I always do it this way from now on? 点外卖先比价", vm.noteText(procedure))
        vm.approveNote("r1", procedure); runCurrent()
        assertEquals(listOf("approve:p1"), learnCalls)
        // On "no" the token has already expired: that procedure is deleted outright
        vm.rejectNote("r1", vm.ui.value.exchanges.single().memoryNotes.single()); runCurrent()
        assertEquals(listOf("approve:p1", "undo:expired", "forget:p2"), learnCalls)
        assertTrue(vm.ui.value.exchanges.single().memoryNotes.isEmpty())
    }

    @Test fun usedMemoryCountShown() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", usedMemory = listOf("a", "b")))
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("用户点外卖不要香菜", "用户喝咖啡不加糖"), vm.usedMemoryTexts(ex.usedMemory))
        // After clearing everything it is no longer listed (the UI uses this to hide the "used n memories" line)
        items.value = emptyList()
        assertTrue(vm.usedMemoryTexts(ex.usedMemory).isEmpty())
    }

    @Test fun suspendedSkillRestoreAndView() = runTest {
        val vm = vmWithHistory()
        val learned = com.wangzan101.mika.core.learn.skills.SkillNote("Claire 学会了一项技能:发微信", draftId = "d1")
        val suspended = com.wangzan101.mika.core.learn.skills.SkillNote("这项技能最近不灵,先停用了", suspended = "wechat-msg")
        notesFlow.value = mapOf("r1" to com.wangzan101.mika.memory.RunNotes("c1", "r1", skillNotes = listOf(learned, suspended)))
        runCurrent()
        vm.viewSkill(learned)
        vm.restoreSkill("r1", suspended); runCurrent()
        assertEquals(listOf("view:d1", "restore:wechat-msg"), learnCalls)
        assertEquals(listOf(learned), vm.ui.value.exchanges.single().skillNotes)
    }

    // Pressing a button in a conversation with no task never lands on another conversation's task; the press carries the conversation that was on screen at that moment
    @Test fun buttonsInConversationWithoutRunGoNowhere() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        show("a", TaskState.WaitingInput("哪个?", null))
        vm.switchTo("b")
        calls.clear()
        vm.cancel(); vm.resume(); vm.answer("第一个"); vm.approve(7)
        assertTrue(calls.isEmpty())

        vm.switchTo("a")
        calls.clear()
        vm.resume()
        vm.switchTo("b")
        assertEquals(listOf("resume@a"), calls.filter { "@" in it })
    }

    // Takeover progress follows only the screen conversation's task; it is cleared when switching to a conversation with no task
    @Test fun takeoverStageFollowsScreenConversation() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        vm.switchTo("a")
        show("a", TaskState.WaitingTakeover("login", "请登录"))
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)
        vm.switchTo("b")
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        stage.value = Takeover.Stage.Waiting
        runCurrent()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        vm.newConversation()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
    }

    // Exported files, like success criteria, are per task: when the service is showing another conversation's task they are not attached to the card of the conversation on screen
    @Test fun sharedFilesFollowScreenConversation() = runTest {
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        val vm = parallelVm(ConversationStore(tmp.newFolder()), files)
        active.value = listOf("a", "b")
        vm.switchTo("a")
        show("a", TaskState.Running(1, "整理"))
        val mine = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Claire/a.csv", "content://media/1")
        files.value = listOf(mine)
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
        show("b", TaskState.Running(1, "查快递"))
        files.value = listOf(com.wangzan101.mika.chat.SharedFile("b.csv", 1, "Download/Claire/b.csv", "content://media/2"))
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
    }

    // Each conversation's card uses its own run id, no matter which task the service was showing at the time
    @Test fun endedCardUsesItsConversationsRunId() = runTest {
        val ended = MutableStateFlow<Map<String, String?>>(emptyMap())
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunId = MutableStateFlow("r-shown-other"), endedRunIds = ended,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        // When another conversation's task ends, it is recorded only under that conversation's name
        ended.value = mapOf("b" to "r-b")
        runCurrent()
        assertNull(vm.ui.value.exchanges.last().runId)
        ended.value = mapOf("b" to "r-b", "a" to "r-a")
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // The run id is attached while it runs (so the run process can be entered right away), taken by the conversation the card belongs to, not the one the service is showing
    @Test fun runningCardUsesItsOwnConversationsLiveRunId() = runTest {
        val live = mutableMapOf<String, String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunIds = MutableStateFlow(mapOf("a" to "r-a")), liveRunId = { live[it] },
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        live["b"] = "r-b"
        show("a", TaskState.Running(0, "正在准备"))
        assertNull("this conversation's task has no id yet: do not take another conversation's", vm.ui.value.exchanges.last().runId)
        live["a"] = "r-a"
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // The switch is turned on after the UI has been built: it is re-read when starting a task, without rebuilding the UI
    @Test fun parallelFlagReadWhenStarting() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        flag = true
        assertTrue(vm.send("设置版本号", ok))
        assertEquals("start:设置版本号", calls.last())
        val first = vm.ui.value.conversationId
        active.value = listOf(first!!)
        runCurrent()
        // A second task can be started right away in the new conversation
        vm.newConversation()
        assertTrue(vm.ui.value.conversationId != first)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    // The switch is re-read when switching conversations too
    @Test fun parallelFlagReadWhenSwitching() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        active.value = listOf("b")
        runCurrent()
        flag = true
        vm.openConversation("b")
        // Switching to a conversation with a running task during parallel work makes the service show it
        assertTrue("show:b" in calls)
    }

    // Whether there is a screen is also per task: when the one being shown belongs to another conversation it does not go into this card
    @Test fun otherConversationsScreenStaysOut() = runTest {
        val screen = MutableStateFlow(true)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        // Another conversation's task has a screen open: the new task has no screen yet, so no picture-in-picture
        assertTrue(vm.send("设置版本号", ok))
        assertFalse(vm.ui.value.pip.visible)
        show("b", TaskState.Running(1, "看屏幕"))
        runCurrent()
        show("a", TaskState.Running(1, "打开设置"))
        runCurrent()
    }

    // On a real device the UI thread dispatches immediately: as soon as the service changes what it shows, the state arrives on the spot. After switching away and back to a conversation that is still running,
    // the words of that exchange, its steps (including those from while you were away) and its files must all be there; a new empty exchange must not be started
    @Test fun switchingBackRestoresTheRunningExchange() = runTest {
        val runStates = mapOf("a" to MutableStateFlow<TaskState>(TaskState.Running(0, "")), "b" to MutableStateFlow<TaskState>(TaskState.Running(0, "")))
        val fileA = com.wangzan101.mika.chat.SharedFile("a.csv", 1, "Download/Claire/a.csv", "content://media/1")
        val runFiles = mapOf("a" to listOf(fileA), "b" to emptyList())
        val goals = mapOf("a" to "第一个任务", "b" to "第二个任务")
        val files = MutableStateFlow<List<com.wangzan101.mika.chat.SharedFile>>(emptyList())
        // Same as the service: when switching what is shown, copy the files first, then the state tagged with the conversation id
        val immediate = object : TaskActions by actions {
            override fun show(conversationId: String) {
                files.value = runFiles.getValue(conversationId)
                shown.value = conversationId to runStates.getValue(conversationId).value
            }
        }
        fun publish(c: String, s: TaskState) {
            runStates.getValue(c).value = s
            if (shown.value?.first == c) shown.value = c to s
        }
        val ui = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val vm = ChatViewModel(
            ui, state, stage, immediate, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = goals::get, runState = runStates::get,
            sharedFiles = files,
        )
        vm.switchTo("a")
        assertTrue(vm.send("第一个任务", ok))
        active.value = listOf("a")
        immediate.show("a")
        publish("a", TaskState.Running(1, "打开设置", "phone_launch_app"))

        // Switching to b, which has no task, to start a second task: a's exchange is set aside and a keeps running
        vm.switchTo("b")
        assertTrue(vm.send("第二个任务", ok))
        active.value = listOf("a", "b")
        immediate.show("b")
        publish("b", TaskState.Running(1, "看屏幕"))
        publish("a", TaskState.Running(2, "点版本号", "phone_tap", reason = "点版本号"))
        assertEquals("第二个任务", vm.ui.value.exchanges.last().goal)

        vm.switchTo("a")
        val ex = vm.ui.value.exchanges.last()
        assertEquals("第一个任务", ex.goal)
        assertEquals(listOf(fileA), ex.files)
        // a's progress keeps being recorded while you are away
        assertEquals("点版本号", ex.line)
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    @Test fun withdrawRemovesOnlyTheUnprocessedMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("撤回这句", ok)
        vm.send("留下这句", ok)
        val id = steerIds.first()
        assertTrue(vm.withdrawSteer(id))
        assertEquals(listOf(SteerState.Withdrawn, SteerState.Pending), vm.ui.value.exchanges.single().steers.map { it.state })
        assertFalse(vm.withdrawSteer(id))
        assertEquals(1, calls.count { it == "withdraw:$id" })
        assertEquals("留下这句", vm.ui.value.exchanges.single().steers.last().text)
    }

    @Test fun messagesWhileRunningQueueByDefault() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
        vm.send("下一件", ok)
        runCurrent()
        assertEquals(listOf("queue:下一件"), calls.filter { it.startsWith("queue:") })
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun guideQueuedMovesTheMessageIntoTheCurrentTask() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("下一件", ok)
        runCurrent()
        val item = vm.ui.value.queued.single()
        assertTrue(vm.guideQueued(item.id, ok))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        assertEquals(listOf("下一件"), vm.ui.value.exchanges.single().steers.map { it.text })
        assertTrue(calls.contains("cancelQueued:${item.id}"))
        assertTrue(calls.contains("steer:下一件"))
    }

    @Test fun guideQueuedDoesNotMoveAMessageAfterTheTaskEnds() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("下一件", ok)
        runCurrent()
        emit(TaskState.Succeeded("完成了"))
        assertFalse(vm.guideQueued(vm.ui.value.queued.single().id, ok))
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun queueModeEnqueuesInsteadOfSteering() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        assertEquals(listOf("queue:下一件"), calls.filter { it.startsWith("queue:") })
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun failedQueueSaveDoesNotReportDurableItem() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        queueSaveSucceeds = false
        assertFalse(vm.send("不能丢", ok))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    @Test fun newTaskKeepsQueueAsTheDefaultFollowUpMode() = runTest {
        val vm = vm()
        vm.send("新任务", ok)
        assertEquals(FollowUpMode.Queue, vm.ui.value.followUpMode)
    }

    @Test fun queuedTrayFollowsStoreAndHidesClaimedAndFallback() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        val item = vm.ui.value.queued.single()
        // Taken by the service: the queued row is hidden
        followUpFlow.value = listOf(item.copy(claimed = true))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        // fallback is not shown by the queued row (it goes to the "next item" row of the steer line)
        followUpFlow.value = listOf(item.copy(claimed = false, source = QueuedSource.Fallback))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
        // Queues from other conversations do not mix in
        followUpFlow.value = listOf(item.copy(conversationId = "other"))
        runCurrent()
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun cancelAndEditQueuedUseTheServiceAndRestoreText() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("取回这句", ok)
        runCurrent()
        val id = vm.ui.value.queued.single().id
        assertEquals("取回这句", vm.editQueued(id))
        assertTrue(vm.ui.value.queued.isEmpty())
        assertTrue(calls.contains("cancelQueued:$id"))

        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("取消这句", ok)
        runCurrent()
        val cancelId = vm.ui.value.queued.single().id
        assertTrue(vm.cancelQueued(cancelId))
        assertTrue(vm.ui.value.queued.isEmpty())
    }

    @Test fun stopKeepsExplicitQueue() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Queue)
        vm.send("下一件", ok)
        runCurrent()
        vm.cancel()
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
    }


    @Test fun editingRestoresQuoteAndTextWithoutOverwritingAnotherQuote() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        val quote = QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨")
        vm.quote(quote.from, quote.text)
        vm.send("改这句话", ok)
        val id = steerIds.single()
        vm.quote(QuoteMessage.From.You, "正在写的引用")
        assertNull(vm.editSteer(id))
        vm.clearQuote()
        assertEquals("改这句话", vm.editSteer(id))
        assertEquals(quote, vm.ui.value.quote)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
        vm.send("改好的话", ok)
        assertEquals("steer:${QuoteMessage.compose(quote, "改好的话")}", calls.last())
    }

    @Test fun failedWithdrawalDoesNotPretendTheModelMissedTheMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("模型刚取走", ok)
        val id = steerIds.single()
        canWithdraw = false
        assertNull(vm.editSteer(id))
        assertEquals(SteerState.Pending, vm.ui.value.exchanges.single().steers.single().state)
        assertNull(vm.ui.value.quote)
        steerFlow.value = listOf(SteerNote(id, "模型刚取走", SteerState.Joined))
        runCurrent()
        assertFalse(vm.withdrawSteer(id))
        assertEquals(SteerState.Joined, vm.ui.value.exchanges.single().steers.single().state)
    }

    // Words appended from the computer are also shown in the conversation, but a long press offers no withdraw or edit, and calling directly cannot withdraw them either; the service is not asked
    @Test fun remoteSteersOfferNoWithdrawOrEdit() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("手机插话", ok)
        val local = steerIds.single()
        steerFlow.value = listOf(SteerNote(local, "手机插话", SteerState.Received), SteerNote("r", "电脑追加", SteerState.Received, remote = true))
        runCurrent()
        val exchanges = vm.ui.value.exchanges
        assertEquals(listOf(false, true), exchanges.single().steers.map { it.remote })
        assertNull(withdrawableSteer(exchanges, "m0-r"))
        assertEquals(local, withdrawableSteer(exchanges, "m0-$local")?.id)
        assertFalse(vm.withdrawSteer("r"))
        assertNull(vm.editSteer("r"))
        assertFalse(calls.any { it == "withdraw:r" })
        assertEquals(SteerState.Received, vm.ui.value.exchanges.single().steers.last().state)
    }

    // Words inserted with the floating capsule carrying a screen, or with a browser page carrying the page description, are also shown in the conversation: taking them back for editing would put the raw marker into the composer, so only withdraw is offered, not edit
    @Test fun steersWithAScreenOrAPageCanOnlyBeWithdrawn() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val screen = UserScreenMessage.compose("s1", "这个按钮是干什么的")
        val page = AttachedPage.compose(AttachedPage.Page("https://example.com/a", "标题", "选中的一段"), "这段什么意思")
        steerFlow.value = listOf(SteerNote("s", screen, SteerState.Received), SteerNote("p", page, SteerState.Received))
        runCurrent()
        assertEquals("s", withdrawableSteer(vm.ui.value.exchanges, "m0-s")?.id)
        assertFalse(editableSteer(screen))
        assertFalse(editableSteer(page))
        assertNull(vm.editSteer("s"))
        assertNull(vm.editSteer("p"))
        assertFalse(calls.any { it.startsWith("withdraw:") })
        assertTrue(vm.withdrawSteer("p"))
        assertEquals(listOf(SteerState.Received, SteerState.Withdrawn), vm.ui.value.exchanges.single().steers.map { it.state })
        // Ones with only words and a quote are still editable as before
        assertTrue(editableSteer("改这句话"))
        assertTrue(editableSteer(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有雨"), "改这句话")))
    }

    @Test fun withdrawingBeforeTheTaskStartsPreventsLaterDelivery() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = busy)
        runCurrent()
        vm.send("当前任务", ok)
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("别发这句", ok)
        val id = vm.ui.value.exchanges.single().steers.single().id
        assertTrue(vm.withdrawSteer(id))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:当前任务"), calls)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun queueWhileWaitingForBusyServicePersistsWithoutStartingEarly() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            serviceBusy = busy, followUps = followUpFlow)
        runCurrent()
        vm.send("当前任务", ok)
        vm.setFollowUpMode(FollowUpMode.Queue)
        assertTrue(vm.send("下一件", ok))
        runCurrent()
        // The original message has not been handed to the service yet: the next item is already persisted, but it is explicitly not allowed to jump the gun; starting the original also does not rewrite it again.
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
        assertEquals(listOf(false), queueStartIfIdle)
        busy.value = false
        runCurrent()
        assertEquals(1, calls.count { it == "start:当前任务" })
        assertEquals(1, calls.count { it == "queue:下一件" })
        assertEquals(listOf("下一件"), vm.ui.value.queued.map { it.text })
    }

    // The composer can still send while a task is running: what is sent hangs under this exchange and is handed to the current task; the service reports back where it went
    @Test fun messageWhileRunningSteersTheTask() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        assertTrue(vm.send("改成后天", ok))
        assertEquals("steer:改成后天", calls.last())
        var ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("改成后天"), ex.steers.map { it.text })
        assertNull(steerLabel(ex.steers.single().state))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // It reached the model before the next step
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Joined))
        runCurrent()
        assertEquals("已加入当前任务", steerLabel(vm.ui.value.exchanges.single().steers.single().state))

        // Another message is sent and the task wraps up: this one never reached the task and becomes the next item
        assertTrue(vm.send("顺便订午饭", ok))
        emit(TaskState.Succeeded("订好了"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(listOf(SteerState.Joined, SteerState.Next), ex.steers.map { it.state })
        assertEquals("当前任务已结束，已排到下一件", steerLabel(ex.steers.last().state))
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // The steer interrupted the reply being written: that text is reported back together with where the steer went and hangs on the steer; it is not lost even if the state changes afterwards
    @Test fun interruptedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高"))
        assertTrue(vm.send("改成查明天", ok))

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))
        emit(TaskState.Running(2, ""))
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals("今天晴,最高", note.interrupted)
        assertEquals(SteerState.Joined, note.state)

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高", vm.ui.value.exchanges.single().steers.single().interrupted)
    }

    // As soon as the task's inbox accepts it there is an echo saying it was received and the current task will be adjusted in the next step; if the task wraps up right then, it becomes the next item just like one not yet delivered
    @Test fun receivedSteerIsShownAtOnceAndBecomesNextIfTheRunEnds() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals(SteerState.Received, note.state)
        assertEquals("已收到，下一步调整当前任务", steerLabel(note.state))
        emit(TaskState.Succeeded("订好了"))
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun stopCancelsReceivedSteersToo() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        vm.cancel()
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
    }

    // The service reports where it went only after wrap-up: it is still applied to that exchange
    @Test fun lateSteerOutcomeStillLandsOnItsExchange() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("只看顺丰的", ok)
        emit(TaskState.Failed("网络断了", FailKind.Environment))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "只看顺丰的", SteerState.Next))
        runCurrent()
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    // An action that did not succeed only says it did not succeed; the error code is not shown to the user
    @Test fun failedActionHidesErrorCode() = runTest {
        val vm = vm()
        vm.send("发消息", ok)
        emit(TaskState.Running(1, "", "phone_tap"))
        did(1, "act", "phone_tap", "com.chat", "ref_expired", failed = true)
        val row = vm.ui.value.exchanges.single().steps.single()
        assertEquals("点击 · 聊天", row.title)
        assertEquals("没成功", row.subtitle)
        assertFalse(row.toString().contains("ref_expired"))
    }

    // It is asked to remember, but per the rules it declines (not something you said yourself, sensitive, no explicit request to change): this is not a failure, so it does not say "failed" but says it declined and why; tools use their UI names
    @Test fun declinedMemoryWriteSaysWhyInsteadOfFailed() = runTest {
        val vm = vm()
        vm.send("总结几条写进记忆", ok)
        emit(TaskState.Running(1, "", "memory_save"))
        did(1, "observe", "memory_save", null, "content_not_from_user", failed = true)
        did(2, "observe", "memory_save", null, "sensitive", failed = true)
        did(3, "observe", "memory_edit", null, "edit_not_requested", failed = true)
        did(4, "observe", "memory_edit", null, "not_found", failed = true)
        val rows = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("写入记忆", "写入记忆", "修改记忆", "修改记忆"), rows.map { it.title })
        assertEquals(listOf("不是你亲口说的,没有写入", "含敏感信息,没有写入", "你没明确要改或删,没有改动", "没成功"), rows.map { it.subtitle })
        assertFalse(rows.toString().contains("content_not_from_user") || rows.toString().contains("memory_save"))
        // Opening a step and the run process page use the same wording
        assertEquals("不是你亲口说的,没有写入", ChatCopy.effectLabel("quote_not_from_user"))
        assertEquals("没成功", ChatCopy.effectLabel("memory_full"))
        // "Let Claire remember by drawing its own conclusions" is on, but this message did not ask it to remember
        assertEquals("这句话没让它记,没有写入", ChatCopy.effectLabel("inference_not_requested"))
    }

    // A steer is inserted while the original message's task is still waiting for the service to wrap up the previous one: start the original message's task first, then hand the steer to it
    @Test fun steerDuringPendingStartIsSentAfterTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy, steers = steerFlow)
        runCurrent()
        assertTrue(vm.send("订会议室", ok))
        vm.setFollowUpMode(FollowUpMode.Steer)
        assertTrue(vm.send("要大一点的", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.single().steers.map { it.text })
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:订会议室", "steer:要大一点的"), calls)
    }

    // Steers not yet handed to the task when stopping are voided together and marked cancelled; stopping while still waiting for the service means the steer is not sent either
    @Test fun stopCancelsPendingSteers() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.send("只看顺丰的", ok)
        vm.cancel()
        assertEquals("cancel", calls.last())
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
        assertEquals("已取消", steerLabel(SteerState.Cancelled))
        emit(TaskState.Cancelled)
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)

        val busy = MutableStateFlow(true)
        calls.clear()
        val vm2 = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        vm2.send("订会议室", ok)
        vm2.setFollowUpMode(FollowUpMode.Steer)
        vm2.send("要大一点的", ok)
        vm2.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(SteerState.Cancelled, vm2.ui.value.exchanges.single().steers.single().state)
    }

    // A steer may become the next item: it goes through the same check as starting a new task
    @Test fun steerIsCheckedLikeANewTask() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertFalse(vm.send("只看顺丰的", Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    // The quote is sent with the next message: both new tasks and steers carry it, and it is cleared after sending; when there is only a quote and no words nothing is sent
    @Test fun quoteGoesWithTheNextMessage() = runTest {
        val vm = vm()
        vm.quote(QuoteMessage.From.Mika, "  明天有小雨 ")
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "明天有小雨"), vm.ui.value.quote)
        assertFalse(vm.send("  ", ok))
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("那要带伞吗", ok))
        val goal = "> [Quoting your earlier reply]\n> 明天有小雨\n\n那要带伞吗"
        assertEquals(listOf("start:$goal"), calls)
        assertEquals(goal, vm.ui.value.exchanges.single().goal)
        assertNull(vm.ui.value.quote)

        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.setFollowUpMode(FollowUpMode.Steer)
        vm.quote(QuoteMessage.From.You, "查天气")
        assertTrue(vm.send("改成后天", ok))
        val steer = "> [Quoting my earlier message]\n> 查天气\n\n改成后天"
        assertEquals("steer:$steer", calls.last())
        assertEquals(steer, vm.ui.value.exchanges.single().steers.single().text)
        assertNull(vm.ui.value.quote)

        // Quote removed: only the words are sent as usual
        vm.quote(QuoteMessage.From.Mika, "x")
        vm.clearQuote()
        vm.send("再快一点", ok)
        assertEquals("steer:再快一点", calls.last())
    }

    // The quote belongs only to the conversation on screen: it is cleared when switching or starting a new conversation
    @Test fun quoteIsDroppedWhenTheConversationChanges() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.openConversation(id)
        assertNull(vm.ui.value.quote)
        vm.quote(QuoteMessage.From.Mika, "晴")
        vm.newConversation()
        assertNull(vm.ui.value.quote)
    }

    // A stored message with a quote comes back as it was on reopen (the UI draws the quoted one from it); old messages have no quote
    @Test fun quotedTurnsSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val quoted = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), "那后天呢")
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.append(id, Turn(quoted, "多云", "succeeded", steers = listOf(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "查天气"), "顺便看风"))))
        val vm = vm(store)
        vm.openConversation(id)
        val (old, new) = vm.ui.value.exchanges
        assertNull(QuoteMessage.parse(old.goal).quote)
        assertEquals("查天气", QuoteMessage.parse(old.goal).words)
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mika, "晴"), QuoteMessage.parse(new.goal).quote)
        assertEquals("那后天呢", QuoteMessage.parse(new.goal).words)
        assertEquals("顺便看风", QuoteMessage.parse(new.steers.single().text).words)
        assertEquals("查天气", ChatCopy.title(vm.ui.value.exchanges))
    }

    // Steers handed to the task are stored with that turn: after reopening the conversation or a restart they still show under that turn
    @Test fun joinedSteersSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("订会议室", "订好了", "succeeded", steers = listOf("要大一点的")))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("要大一点的"), ex.steers.map { it.text })
        assertEquals("已加入当前任务", steerLabel(ex.steers.single().state))
    }

    // The reply a steer interrupted is stored with that turn too and still hangs on that steer on reopen; the one that was not interrupted has none
    @Test fun interruptedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.interrupted })
    }

    // A reply already finished before a steer: it is reported back together with where the steer went and is not lost even if the state changes afterwards; once stored, it still hangs on that steer on reopen
    @Test fun finishedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高 25 度。"))
        assertTrue(vm.send("改成查明天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, finished = "今天晴,最高 25 度。"))
        emit(TaskState.Running(2, ""))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高 25 度。", vm.ui.value.exchanges.single().steers.single().finished)
    }

    @Test fun finishedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), finishedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.finished })
    }

    // ---- Tasks started elsewhere (floating capsule, scheduled tasks) and their stored turns ----

    /** With the switch off, the service state carries the conversation id ([shown]). */
    private fun TestScope.taggedVm(store: ConversationStore, endedRunId: MutableStateFlow<String?> = MutableStateFlow(null)) = ChatViewModel(
        backgroundScope, state, stage, actions, store, endedRunId = endedRunId,
        shownTask = shown, activeConversations = active, runGoal = { if (it == "a") "胶囊里说的" else null }, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** The task the service shows changed state: the tagged copy is written first, as in the service. */
    private fun TestScope.serve(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        state.value = s
        runCurrent()
    }

    // A turn that ran elsewhere is saved into the conversation on screen: it is filled in on the spot, exactly once
    @Test fun turnSavedElsewhereAppearsOnce() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        store.append("a", Turn("订会议室", "订好了", "succeeded", runId = "r2", steers = listOf("要大一点的")))
        runCurrent()
        assertEquals(listOf("查天气", "订会议室"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.last().steers.map { it.text })
        // Later that turn is modified (a memory hint is added): it is re-read without producing an extra one
        store.updateTurn("a", "r2") { it.copy(outcome = "订好了 3 楼") }
        runCurrent()
        assertEquals(2, vm.ui.value.exchanges.size)
        assertEquals("订好了 3 楼", (vm.ui.value.exchanges.last().status as ExchangeStatus.Done).answer)
    }

    // Turns saved into another conversation do not touch the conversation on screen
    @Test fun turnSavedInAnotherConversationLeavesScreenAlone() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        val before = vm.ui.value
        store.append("b", Turn("订会议室", "订好了", "succeeded", runId = "r2"))
        runCurrent()
        assertEquals(before, vm.ui.value)
    }

    // The task followed here ends and that turn is saved into the conversation: whether the save or the end comes first, there is only one, and the steps from here are kept
    @Test fun ownRunSavedIsNotDuplicated() = runTest {
        val ended = MutableStateFlow<String?>(null)
        for (savedFirst in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            val vm = taggedVm(store, ended)
            assertTrue(vm.send("查快递", ok))
            val id = vm.ui.value.conversationId!!
            serve(id, TaskState.Running(1, "", "phone_launch_app"))
            did(1, "act", "phone_launch_app", "com.chat")
            val turn = Turn("查快递", "到了", "succeeded", runId = "r-$savedFirst")
            ended.value = "r-$savedFirst"
            if (savedFirst) { store.append(id, turn); runCurrent() }
            serve(id, TaskState.Succeeded("到了"))
            if (!savedFirst) { store.append(id, turn); runCurrent() }
            val ex = vm.ui.value.exchanges.single()
            assertEquals("r-$savedFirst", ex.runId)
            assertTrue(ex.live)
            assertEquals(1, ex.steps.size)
            assertEquals(ComposerMode.Idle, vm.ui.value.composer)
            acts.value = emptyList()
        }
    }

    // A task started elsewhere in the conversation on screen: it keeps being followed, the words come from the task itself, and steers inserted elsewhere are attached too; after it ends and is saved there is only one
    @Test fun runStartedElsewhereInThisConversationIsFollowed() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val ended = MutableStateFlow<String?>(null)
        val vm = taggedVm(store, ended)
        vm.openConversation("a")
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        steerFlow.value = listOf(SteerNote("m1", "只看顺丰的", SteerState.Joined))
        runCurrent()
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
        ended.value = "r2"
        serve("a", TaskState.Succeeded("好了"))
        store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2", steers = listOf("只看顺丰的")))
        runCurrent()
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
    }

    // Opening (or returning to) a conversation that has a running task this side is not following yet: it is attached on the spot, without waiting for the next state change
    @Test fun openingOrRefreshingAttachesActiveRun() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = taggedVm(store)
        active.value = listOf("a")
        shown.value = "a" to TaskState.Running(2, "看屏幕")
        vm.openConversation("a")
        assertTrue("show:a" in calls)
        assertEquals("胶囊里说的", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)

        // Attached again on returning to the foreground: it is already being followed, so no extra exchange is created
        vm.refresh()
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // With the switch off, another conversation's task is not attached to the conversation on screen; when the task being followed here is swapped out of the display, the service is asked to show it again
    @Test fun serialRunInAnotherConversationIsNotAttached() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        vm.openConversation("a")
        active.value = listOf("b")
        serve("b", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        serve("b", TaskState.Succeeded("好了"))
        assertEquals(1, vm.ui.value.exchanges.size)

        assertTrue(vm.send("查快递", ok))
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "", "phone_launch_app"))
        calls.clear()
        // A scheduled task is queued in conversation b and the service is showing it
        active.value = listOf("a", "b")
        serve("b", TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务")))
        assertEquals(listOf("show:a"), calls)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    // A task attached when there is no conversation yet: it is shown together with the turns stored for that conversation, and turns saved later still line up
    @Test fun adoptingARunLoadsItsConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // ---- Tasks started elsewhere, in the service's real order: show first (state emitted), then register in the task list ----

    private val goals = mutableMapOf<String, String>()
    private val endedRun = MutableStateFlow<String?>(null)

    private fun TestScope.registryVm(store: ConversationStore, parallel: Boolean) = ChatViewModel(
        backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it }, endedRunId = endedRun, parallelFlag = { parallel },
        shownTask = shown, activeConversations = active, runGoal = goals::get, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** The service starts a task: it is shown first (tagged state, state), and only then registered in the task list (conversation, words). */
    private fun TestScope.startedElsewhere(conversationId: String, goal: String) {
        val s = TaskState.Running(0, "正在准备")
        shown.value = conversationId to s
        state.value = s
        runCurrent()
        goals[conversationId] = goal
        active.value = active.value + conversationId
        runCurrent()
    }

    // Words sent elsewhere (floating capsule) in the conversation on screen: while running, that message and its steps are shown, just like sending here
    @Test fun runStartedElsewhereShowsMessageWhileRunning() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("a", "胶囊里说的")
            assertEquals("parallel=$parallel", listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ComposerMode.Busy, vm.ui.value.composer)
            shown.value = "a" to TaskState.Running(1, "打开设置", "phone_launch_app")
            state.value = shown.value!!.second
            runCurrent()
            did(1, "act", "phone_launch_app", "com.chat")
            assertEquals(listOf("打开 App · 聊天"), vm.ui.value.exchanges.last().steps.map { it.text })
            // Attached again on returning to the foreground: no extra exchange is created
            vm.refresh()
            assertEquals(2, vm.ui.value.exchanges.size)
            assertEquals("胶囊里说的", vm.ui.value.exchanges.last().goal)
            endedRun.value = "r2-$parallel"
            serve("a", TaskState.Succeeded("好了"))
            store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2-$parallel"))
            runCurrent()
            assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            active.value = emptyList(); acts.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // A task started elsewhere is attached when there is no conversation on screen yet: that message is there too
    @Test fun adoptedRunShowsItsMessage() = runTest {
        val vm = registryVm(ConversationStore(tmp.newFolder()), parallel = false)
        startedElsewhere("a", "胶囊里说的")
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // A task started elsewhere in a new conversation: the sidebar lists it under running; opening it (returning to Claire) shows that message and the running state
    @Test fun runInNewConversationElsewhereOpensWithMessage() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("n", "新对话里说的")
            assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(listOf("n"), vm.otherRunning.value)
            val sections = drawerSections(store.list(), CurrentConversation("a", false, "查天气", ""), vm.otherRunning.value, vm::runningGoal, vm.canSwitch(), 0L)
            assertEquals(listOf("新对话里说的"), sections.running.map { it.title })
            vm.openConversation("n")
            assertEquals("n", vm.ui.value.conversationId)
            assertEquals(listOf("新对话里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
            serve("n", TaskState.Succeeded("好了"))
            active.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // The answer being written sits after the steers already handed to the model and before the steers not yet handed to it
    @Test fun draftSitsBetweenJoinedAndWaitingSteers() {
        val joined = SteerNote("a", "改成明天", SteerState.Joined)
        val received = SteerNote("b", "简短点", SteerState.Received)
        val pending = SteerNote("c", "再简单点", SteerState.Pending)
        assertEquals(listOf(joined) to listOf(received, pending), steersAroundDraft(listOf(joined, received, pending)))
        assertEquals(listOf(joined) to emptyList<SteerNote>(), steersAroundDraft(listOf(joined)))
        assertEquals(emptyList<SteerNote>() to listOf(received), steersAroundDraft(listOf(received)))
    }
    /** Still working while waiting for another task to release the app: the progress line says whom it is waiting for, which is neither paused nor needing you. */
    @Test fun waitingForAnotherTasksAppIsStillWorking() = runTest {
        val vm = vm()
        vm.send("给张三发消息", ok)
        emit(TaskState.Running(2, "打开聊天", tool = "phone_launch_app", progress = com.wangzan101.mika.core.agent.RunProgress.WaitingForApp("聊天")))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals("等另一个任务用完聊天", ex.line)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
    }

    /** As soon as the screen opens the small window floats up; once the user closes it only a small button is left, and the task keeps running as usual. */
    @Test fun smallWindowCanBeClosedForThisTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertFalse("does not float up before the screen opens", vm.ui.value.pip.visible)
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)

        vm.closePip()
        assertTrue("still a small button after closing", vm.ui.value.pip.visible)
        assertTrue(vm.ui.value.pip.closed)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // Later progress does not make it float up again by itself
        emit(TaskState.Running(1, "点了设置"))
        assertTrue(vm.ui.value.pip.closed)

        vm.reopenPip()
        assertFalse("tapping the small button opens it again", vm.ui.value.pip.closed)
    }

    @Test fun pipSoundButtonAlwaysDispatchesRealActionAndDoesNotInventState() = runTest {
        val vm = vm()
        vm.send("打开视频", ok)
        val id = vm.ui.value.conversationId!!
        calls.clear()
        val before = vm.ui.value.pip
        // The direction is determined by the real audio record in the task (resume / mute again); the UI only ever sends the real action and does not keep the toggle state itself
        vm.togglePipSound()
        vm.togglePipSound()
        assertEquals(listOf("toggle-sound@$id", "toggle-sound@$id"), calls)
        assertEquals(before, vm.ui.value.pip)
    }

    /** While the small window is closed it needs your takeover: it does not pop open by itself, but the small button takes on the needing-you look and what you need to do still appears in the conversation card. */
    @Test fun closedSmallWindowStillSignalsWhenItNeedsYou() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("付款", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.WaitingTakeover("payment", "要你付款"))
        val pip = vm.ui.value.pip
        assertTrue(pip.visible)
        assertTrue(pip.closed)
        assertFalse("takeover does not expand it while closed", pip.expanded)
        assertEquals(PipMode.NeedsYou, pip.mode)
        assertTrue(vm.ui.value.exchanges.single().status is ExchangeStatus.NeedsTakeover)
    }

    /** Closing applies only to that one task: the next task floats the small window up as usual. */
    @Test fun closingTheSmallWindowOnlyLastsForThatTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.Succeeded("电量 80%"))
        open.value = false
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()

        vm.send("再看看存储", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)
    }

    // ---------- Which conversation the capsule continues ----------
    // The capsule does not look at who last wrote the conversation file but at which conversation the UI is in right now: these three changes each tell it once.

    @Test fun newChatTellsTheCapsuleItIsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        assertEquals(null, store.currentChat())
        vm.newConversation()
        assertEquals(CurrentChat.Fresh, store.currentChat())
    }

    @Test fun openingAConversationTellsTheCapsuleWhichOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("c-old", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("c-old")
        assertEquals(CurrentChat.Open("c-old"), store.currentChat())
    }

    /** The first message in a new conversation settles the id: the capsule then continues this conversation, not the previous one. */
    @Test fun theFirstMessageInANewChatTellsTheCapsuleItsId() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.newConversation()
        assertTrue(vm.send("查天气", ok))
        val id = vm.ui.value.conversationId
        assertTrue(id != null)
        assertEquals(CurrentChat.Open(id!!), store.currentChat())
        emit(TaskState.Succeeded("晴"))
        // It is still the same one after the end: the capsule does not jump back to the previous conversation
        assertEquals(CurrentChat.Open(id), store.currentChat())
    }

    /** Records only which two workspaces were copied, and returns the result it was given in advance. */
    private class FakeBranchWorkspace(var result: BranchCopyResult? = BranchCopyResult(copied = 1)) : BranchWorkspace {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long): BranchCopyResult? {
            calls += sourceId to targetId
            return result
        }
    }

    /** Branch from an answer: copy up to and including this turn, add a suffix to the title and switch to the new conversation; the source conversation is unchanged and the workspace inbox is copied over too. */
    @Test fun branchFromCopiesThroughTheTurnAndOpensTheBranch() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 123L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val target = vm.branchFrom(1)

        assertNotNull(target)
        assertTrue(target != source)
        assertEquals(target, vm.ui.value.conversationId)
        assertEquals(listOf("一", "二"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
        assertEquals("原对话 · 分支", store.title(target!!))
        assertEquals(BranchOrigin(source, 1, afterTurn = true, at = 123L), store.branchOrigin(target))
        assertEquals(listOf(source to target), workspace.calls)
    }

    /** When the source conversation has no title, the first message is used as the base and the suffix is added on top. */
    @Test fun branchTitleFallsBackToTheFirstWords() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("帮我整理发票", "好了", "succeeded"))
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" },
        ).also { runCurrent() }
        vm.openConversation(source)
        val target = vm.branchFrom(0)
        assertNotNull(target)
        assertEquals("帮我整理发票 · 分支", store.title(target!!))
    }

    /** Task running, composer busy, turn out of range, this turn not finished yet, no conversation: none of them branch. */
    @Test fun branchFromRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertTrue(vm.currentRunning())
        assertNull(vm.branchFrom(0))

        activeNow.value = emptyList()
        runCurrent()
        assertFalse(vm.currentRunning())
        assertNull(vm.branchFrom(1))
        assertNull(vm.branchFrom(-1))

        // The composer is busy after sending a new message, so it cannot branch
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchFrom(0))
    }

    /** Workspace limit exceeded and nothing copied: the turn is still branched as usual, and the UI can get the notice that not all attachments were copied. */
    @Test fun branchFromReportsAttachmentsThatDidNotCopy() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val workspace = FakeBranchWorkspace(BranchCopyResult(copied = 0, truncated = true))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)

        assertNotNull(vm.branchFrom(0))
        assertEquals(BranchCopyResult(copied = 0, truncated = true), vm.branchCopy.value)
        vm.clearBranchCopy()
        assertNull(vm.branchCopy.value)
    }

    /** The source conversation file is corrupted: returns null and does not switch. */
    @Test fun branchFromFailsWithoutSwitchingWhenTheSourceIsUnreadable() = runTest {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val vm = vm(store)
        vm.openConversation(source)
        java.io.File(dir, "$source.json").writeText("{not json")

        assertNull(vm.branchFrom(0))
        assertEquals(source, vm.ui.value.conversationId)
    }

    /** Ask again from a user message: copy what comes before this turn and return the text to prefill; the source conversation is unchanged and it switches to the new conversation. */
    @Test fun branchBeforeCopiesEarlierTurnsAndReturnsPrefill() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        store.setTitle(source, "原对话")
        val workspace = FakeBranchWorkspace()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, store,
            branchWorkspace = workspace, branchTitle = { it + " · 分支" }, clock = { 7L },
        ).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(1)

        assertNotNull(result)
        val branch = result!!.conversationId
        assertEquals("二", result.prefill)
        assertFalse(result.needsReattach)
        assertEquals(listOf("一"), store.turns(branch).map { it.userText })
        assertEquals("原对话 · 分支", store.title(branch))
        assertEquals(BranchOrigin(source, 1, afterTurn = false, at = 7L), store.branchOrigin(branch))
        assertEquals(branch, vm.ui.value.conversationId)
        assertEquals(listOf("一"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf(source to branch), workspace.calls)
    }

    @Test fun retryRestoresAttachmentsInOrderAndSendsEditedTextWithoutImportingThem() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val paths = listOf("/workspace/inbox/a.pdf", "/workspace/inbox/b.jpg", "/workspace/inbox/c.jpg")
        store.append(source, Turn(paths.joinToString("\n") { "附件:$it(2 B)" }, "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
            override suspend fun checkInboxAttachments(targetId: String, paths: List<String>) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
        }
        var imports = 0
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace,
            importAttachments = { _, _ -> imports++; ImportResult(emptyList(), emptyList()) }).also { runCurrent() }
        vm.openConversation(source)
        val result = vm.branchBefore(0)!!
        assertEquals("", vm.ui.value.input)
        assertEquals(paths, vm.ui.value.attachments.map { it.workspacePath })
        assertTrue(calls.none { it.startsWith("start:") })
        vm.removeAttachment(vm.ui.value.attachments[1].uri)
        vm.input("修改后的问题")
        assertTrue(vm.send("修改后的问题", ok))
        runCurrent()
        assertEquals(0, imports)
        assertEquals("start:修改后的问题\n附件:${paths[0]}(2 B)\n附件:${paths[2]}(2 B)", calls.last { it.startsWith("start:") })
        assertFalse(result.needsReattach)
    }

    @Test fun retryReportsMissingItemsAndKeepsDraftsSeparate() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val first = "/workspace/inbox/a.pdf"
        val missing = "/workspace/inbox/b.jpg"
        store.append(source, Turn("原文\n附件:$first(2 B)\n附件:$missing(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                listOf(RetryAttachmentResult(first, RetryAttachmentStatus.COPIED, 2), RetryAttachmentResult(missing, RetryAttachmentStatus.MISSING))
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        vm.input("原会话草稿")
        val result = vm.branchBefore(0)!!
        assertEquals(listOf(missing), result.failures.map { it.path })
        assertEquals(listOf(first), vm.ui.value.attachments.map { it.workspacePath })
        vm.openConversation(source)
        assertEquals("原会话草稿", vm.ui.value.input)
        assertTrue(vm.ui.value.attachments.isEmpty())
        vm.openConversation(result.conversationId)
        assertEquals("原文", vm.ui.value.input)
        assertEquals(listOf(first), vm.ui.value.attachments.map { it.workspacePath })
    }

    @Test fun retryReportsFailuresForAttachmentsInEarlierTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val previous = "/workspace/inbox/old.pdf"
        val current = "/workspace/inbox/new.jpg"
        store.append(source, Turn("以前\n附件:$previous(2 B)", "好", "succeeded"))
        store.append(source, Turn("现在\n附件:$current(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long): List<RetryAttachmentResult> {
                assertEquals(listOf(current, previous), paths)
                return listOf(RetryAttachmentResult(current, RetryAttachmentStatus.COPIED, 2), RetryAttachmentResult(previous, RetryAttachmentStatus.OVER_LIMIT))
            }
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        val result = vm.branchBefore(1)!!
        assertEquals(listOf(previous), result.failures.map { it.path })
        assertEquals(listOf(current), vm.ui.value.attachments.map { it.workspacePath })
    }

    @Test fun retryDoesNotSendWhenRestoredFileDisappears() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        val path = "/workspace/inbox/a.pdf"
        store.append(source, Turn("看看\n附件:$path(2 B)", "好", "succeeded"))
        val workspace = object : BranchWorkspace {
            override suspend fun copyInbox(sourceId: String, targetId: String, maxBytes: Long) = BranchCopyResult(0)
            override suspend fun copyInboxForRetry(sourceId: String, targetId: String, paths: List<String>, maxBytes: Long) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.COPIED, 2) }
            override suspend fun checkInboxAttachments(targetId: String, paths: List<String>) =
                paths.map { RetryAttachmentResult(it, RetryAttachmentStatus.MISSING) }
        }
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchWorkspace = workspace).also { runCurrent() }
        vm.openConversation(source)
        vm.branchBefore(0)!!
        assertTrue(vm.send("看看", ok))
        runCurrent()
        assertTrue(calls.none { it.startsWith("start:") })
        assertEquals("看看", vm.ui.value.input)
        assertEquals(listOf("a.pdf"), vm.ui.value.rejectedAttachments.map { it.name })
        assertEquals(1, vm.ui.value.attachments.size)
    }

    /** No turns before the first one: the new conversation is empty; when the original message had attachments it prompts to add them again. */
    @Test fun branchBeforeTheFirstTurnIsEmptyAndFlagsAttachments() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("看这个\n附件:/workspace/inbox/a.pdf(1 KB)", "好", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, branchTitle = { it + " · 分支" }).also { runCurrent() }
        vm.openConversation(source)

        val result = vm.branchBefore(0)

        assertNotNull(result)
        assertEquals("看这个", result!!.prefill)
        assertTrue(result.needsReattach)
        assertTrue(store.turns(result.conversationId).isEmpty())
        assertTrue(store.list().none { it.id == result.conversationId })
        assertEquals(result.conversationId, vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
    }

    /** The entries the menu shows agree with the check after tapping: while this turn is not finished both entries are hidden and neither action is performed; likewise while running or busy. */
    @Test fun branchMenuMatchesWhatTheActionAllows() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))
        vm.openConversation(source)

        assertEquals(0, vm.branchTurn("r0"))
        assertEquals(0, vm.retryTurn("u0"))
        assertNull(vm.branchTurn("u0"))
        assertNull(vm.retryTurn("r0"))

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchTurn("r0"))
        assertNull(vm.retryTurn("u0"))

        activeNow.value = emptyList()
        runCurrent()
        // A new message is sent: turn 1 is not finished yet and the composer is busy, so neither entry is shown and neither action is performed
        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchTurn("r1"))
        assertNull(vm.retryTurn("u1"))
        assertNull(vm.retryTurn("u0"))
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchFrom(1))
    }

    /** Task running, turn out of range, composer busy: none of them asks again. */
    @Test fun branchBeforeRefusesRunningBusyAndBadTurns() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val activeNow = MutableStateFlow<List<String>>(emptyList())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = activeNow).also { runCurrent() }
        vm.openConversation(source)

        activeNow.value = listOf(source)
        runCurrent()
        assertNull(vm.branchBefore(0))

        activeNow.value = emptyList()
        runCurrent()
        assertNull(vm.branchBefore(1))
        assertNull(vm.branchBefore(-1))

        assertTrue(vm.send("新一句", ok))
        assertNull(vm.branchBefore(0))
    }
    /** Archive the conversation on screen: switch to a new empty conversation and keep the original draft, which can still be edited after opening it again. */
    @Test fun archiveCurrentConversationStartsNewOneAndKeepsDraft() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("a")
        vm.input("还没发出去的补充")
        runCurrent()

        assertTrue(vm.archiveConversation("a"))

        assertTrue(vm.ui.value.conversationId != "a")
        assertNotNull(store.archivedAt("a"))
        assertTrue(store.list().none { it.id == "a" })
        vm.openConversation("a")
        assertEquals("还没发出去的补充", vm.ui.value.input)
    }

    /** Archiving another conversation leaves the current one alone and only tucks it away from the list. */
    @Test fun archiveOtherConversationKeepsCurrentAndHidesIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val vm = vm(store)
        vm.openConversation("a")

        assertTrue(vm.archiveConversation("b"))

        assertEquals("a", vm.ui.value.conversationId)
        assertNotNull(store.archivedAt("b"))
        assertEquals(listOf("a"), store.list().map { it.id })
    }

    /** A conversation with a running or queued task is not archived. */
    @Test fun archiveRunningConversationIsRefused() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val running = MutableStateFlow(listOf("a"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = running).also { runCurrent() }

        assertFalse(vm.archiveConversation("a"))
        assertNull(store.archivedAt("a"))
    }

    /** After unarchiving it returns to the list with its original pin still there. */
    @Test fun unarchiveConversationBringsItBack() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.setPinned("a", true)
        assertTrue(vm.archiveConversation("a"))

        vm.unarchiveConversation("a")

        assertNull(store.archivedAt("a"))
        assertEquals(listOf("a"), store.list().map { it.id })
        assertNotNull(store.pinnedAt("a"))
    }

    /** Multi-select restore in the archive view: the selected ones return to the list and the unselected ones stay tucked away. */
    @Test fun unarchiveConversationsRestoresSelectedOnes() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("记账", "记好了", "succeeded"))
        val vm = vm(store)
        listOf("a", "b", "c").forEach { assertTrue(store.setArchived(it, true)) }

        vm.unarchiveConversations(listOf("a", "c"))

        assertNull(store.archivedAt("a"))
        assertNull(store.archivedAt("c"))
        assertNotNull(store.archivedAt("b"))
        assertEquals(setOf("a", "c"), store.list().map { it.id }.toSet())
    }

    /** Multi-select archive from the normal sidebar: running ones and the one being viewed are skipped and the rest are tucked away; returns how many were actually archived. */
    @Test fun archiveConversationsSkipsRunningAndCurrentOnes() = runTest {
        val store = ConversationStore(tmp.newFolder())
        listOf("a", "b", "c", "d").forEach { store.append(it, Turn("第 $it 个", "好", "succeeded")) }
        val running = MutableStateFlow(listOf("c"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, activeConversations = running).also { runCurrent() }
        vm.openConversation("a")

        assertEquals(2, vm.archiveConversations(listOf("a", "b", "c", "d")))

        assertEquals("a", vm.ui.value.conversationId)
        assertNull(store.archivedAt("a"))
        assertNotNull(store.archivedAt("b"))
        assertNull(store.archivedAt("c"))
        assertNotNull(store.archivedAt("d"))
    }

    @Test fun batchArchiveSkipsUnsentDraftButArchivesSavedConversationWithDraft() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("saved", Turn("已有消息", "回答", "succeeded"))
        val vm = vm(store)
        vm.input("尚未发送")
        val unsent = vm.ui.value.conversationId!!
        vm.openConversation("saved")
        vm.input("已有对话的草稿")
        vm.newConversation()
        runCurrent()

        assertTrue(vm.withDrafts(store.list()).any { it.id == unsent })
        assertEquals(1, vm.archiveConversations(listOf(unsent, "saved")))
        assertNull(store.archivedAt(unsent))
        assertNotNull(store.archivedAt("saved"))
        vm.openConversation(unsent)
        assertEquals("尚未发送", vm.ui.value.input)
        vm.openConversation("saved")
        assertEquals("已有对话的草稿", vm.ui.value.input)
    }

    @Test fun personalTaskCanBeOpenedWhileOrdinaryConversationIsBusyWithoutStartingWork() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("personal-work", Turn("整理资料", "整理完成", "succeeded"))
        val vm = vm(store)
        vm.send("正在做原来的事", ok)
        emit(TaskState.Running(1, "处理中"))
        val originalId = vm.ui.value.conversationId
        vm.openConversation("personal-work")
        assertEquals(originalId, vm.ui.value.conversationId)
        vm.openPersonalTask("personal-work")
        assertEquals("personal-work", vm.ui.value.conversationId)
        assertEquals("整理资料", vm.ui.value.exchanges.first().goal)
        assertEquals(1, calls.count { it.startsWith("start:") })
    }

    @Test fun projectDraftPersistsOnSendAndDeletesWithProject() = runTest {
        val conversations = ConversationStore(tmp.newFolder("chats"))
        val projects = com.wangzan101.mika.chat.ProjectStore(tmp.newFolder("projects"))
        val resolver = com.wangzan101.mika.chat.WorkspaceResolver(conversations, projects)
        val p1 = projects.create("科研").getOrThrow()
        var deletedProjectId: String? = null
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, conversations,
            appLabel = { it }, activities = acts, steers = steerFlow,
            projectStore = projects,
            removeProject = { pid -> deletedProjectId = pid },
            workspaceResolver = resolver,
        ).also { runCurrent() }

        vm.newConversation(p1.id)
        assertEquals(p1.id, vm.ui.value.projectId)
        vm.input("先建个草稿")
        val draftId = vm.ui.value.conversationId!!
        assertEquals(com.wangzan101.mika.cap.sandbox.WorkspaceKey.Project(p1.id), resolver.resolve(draftId))
        assertTrue(vm.send("先建个草稿", ok))
        assertEquals(p1.id, conversations.projectId(draftId))

        vm.deleteProject(p1.id)
        runCurrent()
        assertEquals(p1.id, deletedProjectId)
        assertNull(vm.ui.value.conversationId)
        assertNull(vm.ui.value.projectId)
    }
}
