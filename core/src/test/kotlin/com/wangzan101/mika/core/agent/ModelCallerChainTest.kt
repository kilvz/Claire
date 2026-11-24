package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.net.RateLimitGovernor
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 3 chain behavior: switch to a fallback automatically when the main model fails, stay on the fallback once switched, and fail as-is when no switch should happen. */
class ModelCallerChainTest {
    private class Scripted(override val name: String, var fail: ModelError?) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
        var calls = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            fail?.let { throw it }
            return answerReply("ok-$name")
        }
    }

    private val history = listOf(Message(Role.User, listOf(Part.Text("你好")), Author.User))

    private fun caller(main: ChatModel, fallbacks: List<ChatModel>, tags: List<String?> = emptyList()) =
        ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 },
            fallbacks = fallbacks, chainProviderIds = tags).also { it.reset("") }

    @Test fun quotaFallsToFallbackAndSticks() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
        // After switching, the next step asks the fallback directly instead of hitting the broken main model once more
        caller.ask(history, emptyList(), step = 2)
        assertEquals(1, main.calls)
        assertEquals(2, backup.calls)
    }

    @Test fun taskBudgetDoesNotSwitch() = runTest {
        val main = Scripted("main", ModelError.TaskBudgetExceeded(100, 100))
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.TaskBudgetExceeded)
        assertEquals(0, backup.calls)
    }

    @Test fun noFallbackBehavesLikeBefore() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val caller = caller(main, emptyList())
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.QuotaExceeded)
        assertEquals(1, main.calls)
    }

    @Test fun rateLimitWithNoRetriesFallsToFallbackImmediately() = runTest {
        RateLimitGovernor.reset()
        val main = Scripted("main-rate-limit-none", ModelError.RateLimited(300_000L))
        val backup = Scripted("backup-rate-limit-none", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
    }

    @Test fun rateLimitWithFallbackDoesNotWaitForTheFullRetryAfter() = runTest {
        RateLimitGovernor.reset()
        val main = Scripted("main-rate-limit-fallback", ModelError.RateLimited(30_000L))
        val backup = Scripted("backup-rate-limit-fallback", null)
        val retry = RetryPolicy(maxRetries = 3, jitter = { 0.0 })
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, retry, {}, { 0 }, fallbacks = listOf(backup))
            .also { it.reset("") }
        caller.ask(history, emptyList(), step = 1)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
    }

    @Test fun chainExhaustionSurfacesPrimaryError() = runTest {
        // The whole chain fails: the primary message is the main model's original error, and fallback errors go into the trail (MK-1 batch 2; batch 1 delivered the primary error directly).
        val mainErr = ModelError.Server(500)
        val main = Scripted("main", mainErr)
        val backup = Scripted("backup", ModelError.Server(503))
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        val chain = thrown as ModelError.ChainFailed
        assertSame(mainErr, chain.primary)
        assertEquals(listOf("main", "backup"), chain.entries.map { it.first })
        assertEquals(1, backup.calls)
    }

    @Test fun mixedFailureSurfacesPrimaryWithTrail() = runTest {
        // The main model fails with a switchable error and the fallback with a non-switchable one: the main model's error still leads, and the fallback's error goes into the trail (MK-1 batch 2).
        val mainErr = ModelError.RateLimited(null)
        val main = Scripted("main", mainErr)
        val backup = Scripted("backup", ModelError.BadResponse("HTTP 400 wrapped"))
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        val chain = thrown as ModelError.ChainFailed
        assertSame(mainErr, chain.primary)
        assertEquals(2, chain.entries.size)
        assertTrue(chain.entries[1].second is ModelError.BadResponse)
    }

    @Test fun laterStepFailureStillSurfacesPrimary() = runTest {
        // Step 1 switches to the fallback and succeeds; in step 2 the fallback itself fails: the main model's error is still present via the task-level record, so it still leads (MK-1 batch 2).
        val mainErr = ModelError.QuotaExceeded()
        val main = Scripted("main", mainErr)
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        backup.fail = ModelError.BadResponse("HTTP 400 wrapped")
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 2) } catch (e: ModelError) { thrown = e }
        val chain = thrown as ModelError.ChainFailed
        assertSame(mainErr, chain.primary)
        assertEquals(listOf("main", "backup"), chain.entries.map { it.first })
    }

    @Test fun singleModelFailureIsNotWrapped() = runTest {
        // No fallback and only one model tried: deliver as-is, without wrapping in ChainFailed.
        val err = ModelError.BadResponse("HTTP 400 wrapped")
        val main = Scripted("main", err)
        val caller = caller(main, emptyList())
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(err, thrown)
    }

    @Test fun primaryBudgetErrorSurfacesOverFallbackFailure() = runTest {
        val budget = ModelError.DailyBudgetExceeded("Gateway", 100, 100, providerId = "primary-id")
        val main = Scripted("main", budget)
        val backup = Scripted("backup", ModelError.BadResponse("bad"))
        val caller = caller(main, listOf(backup), tags = listOf("primary-id", "backup-id"))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(budget, thrown)
        assertEquals(1, backup.calls)
    }

    @Test fun currentBudgetErrorWinsOverEarlierDailyBudget() = runTest {
        val main = Scripted("main", ModelError.DailyBudgetExceeded("Gateway", 100, 100, providerId = "primary-id"))
        val taskBudget = ModelError.TaskBudgetExceeded(100, 100)
        val backup = Scripted("backup", taskBudget)
        val caller = caller(main, listOf(backup), tags = listOf("primary-id", "backup-id"))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(taskBudget, thrown)
    }

    @Test fun fallbackBudgetErrorPropagatesAsItself() = runTest {
        // The fallback reports a task-level budget: it concerns the whole task, so it is delivered as-is and is not overridden by the main model's error.
        val budgetErr = ModelError.TaskBudgetExceeded(100, 100)
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", budgetErr)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(budgetErr, thrown)
    }

    @Test fun server4xxSwitchesWithoutSameModelRetry() = runTest {
        // A 4xx means this request itself was rejected: retrying the same provider is useless, so switch to the next provider directly (MK-1 A-D1, point 1).
        val main = Scripted("main", ModelError.Server(400))
        val backup = Scripted("backup", null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF,
            RetryPolicy(maxRetries = 3, jitter = { 0.0 }), {}, { 0 }, fallbacks = listOf(backup)).also { it.reset("") }
        caller.ask(history, emptyList(), step = 1)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
        assertSame(backup, caller.lastMainUsed)
    }

    @Test fun server5xxRetriesSameModelFirst() = runTest {
        // A 5xx is a transient failure: exhaust retries on the same provider first, then switch to the fallback.
        val main = Scripted("main", ModelError.Server(500))
        val backup = Scripted("backup", null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF,
            RetryPolicy(maxRetries = 3, jitter = { 0.0 }), {}, { 0 }, fallbacks = listOf(backup)).also { it.reset("") }
        caller.ask(history, emptyList(), step = 1)
        assertEquals(4, main.calls)
        assertEquals(1, backup.calls)
        assertSame(backup, caller.lastMainUsed)
    }

    @Test fun dailyBudgetSkipsSameProvider() = runTest {
        val main = Scripted("main", ModelError.DailyBudgetExceeded("Gateway", 100, 100, providerId = "primary-id"))
        val same = Scripted("same", null)
        val other = Scripted("other", null)
        val caller = caller(main, listOf(same, other), tags = listOf("primary-id", "primary-id", "backup-id"))
        caller.ask(history, emptyList(), step = 1)
        assertSame(other, caller.lastMainUsed)
        assertEquals(0, same.calls)
        assertEquals(1, other.calls)
    }

    @Test fun nonSwitchableErrorPropagates() = runTest {
        val main = Scripted("main", ModelError.ContentRefused())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.ContentRefused)
        assertEquals(0, backup.calls)
    }

    @Test fun resetReturnsToMain() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        main.fail = null
        caller.reset("")
        caller.ask(history, emptyList(), step = 2)
        assertSame(main, caller.lastMainUsed)
    }

    /** Fake model that first returns a reply carrying private fields and then fails with [thenFail]; it also records the last history it received. */
    private class Recorder(override val name: String, val first: ModelReply?, val thenFail: ModelError?) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
        var calls = 0
        var seen: List<Message> = emptyList()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            seen = messages
            if (calls == 1 && first != null) return first
            thenFail?.let { throw it }
            return answerReply("ok-$name")
        }
    }

    private val richReply = ModelReply(
        listOf(
            Part.Thinking("inner", opaque = "think-opaque"),
            Part.ToolCall("c1", "tap", "{}", opaque = "call-opaque"),
            Part.Text("answer with tools"),
        ), StopReason.EndTurn, null)

    @Test fun switchStripsPreviousMainPrivateParts() = runTest {
        val main = Recorder("main", richReply, ModelError.QuotaExceeded())
        val backup = Recorder("backup", null, null)
        val caller = caller(main, listOf(backup))
        val first = caller.ask(history, emptyList(), step = 1)
        val h2 = history + Message(Role.Assistant, first.parts, Author.Mika)
            .also { caller.noteReply(it, caller.lastMainUsed, first.usage) }
        caller.ask(h2, emptyList(), step = 2)
        assertSame(backup, caller.lastMainUsed)
        val sent = backup.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }

    @Test fun ownRepliesKeepPrivateParts() = runTest {
        val main = Recorder("main", richReply, null)
        val caller = caller(main, emptyList())
        val first = caller.ask(history, emptyList(), step = 1)
        val h2 = history + Message(Role.Assistant, first.parts, Author.Mika)
            .also { caller.noteReply(it, caller.lastMainUsed, first.usage) }
        caller.ask(h2, emptyList(), step = 2)
        val sent = main.seen.single { it.role == Role.Assistant }
        assertEquals("think-opaque", sent.parts.filterIsInstance<Part.Thinking>().single().opaque)
        assertEquals("call-opaque", sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
    }

    @Test fun firstFailoverStripsUnrecordedReplies() = runTest {
        // The task fails over on its very first call: history contains an assistant reply with no recorded origin (treated as coming from the main model),
        // so its private fields must be stripped when sending to the fallback; do not send it through verbatim just because replyOrigin is empty (review T-1112-b649d2 round 3 D1).
        val main = Recorder("main", null, ModelError.QuotaExceeded())
        val backup = Recorder("backup", null, null)
        val caller = caller(main, listOf(backup))
        val h = history + Message(Role.Assistant, richReply.parts, Author.Mika)
        caller.ask(h, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        val sent = backup.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }

    @Test fun lightViewStripsUnrecordedReplies() = runTest {
        // When an assistant reply with no recorded origin (the task just started and replyOrigin is still empty) is sent to the light model,
        // its private fields must still be stripped; do not send it through verbatim just because the map is empty (review T-1112-b649d2 round 2 D1).
        val main = Recorder("main", null, null)
        val light = Recorder("light", null, null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), ModelRouter(main, light),
            SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 }).also { it.reset("") }
        val h = history + Message(Role.Assistant, richReply.parts, Author.Mika)
        caller.ask(h, emptyList(), step = 1, using = light)
        val sent = light.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }
}
