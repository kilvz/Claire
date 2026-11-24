package com.wangzan101.mika.core.model.usage

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The proactive call limit counts actual HTTP requests sent. The adapter re-sends on its own within a single call (here using a service that rejects the include parameter:
 * the first request gets a 400, then another is sent without the parameter), and those are only recorded in the ledger when the call ends — before re-sending, they must be added and the limit re-checked.
 */
class ProactiveRetryBudgetTest {
    private val limit = ProactiveBudget.DEFAULT_DAILY_LIMIT
    private val cap = ProactiveBudget.callLimit(limit)
    private val ok = "data: " + """{"type":"response.output_text.delta","delta":"ok"}""" + "\n\n" +
        "data: " + """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":1,"output_tokens":1}}}""" + "\n\n"
    private val rejectsInclude = """{"error":{"param":"include","message":"reasoning.encrypted_content unsupported"}}"""

    private class Run(val server: MockWebServer, var calls: Long, limit: Long) {
        var checks = 0
        var retryChecks = 0
        private val sink = object : UsageSink {
            override fun record(source: UsageSource, usage: Usage?) = Unit
            override fun record(source: UsageSource, usage: Usage?, httpAttempts: Int) { calls += httpAttempts }
        }
        val model = ResponsesModel(
            server.url("/v1").toString().trimEnd('/'), BearerToken.fixed("local-test-key"), "test-model",
            Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false), OkHttpClient(),
        ).metered(
            UsageSource("p", "local test", "test-model", UsagePurpose.Proactive), sink,
            beforeCall = { checks++; ProactiveBudget.check(0, limit, calls) },
            beforeRetry = { _, started -> retryChecks++; ProactiveBudget.check(0, limit, calls + started) },
        )
        suspend fun ask() = model.complete("s", listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika)), emptyList())
    }

    // Only the last remaining request of budget: the first request went out and the service rejects the parameter so another would be sent — it isn't, and the count stops at the limit
    @Test fun aRetryInsideOneCallDoesNotGoPastTheLastRemainingRequest() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(rejectsInclude))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ok))
            val run = Run(server, cap - 1, limit)
            try {
                run.ask()
                fail("should have stopped at the cap")
            } catch (e: ModelError.ProactiveBudgetExceeded) {
                assertTrue(e.byCalls)
            }
            assertEquals(1, server.requestCount)
            assertEquals("发出去的那一个照实记账", cap, run.calls)
            assertEquals(1, run.checks)
            assertEquals(1, run.retryChecks)
        } finally {
            server.shutdown()
        }
    }

    // With enough budget the retry goes out as usual and both requests are recorded
    @Test fun aRetryStillGoesOutWhileThereIsRoom() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody(rejectsInclude))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(ok))
            val run = Run(server, cap - 2, limit)
            run.ask()
            assertEquals(2, server.requestCount)
            assertEquals(cap, run.calls)
            assertEquals(1, run.retryChecks)
        } finally {
            server.shutdown()
        }
    }
}
