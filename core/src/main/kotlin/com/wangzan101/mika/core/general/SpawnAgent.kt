package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.run.ChildPhase
import com.wangzan101.mika.core.run.ChildRunHost
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.observation.Trust
import com.wangzan101.mika.core.screen.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpawnAgent : AgentTool {
    /** Default step budget and maximum step budget for a child task. */
    const val DEFAULT_STEPS = 15
    const val MAX_STEPS = 40
    /** How many characters of a child result are kept by default, the most, and the least. */
    const val DEFAULT_RESULT_CHARS = 4_000
    const val MAX_RESULT_CHARS = 8_000
    const val MIN_RESULT_CHARS = 500
    /** How many at most can be dispatched side by side in one go. Beyond that, split into several calls so one round's token budget is not eaten up at once. */
    const val MAX_BATCH = 20

    override val verb = Verb.SpawnAgent
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("spawn_agent", Prompts.tool("spawn_agent",
        "steps" to DEFAULT_STEPS, "maxSteps" to MAX_STEPS, "chars" to DEFAULT_RESULT_CHARS, "maxChars" to MAX_RESULT_CHARS), mapOf(
        "goal" to str("One bounded research question, for a single child agent. Use tasks instead to run several at once."),
        "tasks" to strArray("Run several child agents at the same time, one goal per item. They all run in parallel and this call returns when every one is done; use it whenever the answers do not depend on each other. Up to $MAX_BATCH items.", MAX_BATCH),
        "format" to str("Optional output format.", listOf("text", "json")),
        "maxSteps" to int("Optional step budget for the child, 1-$MAX_STEPS (default $DEFAULT_STEPS); larger values are lowered to $MAX_STEPS.", 1),
        "maxResultChars" to int("Optional length limit of the returned result in characters, $MIN_RESULT_CHARS-$MAX_RESULT_CHARS (default $DEFAULT_RESULT_CHARS); values outside are moved into range.", 1),
    ), listOf())

    override fun available(ctx: ToolContext) = ctx.config.childHost != null && ctx.config.runSpec != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val parent = ctx.config.runSpec ?: error("no parent RunSpec")
        val host = ctx.config.childHost ?: error("no child host")
        val format = a.str("format")
        // Only check that it is an integer; out-of-range values are pulled back into range instead of being an error (the child can still be dispatched)
        val steps = (a.int("maxSteps", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        val limit = (a.int("maxResultChars", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_RESULT_CHARS).coerceIn(MIN_RESULT_CHARS, MAX_RESULT_CHARS)
        val goals = a.strList("tasks", min = 1, max = MAX_BATCH, itemMax = 4000)
            ?: listOfNotNull(a.str("goal", max = 4000)).takeIf { it.isNotEmpty() }
            ?: return failure("给一个 goal,或用 tasks 一次派几个")
        if (goals.size == 1) return runOne(goals[0], parent, host, format, steps, limit)
        // Several: start them all at once and return once all are done; a failure in any one does not affect the others, and each result is reported separately
        val results = coroutineScope {
            goals.map { goal ->
                async {
                    goal to try {
                        runOne(goal, parent, host, format, steps, limit, parallelChild = true)
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val message = Redactor.text("子任务起不来: ${e.message ?: e::class.simpleName}").take(limit)
                        ToolExecution(buildJsonObject {
                            put("ok", false)
                            put("goal", goal.take(200))
                            put("status", "failed")
                            put("error", Untrusted.wrap(message))
                        })
                    }
                }
            }.awaitAll()
        }
        return merge(results)
    }

    private suspend fun runOne(goal: String, parent: RunSpec, host: ChildRunHost,
                               format: String?, steps: Int, limit: Int, parallelChild: Boolean = false): ToolExecution {
        val child = RunSpec(RunId(UUID.randomUUID().toString()), goal, parent.threadId,
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("web", "sandbox"), readOnly = true, parent = parent.id, stepBudget = steps,
            parallelChild = parallelChild)
        val result = host.spawn(child, format)
        val text = result.text.take(limit)
        return ToolExecution(buildJsonObject {
            put("ok", result.phase == ChildPhase.Completed)
            put("goal", goal.take(200))
            put("childRunId", result.id.value)
            put("status", result.phase.name.lowercase())
            put("maxSteps", steps)
            put("result", Untrusted.wrap(text))
            if (result.text.length > limit) {
                put("truncated", true)
                put("fullLength", result.text.length)
            }
        }, observations = listOf(Observation(ObsKind.Reply, text, source = "child", trust = Trust.Untrusted)))
    }

    /** Merge the bodies, and return each item's status and other metadata in the same order. */
    private fun merge(results: List<Pair<String, ToolExecution>>): ToolExecution {
        val okAll = results.all { (_, r) -> r.content["ok"] == JsonPrimitive(true) }
        val text = results.joinToString("\n\n") { (goal, r) ->
            "### ${goal.take(200)}\n${r.observations.firstOrNull()?.text.orEmpty()}"
        }
        return ToolExecution(buildJsonObject {
            put("ok", okAll)
            put("ranInParallel", results.size)
            put("result", Untrusted.wrap(text))
            put("results", JsonArray(results.map { (_, r) -> JsonObject(r.content - "result") }))
        }, observations = results.mapNotNull { it.second.observations.firstOrNull() })
    }

    private fun failure(message: String) = ToolExecution(buildJsonObject {
        put("ok", false)
        put("error", message)
    })
}
