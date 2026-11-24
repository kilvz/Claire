package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeHints
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.NoticeTriage
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriagePlanner
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After an App is blocked in "App access": its collected records, items awaiting triage, alerts and events awaiting learning are all removed, requests in flight are cancelled, and nothing from it is sent again. */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedAppsTest {
    private class Fake(var reply: String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val packages = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val text = (messages.single().parts.single() as Part.Text).text
            packages += Regex("App package: (\\S+)").find(text)!!.groupValues[1]
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        override fun load() = data
        override fun save(data: TopicPoolData) { this.data = data }
        override fun dropBroken() {}
        override fun wipe() { data = TopicPoolData() }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(val scope: TestScope) {
        val allowed = setOf("com.chat", "com.other")
        val blocked = HashSet<String>()
        /** Whether "App access" can be read. */
        var known = true
        val gate get() = AppGate(known, blocked.toSet())
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        val hints = NoticeHints()
        val judge = Fake("""{"items":[{"id":"n1","decision":"surface","reason":"r"},{"id":"n2","decision":"surface","reason":"r"}]}""")
        val distil = Fake("""{"signals":[]}""")
        private fun hooks(m: ChatModel) = TriageHooks(
            target = { target }, model = { TargetedModel(target, m) },
            tokensToday = { 0 }, callsToday = { 0 }, usageVersion = { MutableStateFlow(0L) }, busy = busy,
        )
        val triage = TriageRunner(
            inbox, hooks(judge), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            clock = { scope.testScheduler.currentTime }, planner = TriagePlanner(zone = { ZoneOffset.UTC }),
            suspended = { !known },
        )
        val learn = LearnRunner(
            hooks(distil), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            eligible = { BlockedApps.learnEligible(it, allowed, emptySet(), blocked) },
            suspended = { !known },
            sensitive = { false }, store = MemStore(),
            clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )

        init {
            scope.backgroundScope.launch { triage.watch(locked) { locked.value } }
            scope.backgroundScope.launch { learn.watch(locked) { locked.value } }
            scope.runCurrent()
        }

        private var n = 0
        /** Accept one record the way ProactiveRuntime.admit does: into the inbox and into the learning buffer. */
        fun post(pkg: String) {
            n++
            val r = NoticeRecord("$pkg-$n", pkg, scope.testScheduler.currentTime + n, "title $n", "text $n")
            if (BlockedApps.readable(pkg, allowed, gate) && BlockedApps.admits(r, allowed, gate)) {
                inbox.add(r)
                learn.capture(r)
            }
            scope.runCurrent()
        }

        fun syncHints() = hints.sync(inbox.state.value.records, triage.state.value.marks, true)

        fun block(pkg: String) {
            blocked += pkg
            changed()
        }

        /** "App access" has become unreadable / readable again. */
        fun access(known: Boolean) {
            this.known = known
            changed()
        }

        /** Do what ProactiveRuntime does on receiving "App access changed". */
        private fun changed() {
            BlockedApps.accessChanged(gate, inbox, triage, learn)
            scope.runCurrent()
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun learnEligibilityHonoursTheBlockedSet() {
        assertTrue(BlockedApps.learnEligible("com.chat", setOf("com.chat"), emptySet(), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.chat", setOf("com.chat"), emptySet(), setOf("com.chat")))
        assertFalse(BlockedApps.learnEligible("com.chat", setOf("com.chat"), setOf("com.chat"), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.chat", emptySet(), emptySet(), emptySet()))
        assertFalse(BlockedApps.learnEligible("com.wangzan101.mika", setOf("com.wangzan101.mika"), emptySet(), emptySet()))
    }

    @Test fun blockingPurgesQueuedRecordsHintsAndEvents() = runTest {
        val e = Env(this)
        // First there is one record triaged as worth an alert, so it is in the alert list
        e.post("com.chat")
        wait(TriagePlanner.WINDOW_MS)
        e.syncHints()
        assertEquals(1, e.hints.state.value.list.size)
        // Then a few more awaiting triage and learning
        e.post("com.chat"); e.post("com.other")
        assertEquals(2, e.triage.state.value.counts.pending)
        assertEquals(3, e.learn.state.value.pending)
        e.block("com.chat")
        assertTrue(e.inbox.state.value.records.none { it.packageName == "com.chat" })
        assertEquals(1, e.triage.state.value.counts.pending)
        assertTrue(e.triage.state.value.marks.keys.none { it.startsWith("com.chat") })
        e.syncHints()
        assertTrue(e.hints.state.value.list.isEmpty())
        assertEquals(1, e.learn.state.value.pending)
        assertEquals(1, e.learn.state.value.buffered)
        // A batch fills up and comes due, and learning is due: only the other App's records are sent
        wait(TriagePlanner.WINDOW_MS)
        wait(LearnTuning.DUE_AGE_MS)
        e.post("com.other")
        assertEquals(listOf("com.chat", "com.other"), e.judge.packages)
        assertTrue(e.distil.packages.none { it == "com.chat" })
    }

    @Test fun blockingCancelsRequestsInFlightForThatApp() = runTest {
        val e = Env(this)
        e.judge.gate = CompletableDeferred()
        e.distil.gate = CompletableDeferred()
        repeat(LearnTuning.DUE_COUNT) { e.post("com.chat") }
        assertTrue(e.learn.requestActive)
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        e.block("com.chat")
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(1, e.judge.cancelled)
        assertEquals(1, e.distil.cancelled)
        assertEquals(0, e.learn.state.value.buffered)
        assertTrue(e.inbox.state.value.records.isEmpty())
        // Even when let through, they yield no result and nothing is sent again
        e.judge.gate!!.complete(Unit)
        e.distil.gate!!.complete(Unit)
        wait(24 * 3_600_000L)
        assertTrue(e.triage.state.value.marks.isEmpty())
        assertEquals(listOf("com.chat"), e.judge.packages)
        assertEquals(listOf("com.chat"), e.distil.packages)
    }

    @Test fun blockingAnotherAppLeavesTheRequestAlone() = runTest {
        val e = Env(this)
        e.judge.gate = CompletableDeferred()
        e.post("com.chat")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        e.block("com.other")
        assertTrue(e.triage.requestActive)
        e.judge.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, e.triage.state.value.marks.size)
    }

    // ---- The "App access" settings cannot be read: which Apps must not be touched is unknown, so everything stays suspended and what has accumulated is left as is ----

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    @Test fun unreadableAppAccessReadsNoNotifications() {
        val broken = java.io.File(tmp.root, "broken.json").apply { writeText("{broken") }
        val state = com.wangzan101.mika.settings.AppAccessStore(broken).current()
        assertFalse(state.available)
        // When it cannot be read, the "blocked" set is empty: relying on it alone would look like nothing is blocked
        assertTrue(state.blocked.isEmpty())
        val unknown = AppGate.of(state)
        assertFalse(unknown.known)
        val r = NoticeRecord("k", "com.chat", 1L, "title", "text")
        assertFalse(BlockedApps.readable("com.chat", setOf("com.chat"), unknown))
        assertFalse(BlockedApps.admits(r, setOf("com.chat"), unknown))
        // A missing file is readable (nothing is blocked)
        val fresh = com.wangzan101.mika.settings.AppAccessStore(java.io.File(tmp.root, "none.json")).current()
        val known = AppGate.of(fresh)
        assertTrue(known.known)
        assertTrue(BlockedApps.readable("com.chat", setOf("com.chat"), known))
        assertTrue(BlockedApps.admits(r, setOf("com.chat"), known))
        assertFalse(BlockedApps.readable("com.chat", setOf("com.chat"), known.copy(blocked = setOf("com.chat"))))
    }

    @Test fun unreadableAppAccessStopsRequestsAndKeepsWhatIsQueued() = runTest {
        val e = Env(this)
        // This batch has DUE_COUNT records: the triage reply gives every one of them a verdict (the default fake reply only judges the first two, and the rest count as failed and are sent again)
        e.judge.reply = (1..LearnTuning.DUE_COUNT).joinToString(",", """{"items":[""", "]}") { """{"id":"n$it","decision":"ignore","reason":"r"}""" }
        e.judge.gate = CompletableDeferred()
        e.distil.gate = CompletableDeferred()
        repeat(LearnTuning.DUE_COUNT) { e.post("com.chat") }
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.triage.requestActive)
        assertTrue(e.learn.requestActive)
        val records = e.inbox.state.value.records.size
        assertEquals(LearnTuning.DUE_COUNT, records)
        val buffered = e.learn.state.value.buffered

        e.access(known = false)
        // Both requests in flight have stopped; not a single inbox record or item awaiting learning is lost
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(1, e.judge.cancelled)
        assertEquals(1, e.distil.cancelled)
        assertEquals(TriageStatus.Suspended, e.triage.state.value.status)
        assertEquals(LearnStatus.Suspended, e.learn.state.value.status)
        assertEquals(records, e.inbox.state.value.records.size)
        assertEquals(buffered, e.learn.state.value.buffered)
        assertEquals(buffered, e.learn.state.value.pending)
        // Notifications arriving meanwhile are not accepted; nothing is sent no matter how long it waits
        e.post("com.chat"); e.post("com.other")
        wait(24 * 3_600_000L)
        assertEquals(records, e.inbox.state.value.records.size)
        assertEquals(buffered, e.learn.state.value.buffered)
        assertEquals(1, e.judge.packages.size)
        assertEquals(1, e.distil.packages.size)

        // Readable again: work resumes and the earlier records are sent again
        e.judge.gate = null
        e.distil.gate = null
        e.access(known = true)
        wait(TriagePlanner.WINDOW_MS)
        // The suspended attempt does not count as a failure, and there is no other resend: learning resends its batch; triage takes at most MAX_BATCH per batch, so these go out in two batches
        assertTrue(LearnTuning.DUE_COUNT in NoticeTriage.MAX_BATCH + 1..2 * NoticeTriage.MAX_BATCH)
        assertEquals(listOf("com.chat", "com.chat", "com.chat"), e.judge.packages)
        assertEquals(listOf("com.chat", "com.chat"), e.distil.packages)
        val c = e.triage.state.value.counts
        assertEquals(0, c.pending)
        assertEquals(0, c.failed)
        assertEquals(records, c.judged)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(0, e.learn.state.value.feed.getValue("com.chat").failed)
    }

    // While it was unreadable the user reset it and blocked one App: once readable, that App is purged first and work resumes, so nothing from it is sent
    @Test fun whenAppAccessComesBackTheBlockedAppIsPurgedBeforeAnythingIsSent() = runTest {
        val e = Env(this)
        // No full batch yet and nothing due: everything is queued
        e.post("com.chat"); e.post("com.chat"); e.post("com.other")
        e.access(known = false)
        wait(24 * 3_600_000L)
        assertTrue(e.judge.packages.isEmpty())
        assertTrue(e.distil.packages.isEmpty())
        assertEquals(3, e.inbox.state.value.records.size)
        e.blocked += "com.chat"
        e.access(known = true)
        wait(TriagePlanner.WINDOW_MS)
        wait(LearnTuning.DUE_AGE_MS)
        e.post("com.other")
        assertTrue(e.inbox.state.value.records.none { it.packageName == "com.chat" })
        assertEquals(listOf("com.other"), e.judge.packages.distinct())
        assertEquals(listOf("com.other"), e.distil.packages.distinct())
    }
}
