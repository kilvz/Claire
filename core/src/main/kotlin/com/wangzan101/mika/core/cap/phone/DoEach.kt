package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.agent.EachCursor
import com.wangzan101.mika.core.agent.EachRunner
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.screen.uistate.session
import com.wangzan101.mika.core.screen.uistate.screenAttempt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `do_each`: run the same steps (open → act → back) for every entry in a list, one entry per round, so the model
 * does not have to reason through them one by one.
 * The loop engine (find the next → skip done ones → scroll for more → stop at the end) is [EachRunner]/ScrollCursor;
 * this tool only handles three things: matching the current screen's entries by item, handing the whole body to
 * phone_run (reusing its step-by-step re-finding and conditional waiting), and scrolling for more.
 * Each entry produces a compact summary, compressing N rounds into 1 instead of one screenshot per entry.
 */
internal object DoEach : PhoneTool {
    override val verb = Verb.DoEach
    override val risk = Risk.Write
    override val composite = true
    override val def = ToolDef("do_each", Prompts.tool("do_each"), mapOf(
        "item" to buildJsonObject {
            put("type", "object")
            put("description", "How to recognize one list item on the current screen.")
            put("properties", buildJsonObject {
                put("role", buildJsonObject { put("type", "string"); put("description", "Accessibility role each item has (e.g. Button, ViewGroup).") })
                put("text", buildJsonObject { put("type", "string"); put("description", "Substring every item's label contains.") })
                put("resourceId", buildJsonObject { put("type", "string"); put("description", "Substring of the items' resource id.") })
            })
            put("additionalProperties", false)
        },
        "body" to buildJsonObject {
            put("type", "array"); put("minItems", 1); put("maxItems", 30)
            put("description", "Steps to run for ONE item, in order. Use \"@item\" as targetText to act on the current item. End with the step that returns to the list (usually back) when each body opens an item.")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("op", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("tap", "type", "clear", "scroll", "swipe", "key", "back", "wait", "assert", "observe").map(::JsonPrimitive))) })
                    for (key in listOf("targetText", "resourceId", "role", "text", "packageName", "direction", "whenText", "key"))
                        put(key, buildJsonObject { put("type", "string") })
                    for (key in listOf("x", "y", "amount", "timeoutMs", "startX", "startY", "endX", "endY", "durationMs"))
                        put(key, buildJsonObject { put("type", "integer") })
                    put("present", buildJsonObject { put("type", "boolean") })
                    put("pressEnter", buildJsonObject { put("type", "boolean") })
                })
                put("required", JsonArray(listOf(JsonPrimitive("op"))))
                put("additionalProperties", false)
            })
        },
        "on_error" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("stop"), JsonPrimitive("skip")))); put("description", "When one item's body fails: stop (default; report and leave the rest) or skip (mark done and continue).") },
        "reveal" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("scroll"), JsonPrimitive("none")))); put("description", "How to find more items when the visible ones are done: scroll (default) or none.") },
        "max_items" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 50); put("description", "Safety cap on items processed per call (default 20).") },
    ), listOf("item", "body"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly { run(a, ctx) }

    private suspend fun run(a: Args, ctx: ToolContext): ToolExecution {
        val item = a.o["item"] as? JsonObject ?: throw ArgError("item", "must be an object")
        val role = item.s("role")
        val text = item.s("text")
        val resourceId = item.s("resourceId")
        if (role == null && text == null && resourceId == null) throw ArgError("item", "need role, text or resourceId")

        val body = a.o["body"] as? JsonArray ?: throw ArgError("body", "must be an array")
        if (body.isEmpty() || body.size > 30) throw ArgError("body", "must have 1-30 steps")

        val onError = when (a.str("on_error") ?: "stop") {
            "stop" -> EachRunner.OnError.Stop
            "skip" -> EachRunner.OnError.Skip
            else -> throw ArgError("on_error", "must be stop or skip")
        }
        val reveal = when (a.str("reveal") ?: "scroll") {
            "scroll" -> true
            "none" -> false
            else -> throw ArgError("reveal", "must be scroll or none")
        }
        val maxItems = a.int("max_items", 1, 50) ?: 20

        val sub = ctx.sub ?: return failed("actions_unavailable")

        val runner = EachRunner(role, text, onError)
        var subIndex = 0
        // @item taps directly by ref: read uses the registered form of screen reading (only then can ref be resolved by tap), and hands this screen's snapshotId to this round's actions.
        var currentSnapshotId: String? = null
        val summary = runner.run(
            read = {
                val snapshot = screenAttempt { ctx.session.snapshot(internal = true) }
                currentSnapshotId = snapshot?.snapshotId
                if (snapshot == null) emptyList()
                else {
                    val r = PhoneAutomationEngine.find(snapshot, PhoneAutomationEngine.Query(text = text, resourceId = resourceId, role = role), 100)
                    (r["matches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                }
            },
            scroll = {
                if (reveal) sub.run(Part.ToolCall("${ctx.callId}.${++subIndex}.phone_scroll", "phone_scroll", buildJsonObject { put("direction", "down"); put("amount", 3) }.toString()))
            },
            doItem = { current ->
                val resolved = JsonArray(body.map { resolveItem(it, current, currentSnapshotId) })
                val result = sub.run(Part.ToolCall("${ctx.callId}.${++subIndex}.phone_run", "phone_run", buildJsonObject { put("commands", resolved) }.toString()))
                result.content["ok"] == JsonPrimitive(true)
            },
            maxItems = maxItems,
        )

        val stoppedAt = summary.stoppedAt
        val reason = when (stoppedAt) {
            null -> null
            "__guard__" -> "max_items_reached"
            else -> "item_failed"
        }
        return ToolExecution(buildJsonObject {
            put("ok", stoppedAt == null)
            put("done", summary.done)
            put("failed", summary.failed)
            stoppedAt?.takeIf { it != "__guard__" }?.let { put("stoppedAt", it) }
            reason?.let { put("reason", it) }
            put("perItem", JsonArray(summary.perItem.map { o -> buildJsonObject { put("item", o.contentKey); put("ok", o.ok) } }))
        })
    }

    /** Replaces the @item in body that refers to this round's entry with that entry's ref + snapshotId (tap directly, not by finding the name again); everything else is left as-is and the whole block is handed to phone_run. */
    private fun resolveItem(step: JsonElement, item: EachCursor.Item, snapshotId: String?): JsonElement {
        val o = step as? JsonObject ?: return step
        val tt = (o["targetText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (tt != "@item") return o
        val ref = item.ref.takeIf { it.isNotBlank() }
        if (ref == null || snapshotId == null) return o
        return buildJsonObject {
            o.forEach { (k, v) -> if (k != "targetText") put(k, v) }
            put("ref", ref)
            put("snapshotId", snapshotId)
        }
    }

    private fun failed(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })

    private fun JsonObject.s(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}
