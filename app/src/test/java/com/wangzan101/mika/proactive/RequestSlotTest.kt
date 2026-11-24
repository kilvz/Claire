package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.LearnTuning
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
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

/** Triage and distillation share one request slot: proactive background requests go out one at a time, and both sides get their turn. */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestSlotTest {
    /** One model per side, with the in-flight count recorded in one place; every call waits for the test to release it. */
    private class Gauge {
        var inFlight = 0
        var most = 0
        val order = ArrayList<String>()
    }

    private class Held(private val who: String, private val gauge: Gauge, private val reply: String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val gates = ArrayList<CompletableDeferred<Unit>>()
        val calls get() = gates.size
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val gate = CompletableDeferred<Unit>().also { gates += it }
            gauge.order += who
            gauge.inFlight++
            gauge.most = maxOf(gauge.most, gauge.inFlight)
            try {
                gate.await()
            } finally {
                gauge.inFlight--
            }
            return ModelReply(listOf(Part.Text(reply)), StopReason.EndTurn, null)
        }
        fun finish(i: Int = gates.lastIndex) = gates[i].complete(Unit)
    }

    private class MemStore : TopicPoolStore {
        var data = TopicPoolData()
        override fun load() = data
        override fun save(data: TopicPoolData) { this.data = data }
        override fun dropBroken() {}
        override fun wipe() { data = TopicPoolData() }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(val scope: TestScope, shared: Boolean = true, watch: Boolean = true) {
        val gauge = Gauge()
        val judge = Held("triage", gauge, """[{"id":"n1","decision":"ignore","reason":"r"}]""")
        val distil = Held("learn", gauge, """{"signals":[]}""")
        val locked = MutableStateFlow(false)
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        private val slot = RequestSlot()
        private fun hooks(m: ChatModel) = TriageHooks(
            target = { target }, model = { TargetedModel(target, m) },
            tokensToday = { 0 }, callsToday = { 0 }, usageVersion = { MutableStateFlow(0L) }, busy = MutableStateFlow(false),
        )
        val triage = TriageRunner(
            inbox, hooks(judge), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            clock = { scope.testScheduler.currentTime }, planner = TriagePlanner(zone = { ZoneOffset.UTC }, windowMs = 0),
            slot = if (shared) slot else RequestSlot(),
        )
        val learn = LearnRunner(
            hooks(distil), on = { true }, consent = { TriageConsent.of(target) },
            budgetLimit = { ProactiveBudget.DEFAULT_DAILY_LIMIT }, language = { "English" },
            eligible = { true }, slot = if (shared) slot else RequestSlot(), sensitive = { false }, store = MemStore(),
            clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC },
        )

        var watches: List<kotlinx.coroutines.Job> = emptyList()

        /** Start watching (as in ProactiveRuntime, both sides under the same job). */
        fun start() {
            watches = listOf(
                scope.backgroundScope.launch { triage.watch(locked) { locked.value } },
                scope.backgroundScope.launch { learn.watch(locked) { locked.value } },
            )
            scope.runCurrent()
        }

        /** Stop watching (feature switched off, app fully exited). */
        fun stop() {
            watches.forEach { it.cancel() }
            scope.runCurrent()
        }

        init {
            if (watch) start()
        }

        private var n = 0
        fun record(key: String = "k${++n}") = NoticeRecord(key, "com.chat", scope.testScheduler.currentTime + n, "title $key", "text $key")
    }

    // Both sides come due at the same time: only one is sent at a time (not one each, i.e. two requests at once); triage goes first and distillation follows when it finishes, so both get their turn
    @Test fun onlyOneBackgroundRequestAtATimeAndBothGetATurn() = runTest {
        val e = Env(this)
        // Distillation has already accumulated a full batch (the triage one is not due yet)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        // Same instant: distillation has a full batch and triage also has one due
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(1, e.gauge.inFlight)
        assertEquals(listOf("triage"), e.gauge.order)
        assertTrue(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        // Triage has finished sending: distillation sends next
        e.judge.finish()
        runCurrent()
        assertEquals(listOf("triage", "learn"), e.gauge.order)
        assertTrue(e.learn.requestActive)
        e.distil.finish()
        runCurrent()
        assertEquals(0, e.gauge.inFlight)
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(1, e.triage.state.value.counts.judged)
    }

    // Triage comes due while distillation is sending: it waits and does not cut in; when distillation finishes, triage goes first and the next distillation batch queues behind it
    @Test fun triageWaitsForLearningAndThenGoesFirst() = runTest {
        val e = Env(this)
        repeat(2 * LearnTuning.DUE_COUNT) { e.learn.capture(e.record()) }
        runCurrent()
        assertEquals(listOf("learn"), e.gauge.order)
        e.inbox.add(e.record("late"))
        runCurrent()
        assertFalse(e.triage.requestActive)
        assertEquals(0, e.judge.calls)
        assertEquals(1, e.gauge.inFlight)
        e.distil.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage"), e.gauge.order)
        e.judge.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage", "learn"), e.gauge.order)
        e.distil.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
    }

    // The in-flight request is cancelled (screen locked): the slot is released; after unlocking everything runs as usual, still one at a time
    @Test fun aCancelledRequestFreesTheSlot() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertTrue(e.triage.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(0, e.gauge.inFlight)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.gauge.inFlight)
        e.judge.finish()
        runCurrent()
        e.distil.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
    }

    // Control case: with separate slots both sides send one at the same time — a shared slot is exactly what prevents this
    @Test fun withoutASharedSlotBothWouldSendAtOnce() = runTest {
        val e = Env(this, shared = false)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(2, e.gauge.most)
    }

    // Both sides come due while the slot is free: whoever looks first sends first (here distillation accumulated first), and the other waits for it to finish — still only one at a time
    @Test fun atATieWhoeverLooksFirstSendsFirst() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        batch.forEach(e.learn::capture)
        e.inbox.add(batch.first())
        runCurrent()
        assertEquals(listOf("learn"), e.gauge.order)
        assertFalse(e.triage.requestActive)
        e.distil.finish()
        runCurrent()
        assertEquals(listOf("learn", "triage"), e.gauge.order)
        e.judge.finish()
        runCurrent()
        assertEquals(1, e.gauge.most)
    }

    // When the watch stops (feature off, app fully exited) with triage sending and distillation waiting: both are reined in and no extra request is slipped in; starting the watch again, each side takes its turn as usual
    @Test fun stoppingAndRestartingTheWatchKeepsOneAtATime() = runTest {
        val e = Env(this)
        val batch = (1..LearnTuning.DUE_COUNT).map { e.record() }
        e.inbox.add(batch.first())
        batch.forEach(e.learn::capture)
        runCurrent()
        assertEquals(listOf("triage"), e.gauge.order)
        e.stop()
        assertEquals(listOf("triage"), e.gauge.order)
        assertEquals(0, e.gauge.inFlight)
        assertFalse(e.triage.requestActive)
        assertFalse(e.learn.requestActive)
        assertEquals(LearnTuning.DUE_COUNT, e.learn.state.value.pending)
        e.start()
        assertEquals(1, e.gauge.inFlight)
        // Both sides have finished sending: each got one turn, and nothing was ever in flight at the same time
        repeat(4) {
            e.judge.gates.lastOrNull()?.complete(Unit)
            e.distil.gates.lastOrNull()?.complete(Unit)
            runCurrent()
        }
        assertEquals(0, e.gauge.inFlight)
        assertEquals(1, e.gauge.most)
        assertEquals(0, e.learn.state.value.pending)
        assertEquals(1, e.triage.state.value.counts.judged)
        assertTrue("learn" in e.gauge.order.drop(1) && "triage" in e.gauge.order.drop(1))
    }
}
