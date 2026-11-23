package com.wangzan101.mika.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Cloud Code (Antigravity) request/response mapping, against a local fake server. No network. */
class AntigravityModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun model(reasoningEffort: String? = null) = AntigravityModel(
        server.url("/v1internal").toString().trimEnd('/'), BearerToken.fixed("tok"), "gemini-4-neon",
        Capabilities(true, false, 1_048_576, false), OkHttpClient(), reasoningEffort = reasoningEffort,
        project = { "proj-123" },
    )
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun sentBody() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private fun resp(parts: String, finish: String = "STOP", usage: String = "") =
        """{"response":{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"$finish"}]${usage}}}"""

    @Test fun sendsModelProjectAndRequestEnvelope() = runTest {
        server.enqueue(ok(resp("""[{"text":"hello"}]""", usage = ""","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}""")))
        val r = model().complete("SYS", user, emptyList())
        assertEquals(listOf(Part.Text("hello")), r.parts)
        assertEquals(Usage(10, 3), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1internal:generateContent", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = sentBody()
        assertEquals("gemini-4-neon", body.str("model"))
        assertEquals("proj-123", body.str("project"))
        val inner = body.obj("request")
        assertEquals("SYS", inner.obj("systemInstruction").arr("parts")[0].jsonObject.str("text"))
        assertEquals("user", inner.arr("contents")[0].jsonObject.str("role"))
    }

    @Test fun parsesFunctionCalls() = runTest {
        server.enqueue(ok(resp("""[{"text":"ok"},{"functionCall":{"name":"phone_tap","args":{"ref":"a1"}}}]""")))
        val r = model().complete("s", user, emptyList())
        assertEquals(
            listOf(Part.Text("ok"), Part.ToolCall("antigravity-0-phone_tap", "phone_tap", """{"ref":"a1"}""")),
            r.parts,
        )
        assertEquals(StopReason.ToolUse, r.stopReason)
    }

    @Test fun effortBecomesModelSuffixMaxMapsToXhigh() = runTest {
        server.enqueue(ok(resp("""[{"text":"ok"}]""")))
        AntigravityModel(
            server.url("/v1internal").toString().trimEnd('/'), BearerToken.fixed("tok"), "gemini-4-neon",
            Capabilities(true, false, 1_048_576, false), OkHttpClient(), reasoningEffort = "max",
            project = { "proj-123" },
        ).complete("s", user, emptyList())
        assertEquals("gemini-4-neon-xhigh", sentBody().str("model"))

        server.enqueue(ok(resp("""[{"text":"ok"}]""")))
        model(reasoningEffort = "high").complete("s", user, emptyList())
        assertEquals("gemini-4-neon-high", sentBody().str("model"))

        server.enqueue(ok(resp("""[{"text":"ok"}]""")))
        model().complete("s", user, emptyList())
        assertEquals("gemini-4-neon", sentBody().str("model"))
    }

    @Test fun finishReasonStopSuffixAndToolResultRoundTrip() = runTest {
        server.enqueue(ok(resp("""[{"text":"x"}]""", finish = "FINISH_REASON_STOP")))
        assertEquals(StopReason.EndTurn, model().complete("s", user, emptyList()).stopReason)
        server.enqueue(ok(resp("""[]""")))
        val history = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("antigravity-0-phone_tap", "phone_tap", """{"ref":"x"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("antigravity-0-phone_tap", "done", false)), Author.Mika),
            Message(Role.User, listOf(Part.Text("next")), Author.Mika),
        )
        model().complete("s", history, emptyList())
        val c = sentBody().obj("request").arr("contents")
        assertEquals(listOf("user", "model", "user"), c.map { it.jsonObject.str("role") })
        val fr = c[2].jsonObject.arr("parts")[0].jsonObject.obj("functionResponse")
        assertEquals("phone_tap", fr.str("name"))
        assertEquals("done", fr.obj("response").str("content"))
    }

    @Test fun mapsErrors() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad token"}}"""))
        try {
            model().complete("s", user, emptyList())
            fail("expected error")
        } catch (e: ModelError) {
            assertTrue(e is ModelError.Auth)
        }
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        try {
            model().complete("s", user, emptyList())
            fail("expected error")
        } catch (e: ModelError) {
            assertTrue(e is ModelError.RateLimited)
        }
    }

    private fun event(data: String) = "data: $data\n\n"
    private fun stream(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { event(it) })

    @Test fun streamsWrappedEvents() = runTest {
        server.enqueue(stream(
            resp("""[{"text":"Hel"}]"""),
            resp("""[{"text":"lo"}]""", usage = ""","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2}"""),
        ))
        val updates = mutableListOf<String>()
        val reply = model().completeStreaming("s", user, emptyList(), updates::add)
        assertEquals(listOf("Hel", "Hello"), updates)
        assertEquals(listOf(Part.Text("Hello")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(10, 2), reply.usage)
        assertEquals("/v1internal:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test fun discoveryReadsProjectAndModels() = runTest {
        val discovery = AntigravityDiscovery(
            OkHttpClient(), server.url("/v1internal").toString().trimEnd('/'), BearerToken.fixed("tok"),
        )
        server.enqueue(ok("""{"managedProjectId":"p1","currentTier":{"id":"free"}}"""))
        assertEquals(CodeAssistInfo("p1", "free"), discovery.loadCodeAssist())
        assertEquals("/v1internal:loadCodeAssist", server.takeRequest().path)
        server.enqueue(ok("""{"models":[{"name":"models/gemini-4-neon"},{"name":"gemini-3.8-flash"},{"displayName":"no name here"}]}"""))
        assertEquals(listOf("gemini-4-neon", "gemini-3.8-flash"), discovery.fetchAvailableModels("p1"))
        val fetchBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("p1", fetchBody.str("project"))
    }
}
