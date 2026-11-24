package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

const val DO_STEPS_TOOL = "do_steps"
private const val MIN_STEPS = 1
/** Only limits the resource consumption of a single call; it does not turn the reasonable length of consecutive operations into a task rule. */
private const val MAX_STEPS = 20
private const val EXPECT_MAX = 200
/** From the second step on, the upper bound for waiting for the interface to settle before judging and executing (same as the screen an action carries). */
private const val SETTLE_MS = 1500L
/** Wait conditions for one step (all optional); expect is the old form, its meaning changed from "take a look when done" to "wait until it appears". */
private const val EXPECT = "expect"
private const val EXPECT_GONE = "expect_gone"
private const val EXPECT_APP = "expect_app"
private const val WAIT_STABLE = "wait_stable"
private const val TIMEOUT_S = "timeout_s"
private val STEP_FIELDS = setOf("tool", "args", EXPECT, EXPECT_GONE, EXPECT_APP, WAIT_STABLE, TIMEOUT_S)

/** App access is blocked: the batch must stop, and the explanation from the execution entry point is passed to the model as-is. */
private fun appAccessDenied(code: String) = com.wangzan101.mika.core.device.access.AppAccessDenied.isAppAccessCode(code)

/** One step in a batch; [until] is the state to wait for after it is done and before continuing, null when not given. */
data class BatchStep(val tool: String, val argsJson: String, val until: Until? = null)

/**
 * Run several consecutive actions at once, checking each one step by step, and stop on anything unexpected. Each step
 * (from the second one on, after first waiting for the interface to settle) runs through [ActionExecutor], with the
 * same sequence as a single step; the batch uses and assumes no consent at all, so an irreversible step stops the batch
 * before it, without being executed.
 * A check fails when: the tool fails, focus switched to another app, or the typed input reads back inconsistently. When
 * a step carries a wait condition ([Until]), after it is done the batch first waits for that to hold
 * ([ToolDispatcher.waitUntil]: screen reads compared literally, no model asked); it stops when the deadline passes, a
 * dialog is detected, or the app changed; how long it waited is written into that step's result.
 * For guards and the run record, each step is one action (source = "batch" in the run record).
 */
