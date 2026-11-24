package com.wangzan101.mika.proactive

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.proactive.NoticeInbox
import com.wangzan101.mika.core.proactive.NoticeRecord
import com.wangzan101.mika.core.proactive.TargetedModel
import com.wangzan101.mika.core.proactive.TriageConsent
import com.wangzan101.mika.core.proactive.TriageDecision
import com.wangzan101.mika.core.proactive.TriageMark
import com.wangzan101.mika.core.proactive.TriagePlanner
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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TriageRunnerTest {
    /** Each call waits for the test to release it ([release]), or replies straight from [reply]. */
    private class Fake(var reply: () -> String = { "" }) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
        val calls = mutableListOf<List<Message>>()
        val tools = mutableListOf<List<ToolSpec>>()
        var gate: CompletableDeferred<Unit>? = null
        var cancelled = 0
        /** When set, every call throws it (model unreachable and so on). */
        var error: Exception? = null
        /** When set, the next call ignores cancellation: once released it throws "budget exhausted" (used only once). */
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
            return ModelReply(listOf(Part.Text(reply())), StopReason.EndTurn, null)
        }
        fun packages() = calls.map { m -> Regex("App package: (\\S+)").find((m.single().parts.single() as Part.Text).text)!!.groupValues[1] }
    }

    private val target = TriageTarget("p1", "Provider", "m1")

    private inner class Env(scope: TestScope) {
        val inbox = NoticeInbox(clock = { scope.testScheduler.currentTime }, zone = { ZoneOffset.UTC })
        val locked = MutableStateFlow(false)
        val busy = MutableStateFlow(false)
        var on = true
        var consent: TriageConsent? = TriageConsent.of(target)
        var effective: TriageTarget? = target
        /** The target read at model-build time (same as [effective] when null): simulates the configuration changing between the two reads. */
        var built: TriageTarget? = null
        val model = Fake()
        var tokens = 0L
        var calls = 0L
        var limit = ProactiveBudget.DEFAULT_DAILY_LIMIT
        var models = 0
        var modelError: Exception? = null
        /** The step that reads "which model is configured now" fails (the configuration cannot be read temporarily). */
        var targetError: Exception? = null
        /** The step that reads the lock state fails. */
        var lockReadFails = false
        val runner = TriageRunner(
            inbox,
            TriageHooks(
                target = { targetError?.let { throw it }; effective },
                model = { call ->
                    models++
                    assertEquals("判定建模型时说的是判定", com.wangzan101.mika.core.proactive.ProactiveCall.Triage, call)
                    modelError?.let { throw it }
                    (built ?: effective)?.let { TargetedModel(it, model) }
                },
                tokensToday = { tokens }, callsToday = { calls },
                usageVersion = { MutableStateFlow(0L) }, busy = busy,
            ),
            on = { on }, consent = { consent }, budgetLimit = { limit },
            language = { "English" }, clock = { scope.testScheduler.currentTime },
            planner = TriagePlanner(zone = { ZoneOffset.UTC }),
        )

        private var watching: kotlinx.coroutines.Job? = null
        private val scope0 = scope

        /** Stop the watch and start it again (the switch was turned off and back on, app back in the foreground). */
        fun restart() {
            watching?.cancel()
            scope0.runCurrent()
            watching = scope0.backgroundScope.launch { runner.watch(locked) { if (lockReadFails) throw IllegalStateException("lock state unavailable"); locked.value } }
            scope0.runCurrent()
        }

        init {
            restart()
        }

        fun post(key: String, pkg: String = "com.chat", at: Long = 1) {
            inbox.add(NoticeRecord(key, pkg, at, "title $key", "text $key"))
        }
    }

    private fun TestScope.wait(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun ok(vararg ids: String) =
        """{"items":[${ids.joinToString(",") { """{"id":"$it","decision":"remember","reason":"r"}""" }}]}"""

    @Test fun batchIsSentAfterTheWindowWithNoTools() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1", "n2") }
        e.post("a"); e.post("b", at = 2)
        runCurrent()
        assertEquals(TriageStatus.Waiting, e.runner.state.value.status)
        assertTrue(e.runner.timerActive)
        wait(TriagePlanner.WINDOW_MS - 1)
        assertTrue(e.model.calls.isEmpty())
        wait(1)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.model.tools.single().isEmpty())
        val marks = e.runner.state.value.marks
        assertEquals(TriageDecision.Remember, (marks.getValue("a") as TriageMark.Done).verdict.decision)
        assertEquals(TriageStatus.Idle, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        // Nothing new means nothing more is sent
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
    }

    @Test fun lockedHasNoTimerAndSendsNothing() = runTest {
        val e = Env(this)
        e.locked.value = true
        runCurrent()
        e.post("a")
        runCurrent()
        assertEquals(TriageStatus.Locked, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        wait(24 * 3_600_000L)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(0, e.models)
        // Only sent after unlocking (the batching window passed long ago, so it sends right away)
        e.locked.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun lockCancelsTheTimer() = runTest {
        val e = Env(this)
        e.post("a")
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse("一锁屏就取消计时", e.runner.timerActive)
        wait(TriagePlanner.WINDOW_MS * 10)
        assertTrue(e.model.calls.isEmpty())
    }

    @Test fun lockDuringRequestCancelsItAndKeepsItemsPending() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        e.locked.value = true
        runCurrent()
        assertEquals(1, e.model.cancelled)
        assertFalse(e.runner.requestActive)
        val s = e.runner.state.value
        assertEquals(TriageStatus.Locked, s.status)
        assertEquals("照旧待判定,不算失败", 1, s.counts.pending)
        assertEquals(0, s.counts.failed)
        assertNull(s.marks["a"])
        // Send once more after unlocking
        e.model.gate = null
        e.model.reply = { ok("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // The lock cancelled the request, but the model ignores cancellation and only returns later; meanwhile, after unlocking, a new batch is already being sent: the late return must not touch anything
    @Test fun staleRequestReturningLateDoesNotTouchTheNewerBatch() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        val late = CompletableDeferred<Unit>()
        e.model.late = late
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.requestActive)
        e.model.gate = CompletableDeferred()
        e.locked.value = false
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertTrue(e.runner.requestActive)
        // The first request only comes back now (reported as "budget exhausted")
        late.complete(Unit)
        runCurrent()
        assertTrue("新的一批还在发", e.runner.requestActive)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        assertEquals(1, e.runner.state.value.counts.sending)
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertEquals(TriageDecision.Remember, (e.runner.state.value.marks.getValue("a") as TriageMark.Done).verdict.decision)
        assertEquals("晚回来的「预算用完」不算数", TriageStatus.Idle, e.runner.state.value.status)
    }

    @Test fun busyWaitsWithoutPollingThenSendsWhenTheTaskEnds() = runTest {
        val e = Env(this)
        e.busy.value = true
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 5)
        assertTrue(e.model.calls.isEmpty())
        assertEquals(TriageStatus.Busy, e.runner.state.value.status)
        assertFalse("任务在跑时不计时、不轮询", e.runner.timerActive)
        e.busy.value = false
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun failedBatchIsNotRetriedUntilAsked() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        wait(24 * 3_600_000L)
        assertEquals("不自动重试", 1, e.model.calls.size)
        e.runner.retryFailed()
        runCurrent()
        assertEquals("点一次只多试一次", 2, e.model.calls.size)
        wait(24 * 3_600_000L)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
    }

    @Test fun budgetExceededStopsQuietly() = runTest {
        val e = Env(this)
        e.post("a")
        e.model.reply = { throw ModelError.ProactiveBudgetExceeded(10, 10) }
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        val s = e.runner.state.value
        assertEquals("请求时被拒、账本看不出哪项:按 Token 算", TriageStatus.TokensUsedUp, s.status)
        assertEquals(1, s.counts.pending)
        assertEquals(0, s.counts.failed)
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals("当天不再发", 1, e.model.calls.size)
    }

    @Test fun ledgerBudgetUsedUpSendsNothing() = runTest {
        val e = Env(this)
        e.calls = ProactiveBudget.callLimit(e.limit)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(0, e.models)
        assertEquals(TriageStatus.CallsUsedUp, e.runner.state.value.status)
    }

    @Test fun consentMismatchStopsTriage() = runTest {
        val e = Env(this)
        e.effective = target.copy(model = "m2")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
        assertEquals(0, e.models)
        // Only sent after the consent is confirmed again
        e.consent = TriageConsent.of(e.effective!!)
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // The consent gate saw the consented model, but by the time the model was built the configuration had been switched to another one: nothing is sent, the items stay pending and wait for the user to confirm again
    @Test fun targetChangedBetweenTheGateAndTheBuildSendsNothing() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.built = target.copy(model = "m2")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.models)
        assertTrue("文字没发出去", e.model.calls.isEmpty())
        assertFalse(e.runner.requestActive)
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
        assertEquals(1, e.runner.state.value.counts.pending)
        assertTrue(e.runner.state.value.marks.isEmpty())
        // The configuration is back to the consented one: it sends as usual
        e.built = null
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
        assertTrue(e.runner.state.value.marks.getValue("a") is TriageMark.Done)
    }

    @Test fun consentChangedDuringRequestDropsTheResult() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1") }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.effective = target.copy(providerId = "p2")
        e.model.gate!!.complete(Unit)
        runCurrent()
        assertNull("换了服务商:回来的结果不记", e.runner.state.value.marks["a"])
        assertEquals(TriageStatus.NeedsConsent, e.runner.state.value.status)
    }

    @Test fun noModelSendsNothing() = runTest {
        val e = Env(this)
        e.effective = null
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.NoModel, e.runner.state.value.status)
        assertEquals(0, e.models)
    }

    @Test fun switchedOffSendsNothing() = runTest {
        val e = Env(this)
        e.on = false
        e.runner.poke()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(TriageStatus.Off, e.runner.state.value.status)
        assertEquals(0, e.models)
    }

    @Test fun appsNeverShareARequestAndOnlyOneIsInFlight() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1", "n2") }
        e.post("a1", "com.a", 1); e.post("b1", "com.b", 2); e.post("a2", "com.a", 3)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals("一次只发一批", 1, e.model.calls.size)
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(listOf("com.a", "com.b"), e.model.packages())
        assertEquals(3, e.runner.state.value.counts.judged)
    }

    // Building the model fails (e.g. the key cannot be decrypted): this batch counts as failed, the watch is not dragged down with it, and later batches are handled as usual
    @Test fun modelConstructionErrorFailsTheBatchAndKeepsRunning() = runTest {
        val e = Env(this)
        e.modelError = IllegalStateException("key")
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.modelError = null
        e.model.reply = { ok("n1") }
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun eightPendingAreSentWithoutWaiting() = runTest {
        val e = Env(this)
        repeat(8) { e.post("k$it", at = it.toLong()) }
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    @Test fun updateDuringTheCooldownSendsNothingUntilItEnds() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
        val judgedAt = currentTime
        // The chat keeps going: the same key is reposted every 20 seconds, and no request is sent during the cooldown (2 minutes)
        e.post("a", at = 50)
        runCurrent()
        assertEquals(1, e.runner.state.value.counts.cooling)
        assertEquals(TriageStatus.Waiting, e.runner.state.value.status)
        assertTrue("冷却到点要靠计时(解锁时)", e.runner.timerActive)
        e.post("a", at = 60)
        wait(60_000)
        e.post("a", at = 70)
        wait(judgedAt + 120_000 - currentTime - 1)
        assertEquals(1, e.model.calls.size)
        assertNull("正在显示的是旧结果", e.runner.state.value.marks["a"]?.takeIf { it.postedAtMs == 70L })
        // When it ends, batching resumes as usual and one request carries the accumulated items
        wait(1 + TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(0, e.runner.state.value.counts.cooling)
        assertEquals(1, e.runner.state.value.counts.judged)
        wait(3_600_000)
        assertEquals("没新内容就不再发", 2, e.model.calls.size)
    }

    @Test fun chattyAppCostsAFewRequestsNotOneEveryTwentySeconds() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        // One message every 5 seconds for ten minutes; after the first batch is sent the request is held for 3 seconds, and a message arrives exactly during that window
        var at = 1L
        e.post("a", at = at)
        wait(TriagePlanner.WINDOW_MS - 1)
        e.model.gate = CompletableDeferred()
        wait(1)
        assertEquals(1, e.model.calls.size)
        e.post("a", at = ++at)
        runCurrent()
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        assertEquals("请求期间的更新也进冷却", 1, e.runner.state.value.counts.cooling)
        val end = currentTime + 600_000
        while (currentTime < end) {
            e.post("a", at = ++at)
            wait(5_000)
        }
        // Triage at 0 s + 20 s; cooldown 2 min + 20 s batching, then 5 min + 20 s, the third one at ~8 min, followed by a 15-minute cooldown
        assertEquals(3, e.model.calls.size)
    }

    @Test fun lockedCooldownHasNoTimerAndIsSentAfterUnlock() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse("锁着不为冷却计时", e.runner.timerActive)
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
        e.locked.value = false
        runCurrent()
        assertEquals("解锁时早已到点:马上发", 2, e.model.calls.size)
    }

    @Test fun updateAfterAFailureWaitsForTheHold() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        val failedAt = currentTime
        // The model is broken and the chat posts every 20 seconds: no request is sent during the 2-minute hold
        var at = 1L
        while (currentTime < failedAt + 120_000 - 20_000) {
            e.post("a", at = ++at)
            wait(20_000)
        }
        wait(failedAt + 120_000 - currentTime - 1)
        assertEquals(1, e.model.calls.size)
        assertEquals("停着的算失败", 1, e.runner.state.value.counts.failed)
        assertEquals(0, e.runner.state.value.counts.pending)
        // After the hold, batch the items and send only once
        wait(1 + TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        // It fails again: with nothing new, nothing is sent automatically any more
        wait(3_600_000)
        assertEquals(2, e.model.calls.size)
    }

    @Test fun lockedFailureHoldHasNoTimer() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.timerActive)
        wait(3_600_000)
        assertEquals(1, e.model.calls.size)
        e.model.reply = { ok("n1") }
        e.locked.value = false
        runCurrent()
        assertEquals("解锁时早已停完:马上发", 2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun manualRetryIgnoresTheFailureHold() = runTest {
        val e = Env(this)
        e.model.reply = { "sorry, I cannot" }
        e.post("a", at = 1)
        wait(TriagePlanner.WINDOW_MS)
        e.post("a", at = 2)
        runCurrent()
        e.model.reply = { ok("n1") }
        e.runner.retryFailed()
        runCurrent()
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    @Test fun triageStopsAtItsShareOfTokensAndResumesWhenTheLimitIsRaised() = runTest {
        val e = Env(this)
        e.model.reply = { ok("n1") }
        e.tokens = ProactiveBudget.triageShare(e.limit) - 1
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals("八成之前照常发", 1, e.model.calls.size)
        e.tokens = ProactiveBudget.triageShare(e.limit)
        e.post("b", at = 5)
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals("到判定那份(整份还剩两成)就停", 1, e.model.calls.size)
        assertEquals(TriageStatus.TokensUsedUp, e.runner.state.value.status)
        assertFalse(e.runner.timerActive)
        // The user raises the limit: triage carries on
        e.limit = 2 * ProactiveBudget.DEFAULT_DAILY_LIMIT
        e.runner.budgetChanged()
        runCurrent()
        assertEquals(2, e.model.calls.size)
    }

    @Test fun triageStopsAtItsShareOfRequests() = runTest {
        val e = Env(this)
        e.calls = ProactiveBudget.triageShare(ProactiveBudget.callLimit(e.limit))
        assertEquals(640L, e.calls)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS * 3)
        assertEquals(0, e.models)
        assertEquals(TriageStatus.CallsUsedUp, e.runner.state.value.status)
        e.calls--
        e.runner.poke()
        runCurrent()
        assertEquals(1, e.model.calls.size)
    }

    // A configuration read fails during one check: the in-flight request is left alone (its cost has already been counted, and cancelling it would only mean sending it again later), only no new one is sent this time;
    // once the configuration can be read again, everything proceeds as usual
    @Test fun aFailedConfigurationReadLeavesTheRequestInFlightAlone() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.model.reply = { ok("n1") }
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.targetError = IllegalStateException("temporary configuration read failure")
        e.runner.poke()
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        assertEquals(0, e.model.cancelled)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Sending, e.runner.state.value.status)
        e.targetError = null
        e.model.gate!!.complete(Unit)
        e.model.gate = null
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        // The original one was not resent; the item that arrived during the failure is triaged as usual afterwards
        assertEquals(listOf("com.chat", "com.other"), e.model.packages())
        assertEquals(0, e.runner.state.value.counts.failed)
    }

    // A request must not be left running when the lock state cannot be read: not sending while locked is a hard rule, so an unknown lock state is treated as locked
    @Test fun aFailedLockReadStopsTheRequest() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertTrue(e.runner.requestActive)
        e.lockReadFails = true
        e.runner.poke()
        runCurrent()
        assertFalse(e.runner.requestActive)
        assertEquals(1, e.model.cancelled)
    }

    // When the model is unreachable, triage pauses as a whole: with only per-conversation cooldowns, every new conversation would trigger another connection attempt, and with many of them that means reconnecting non-stop.
    // That batch does not count as failed and stays pending; consecutive failures pause for longer; once reachable again, triage proceeds as usual
    @Test fun anUnreachableModelPausesTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(0, e.runner.state.value.counts.failed)
        assertEquals(1, e.runner.state.value.counts.pending)
        // Four more conversations arrive (including from other apps): no further connection attempts
        e.post("b", at = 2); e.post("c", at = 3); e.post("d", pkg = "com.other", at = 4); e.post("e", pkg = "com.mail", at = 5)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        // Locking while paused: the timer that would re-check when the pause ends is cancelled (no timers are left while the screen is off), and the pause continues after unlocking
        assertTrue(e.runner.timerActive)
        e.locked.value = true
        runCurrent()
        assertFalse(e.runner.timerActive)
        e.locked.value = false
        runCurrent()
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        assertEquals(1, e.model.calls.size)
        // The first backoff level elapses: one more attempt, still unreachable, so this time it pauses for the second level
        wait(TriagePlanner.BACKOFF_MS[0])
        assertEquals(2, e.model.calls.size)
        wait(TriagePlanner.BACKOFF_MS[0])
        assertEquals(2, e.model.calls.size)
        // The network is back: triage resumes when due, nothing was lost and nothing was recorded as failed
        e.model.error = null
        e.model.reply = { ok("n1", "n2", "n3") }
        wait(TriagePlanner.BACKOFF_MS[1])
        wait(3 * TriagePlanner.WINDOW_MS)
        assertEquals(0, e.runner.state.value.counts.pending)
        assertEquals(0, e.runner.state.value.counts.failed)
        assertEquals(5, e.runner.state.value.counts.judged)
    }

    // Other failures (the model returned an error, or the reply cannot be parsed) are still recorded only against that batch and do not pause everything
    @Test fun otherFailuresDoNotPauseTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Server(500)
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // When the user taps "try again" while paused for unreachability: it reconnects immediately
    @Test fun retryingByHandEndsTheUnreachablePause() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.runner.retryFailed()
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // Connected but no response before the timeout (the model is busy or very slow): this too pauses everything instead of asking item by item.
    // The timed-out batch is recorded as failed (the problem may lie with that batch itself, so it is not resent automatically), and "try again" stays available on the page
    @Test fun aTimeoutAlsoPausesTriageAsAWhole() = runTest {
        val e = Env(this)
        e.model.gate = CompletableDeferred()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        wait(com.wangzan101.mika.core.proactive.NoticeTriage.TIMEOUT_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.failed)
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        e.model.gate = null
        e.model.reply = { ok("n1") }
        wait(TriagePlanner.BACKOFF_MS[0])
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)
    }

    // Whether "the model changed" is judged by provider id and model name: two configuration reads returning different objects, or a changed display name, do not count as a change, and the pause holds
    @Test fun theUnreachablePauseHoldsWhileTheSameModelIsConfigured() = runTest {
        val e = Env(this)
        // The read at model-build time returned a different object, and the display name differs too
        e.built = TriageTarget(target.providerId, "Renamed", target.model)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        e.effective = TriageTarget(target.providerId, "Renamed again", target.model)
        e.post("b", pkg = "com.other", at = 2)
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(1, e.model.calls.size)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
    }

    // A pause caused by unreachability must not outlive its cause: changing the provider or model, or restarting the watch, retries immediately
    @Test fun theUnreachablePauseEndsWhenTheModelChangesOrTheWatchRestarts() = runTest {
        val e = Env(this)
        e.model.error = ModelError.Network()
        e.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(TriageStatus.Unreachable, e.runner.state.value.status)
        // The model is changed (the consent switches along with it)
        val other = TriageTarget("p2", "Other", "m2")
        e.effective = other
        e.consent = TriageConsent.of(other)
        e.model.error = null
        e.model.reply = { ok("n1") }
        e.runner.poke()
        runCurrent()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, e.model.calls.size)
        assertEquals(1, e.runner.state.value.counts.judged)

        // Restart the watch
        val again = Env(this)
        again.model.error = ModelError.Network()
        again.post("a")
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(TriageStatus.Unreachable, again.runner.state.value.status)
        again.model.error = null
        again.model.reply = { ok("n1") }
        again.restart()
        wait(TriagePlanner.WINDOW_MS)
        assertEquals(2, again.model.calls.size)
        assertEquals(1, again.runner.state.value.counts.judged)
    }
}
