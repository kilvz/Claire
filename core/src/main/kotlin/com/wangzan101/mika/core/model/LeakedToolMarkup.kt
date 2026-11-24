package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Some models spit tool-call markup that should have gone through the `tool_calls` channel straight into the text:
 * DeepSeek V3.2 / V4's DSML (`<｜DSML｜invoke name="…">…`), and Qwen-style `<tool_call>{…}</tool_call>`.
 * Handed to the UI as-is it is a pile of `<｜｜DSML｜｜ calls>` garbage, and it gets recorded in the history as body text.
 *
 * Two things happen here, in the order restore first, strip second:
 * 1. Anything that can be restored into a tool call is restored (arguments become strings or JSON values according to the `string` flag) and handed to the tool engine to run as usual;
 * 2. DSML that cannot be restored only has its markup stripped, keeping the text outside the markup; `<tool_call>` blocks that cannot be restored are left as they are,
 *    to be bounced once by AgentLoop's leaked-call check (LeakedToolCall) instead of being handed out as an answer.
 *
 * Nothing is touched unless the markup really appears in the text; a normal answer is not changed by a single character (see the changed check in [repair]).
 * A `<tool_call>` block written in the answer as a pair containing valid call JSON is treated as a call; this is a deliberate trade-off:
 * showing garbage to the user costs more; when a tool that does not exist is called, the tool engine reports the error as usual.
 */
object LeakedToolMarkup {
    data class Invocation(val name: String, val argsJson: String)

    /** [text] is the remaining body text after stripping the markup; [calls] are the tool calls restored from the markup (may be empty). */
    data class Scan(val text: String, val calls: List<Invocation>)

    /** The vertical bar has two forms: full-width `｜` and half-width `|`, and leaking output sometimes writes one or two in a row. */
    private const val B = "[｜|]"

