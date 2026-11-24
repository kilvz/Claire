package com.wangzan101.mika.chat

import com.wangzan101.mika.core.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AutoArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000

    private fun summary(id: String, updatedAt: Long, pinnedAt: Long? = null, archivedAt: Long? = null, restoredAt: Long? = null) =
        ConversationSummary(id, id, updatedAt, pinnedAt = pinnedAt, archivedAt = archivedAt, restoredAt = restoredAt)

    /** Picks only chats that are not archived, not pinned, and whose last change predates the cutoff; running ones and the one currently being viewed are kept. */
    @Test fun candidatesOnlyTakeOldUnpinnedChats() {
        val now = 1_000_000_000_000L
        val cutoff = AutoArchive.cutoff(30, now)
        val all = listOf(
            summary("old", cutoff - 1),
            summary("edge", cutoff),
            summary("fresh", cutoff + 1),
            summary("pinned", cutoff - 5, pinnedAt = 1L),
            summary("archived", cutoff - 5, archivedAt = 2L),
            summary("running", cutoff - 5),
            summary("current", cutoff - 5),
        )
        assertEquals(listOf("old"), AutoArchive.candidates(all, cutoff, setOf("running", "current")))
    }

    /** A manually restored conversation gets an N-day grace period: a freshly restored one is not collected, and only once the grace period has passed can it be collected again. */
    @Test fun candidatesGiveRestoredChatsAGracePeriod() {
        val now = 1_000_000_000_000L
        val cutoff = AutoArchive.cutoff(30, now)
        val all = listOf(
            summary("restored-now", cutoff - 40 * day, restoredAt = now - day),
            summary("restored-long-ago", cutoff - 40 * day, restoredAt = cutoff - 1),
        )
        assertEquals(listOf("restored-long-ago"), AutoArchive.candidates(all, cutoff))
    }

    /** Actually archives old conversations according to the setting; new ones, pinned ones, running ones and the one currently being viewed are untouched. */
    @Test fun runArchivesOldChatsAndSkipsTheKeptOnes() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val now = System.currentTimeMillis()
        listOf("old", "fresh", "running", "pinned", "current").forEach {
            store.append(it, Turn("第 $it 个", "好", "succeeded", atMs = if (it == "fresh") now else now - 40 * day))
        }
        store.setPinned("pinned", pinned = true)
        val old = now - 40 * day
        listOf("old", "running", "pinned", "current").forEach { File(dir, "$it.json").setLastModified(old) }

        assertEquals(
            1,
            AutoArchive.run(store, days = 30, running = listOf("running"), currentId = "current", now = now),
        )
        assertEquals(now, store.archivedAt("old"))
        assertNull(store.archivedAt("fresh"))
        assertNull(store.archivedAt("running"))
        assertNull(store.archivedAt("pinned"))
        assertNull(store.archivedAt("current"))
        // An already archived chat is not archived again
        assertEquals(0, AutoArchive.run(store, days = 30, running = listOf("running"), currentId = "current", now = now + day))
    }

    /** An old conversation manually restored from the archive is not collected again right away by the next auto-archive run. */
    @Test fun runLeavesAJustRestoredChatAlone() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val now = System.currentTimeMillis()
        store.append("a", Turn("旧对话", "好", "succeeded", atMs = now - 40 * day))
        File(dir, "a.json").setLastModified(now - 40 * day)
        store.setArchived("a", true, at = now - 2 * day)
        assertTrue(store.setArchived("a", false, at = now))

        assertEquals(0, AutoArchive.run(store, days = 30, now = now))
        assertNull(store.archivedAt("a"))
    }

    @Test fun staleCandidatesDoNotArchiveRestoredOrUpdatedChats() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        val now = System.currentTimeMillis()
        listOf("restored", "updated", "backdated", "pinned", "current", "deleted", "old").forEach {
            store.append(it, Turn("旧消息", "好", "succeeded", atMs = now - 40 * day))
            assertTrue(File(dir, "$it.json").setLastModified(now - 40 * day))
        }
        val cutoff = AutoArchive.cutoff(30, now)
        val candidates = store.autoArchiveCandidates()
        assertEquals(7, candidates.size)

        store.setArchived("restored", true, at = now - day)
        store.setArchived("restored", false, at = now)
        store.append("updated", Turn("新消息", "好", "succeeded"))
        store.append("backdated", Turn("补存的消息", "好", "succeeded", atMs = now - 40 * day))
        store.setPinned("pinned", true)
        store.setCurrent(CurrentChat.Open("current"))
        store.delete("deleted")

        assertEquals(1, candidates.count { store.archiveIfStillEligible(it, cutoff, at = now) })
        assertNull(store.archivedAt("restored"))
        assertNull(store.archivedAt("updated"))
        assertNull(store.archivedAt("backdated"))
        assertNull(store.archivedAt("pinned"))
        assertNull(store.archivedAt("current"))
        assertNull(store.archivedAt("deleted"))
        assertEquals(now, store.archivedAt("old"))
    }

    /** An old conversation starts a task only after the running list has been read and scanned: each entry is re-checked before archiving, so a stale snapshot must not collect it. */
    @Test fun taskStartingAfterRunningSnapshotIsRecheckedBeforeArchiving() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        val store = ConversationStore(dir)
        store.append("old", Turn("旧消息", "好", "succeeded", atMs = now - 40 * day))
        val active = mutableListOf<String>()
        val runningSnapshot = active.toList()
        active.add("old") // Simulates a task starting after the snapshot, whose result has not been appended yet.

        assertEquals(0, AutoArchive.run(
            store, days = 30, running = runningSnapshot, now = now,
            isRunning = { it in active },
        ))
        assertNull(store.archivedAt("old"))

        active.clear()
        assertEquals(1, AutoArchive.run(
            store, days = 30, running = runningSnapshot, now = now,
            isRunning = { it in active },
        ))
        assertEquals(now, store.archivedAt("old"))
    }

    @Test fun appendDoesNotWaitForArchiveReadAndInvalidatesCandidate() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockRead = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>()
        val store = ConversationStore(dir, readConversation = { file ->
            if (blockRead.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            file.readText()
        })
        store.append("old", Turn("旧消息", "好", "succeeded", atMs = now - 40 * day))
        blockRead.set(true)
        val archive = Thread { runCatching { AutoArchive.run(store, 30, now = now) }.onFailure(failure::set) }
        val append = Thread { runCatching { store.append("old", Turn("新消息", "好", "succeeded")) }.onFailure(failure::set) }
        archive.start()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            append.start()
            append.join(5_000)
            assertTrue("append should finish while archive read is blocked", !append.isAlive)
        } finally {
            release.countDown()
            archive.join(10_000)
            if (append.isAlive) append.join(10_000)
        }
        assertNull(failure.get())
        assertTrue(!archive.isAlive && !append.isAlive)
        assertNull(store.archivedAt("old"))
        assertEquals(2, store.turns("old").size)
    }

    @Test fun unchangedConversationsReuseMessageTimeAndWritesInvalidateIt() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        var reads = 0
        val store = ConversationStore(dir, readConversation = { file -> reads++; file.readText() })
        store.append("fresh", Turn("新消息", "好", "succeeded", runId = "run", atMs = now))

        assertEquals(0, AutoArchive.run(store, 30, now = now))
        assertEquals(1, reads)
        assertEquals(0, AutoArchive.run(store, 30, now = now))
        assertEquals(1, reads)

        store.updateTurn("fresh", "run") { it.copy(atMs = now - 40 * day) }
        val afterUpdate = reads
        assertEquals(1, AutoArchive.run(store, 30, now = now))
        assertEquals(afterUpdate + 1, reads)
        assertEquals(now, store.archivedAt("fresh"))
    }

    @Test fun changedLegacyFileTimeInvalidatesCachedFallback() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        val store = ConversationStore(dir)
        store.append("legacy", Turn("旧格式", "好", "succeeded", atMs = now))
        val legacy = File(dir, "legacy.json")
        legacy.writeText(legacy.readText().replace(Regex(",\\\"atMs\\\":\\d+"), ""))
        assertTrue(legacy.setLastModified(now))
        assertEquals(0, AutoArchive.run(store, 30, now = now))
        assertTrue(legacy.setLastModified(now - 40 * day))
        assertEquals(1, AutoArchive.run(store, 30, now = now))
    }

    @Test fun messageTimeSurvivesUpdatesAndBranchesWhileLegacyFallsBackToFileTime() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        val store = ConversationStore(dir)
        store.append("old", Turn("旧消息", "好", "succeeded", runId = "run", atMs = now - 40 * day))
        store.updateTurn("old", "run") { it.copy(outcome = "后台补写") }
        assertTrue(store.branch("old", "branch", 0))
        store.append("legacy", Turn("旧格式", "好", "succeeded", atMs = now - 40 * day))
        val legacy = File(dir, "legacy.json")
        legacy.writeText(legacy.readText().replace(Regex(",\\\"atMs\\\":\\d+"), ""))
        assertTrue(legacy.setLastModified(now - 40 * day))
        store.append("fresh", Turn("新消息", "好", "succeeded", atMs = now))

        assertEquals(3, AutoArchive.run(store, days = 30, now = now))
        listOf("old", "branch", "legacy").forEach { assertEquals(now, store.archivedAt(it)) }
        assertNull(store.archivedAt("fresh"))
    }

    /** When off (0 days) it does not run even once. */
    @Test fun runDoesNothingWhenOff() {
        val dir = tmp.newFolder()
        val store = ConversationStore(dir)
        store.append("old", Turn("很久以前", "好", "succeeded"))
        File(dir, "old.json").setLastModified(System.currentTimeMillis() - 400 * day)

        assertEquals(0, AutoArchive.run(store, days = 0))
        assertNull(store.archivedAt("old"))
        assertNotNull(ConversationStore(dir).list().singleOrNull { it.id == "old" })
    }
}