class BatchRunner(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    private val executor: ActionExecutor,
    /** The guard verdicts for each sub-step (once before and once after execution) are handed back to the loop, counting toward stuck reminders and repeated input; returns the advice to attach for the model. */
    private val noteVerdicts: (step: BatchStep, exec: ToolExecution?, verdicts: List<GuardVerdict>) -> String? = { _, _, _ -> null },
    /** Whether a new steer from the user is waiting right now (only looks at the in-memory inbox); if so, stop between two sub-steps and let the loop deliver it. */
    private val steerWaiting: () -> Boolean = { false },
) {
    private class Stop(
        val index: Int, val reason: String, val entry: JsonObject, val executed: Boolean, val extra: JsonObject = JsonObject(emptyMap()),
        /** The sub-steps not done after the stop also get a result each (when a new steer arrives), so sub-steps and results match one-to-one. */
        val rest: List<JsonElement> = emptyList(),
    )

    /** All guard verdicts seen across the sub-steps; the loop uses them to decide whether this step had a guard warning. */
    val verdicts = mutableListOf<GuardVerdict>()
    private var advice: String? = null

    /** The total time the steps spent waiting for the interface per their conditions; the run record counts it as waiting, not as operations. */
    var waitedMs = 0L
        private set

    suspend fun run(steps: List<BatchStep>, callId: String, step: Int = 0): ToolExecution {
        val entries = mutableListOf<JsonElement>()
        var last: ToolExecution? = null
        // The observations read by each sub-step (already recorded in the ledger); the batch result carries them so that trimming can shorten by their form
        val seen = mutableListOf<Observation>()
        var stop: Stop? = null
        fun skipFrom(i: Int): Stop {
            val skipped = steps.drop(i).map { entry(it.tool) { put("ok", false); put("error", NEW_MESSAGE); put("executed", false) } }
            return Stop(i, NEW_MESSAGE, skipped.first(), executed = false, rest = skipped.drop(1))
        }
        for ((i, s) in steps.withIndex()) {
            if (i > 0 && steerWaiting()) {
                stop = skipFrom(i)
                break
            }
            // When the previous step's interface is still changing, the check reads a transitional frame and expect also misjudges
            if (i > 0) tools.settle(SETTLE_MS)
            val out = executor.run(Part.ToolCall("$callId.${i + 1}", s.tool, s.argsJson), Origin.Batch(step))
            val before = out.before
            note(s, null, before)
            fun notRun(reason: String, extra: JsonObject = JsonObject(emptyMap()), message: String? = null) = Stop(i, reason, entry(s.tool) {
                put("ok", false); put("error", reason); put("executed", false)
                message?.let { put("message", it) }
                if (before.isNotEmpty()) put("guard", JsonArray(before.map { it.toJson() }))
            }, executed = false, extra = extra)
            val exec = when (out) {
                is ActionOutcome.Ran -> out.exec
                is ActionOutcome.Refused -> {
                    val hit = out.hit
                    // App permission denied: hand the next-step explanation from the execution entry point to the model as well, so it does not only see the error code
                    val message = (out.content["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { appAccessDenied(out.code) }
                    stop = if (hit == null) notRun(out.code, message = message)
                    else notRun(out.code, buildJsonObject { put("action", hit.action.name); put("matched", hit.matched) }, message)
                    break
                }
                is ActionOutcome.Cancelled -> { stop = notRun("cancelled"); break }
            }
            // The pause, interval or this wait before the step was cut short by a user steer: this step did nothing, so it is skipped along with the rest, and the loop goes to deliver that message
            if (exec.content["error"] == JsonPrimitive(NEW_MESSAGE)) {
                waitedMs += exec.waitMs
                stop = skipFrom(i)
                break
            }
            seen += exec.observations
            // Settle pauses added for pacing inside a sub-step also count as waiting for this batch
            waitedMs += exec.waitMs
            val afterVerdicts = out.after
            note(s, exec, afterVerdicts)
            val all = before + afterVerdicts
            last = exec
            val c = exec.content
            val screen = c["screen"] as? JsonObject
            val failed = c["ok"] != JsonPrimitive(true)
            val hard = when {
                failed -> (c["error"] as? JsonPrimitive)?.contentOrNull ?: "failed"
                c["verification"] == JsonPrimitive("mismatch") -> "action_not_applied"
                c["app_switched"] != null -> "app_switched"
                // Opened onto the user's home screen (even if moved back, the location changed): the following steps were planned for that other screen
                c["opened"] != null -> "opened_elsewhere"
                // The website or app reports it has reached its limit: do the following steps no more
                c["limitReached"] != null -> "limit_reached"
                else -> null
            }
            val review = i < steps.lastIndex && screen != null &&
                (screen["imageReason"] != null || screen["protected"] == JsonPrimitive(true) ||
                    screen["captureError"] != null || screen["readable"] == JsonPrimitive(false) || screen["note"] != null)
            // The screen an action carries may still show a loading state: once the text to appear has appeared, there is no need to look at the image
            val until = s.until?.takeIf { hard == null && (!review || it.appears != null) }
            val waited = until?.let { tools.waitUntil(it, executor.recorder::now, steerWaiting) }
            waited?.let { waitedMs += it.waitedMs }
            val reason = hard ?: when {
                waited != null && !waited.met -> when (waited.stopped) {
                    UntilOutcome.TIMEOUT -> "expectation_unmet"
                    UntilOutcome.NEW_MESSAGE -> null
                    else -> waited.stopped
                }
                review && waited?.met != true -> "screen_needs_review"
                else -> null
            }
            val e = entry(s.tool) {
                put("ok", reason == null)
                exec.changed?.let { put("changed", it) }
                effectKind(c)?.let { put("effect", it) }
                (c["verification"] as? JsonPrimitive)?.let { put("verification", it) }
                reason?.let { put("error", it) }
                if (waited != null && !waited.met) s.until?.let { untilFields(it).forEach { (k, v) -> put(k, v) } }
                waited?.let { w ->
                    put("wait", buildJsonObject { put("met", w.met); w.stopped?.let { put("stopped", it) }; put("waited_ms", w.waitedMs) })
                }
                (c["app_switched"] as? JsonPrimitive)?.let { put("app_switched", it) }
                (c["opened"] as? JsonObject)?.let { put("opened", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                (c["chooser"] as? JsonObject)?.let { put("chooser", it) }
                (c["limitReached"] as? JsonPrimitive)?.let { put("limitReached", it); (c["hint"] as? JsonPrimitive)?.let { h -> put("hint", h) } }
                if (all.isNotEmpty()) put("guard", JsonArray(all.map { it.toJson() }))
            }
            if (reason != null) { stop = Stop(i, reason, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break }
            // The user steered while waiting: the following sub-steps are skipped as usual at the start of the next round; if this is already the last step, stop here
            if (waited?.stopped == UntilOutcome.NEW_MESSAGE && i == steps.lastIndex) {
                stop = Stop(i, NEW_MESSAGE, JsonObject(e + ("executed" to JsonPrimitive(true))), executed = true); break
            }
            entries += e
            if (guards.stop != null && i < steps.size - 1) {
                stop = Stop(i + 1, "guard_stopped", entry(steps[i + 1].tool) { put("ok", false); put("error", "guard_stopped"); put("executed", false) }, executed = false)
                break
            }
        }
        return finish(steps, entries, stop, last, callId, step, seen)
    }

    private fun finish(
        steps: List<BatchStep>, done: List<JsonElement>, stop: Stop?, last: ToolExecution?, callId: String, step: Int, seen: List<Observation>,
    ): ToolExecution {
        val completed = stop?.index ?: steps.size
        executor.recorder.event(RunEvent.Batch(executor.recorder.now(), completed, steps.size, stop != null, stop?.reason))
        val screen = last?.content?.get("screen")
        val content = buildJsonObject {
            put("ok", stop == null)
            when (stop?.reason) {
                null -> Unit
                "confirmation_required" -> {
                    put("error", "confirmation_required")
                    stop.extra.forEach { (k, v) -> put(k, v) }
                    val what = stop.extra["matched"]?.let { (it as JsonPrimitive).content }
                    put("message", "Step ${stop.index + 1} of ${steps.size} spends money ($what). " +
                        "It was not run, and the steps after it were not run either. Payment steps never go in do_steps: " +
                        if (executor.confirms) "follow the step's message (request_confirmation, or hand_to_user for a payment), then do that step on its own as a single tool call."
                        else "do it on its own as a single tool call, not as part of a batch.")
                    put("counts_as_failure", false)
                }
                NEW_MESSAGE -> {
                    put("error", "batch_stopped")
                    put("reason", NEW_MESSAGE)
                    put("message", (if (stop.executed) "Step ${stop.index + 1} of ${steps.size} was run, but waiting for its condition was cut short"
                        else "Steps ${stop.index + 1}-${steps.size} of ${steps.size} were skipped") + " to handle a new message from the user. " +
                        "Read the message first, then decide what to do next.")
                    put("counts_as_failure", false)
                }
                else -> {
                    put("error", "batch_stopped")
                    put("reason", stop.reason)
                    val n = "Step ${stop.index + 1} of ${steps.size}"
                    if (appAccessDenied(stop.reason)) {
                        // A permission denial is not an ordinary operation failure: do not suggest "continue with another batch", but state clearly what to do next
                        val detail = (stop.entry["message"] as? JsonPrimitive)?.contentOrNull
                            ?: "Step ${stop.index + 1} of ${steps.size} was not run (${stop.reason})."
                        put("message", "$detail The remaining steps in this batch were not run.")
                        put("retryable", false)
                    } else {
                        put("message", (if (stop.executed) "$n was run but did not pass its check (${stop.reason})" else "$n was not run (${stop.reason})") +
                            "; the steps after it were not run. Check the screen, then continue one step at a time or with a new batch.")
                    }
                }
            }
            advice?.let { put("advice", it) }
            put("completed", completed); put("total", steps.size)
            put("steps", JsonArray(done + listOfNotNull(stop?.entry) + stop?.rest.orEmpty()))
            screen?.let { put("screen", it) }
            if (stop != null) put("recoverable", true)
        }
        // The sub-step screens have already been saved one by one
        // The screen of the last sub-step to send to the model (only for main models that can see images) is left to the loop, to be appended after the whole batch result
        return ToolExecution(content, changed = last?.changed, frame = if (!executor.recorder.savesScreens) last?.frame ?: last?.image else null,
            screenElements = last?.screenElements, images = last?.images.orEmpty(), imageIds = last?.imageIds.orEmpty(), place = last?.place,
            observations = seen)
    }

    private fun note(s: BatchStep, exec: ToolExecution?, v: List<GuardVerdict>) {
        verdicts += v
        noteVerdicts(s, exec, v)?.let { advice = it }
    }

    private fun entry(tool: String, b: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("tool", tool); b() }

    companion object {
        /** The stop reason when a sub-step was not done because the user sent a new steer. */
        const val NEW_MESSAGE = SKIPPED_FOR_NEW_MESSAGE

        /**
         * Validate the arguments of do_steps; [available] is the tool names currently offered to the model, [batchable] the tools that can go into a batch (per the tool declarations,
         * only screen actions and waiting; observing, asking, taking over and confirming are all excluded).
         * Returns the steps, or an error description naming which step is at fault.
         */
        fun parse(argsJson: String, available: Set<String>, batchable: List<String>): Pair<List<BatchStep>?, String?> {
            val o = try { Json.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject } catch (e: SerializationException) { null }
                ?: return null to "arguments: must be a JSON object"
            o.keys.firstOrNull { it != "steps" && it != "reason" }?.let { return null to "$it: unknown argument for do_steps" }
            val arr = o["steps"] as? JsonArray ?: return null to "steps: required, an array of $MIN_STEPS-$MAX_STEPS steps"
            if (arr.size !in MIN_STEPS..MAX_STEPS) return null to "steps: must have $MIN_STEPS-$MAX_STEPS items"
            val out = arr.mapIndexed { i, e ->
                val n = i + 1
                val s = e as? JsonObject ?: return null to "steps[$n]: must be an object with tool, args and optional expect"
                s.keys.firstOrNull { it !in STEP_FIELDS }?.let { return null to "steps[$n].$it: unknown field" }
                val tool = (s["tool"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null to "steps[$n].tool: required"
                if (tool !in batchable || tool !in available) {
                    return null to "steps[$n].tool: $tool cannot be batched; only ${batchable.filter { it in available }.joinToString()}"
                }
                val args = when (val a = s["args"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> a
                    else -> return null to "steps[$n].args: must be an object"
                }
                val texts = listOf(EXPECT, EXPECT_GONE, EXPECT_APP).associateWith { k ->
                    when (val x = s[k]) {
                        null -> null
                        is JsonPrimitive -> x.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: return null to "steps[$n].$k: must be a non-blank string"
                        else -> return null to "steps[$n].$k: must be a string"
                    }?.also { if (it.length > EXPECT_MAX) return null to "steps[$n].$k: must be at most $EXPECT_MAX characters" }
                }
                val stable = when (val x = s[WAIT_STABLE]) {
                    null -> false
                    is JsonPrimitive -> x.takeIf { !it.isString }?.booleanOrNull ?: return null to "steps[$n].$WAIT_STABLE: must be true or false"
                    else -> return null to "steps[$n].$WAIT_STABLE: must be true or false"
                }
                val max = Until.MAX_MS / 1000
                val seconds = when (val x = s[TIMEOUT_S]) {
                    null -> null
                    is JsonPrimitive -> x.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 1.0..max.toDouble() }
                        ?: return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                    else -> return null to "steps[$n].$TIMEOUT_S: must be a number of seconds, 1-$max"
                }
                val waits = texts.values.any { it != null } || stable
                if (seconds != null && !waits) return null to "steps[$n].$TIMEOUT_S: only goes with $EXPECT, $EXPECT_GONE, $EXPECT_APP or $WAIT_STABLE"
                val until = if (!waits) null else Until(texts[EXPECT], texts[EXPECT_GONE], texts[EXPECT_APP], stable,
                    seconds?.let { (it * 1000).toLong() } ?: Until.DEFAULT_MS)
                BatchStep(tool, args.toString(), until)
            }
            return out to null
        }

        /** Wait conditions are handed back in the model's own wording (attached to that step's result when the wait was not met). */
        private fun untilFields(u: Until): JsonObject = buildJsonObject {
            u.appears?.let { put(EXPECT, it) }
            u.gone?.let { put(EXPECT_GONE, it) }
            u.place?.let { put(EXPECT_APP, it) }
            if (u.stable) put(WAIT_STABLE, true)
            put(TIMEOUT_S, u.timeoutMs / 1000.0)
        }

        /**
         * The explanation given to the model when the steps in a batch act on more than one screen ([surfaceOf] is the screen a tool acts on, null for waits that do not touch the screen);
         * null when they are all on the same screen.
         */
        fun mixedSurface(steps: List<BatchStep>, surfaceOf: (String) -> String?): String? {
            val surfaces = steps.mapNotNull { surfaceOf(it.tool) }.distinct()
            if (surfaces.size < 2) return null
            return Feedback.format("batch.mixed_surface", "surfaces" to surfaces.joinToString(", "))
        }

        /** For the run record: text typed inside the batch is always masked (the entry point has not said whether it is a password field). */
        fun redactArgs(argsJson: String): String {
            val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return Redactor.text(argsJson)
            val steps = o["steps"] as? JsonArray ?: return Redactor.text(argsJson)
            val masked = JsonArray(steps.map { s ->
                val so = s as? JsonObject ?: return@map s
                val a = so["args"] as? JsonObject
                if (a == null || "text" !in a) s else JsonObject(so + ("args" to JsonObject(a + ("text" to JsonPrimitive("***")))))
            })
            return Redactor.text(JsonObject(o + ("steps" to masked)).toString())
        }

        /** The definition of do_steps; [batchable] is the tool names that can go into a batch. */
        fun spec(batchable: List<String>) = ToolSpec(
            DO_STEPS_TOOL,
            Prompts.tool("do_steps"),
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("steps", buildJsonObject {
                        put("type", "array"); put("minItems", MIN_STEPS); put("maxItems", MAX_STEPS)
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(batchable.map(::JsonPrimitive))) })
                                put("args", buildJsonObject { put("type", "object"); put("description", "The arguments that tool takes on its own.") })
                                put(EXPECT, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: after this step, wait until an element's name contains this text. Text already on screen before the step also matches, so pick text the step brings up.")
                                })
                                put(EXPECT_GONE, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until no element's name contains this text (a loading or progress label).")
                                })
                                put(EXPECT_APP, buildJsonObject {
                                    put("type", "string"); put("maxLength", EXPECT_MAX)
                                    put("description", "Optional: wait until the app in front (package name) or the page address contains this.")
                                })
                                put(WAIT_STABLE, buildJsonObject { put("type", "boolean"); put("description", "Optional: wait until the screen stops changing.") })
                                put(TIMEOUT_S, buildJsonObject {
                                    put("type", "number"); put("minimum", 1); put("maximum", Until.MAX_MS / 1000)
                                    put("description", "Optional: longest wait for this step's conditions, in seconds (default ${Until.DEFAULT_MS / 1000}).")
                                })
                            })
                            put("required", JsonArray(listOf(JsonPrimitive("tool"), JsonPrimitive("args"))))
                            put("additionalProperties", false)
                        })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", com.wangzan101.mika.core.tool.REASON_NOTE) })
                })
                put("required", JsonArray(listOf(JsonPrimitive("steps"))))
                put("additionalProperties", false)
            }.toString(),
        )
    }
}

/** The kind of the effect in the result; null when the action reported no effect. */
/** The kind of the actual effect the action reported back (the capability is written in the result's effect); null when not reported. */
private fun effectKind(c: JsonObject): String? = ((c["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