    private fun bars(prefix: String) = prefix + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}"

    private val INVOKE = Regex(
        bars("<") + "\\s*invoke\\s+name\\s*=\\s*\"([^\"]*)\"\\s*>(.*?)</" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*invoke\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PARAM = Regex(
        bars("<") + "\\s*parameter\\s+name\\s*=\\s*\"([^\"]*)\"(?:\\s+string\\s*=\\s*\"(true|false)\")?\\s*>(.*?)</" +
            B + "{1,2}\\s*DSML\\s*" + B + "{1,2}\\s*parameter\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Any remaining paired/orphan markup (outer `calls`, `function_calls` and the like) is removed. */
    private val TAG = Regex("</?" + B + "{1,2}\\s*DSML\\s*" + B + "{1,2}[^>]*>")

    /** Qwen-style: `<tool_call>{"name":…,"arguments":…}</tool_call>`. */
    private val TOOL_CALL_BLOCK = Regex(
        "<tool_call>\\s*(.*?)\\s*</tool_call>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val PIPE_TOOL_CALL_BLOCK = Regex(
        "<" + B + "{1,2}tool_call" + B + "{1,2}\\s*>(.*?)</" + B + "{1,2}tool_call" + B + "{1,2}\\s*>",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Common in streaming: the markup has just started (`<｜`, `<｜｜DSML`, `<｜｜DSML｜｜ invoke name="pho`).
     * Only a partial marker starting with a bar and followed by the first few letters of DSML is recognized, normal text is not matched (`a<b this part talks about DSML` does not count).
     */
    private val PARTIAL_TAIL = Regex("^/?" + B + "{1,2}\\s*(?:D(?:S(?:M(?:L.*)?)?)?)?$", RegexOption.DOT_MATCHES_ALL)

    /** For streaming preview: strip the part of the markup received so far on each frame, so the user does not watch garbage flash by. */
    fun stripMarkup(text: String): String = scan(text).text

    /** Scan a piece of text: strip markup, restore calls. When there is no markup, returns it unchanged with no calls. */
    fun scan(text: String): Scan {
        if (text.isEmpty()) return Scan(text, emptyList())
        var rest = text
        val calls = mutableListOf<Invocation>()

        // 1. DSML: first extract whole invoke blocks, then extract parameters by parameter
        if (rest.contains("DSML", ignoreCase = true)) {
            for (m in INVOKE.findAll(rest)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                calls += Invocation(name, paramsJson(m.groupValues[2]))
            }
            if (calls.isNotEmpty()) rest = rest.replace(INVOKE, " ")
            rest = TAG.replace(rest, " ")
        }

        // 2. <tool_call>{…}</tool_call> (contains JSON, may be one object or a list)
        rest = extractJsonBlocks(rest, TOOL_CALL_BLOCK, calls)
        rest = extractJsonBlocks(rest, PIPE_TOOL_CALL_BLOCK, calls)

        // Streaming preview: also hide a half marker at the end that is not complete yet (<｜｜DSML｜, <｜…)
        val lt = rest.lastIndexOf('<')
        if (lt >= 0) {
            val tail = rest.substring(lt + 1)
            if (!tail.contains('>') && PARTIAL_TAIL.matches(tail)) {
                rest = rest.substring(0, lt)
            }
        }
        if (calls.isEmpty() && rest == text) return Scan(text, emptyList())
        // Tidy up the blank lines left after stripping the markup; line breaks inside the body are left alone
        rest = rest.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
        return Scan(rest, calls)
    }

    /** Clean up the body text in a reply; when calls are restored, add [Part.ToolCall]s and mark the end as ToolUse. */
    fun repair(reply: ModelReply): ModelReply {
        if (reply.parts.none { it is Part.Text || it is Part.Thinking }) return reply
        val out = mutableListOf<Part>()
        val calls = mutableListOf<Part.ToolCall>()
        var changed = false
        for (p in reply.parts) {
            if (p is Part.Thinking) {
                // Markup leaking into the thinking text is only stripped, never treated as a call: calls are only recognized from the body text
                val cleaned = stripMarkup(p.text)
                if (cleaned != p.text) changed = true
                if (cleaned.isNotEmpty()) out += Part.Thinking(cleaned)
                continue
            }
            if (p !is Part.Text) {
                out += p
                continue
            }
            val scan = scan(p.text)
            if (scan.text != p.text || scan.calls.isNotEmpty()) changed = true
            if (scan.text.isNotEmpty()) out += Part.Text(scan.text)
            scan.calls.forEach { c -> calls += Part.ToolCall(callId(c, calls.size), c.name, c.argsJson) }
        }
        if (!changed) return reply
        out += calls
        return ModelReply(out, if (calls.isEmpty()) reply.stopReason else StopReason.ToolUse, reply.usage)
    }

    /** Parameter blocks → tool argument JSON. `string="false"` ones are taken as JSON values, the rest as strings. */
    private fun paramsJson(block: String): String {
        if (PARAM.find(block) == null) return "{}"
        return buildJsonObject {
            for (m in PARAM.findAll(block)) {
                val name = m.groupValues[1].trim()
                if (name.isEmpty()) continue
                val raw = m.groupValues[3]
                val isString = m.groupValues[2] != "false"
                put(name, if (isString) JsonPrimitive(raw) else jsonValue(raw))
            }
        }.toString()
    }

    private fun jsonValue(raw: String) = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        JsonPrimitive(raw)
    }

    /** A stable call ID built from name + arguments (further distinguished by index within the same message), no randomness, for easy unit testing. */
    private fun callId(c: Invocation, index: Int): String =
        "leaked_" + Integer.toHexString((c.name + "\u0000" + c.argsJson).hashCode()) + "_" + index

    private fun extractJsonBlocks(text: String, block: Regex, calls: MutableList<Invocation>): String {
        // Only strip blocks that can be restored into calls; blocks that cannot are left as they are, for AgentLoop's leaked-call check (LeakedToolCall) to bounce back,
        // so that stripping them does not leave only a preceding "let me check" that is handed out as the final answer
        return block.replace(text) { m ->
            val found = invocationsOf(m.groupValues[1])
            if (found.isEmpty()) m.value else " ".also { calls += found }
        }
    }

    private fun invocationsOf(body: String): List<Invocation> {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            return emptyList()
        }
        val items = when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> listOf(element)
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val args = obj["arguments"] ?: obj["parameters"]
            Invocation(name, when (args) {
                null -> "{}"
                is JsonPrimitive -> (args as JsonPrimitive).contentOrNull ?: "{}"
                else -> args.toString()
            })
        }
    }
}
