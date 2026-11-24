package com.wangzan101.mika.proactive

import android.service.notification.NotificationListenerService
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.ContactDrop
import com.wangzan101.mika.core.proactive.ContactPoolData
import com.wangzan101.mika.core.proactive.ContactRelation
import com.wangzan101.mika.core.proactive.DigestData
import com.wangzan101.mika.core.proactive.DayDigest
import com.wangzan101.mika.core.proactive.DigestLine
import com.wangzan101.mika.core.proactive.DigestNote
import com.wangzan101.mika.core.proactive.LearnLevel
import com.wangzan101.mika.core.proactive.SourceTier
import com.wangzan101.mika.core.proactive.SpanKind
import com.wangzan101.mika.core.proactive.TopicPool
import com.wangzan101.mika.core.proactive.LearnDrop
import com.wangzan101.mika.core.proactive.DigestPool
import com.wangzan101.mika.core.proactive.SpanDigest
import com.wangzan101.mika.core.proactive.DigestLevel
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeJournal
import com.wangzan101.mika.core.proactive.NoticeEntry
import com.wangzan101.mika.core.proactive.ProactiveCall
import com.wangzan101.mika.core.proactive.NoticeJournalData
import com.wangzan101.mika.core.proactive.NoticeMessage
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.ObservedContact
import com.wangzan101.mika.core.proactive.ObservedTopic
import com.wangzan101.mika.core.proactive.TopicKind
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TopicPoolData
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageTarget
import com.wangzan101.mika.core.model.usage.ProactiveBudget
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnRunnerTest {
    private class Fake(var reply: () -> String = { """{"signals":[]}""" }) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val calls = mutableListOf<List<Message>>()
        val tools = mutableListOf<List<ToolSpec>>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        var error: Exception? = null
        /** When set, the next call ignores cancellation: after it is released it throws "budget exhausted" (used only once). */
        var late: CompletableDeferred<Unit>? = null
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls += messages
            this.tools += tools
            error?.let { throw it }
            late?.let { d ->
                late = null
                withContext(NonCancellable) { d.await() }
                throw ModelError.ProactiveBudgetExceeded(1, 1)
            }
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw e
            }
            // Segmented digest requests (level 1 of the daily notification digest) get a separate reply
            val text = (messages.single().parts.single() as Part.Text).text
            val out = when {
                text.startsWith("Language for notes") -> notesReply()
                isSpan(text, "Week") -> weekReply()
                isSpan(text, "Month") -> monthReply()
                text.startsWith("Language for the digest") -> dayReply()
                else -> reply()
            }
            return ModelReply(listOf(Part.Text(out)), StopReason.EndTurn, null)
        }
        var notesReply: () -> String = { """{"notes":[]}""" }
        /** The reply to daily digest requests (level 2). */
        var dayReply: () -> String = { """{"lines":[]}""" }
        /** The reply to weekly digest (level 3) and monthly digest (level 4) requests. */
        var weekReply: () -> String = { """{"lines":[]}""" }
        var monthReply: () -> String = { """{"lines":[]}""" }
        /** The second line of the weekly and monthly digest input says which week or month it is. */
        private fun isSpan(text: String, what: String) = text.startsWith("Language for the digest") && text.lineSequence().drop(1).firstOrNull()?.startsWith("$what: ") == true
        /** Which kind the [i]th request was: learn (topic digest), notes (segmented digest), day (daily digest), week, month. */
        fun kind(i: Int) = when {
            input(i).startsWith("Language for notes") -> "notes"
            isSpan(input(i), "Week") -> "week"
            isSpan(input(i), "Month") -> "month"
            input(i).startsWith("Language for the digest") -> "day"
            else -> "learn"
        }
        val kinds get() = calls.indices.map(::kind)
        fun isNotes(i: Int) = input(i).startsWith("Language for notes")
        fun input(i: Int) = (calls[i].single().parts.single() as Part.Text).text
        fun packages() = calls.indices.map { Regex("App package: (\\S+)").find(input(it))!!.groupValues[1] }
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        var saves = 0
        var failLoad = false
        var failSave = false
        /** How many old copies were moved aside, and how many times everything was deleted. */
        var brokenCopies = 0
        var wipes = 0
        override fun load(): TopicPoolData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        var onSave: () -> Unit = {}
        override fun save(data: TopicPoolData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++; onSave()
        }
        override fun dropBroken() { brokenCopies = 0 }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = TopicPoolData(); brokenCopies = 0; wipes++
        }
    }

    private class MemJournal : NoticeJournalStore {
        var data: NoticeJournalData? = null
        var saves = 0
        var wipes = 0
        var failLoad = false
        var failSave = false
        var failWipe = false
        override fun load(): NoticeJournalData? {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        override fun save(data: NoticeJournalData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++
        }
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = null; wipes++
        }
        val texts get() = data?.entries.orEmpty().map { it.text }
    }

    private class MemDigest : DigestStore {
        var data = DigestData()
        var saves = 0
        var wipes = 0
        var failLoad = false
        /** The save step fails (storage full and the like); [saveAttempts] counts the failed ones too. */
        var failSave = false
        var saveAttempts = 0
        override fun load(): DigestData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        override fun save(data: DigestData) {
            saveAttempts++
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++
        }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = DigestData(); wipes++
        }
        val texts get() = data.notes.map { it.text }
    }

    private class MemPurges : LearnPurges {
        /** How many writes happened (each write is one preferences commit). */
        var writes = 0
        /** What to run after the next write of the topic set (only once): simulates the process dying right after that write. */
        var afterTopicWrite: (() -> Unit)? = null
        override var topicPurges: Set<String> = emptySet()
            set(v) { field = v; writes++; afterTopicWrite?.also { afterTopicWrite = null }?.invoke() }
        override var contactPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
        override var digestPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
        override var journalPurges: Set<String> = emptySet()
            set(v) { field = v; writes++ }
    }

    private class MemContacts : ContactPoolStore {
        var data = ContactPoolData()
        var saves = 0
        var failLoad = false
        var failSave = false
        var brokenCopies = 0
        var wipes = 0
        override fun load(): ContactPoolData {
            if (failLoad) throw java.security.KeyStoreException("busy")
            return data
        }
        var onSave: () -> Unit = {}
        override fun save(data: ContactPoolData) {
            if (failSave) throw java.io.IOException("disk")
            this.data = data; saves++; onSave()
        }
        override fun dropBroken() { brokenCopies = 0 }
        var failWipe = false
        override fun wipe() {
            if (failWipe) throw java.io.IOException("could not delete")
            data = ContactPoolData(); brokenCopies = 0; wipes++
        }
    }

    private val target = TriageTarget("p1", "Provider", "m1")
    private val start = 20_000 * LearnTuning.DAY_MS

    private inner class Env(val scope: TestScope) {
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        var on = true
        var sensitive = false
        val contacts = MemContacts()
        var consent: TriageConsent? = TriageConsent.of(target)
        var effective: TriageTarget? = target
        /** The target read when the model was built (null means the same as [effective]). */
        var built: TriageTarget? = null
        val model = Fake()
        val store = MemStore()
        var calls = 0L
        /** The model-build step (which decrypts the Key) fails. */
        var buildFails = false
        /** Reading "which model is configured now" fails (the configuration is temporarily unreadable). */
        var targetFails = false
        /** Reading the lock state fails. */
        var lockReadFails = false
        var builds = 0
        /** Which kind of request is announced each time the model is built (the reply size limit follows from it). */
        val asked = mutableListOf<ProactiveCall>()
        val purges = MemPurges()
        /** The "daily notification digest" switch. */
        var digest = false
        val journal = MemJournal()
        val digestStore = MemDigest()
        val optOut = HashSet<String>()
        /** "App access" cannot be read. */
        var suspended = false
        val runner = LearnRunner(
            TriageHooks(
                target = { if (targetFails) throw IllegalStateException("temporary configuration read failure"); effective },
                model = {
                    builds++
                    asked += it
                    if (buildFails) throw java.security.KeyStoreException("busy")
                    (built ?: effective)?.let { TargetedModel(it, model) }
                },
                tokensToday = { 0 }, callsToday = { calls },
                usageVersion = { MutableStateFlow(0L) }, busy = busy,
            ),
            on = { on }, consent = { consent }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            language = { "English" },
            eligible = { it !in optOut },
            suspended = { suspended },
            sensitive = { sensitive },
            store = store,
            contactStore = contacts, purges = purges,
            digest = { digest }, journalStore = journal, digestStore = digestStore,
            appLabel = { "App ${it.substringAfterLast('.')}" },
            clock = { now() }, zone = { ZoneOffset.UTC },
        )

        fun now() = start + scope.testScheduler.currentTime

        init {
            scope.backgroundScope.launch { runner.watch(locked) { if (lockReadFails) throw IllegalStateException("lock state unavailable"); locked.value } }
            scope.runCurrent()
        }

        private var n = 0
        fun post(pkg: String = "com.shop", count: Int = 1) {
            repeat(count) {
                n++
                runner.capture(NoticeRecord("k$n", pkg, now(), "title $n", "text $n"))
            }
            scope.runCurrent()
        }

        /** A conversation in a chat App sends [count] messages: the same key (the same tag reposted), the same title. */
        fun chat(key: String, title: String, count: Int = 1, pkg: String = "com.chat") {
            repeat(count) {
                n++
                runner.capture(NoticeRecord(key, pkg, now(), title, "message $n"))
            }
            scope.runCurrent()
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun newTopic(vararg ids: String, label: String = "Hiking") =
        """{"signals":[{"ids":[${ids.joinToString(",") { "\"$it\"" }}],"new":{"kind":"interest","label":"$label"}}]}"""

    @Test fun notDueSendsNothingAndThereIsNoTimer() = runTest {
        val e = Env(this)
        e.post(count = LearnTuning.DUE_COUNT - 1)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // 30 minutes and then hours pass with nothing happening: nothing is sent (there is no timer)
        wait(5 * 3_600_000L)
        assertTrue(e.model.calls.isEmpty())
        // It only looks when something happens (another App's notification arrives): by then the oldest one is long past 30 minutes
        e.post(pkg = "com.other")
        assertEquals(1, e.model.calls.size)
        assertEquals(listOf("com.shop"), e.model.packages())
    }

    @Test fun twelveEventsMakeOneRequestWithNoTools() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.tools.single().isEmpty())
        assertEquals(LearnTuning.BATCH_MAX, Regex("\\[n\\d+]").findAll(e.model.input(0)).count())
        val s = e.runner.state.value
        assertEquals(LearnStatus.Idle, s.status)
        assertEquals(0, s.pending)
        assertEquals(e.now(), s.lastDistilledAtMs)
        val f = s.feed.getValue("com.shop")
        assertEquals(LearnFeed(captured = 12, distilled = 12, batches = 1, accepted = 1), f)
        assertEquals(1, e.store.saves)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals(setOf("com.shop"), e.store.data.topics.single().evidence.keys)
        assertEquals(listOf("Hiking"), e.runner.topics.value.map { it.label })
    }

    @Test fun lockedSendsNothingCancelsInFlightAndKeepsEvents() = runTest {
        val e = Env(this)
        e.locked.value = true
        runCurrent()
        e.post(count = 12)
        assertEquals(LearnStatus.Locked, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        // It sends after unlocking; locking while sending cancels it and the events stay
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(0, e.runner.state.value.feed.getValue("com.shop").failed)
        // Unlock again: the same batch is sent again
        e.model.gate = null
        e.model.reply = { newTopic("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.pending)
    }

    // Locking cancelled the request but the model ignored the cancellation and returned later; meanwhile a new batch is already being sent after unlocking: the late return must not change anything
    @Test fun staleRequestReturningLateDoesNotTouchTheNewerBatch() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        val late = CompletableDeferred<Unit>()
        e.model.late = late
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        late.complete(Unit)
        runCurrent()
        assertTrue("the new batch is still being sent", e.runner.requestActive)
        assertEquals(LearnStatus.Sending, e.runner.state.value.status)
        assertEquals(0, e.runner.state.value.pending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals("a late \"budget exhausted\" does not count", LearnStatus.Idle, e.runner.state.value.status)
    }

    @Test fun busyWaits() = runTest {
        val e = Env(this)
        e.busy.value = true
        runCurrent()
        e.post(count = 12)
        assertEquals(LearnStatus.Busy, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        e.busy.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // The model replied but the reply is unreadable: this batch is dropped without retry; the next batch waits out a hold before sending (no timer: once the hold is over it waits for the next event)
    @Test fun unreadableReplyDropsTheBatchAndHoldsTheNextOne() = runTest {
        val e = Env(this)
        e.model.reply = { "I cannot help with that." }
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(0, e.runner.state.value.buffered)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        // Another full batch is ready at once: it stays held and nothing is sent
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        assertEquals(12, e.runner.state.value.pending)
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(1, e.model.calls.size)
        // Even once the hold is over there is no timer; it sends only when something happens
        wait(LearnTuning.failureHoldMs(1))
        assertEquals(1, e.model.calls.size)
        e.model.reply = { newTopic("n1") }
        e.post(pkg = "com.other")
        assertEquals(2, e.model.calls.size)
        assertEquals(listOf("com.shop", "com.shop"), e.model.packages())
        // After one success it stops holding: another App with a full batch sends right away
        e.post(pkg = "com.other", count = 11)
        assertEquals(3, e.model.calls.size)
    }

    // Once the model has a problem, the due Apps are not sent batch after batch only to fail
    @Test fun oneFailureDoesNotCascadeAcrossApps() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.locked.value = true
        runCurrent()
        e.post(pkg = "com.a", count = 12)
        e.post(pkg = "com.b", count = 12)
        e.post(pkg = "com.c", count = 12)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertEquals(24, e.runner.state.value.pending)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // With consecutive failures the hold grows step by step: 2, 5, 15, 30 minutes, then always 30
        var expected = 1
        for (hold in listOf(2, 5, 15, 30, 30)) {
            wait(hold * 60_000L - 1)
            e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
            assertEquals("nothing is sent before the full $hold minutes", expected, e.model.calls.size)
            wait(1)
            e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
            if (expected < 3) expected++
            assertEquals(expected, e.model.calls.size)
        }
        // Three Apps each lost one batch; afterwards there is nothing left to send
        assertEquals(0, e.runner.state.value.buffered)
    }

    // The model is not reachable at all (it is only on the home network and the person went out): the batch is kept and retried after a hold; once connected it is learned
    @Test fun unreachableModelKeepsTheBatchAndRetriesLater() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        assertFalse(e.runner.requestActive)
        e.post()
        assertEquals(1, e.model.calls.size)
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.model.calls.size)
        assertEquals(14, e.runner.state.value.pending)
        // The second failure holds for 5 minutes
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.model.calls.size)
        // Connected: the same batch (the oldest 12 entries) goes out and is learned
        e.model.error = null
        e.model.reply = { newTopic("n1") }
        wait(LearnTuning.failureHoldMs(2))
        e.post()
        assertEquals(3, e.model.calls.size)
        assertEquals(e.model.input(0).substringAfter("Notifications"), e.model.input(2).substringAfter("Notifications"))
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertEquals(4, e.runner.state.value.pending)
        assertEquals(12, e.runner.state.value.feed.getValue("com.shop").distilled)
    }

    // Building the model (which decrypts the Key) fails: that is not this batch's fault, so it is kept; a hold applies too, so it does not fail again on every new notification
    @Test fun modelBuildFailureKeepsTheBatchAndHolds() = runTest {
        val e = Env(this)
        e.buildFails = true
        e.post(count = 12)
        assertEquals(1, e.builds)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        e.post(count = 5)
        assertEquals(1, e.builds)
        e.buildFails = false
        e.model.reply = { newTopic("n1") }
        wait(LearnTuning.failureHoldMs(1))
        e.post()
        assertEquals(2, e.builds)
        assertEquals(1, e.model.calls.size)
        assertEquals(6, e.runner.state.value.pending)
    }

    // The user opens the learn page: the hold is lifted and anything due is retried at once (usually right after a settings change)
    @Test fun openingThePageLiftsTheHold() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        e.model.error = null
        e.model.reply = { newTopic("n1") }
        e.runner.open()
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.pending)
        assertEquals(LearnStatus.Idle, e.runner.state.value.status)
    }

    // Turning learning off and on again: it starts from scratch without the previous hold
    @Test fun switchingOffForgetsTheHold() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        e.on = false
        e.runner.clear(); e.runner.poke()
        e.on = true
        e.runner.poke()
        e.model.error = null
        e.post(count = 12)
        assertEquals(2, e.model.calls.size)
    }

    @Test fun timeoutCountsAsFailure() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = 12)
        wait(LearnTuning.TIMEOUT_MS)
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").failed)
        assertEquals(0, e.runner.state.value.buffered)
        assertEquals(1, e.model.calls.size)
    }

    @Test fun budgetExhaustedIsQuietAndKeepsEvents() = runTest {
        val e = Env(this)
        e.model.error = ModelError.ProactiveBudgetExceeded(1, 1)
        e.post(count = 12)
        assertEquals(1, e.model.calls.size)
        assertEquals(LearnStatus.BudgetUsedUp, e.runner.state.value.status)
        assertEquals(12, e.runner.state.value.pending)
        // Nothing more is sent that day
        e.post()
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(1, e.model.calls.size)
        // Nothing is sent when the ledger has reached its limit either
        val f = Env(this)
        // The request-count limit follows the token limit; a digest counts as a whole
        f.calls = ProactiveBudget.callLimit(ProactiveBudget.DEFAULT_DAILY_LIMIT)
        f.post(count = 12)
        assertTrue(f.model.calls.isEmpty())
        assertEquals(LearnStatus.BudgetUsedUp, f.runner.state.value.status)
    }

    @Test fun consentMismatchStops() = runTest {
        val e = Env(this)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.post(count = 12)
        assertEquals(LearnStatus.NeedsConsent, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        // The model is switched while sending: the result is discarded and the events are kept
        e.effective = target
        e.model.gate = CompletableDeferred()
        e.runner.poke(); runCurrent()
        assertEquals(1, e.model.calls.size)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.model.reply = { newTopic("n1") }
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
        assertEquals(LearnStatus.NeedsConsent, e.runner.state.value.status)
    }

    // Consent at the gate was for the agreed model, but the configuration changed to another by the time the model is built: nothing is sent and the events stay pending
    @Test fun targetChangedBetweenTheGateAndTheBuildSendsNothing() = runTest {
        val e = Env(this)
        e.built = target.copy(providerId = "p2")
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue("nothing was sent", e.model.calls.isEmpty())
        assertFalse(e.runner.requestActive)
        val s = e.runner.state.value
        assertEquals(LearnStatus.NeedsConsent, s.status)
        assertEquals(LearnTuning.DUE_COUNT, s.pending)
        assertEquals(0, s.feed.getValue("com.shop").batches)
        e.built = null
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun optedOutAppIsNeverSent() = runTest {
        val e = Env(this)
        e.optOut += "com.chat"
        e.post(pkg = "com.chat", count = 20)
        assertEquals(0, e.runner.state.value.buffered)
        e.post(pkg = "com.shop", count = 5)
        // Captured first, then turned off: what was captured is dropped
        e.optOut += "com.shop"
        e.runner.poke(); runCurrent()
        assertEquals(0, e.runner.state.value.buffered)
        wait(3_600_000)
        e.post(pkg = "com.chat")
        assertTrue(e.model.calls.isEmpty())
    }

    @Test fun switchOffClearsAndCapturesNothing() = runTest {
        val e = Env(this)
        e.post(count = 3)
        e.on = false
        e.runner.clear(); e.runner.poke(); runCurrent()
        assertEquals(LearnStatus.Off, e.runner.state.value.status)
        assertEquals(0, e.runner.state.value.buffered)
        e.post(count = 12)
        assertEquals(0, e.runner.state.value.buffered)
        assertTrue(e.model.calls.isEmpty())
    }

    // The pool holds more topics than the slots shown to the model: the one this batch's App has evidence for is shown even when weak (so the model can cite it when it proposes the same thing again;
    // otherwise it can only start another one with a similar name); for other Apps' batches it is still picked by strength alone, so it gets no slot
    @Test fun theAppsOwnTopicsAreShownToTheModelEvenWhenWeak() = runTest {
        val e = Env(this)
        val today = 20_000L
        val old = start - 5 * LearnTuning.DAY_MS
        e.store.data = TopicPoolData(topics =
            (0 until LearnTuning.EXISTING_MAX + 10).map {
                ObservedTopic("kn$it", TopicKind.Interest, "News $it", start, start, mapOf("com.news" to listOf(today - 2, today - 1, today)))
            } + ObservedTopic("kshop", TopicKind.Shopping, "Tents", old, old, mapOf("com.shop" to listOf(today - 5))))
        e.post(count = 12)
        val lines = e.model.input(0).lines()
        assertEquals(LearnTuning.EXISTING_MAX, lines.count { it.contains("] kind: ") })
        // It comes first (the topic name is on the line below, inside the untrusted block)
        assertEquals("Tents", lines[lines.indexOfFirst { it.contains("] kind: ") } + 1])
        e.post(pkg = "com.other", count = 12)
        assertEquals(listOf("com.shop", "com.other"), e.model.packages())
        assertFalse(e.model.input(1).contains("Tents"))
    }

    @Test fun lateOutcomeReachesTheTopic() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        e.runner.removed("k1", NotificationListenerService.REASON_CLICK)
        e.runner.removed("k2", NotificationListenerService.REASON_CANCEL_ALL)
        assertEquals(1, e.store.data.topics.single().opened)
        assertEquals(2, e.store.saves)
    }

    // With "sensitive-category inference" off, notifications assigned earlier to a sensitive topic are only now tapped: nothing is added back
    @Test fun lateOutcomeSkipsSensitiveTopicsWhenTheSwitchIsOff() = runTest {
        val e = Env(this)
        e.sensitive = true
        e.model.reply = { """{"signals":[{"ids":["n1","n2"],"new":{"kind":"interest","label":"Diabetes","sensitive":true}}]}""" }
        e.post(count = 12)
        assertTrue(e.store.data.topics.single().sensitive)
        e.sensitive = false
        val saves = e.store.saves
        e.runner.removed("k1", NotificationListenerService.REASON_CLICK)
        assertEquals(0, e.store.data.topics.single().opened)
        assertEquals(saves, e.store.saves)
        // Turn the switch back on: later taps are recorded as usual
        e.sensitive = true
        e.runner.removed("k2", NotificationListenerService.REASON_CLICK)
        assertEquals(1, e.store.data.topics.single().opened)
    }

    @Test fun unreadablePoolIsNotOverwritten() = runTest {
        val e = Env(this)
        e.store.failLoad = true
        e.post(count = 12)
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.store.saves)
        assertEquals(12, e.runner.state.value.pending)
        e.store.failLoad = false
        e.post()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun sensitiveSwitch() = runTest {
        val e = Env(this)
        val flagged = """{"signals":[{"ids":["n1"],"new":{"kind":"interest","label":"Diabetes","sensitive":true}}]}"""
        e.model.reply = { flagged }
        e.post(count = 12)
        assertTrue(e.model.input(0).contains("Sensitive topics: not allowed"))
        assertTrue(e.store.data.topics.isEmpty())
        e.sensitive = true
        e.post(count = 12)
        assertTrue(e.model.input(1).contains("Sensitive topics: allowed"))
        assertTrue(e.store.data.topics.single().sensitive)
        // Turn it off and choose delete: they are deleted and saved
        e.sensitive = false
        val saves = e.store.saves
        e.runner.deleteSensitive()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(saves + 1, e.store.saves)
    }

    // ---- Contacts and relations ----

    private val aliceReply =
        """{"signals":[{"ids":["n1","n2"],"new":{"kind":"interest","label":"Hiking"}}],"contacts":[{"ids":["n1","n2","n3"],"type":"person","relation":"friend","name":"Mallory"}]}"""

    @Test fun contactsRideOnTheSameSingleRequest() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        e.chat("0|com.chat|7|null|1", "Alice", count = 6)
        e.chat("0|com.chat|8|null|1", "Promo", count = 6)
        // As in the first part: 12 entries still make only one request
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.input(0).lines().contains("Contacts: on"))
        val c = e.contacts.data.contacts.single()
        // The name comes from the notification title, not from the model
        assertEquals("Alice", c.name)
        assertEquals("com.chat", c.packageName)
        assertEquals(listOf(e.store.data.topics.single().id), c.topics)
        val f = e.runner.state.value.feed.getValue("com.chat")
        assertEquals(1, f.contactsAccepted)
        assertEquals(0, f.contactDroppedTotal)
        assertEquals(listOf("Alice"), e.runner.contactsView.value.contacts.map { it.name })
        // Only hashes of the key and title, never the raw key
        assertFalse(c.id.contains("com.chat") || c.id.contains("Alice"))
    }

    // Topics are saved before contacts: at the moment contacts are saved, the topics they link to are already in the saved topic pool
    @Test fun topicsAreSavedBeforeTheContactsThatLinkToThem() = runTest {
        val e = Env(this)
        val order = ArrayList<String>()
        var danglingAtSave: Set<String>? = null
        e.store.onSave = { order += "topics" }
        e.contacts.onSave = {
            order += "contacts"
            val saved = e.store.data.topics.map { it.id }.toSet()
            danglingAtSave = e.contacts.data.contacts.flatMap { it.topics }.toSet() - saved
        }
        e.model.reply = { aliceReply }
        e.chat("0|com.chat|7|null|1", "Alice", count = 6)
        e.chat("0|com.chat|8|null|1", "Promo", count = 6)
        assertEquals(listOf("topics", "contacts"), order)
        assertEquals(emptySet<String>(), danglingAtSave)
        assertEquals(1, e.contacts.data.contacts.single().topics.size)
    }

    @Test fun optedOutAppRecordsNoContacts() = runTest {
        val e = Env(this)
        e.optOut += "com.chat"
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 20)
        wait(3_600_000)
        e.post(pkg = "com.chat")
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.contacts.saves)
    }

    @Test fun consentMismatchDiscardsContactsToo() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        e.effective = TriageTarget("p2", "Other", "m2")
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(12, e.runner.state.value.pending)
    }

    @Test fun pageActionsOnContacts() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        val id = e.contacts.data.contacts.single().id
        e.runner.setContactRelation(id, ContactRelation.Family)
        assertEquals(ContactRelation.Family, e.contacts.data.contacts.single().userRelation)
        // Deleting a topic: its links on contacts are removed too
        e.runner.deleteTopic(e.store.data.topics.single().id)
        assertTrue(e.contacts.data.contacts.single().topics.isEmpty())
        // Deleting a contact: the same conversation is not recorded again
        e.runner.deleteContact(id)
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(1, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(1, e.runner.state.value.feed.getValue("com.chat").contactDropped[ContactDrop.Blocked])
        e.runner.allowDeletedContacts()
        assertEquals(0, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.contacts.data.contacts.size)
        // Clearing one App and clearing all: contacts are removed too, but not blocked
        e.runner.clearApp("com.chat")
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(0, e.runner.contactsView.value.blocked)
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.contacts.data.contacts.size)
        e.runner.clearAll()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(0, e.runner.contactsView.value.blocked)
    }

    // Merging one topic into another on the topics page: only the target survives on disk, the evidence moves over, and contact links are repointed to it; notifications assigned earlier to the merged-away topic
    // that are opened afterwards count on it; when the model starts the merged-away name again, no new topic is made. Merging is not deleting: the name does not go into "never record again" and no pending delete is recorded
    @Test fun mergingATopicMovesItsEvidenceLinksAndName() = runTest {
        val e = Env(this)
        e.model.reply = { aliceReply }
        repeat(12) { e.runner.capture(NoticeRecord("a$it", "com.chat", e.now(), "Alice", "message $it")) }
        runCurrent()
        val hiking = e.store.data.topics.single().id
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val trails = e.store.data.topics.single { it.label == "Trails" }.id
        e.runner.mergeTopic(hiking, trails)
        val merged = e.store.data.topics.single()
        assertEquals(trails, merged.id)
        assertEquals("Trails", merged.label)
        assertEquals(setOf("com.chat", "com.shop"), merged.evidence.keys)
        assertEquals(listOf(trails), e.runner.topics.value.map { it.id })
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertEquals(listOf(trails), e.runner.contactsView.value.contacts.single().topics)
        assertTrue(e.store.data.blocked.isEmpty())
        assertEquals(0, e.runner.topicsBlocked.value)
        // Both were saved: nothing is recorded as pending replay
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
        // The chat notification assigned to "Hiking" is only now tapped
        e.runner.removed("a0", NotificationListenerService.REASON_CLICK)
        assertEquals(1, e.store.data.topics.single().opened)
        // The model starts "Hiking" again: it counts on "Trails"
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        assertEquals(listOf("Trails"), e.store.data.topics.map { it.label })
        assertFalse(LearnDrop.Blocked in e.runner.state.value.feed.getValue("com.shop").dropped)
        // New process: what is stored is the merged shape, and the name is still recognised
        assertEquals(1, e.store.data.merged.size)
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), again.topics.value.map { it.id })
        // The same topic, or one that is gone: nothing is done and nothing is saved
        val saves = e.store.saves
        e.runner.mergeTopic(trails, trails)
        e.runner.mergeTopic(hiking, trails)
        assertEquals(saves, e.store.saves)
    }

    /** The two topics "Hiking" (linked to by Alice's conversation in the chat App) and "Trails". Returns both ids. */
    private fun TestScope.hikingAndTrails(e: Env): Pair<String, String> {
        e.model.reply = { aliceReply }
        repeat(12) { e.runner.capture(NoticeRecord("a$it", "com.chat", e.now(), "Alice", "message $it")) }
        runCurrent()
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single { it.label == "Hiking" }.id
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        return hiking to e.store.data.topics.single { it.label == "Trails" }.id
    }

    // The merge failed to save (storage full and the like), the user then deleted the merged result and that failed to save too, and the process died. On the next read: both topics are still in the file,
    // and both the merge and the delete must be replayed — replaying only the delete would bring the merged-away topic back with its evidence, and its name would not be in "never record again"
    @Test fun anUnsavedMergeIsRedoneBeforeTheDeleteThatFollowedIt() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.store.failSave = true
        e.runner.mergeTopic(hiking, trails)
        e.runner.deleteTopic(trails)
        assertEquals(2, e.store.data.topics.size)
        assertEquals(2, e.purges.topicPurges.size)
        e.store.failSave = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.topics.value.isEmpty())
        assertTrue(e.store.data.topics.isEmpty())
        // Both names went into "never record again"
        assertEquals(setOf(TopicPool.fingerprint("Hiking"), TopicPool.fingerprint("Trails")), e.store.data.blocked.toSet())
        assertTrue(e.purges.topicPurges.isEmpty())
        // Contacts no longer link to them
        assertTrue(e.contacts.data.contacts.single().topics.isEmpty())
    }

    // Only the merge failed to save and the process died: it is replayed on the next read without the user merging again; the merged-away name is still recognised
    @Test fun anUnsavedMergeIsRedoneWhenThePoolLoadsAgain() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.store.failSave = true
        e.runner.mergeTopic(hiking, trails)
        assertEquals(2, e.store.data.topics.size)
        e.store.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(setOf("com.chat", "com.shop"), e.store.data.topics.single().evidence.keys)
        assertEquals(mapOf(TopicPool.fingerprint("Hiking") to trails), e.store.data.merged)
        assertTrue(e.store.data.blocked.isEmpty())
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        // Once saved it is no longer recorded: another process neither redoes it nor saves extra
        val saves = e.store.saves
        newProcess(e).open()
        assertEquals(saves, e.store.saves)
    }

    // At the moment of the merge the contacts store cannot be read (the keystore is briefly unavailable), while the topic store merges and saves as usual. Once contacts can be read:
    // links to the merged-away topic are repointed to the target instead of being dropped as links pointing nowhere
    @Test fun contactLinksFollowAMergeMadeWhileContactsWereUnreadable() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.contacts.failLoad = true
        val later = newProcess(e)
        later.open()
        later.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        assertEquals(1, e.purges.contactPurges.size)
        e.contacts.failLoad = false
        later.open()
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertEquals(listOf(trails), later.contactsView.value.contacts.single().topics)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // The contacts store was changed but failed to save and the process died: it is replayed on the next read
    @Test fun contactLinksFollowAMergeWhoseContactSaveFailed() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.contacts.failSave = true
        e.runner.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // One merge is recorded on both the topic and contact stores. The process dies right after recording (neither file has changed yet): on the next read both are replayed and contact links follow the repoint
    @Test fun aMergeRecordedJustBeforeTheProcessDiedIsRedoneOnBothStores() = runTest {
        class Died : Error("process death")
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.purges.afterTopicWrite = { throw Died() }
        try {
            e.runner.mergeTopic(hiking, trails)
            org.junit.Assert.fail("the injected death should interrupt the merge")
        } catch (_: Died) {}
        assertEquals(2, e.store.data.topics.size)
        assertEquals(listOf(hiking), e.contacts.data.contacts.single().topics)
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(trails), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // For the same name, whichever of "delete" and "merge into another" came later wins, and the same holds for a replay after a failed save and process death:
    // deleting "Tea" (an interest) first, then merging another with the same name (shopping) into "Drinks" → "Tea" counts as merged away and is not in "never record again"
    @Test fun unsavedDeletesAndMergesAreRedoneInTheOrderTheUserMadeThem() = runTest {
        fun topic(id: String, kind: TopicKind, label: String) = ObservedTopic(id, kind, label, start, start, mapOf("com.shop" to listOf(20_000L)))
        val tea = TopicPool.fingerprint("Tea")
        val e = Env(this)
        e.store.data = TopicPoolData(topics = listOf(
            topic("k1", TopicKind.Interest, "Tea"), topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Routine, "Drinks")))
        e.runner.open()
        e.store.failSave = true
        e.runner.deleteTopic("k1")
        e.runner.mergeTopic("k3", "k4")
        assertEquals(0, e.runner.topicsBlocked.value)
        e.store.failSave = false
        newProcess(e).open()
        assertEquals(listOf("k4"), e.store.data.topics.map { it.id })
        assertEquals("k4", e.store.data.merged[tea])
        assertFalse(tea in e.store.data.blocked)
        // The other way round: merge one away first, then delete the other with the same name → "Tea" is never recorded again
        val f = Env(this)
        f.store.data = TopicPoolData(topics = listOf(
            topic("k1", TopicKind.Interest, "Tea"), topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Routine, "Drinks")))
        f.runner.open()
        f.store.failSave = true
        f.runner.mergeTopic("k3", "k4")
        f.runner.deleteTopic("k1")
        f.store.failSave = false
        newProcess(f).open()
        assertEquals(listOf("k4"), f.store.data.topics.map { it.id })
        assertTrue(f.store.data.merged.isEmpty())
        assertTrue(tea in f.store.data.blocked)
    }

    // Two topics with the same name are merged into two different targets one after the other: the name counts on the later target, and the replay must be correct whether applied forwards or backwards
    @Test fun theLaterOfTwoSameNameMergesWinsAfterAReplay() = runTest {
        fun topic(id: String, kind: TopicKind, label: String) = ObservedTopic(id, kind, label, start, start, mapOf("com.shop" to listOf(20_000L)))
        for (reverse in listOf(false, true)) {
            val e = Env(this)
            e.store.data = TopicPoolData(topics = listOf(
                topic("k1", TopicKind.Interest, "Tea"), topic("k2", TopicKind.Interest, "Coffee"),
                topic("k3", TopicKind.Shopping, "Tea"), topic("k4", TopicKind.Shopping, "Drinks")))
            e.runner.open()
            e.store.failSave = true
            val ops = listOf("k1" to "k2", "k3" to "k4").let { if (reverse) it.reversed() else it }
            ops.forEach { (from, into) -> e.runner.mergeTopic(from, into) }
            e.store.failSave = false
            newProcess(e).open()
            assertEquals("reverse=$reverse", ops.last().second, e.store.data.merged[TopicPool.fingerprint("Tea")])
        }
    }

    // The target is a topic just learned and not saved yet (storage full and the like): no merge happens. The mark holds only two ids, so if the process dies the target is not in the file
    // and this merge cannot be replayed; had it merged, the user would see it merged, yet after a restart the merged-away topic would be back and the target gone
    @Test fun aMergeIntoATopicThatIsNotSavedYetIsNotDone() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single().id
        e.store.failSave = true
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val trails = e.runner.topics.value.single { it.label == "Trails" }.id
        assertEquals(listOf(hiking), e.store.data.topics.map { it.id })
        e.runner.mergeTopic(hiking, trails)
        // Not merged: both are still there and nothing was recorded
        assertEquals(setOf(hiking, trails), e.runner.topics.value.map { it.id }.toSet())
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
        // Saving works again: the topic pool is saved first, then the merge happens
        e.store.failSave = false
        e.runner.mergeTopic(hiking, trails)
        assertEquals(listOf(trails), e.store.data.topics.map { it.id })
        assertEquals(listOf(trails), e.runner.topics.value.map { it.id })
        assertTrue(e.purges.topicPurges.isEmpty())
    }

    // Two merges in a row both failed to save: the replay follows their order and ends on the last target
    @Test fun unsavedMergesInARowEndUpOnTheLastTopic() = runTest {
        val e = Env(this)
        val (hiking, trails) = hikingAndTrails(e)
        e.model.reply = { newTopic("n1", label = "Camping") }
        e.post(count = 12)
        val camping = e.store.data.topics.single { it.label == "Camping" }.id
        e.store.failSave = true
        e.contacts.failSave = true
        e.runner.mergeTopic(hiking, trails)
        e.runner.mergeTopic(trails, camping)
        assertEquals(3, e.store.data.topics.size)
        e.store.failSave = false
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf(camping), e.store.data.topics.map { it.id })
        assertEquals(mapOf(TopicPool.fingerprint("Hiking") to camping, TopicPool.fingerprint("Trails") to camping), e.store.data.merged)
        assertEquals(listOf(camping), e.contacts.data.contacts.single().topics)
        assertTrue(e.purges.topicPurges.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // While the request is in flight the user merges away the topic it will cite: the reply still cites the merged-away one, which counts on the target rather than being dropped as "deleted"
    @Test fun aReplyCitingATopicMergedMeanwhileCountsOnTheOther() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        e.model.reply = { newTopic("n1", label = "Trails") }
        e.post(count = 12)
        val hiking = e.store.data.topics.single { it.label == "Hiking" }.id
        val trails = e.store.data.topics.single { it.label == "Trails" }.id
        wait(LearnTuning.DAY_MS)
        e.model.gate = CompletableDeferred()
        e.post(count = 12)
        assertEquals(3, e.model.calls.size)
        // Which position "Hiking" takes among the topics shown to the model this time
        val lines = e.model.input(2).lines()
        val ref = lines[lines.indexOf("Hiking") - 1].substringAfter("[").substringBefore("]")
        e.model.reply = { """{"signals":[{"ids":["n1"],"topic":"$ref"}]}""" }
        e.runner.mergeTopic(hiking, trails)
        e.model.gate!!.complete(Unit)
        runCurrent()
        val t = e.store.data.topics.single()
        assertEquals(trails, t.id)
        assertEquals(2, t.days)
        assertFalse(LearnDrop.NotApplied in e.runner.state.value.feed.getValue("com.shop").dropped)
    }

    @Test fun unreadableContactStoreDoesNotBlockTopics() = runTest {
        val e = Env(this)
        e.contacts.failLoad = true
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.input(0).lines().contains("Contacts: off"))
        assertEquals(1, e.store.data.topics.size)
        assertEquals(0, e.contacts.saves)
        e.runner.open()
        assertEquals(0, e.contacts.saves)
    }

    private fun seededTopics() = TopicPoolData(topics = listOf(
        ObservedTopic("ka", TopicKind.Interest, "Hiking", 1L, start, mapOf("com.shop" to listOf(20_000L), "com.chat" to listOf(20_000L))),
        ObservedTopic("kb", TopicKind.Interest, "Diabetes", 1L, start, mapOf("com.chat" to listOf(20_000L)), sensitive = true),
    ))

    private fun seededContacts() = ContactPoolData(contacts = listOf(
        ObservedContact("c1", "com.chat", "Alice", 1L, start, relationVotes = mapOf(ContactRelation.Partner to listOf(20_000L)), days = listOf(20_000L)),
        ObservedContact("c2", "com.mail", "Bob", 1L, start, days = listOf(20_000L)),
    ))

    // During "clear all" all four stores cannot be read: delete the files along with them rather than doing nothing (deleting is the only way out for a store that cannot be read)
    @Test fun clearAllWorksWhenNothingCanBeRead() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = seededTopics(); e.store.brokenCopies = 1
        e.contacts.data = seededContacts(); e.contacts.brokenCopies = 2
        e.digestStore.data = DigestData(days = listOf(DayDigest(20_000, start, listOf(DigestLine("一句", apps = listOf("com.shop"))))))
        e.journal.data = NoticeJournalData(sinceMs = start, entries = listOf(NoticeEntry("com.shop", start, "t", text = "x")))
        e.store.failLoad = true; e.contacts.failLoad = true; e.digestStore.failLoad = true; e.journal.failLoad = true
        e.purges.topicPurges = setOf("com.old"); e.purges.contactPurges = setOf("com.old")
        e.runner.clearAll()
        assertEquals(1, e.store.wipes)
        assertEquals(1, e.contacts.wipes)
        assertEquals(1, e.digestStore.wipes)
        assertEquals(1, e.journal.wipes)
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(null, e.journal.data)
        assertEquals(0, e.store.brokenCopies)
        assertEquals(0, e.contacts.brokenCopies)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        // Everything is cleared, so the recorded pending deletes are not needed
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        // It works as usual afterwards, without waiting for it to become readable
        e.model.reply = { newTopic("n1") }
        e.post(count = 12)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // "Clear all" can read but cannot save: the file still holds the old data and the next start would read it back — so delete the file
    @Test fun clearAllDeletesTheFileWhenTheEmptyPoolCannotBeSaved() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.runner.open()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearAll()
        assertEquals(1, e.store.wipes)
        assertEquals(1, e.contacts.wipes)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.runner.topics.value.isEmpty())
    }

    // Turning off "sensitive-category inference" and deleting while it cannot be read: record it, and replay the delete first once readable (it survives process death, recorded in LearnPurges)
    @Test fun sensitiveDeleteIsMadeUpWhenTheStoresCanBeReadAgain() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failLoad = true; e.contacts.failLoad = true
        e.runner.deleteSensitive()
        assertEquals(2, e.store.data.topics.size)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE), e.purges.topicPurges)
        assertEquals(setOf(LearnRunner.PURGE_SENSITIVE), e.purges.contactPurges)
        // A new process (a fresh LearnRunner over the same records): the delete is replayed as soon as it reads, before any other operation
        e.store.failLoad = false; e.contacts.failLoad = false
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { null }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, contactStore = e.contacts, purges = e.purges, clock = { e.now() },
        )
        again.open()
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        assertTrue(e.contacts.data.contacts.none { ContactRelation.Partner in it.relationVotes })
        assertEquals(2, e.contacts.data.contacts.size)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
    }

    // The same for "clear this App's": when only one store cannot be read, the readable one is cleared on the spot
    @Test fun clearAppIsMadeUpForTheStoreThatCouldNotBeRead() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.contacts.failLoad = true
        e.runner.clearApp("com.chat")
        assertEquals(mapOf("Hiking" to setOf("com.shop")), e.store.data.topics.associate { it.label to it.evidence.keys })
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(setOf("com.chat"), e.purges.contactPurges)
        assertEquals(2, e.contacts.data.contacts.size)
        e.contacts.failLoad = false
        e.runner.open()
        assertEquals(listOf("Bob"), e.contacts.data.contacts.map { it.name })
        assertEquals(emptySet<String>(), e.purges.contactPurges)
    }

    // Deleted but not saved: it stays recorded (if the process dies now, the next read replays it)
    @Test fun aPurgeThatCouldNotBeSavedStaysPending() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearApp("com.chat")
        assertEquals(setOf("com.chat"), e.purges.topicPurges)
        assertEquals(setOf("com.chat"), e.purges.contactPurges)
        assertEquals(listOf("Hiking"), e.runner.topics.value.map { it.label })
        assertEquals(listOf("Bob"), e.runner.contactsView.value.contacts.map { it.name })
        // The file still holds the old data
        assertEquals(2, e.store.data.topics.size)
        assertEquals(2, e.contacts.data.contacts.size)
    }

    // Deleted, unsaved at the time, saved later: no longer recorded. Otherwise the next start would delete again and take out whatever was accumulated afterwards
    @Test fun aPurgeIsNotRepeatedOnceItReachedTheDisk() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.store.failSave = true; e.contacts.failSave = true
        e.runner.clearApp("com.chat")
        // Saving works again; this App then accumulates new topics and contacts
        e.store.failSave = false; e.contacts.failSave = false
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        assertEquals(setOf("com.shop", "com.chat"), e.store.data.topics.single().evidence.keys)
        assertEquals(setOf("Alice", "Bob"), e.contacts.data.contacts.map { it.name }.toSet())
        // Open again in a new process: what was newly accumulated is still there
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { null }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, contactStore = e.contacts, purges = e.purges, clock = { e.now() },
        )
        again.open()
        assertEquals(setOf("com.shop", "com.chat"), e.store.data.topics.single().evidence.keys)
        assertEquals(setOf("Alice", "Bob"), e.contacts.data.contacts.map { it.name }.toSet())
    }

    // Tapping "clear" for the same App again in the same process deletes again (new data may have accumulated in memory since last time)
    @Test fun askingAgainPurgesAgain() = runTest {
        val e = Env(this)
        e.runner.open()
        e.model.reply = { aliceReply }
        e.chat("a", "Alice", count = 12)
        e.runner.clearApp("com.chat")
        assertTrue(e.store.data.topics.isEmpty())
        e.chat("a", "Alice", count = 12)
        assertEquals(1, e.store.data.topics.size)
        e.runner.clearApp("com.chat")
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertEquals(emptySet<String>(), e.purges.topicPurges)
    }

    // When the user deletes things: the old copies moved aside while unreadable (which may still hold what was just deleted) are deleted as well
    @Test fun deletionsDropTheSetAsideCopies() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        fun copies() { e.store.brokenCopies = 1; e.contacts.brokenCopies = 1 }
        fun gone() = e.store.brokenCopies == 0 && e.contacts.brokenCopies == 0
        copies(); e.runner.open(); assertFalse("merely opening the page does not delete", gone())
        e.runner.deleteTopic("ka"); assertTrue(gone())
        copies(); e.runner.deleteContact("c2"); assertTrue(gone())
        copies(); e.runner.clearApp("com.chat"); assertTrue(gone())
        copies(); e.runner.deleteSensitive(); assertTrue(gone())
        copies(); e.runner.clearAll(); assertTrue(gone())
    }

    // ---- Notification journal (raw notifications, used by the "daily notification digest") ----

    private fun TestScope.lockAndUnlock(e: Env) {
        e.locked.value = true; runCurrent(); e.locked.value = false; runCurrent()
    }

    // Recorded only while the "daily notification digest" is on, and only for the Apps learning accepts
    @Test fun journalTakesOnlyWhatLearningTakesAndOnlyWhenDigestIsOn() = runTest {
        val e = Env(this)
        e.post(pkg = "com.shop")
        assertEquals(0, e.runner.state.value.journal)
        e.digest = true
        e.optOut += "com.bank"
        e.post(pkg = "com.shop", count = 2)
        e.post(pkg = "com.bank")
        assertEquals(2, e.runner.state.value.journal)
        // With learning off, not a single entry is recorded
        e.on = false
        e.post(pkg = "com.shop")
        e.on = true
        lockAndUnlock(e)
        assertEquals(listOf("text 2", "text 3"), e.journal.texts)
    }

    // No timer-based saves: save on lock; save once 20 entries have accumulated; save when the watchman stops
    @Test fun journalIsSavedOnLockAndEveryTwentyEntries() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 3)
        assertEquals(0, e.journal.saves)
        e.locked.value = true; runCurrent()
        assertEquals(1, e.journal.saves)
        assertEquals(3, e.journal.texts.size)
        e.locked.value = false; runCurrent()
        // Nothing new, so unlocking does not save again
        assertEquals(1, e.journal.saves)
        // No single App reaches 12 entries, so no request is sent; cycling through Apps, only the saves matter
        (1..LearnRunner.JOURNAL_FLUSH_EVERY).forEach { e.post(pkg = "com.b${it % 4}") }
        assertEquals(2, e.journal.saves)
        assertEquals(3 + LearnRunner.JOURNAL_FLUSH_EVERY, e.journal.texts.size)
    }

    // Expired entries are deleted even when no new notification arrives (at the next lock or unlock)
    @Test fun journalExpiresWithoutNewNotifications() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        lockAndUnlock(e)
        assertEquals(2, e.journal.texts.size)
        wait(NoticeJournal.KEEP_MS + 60_000)
        assertEquals(2, e.journal.texts.size)
        lockAndUnlock(e)
        assertEquals(0, e.journal.texts.size)
        assertEquals(0, e.runner.state.value.journal)
    }

    // New process: it continues from what was saved; the same notification reposted unchanged is not recorded again
    @Test fun journalSurvivesARestartWithoutDuplicates() = runTest {
        val e = Env(this)
        e.digest = true
        e.runner.capture(NoticeRecord("k", "com.chat", e.now(), "家长群", "明天开会", sender = "王老师", group = true))
        e.runner.capture(NoticeRecord("g", "com.gone", e.now(), "别的", "别的"))
        lockAndUnlock(e)
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, digest = { true }, journalStore = e.journal, clock = { e.now() },
        )
        again.capture(NoticeRecord("k", "com.chat", e.now() + 5, "家长群", "明天开会", sender = "王老师", group = true))
        again.capture(NoticeRecord("k", "com.chat", e.now() + 9, "家长群", "收到", sender = "李老师", group = true))
        assertEquals(3, again.state.value.journal)
        // Clearing another App's: there are changes, so it saves once (this instance is not watching, so no lock triggers a save)
        again.clearApp("com.gone")
        assertEquals(listOf("王老师" to "明天开会", "李老师" to "收到"), e.journal.data!!.entries.map { it.sender to it.text })
    }

    /** A new process: the same files and the same recorded pending deletes, in a new instance. */
    private fun newProcess(e: Env) = LearnRunner(
        TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
            usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
        on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" },
        eligible = { it !in e.optOut }, sensitive = { false },
        store = e.store, contactStore = e.contacts, purges = e.purges,
        digest = { true }, journalStore = e.journal, digestStore = e.digestStore, clock = { e.now() }, zone = { ZoneOffset.UTC },
    )

    private fun rawApps(e: Env) = e.journal.data?.entries.orEmpty().map { it.packageName }

    // The raw text store cannot be read exactly when learning stops for this App: record it and replay the delete first once readable. Otherwise, if the App is allowed again later, the old raw text comes back
    @Test fun revokedAppsRawTextIsRemovedOnceTheJournalLoadsAgain() = runTest {
        val e = Env(this)
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(
            NoticeEntry("com.a", e.now() - 1_000, "旧会话", text = "撤销之前的原文"),
            NoticeEntry("com.b", e.now() - 900, "别的", text = "别的 App 的"),
        ))
        e.digest = true
        e.journal.failLoad = true
        e.optOut += "com.a"
        e.runner.forgetApps(setOf("com.a"))
        assertEquals(setOf("com.a"), e.purges.journalPurges)
        assertEquals("an unreadable file is not wiped entirely", 0, e.journal.wipes)
        // Later it becomes readable and the App is allowed again too
        e.journal.failLoad = false
        e.optOut -= "com.a"
        val view = e.runner.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!
        assertEquals(listOf("com.b"), view.entries.map { it.packageName })
        assertEquals(listOf("com.b"), rawApps(e))
        assertTrue("once saved it is no longer recorded", e.purges.journalPurges.isEmpty())
        // After being allowed again, new entries are recorded and kept as usual: a new process will not sweep them away with the earlier pending delete
        e.runner.capture(NoticeRecord("n1", "com.a", e.now(), "新会话", "重新允许之后的"))
        lockAndUnlock(e)
        val again = newProcess(e)
        assertEquals(listOf("com.b", "com.a"), again.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.map { it.packageName })
    }

    // Deleted but not saved and the process died again: the file still holds the old data. The recorded pending delete is replayed on the next read
    @Test fun revokedAppsRawTextStaysDeletedAfterAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.post(pkg = "com.b", count = 1)
        lockAndUnlock(e)
        assertEquals(3, rawApps(e).size)
        e.journal.failSave = true
        e.optOut += "com.a"
        e.runner.forgetApps(setOf("com.a"))
        assertEquals("the save failed: the file still has three entries", 3, rawApps(e).size)
        assertEquals(setOf("com.a"), e.purges.journalPurges)
        e.journal.failSave = false
        e.optOut -= "com.a"
        val again = newProcess(e)
        assertEquals(listOf("com.b"), again.notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.map { it.packageName })
        assertEquals(listOf("com.b"), rawApps(e))
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // "Clear this App's" while the raw text cannot be read: delete everything; if even that fails, record this App and replay it once readable
    @Test fun clearingAnAppWhileTheJournalCanNeitherBeReadNorDeletedIsReplayedLater() = runTest {
        val e = Env(this)
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(
            NoticeEntry("com.a", e.now() - 1_000, "旧会话", text = "要清掉的"),
            NoticeEntry("com.b", e.now() - 900, "别的", text = "别的 App 的"),
        ))
        e.digest = true
        e.journal.failLoad = true
        e.journal.failWipe = true
        e.runner.clearApp("com.a")
        // Being unreadable normally means deleting everything; since that fails, the record is "clear all"
        assertEquals(setOf(":all"), e.purges.journalPurges)
        e.journal.failLoad = false
        e.journal.failWipe = false
        assertTrue(newProcess(e).notices(e.now() - 10_000, e.now() + 1, LearnLevel.Balanced) { SourceTier.High }!!.entries.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        assertTrue(e.purges.journalPurges.isEmpty())
        // When deleting works, everything is deleted and there is nothing to replay
        val e2 = Env(this)
        e2.journal.data = NoticeJournalData(sinceMs = e2.now() - 2_000, entries = listOf(NoticeEntry("com.a", e2.now() - 1_000, "旧会话", text = "要清掉的")))
        e2.digest = true
        e2.journal.failLoad = true
        e2.runner.clearApp("com.a")
        assertEquals(1, e2.journal.wipes)
        assertTrue(e2.purges.journalPurges.isEmpty())
    }

    // The page must know which Apps still have raw notifications (package names only): an App with only raw text and nothing learned must be clearable on its own
    @Test fun stateListsTheAppsThatStillHaveRawText() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.post(pkg = "com.b", count = 1)
        assertEquals(setOf("com.a", "com.b"), e.runner.state.value.journalApps)
        e.runner.clearApp("com.a")
        assertEquals(setOf("com.b"), e.runner.state.value.journalApps)
        // A new process, not watching: opening the content page reads the raw text, and the page can still list them
        lockAndUnlock(e)
        val again = newProcess(e)
        assertEquals(emptySet<String>(), again.state.value.journalApps)
        again.open()
        assertEquals(setOf("com.b"), again.state.value.journalApps)
    }

    // During clear all one store can neither be read nor deleted: that must not count as cleared. Record a "clear all" and empty it accordingly once readable
    @Test fun clearAllThatCouldNotWipeAFileIsFinishedWhenItLoadsAgain() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = TopicPoolData(topics = listOf(ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.a" to listOf(20_000L, 19_999L))))
        e.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        e.journal.data = NoticeJournalData(sinceMs = e.now() - 2_000, entries = listOf(NoticeEntry("com.a", e.now() - 1_000, "会话", text = "原文")))
        e.digestStore.data = DigestData(days = listOf(DayDigest(20_000, start, listOf(DigestLine("一句", apps = listOf("com.a"))))))
        e.store.failLoad = true; e.store.failWipe = true
        e.contacts.failLoad = true; e.contacts.failWipe = true
        e.journal.failLoad = true; e.journal.failWipe = true
        e.digestStore.failLoad = true; e.digestStore.failWipe = true
        e.runner.clearAll()
        assertEquals(setOf(":all"), e.purges.topicPurges)
        assertEquals(setOf(":all"), e.purges.contactPurges)
        assertEquals(setOf(":all"), e.purges.journalPurges)
        assertEquals(setOf(":all"), e.purges.digestPurges)
        // Storage works again and a new process starts: everything is emptied as it is read, saved, and no longer recorded
        e.store.failLoad = false; e.store.failWipe = false
        e.contacts.failLoad = false; e.contacts.failWipe = false
        e.journal.failLoad = false; e.journal.failWipe = false
        e.digestStore.failLoad = false; e.digestStore.failWipe = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.topics.value.isEmpty())
        assertTrue(again.contactsView.value.contacts.isEmpty())
        assertTrue(again.digestDays.value.isEmpty())
        assertEquals(0, again.state.value.journal)
        assertTrue(e.store.data.topics.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        assertTrue(e.digestStore.data.days.isEmpty())
        for (left in listOf(e.purges.topicPurges, e.purges.contactPurges, e.purges.journalPurges, e.purges.digestPurges)) assertTrue(left.isEmpty())
    }

    // A single contact delete failed to save (memory is already empty, the file is not), then clear all: even with empty memory the file must be cleared, not just the pending delete crossed out
    @Test fun clearAllAfterAnUnsavedContactDeleteStillCleansTheFile() = runTest {
        fun seeded() = Env(this).also {
            it.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        }
        // Storage already works when clearing: the empty state is saved and nothing stays recorded
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            assertEquals(setOf(":contact:c1"), e.purges.contactPurges)
            assertEquals("the save failed, it is still in the file", 1, e.contacts.data.contacts.size)
            e.contacts.failSave = false
            e.runner.clearAll()
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
            val again = newProcess(e)
            again.open()
            assertTrue(again.contactsView.value.contacts.isEmpty())
        }
        // Saving still fails when clearing: fall back to deleting the file
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            e.runner.clearAll()
            assertEquals(1, e.contacts.wipes)
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
        }
        // Clearing can neither save nor delete: the pending delete must not be lost, so it is recorded as "clear all"; once storage works it is finished
        seeded().let { e ->
            e.contacts.failSave = true
            e.runner.deleteContact("c1")
            e.contacts.failWipe = true
            e.runner.clearAll()
            assertEquals(setOf(":all"), e.purges.contactPurges)
            assertEquals(1, e.contacts.data.contacts.size)
            e.contacts.failSave = false
            e.contacts.failWipe = false
            val again = newProcess(e)
            again.open()
            assertTrue(again.contactsView.value.contacts.isEmpty())
            assertTrue(e.contacts.data.contacts.isEmpty())
            assertTrue(e.purges.contactPurges.isEmpty())
        }
    }

    // The first clear all could neither save nor delete contacts; after storage recovered, clearing again in the same process: memory has long been empty, but this time the file really must be cleared
    @Test fun aSecondClearAllFinishesWhatTheFirstCouldNot() = runTest {
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(ObservedContact("c1", "com.a", "张三", 1L, start, days = listOf(20_000L))))
        e.contacts.failSave = true
        e.contacts.failWipe = true
        e.runner.clearAll()
        assertEquals(setOf(":all"), e.purges.contactPurges)
        assertEquals(1, e.contacts.data.contacts.size)
        e.contacts.failSave = false
        e.contacts.failWipe = false
        e.runner.clearAll()
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // Contacts stored earlier by notification key: on load, those with the same name are merged into one and saved; recorded deletes that never saved (recorded under the old id) are replayed first, so deleted ones do not come back through the merge
    @Test fun storedContactsAreMergedByConversationNameWhenLoaded() = runTest {
        fun old(key: String, title: String) = com.wangzan101.mika.core.proactive.ContactIdentity.of(
            "com.chat", key, title, com.wangzan101.mika.core.proactive.ContactIdStrategy.KeyAndTitle)!!.id
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact(old("k1", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_998L)),
            ObservedContact(old("k2", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_999L)),
            ObservedContact(old("k3", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(20_000L)),
            ObservedContact(old("k4", "删掉的群"), "com.chat", "删掉的群", 1L, start, days = listOf(20_000L)),
            ObservedContact(old("k5", "删掉的群"), "com.chat", "删掉的群", 1L, start, days = listOf(19_999L)),
        ))
        e.purges.contactPurges = setOf(":contact:" + old("k4", "删掉的群"))
        e.runner.open()
        val shown = e.runner.contactsView.value.contacts
        assertEquals(listOf("家长群"), shown.map { it.name })
        assertEquals(listOf(19_998L, 19_999L, 20_000L), shown.single().days)
        assertTrue(shown.single().established)
        // Saved: the file holds the single merged entry and the recorded delete is crossed out
        assertEquals(1, e.contacts.data.contacts.size)
        assertTrue(e.contacts.data.contacts.single().id.startsWith("t"))
        assertTrue(e.purges.contactPurges.isEmpty())
        // Read again in a new process: nothing changes
        val saves = e.contacts.saves
        val again = newProcess(e)
        again.open()
        assertEquals(1, again.contactsView.value.contacts.size)
        assertEquals(saves, e.contacts.saves)
    }

    // The re-identification failed to save (storage full and the like), the user deleted the re-identified entry and that failed to save too, and the process died. The next read still sees the pre-migration file:
    // the recorded delete must count, so the entries with the same name must not come back just by being re-identified again
    @Test fun aDeleteMadeAfterAnUnsavedMigrationStillHolds() = runTest {
        fun old(key: String, title: String) = com.wangzan101.mika.core.proactive.ContactIdentity.of(
            "com.chat", key, title, com.wangzan101.mika.core.proactive.ContactIdStrategy.KeyAndTitle)!!.id
        val e = Env(this)
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact(old("k1", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(19_999L)),
            ObservedContact(old("k2", "家长群"), "com.chat", "家长群", 1L, start, days = listOf(20_000L)),
        ))
        e.contacts.failSave = true
        e.runner.open()
        val merged = e.runner.contactsView.value.contacts.single()
        assertTrue(merged.id.startsWith("t"))
        e.runner.deleteContact(merged.id)
        assertEquals(2, e.contacts.data.contacts.size)
        assertEquals(1, e.purges.contactPurges.size)
        e.contacts.failSave = false
        val again = newProcess(e)
        again.open()
        assertTrue(again.contactsView.value.contacts.isEmpty())
        assertTrue(e.contacts.data.contacts.isEmpty())
        assertTrue(merged.id in e.contacts.data.blocked)
        assertTrue(e.purges.contactPurges.isEmpty())
    }

    // Clear all completed normally: no pending deletes remain recorded (earlier records are crossed out too)
    @Test fun clearAllLeavesNoPendingDeletionsWhenItWorked() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        e.purges.topicPurges = setOf(":topic:k9")
        e.purges.digestPurges = setOf("com.gone")
        e.purges.journalPurges = setOf("com.gone")
        e.runner.clearAll()
        for (left in listOf(e.purges.topicPurges, e.purges.contactPurges, e.purges.journalPurges, e.purges.digestPurges)) assertTrue(left.toString(), left.isEmpty())
    }

    // A notification key is recognised as a repeatedly reposted status display (navigation prompts and the like): the entries just captured and not yet learned are dropped, along with their raw notifications; other keys are untouched
    @Test fun aKeyRecognisedAsAStatusDisplayLosesWhatItJustAdded() = runTest {
        val e = Env(this)
        e.digest = true
        // A normal notification on the same key from an hour earlier (many Apps use one notification key for all pushes): it must not be dropped with them
        e.runner.capture(NoticeRecord("nav", "com.maps", e.now(), "常去的地方有新的评价", "点开看看"))
        wait(3_600_000L)
        val burst = e.now()
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", burst + i * 2_000L, "${300 - i * 10} 米后右转", "某某路")) }
        e.runner.capture(NoticeRecord("promo", "com.maps", burst + 9, "周边优惠", "满减券到账"))
        runCurrent()
        assertEquals(5, e.runner.state.value.journal)
        e.runner.dropLive("nav", "com.maps", burst + 6_000L - 60_000L)
        assertEquals(listOf("常去的地方有新的评价", "周边优惠"), e.journal.data!!.entries.map { it.title })
        assertEquals(2, e.runner.state.value.journal)
        // With nothing under this key, nothing happens
        val saves = e.journal.saves
        e.runner.dropLive("nothing", "com.maps", 0)
        assertEquals(saves, e.journal.saves)
    }

    // When a key is recognised as a status display, the segmented digest request being written for this App is cancelled: otherwise a late reply would still put sentences written from the raw text just dropped into the digest.
    // Requests for other Apps are left alone
    @Test fun aNotesRequestInFlightIsCancelledWhenItsAppTurnsOutToPostAStatusDisplay() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.maps", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.maps")
        assertTrue(e.runner.requestActive)
        e.runner.dropLive("x", "com.other", 0)
        assertTrue(e.runner.requestActive)
        e.runner.dropLive("k20", "com.maps", e.now() - 60_000L)
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(19, e.runner.state.value.journal)
    }

    // When the status display is recognised, the topic digest request in flight carries the entries just captured: cancel it and drop them. Otherwise a late reply would still store topics learned from them;
    // if locking cancels it a second time they return to pending and are sent to the model again after unlocking
    @Test fun aLearnRequestInFlightIsCancelledWhenItCarriesAStatusDisplay() = runTest {
        val e = Env(this)
        repeat(9) { i -> e.runner.capture(NoticeRecord("normal-$i", "com.maps", e.now(), "普通通知 $i", "普通内容 $i")) }
        e.model.gate = CompletableDeferred()
        e.model.reply = { """{"signals":[{"ids":["n10"],"new":{"kind":"routine","label":"导航播报"}}]}""" }
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", e.now() + i * 2_000L, "导航", "NAV_TEXT_$i")) }
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        assertTrue(e.model.input(0).contains("NAV_TEXT_0"))
        e.runner.dropLive("nav", "com.maps", e.now() - 60_000L)
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(9, e.runner.state.value.pending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        // The rest is sent again as usual (once due), without those entries
        e.model.gate = null
        e.model.reply = { """{"signals":[]}""" }
        wait(LearnTuning.DUE_AGE_MS + 1)
        e.post(pkg = "com.other")
        val resent = e.model.calls.indices.first { it > 0 && e.model.packages()[it] == "com.maps" }
        assertTrue(e.model.input(resent).contains("普通内容 0"))
        assertFalse(e.model.input(resent).contains("NAV_TEXT"))
        // No cancellation while entries for other keys, or earlier entries of this key, are in flight
        val f = Env(this)
        f.model.gate = CompletableDeferred()
        f.post(pkg = "com.maps", count = 12)
        assertTrue(f.runner.requestActive)
        f.runner.dropLive("nav", "com.maps", f.now() - 60_000L)
        f.runner.dropLive("k1", "com.maps", f.now() + 1)
        assertTrue(f.runner.requestActive)
    }

    // The raw notifications cannot be read at the moment the status display is recognised (the keystore is briefly unavailable): record it, and once readable drop that burst before choosing a batch.
    // Otherwise they would still count as unlearned and be sent to the model again with the next segmented digest. Normal notifications on the same key afterwards are untouched
    @Test fun aStatusDisplaysRawTextIsRemovedOnceTheJournalLoadsAgain() = runTest {
        val e = Env(this)
        e.digest = true
        val since = start - 60_000L
        val keyId = com.wangzan101.mika.core.proactive.NoticeDedup.idOf("nav")
        e.journal.data = NoticeJournalData(sinceMs = start - 3_600_000L, entries = listOf(
            NoticeEntry("com.maps", start - 3_000_000L, "更早的正常通知", text = "同一个键", keyId = keyId),
            NoticeEntry("com.maps", start - 30_000L, "导航", text = "300 米后右转", keyId = keyId),
            NoticeEntry("com.maps", start - 20_000L, "导航", text = "200 米后右转", keyId = keyId),
            NoticeEntry("com.maps", start - 10_000L, "周边优惠", text = "另一个键", keyId = "other"),
            NoticeEntry("com.maps", start + 600_000L, "之后的正常通知", text = "同一个键", keyId = keyId),
        ))
        e.journal.failLoad = true
        e.runner.dropLive("nav", "com.maps", since)
        assertEquals(1, e.purges.journalPurges.size)
        assertEquals(5, e.journal.data!!.entries.size)
        e.journal.failLoad = false
        e.runner.open()
        assertEquals(listOf("更早的正常通知", "周边优惠", "之后的正常通知"), e.journal.data!!.entries.map { it.title })
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // The raw text was dropped but not saved and the process died: the file still holds the old data, replayed on the next read
    @Test fun aStatusDisplaysRawTextRemovalSurvivesAFailedSave() = runTest {
        val e = Env(this)
        e.digest = true
        wait(60_000L)
        repeat(3) { i -> e.runner.capture(NoticeRecord("nav", "com.maps", e.now() - 6_000L + i * 2_000L, "${300 - i * 10} 米后右转", "某某路")) }
        e.runner.capture(NoticeRecord("promo", "com.maps", e.now() - 9, "周边优惠", "满减券到账"))
        runCurrent()
        lockAndUnlock(e)
        assertEquals(4, e.journal.data!!.entries.size)
        e.journal.failSave = true
        e.runner.dropLive("nav", "com.maps", e.now() - 60_000L)
        assertEquals(4, e.journal.data!!.entries.size)
        assertEquals(1, e.purges.journalPurges.size)
        e.journal.failSave = false
        val again = newProcess(e)
        again.open()
        assertEquals(listOf("周边优惠"), e.journal.data!!.entries.map { it.title })
        assertTrue(e.purges.journalPurges.isEmpty())
    }

    // Every clearing path must take the raw notifications with it (the same category as "queued items are cleared when an App is blocked")
    @Test fun journalFollowsEveryPurgePath() = runTest {
        fun Env.seed(): Env {
            digest = true
            post(pkg = "com.a", count = 2)
            post(pkg = "com.b", count = 1)
            return this
        }
        fun apps(e: Env) = e.journal.data?.entries.orEmpty().map { it.packageName }
        // Unchecking it, blocking it in "App access", or "do not learn from it": all mean this App is no longer learned from, and then it looks again
        Env(this).seed().let { e ->
            e.optOut += "com.a"
            e.runner.forgetApps(setOf("com.a"))
            assertEquals(listOf("com.b"), apps(e))
            assertEquals(1, e.runner.state.value.journal)
        }
        // Clear this App's
        Env(this).seed().let { e ->
            e.runner.clearApp("com.a")
            assertEquals(listOf("com.b"), apps(e))
        }
        // Clear all
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            assertEquals(3, apps(e).size)
            e.runner.clearAll()
            assertEquals(emptyList<String>(), apps(e))
            assertEquals(0, e.runner.state.value.journal)
        }
        // Turn off the "daily notification digest"
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            e.digest = false
            e.runner.poke()
            assertEquals(emptyList<String>(), apps(e))
            assertTrue(e.journal.wipes > 0)
            assertEquals(0, e.runner.state.value.journal)
        }
        // Turn off learning and turn off proactive features (both go through clear)
        Env(this).seed().let { e ->
            lockAndUnlock(e)
            e.on = false
            e.runner.clear()
            assertEquals(emptyList<String>(), apps(e))
            assertEquals(0, e.runner.state.value.journal)
        }
    }

    // The journal is temporarily unreadable: nothing is recorded or saved (no empty data written over it); "clear all" and "clear this App's" still delete the file
    @Test fun unreadableJournalIsNeverOverwrittenButCanStillBeWiped() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 2)
        lockAndUnlock(e)
        val saved = e.journal.data
        // A new process, and now it cannot be read
        e.journal.failLoad = true
        val again = LearnRunner(
            TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false)),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { 1 }, language = { "English" }, eligible = { true }, sensitive = { false },
            store = e.store, digest = { true }, journalStore = e.journal, clock = { e.now() },
        )
        val saves = e.journal.saves
        again.capture(NoticeRecord("x", "com.a", e.now(), "t", "new"))
        again.poke()
        assertEquals(saves, e.journal.saves)
        assertEquals(saved, e.journal.data)
        again.clearApp("com.a")
        assertEquals(null, e.journal.data)
        e.journal.data = saved
        again.clearAll()
        assertEquals(null, e.journal.data)
    }

    // ---- Level 1 of the daily notification digest: segmented digest ----

    private val oneNote = """{"notes":[{"ids":["e1","e2"],"text":"买的东西发货了"}]}"""

    // One App accumulates 20 unlearned entries: a batch is sent (that App only, no tools), the sentence and its grounds are stored, and this batch is not sent again
    @Test fun notesBatchIsSentWhenDueAndStoredWithItsGrounds() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = LearnTuning.NOTES_DUE_COUNT - 1)
        // The topic digest batch (12 entries) already went out; the segmented digest is one entry short
        assertEquals(listOf(false), e.model.calls.indices.map(e.model::isNotes))
        e.post(pkg = "com.shop")
        assertEquals(listOf(false, true), e.model.calls.indices.map(e.model::isNotes))
        assertTrue(e.model.tools.last().isEmpty())
        assertEquals(LearnTuning.NOTES_BATCH_MAX, Regex("\\[e\\d+] time:").findAll(e.model.input(1)).count())
        val note = e.digestStore.data.notes.single()
        assertEquals("买的东西发货了", note.text)
        assertEquals("com.shop", note.packageName)
        assertEquals(2, note.count)
        assertEquals(listOf("买的东西发货了"), e.runner.digestNotes.value.map { it.text })
        // These 20 entries are marked done (the raw text stays) and are not sent again
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.journal.data!!.entries.count { it.noted })
        e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
    }

    // Short of 20 entries, it still sends once the oldest entry is two hours old (it only looks when something happens; no timer)
    @Test fun notesBatchIsSentWhenItsOldestEntryIsOldEnough() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a", count = 3)
        wait(LearnTuning.NOTES_DUE_AGE_MS)
        assertTrue(e.model.calls.none { e.model.isNotes(e.model.calls.indexOf(it)) })
        // The topic digest batch (over 30 minutes old) goes first, and the segmented digest follows once it returns
        e.post(pkg = "com.other")
        assertEquals(listOf("com.a" to false, "com.a" to true), e.model.calls.indices.map { e.model.packages()[it] to e.model.isNotes(it) })
        assertEquals(1, e.digestStore.data.notes.size)
    }

    // Unreadable reply: this batch counts as done (the same batch is not sent again) and a hold follows; not connected at all: it is kept and the same batch is sent after a hold
    @Test fun notesFailuresFollowTheSameRulesAsLearning() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.reply = { """{"signals":[]}""" }
        e.model.notesReply = { "I cannot do that." }
        e.post(pkg = "com.a", count = 20)
        assertEquals(1, e.model.calls.indices.count(e.model::isNotes))
        assertEquals(20, e.journal.data!!.entries.count { it.noted })
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(LearnStatus.Waiting, e.runner.state.value.status)
        // Not connected
        val f = Env(this)
        f.digest = true
        f.model.reply = { """{"signals":[]}""" }
        f.post(pkg = "com.a", count = 19)
        f.model.error = ModelError.Network()
        f.post(pkg = "com.a")
        val notesCalls = { f.model.calls.indices.count(f.model::isNotes) }
        assertEquals(1, notesCalls())
        assertEquals(0, f.journal.data?.entries.orEmpty().count { it.noted })
        f.model.error = null
        f.model.notesReply = { oneNote }
        f.busy.value = true; runCurrent(); f.busy.value = false; runCurrent()
        assertEquals("nothing is sent while paused", 1, notesCalls())
        wait(LearnTuning.failureHoldMs(1))
        f.busy.value = true; runCurrent(); f.busy.value = false; runCurrent()
        assertEquals(2, notesCalls())
        assertEquals(listOf("买的东西发货了"), f.digestStore.texts)
    }

    // Locking: the segmented digest in flight is cancelled, that batch is kept, and it is sent again after unlocking
    @Test fun lockCancelsTheNotesRequestAndKeepsTheBatch() = runTest {
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a")
        assertTrue(e.runner.requestActive)
        e.locked.value = true; runCurrent()
        assertFalse(e.runner.requestActive)
        assertTrue(e.digestStore.data.notes.isEmpty())
        e.model.gate = null
        e.locked.value = false; runCurrent()
        assertEquals(listOf("买的东西发货了"), e.digestStore.texts)
    }

    // The clearing paths all take the accumulated sentences with them; turning switches off does not (only raw notifications are deleted); when clearing happens while a request is in flight, the late result must not bring back what was cleared
    @Test fun notesFollowEveryPurgePath() = runTest {
        fun seeded(): Env = Env(this).also { e ->
            e.digest = true
            e.model.notesReply = { oneNote }
            e.post(pkg = "com.a", count = 20)
            e.post(pkg = "com.b", count = 20)
            assertEquals(listOf("com.a", "com.b"), e.digestStore.data.notes.map { it.packageName })
        }
        fun apps(e: Env) = e.digestStore.data.notes.map { it.packageName }
        seeded().let { e -> e.optOut += "com.a"; e.runner.forgetApps(setOf("com.a")); assertEquals(listOf("com.b"), apps(e)); assertEquals(1, e.runner.digestNotes.value.size) }
        seeded().let { e -> e.runner.clearApp("com.a"); assertEquals(listOf("com.b"), apps(e)) }
        seeded().let { e -> e.runner.clearAll(); assertEquals(emptyList<String>(), apps(e)); assertTrue(e.runner.digestNotes.value.isEmpty()) }
        // Turning off the "daily notification digest" and the master switch: raw notifications are deleted while sentences already written stay (still visible and deletable on the page)
        seeded().let { e ->
            e.digest = false; e.runner.poke()
            assertEquals(listOf("com.a", "com.b"), apps(e)); assertEquals(0, e.digestStore.wipes); assertEquals(null, e.journal.data)
            assertEquals(2, e.runner.digestNotes.value.size)
        }
        seeded().let { e ->
            e.on = false; e.runner.clear()
            assertEquals(listOf("com.a", "com.b"), apps(e)); assertEquals(0, e.digestStore.wipes); assertEquals(null, e.journal.data)
        }
        seeded().let { e ->
            val id = e.digestStore.data.notes.first().id
            e.runner.deleteNote(id)
            assertEquals(listOf("com.b"), apps(e))
        }
        // Cleared while in flight
        val e = Env(this)
        e.digest = true
        e.post(pkg = "com.a", count = 19)
        e.model.gate = CompletableDeferred()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.a")
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(null, e.journal.data)
    }

    // Tapping a segmented digest sentence on the page: returns that App's notifications still stored for that period; after two days there are none; with the "daily notification digest" off it reports "off"
    @Test fun aNotesRawNotificationsCanBeLookedUpWhileTheyLast() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = 20)
        val note = e.digestStore.data.notes.single()
        val found = e.runner.rawFor(note) as RawNotices.Found
        assertTrue(found.entries.isNotEmpty())
        assertTrue(found.entries.all { it.packageName == "com.shop" && it.atMs in note.fromMs..note.toMs })
        wait(NoticeJournal.KEEP_MS + 1)
        assertEquals(RawNotices.Found(emptyList()), e.runner.rawFor(note))
        e.digest = false
        assertEquals(RawNotices.Off, e.runner.rawFor(note))
        e.digest = true
        e.on = false
        assertEquals(RawNotices.Off, e.runner.rawFor(note))
    }

    // ---- Level 2 of the daily notification digest: daily digest ----

    private val oneLine = """{"lines":[{"ids":["s1"],"text":"买的东西发货了,今天到"}]}"""
    private fun TestScope.nudge(e: Env) { e.busy.value = true; runCurrent(); e.busy.value = false; runCurrent() }

    /** An environment with one segmented digest sentence for today. */
    private fun TestScope.withANote(): Env = Env(this).also { e ->
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertEquals(listOf("learn", "notes"), e.model.kinds)
    }

    // Once a day is over (it only looks when something happens) that day's sentences are merged into one; only the sentences and App names go into the request; once done it is not redone
    @Test fun dayDigestIsMadeOnceTheDayIsOver() = runTest {
        val e = withANote()
        nudge(e)
        assertFalse("not done automatically on the same day", "day" in e.model.kinds)
        wait(LearnTuning.DAY_MS)
        assertFalse("no timer", "day" in e.model.kinds)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "[s1] app: App shop" in input && "买的东西发货了" in input)
        assertFalse("no notification raw text", "text 1" in input || "title 1" in input)
        assertTrue(e.model.tools.last().isEmpty())
        val d = e.digestStore.data.days.single()
        assertEquals(20_000L, d.day)
        assertFalse(d.partial)
        assertEquals(listOf("买的东西发货了,今天到"), d.lines.map { it.text })
        assertEquals(listOf("com.shop"), d.lines.single().apps)
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // The one-sentence overview the model gives with the sentences is stored for the page; tasks never get it (the read-notifications tool and the section carried into tasks take only sentences)
    @Test fun theBriefIsStoredForThePageAndNeverGivenToTasks() = runTest {
        val e = withANote()
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"买的东西发货了"}],"brief":"概述只给页面看"}""" }
        e.runner.digestToday()
        runCurrent()
        assertEquals("概述只给页面看", e.digestStore.data.days.single().brief)
        assertEquals("概述只给页面看", e.runner.digestDays.value.single().brief)
        val tier = { _: String -> SourceTier.High }
        val given = e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!
        assertEquals(listOf("买的东西发货了"), given.digests.single().lines.map { it.text })
        assertFalse("概述" in e.runner.observed(LearnLevel.Aggressive, tier).orEmpty())
        // Not given by the model when redone: the previous one is not kept
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"买的东西发货了"}]}""" }
        e.runner.digestToday()
        runCurrent()
        assertEquals("", e.digestStore.data.days.single().brief)
    }

    private val fiveNotes = """{"notes":[""" + (1..5).joinToString(",") { """{"ids":["e$it"],"text":"第 $it 件事"}""" } + "]}"

    // Once today's sentences have accumulated enough, without a tap from the user: when something happens it automatically makes an "up to now" one; it does not redo it without new sentences or when the last one is too recent; after the day ends it is redone complete
    @Test fun todaysDigestIsMadeWithoutBeingAskedOnceEnoughNotesHaveGathered() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertEquals("nobody tapped it, it did it by itself", "day", e.model.kinds.last())
        val d = e.digestStore.data.days.single()
        assertTrue(d.partial)
        assertEquals(20_000L, d.day)
        // No new sentences: no redo even when something happens (even if the last one is long past)
        nudge(e)
        wait(LearnTuning.TODAY_AUTO_INTERVAL_MS)
        nudge(e)
        assertEquals(e.model.kinds.toString(), 1, e.model.kinds.count { it == "day" })
        // The day is over: a complete one is redone
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
        assertFalse(e.digestStore.data.days.single { it.day == 20_000L }.partial)
    }

    // Every automatic reply today is unreadable: it stops trying automatically; but these do not count against "the complete digest for this day", which is still made once the day is over
    @Test fun unreadableAutomaticDigestsDoNotUseUpTheFullDigestsTries() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { "I cannot do that." }
        e.post(pkg = "com.shop", count = 20)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.DAY_TRIES_MAX, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
        e.model.dayReply = { oneLine }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val d = e.digestStore.data.days.single()
        assertEquals(20_000L, d.day)
        assertFalse("the complete copy is still made", d.partial)
    }

    // The user deleted today's: it is not made again automatically (even if new sentences arrive)
    @Test fun aDeletedTodayIsNotMadeAgainByItself() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { fiveNotes }
        e.model.dayReply = { oneLine }
        e.post(pkg = "com.shop", count = 20)
        assertTrue(e.digestStore.data.days.single().partial)
        e.runner.deleteDay(20_000L)
        runCurrent()
        val days = e.model.kinds.count { it == "day" }
        wait(LearnTuning.TODAY_AUTO_INTERVAL_MS)
        e.post(pkg = "com.shop", count = 20)
        nudge(e)
        assertEquals(days, e.model.kinds.count { it == "day" })
    }

    // The model is built with which kind of request it is for: long replies (segmented, daily, weekly, monthly digests) must not share the short limit used for triage
    @Test fun everyRequestSaysWhichKindItIsWhenTheModelIsBuilt() = runTest {
        val e = withANote()
        assertEquals(listOf(ProactiveCall.Learn, ProactiveCall.Notes), e.asked)
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(listOf("day", "week", "month"), e.model.kinds.takeLast(3))
        assertEquals(listOf(ProactiveCall.Day, ProactiveCall.Span, ProactiveCall.Span), e.asked.takeLast(3))
    }

    // The user taps "digest today up to now": one marked "up to now" is made at once; after the day ends it is redone complete
    @Test fun todayOnDemandIsPartialAndIsRedoneAfterTheDay() = runTest {
        val e = withANote()
        e.runner.digestToday()
        runCurrent()
        assertEquals("day", e.model.kinds.last())
        assertTrue(e.digestStore.data.days.single().partial)
        // It is only redone on another tap; with no sentences today a tap sends no request
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"完整的一份"}]}""" }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val d = e.digestStore.data.days.single()
        assertFalse(d.partial)
        assertEquals(listOf("完整的一份"), d.lines.map { it.text })
        val empty = Env(this)
        empty.digest = true
        empty.runner.digestToday()
        runCurrent()
        assertTrue(empty.model.calls.isEmpty())
    }

    // Notifications from that day have not been segmented yet: the segmented digest runs first, then the daily digest
    @Test fun dayDigestWaitsForTheDaysNotes() = runTest {
        val e = withANote()
        e.post(pkg = "com.mail", count = 3)
        wait(LearnTuning.DAY_MS)
        nudge(e)
        val after = e.model.kinds.drop(2)
        assertEquals("day", after.last())
        assertTrue(after.toString(), after.indexOf("notes") in 0 until after.lastIndex)
        assertTrue("[s2]" in e.model.input(e.model.calls.lastIndex))
    }

    // The reply is always unreadable: at most 3 tries per day within one process; failures to reach the model do not count
    @Test fun unreadableDayDigestIsTriedAFewTimesOnly() = runTest {
        val e = withANote()
        e.model.dayReply = { "I cannot do that." }
        wait(LearnTuning.DAY_MS)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.DAY_TRIES_MAX, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
    }

    // Clearing paths: clearing this App's and clearing all take the daily digest with them; turning off the "daily notification digest" does not; clearing while a request is in flight is not written back; a single day can be deleted
    @Test fun dayDigestFollowsThePurgePaths() = runTest {
        fun TestScope.made(): Env = withANote().also { e ->
            wait(LearnTuning.DAY_MS)
            nudge(e)
            assertEquals(1, e.digestStore.data.days.single().lines.size)
        }
        made().let { e -> e.runner.clearApp("com.shop"); assertTrue(e.digestStore.data.days.single().lines.isEmpty()) }
        made().let { e -> e.digest = false; e.runner.poke(); assertEquals(1, e.digestStore.data.days.single().lines.size); assertEquals(1, e.runner.digestDays.value.size) }
        made().let { e -> e.runner.clearAll(); assertTrue(e.digestStore.data.days.isEmpty()) }
        // (several environments in one test share a virtual clock, so this digest's day is not the first day)
        made().let { e ->
            e.runner.deleteDay(e.digestStore.data.days.single().day)
            // All that remains is a "deleted" mark: no sentences, invisible to readers
            assertTrue(e.runner.digestDays.value.isEmpty())
            assertTrue(e.digestStore.data.days.single().let { it.removed && it.lines.isEmpty() })
        }
        val e = withANote()
        e.model.gate = CompletableDeferred()
        e.runner.digestToday()
        runCurrent()
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.days.isEmpty() && e.digestStore.data.notes.isEmpty())
    }

    // Yesterday's digest was already made and last night's notifications are captured only afterwards: once they become sentences, yesterday is redone — exactly once
    @Test fun aDayIsRedoneOnceWhenLateNotesArrive() = runTest {
        val e = withANote()
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "day" })
        e.model.dayReply = { """{"lines":[{"ids":["s1","s2"],"text":"连同后来补收到的"}]}""" }
        // Captured later: they were posted yesterday
        wait(60_000)
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals(2, e.model.kinds.count { it == "day" })
        assertEquals(listOf("连同后来补收到的"), e.digestStore.data.days.single { it.day == 20_000L }.lines.map { it.text })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // While the daily digest is in flight one App stops being learned from: the request is cancelled so that App's sentences appear in no line; afterwards it is redone from the rest
    @Test fun purgingAnAppCancelsTheDayRequestInFlight() = runTest {
        val e = withANote()
        e.post(pkg = "com.mail", count = 20)
        assertEquals(listOf("com.shop", "com.mail"), e.digestStore.data.notes.map { it.packageName })
        e.model.dayReply = { """{"lines":[{"ids":["s1","s2"],"text":"两个 App 合在一起的一句"}]}""" }
        e.model.gate = CompletableDeferred()
        e.runner.digestToday()
        runCurrent()
        assertTrue(e.runner.requestActive)
        e.optOut += "com.mail"
        e.runner.forgetApps(setOf("com.mail"))
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.days.isEmpty())
        // Ask again: only one App's sentences remain, so the merged line citing a second sentence that no longer exists is dropped entirely
        e.model.gate = null
        e.runner.digestToday()
        runCurrent()
        val last = e.model.input(e.model.calls.lastIndex)
        assertTrue("[s1]" in last && "[s2]" !in last)
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
    }

    // ---- The section carried into tasks: picked by aggressiveness level and source tier ----

    private fun ObservedTopic.on(vararg apps: Pair<String, List<Long>>) = copy(evidence = apps.toMap())

    // Topics and contacts that should already have faded (nothing newly learned for a long time, no content page opened, not tidied away yet) are not carried into tasks; contacts the user gave a relation to still are
    @Test fun observedLeavesOutWhatShouldAlreadyBeForgotten() = runTest {
        val e = Env(this)
        val long = start - 70L * LearnTuning.DAY_MS
        val oldDays = listOf(19_928L, 19_929L, 19_930L)
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("old", TopicKind.Interest, "很久以前的话题", long, long).on("com.high" to oldDays),
            ObservedTopic("new", TopicKind.Interest, "最近的话题", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
        ))
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact("c1", "com.high", "很久没联系的人", long, long, days = oldDays),
            ObservedContact("c2", "com.high", "用户定过的人", long, long, days = oldDays, userRelation = ContactRelation.Friend),
            ObservedContact("c3", "com.high", "最近的人", 1L, start, days = listOf(19_998L, 19_999L, 20_000L)),
        ))
        assertTrue(e.store.data.topics.first().retention(e.now()) < LearnTuning.FORGET_BELOW)
        assertTrue(e.contacts.data.contacts.first().retention(e.now()) < LearnTuning.FORGET_BELOW)
        val text = e.runner.observed(LearnLevel.Balanced) { SourceTier.High }!!
        for (yes in listOf("最近的话题", "最近的人", "用户定过的人")) assertTrue(yes, yes in text)
        for (no in listOf("很久以前的话题", "很久没联系的人")) assertFalse(no, no in text)
        // Only left out: the pool is untouched and nothing was saved for this (tidying still happens when new learnings are accepted and when a content page opens)
        assertEquals(2, e.store.data.topics.size)
        assertEquals(0, e.store.saves)
        // Only forgettable ones left: no section at all
        e.store.data = TopicPoolData(topics = e.store.data.topics.take(1))
        e.contacts.data = ContactPoolData(contacts = e.contacts.data.contacts.take(1))
        val alone = Env(this).also { it.store.data = e.store.data; it.contacts.data = e.contacts.data }
        assertEquals(null, alone.runner.observed(LearnLevel.Balanced) { SourceTier.High })
    }

    @Test fun observedFollowsTheLevelAndTheSourceTier() = runTest {
        val e = Env(this)
        e.digest = true
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
            ObservedTopic("k2", TopicKind.Shopping, "跑鞋", 1L, start).on("com.mid" to listOf(20_000L, 19_999L)),
            ObservedTopic("k3", TopicKind.Follow, "只有一天", 1L, start).on("com.high" to listOf(20_000L)),
            ObservedTopic("k4", TopicKind.Interest, "体检", 1L, start, sensitive = true).on("com.high" to listOf(20_000L, 19_999L)),
            ObservedTopic("k5", TopicKind.Service, "不归纳的", 1L, start).on("com.low" to listOf(20_000L, 19_999L)),
        ))
        e.contacts.data = ContactPoolData(contacts = listOf(
            ObservedContact("c1", "com.high", "张三", 1L, start, days = listOf(19_998L, 19_999L, 20_000L), userRelation = ContactRelation.Friend),
            ObservedContact("c2", "com.mid", "李四", 1L, start, days = listOf(19_998L, 19_999L, 20_000L)),
            ObservedContact("c3", "com.high", "还没确立", 1L, start, days = listOf(20_000L)),
        ))
        e.digestStore.data = DigestData(days = listOf(
            DayDigest(20_000, start, listOf(DigestLine("高档的一句", apps = listOf("com.high")), DigestLine("中档的一句", apps = listOf("com.mid")),
                DigestLine("两档合在一起的", apps = listOf("com.high", "com.mid")))),
            DayDigest(19_999, start, listOf(DigestLine("昨天高档的", apps = listOf("com.high"))), leftOut = 9),
        ))
        e.optOut += "com.low"
        val tier: (String) -> SourceTier = { when (it) { "com.high" -> SourceTier.High; "com.low" -> SourceTier.Low; else -> SourceTier.Mid } }

        // The most conservative level: nothing is carried
        assertEquals(null, e.runner.observed(LearnLevel.Conservative, tier))

        // Balanced: only high-tier sources; topics need enough days; sensitive ones are left out while the switch is off
        val balanced = e.runner.observed(LearnLevel.Balanced, tier)!!
        for (yes in listOf("高档的一句", "昨天高档的", "徒步", "张三 (friend, set by the user)")) assertTrue(yes, yes in balanced)
        // When yesterday's was made 9 sentences did not fit: the heading says so; today's leaves nothing out, so it does not
        assertTrue("Yesterday (9 notes did not fit when this was written, so it is not everything):" in balanced)
        assertFalse("Today so far (" in balanced && "did not fit" in balanced.substringBefore("Yesterday"))
        for (no in listOf("中档的一句", "两档合在一起的", "跑鞋", "只有一天", "体检", "不归纳的", "李四", "还没确立")) assertFalse(no, no in balanced)

        // Aggressive: medium is carried too and one day is enough for a topic; low tier (not learned from) is still left out
        val aggressive = e.runner.observed(LearnLevel.Aggressive, tier)!!
        for (yes in listOf("中档的一句", "两档合在一起的", "跑鞋", "只有一天", "李四")) assertTrue(yes, yes in aggressive)
        for (no in listOf("体检", "不归纳的", "还没确立")) assertFalse(no, no in aggressive)

        // Sensitive ones are carried only while "sensitive-category inference" is on
        e.sensitive = true
        assertTrue("体检" in e.runner.observed(LearnLevel.Balanced, tier)!!)

        // With the "daily notification digest" off: digest sentences are left out while topics and contacts still go in; with learning off: nothing is carried
        e.digest = false
        val noDigest = e.runner.observed(LearnLevel.Balanced, tier)!!
        assertFalse("高档的一句" in noDigest)
        assertTrue("徒步" in noDigest)
        e.on = false
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, tier))
    }

    // "App access" cannot be read: raw text, sentences and topics all remain, but nothing new is captured, nothing is sent and nothing is carried into tasks; once readable again it is as before
    @Test fun suspendedKeepsEverythingButCollectsSendsAndCarriesNothing() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.store.data = TopicPoolData(topics = listOf(
            ObservedTopic("k1", TopicKind.Interest, "徒步", 1L, start).on("com.high" to listOf(20_000L, 19_999L)),
        ))
        e.digestStore.data = DigestData(days = listOf(
            DayDigest(19_999, start, listOf(DigestLine("昨天的一句", apps = listOf("com.high")))),
        ))
        e.post(pkg = "com.high", count = 20)
        val tier: (String) -> SourceTier = { SourceTier.High }
        val from = start
        val to = start + LearnTuning.DAY_MS
        assertTrue("昨天的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        assertEquals(20, e.runner.notices(from, to, LearnLevel.Aggressive, tier)!!.entries.size)
        assertTrue(e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!.digests.isNotEmpty())
        val journal = e.journal.texts
        val notes = e.digestStore.texts
        val calls = e.model.calls.size

        e.suspended = true
        e.runner.poke()
        runCurrent()
        assertEquals(LearnStatus.Suspended, e.runner.state.value.status)
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, tier))
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Aggressive, tier))
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier))
        // What arrives meanwhile is not captured (nor journaled), and asking for a digest by hand sends nothing
        e.post(pkg = "com.high", count = 20)
        e.runner.digestToday()
        wait(LearnTuning.DAY_MS / 2)
        assertEquals(calls, e.model.calls.size)
        assertEquals(journal, e.journal.texts)
        assertEquals(notes, e.digestStore.texts)
        assertEquals(0, e.journal.wipes)
        assertEquals(0, e.digestStore.wipes)
        assertEquals(0, e.store.wipes)
        assertEquals(1, e.store.data.topics.size)

        e.suspended = false
        e.runner.poke()
        runCurrent()
        // Only what was already queued resumes (the few entries that had not formed a batch and are now due go off for topic learning);
        // a "digest today up to now" tapped while suspended does not count and is not sent later either
        assertEquals(listOf("learn"), e.model.kinds.drop(calls))
        assertTrue("昨天的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        assertEquals(journal.size, e.runner.notices(from - LearnTuning.DAY_MS, to + LearnTuning.DAY_MS, LearnLevel.Aggressive, tier)!!.entries.size)
    }

    // Tasks reading notifications: only raw text and sentences of Apps whose tier qualifies; none when the "daily notification digest" is off or at the most conservative level
    @Test fun noticesForTasksFollowTheLevelAndTier() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.high", count = 20)
        e.post(pkg = "com.mid", count = 20)
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        val from = start
        val to = start + LearnTuning.DAY_MS
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Conservative, tier))
        val balanced = e.runner.notices(from, to, LearnLevel.Balanced, tier)!!
        assertEquals(setOf("com.high"), balanced.entries.map { it.packageName }.toSet())
        assertEquals(20, balanced.entries.size)
        assertEquals(listOf("com.high"), balanced.notes.map { it.packageName })
        // Another App has notifications too but its tier is too low: a count is given so the reader can say "one more App's were not looked at"
        assertEquals(1, balanced.withheldApps)
        val aggressive = e.runner.notices(from, to, LearnLevel.Aggressive, tier)!!
        assertEquals(40, aggressive.entries.size)
        assertEquals(2, aggressive.notes.size)
        assertEquals(0, aggressive.withheldApps)
        assertFalse(aggressive.notesUnavailable)
        // Nothing outside the time range is given
        assertTrue(e.runner.notices(to, to + LearnTuning.DAY_MS, LearnLevel.Aggressive, tier)!!.entries.isEmpty())
        // The digest store cannot be read right now (new process): raw text is still given, sentences are empty, and it can report "unavailable"
        e.digestStore.failLoad = true
        val unreadable = restarted(e).notices(from, to, LearnLevel.Aggressive, tier)!!
        assertEquals(40, unreadable.entries.size)
        assertTrue(unreadable.notes.isEmpty())
        assertTrue(unreadable.notesUnavailable)
        e.digestStore.failLoad = false
        e.digest = false
        assertEquals(null, e.runner.notices(from, to, LearnLevel.Aggressive, tier))
    }

    // ---- Level 3 (weekly) and level 4 (monthly) of the daily notification digest ----
    // The test's first day (epoch day 20000) is Friday 2024-10-04: that week runs from 9-30 (19996) to 10-06 and counts as October; next Monday is 20003; 11-01 is 20028

    private val weekLine = """{"lines":[{"ids":["d1"],"text":"10 月 4 日买的东西发货了"}],"brief":"这一周:买的东西发货"}"""
    private val monthLine = """{"lines":[{"ids":["d1"],"text":"十月初买的东西到了"}],"brief":"这个月:东西到了"}"""

    /** An environment with one segmented digest sentence on Friday and Friday's daily digest made on Saturday. */
    private fun TestScope.withADay(): Env = withANote().also { e ->
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("day", e.model.kinds.last())
    }

    // Once a week is over (it only looks when something happens) that week's daily digests are merged into one: only daily digest sentences go into the request, each stating its day; once done it is not redone
    @Test fun weekDigestIsMadeOnceTheWeekIsOver() = runTest {
        val e = withADay()
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("Sunday: the week is not over yet", "week" in e.model.kinds)
        wait(LearnTuning.DAY_MS)
        assertFalse("no timer", "week" in e.model.kinds)
        nudge(e)
        assertEquals("week", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "Week: 2024-09-30 (Mon) to 2024-10-06 (Sun)" in input)
        assertTrue(input, "[d1] date: 2024-10-04 (Fri), apps: App shop" in input && "买的东西发货了,今天到" in input)
        assertFalse("no notification raw text", "text 1" in input || "title 1" in input)
        assertTrue(e.model.tools.last().isEmpty())
        val w = e.digestStore.data.weeks.single()
        assertEquals(19_996L, w.start)
        assertFalse(w.partial)
        // The one-sentence overview the model gave along with them is stored too
        assertEquals("这一周:买的东西发货", w.brief)
        val line = w.lines.single()
        assertEquals("10 月 4 日买的东西发货了", line.text)
        assertEquals(listOf(20_000L), line.days)
        assertEquals(listOf("com.shop"), line.apps)
        assertEquals(e.digestStore.data.notes.map { it.id }, line.notes)
        assertEquals(listOf(19_996L), e.runner.digestWeeks.value.map { it.start })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        assertTrue(e.digestStore.data.months.isEmpty())
    }

    // Once a month is over, the weekly digests of the weeks ending in it are merged into one; the input is only weekly digest sentences
    @Test fun monthDigestIsMadeOnceTheMonthIsOver() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("week", e.model.kinds.last())
        wait(20 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("October is not over yet", "month" in e.model.kinds)
        wait(5 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals("month", e.model.kinds.last())
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "Month: 2024-10" in input)
        assertTrue(input, "[d1] week: 2024-09-30 (Mon) to 2024-10-06 (Sun), apps: App shop" in input && "10 月 4 日买的东西发货了" in input)
        assertFalse("no day-digest sentences", "今天到" in input)
        val m = e.digestStore.data.months.single()
        assertEquals("这个月:东西到了", m.brief)
        assertEquals(19_997L, m.start)
        assertFalse(m.partial)
        assertEquals(listOf(19_996L), m.lines.single().weeks)
        assertEquals(listOf(20_000L), m.lines.single().days)
        assertEquals(listOf(19_997L), e.runner.digestMonths.value.map { it.start })
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // The user taps "digest this week up to now": if today has new sentences, today up to now is made first, then the week; both are marked "up to now" and each is redone complete afterwards
    @Test fun weekOnDemandDoesTodayFirstAndIsRedoneAfterTheWeek() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(listOf("day", "week"), e.model.kinds.takeLast(2))
        assertTrue(e.digestStore.data.days.single().partial)
        assertTrue(e.digestStore.data.weeks.single().partial)
        // Tapping again: today has no new sentences, so only the week is redone
        e.runner.digestWeek()
        runCurrent()
        assertEquals(listOf("day", "week", "week"), e.model.kinds.takeLast(3))
        // Without a tap it does not do it by itself
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        wait(3 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(listOf("day", "week"), e.model.kinds.takeLast(2))
        assertFalse(e.digestStore.data.days.single().partial)
        assertFalse(e.digestStore.data.weeks.single().partial)
        // With nothing in the week, a tap sends no request
        val empty = Env(this)
        empty.digest = true
        empty.runner.digestWeek()
        empty.runner.digestMonth()
        runCurrent()
        assertTrue(empty.model.calls.isEmpty())
    }

    // The user taps "digest this month up to now": today, this week and this month are done in turn, all marked "up to now"
    @Test fun monthOnDemandDoesTodayAndTheWeekFirst() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(listOf("day", "week", "month"), e.model.kinds.takeLast(3))
        val m = e.digestStore.data.months.single()
        assertTrue(m.partial)
        assertEquals(19_997L, m.start)
        assertEquals("十月初买的东西到了", m.lines.single().text)
    }

    // The week's daily digests have nothing worth saying: without asking the model it is recorded as done (otherwise the level above keeps waiting for it); the same for the month
    @Test fun aWeekWithNothingToMergeIsRecordedWithoutAModelCall() = runTest {
        val e = withANote()
        e.model.dayReply = { """{"lines":[]}""" }
        wait(LearnTuning.DAY_MS)
        nudge(e)
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("week" in e.model.kinds)
        val w = e.digestStore.data.weeks.single()
        assertTrue(w.lines.isEmpty() && !w.partial)
        wait(25 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("month" in e.model.kinds)
        assertTrue(e.digestStore.data.months.single().lines.isEmpty())
    }

    // Notifications in this week have not been segmented yet: the segmented digest runs first, then that day's daily digest, and only then this week's
    @Test fun weekDigestWaitsForTheWeeksNotesAndDays() = runTest {
        val e = withADay()
        // A few arrived on Saturday, not yet enough for a batch
        e.post(pkg = "com.mail", count = 3)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周六的邮件"}]}""" }
        e.model.weekReply = { """{"lines":[{"ids":["d1","d2"],"text":"两天的事"}]}""" }
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(listOf("notes", "day", "week"), e.model.kinds.takeLast(3))
        val input = e.model.input(e.model.calls.lastIndex)
        assertTrue(input, "[d1] date: 2024-10-04 (Fri)" in input && "[d2] date: 2024-10-05 (Sat)" in input)
        assertEquals(listOf(20_000L, 20_001L), e.digestStore.data.weeks.single().lines.single().days)
    }

    // Monday morning made last week's digest first, and Sunday night's notifications are captured only afterwards: once Sunday's daily digest is made, last week is redone along with it
    @Test fun aWeekIsRedoneOnceWhenOneOfItsDaysIsDoneLater() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周日夜里补收到的"}]}""" }
        e.model.weekReply = { """{"lines":[{"ids":["d1","d2"],"text":"重做的一周"}]}""" }
        wait(60_000)
        // They were posted on Sunday
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals(listOf("notes", "day", "week"), e.model.kinds.filter { it != "learn" }.takeLast(3))
        val w = e.digestStore.data.weeks.single()
        assertEquals(listOf("重做的一周"), w.lines.map { it.text })
        assertEquals(listOf(20_000L, 20_002L), w.lines.single().days)
        val calls = e.model.calls.size
        nudge(e)
        assertEquals(calls, e.model.calls.size)
    }

    // The reply is always unreadable: at most 3 tries per week within one process, then it stops
    @Test fun unreadableWeekDigestIsTriedAFewTimesOnly() = runTest {
        val e = withADay()
        e.model.weekReply = { "I cannot do that." }
        wait(2 * LearnTuning.DAY_MS)
        repeat(8) {
            nudge(e)
            wait(LearnTuning.FAILURE_HOLD_MS.last())
        }
        assertEquals(LearnTuning.SPAN_TRIES_MAX, e.model.kinds.count { it == "week" })
        assertTrue(e.digestStore.data.weeks.isEmpty())
    }

    // Clearing paths: clearing this App's, turning off the "daily notification digest" and clearing all take weekly and monthly digests with them; a single week or month can be deleted; deleting a segmented digest sentence removes every line based on it at every level
    @Test fun spanDigestsFollowThePurgeAndDeletePaths() = runTest {
        fun TestScope.made(): Env = withANote().also { e ->
            e.model.weekReply = { weekLine }
            e.model.monthReply = { monthLine }
            e.runner.digestMonth()
            runCurrent()
            assertEquals(1, e.digestStore.data.weeks.single().lines.size)
            assertEquals(1, e.digestStore.data.months.single().lines.size)
        }
        made().let { e ->
            e.runner.clearApp("com.shop")
            assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        }
        // Turning off the "daily notification digest": weekly and monthly digests stay
        made().let { e ->
            e.digest = false
            e.runner.poke()
            assertEquals(1, e.digestStore.data.weeks.single().lines.size)
            assertEquals(1, e.digestStore.data.months.single().lines.size)
            assertEquals(1, e.runner.digestWeeks.value.size)
        }
        made().let { e -> e.runner.clearAll(); assertTrue(e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty()) }
        made().let { e ->
            e.runner.deleteNote(e.digestStore.data.notes.single().id)
            assertTrue(e.digestStore.data.days.single().lines.isEmpty())
            assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        }
        made().let { e ->
            val week = e.digestStore.data.weeks.single().start
            e.runner.deleteSpan(SpanKind.Week, week)
            assertTrue(e.runner.digestWeeks.value.isEmpty())
            assertTrue(e.digestStore.data.weeks.single().let { it.removed && it.lines.isEmpty() })
            assertTrue("the month digest's sentences based on this week are removed too", e.digestStore.data.months.single().lines.isEmpty())
            val month = e.digestStore.data.months.single().start
            e.runner.deleteSpan(SpanKind.Month, month)
            assertTrue(e.runner.digestMonths.value.isEmpty())
            assertEquals(setOf(SpanKind.Week to week, SpanKind.Month to month), e.runner.digestRemoved.value)
        }
    }

    // While the weekly digest is in flight: clear all and the result is not written back; when one App stops being learned from, the request is cancelled
    @Test fun purgesCancelASpanRequestInFlight() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestToday()
        runCurrent()
        e.model.gate = CompletableDeferred()
        e.runner.digestWeek()
        runCurrent()
        assertEquals("week", e.model.kinds.last())
        assertTrue(e.runner.requestActive)
        e.optOut += "com.shop"
        e.runner.forgetApps(setOf("com.shop"))
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.digestStore.data.weeks.isEmpty())
        val f = withANote()
        f.model.weekReply = { weekLine }
        f.runner.digestToday()
        runCurrent()
        f.model.gate = CompletableDeferred()
        f.runner.digestWeek()
        runCurrent()
        assertTrue(f.runner.requestActive)
        f.runner.clearAll()
        assertFalse(f.runner.requestActive)
        f.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(f.digestStore.data.weeks.isEmpty() && f.digestStore.data.days.isEmpty())
    }

    // "This month up to now": when today's week only ends next month, that week counts towards next month and is kept out of this one — only today up to now is made, no week or month
    @Test fun monthOnDemandLeavesOutAWeekThatEndsNextMonth() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { oneLine }
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        // 10-30, a Wednesday: this week ends on 11-03
        wait(26 * LearnTuning.DAY_MS)
        e.post(pkg = "com.shop", count = 20)
        e.runner.digestMonth()
        runCurrent()
        assertEquals("day", e.model.kinds.last())
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
        assertTrue(e.digestStore.data.days.single().partial)
        assertTrue(e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty())
        // A tap with nothing to do is not kept: no monthly digest appears by itself later either
        nudge(e)
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
    }

    // The section carried into tasks: the digest of the week and month just finished (the complete one) is carried too, again only with sentences whose source Apps all qualify; an "up to now" one does not count as last week
    @Test fun observedCarriesLastWeekAndLastMonth() = runTest {
        val e = Env(this)
        e.digest = true
        // Today is 20000 (Friday 2024-10-04): last week starts at 19989 (9-23) and last month is September (covering 8-26 to 9-29)
        val lastWeek = 19_989L
        val sept = java.time.LocalDate.of(2024, 9, 1).toEpochDay()
        e.digestStore.data = DigestData(
            weeks = listOf(
                SpanDigest(lastWeek, start, listOf(DigestLine("上周高档的一句", apps = listOf("com.high")), DigestLine("上周中档的一句", apps = listOf("com.mid")))),
                SpanDigest(19_996L, start, listOf(DigestLine("本周到现在的", apps = listOf("com.high"))), partial = true),
            ),
            months = listOf(SpanDigest(sept, start, listOf(DigestLine("九月高档的一句", apps = listOf("com.high"))))),
        )
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        val balanced = e.runner.observed(LearnLevel.Balanced, tier)!!
        assertTrue(balanced, "Last week (2024-09-23 to 2024-09-29):" in balanced && "上周高档的一句" in balanced)
        assertTrue(balanced, "Last month (2024-08-26 to 2024-09-29):" in balanced && "九月高档的一句" in balanced)
        for (no in listOf("上周中档的一句", "本周到现在的")) assertFalse(no, no in balanced)
        assertTrue("上周中档的一句" in e.runner.observed(LearnLevel.Aggressive, tier)!!)
        // Last week's is still only "up to now" (not redone after the week ended): not carried
        e.runner.clearAll()
        e.digestStore.data = DigestData(weeks = listOf(SpanDigest(lastWeek, start, listOf(DigestLine("半截的", apps = listOf("com.high"))), partial = true)))
        val f = Env(this).also { it.digest = true; it.digestStore.data = e.digestStore.data }
        assertEquals(null, f.runner.observed(LearnLevel.Balanced, tier))
    }

    // Tasks reading stored digests: at each level newest first, only sentences whose source Apps all qualify, a digest yielding no sentences is not listed, and withheld sentences are counted; the conditions match those for raw notifications
    @Test fun digestsForTasksFollowTheLevelAndTier() = runTest {
        val e = Env(this)
        e.digest = true
        val oct = java.time.LocalDate.of(2024, 10, 1).toEpochDay()
        e.digestStore.data = DigestData(
            days = listOf(
                DayDigest(19_999, start, listOf(DigestLine("昨天高档的", apps = listOf("com.high")), DigestLine("昨天两档合的", apps = listOf("com.high", "com.mid"))), leftOut = 4),
                DayDigest(20_000, start, listOf(DigestLine("今天中档的", apps = listOf("com.mid"))), partial = true),
            ),
            weeks = listOf(SpanDigest(19_989, start, listOf(DigestLine("上周高档的", apps = listOf("com.high"))))),
            months = listOf(SpanDigest(oct, start, listOf(DigestLine("十月高档的", apps = listOf("com.high"))), partial = true)),
        )
        val tier: (String) -> SourceTier = { if (it == "com.high") SourceTier.High else SourceTier.Mid }
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Conservative, tier))
        val days = e.runner.digests(DigestLevel.Day, LearnLevel.Balanced, tier)!!
        assertEquals(listOf(19_999L), days.digests.map { it.start })
        assertEquals(listOf("昨天高档的"), days.digests.single().lines.map { it.text })
        // Sentences left out when it was made are still reported to the reader
        assertEquals(4, days.digests.single().leftOut)
        assertEquals(2, days.withheldLines)
        val all = e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, tier)!!
        assertEquals(listOf(20_000L, 19_999L), all.digests.map { it.start })
        assertEquals(listOf(true, false), all.digests.map { it.partial })
        assertEquals(0, all.withheldLines)
        val week = e.runner.digests(DigestLevel.Week, LearnLevel.Balanced, tier)!!.digests.single()
        assertEquals(19_989L to 19_995L, week.start to week.end)
        // Monthly digest: it reports the days it actually covers (October 2024 covers 9-30 to 10-27)
        val month = e.runner.digests(DigestLevel.Month, LearnLevel.Balanced, tier)!!.digests.single()
        assertEquals(java.time.LocalDate.of(2024, 9, 30).toEpochDay() to java.time.LocalDate.of(2024, 10, 27).toEpochDay(), month.start to month.end)
        assertTrue(month.partial)
        e.digest = false
        assertEquals(null, e.runner.digests(DigestLevel.Week, LearnLevel.Aggressive, tier))
    }

    // ---- Once deleted, it is not made automatically again ----

    // Deleting yesterday's daily digest: it is not made again — not even when that day still has sentences or notifications from it are captured later; Monday will not use it for the weekly digest either
    @Test fun aDeletedDayDigestIsNotMadeAgain() = runTest {
        val e = withADay()
        e.runner.deleteDay(20_000)
        assertTrue(e.runner.digestDays.value.isEmpty())
        val days = e.model.kinds.count { it == "day" }
        nudge(e)
        assertEquals(days, e.model.kinds.count { it == "day" })
        // Friday's notifications are captured later (posted on Friday) and become sentences: Friday's daily digest is still not made
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", start + 20 * 3_600_000L + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals("notes", e.model.kinds.filter { it != "learn" }.last())
        assertEquals(days, e.model.kinds.count { it == "day" })
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("week" in e.model.kinds)
        assertTrue(e.runner.digestWeeks.value.isEmpty())
    }

    // Deleting last week's digest: it is not made again — even when Sunday night's notifications are captured later and Sunday's daily digest is made, it is not redone along with them; the monthly digest does not take it as input either
    @Test fun aDeletedWeekDigestIsNotMadeAgain() = runTest {
        val e = withADay()
        wait(2 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        assertTrue(e.runner.digestWeeks.value.isEmpty())
        assertEquals(setOf(SpanKind.Week to 19_996L), e.runner.digestRemoved.value)
        nudge(e)
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"周日夜里补收到的"}]}""" }
        wait(60_000)
        repeat(20) { i -> e.runner.capture(NoticeRecord("late$i", "com.mail", e.now() - LearnTuning.DAY_MS + i, "title", "late $i")) }
        runCurrent()
        nudge(e)
        assertEquals("day", e.model.kinds.filter { it != "learn" }.last())
        assertEquals(1, e.model.kinds.count { it == "week" })
        wait(26 * LearnTuning.DAY_MS)
        nudge(e)
        assertFalse("month" in e.model.kinds)
        assertTrue(e.digestStore.data.months.isEmpty())
    }

    // Deleting this week's "up to now" digest: it does not reappear by itself and is not made automatically when the week ends; another tap remakes it, and it is then completed as usual at the weekend
    @Test fun aDeletedCurrentWeekComesBackOnlyWhenAskedAgain() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        nudge(e)
        wait(3 * LearnTuning.DAY_MS)
        nudge(e)
        assertEquals(1, e.model.kinds.count { it == "week" })
        assertTrue(e.runner.digestWeeks.value.isEmpty())
        // It is the next week by now: the deleted week has no button left to tap. A separate environment checks "tap again"
        val f = withANote()
        f.model.weekReply = { weekLine }
        val thisWeek = DigestPool.weekOf(f.now() / LearnTuning.DAY_MS)
        f.runner.digestWeek()
        runCurrent()
        f.runner.deleteSpan(SpanKind.Week, thisWeek)
        assertEquals(setOf(SpanKind.Week to thisWeek), f.runner.digestRemoved.value)
        f.runner.digestWeek()
        runCurrent()
        assertEquals(2, f.model.kinds.count { it == "week" })
        assertTrue(f.runner.digestRemoved.value.isEmpty())
        assertTrue(f.runner.digestWeeks.value.single().partial)
    }

    // "Digest this week up to now" was just tapped and then deleted before it was sent: that tap is void and does not surface later
    @Test fun deletingClearsAQueuedTap() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestToday()
        runCurrent()
        e.busy.value = true
        runCurrent()
        e.runner.digestMonth()
        runCurrent()
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        e.runner.deleteSpan(SpanKind.Month, 19_997)
        e.busy.value = false
        runCurrent()
        assertFalse("week" in e.model.kinds || "month" in e.model.kinds)
    }

    // A topic the user deleted on its own: when the model starts the same name again it is not accepted, the page shows a count and the drop reason records it; after allowing them again it can be learned. Clear all does not count as this
    @Test fun aDeletedTopicIsNotLearnedAgain() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.post(count = LearnTuning.DUE_COUNT)
        e.runner.deleteTopic(e.store.data.topics.single().id)
        assertEquals(1, e.runner.topicsBlocked.value)
        assertEquals(1, e.store.data.blocked.size)
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(2, e.model.calls.size)
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(1, e.runner.state.value.feed.getValue("com.shop").dropped[LearnDrop.Blocked])
        e.runner.allowDeletedTopics()
        assertEquals(0, e.runner.topicsBlocked.value)
        assertTrue(e.store.data.blocked.isEmpty())
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
        // Clear all is a fresh start: nothing is recorded
        e.runner.clearAll()
        assertEquals(0, e.runner.topicsBlocked.value)
        e.post(count = LearnTuning.DUE_COUNT)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // After this week's digest is deleted, tapping "digest this month up to now": it does not bring this week back along the way; with no other week to use in this month, nothing is made
    @Test fun monthOnDemandDoesNotBringBackADeletedWeek() = runTest {
        val e = withANote()
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestWeek()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        e.runner.deleteSpan(SpanKind.Week, 19_996)
        e.runner.digestMonth()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "week" })
        assertFalse("month" in e.model.kinds)
        assertEquals(setOf(SpanKind.Week to 19_996L), e.runner.digestRemoved.value)
        assertTrue(e.runner.digestWeeks.value.isEmpty() && e.runner.digestMonths.value.isEmpty())
        // After today's daily digest is deleted, tapping "digest this week up to now": today's is likewise not brought back
        val f = withANote()
        f.model.weekReply = { weekLine }
        f.runner.digestToday()
        runCurrent()
        val today = f.now() / LearnTuning.DAY_MS
        f.runner.deleteDay(today)
        val days = f.model.kinds.count { it == "day" }
        f.runner.digestWeek()
        runCurrent()
        assertEquals(days, f.model.kinds.count { it == "day" })
        assertFalse("week" in f.model.kinds)
        assertTrue(f.runner.digestDays.value.isEmpty())
    }

    // ---- While the switches are off: what was already learned stays, and clearing, deleting and expiry still run ----

    /** An environment with one sentence and one daily, weekly and monthly digest each. */
    private fun TestScope.withEveryLevel(): Env = withANote().also { e ->
        e.model.weekReply = { weekLine }
        e.model.monthReply = { monthLine }
        e.runner.digestMonth()
        runCurrent()
        assertEquals(1, e.digestStore.data.months.single().lines.size)
    }

    // Turning the master switch off (consent goes with it): requests are cancelled and raw notifications and unfinished work are cleared; sentences and digests at each level stay, and no further request is sent
    @Test fun turningTheFeatureOffKeepsWhatWasDistilled() = runTest {
        val e = withEveryLevel()
        e.post(pkg = "com.shop", count = 3)
        assertTrue(e.journal.data!!.entries.isNotEmpty())
        e.on = false
        e.consent = null
        e.runner.clear()
        assertEquals(null, e.journal.data)
        assertEquals(1, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.days.single().lines.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        assertEquals(0, e.digestStore.wipes)
        val calls = e.model.calls.size
        e.runner.digestToday(); e.runner.digestWeek(); e.runner.digestMonth()
        e.post(pkg = "com.shop", count = 20)
        nudge(e)
        assertEquals(calls, e.model.calls.size)
        // While off nothing is carried into tasks and the tools cannot read it either
        val high: (String) -> SourceTier = { SourceTier.High }
        assertEquals(null, e.runner.observed(LearnLevel.Aggressive, high))
        assertEquals(null, e.runner.digests(DigestLevel.Day, LearnLevel.Aggressive, high))
    }

    // While off (master switch off, or "daily notification digest" off): clearing an App, tuning it to no longer learn, single deletes and clear all all still remove sentences at every level
    @Test fun purgesAndDeletesWorkWhileSwitchedOff() = runTest {
        for (off in listOf<(Env) -> Unit>({ it.on = false; it.consent = null; it.runner.clear() }, { it.digest = false; it.runner.poke() })) {
            withEveryLevel().let { e ->
                off(e)
                e.runner.clearApp("com.shop")
                assertTrue(e.digestStore.data.notes.isEmpty())
                assertTrue(e.digestStore.data.days.single().lines.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.optOut += "com.shop"
                e.runner.forgetApps(setOf("com.shop"))
                assertTrue(e.digestStore.data.notes.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.deleteNote(e.digestStore.data.notes.single().id)
                assertTrue(e.digestStore.data.days.single().lines.isEmpty())
                assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.deleteSpan(SpanKind.Week, e.digestStore.data.weeks.single().start)
                assertTrue(e.runner.digestWeeks.value.isEmpty())
                assertTrue(e.digestStore.data.months.single().lines.isEmpty())
            }
            withEveryLevel().let { e ->
                off(e)
                e.runner.clearAll()
                assertTrue(e.digestStore.data.notes.isEmpty() && e.digestStore.data.days.isEmpty() && e.digestStore.data.weeks.isEmpty() && e.digestStore.data.months.isEmpty())
            }
        }
    }

    // Retention still applies while off: when the page opens, expired sentences and digests are deleted as usual
    @Test fun expiryStillRunsWhileSwitchedOff() = runTest {
        val e = withEveryLevel()
        e.digest = false
        e.runner.poke()
        wait((LearnTuning.NOTES_KEEP_DAYS + 2) * LearnTuning.DAY_MS)
        e.runner.open()
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(1, e.digestStore.data.days.size)
        wait(LearnTuning.DAYS_KEEP * LearnTuning.DAY_MS)
        e.runner.open()
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(1, e.digestStore.data.weeks.size)
    }

    // The master switch is on but the user has never consented to sending notifications to the model (turned on before any model was configured): learning accumulates nothing and raw text is not written to disk;
    // capturing starts only after consent. If consent was given and only the model changed while waiting for re-confirmation, it captures but does not send
    @Test fun nothingIsGatheredForLearningBeforeConsent() = runTest {
        val e = Env(this)
        e.digest = true
        e.consent = null
        e.post(pkg = "com.shop", count = 20)
        assertEquals(0, e.runner.state.value.pending)
        assertEquals(null, e.journal.data)
        assertTrue(e.model.calls.isEmpty())
        e.consent = TriageConsent.of(target)
        e.runner.poke()
        e.model.notesReply = { oneNote }
        e.post(pkg = "com.shop", count = 20)
        assertEquals(listOf("learn", "notes"), e.model.kinds)
        // The model changed: the consent record is still there but does not match the current model. Nothing is sent, but capturing continues
        val f = Env(this)
        f.digest = true
        f.effective = TriageTarget("other", "Other", "m2")
        f.post(pkg = "com.shop", count = 20)
        assertTrue(f.model.calls.isEmpty())
        assertEquals(20, f.runner.state.value.pending)
        assertEquals(LearnStatus.NeedsConsent, f.runner.state.value.status)
    }

    // ---- Clearing must be thorough, and nothing is wrongly deleted while unreadable ----

    // When the roster of which Apps may be read is unreadable, every App is "ineligible": nothing is captured or sent, but not one stored raw notification or digest sentence is deleted.
    // The user then checks just one App back in and what the others accumulated stays — only named ones (unchecked, tuned to low, blocked) are deleted
    @Test fun onlyNamedAppsAreForgotten() = runTest {
        val e = withEveryLevel()
        e.post(pkg = "com.shop", count = 3)
        lockAndUnlock(e)
        val journal = e.journal.data!!.entries.size
        // The roster is unreadable: com.shop suddenly becomes "ineligible"
        e.optOut += "com.shop"
        e.runner.poke()
        assertEquals(1, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.days.single().lines.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        assertEquals(journal, e.journal.data!!.entries.size)
        val calls = e.model.calls.size
        e.post(pkg = "com.shop", count = 20)
        assertEquals(calls, e.model.calls.size)
        // The user checks another App back in (the roster becomes readable and holds no com.shop): com.shop's digests are still there
        e.runner.forgetApps(emptySet())
        e.runner.poke()
        assertEquals(1, e.digestStore.data.months.single().lines.size)
        // Only named Apps are deleted
        e.runner.forgetApps(setOf("com.shop"))
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertTrue(e.digestStore.data.days.single().lines.isEmpty())
        assertTrue(e.digestStore.data.weeks.single().lines.isEmpty() && e.digestStore.data.months.single().lines.isEmpty())
        assertTrue(e.journal.data!!.entries.isEmpty())
        // Named while the digest store cannot be read: record it and replay once readable
        val f = Env(this)
        f.digestStore.data = DigestData(days = listOf(DayDigest(19_990, start, listOf(DigestLine("购物的一句", apps = listOf("com.shop"))))))
        f.digestStore.failLoad = true
        f.runner.forgetApps(setOf("com.shop"))
        assertEquals(setOf("com.shop"), f.purges.digestPurges)
        f.digestStore.failLoad = false
        f.runner.open()
        assertTrue(f.digestStore.data.days.single().lines.isEmpty())
        assertTrue(f.purges.digestPurges.isEmpty())
    }

    // "Clear what came from an App": its batch in flight is cancelled and a late reply adds no topics back; its unsent entries in the buffer are dropped so they are never sent to the model later
    @Test fun clearingAnAppStopsItsBatchAndDropsItsPendingEvents() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.model.gate = CompletableDeferred()
        e.post(pkg = "com.shop", count = LearnTuning.DUE_COUNT + 5)
        e.post(pkg = "com.other", count = 2)
        assertTrue(e.runner.requestActive)
        assertEquals(listOf("com.shop"), e.model.packages())
        e.runner.clearApp("com.shop")
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        // Only the other App's two entries remain
        assertEquals(2, e.runner.state.value.pending)
        e.model.gate = null
        wait(LearnTuning.DUE_AGE_MS + 1)
        e.post(pkg = "com.other")
        assertEquals(listOf("com.shop", "com.other"), e.model.packages())
        assertFalse(e.model.input(1).contains("com.shop"))
    }

    // "Clear all": the batch in flight is cancelled, its result is not written back, and the buffer is emptied
    @Test fun clearingEverythingStopsTheBatchAndEmptiesTheBuffer() = runTest {
        val e = Env(this)
        e.model.reply = { newTopic("n1", "n2") }
        e.model.gate = CompletableDeferred()
        e.post(pkg = "com.shop", count = LearnTuning.DUE_COUNT + 5)
        assertTrue(e.runner.requestActive)
        e.runner.clearAll()
        assertFalse(e.runner.requestActive)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertTrue(e.store.data.topics.isEmpty())
        assertEquals(0, e.runner.state.value.pending)
        e.model.gate = null
        wait(LearnTuning.DUE_AGE_MS + 1)
        nudge(e)
        assertEquals(1, e.model.calls.size)
    }

    // "Clear what came from an App" while the digest store cannot be read (the keystore is briefly unavailable): do not delete it wholesale (it holds monthly digests kept for two years); record it instead;
    // replay it once readable, and stop recording it once done and saved — later accumulations are unaffected
    @Test fun clearingAnAppWhileDigestsAreUnreadableIsDoneLaterNotByWipingEverything() = runTest {
        val e = Env(this)
        e.digest = true
        e.digestStore.data = DigestData(
            days = listOf(DayDigest(19_990, start, listOf(DigestLine("购物的一句", apps = listOf("com.shop")), DigestLine("聊天的一句", apps = listOf("com.chat"))))),
            months = listOf(SpanDigest(19_967, start, listOf(DigestLine("两个合起来的", apps = listOf("com.shop", "com.chat")), DigestLine("只有聊天的", apps = listOf("com.chat"))))),
        )
        e.digestStore.failLoad = true
        e.runner.clearApp("com.shop")
        assertEquals(0, e.digestStore.wipes)
        assertEquals(setOf("com.shop"), e.purges.digestPurges)
        assertEquals(2, e.digestStore.data.days.single().lines.size)
        e.digestStore.failLoad = false
        e.runner.open()
        assertEquals(listOf("聊天的一句"), e.digestStore.data.days.single().lines.map { it.text })
        assertEquals(listOf("只有聊天的"), e.digestStore.data.months.single().lines.map { it.text })
        assertTrue(e.purges.digestPurges.isEmpty())
        // Clear all also wipes what was recorded
        val f = Env(this)
        f.digestStore.failLoad = true
        f.runner.clearApp("com.shop")
        assertEquals(setOf("com.shop"), f.purges.digestPurges)
        f.runner.clearAll()
        assertTrue(f.purges.digestPurges.isEmpty())
    }

    // ---- Deleted but not saved: recorded and replayed on the next read; only once saved is it no longer recorded ----

    /** A new process over the same stores and the same recorded deletes. */
    private fun restarted(e: Env) = LearnRunner(
        TriageHooks(target = { target }, model = { null }, tokensToday = { 0 }, callsToday = { 0 },
            usageVersion = { MutableStateFlow(0L) }, busy = e.busy),
        on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
        language = { "English" }, eligible = { true }, sensitive = { false }, store = e.store,
        contactStore = e.contacts, purges = e.purges, digest = { true }, journalStore = e.journal,
        digestStore = e.digestStore, clock = { e.now() }, zone = { ZoneOffset.UTC },
    )

    private fun seedDigests(e: Env) {
        val at = e.now()
        e.digestStore.data = DigestData(
            notes = listOf(
                DigestNote("d1", "com.shop", 20_000L, at, at, 1, text = "店里的一句", madeAtMs = at),
                DigestNote("d2", "com.mail", 20_000L, at, at, 1, text = "邮件的一句", madeAtMs = at),
            ),
            days = listOf(
                DayDigest(20_000, at, listOf(DigestLine("今天店里的", notes = listOf("d1"), apps = listOf("com.shop")), DigestLine("今天邮件的", notes = listOf("d2"), apps = listOf("com.mail")))),
                DayDigest(19_999, at, listOf(DigestLine("昨天邮件的", apps = listOf("com.mail")))),
            ),
            weeks = listOf(SpanDigest(19_992, at, listOf(DigestLine("上周邮件的", apps = listOf("com.mail"))))),
            months = listOf(SpanDigest(19_967, at, listOf(DigestLine("上月邮件的", apps = listOf("com.mail"))))),
        )
        e.runner.open()
    }

    // Clearing an App, tuning it to unread or low: when the digest store reads fine but the delete cannot be saved, it must still be recorded (not only when it cannot be read)
    @Test fun purgingAnAppIsRememberedWhenTheDigestCannotBeSaved() = runTest {
        for (viaTier in listOf(false, true)) {
            val e = Env(this)
            seedDigests(e)
            e.digestStore.failSave = true
            if (viaTier) e.runner.forgetApps(setOf("com.shop")) else e.runner.clearApp("com.shop")
            assertTrue("$viaTier", "com.shop" in e.purges.digestPurges)
            // Invisible within this run already; the file still holds the old data
            assertEquals(listOf("邮件的一句"), e.runner.digestNotes.value.map { it.text })
            assertEquals(2, e.digestStore.data.notes.size)
            // New process: replayed on read, and no longer recorded once saved
            e.digestStore.failSave = false
            val again = restarted(e)
            again.open()
            assertEquals(listOf("邮件的一句"), again.digestNotes.value.map { it.text })
            assertEquals(listOf("邮件的一句"), e.digestStore.data.notes.map { it.text })
            assertTrue(e.digestStore.data.days.none { d -> d.lines.any { "com.shop" in it.apps } })
            assertFalse("com.shop" in e.purges.digestPurges)
        }
    }

    // Deleting a single sentence, a day, a week or a month: a failed save plus process death must not bring it back on the next start
    @Test fun singleDigestDeletesSurviveAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        seedDigests(e)
        e.digestStore.failSave = true
        e.runner.deleteNote("d1")
        e.runner.deleteDay(19_999)
        e.runner.deleteSpan(SpanKind.Week, 19_992)
        e.runner.deleteSpan(SpanKind.Month, 19_967)
        assertEquals(setOf(":note:d1", ":day:19999", ":week:19992", ":month:19967"), e.purges.digestPurges)
        assertEquals(listOf("d2"), e.runner.digestNotes.value.map { it.id })
        // Nothing is missing from the file
        assertEquals(2, e.digestStore.data.notes.size)
        assertEquals(1, e.digestStore.data.weeks.single().lines.size)

        e.digestStore.failSave = false
        val again = restarted(e)
        again.open()
        assertEquals(listOf("d2"), again.digestNotes.value.map { it.id })
        assertEquals(listOf(20_000L), again.digestDays.value.map { it.day })
        // The daily digest lines based on that sentence are removed as well (the same as an immediate delete)
        assertEquals(listOf("今天邮件的"), again.digestDays.value.single().lines.map { it.text })
        assertTrue(again.digestWeeks.value.isEmpty())
        assertTrue(again.digestMonths.value.isEmpty())
        // The deleted day, week and month keep a "deleted" mark: they are not remade automatically
        assertTrue(e.digestStore.data.days.single { it.day == 19_999L }.removed)
        assertTrue(e.digestStore.data.weeks.single().removed)
        assertTrue(e.digestStore.data.months.single().removed)
        assertEquals(emptySet<String>(), e.purges.digestPurges)
    }

    // After a failed save, the next save in this process succeeds: it is no longer recorded — so later accumulations are not deleted afterwards
    @Test fun aLaterSuccessfulSaveSettlesTheRememberedDeletes() = runTest {
        val e = Env(this)
        seedDigests(e)
        e.digestStore.failSave = true
        e.runner.clearApp("com.shop")
        e.runner.deleteNote("d2")
        assertEquals(setOf("com.shop", ":note:d2"), e.purges.digestPurges)
        // Tapping the same delete again (nothing left to delete): this just saves once more
        e.digestStore.failSave = false
        e.runner.deleteNote("d2")
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertTrue(e.digestStore.data.notes.isEmpty())
        // This App then accumulates new data: a new process does not delete it
        e.digestStore.data = e.digestStore.data.copy(notes = listOf(DigestNote("d9", "com.shop", 20_000L, e.now(), e.now(), 1, text = "新的一句", madeAtMs = e.now())))
        val again = restarted(e)
        again.open()
        assertEquals(listOf("d9"), again.digestNotes.value.map { it.id })
    }

    // With nothing to delete, nothing is recorded and no save happens: at startup the Apps blocked in "App access" are cleared all over again, and that must not write preferences and files every time
    @Test fun forgettingAppsWithNothingStoredWritesNothing() = runTest {
        val e = Env(this)
        seedDigests(e)
        val writes = e.purges.writes
        val attempts = e.digestStore.saveAttempts
        e.runner.forgetApps(setOf("com.blocked", "com.other"))
        assertEquals(writes, e.purges.writes)
        assertEquals(attempts, e.digestStore.saveAttempts)
    }

    // Deleting one topic or one contact: a failed save plus process death must not bring it back on the next start; the topic name is still recorded in "do not learn"
    @Test fun singleTopicAndContactDeletesSurviveAFailedSaveAndARestart() = runTest {
        val e = Env(this)
        e.store.data = seededTopics()
        e.contacts.data = seededContacts()
        e.runner.open()
        val topic = e.runner.topics.value.first()
        val contact = e.runner.contactsView.value.contacts.first()
        e.store.failSave = true
        e.contacts.failSave = true
        e.runner.deleteTopic(topic.id)
        e.runner.deleteContact(contact.id)
        assertEquals(setOf(":topic:${topic.id}"), e.purges.topicPurges)
        assertEquals(setOf(":contact:${contact.id}"), e.purges.contactPurges)
        assertTrue(e.store.data.topics.any { it.id == topic.id })
        assertTrue(e.contacts.data.contacts.any { it.id == contact.id })

        e.store.failSave = false
        e.contacts.failSave = false
        val again = restarted(e)
        again.open()
        assertTrue(again.topics.value.none { it.id == topic.id })
        assertTrue(again.contactsView.value.contacts.none { it.id == contact.id })
        assertTrue(e.store.data.topics.none { it.id == topic.id })
        assertTrue(e.contacts.data.contacts.none { it.id == contact.id })
        assertEquals(1, e.store.data.blocked.size)
        assertEquals(1, e.contacts.data.blocked.size)
        assertEquals(emptySet<String>(), e.purges.topicPurges)
        assertEquals(emptySet<String>(), e.purges.contactPurges)
        // Other topics and contacts remain
        assertEquals(seededTopics().topics.size - 1, e.store.data.topics.size)
    }

    // ---- The segmented digest sentence was not saved: the raw text must not be marked done first ----

    // When the sentence is not saved the raw text stays queued (had it been marked done and saved, a restart would leave no sentence and the raw text would never be learned again, losing the batch);
    // the in-memory sentence is rolled back; while saving keeps failing no further request is sent; once saving works it is learned once more, and this time it sticks
    @Test fun notesAreNotMarkedDoneUntilTheyAreSaved() = runTest {
        val e = Env(this)
        e.digest = true
        // Saving starts failing the moment the request returns
        e.model.notesReply = { e.digestStore.failSave = true; oneNote }
        fun notesCalls() = e.model.kinds.count { it == "notes" }

        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(1, notesCalls())
        // No sentence in the file; not one raw entry marked done; no sentence from this batch left in memory either
        assertTrue(e.digestStore.data.notes.isEmpty())
        assertEquals(0, e.journal.data!!.entries.count { it.noted })
        assertTrue(e.runner.digestNotes.value.isEmpty())
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.runner.state.value.journal)

        // Saving keeps failing: even after the hold passes nothing more is sent (it saves first, and sends nothing if that fails)
        wait(LearnTuning.FAILURE_HOLD_MS.last() + 1)
        e.runner.poke()
        runCurrent()
        wait(LearnTuning.NOTES_DUE_AGE_MS)
        e.runner.poke()
        runCurrent()
        assertEquals(1, notesCalls())
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertEquals(0, e.journal.data!!.entries.count { it.noted })

        // Saving works again: the batch is learned once more, and both the sentence and the "done" marks reach the disk this time
        e.model.notesReply = { oneNote }
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(2, notesCalls())
        assertEquals(listOf("买的东西发货了"), e.digestStore.data.notes.map { it.text })
        assertEquals(LearnTuning.NOTES_DUE_COUNT, e.journal.data!!.entries.count { it.noted })
        // New process: the sentence is there and this raw batch is no longer queued
        val again = restarted(e)
        again.open()
        assertEquals(1, again.digestNotes.value.size)
    }

    // While the digest store holds an unsaved delete no segmented digest request is sent (the result could not be kept); once saving works that delete is written first, then the request goes out
    @Test fun noNotesRequestWhileADeleteIsStillUnsaved() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        val at = e.now()
        e.digestStore.data = DigestData(notes = listOf(DigestNote("old", "com.gone", 20_000L, at, at, 1, text = "要清掉的 App 的一句", madeAtMs = at)))
        e.runner.open()
        e.digestStore.failSave = true
        e.runner.clearApp("com.gone")
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(0, e.model.kinds.count { it == "notes" })
        assertEquals(LearnStatus.StoreUnavailable, e.runner.state.value.status)
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "notes" })
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertEquals(listOf("com.shop"), e.digestStore.data.notes.map { it.packageName })
    }

    // The daily digest result failed to save: the request is not re-sent, and the next time something happens it saves first
    @Test fun aDayDigestThatCouldNotBeSavedIsWrittenLaterWithoutAskingAgain() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"今天的一句"}]}""" }
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        assertEquals(1, e.digestStore.data.notes.size)
        e.digestStore.failSave = true
        e.runner.digestToday()
        runCurrent()
        assertEquals(1, e.model.kinds.count { it == "day" })
        assertTrue(e.digestStore.data.days.isEmpty())
        assertEquals(1, e.runner.digestDays.value.size)
        // Saving works again: the next event (even with nothing to learn) saves it, without asking the model again
        e.digestStore.failSave = false
        e.runner.poke()
        runCurrent()
        assertEquals(listOf("今天的一句"), e.digestStore.data.days.single().lines.map { it.text })
        assertEquals(1, e.model.kinds.count { it == "day" })
    }

    // While the segmented digest request is in flight the user clears another App's and that save happens to fail; then the sentence itself fails to save and must be rolled back:
    // what is rolled back is the state "after the clear", and that clear is still recorded and still counts
    @Test fun backingOutNotesKeepsADeleteMadeWhileTheRequestWasInFlight() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        val at = e.now()
        e.digestStore.data = DigestData(notes = listOf(DigestNote("old", "com.gone", 20_000L, at, at, 1, text = "要清掉的 App 的一句", madeAtMs = at)))
        e.runner.open()
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        // The topic digest batch goes first; let it through so the segmented digest request stays in flight
        while (e.model.kinds.lastOrNull() != "notes") {
            e.model.gate!!.complete(Unit)
            e.model.gate = CompletableDeferred()
            runCurrent()
        }
        assertTrue(e.runner.requestActive)
        e.digestStore.failSave = true
        e.runner.clearApp("com.gone")
        assertTrue(e.runner.requestActive)
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        // No sentence was kept, what was cleared did not come back, and that clear is still recorded
        assertTrue(e.runner.digestNotes.value.isEmpty())
        assertEquals(setOf("com.gone"), e.purges.digestPurges)
        assertEquals(0, e.journal.data!!.entries.count { it.noted })
        assertEquals(listOf("old"), e.digestStore.data.notes.map { it.id })
        // Saving works again: the clear reaches the disk, then the batch is learned once more
        e.digestStore.failSave = false
        wait(LearnTuning.FAILURE_HOLD_MS.last() + 1)
        e.runner.poke()
        runCurrent()
        assertEquals(emptySet<String>(), e.purges.digestPurges)
        assertEquals(listOf("com.shop"), e.digestStore.data.notes.map { it.packageName })
    }

    // ---- Retention: expired entries are removed from the file on schedule and readers cannot get them ----

    // Raw notifications: some are already expired when loaded and are dropped from memory at once — they must be removed from the file too (waiting for another change to save along could mean waiting forever)
    @Test fun expiredRawTextIsRemovedFromTheFileAsSoonAsItIsLoaded() = runTest {
        val e = Env(this)
        e.digest = true
        val old = e.now() - NoticeJournal.KEEP_MS - 1
        e.journal.data = NoticeJournalData(sinceMs = old, entries = listOf(
            NoticeEntry("com.shop", old, "Title", text = "过期的原文"),
            NoticeEntry("com.shop", e.now() - 1_000, "Title", text = "还新的原文"),
        ))
        val saves = e.journal.saves
        e.runner.poke()
        runCurrent()
        assertEquals(listOf("还新的原文"), e.journal.texts)
        assertEquals(saves + 1, e.journal.saves)
        // Without expired entries it does not save an extra time for this (a new process reads the raw text in)
        val again = restarted(e)
        val view = again.notices(e.now() - LearnTuning.DAY_MS, e.now() + 1, LearnLevel.Aggressive) { SourceTier.High }!!
        assertEquals(listOf("还新的原文"), view.entries.map { it.text })
        assertEquals(saves + 1, e.journal.saves)
    }

    // Digests at each level: when a task reads them, expired ones are withheld and removed from the file too — no need to wait for the user to open the page
    @Test fun expiredDigestsAreNeitherGivenToTasksNorKept() = runTest {
        val today = 20_000L
        fun line(text: String) = listOf(DigestLine(text, apps = listOf("com.shop")))
        val at = start
        val data = DigestData(
            notes = listOf(
                DigestNote("gone", "com.shop", today - LearnTuning.NOTES_KEEP_DAYS - 1, at - 9 * LearnTuning.DAY_MS, at - 9 * LearnTuning.DAY_MS, 1, text = "过期的一句", madeAtMs = at),
                DigestNote("kept", "com.shop", today, at, at, 1, text = "今天的一句", madeAtMs = at),
            ),
            days = listOf(DayDigest(today - LearnTuning.DAYS_KEEP - 1, 1, line("过期的一天")), DayDigest(today - 1, 1, line("昨天"))),
            weeks = listOf(
                SpanDigest(today - LearnTuning.WEEKS_KEEP_DAYS - 7, 1, line("过期的一周")),
                SpanDigest(DigestPool.weekOf(today) - 7, 1, line("上周")),
            ),
            months = listOf(
                SpanDigest(today - LearnTuning.MONTHS_KEEP_DAYS - 31, 1, line("过期的一个月")),
                SpanDigest(DigestPool.monthOf(DigestPool.monthOf(today) - 1), 1, line("上个月")),
            ),
        )
        val tier: (String) -> SourceTier = { SourceTier.High }
        // Three read paths, each starting from an unpurged file
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            val days = e.runner.digests(DigestLevel.Day, LearnLevel.Balanced, tier)!!
            assertEquals(listOf("昨天"), days.digests.flatMap { d -> d.lines.map { it.text } })
            assertEquals(listOf("上周"), e.runner.digests(DigestLevel.Week, LearnLevel.Balanced, tier)!!.digests.flatMap { d -> d.lines.map { it.text } })
            assertEquals(listOf("上个月"), e.runner.digests(DigestLevel.Month, LearnLevel.Balanced, tier)!!.digests.flatMap { d -> d.lines.map { it.text } })
            // Removed from the file too
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
            assertEquals(1, e.digestStore.data.weeks.size)
            assertEquals(1, e.digestStore.data.months.size)
        }
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            e.journal.data = NoticeJournalData(sinceMs = start, entries = emptyList())
            val view = e.runner.notices(start - 30 * LearnTuning.DAY_MS, start + LearnTuning.DAY_MS, LearnLevel.Balanced, tier)!!
            assertEquals(listOf("今天的一句"), view.notes.map { it.text })
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
        }
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            val carried = e.runner.observed(LearnLevel.Balanced, tier)!!
            assertTrue("昨天" in carried)
            for (no in listOf("过期的一天", "过期的一周", "过期的一个月")) assertFalse(no, no in carried)
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
        }
        // The same with nobody reading: it is purged during the usual look when something happens
        run {
            val e = Env(this); e.digest = true; e.digestStore.data = data
            e.post()
            assertEquals(listOf("kept"), e.digestStore.data.notes.map { it.id })
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
            assertEquals(1, e.digestStore.data.weeks.size)
            assertEquals(1, e.digestStore.data.months.size)
        }
        // With the "daily notification digest" off (data stays, retention still applies): what has been loaded is still purged
        run {
            val e = Env(this); e.digest = false
            e.digestStore.data = DigestData(days = listOf(DayDigest(today - LearnTuning.DAYS_KEEP + 1, 1, line("快到期的一天")), DayDigest(today - 1, 1, line("昨天"))))
            e.runner.open()
            assertEquals(2, e.runner.digestDays.value.size)
            wait(3 * LearnTuning.DAY_MS)
            e.post()
            assertEquals(listOf(today - 1), e.digestStore.data.days.map { it.day })
        }
    }

    // Conversation-style notifications: the body is empty and the words are in the message list — the learning request must contain the messages seen this time
    @Test fun newMessagesReachTheLearningRequest() = runTest {
        val e = Env(this)
        repeat(LearnTuning.DUE_COUNT) { i ->
            val said = NoticeMessage("Alice", "第 ${i + 1} 句:明天三点开会", e.now())
            e.runner.capture(NoticeRecord("chat$i", "com.chat", e.now(), "Alice", "", messages = listOf(said), conversation = true), listOf(said))
        }
        runCurrent()
        assertEquals(1, e.model.calls.size)
        val input = e.model.input(0)
        assertTrue("Alice: 第 1 句:明天三点开会" in input)
        assertTrue("第 ${LearnTuning.DUE_COUNT} 句" in input)
    }

    // ---- A request in flight must not be cancelled by unrelated events ----

    // The previous watch had one App's segmented digest in flight; the watch stopped and restarted, and this time another App's topic digest is in flight.
    // Now the first App stops being learned from: the leftover "its request is in flight" from last time must not cancel the current request
    @Test fun aRequestLeftFromAnEarlierWatchDoesNotCancelALaterOne() = runTest {
        val model = Fake().also { it.gate = CompletableDeferred() }
        val busy = MutableStateFlow(false)
        val journal = MemJournal().also {
            it.data = NoticeJournalData(sinceMs = start, entries = (1..LearnTuning.NOTES_DUE_COUNT).map { i -> NoticeEntry("com.old.notes", start + i, "t", text = "text $i") })
        }
        var oldAppEligible = true
        val locked = MutableStateFlow(false)
        val runner = LearnRunner(
            TriageHooks(target = { target }, model = { TargetedModel(target, model) }, tokensToday = { 0 }, callsToday = { 0 },
                usageVersion = { MutableStateFlow(0L) }, busy = busy),
            on = { true }, consent = { TriageConsent.of(target) }, budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT },
            language = { "English" }, eligible = { it != "com.old.notes" || oldAppEligible }, sensitive = { false },
            store = MemStore(), digest = { true }, journalStore = journal, digestStore = MemDigest(),
            clock = { start + testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )
        val first = backgroundScope.launch { runner.watch(locked) { locked.value } }
        runCurrent()
        assertEquals(listOf("notes"), model.kinds)
        first.cancel()
        runCurrent()
        // The watch restarts while a task is running and a batch accumulates; when the task finishes, the topic digest request goes out
        busy.value = true
        backgroundScope.launch { runner.watch(locked) { locked.value } }
        runCurrent()
        repeat(LearnTuning.DUE_COUNT) { i -> runner.capture(NoticeRecord("k$i", "com.shop", start + testScheduler.currentTime, "title $i", "text $i")) }
        runCurrent()
        busy.value = false
        runCurrent()
        assertEquals(listOf("notes", "learn"), model.kinds)
        assertTrue(runner.requestActive)
        val cancelled = model.cancelled
        oldAppEligible = false
        runner.poke()
        runCurrent()
        assertTrue(runner.requestActive)
        assertEquals(cancelled, model.cancelled)
        assertEquals(listOf("notes", "learn"), model.kinds)
    }

    // Reading the configuration fails once during a look: the request in flight is untouched and nothing new is sent this time
    @Test fun aFailedConfigurationReadLeavesTheRequestInFlightAlone() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        val real = e.effective
        e.targetFails = true
        e.runner.poke()
        runCurrent()
        assertTrue(e.runner.requestActive)
        assertEquals(0, e.model.cancelled)
        assertEquals(LearnStatus.Sending, e.runner.state.value.status)
        e.targetFails = false
        e.effective = real
        e.model.reply = { newTopic("n1") }
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertEquals(listOf("Hiking"), e.store.data.topics.map { it.label })
    }

    // A request must not stay in flight while the lock state cannot be read: not sending while locked is a hard rule, so unknown counts as locked. This batch is kept
    @Test fun aFailedLockReadStopsTheRequest() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post(count = LearnTuning.DUE_COUNT)
        assertTrue(e.runner.requestActive)
        e.lockReadFails = true
        e.runner.poke()
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
        assertEquals(LearnTuning.DUE_COUNT, e.runner.state.value.pending)
    }

    // Deleting today's daily digest: the page must be able to say "deleted, it will not be made automatically again" (not "not yet; once the day is over it will be learned automatically");
    // after the user taps "digest up to now" and makes it again, it no longer counts as deleted
    @Test fun aDeletedDayIsReportedAsDeleted() = runTest {
        val e = Env(this)
        e.digest = true
        e.model.notesReply = { oneNote }
        e.model.dayReply = { """{"lines":[{"ids":["s1"],"text":"今天的一句"}]}""" }
        e.post(count = LearnTuning.NOTES_DUE_COUNT)
        e.runner.digestToday()
        runCurrent()
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        assertTrue(e.runner.digestRemovedDays.value.isEmpty())
        e.runner.deleteDay(20_000)
        assertTrue(e.runner.digestDays.value.isEmpty())
        assertEquals(setOf(20_000L), e.runner.digestRemovedDays.value)
        // It can say so in a new process too
        val again = restarted(e)
        again.open()
        assertEquals(setOf(20_000L), again.digestRemovedDays.value)
        e.runner.digestToday()
        runCurrent()
        assertEquals(listOf(20_000L), e.runner.digestDays.value.map { it.day })
        assertTrue(e.runner.digestRemovedDays.value.isEmpty())
    }
}
