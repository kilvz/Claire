package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * A 30-step fake task (screen reads, taps, and commands alternating) sent through the real OpenAI-compatible adapter to a fake server, comparing adjacent requests by their wire bytes:
 * the system prompt and tools change only on the request after a group is expanded (step 1's screen read expands the phone, step 3's command expands the sandbox, once per group),
 * and when they change, the part of the tool list before that group stays byte-for-byte identical while every other request is byte-for-byte identical;
 * the history only appends: nothing is rewritten while the estimate has not crossed the compaction trigger point, only the compacting request rewrites,
 * and the first rewritten message is exactly the one that was shortened (or folded into the summary).
 */
class PrefixStabilityTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** Fake server: determines the step from the step count in the runtime state at the end of the request (assistant messages become fewer after folding) and replies according to the script; steps in [fail] return 429 the first time. */
    private inner class Wire(val fail: Set<Int> = emptySet()) {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        private val tried = mutableSetOf<Int>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    bodies += body
                    val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
                    val step = STEP.findAll(msgs.last().toString()).last().groupValues[1].toInt()
                    if (step in fail && tried.add(step)) return MockResponse().setResponseCode(429).setHeader("retry-after", "0")
                    return MockResponse().setBody(reply(step, msgs, TokenEstimate.of(body.toString())).toString())
                }
            }
            start()
        }

        fun model(vision: Boolean, contextTokens: Int) =
            OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", Capabilities(true, vision, contextTokens, false), OkHttpClient())
    }

    /** [promptTokens]: report input usage based on the size of this request, like a real provider (compaction timing uses actual usage; see [UsageMeter]). */
    private fun reply(step: Int, msgs: List<JsonObject>, promptTokens: Int): JsonObject {
        // The final step carries no call and returns only text: that is the final answer
        if (step > STEPS) return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") { put("content", "看完了") }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 10) }
        }
        val (name, args) = when {
            step % 3 == 1 -> "phone_observe" to buildJsonObject {}
            step % 3 == 2 -> lastRead(msgs).let { (ref, snap) -> "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) } }
            else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
        }
        return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    putJsonObject("message") {
                        put("content", JsonPrimitive(null as String?))
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", "call_$step"); put("type", "function")
                                putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
                            })
                        }
                    }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", promptTokens); put("completion_tokens", 10) }
        }
    }

    /** The ref of the first element and the snapshot ID in the latest screen read (with diff screens the IDs get renumbered, so they cannot be hard-coded). */
    private fun lastRead(msgs: List<JsonObject>): Pair<String, String> {
        val c = msgs.filter { it.str("role") == "tool" }.map { it.str("content").orEmpty() }.last { "\"elements\":[{" in it }
        return c.substringAfter("\"ref\":\"").substringBefore('"') to c.substringAfter("\"snapshotId\":\"").substringBefore('"')
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private class Setup(val vision: Boolean = false, val inline: Boolean = false, val fail: Set<Int> = emptySet(),
                        val light: Boolean = false, val contextTokens: Int = 1_000_000,
                        /** Groups used in the previous turn: expanded from the start. */
                        val prior: List<String> = emptyList())

    /** Run once and return the requests received by each server (main model first). */
    private fun run(s: Setup): List<List<JsonObject>> {
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!s.vision) scripted else object : ScreenPort by scripted { override suspend fun screenshotPng() = byteArrayOf(1, 2, 3) }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        val main = Wire(s.fail)
        val light = if (s.light) Wire() else null
        val mainModel = main.model(s.vision, s.contextTokens)
        val lightModel = light?.model(false, s.contextTokens)
        runTest {
            AgentLoop(mainModel, toolDispatcher(port, sandbox = sb, workspace = { "w" }, inlineScreen = s.inline, visionEnabled = s.vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
                flags = SpeedFlags(inlineScreen = s.inline, lightSteps = s.light), router = lightModel?.let { ModelRouter(mainModel, it) },
            ).run("把每一屏都看一遍", if (s.prior.isEmpty()) emptyList() else listOf(Turn("上一轮", "好", "succeeded", groups = s.prior))).also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return listOfNotNull(main, light).map { it.bodies.toList() }
    }

    /** One request on the wire: zone 0 (system prompt, tools) and the per-message entries, each as the raw bytes sent; [tools] holds the raw bytes of each tool. */
    private class Req(val zone0: String, val msgs: List<String>, val raw: List<JsonObject>, val tools: List<String>)

    private fun wire(body: JsonObject): Req {
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        return Req(msgs.first().toString() + body["tools"].toString(), msgs.drop(1).map { it.toString() }, msgs.drop(1),
            body["tools"]!!.jsonArray.map { it.toString() })
    }

    /** The name of a tool definition on the wire. */
    private fun nameOf(tool: String) = Json.parseToJsonElement(tool).jsonObject["function"]!!.jsonObject.str("name")

    /** Entry tools that are provided even while collapsed: when expanding, the first changed tool can only be one of them (or a new tool appended at the end). */
    private val entries = setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command")

    private val markers = listOf(HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.olderImage(0).substringBefore(";"), HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER)

    /**
     * The common prefix of adjacent requests in the wire bytes: Σ common-prefix length ÷ Σ request length (in characters). The requests that expand a group ([skip], where the prefix breaks once by design) are excluded,
     * and [check] separately verifies that they changed only the part from that group onward.
     */
    private fun prefixShare(reqs: List<Req>, skip: List<Int> = emptyList()): Double {
        val full = reqs.map { it.zone0 + it.msgs.joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in skip }.map { it.value }
        val common = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }
        return common.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
    }

    private class Result(val pairs: Int, val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** [appendOnly]: the trigger point was not crossed, so nothing should be rewritten; for compaction the caller supplies the exact expectation. */
    private fun check(bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val reqs = bodies.map(::wire)
        // Zone 0 changes only when a group is expanded, at most twice (phone, sandbox); when it changes, the preceding tools stay byte-for-byte identical and the first changed one is that group's entry tool
        val opened = (1 until reqs.size).filter { reqs[it].zone0 != reqs[it - 1].zone0 }
        assertTrue("$label:区 0 变了 $opened 次", opened.size <= 2)
        for (i in opened) {
            val (a, b) = reqs[i - 1].tools to reqs[i].tools
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            assertTrue("$label 第 ${i + 1} 次请求:展开时改了一组前面的工具 ${a.getOrNull(same)?.let(::nameOf)}", same == a.size || nameOf(a[same]) in entries)
            assertTrue("$label 第 ${i + 1} 次请求:展开只加不减", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until reqs.size) {
            val (a, b) = reqs[i - 1] to reqs[i]
            val same = a.msgs.zip(b.msgs).takeWhile { (x, y) -> x == y }.size
            if (same == a.msgs.size) continue
            changed += i
            // The first rewrite is exactly the entry cut by the pruning line (or folded into the summary)
            val first = b.msgs[same]
            assertTrue("$label 第 ${i + 1} 次请求第 $same 条改写不是压缩:$first", markers.any { it in first && it !in a.msgs[same] } || ContextCompressor.HEADER in first)
        }
        if (appendOnly) assertEquals("$label:没越过触发点却改写了前缀", emptyList<Int>(), changed)
        return Result(reqs.size - 1, changed, prefixShare(reqs, opened), opened)
    }

    // Screen reads, large results, and images all only append: nothing is rewritten across the 30 steps
    @Test fun textOnly() {
        val r = check(run(Setup()).single(), "text")
        // The request after step 1's screen read expands the phone; the one after step 3's command expands the sandbox
        assertEquals(listOf(1, 3), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    // The phone and sandbox were used in the previous turn: they are expanded from the start, and zone 0 never changes across the 30 steps
    @Test fun preloadedGroupsNeverChangeZoneZero() {
        val r = check(run(Setup(prior = listOf("phone", "sandbox"))).single(), "preloaded")
        assertEquals(emptyList<Int>(), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withVisionImages() {
        val r = check(run(Setup(vision = true)).single(), "vision")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withInlineScreens() {
        val r = check(run(Setup(inline = true)).single(), "inline")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun retriedRequestsAreIdentical() {
        val bodies = run(Setup(fail = setOf(5, 14))).single()
        // The retried request is byte-for-byte identical to the rejected one
        assertEquals(33, bodies.size)
        check(bodies, "retry")
    }

    @Test fun lightModelRouting() {
        val (main, light) = run(Setup(light = true))
        assertTrue("轻量模型应当接过几步", light.isNotEmpty())
        check(main, "main")
        check(light, "light")
    }

    @Test fun compaction() {
        val bodies = run(Setup(contextTokens = 80_000)).single()
        val r = check(bodies, "compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        // Each crossing of the trigger point compacts once (one rewrite); afterwards it only appends, and it compacts again only after growing by another margin: two rewrites in 30 steps
        // (80K window: the prompt and expanded tools are about 20K, the trigger point is 47K, and it compacts below 30K; added tool descriptions make later trigger crossings happen sooner)
        assertEquals(listOf(12, 21, 30), r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
    }
}
