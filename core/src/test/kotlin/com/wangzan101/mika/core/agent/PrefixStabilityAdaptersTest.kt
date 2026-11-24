package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

/**
 * The same fake task as [PrefixStabilityTest], run through adapters for several other APIs (Responses, Anthropic, Gemini, and an
 * OpenAI-compatible API with explicit cache markers), checking the same properties on the wire bytes: zone 0 (system prompt, tools) changes only when a group is expanded, and when it does, the tools listed before it stay byte-for-byte identical;
 * the history only appends, except for the few compaction rewrites; replayed thinking (encrypted thinking, signed thinking, thoughtSignature) is carried in full at every step;
 * top-level fields other than zone 0 and messages (reasoning effort, output limit, etc.) are identical on every request.
 * The cache marker (cache_control) moves to the last entry each time and is not part of the cache key itself, so it is stripped before comparison; an Anthropic request carries no more than 4 markers.
 */
class PrefixStabilityAdaptersTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** One API: how to build the adapter, how to split a request into zone 0 and per-message entries, and how to respond to tool calls and the final answer. */
    private interface Api {
        fun model(url: String, vision: Boolean, contextTokens: Int): ChatModel
        /** The top-level fields occupied by zone 0 and per-message entries; all other top-level fields should be identical on every request. */
        val keys: Set<String>
        /** Prefix of the replay value for each step in the fake replies (step n is the prefix plus n); null for APIs without replay. */
        val replay: String?
        /** Requests that rewrite the prefix (compaction) under an 80K window; see [PrefixStabilityTest.compaction]. */
        val compactions: List<Int>
        fun zone0(body: JsonObject): String
        /** Per-message entries, with cache markers removed. */
        fun messages(body: JsonObject): List<String>
        /** Each tool's raw bytes and name. */
        fun tools(body: JsonObject): List<Pair<String, String>>
        fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse
        fun answer(promptTokens: Int): MockResponse
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private val responses = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = ResponsesModel(
            "$url/v1", BearerToken.fixed("k"), "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium",
        )
        override val keys = setOf("instructions", "tools", "input")
        override val replay = "ENC"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["instructions"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["input"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse {
            val call = buildJsonObject {
                put("type", "function_call"); put("id", "fc_$step"); put("call_id", "call_$step"); put("name", name); put("arguments", args.toString())
            }
            return sse(
                """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_$step","summary":[],"encrypted_content":"ENC$step"}}""",
                """{"type":"response.output_item.done","item":$call}""",
                completed(promptTokens),
            )
        }
        override fun answer(promptTokens: Int) = sse(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"看完了"}]}}""",
            completed(promptTokens),
        )
        private fun completed(promptTokens: Int) =
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":$promptTokens,"output_tokens":10}}}"""
    }

    private val anthropic = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            AnthropicModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("system", "tools", "messages")
        override val replay = "SIG"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["system"]!!).toString() + strip(body["tools"]!!)
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.map { strip(it).toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { strip(it).toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"content":[{"type":"thinking","thinking":"想第${step}步","signature":"SIG$step"},""" +
                """{"type":"tool_use","id":"tu_$step","name":"$name","input":$args}],""" +
                """"stop_reason":"tool_use","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) = json(
            """{"content":[{"type":"text","text":"看完了"}],"stop_reason":"end_turn","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
    }

    private val gemini = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            GeminiModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("systemInstruction", "tools", "contents")
        override val replay = "TS"
        // The request bytes are slightly smaller than the other three and only just below the trigger point: if the prompt or tool descriptions change length, the step crossing the trigger point may differ by one from the other three
        // (it used to be 13, 24); it now crosses on the same step as the other three
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["systemInstruction"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["contents"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.flatMap { t ->
            t.jsonObject["functionDeclarations"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) =
            reply("""{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"TS$step"}""", promptTokens)
        override fun answer(promptTokens: Int) = reply("""{"text":"看完了"}""", promptTokens)
        private fun reply(part: String, promptTokens: Int) = json(
            """{"candidates":[{"content":{"role":"model","parts":[$part]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":$promptTokens,"candidatesTokenCount":10}}""",
        )
    }

    /** The OpenAI-compatible variant that uses cache markers (as some providers' compatible APIs do): move the marker to the last entry each time. */
    private val explicitCompat = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = OpenAiCompatModel(
            "$url/v1", "k", "m", Capabilities(true, vision, contextTokens, false, promptCaching = PromptCaching.Explicit), OkHttpClient(),
        )
        override val keys = setOf("messages", "tools")
        override val replay = null
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["messages"]!!.jsonArray.first()).toString() + body["tools"]
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.drop(1).map { plain(strip(it).jsonObject).toString() }
        override fun tools(body: JsonObject) =
            body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject["function"]!!.jsonObject.str("name")!! }

        /**
         * When marked, string content becomes a text block, and when it is no longer the last entry it is a string again: treat these as the same entry for comparison. This is based on an example in a provider's
         * context caching documentation for a compatible API (checked 2026-11-01): the first time a system message is sent as an array with a marker,
         * the second time the same content is sent as a string and still hits the cache; the documentation only covers system messages, so handling other roles the same way is an inference.
         */
        private fun plain(m: JsonObject): JsonObject {
            val block = (m["content"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return m
            if (block.keys != setOf("type", "text") || block.str("type") != "text") return m
            return JsonObject(m + ("content" to block["text"]!!))
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_$step","type":"function",""" +
                """"function":{"name":"$name","arguments":${JsonPrimitive(args.toString())}}}]}}],""" +
                """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) =
            json("""{"choices":[{"finish_reason":"stop","message":{"content":"看完了"}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""")
    }

    /** Run the fake task from [PrefixStabilityTest] once and return the requests received by the server. */
    private fun run(api: Api, vision: Boolean = false, contextTokens: Int = 1_000_000): List<JsonObject> {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val raw = request.body.readUtf8()
                    val body = Json.parseToJsonElement(raw).jsonObject
                    bodies += body
                    val msgs = api.messages(body)
                    val step = STEP.findAll(msgs.last()).last().groupValues[1].toInt()
                    val tokens = TokenEstimate.of(raw)
                    if (step > STEPS) return api.answer(tokens)
                    val (name, args) = when {
                        step % 3 == 1 -> "phone_observe" to buildJsonObject {}
                        step % 3 == 2 -> lastRead(msgs.joinToString("")).let { (ref, snap) ->
                            "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) }
                        }
                        else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
                    }
                    return api.call(step, name, args, tokens)
                }
            }
            start()
        }
        val model = api.model(server.url("/").toString().trimEnd('/'), vision, contextTokens)
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!vision) scripted else object : ScreenPort by scripted {
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        runTest {
            AgentLoop(model, toolDispatcher(port, sandbox = sb, workspace = { "w" }, visionEnabled = vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
            ).run("把每一屏都看一遍").also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return bodies.toList()
    }

    /** The ref of the first element and the snapshot ID in the latest screen read; results are escaped to different depths in each API's wire format, so search without limiting the escaping. */
    private fun lastRead(text: String): Pair<String, String> {
        val at = ELEMENTS.findAll(text).last().range.first
        return REF.find(text, at)!!.groupValues[1] to SNAPSHOT.findAll(text.substring(0, at)).last().groupValues[1]
    }

    private val entries = setOf(
        "phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "phone_calendar_bulk_insert", "web_navigate", "web_read_page", "run_command",
    )
    private val markers = listOf(
        HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.olderImage(0).substringBefore(";"), HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER,
    )

    /**
     * The first difference between two messages: when the content is a list of blocks (or parts), narrow it down to the block. APIs that merge consecutive messages of the same role into one (Anthropic, Gemini)
     * fold the summary into the opening user message, which also carries the opening context; only narrowing to the block can tell whether the summary or the opening context changed.
     */
    private fun firstDiff(old: String, new: String): Pair<String, String> {
        val (a, b) = Json.parseToJsonElement(old).jsonObject to Json.parseToJsonElement(new).jsonObject
        val key = listOf("content", "parts").firstOrNull { a[it] is JsonArray && b[it] is JsonArray } ?: return old to new
        val (x, y) = a[key]!!.jsonArray to b[key]!!.jsonArray
        val same = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return x.getOrNull(same).toString() to y.getOrNull(same).toString()
    }

    private class Result(val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** The same checks as [PrefixStabilityTest]; [appendOnly]: the trigger point was not crossed, so nothing should be rewritten at all. */
    private fun check(api: Api, bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val zone0 = bodies.map(api::zone0)
        val msgs = bodies.map(api::messages)
        val opened = (1 until bodies.size).filter { zone0[it] != zone0[it - 1] }
        assertTrue("$label:区 0 变了 $opened 次", opened.size <= 2)
        for (i in opened) {
            val (a, b) = api.tools(bodies[i - 1]) to api.tools(bodies[i])
            val same = a.zip(b).takeWhile { (x, y) -> x.first == y.first }.size
            assertTrue("$label 第 ${i + 1} 次请求:展开时改了一组前面的工具 ${a.getOrNull(same)?.second}", same == a.size || a[same].second in entries)
            assertTrue("$label 第 ${i + 1} 次请求:展开只加不减", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until bodies.size) {
            val (a, b) = msgs[i - 1] to msgs[i]
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            if (same == a.size) continue
            changed += i
            // The first rewrite is either the pruned entry itself or the summary (when compacting again, the summary is replaced with a new one)
            val (old, new) = firstDiff(a[same], b[same])
            assertTrue("$label 第 ${i + 1} 次请求第 $same 条改写不是压缩:${new.take(300)}",
                markers.any { it in new && it !in old } || ContextCompressor.HEADER in new)
        }
        if (appendOnly) assertEquals("$label:没越过触发点却改写了前缀", emptyList<Int>(), changed)
        val rest = bodies.map { b -> JsonObject(b.filterKeys { it !in api.keys }) }.distinct()
        assertEquals("$label:区 0 和消息以外的字段变了", 1, rest.size)
        val full = bodies.indices.map { zone0[it] + msgs[it].joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in opened }.map { it.value }
        val share = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
        return Result(changed, share, opened)
    }

    /** The last request carries the replay for all 30 steps: if two consecutive requests both consistently omit it, the checks above cannot catch it. */
    private fun replaysAll(api: Api, bodies: List<JsonObject>, label: String) {
        val prefix = api.replay ?: return
        val last = bodies.last().toString()
        val missing = (1..STEPS).filter { "\"$prefix$it\"" !in last }
        assertEquals("$label:最后一次请求缺了这几步的回放", emptyList<Int>(), missing)
    }

    // Screen reads, large results (and images): nothing is rewritten across the 30 steps, and zone 0 is expanded only when the phone is first used and when the sandbox is first used
    private fun appendOnly(api: Api, label: String) {
        for (vision in listOf(false, true)) {
            val l = if (vision) "$label vision" else label
            val bodies = run(api, vision = vision)
            val r = check(api, bodies, l)
            assertEquals(l, listOf(1, 3), r.opened)
            assertTrue("$l ${r.share}", r.share > 0.95)
            replaysAll(api, bodies, l)
        }
    }

    // 80K window: rewrites happen only at the compaction steps, with indices matching the algorithm in [PrefixStabilityTest.compaction] (for Gemini see its compactions)
    private fun compaction(api: Api, label: String) {
        val bodies = run(api, contextTokens = 80_000)
        val r = check(api, bodies, "$label compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        assertEquals(api.compactions, r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    @Test fun responsesAppendOnly() = appendOnly(responses, "responses")
    @Test fun responsesCompaction() = compaction(responses, "responses")
    @Test fun anthropicAppendOnly() = appendOnly(anthropic, "anthropic")
    @Test fun anthropicCompaction() = compaction(anthropic, "anthropic")
    @Test fun geminiAppendOnly() = appendOnly(gemini, "gemini")
    @Test fun geminiCompaction() = compaction(gemini, "gemini")
    @Test fun explicitCompatAppendOnly() = appendOnly(explicitCompat, "compat explicit")
    @Test fun explicitCompatCompaction() = compaction(explicitCompat, "compat explicit")

    // One each for the system prompt, the last tool, and the last user message; an Anthropic request allows at most 4, and more than that causes the whole request to be rejected (400)
    @Test fun anthropicCacheMarksStayWithinLimit() {
        for (body in run(anthropic, contextTokens = 80_000)) {
            assertEquals("缓存标记个数", 3, CACHE_MARK.findAll(body.toString()).count())
        }
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
        val ELEMENTS = Regex("""elements\\*":\[\{""")
        val REF = Regex("""ref\\*":\\*"([^"\\]+)""")
        val SNAPSHOT = Regex("""snapshotId\\*":\\*"([^"\\]+)""")
        val CACHE_MARK = Regex("\"cache_control\"")
    }
}
