package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.settings.ModelChoice
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.run.record.RunMetricsStore
import com.wangzan101.mika.core.model.usage.ConversationUsage
import com.wangzan101.mika.core.model.usage.RequestComposition
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.chat.GeneratedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ConversationStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val lenientJson = Json { ignoreUnknownKeys = true }

    // Background task ids dispatched by the front desk are stored with that round and read back unchanged; older records without this field read as empty
    @Test fun turnTasksRoundTrip() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val turn = Turn("帮我订票", "好的,在办", "succeeded", atMs = 1, deskTasks = listOf("c1", "c2"))
        store.append("p", turn)
        assertEquals(listOf(turn), ConversationStore(dir).turns("p"))
        File(dir, "old.json").writeText("""[{"userText":"问题","outcome":"结果","status":"succeeded"}]""")
        assertEquals(emptyList<String>(), ConversationStore(dir).turns("old").single().deskTasks)
        // No task ids means the field is not written
        store.append("plain", Turn("问题", "结果", "succeeded"))
        assertFalse("deskTasks" in File(dir, "plain.json").readText())
    }

    // A child conversation opened by front-desk dispatch remembers its front-desk conversation and does not appear in the conversation list; deleting the child deletes this link too
    @Test fun deskChildrenHiddenFromList() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("p", Turn("帮我订票", "在办", "succeeded"))
        store.append("c1", Turn("订明早的票", "订好了", "succeeded"))
        assertNull(store.deskParent("c1"))
        store.setDeskParent("c1", "p")
        assertEquals("p", store.deskParent("c1"))
        assertEquals("p", ConversationStore(dir).deskParent("c1"))
        assertEquals(listOf("p"), ConversationStore(dir).list(includeArchived = true).map { it.id })
        assertNull(store.deskParent("p"))
        store.delete("c1")
        assertFalse(File(dir, "c1.desk_parent").exists())
    }

    // A child conversation is hidden only while its parent exists: once the parent is deleted (or cannot be found after a restart), the child returns to the list instead of being lost forever
    @Test fun deskChildShownOnceParentIsGone() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("c1", Turn("订明早的票", "订好了", "succeeded"))
        // The parent's round is not stored yet, but work was just dispatched in this process: still hidden
        store.setDeskParent("c1", "p")
        assertEquals(emptyList<String>(), store.list().map { it.id })
        store.append("p", Turn("帮我订票", "在办", "desk"))
        assertEquals(listOf("p"), ConversationStore(dir).list().map { it.id })
        store.delete("p")
        assertEquals(listOf("c1"), store.list().map { it.id })
        assertEquals(listOf("c1"), ConversationStore(dir).list().map { it.id })
        // After a restart the parent was never stored: the child is listed too
        store.append("c2", Turn("查天气", "晴", "succeeded"))
        store.setDeskParent("c2", "never-saved")
        assertTrue("c2" in ConversationStore(dir).list().map { it.id })
    }

    @Test fun failedFileDeletionKeepsHistoryAndAllowsRetry() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("a", Turn("问题", "结果", "succeeded"))
        store.setCurrent(CurrentChat.Open("a"))
        val blocker = File(dir, "a.json.tmp")
        assertTrue(blocker.mkdir())
        val child = File(blocker, "keep")
        child.writeText("data")

        assertThrows(IOException::class.java) { store.delete("a") }
        assertEquals(CurrentChat.Open("a"), store.currentChat())
        assertEquals(listOf("a"), ConversationStore(dir).list().map { it.id })
        assertTrue(store.rename("a", "保留的对话"))
        assertTrue(child.isFile)

        assertTrue(child.delete())
        assertTrue(blocker.delete())
        store.delete("a")
        assertTrue(ConversationStore(dir).list().isEmpty())
        assertNull(store.currentChat())
    }

    @Test fun usageSurvivesHistoryPruningAndNeverChargesAnotherChat() {
        val dir = tmp.newFolder("chats")
        val store = ConversationStore(dir)
        val history = RunMetricsStore(File(dir.parentFile, RunMetricsStore.FILE_NAME), keep = 1)
        store.append("a", Turn("one", "ok", "succeeded", runId = "r1"))
        history.append(RunMetrics(runId = "r1", allInputTokens = 80, allOutputTokens = 4))
        val live = store.meter("a")
        live.record(Usage(100, 10, 40), 1)
        history.append(RunMetrics(runId = "r2"))
        store.append("a", Turn("two", "ok", "succeeded", runId = "r2"))
        live.record(Usage(5, 1), 1)
        val saved = ConversationStore(dir).usageSnapshot("a")
        assertEquals(185L, saved.input)
        assertEquals(15L, saved.output)
        assertTrue(saved.partial)
        assertEquals(0.4, saved.cacheRate!!, 0.00001)
        assertTrue(store.branch("a", "b", 0, origin = BranchOrigin("a", 0, true, 1)))
        assertEquals(ConversationUsage(), store.usageSnapshot("b"))
        val branch = store.meter("b")
        branch.record(Usage(20, 3, cacheReported = true), 1)
        assertEquals(0.0, store.usageSnapshot("b").cacheRate!!, 0.0)
        assertEquals(saved, store.usageSnapshot("a"))
        store.delete("a")
        live.record(Usage(2, 1), 1)
        assertEquals(ConversationUsage(), store.usageSnapshot("a"))
        assertFalse(File(dir, "a.json.usage").exists())
    }

    @Test fun overlappingAuxiliaryCallsAccumulateOnce() {
        val store = ConversationStore(tmp.newFolder())
        val meter = store.meter("chat")
        val jobs = List(4) { Thread { repeat(10) { meter.record(Usage(6, 1), 1) } } }
        jobs.forEach(Thread::start)
        jobs.forEach(Thread::join)
        assertEquals(ConversationUsage(input = 240, output = 40), store.usageSnapshot("chat"))
    }

    @Test fun persistsAcrossInstances() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        a.append(id, Turn("订票", "验证码\n\"过不去\"", "failed"))
        val b = ConversationStore(dir)
        assertEquals(listOf(Turn("查天气", "晴", "succeeded"), Turn("订票", "验证码\n\"过不去\"", "failed")), b.turns(id).map { it.copy(atMs = null) })
        assertEquals(emptyList<Turn>(), b.turns(b.newConversation()))
        assertNotEquals(id, b.newConversation())
    }

    @Test fun appendStampsNewTurnsAndPreservesExplicitTimes() {
        val dir = tmp.newFolder("times")
        var now = 1_000L
        val store = ConversationStore(dir, clock = { now })
        store.append("c", Turn("一", "甲", "succeeded"))
        now = 2_000L
        store.append("c", Turn("二", "乙", "succeeded", atMs = 42L))
        now = 3_000L
        store.append("c", Turn("三", "丙", "succeeded"))
        assertEquals(listOf(1_000L, 42L, 3_000L), ConversationStore(dir).turns("c").map { it.atMs })
        val saved = Json.parseToJsonElement(java.io.File(dir, "c.json").readText()) as JsonArray
        assertEquals(listOf("1000", "42", "3000"), saved.map { (it as JsonObject).getValue("atMs").toString() })
    }

    @Test fun oldTurnKeepsUnknownTimeWhenANewTurnIsAppended() {
        val dir = tmp.newFolder("old")
        java.io.File(dir, "c.json").writeText("""[{"userText":"旧","outcome":"好","status":"succeeded"}]""")
        val store = ConversationStore(dir, clock = { 99L })
        assertNull(store.turns("c").single().atMs)
        store.append("c", Turn("新", "也好", "succeeded"))
        val read = ConversationStore(dir).turns("c")
        assertEquals(listOf(null, 99L), read.map { it.atMs })
        val saved = Json.parseToJsonElement(java.io.File(dir, "c.json").readText()) as JsonArray
        assertFalse("atMs" in (saved[0] as JsonObject))
        assertEquals("99", (saved[1] as JsonObject).getValue("atMs").toString())
    }

    @Serializable
    private data class LegacyTurn(val userText: String, val outcome: String, val status: String)

    @Test fun oldReaderIgnoresTheNewOptionalTimeField() {
        val dir = tmp.newFolder("compat")
        ConversationStore(dir).append("c", Turn("问题", "答案", "succeeded", atMs = 123L))
        val raw = java.io.File(dir, "c.json").readText()
        val old = lenientJson.decodeFromString<List<LegacyTurn>>(raw)
        assertEquals(listOf(LegacyTurn("问题", "答案", "succeeded")), old)
    }

    /** The page (site) left running in the background and the reason it was later closed are stored with that turn; turns that left no page do not write these two fields. */
    @Test fun persistsKeptPage() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("播放课程,挂着别关", "开始播放了", "succeeded", runId = "r1", keptPage = "course.example.com"))
        a.append(id, Turn("谢谢", "不客气", "succeeded", runId = "r2"))
        assertEquals(listOf("course.example.com", null), ConversationStore(dir).turns(id).map { it.keptPage })
        a.updateTurn(id, "r1") { it.copy(keptPageClosed = "expired") }
        assertEquals("expired", ConversationStore(dir).turns(id).first().keptPageClosed)
        assertFalse("keptPage" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** The capability groups used in this turn are stored with it (the next turn pre-expands them at the start); turns that used none do not write this field. */
    @Test fun persistsUsedGroups() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("打开设置", "好了", "succeeded", groups = listOf("phone", "web")))
        a.append(id, Turn("谢谢", "不客气", "succeeded"))
        assertEquals(listOf(listOf("phone", "web"), emptyList()), ConversationStore(dir).turns(id).map { it.groups })
        assertFalse("groups" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** Each reply interrupted by a steer is stored one-to-one with that steer; turns with no interruption do not write this field, and old files read back empty. */
    @Test fun persistsInterruptedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        val read = ConversationStore(dir).turns(id)
        assertEquals(listOf(listOf("今天晴", ""), emptyList()), read.map { it.interruptedReplies })
        assertEquals(listOf("改成查明天", "顺便看看后天"), read.first().steers)
        assertFalse("interruptedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** The origin of a placeholder cancellation outcome is stored with it; a later summary clears the origin, and old files and ordinary turns read it back as empty. */
    @Test fun persistsOutcomeSystemType() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查资料", "已取消", "cancelled", runId = "r1", outcomeSystemType = "task_cancelled"))
        a.append(id, Turn("谢谢", "已取消", "succeeded", runId = "r2"))
        assertEquals(listOf("task_cancelled", null), ConversationStore(dir).turns(id).map { it.outcomeSystemType })
        assertFalse("outcomeSystemType" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
        a.updateTurn(id, "r1") { it.copy(outcome = "找到两篇", outcomeSystemType = null) }
        assertEquals(null, ConversationStore(dir).turns(id).first().outcomeSystemType)
    }

    /** Replies that had already finished before a steer are likewise stored one-to-one; when there are none the field is not written. */
    @Test fun persistsFinishedReplies() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。")))
        a.append(id, Turn("谢谢", "不客气", "succeeded", steers = listOf("好")))
        assertEquals(listOf(listOf("今天晴,最高 25 度。"), emptyList()), ConversationStore(dir).turns(id).map { it.finishedReplies })
        assertFalse("finishedReplies" in java.io.File(dir, "$id.json").readText().substringAfter("谢谢"))
    }

    /** Delete conversation: the conversation file, the title, the corrupted file moved aside and the temp file are all deleted, it disappears from the list, other conversations are untouched; one change is emitted after the deletion. */
    @Test fun deleteRemovesTheConversationAndItsSidecars() = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        val other = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.setTitle(id, "天气")
        store.setPinned(id, true, at = 5L)
        store.setProject(id, "proj-sidecar")
        java.io.File(dir, "$id.json.corrupt-1").writeText("x")
        java.io.File(dir, "$id.json.tmp").writeText("x")
        store.append(other, Turn("订票", "好了", "succeeded"))
        val changes = mutableListOf<String>()
        val deletions = mutableListOf<String>()
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.changes.collect { changes += it } }
        val gone = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { store.deletions.collect { deletions += it } }
        store.delete(id)
        kotlinx.coroutines.yield()
        job.cancel()
        gone.cancel()
        assertEquals(listOf(id), changes)
        assertEquals(listOf(id), deletions)
        assertEquals(listOf("$other.json"), dir.list()!!.sorted())
        assertEquals(listOf(other), store.list().map { it.id })
        assertEquals(emptyList<Turn>(), store.turns(id))
        assertEquals(null, store.title(id))
        assertEquals(null, store.pinnedAt(id))
    }

    /** The name the user set wins: a late auto-title from the background does not overwrite it; the user can still rename a conversation that already has a title; an empty name changes nothing. */
    @Test fun renameByUserWinsOverAutoTitle() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查一下明天上海的天气", "晴", "succeeded"))
        assertTrue(store.rename(id, "  天气  "))
        assertFalse(store.setTitle(id, "上海天气"))
        assertEquals("天气", ConversationStore(dir).title(id))

        val other = store.newConversation()
        store.append(other, Turn("订票", "好了", "succeeded"))
        assertTrue(store.setTitle(other, "订高铁票"))
        assertTrue(store.rename(other, "回家的票"))
        assertFalse(store.rename(other, "   "))
        assertEquals("回家的票", store.title(other))
        assertEquals(setOf("天气", "回家的票"), store.list().map { it.title }.toSet())

        store.delete(other)
        assertFalse(store.rename(other, "再改"))
    }

    /** Pinning is recorded in `.pin` (the pin time) and survives a reopen; the list carries the pin time without changing conversation order; unpinning removes it. */
    @Test fun pinPersistsAndShowsInTheList() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("一", "好", "succeeded"))
        store.append("b", Turn("二", "好", "succeeded"))
        assertTrue(store.setPinned("a", true, at = 123L))
        val again = ConversationStore(dir)
        assertEquals(123L, again.pinnedAt("a"))
        assertEquals(null, again.pinnedAt("b"))
        assertEquals(mapOf("a" to 123L, "b" to null), again.list().associate { it.id to it.pinnedAt })
        assertTrue(again.setPinned("a", false))
        assertEquals(null, again.pinnedAt("a"))
        assertFalse(java.io.File(dir, "a.pin").exists())
        // A deleted conversation does not come back as pinned
        again.delete("b")
        assertFalse(again.setPinned("b", true))
    }

    /** Writes that arrive after the deletion (an auto-title finished in the background, an outcome added after wrap-up) do not bring the conversation back; appending another turn reuses that id. */
    @Test fun lateWritesDoNotReviveADeletedConversation() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r1"))
        store.delete(id)
        assertFalse(store.setTitle(id, "天气"))
        store.updateTurn(id, "r1") { it.copy(outcome = "补上的") }
        assertTrue(dir.list()!!.isEmpty())

        store.append(id, Turn("再来", "好", "succeeded"))
        assertTrue(store.setTitle(id, "再来"))
        assertEquals(listOf("再来"), store.list().map { it.title })
    }

    @Test fun corruptFileReadsAsEmpty() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        assertEquals(emptyList<Turn>(), s.turns("x"))
        s.append("x", Turn("a", "b", "cancelled"))
        assertEquals(1, s.turns("x").size)
    }

    @Test fun corruptFileIsMovedAsideNotOverwritten() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        java.io.File(dir, "x.json").writeText("{not json")
        s.append("x", Turn("a", "b", "cancelled"))
        val aside = dir.listFiles().orEmpty().filter { it.name.startsWith("x.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{not json", aside.single().readText())
        assertEquals(listOf(Turn("a", "b", "cancelled")), s.turns("x").map { it.copy(atMs = null) })
        assertEquals(listOf("x"), s.list().map { it.id })
    }

    @Test fun listsConversationsNewestFirst() {
        val dir = tmp.newFolder("c")
        val s = ConversationStore(dir)
        val a = s.newConversation()
        val b = s.newConversation()
        s.append(a, Turn("查天气", "晴", "succeeded"))
        s.append(b, Turn("订票", "好了", "succeeded"))
        s.append(b, Turn("改签", "好了", "succeeded"))
        java.io.File(dir, "$a.json").setLastModified(1_000)
        java.io.File(dir, "$b.json").setLastModified(2_000)
        java.io.File(dir, "broken.json").writeText("{")
        assertEquals(listOf(b to "订票", a to "查天气"), s.list().map { it.id to it.title })
    }

    @Test fun unverifiedTurnKeepsItsNote() {
        val dir = tmp.newFolder()
        val id = ConversationStore(dir).let { s -> s.newConversation().also { s.append(it, Turn("查余额", "余额 10 元", "unverified", note = "防截屏")) } }
        assertEquals(listOf(Turn("查余额", "余额 10 元", "unverified", note = "防截屏")), ConversationStore(dir).turns(id).map { it.copy(atMs = null) })
    }

    @Test fun turnKeepsItsRunId() {
        val dir = tmp.newFolder()
        val s = ConversationStore(dir)
        val id = s.newConversation()
        s.append(id, Turn("查天气", "晴", "succeeded", runId = "20261023-120000-abc123"))
        s.append(id, Turn("旧的", "好", "succeeded"))
        assertEquals(listOf("20261023-120000-abc123", null), ConversationStore(dir).turns(id).map { it.runId })
    }

    @Test fun memoryNotesStoredWithoutContentAndStripped() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val note = com.wangzan101.mika.core.learn.memory.MemoryNote(com.wangzan101.mika.core.learn.memory.NoteKind.added, "m1", "t1", null)
        val other = com.wangzan101.mika.core.learn.memory.MemoryNote(com.wangzan101.mika.core.learn.memory.NoteKind.pending, "m2", "t2", "m2")
        store.append("c1", Turn("点外卖", "ok", "succeeded", runId = "r1"))
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note, other), usedMemory = listOf("m1", "m3")) }
        val raw = java.io.File(dir, "c1.json").readText()
        assertTrue(raw.contains("\"memoryId\":\"m1\""))
        assertFalse(raw.contains("text"))
        assertEquals(listOf(note, other), ConversationStore(dir).turns("c1").single().memoryNotes)
        store.stripMemory(setOf("m1"))
        val t = ConversationStore(dir).turns("c1").single()
        assertEquals(listOf(other), t.memoryNotes)
        assertEquals(listOf("m3"), t.usedMemory)
        store.stripMemory(null)
        assertTrue(ConversationStore(dir).turns("c1").single().memoryNotes.isEmpty())
    }

    @Test fun filesKeptPerTurn() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        val f = SharedFile("图.png", 1234, "Download/Claire/图.png", "content://media/external/downloads/7")
        a.append(id, Turn("画图", "好了", "succeeded"), listOf(f))
        a.append(id, Turn("再来", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(listOf(listOf(f), emptyList()), b.records(id).map { it.second })
        assertEquals(2, b.turns(id).size)
    }

    // Adding a memory hint after the task ends or deleting a memory must not lose this turn's file cards
    @Test fun filesSurviveNoteUpdatesAndStrip() {
        val dir = tmp.newFolder("d")
        val store = ConversationStore(dir)
        val f = SharedFile("表.csv", 10, "Download/Claire/表.csv", "content://media/external/downloads/8")
        store.append("c1", Turn("导出", "好了", "succeeded", runId = "r1"), listOf(f))
        val note = com.wangzan101.mika.core.learn.memory.MemoryNote(com.wangzan101.mika.core.learn.memory.NoteKind.added, "m1", "t1", null)
        store.updateTurn("c1", "r1") { it.copy(memoryNotes = listOf(note)) }
        store.stripMemory(null)
        val r = ConversationStore(dir).records("c1").single()
        assertEquals(listOf(f), r.second)
        assertTrue(r.first.memoryNotes.isEmpty())
    }

    // Whichever conversation was written emits its id: appending a turn, updating a turn, dropping the memory hint
    @Test fun announcesChangedConversations() = kotlinx.coroutines.test.runTest {
        val s = ConversationStore(tmp.newFolder("c"))
        val seen = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.changes.collect { seen += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.deletions.collect { deleted += it } }
        s.append("a", Turn("查天气", "晴", "succeeded", runId = "r1", usedMemory = listOf("m1")))
        s.append("b", Turn("订票", "好了", "succeeded"))
        s.updateTurn("a", "r1") { it.copy(outcome = "晴转多云") }
        s.updateTurn("a", "missing") { it }
        s.stripMemory(setOf("m1"))
        assertEquals(listOf("a", "b", "a", "a"), seen)
        // Only deleting a conversation emits deletions
        assertEquals(emptyList<String>(), deleted)
    }

    /** Which page the conversation started from: recorded in the adjacent .page file and carried in the list; old conversations without it still read fine; deleting the conversation deletes it too. */
    @Test fun pageOriginPersistsAndIsOptional() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val plain = a.newConversation()
        a.append(plain, Turn("查天气", "晴", "succeeded"))
        val paged = a.newConversation()
        assertTrue(a.setPageOrigin(paged, PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索")))
        a.append(paged, Turn("挑一个", "好", "succeeded"))
        val b = ConversationStore(dir)
        assertEquals(PageOrigin("https://shop.example.com/s?k=1", "shop.example.com", "搜索"), b.pageOrigin(paged))
        assertEquals(null, b.pageOrigin(plain))
        val listed = b.list().associateBy { it.id }
        assertEquals("shop.example.com", listed.getValue(paged).page?.host)
        assertEquals(null, listed.getValue(plain).page)
        // The conversation file format is unchanged: no extra fields
        assertFalse(java.io.File(dir, "$paged.json").readText().contains("shop.example.com"))
        // A corrupted .page is treated as absent
        java.io.File(dir, "$plain.page").writeText("{not json")
        assertEquals(null, b.pageOrigin(plain))
        b.delete(paged)
        assertFalse(java.io.File(dir, "$paged.page").exists())
        assertFalse(b.setPageOrigin(paged, PageOrigin("https://a.example/", "a.example")))
    }

    /**
     * "Which conversation the user is in right now" is recorded too: after a process restart the capsule resumes that conversation from it, instead of guessing the most recent one by file timestamps;
     * a newly opened conversation with no messages yet is recorded as [CurrentChat.Fresh]; deleting it clears this too; this file does not appear in the conversation list.
     */
    @Test fun remembersTheConversationOnScreen() {
        val dir = tmp.newFolder("c")
        val a = ConversationStore(dir)
        val id = a.newConversation()
        a.append(id, Turn("查天气", "晴", "succeeded"))
        assertEquals(null, a.currentChat())

        a.setCurrent(CurrentChat.Fresh)
        assertEquals(CurrentChat.Fresh, a.currentChat())
        // A newly opened conversation has no conversation file yet, but it still reads back after a restart
        assertEquals(CurrentChat.Fresh, ConversationStore(dir).currentChat())
        assertEquals(listOf(id), ConversationStore(dir).list().map { it.id })

        a.setCurrent(CurrentChat.Open(id))
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).currentChat())
        assertEquals(CurrentChat.Open(id), ConversationStore(dir).current.value)
        // Recorded in the adjacent current file, without touching the conversation file or entering the list
        assertEquals(setOf("$id.json", "current"), dir.list()!!.toSet())
        assertEquals(listOf(id), a.list().map { it.id })

        // Delete this conversation: the record pointing at it is cleared too, and the capsule falls back to the most recent one
        a.delete(id)
        assertEquals(null, ConversationStore(dir).currentChat())
    }

    /** The branch copies up to and including the selected turn and nothing after it; the source conversation stays as it is. */
    @Test fun branchCopiesThroughTheChosenTurn() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded", atMs = 11L))
        store.append(source, Turn("二", "乙", "succeeded", atMs = 22L))
        store.append(source, Turn("三", "丙", "succeeded", atMs = 33L))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, throughTurn = 1))
        assertEquals(
            listOf(Turn("一", "甲", "succeeded", atMs = 11L), Turn("二", "乙", "succeeded", atMs = 22L)),
            ConversationStore(dir).turns(target),
        )
        // The source conversation still has three turns
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** The branch carries over the title, the page origin and the branch origin, but not the pin. */
    @Test fun branchCopiesTitlePageAndOriginButNotPin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("查天气", "晴", "succeeded"))
        store.setTitle(source, "原对话")
        store.setPinned(source, true, at = 1L)
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 123L)
        assertTrue(store.branch(source, target, 0, title = "原对话 · 分支", origin = origin))
        val again = ConversationStore(dir)
        assertEquals("原对话 · 分支", again.title(target))
        assertEquals(page, again.pageOrigin(target))
        assertEquals(origin, again.branchOrigin(target))
        assertEquals(null, again.pinnedAt(target))
    }

    /** Source missing, source file corrupted, turn index out of range, target conversation file already existing: all return false and write nothing. */
    @Test fun branchRejectsBadInput() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        val target = store.newConversation()

        assertFalse(store.branch("missing", target, 0))
        assertFalse(java.io.File(dir, "$target.json").exists())

        java.io.File(dir, "broken.json").writeText("{not json")
        assertFalse(store.branch("broken", target, 0))

        assertFalse(store.branch(source, target, throughTurn = 2))
        assertFalse(store.branch(source, target, throughTurn = -1))
        assertFalse(java.io.File(dir, "$target.json").exists())
        assertFalse(java.io.File(dir, "$target.branch").exists())

        store.append(target, Turn("已有", "好", "succeeded"))
        assertFalse(store.branch(source, target, 0))
        assertEquals(listOf("已有"), store.turns(target).map { it.userText })
    }

    /** A branch can be branched again; the turn count follows the parent branch's content and the origin points at the previous conversation. */
    @Test fun branchOfBranchWorks() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val middle = store.newConversation()
        assertTrue(store.branch(source, middle, 2, origin = BranchOrigin(source, 2, afterTurn = true, at = 1L)))
        val leaf = store.newConversation()
        assertTrue(store.branch(middle, leaf, 0, origin = BranchOrigin(middle, 0, afterTurn = true, at = 2L)))
        assertEquals(listOf("一"), store.turns(leaf).map { it.userText })
        assertEquals(middle, store.branchOrigin(leaf)?.parentId)
        assertEquals(source, store.branchOrigin(middle)?.parentId)
    }

    /** Deleting a conversation deletes its branch origin too. */
    @Test fun deleteRemovesBranchSidecar() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        assertTrue(store.branch(source, target, 0, origin = BranchOrigin(source, 0, afterTurn = true, at = 1L)))
        val origin = java.io.File(dir, "$target.branch")
        assertTrue(origin.isFile)
        store.delete(target)
        assertFalse(origin.exists())
        assertEquals(null, store.branchOrigin(target))
    }

    /** The list carries the branch origin; it is null for ordinary conversations and for old conversations without a .branch file. */
    @Test fun listExposesBranchOrigin() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = true, at = 9L)
        assertTrue(store.branch(source, target, 0, origin = origin))
        val listed = ConversationStore(dir).list().associateBy { it.id }
        assertEquals(origin, listed.getValue(target).branchOf)
        assertEquals(null, listed.getValue(source).branchOf)
    }

    /** Ask again from a user message: only the turns before this one are copied, and the origin records after=false. */
    @Test fun branchBeforeCopiesOnlyEarlierTurns() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.append(source, Turn("三", "丙", "succeeded"))
        val page = PageOrigin("https://a.example/", "a.example", "示例")
        store.setPageOrigin(source, page)
        val target = store.newConversation()
        val origin = BranchOrigin(source, 1, afterTurn = false, at = 2L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 1, title = "原对话 · 分支", origin = origin))

        assertEquals(listOf("一"), ConversationStore(dir).turns(target).map { it.userText })
        assertEquals(origin, store.branchOrigin(target))
        assertEquals(page, store.pageOrigin(target))
        assertEquals(listOf("一", "二", "三"), store.turns(source).map { it.userText })
    }

    /** No turns before the first one: the branch is empty and does not appear in the sidebar; the title and origin are already written and are used once the first message is stored. */
    @Test fun branchBeforeTheFirstTurnMakesAnEmptyBranch() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        val target = store.newConversation()
        val origin = BranchOrigin(source, 0, afterTurn = false, at = 3L)

        assertTrue(store.branchBefore(source, target, beforeTurn = 0, title = "一 · 分支", origin = origin))

        assertEquals(emptyList<Turn>(), store.turns(target))
        assertTrue(java.io.File(dir, "$target.json").isFile)
        assertTrue(store.list().none { it.id == target })
        assertEquals("一 · 分支", store.title(target))
        assertEquals(origin, store.branchOrigin(target))
        store.append(target, Turn("换个问法", "另一个答", "succeeded"))
        assertEquals(listOf("换个问法"), store.turns(target).map { it.userText })
        assertTrue(store.list().any { it.id == target })
    }

    /** Asking again also rejects bad input: source missing, turn out of range, target file already exists. */
    /** The persona binding reads back; switching records only changes, and writing the same one again is not recorded twice; null means back to the default. */
    @Test fun personaBindingRoundTripsAndRecordsSwitches() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        assertNull(store.persona(id))
        assertTrue(store.setPersona(id, "p1", at = 10L))
        assertEquals("p1", ConversationStore(dir).persona(id)?.personaId)
        assertEquals(emptyList<PersonaSwitch>(), store.persona(id)?.history)
        assertTrue(store.setPersona(id, "p2", at = 20L))
        assertEquals(listOf(PersonaSwitch(20L, "p1", "p2")), store.persona(id)?.history)
        assertTrue(store.setPersona(id, "p2", at = 30L))
        assertEquals(1, store.persona(id)?.history?.size)
        assertTrue(store.setPersona(id, null))
        assertNull(store.persona(id))
        assertFalse(java.io.File(dir, "$id.persona").exists())
    }

    /** A branch copies the current persona but not the switch history; deleting a conversation deletes its sidecar too. */
    @Test fun personaBindingFollowsBranchesAndDeletion() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.append(source, Turn("二", "乙", "succeeded"))
        store.setPersona(source, "p1", at = 10L)
        store.setPersona(source, "p2", at = 20L)
        val target = store.newConversation()
        assertTrue(store.branch(source, target, throughTurn = 0))
        assertEquals("p2", store.persona(target)?.personaId)
        assertEquals(emptyList<PersonaSwitch>(), store.persona(target)?.history)
        store.delete(source)
        assertFalse(java.io.File(dir, "$source.persona").exists())
    }

    /** An invalid persona id is not written; a bad id in the sidecar is treated as absent. */
    @Test fun personaBindingRejectsBadIds() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val id = store.newConversation()
        assertFalse(store.setPersona(id, "../escape"))
        assertNull(store.persona(id))
        java.io.File(dir, "$id.persona").writeText("""{"current":"../escape"}""")
        assertNull(store.persona(id))
    }

    /** Archived conversations are kept out of the sidebar by default; use includeArchived to get them all; it survives a new instance, and the conversation returns after unarchiving. */
    @Test fun archivedConversationsHiddenUntilRequested() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))

        assertTrue(store.setArchived("a", true, at = 123L))
        assertEquals(123L, store.archivedAt("a"))
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(setOf("a", "b"), store.list(includeArchived = true).map { it.id }.toSet())
        assertEquals(123L, store.list(includeArchived = true).first { it.id == "a" }.archivedAt)

        val reopened = ConversationStore(dir)
        assertEquals(123L, reopened.archivedAt("a"))
        assertEquals(listOf("b"), reopened.list().map { it.id })

        assertTrue(reopened.setArchived("a", false))
        assertNull(reopened.archivedAt("a"))
        assertEquals(setOf("a", "b"), reopened.list().map { it.id }.toSet())
    }

    /** Unarchiving records the restore time (auto-archiving uses it to grant a grace period); deleting the conversation deletes it too. */
    @Test fun unarchiveRecordsTheRestoreTime() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)

        assertTrue(store.setArchived("a", false, at = 456L))
        assertEquals(456L, store.restoredAt("a"))
        assertEquals(456L, ConversationStore(dir).list(includeArchived = true).single().restoredAt)

        store.delete("a")
        assertFalse(File(dir, "a.restored").exists())
        assertNull(ConversationStore(dir).restoredAt("a"))
    }

    /** Deleting an archived conversation deletes its sidecar too; check with a new instance so the in-process deletion record does not get in the way. */
    @Test fun deleteRemovesArchiveSidecar() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)
        assertTrue(File(dir, "a.archive").isFile)

        store.delete("a")

        assertFalse(File(dir, "a.archive").exists())
        assertNull(ConversationStore(dir).archivedAt("a"))
        assertTrue(ConversationStore(dir).list(includeArchived = true).isEmpty())
    }

    /** A branch made from an archived conversation is new and active and does not inherit the archive. */
    @Test fun branchDoesNotInheritArchive() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        val source = store.newConversation()
        store.append(source, Turn("一", "甲", "succeeded"))
        store.setArchived(source, true, at = 1L)
        val target = store.newConversation()

        assertTrue(store.branch(source, target, 0))

        assertNull(store.archivedAt(target))
        assertTrue(store.list().any { it.id == target })
        assertTrue(store.list().none { it.id == source })
    }

    /** When the archive sidecar cannot be parsed it is treated as not archived and does not block the list. */
    @Test fun corruptArchiveFileIsTreatedAsActive() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        File(dir, "a.archive").writeText("not-a-time")

        assertNull(store.archivedAt("a"))
        assertEquals(listOf("a"), store.list().map { it.id })
    }

    /** A new turn in an archived conversation unarchives it automatically; updating an existing turn or changing only the title does not count. */
    @Test fun appendClearsArchiveButEditingAnExistingTurnDoesNot() {
        val dir = tmp.newFolder("c")
        val store = ConversationStore(dir)
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.setArchived("a", true, at = 1L)

        store.updateTurn("a", runId = "missing") { it }
        store.setTitle("a", "天气")
        assertEquals(1L, store.archivedAt("a"))

        store.append("a", Turn("那明天呢", "也晴", "succeeded"))

        assertNull(store.archivedAt("a"))
        assertTrue(store.list().any { it.id == "a" })
    }

    @Test fun generatedAudiosSurviveReopen() {
        val dir = tmp.newFolder("audio")
        val store = ConversationStore(dir)
        val audios = listOf(
            GeneratedAudio("/workspace/out/audio/line.wav", 1_200L, "你好", 42L),
            GeneratedAudio("/workspace/out/audio/two.mp3", null, null, null),
        )
        store.append("a", Turn("说一段", "好", "succeeded", audios = audios))
        assertEquals(audios, ConversationStore(dir).turns("a").single().audios)
    }

    @Test fun projectSidecarPersistsInheritsOnBranchAndCleansOnDelete() {
        val dir = tmp.newFolder("project-sidecar")
        val store = ConversationStore(dir)
        val a = store.newConversation()
        store.append(a, Turn("第一轮", "好了", "succeeded"))
        assertTrue(store.setProject(a, "proj-1"))
        assertFalse(store.setProject(a, "../bad"))
        assertEquals("proj-1", ConversationStore(dir).projectId(a))
        assertEquals("proj-1", store.list().single { it.id == a }.projectId)

        val branched = store.newConversation()
        assertTrue(store.branch(a, branched, 0))
        assertEquals("proj-1", ConversationStore(dir).projectId(branched))

        // Deleting the source conversation also clears its .project sidecar; the branch is kept
        store.delete(a)
        assertNull(store.projectId(a))
        assertFalse(File(dir, "$a.project").exists())
        assertFalse(store.setProject(a, "proj-2"))
        assertEquals("proj-1", store.projectId(branched))

        // Unbinding returns to a standalone conversation
        assertTrue(store.setProject(branched, null))
        assertNull(store.projectId(branched))
        assertFalse(File(dir, "$branched.project").exists())
    }

    // Rounds said in a call record which call they belong to; call records replace by id, sort by connect time, survive a reopen, and are deleted with the conversation
    @Test fun callRecordsRoundTrip() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val turn = Turn("几点了", "九点", "desk", atMs = 300, callId = "a")
        store.append("p", turn)
        assertEquals("a", ConversationStore(dir).turns("p").single().callId)
        store.append("plain", Turn("问题", "结果", "succeeded"))
        assertFalse("callId" in File(dir, "plain.json").readText())

        store.recordCall("p", ConversationCall("b", 500))
        store.recordCall("p", ConversationCall("a", 200))
        store.recordCall("p", ConversationCall("a", 200, 400, ConversationCall.END_IDLE, spoke = true))
        val calls = ConversationStore(dir).calls("p")
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals(400L, calls.first().endMs)
        assertEquals(ConversationCall.END_IDLE, calls.first().endReason)
        assertEquals(emptyList<ConversationCall>(), store.calls("plain"))

        store.delete("p")
        assertEquals(emptyList<ConversationCall>(), ConversationStore(dir).calls("p"))
        assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".calls") })
    }

    @Test fun generatedVideosSurviveReopen() {
        val dir = tmp.newFolder("video")
        val store = ConversationStore(dir)
        val videos = listOf(
            com.wangzan101.mika.core.chat.GeneratedVideo("/workspace/out/video/take.mp4", 5_000L, 1280, 720, "海边日落", 42L),
            com.wangzan101.mika.core.chat.GeneratedVideo("/workspace/out/video/raw.mp4"),
        )
        store.append("a", Turn("生成一段", "好", "succeeded", videos = videos))
        assertEquals(videos, ConversationStore(dir).turns("a").single().videos)
    }

    @Test fun deskChildUsageForwardsToActiveParentAndStopsAfterParentDelete() = runBlocking {
        val dir = tmp.newFolder("desk-usage")
        val store = ConversationStore(dir)
        store.setDeskParent("c1", "p")
        val firstUpdate = CompletableDeferred<ConversationUsage>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.observeUsage("p").collect { usage ->
                if (usage.input > 0 && !firstUpdate.isCompleted) firstUpdate.complete(usage)
            }
        }
        val comp = RequestComposition("gpt-main", 2, 128_000, 100, 50, 20, 30, 10)
        val childMeter = store.meter("c1")
        // 即使前台对话第一轮还没写盘（靠 liveDeskParents 识别），子任务用量和上下文也立即同步给父对话
        childMeter.request(comp)
        childMeter.record(Usage(120, 18, 80, cacheReported = true), 1)
        val expected1 = ConversationUsage(input = 120, output = 18, cached = 80, cacheReportedInput = 120, latest = comp)
        assertEquals(expected1, store.usageSnapshot("c1"))
        assertEquals(expected1, store.usageSnapshot("p"))
        assertEquals(expected1, withTimeout(5_000) { firstUpdate.await() })
        observer.cancel()

        // 父对话存盘并跨实例重开后继续转发；分支不继承子任务用量
        store.append("p", Turn("帮我查", "好", "desk"))
        val reopened = ConversationStore(dir)
        reopened.meter("c1").record(Usage(30, 5, 10, cacheReported = true), 1)
        val expected2 = ConversationUsage(input = 150, output = 23, cached = 90, cacheReportedInput = 150, latest = comp)
        assertEquals(expected2, reopened.usageSnapshot("p"))
        assertEquals(expected2, reopened.observeUsage("p").first())
        assertTrue(reopened.branch("p", "b", 0, origin = BranchOrigin("p", 0, true, 1)))
        assertEquals(ConversationUsage(), reopened.usageSnapshot("b"))

        // 删除父对话后，子对话继续记账不再写回父对话的 .usage
        reopened.delete("p")
        assertFalse(File(dir, "p.json.usage").exists())
        reopened.meter("c1").record(Usage(40, 6), 1)
        assertEquals(ConversationUsage(input = 190, output = 29, cached = 90, cacheReportedInput = 150, latest = comp), reopened.usageSnapshot("c1"))
        assertEquals(ConversationUsage(), reopened.usageSnapshot("p"))
        assertFalse(File(dir, "p.json.usage").exists())

        ConversationStore(dir).meter("c1").record(Usage(10, 2), 1)
        assertFalse(File(dir, "p.json.usage").exists())
    }

    // 前台派出的子对话自己没选主模型:开局跟着前台对话的选择(模型或模型组),前台改选后排队中的任务也跟着走
    @Test fun deskChildRunsOnTheParentsMainChoice() {
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("c1", "p")
        assertNull(store.runMainChoice("c1"))
        store.setSettings("p", ConversationSettings(ModelChoice.Group("group-1")))
        assertEquals(ModelChoice.Group("group-1"), store.runMainChoice("c1"))
        store.setSettings("p", ConversationSettings(ModelChoice.Model("cfg-x")))
        assertEquals(ModelChoice.Model("cfg-x"), store.runMainChoice("c1"))
        // 子对话没写旁文件,配置清理不会因为它把这份配置一直留着
        assertEquals(ConversationSettings(), store.settings("c1"))
    }

    // 前台对话的选择还没写进旁文件(通话里派活不经对话页发送):先认那一份,「跟随设置」也压过旧的旁文件
    @Test fun deskChildPrefersTheParentsUnsavedChoice() {
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("c1", "p")
        store.setSettings("p", ConversationSettings(ModelChoice.Model("cfg-old")))
        store.setPendingMainChoice("p", ModelChoice.Model("cfg-x"))
        assertEquals(ModelChoice.Model("cfg-x"), store.runMainChoice("c1"))
        store.setPendingMainChoice("p", null)
        assertNull(store.runMainChoice("c1"))
    }

    // 自己单独选过的对话照用自己的;不是前台派出的对话不看别人
    @Test fun ownMainChoiceWinsAndPlainChatsIgnoreOthers() {
        val store = ConversationStore(tmp.newFolder())
        store.setDeskParent("c1", "p")
        store.setSettings("p", ConversationSettings(ModelChoice.Model("cfg-x")))
        store.setSettings("c1", ConversationSettings(ModelChoice.Model("cfg-own")))
        assertEquals(ModelChoice.Model("cfg-own"), store.runMainChoice("c1"))
        assertNull(store.runMainChoice("plain"))
    }
}
