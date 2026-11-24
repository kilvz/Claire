package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.tag
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Agent loop: execution of a batch of tool calls and its result events
/**
 * Execute one do_steps: the whole batch counts as one step against the step limit, while each substep counts as one action for the guards and the run record (recorded by [BatchRunner]).
 * When the batch stops, the next step goes back to the main model. A non-null return means the task ends here.
 */
internal suspend fun AgentLoop.batch(
    first: Part.ToolCall, calls: ReplyCalls, history: MutableList<Message>, specs: List<ToolSpec>, guards: OperationGuards,
    executor: ActionExecutor, track: StepTrack, eachLoop: EachLoopDetector, step: Int, note: String, tool: String?, reason: String?, narration: String?,
    recordReply: (String?) -> Unit, app: (Place) -> Unit, steerWaiting: () -> Boolean,
    noteVerdicts: (BatchStep, ToolExecution?, List<GuardVerdict>) -> String?,
): TaskState? {
    // A batch breaks the streak of doing things one by one (which is the direction the reminder wants): reset the consecutive count
    eachLoop.reset()
    // Tools from a collapsed group can also go into a batch (they execute as usual and expand their group, same as a single step)
    val (parsed, problem) = BatchRunner.parse(first.argsJson, tools.definitions().map { it.name }.toSet(), tools.batchable())
    // A batch runs on a single screen only: a whole batch that mixes two screens is not executed
    val mixed = parsed?.let { BatchRunner.mixedSurface(it, tools::surfaceOf) }
    val steps = parsed.takeIf { mixed == null }
    val running = TaskState.Running(step, note, tool, steps?.let { RunProgress.Batch(it.size) }, reason, narration)
    if (steps != null) _state.value = running
    val started = now()
    var runner: BatchRunner? = null
    val exec = if (steps == null) ToolExecution(buildJsonObject {
        put("ok", false); put("error", if (mixed != null) "mixed_surface" else "invalid_arguments"); put("message", mixed ?: problem); put("recoverable", true)
    }) else {
        gateState = running
        runner = BatchRunner(tools, guards, executor, noteVerdicts = noteVerdicts, steerWaiting = steerWaiting)
        runner.run(steps, first.id, step)
    }
    val durationMs = now() - started
    val isError = exec.content["ok"] != JsonPrimitive(true)
    history += calls.results(Part.ToolResult(first.id, ScreenElementTable.text(exec.content, flags.tablesAtBirth && !isError), isError, exec.observations.map { it.tag }))
    // After the batch finishes (or stops), give the model a current element table; attach a frame only when the element information is insufficient.
    if (steps != null && ranAnyStep(exec.content)) {
        tools.lookNow()?.let { v ->
            history += Message(Role.User, listOfNotNull(Part.Text(batchViewNote(ScreenElementTable.text(v.content, flags.tablesAtBirth))), v.image?.let { tools.modelImage(it, v.imageId) }), Author.Mika)
        }
    }
    recordReply(BatchRunner.redactArgs(first.argsJson))
    val picture = exec.frame
    val shotId = picture?.let { executor.recorder.nextShot() }
    // Time spent conditionally waiting for the UI after substeps is recorded separately and counted as waiting in the summary
    emit(RunEvent.ToolResult(now(), step, first.name, RunEvent.clipResult(Redactor.text(exec.content.toString())), exec.changed, shotId, durationMs,
        waitMs = runner?.waitedMs ?: 0, callId = first.id))
    val currentPlace = exec.place
    currentPlace?.let(app)
    // Needing confirmation does not count as a failure (like a blocked single step it does not enter the failure circuit breaker), but it is still handed to the main model
    // A batch stopped for a new interjection likewise does not count as a failure
    val confirm = exec.content["error"] == JsonPrimitive("confirmation_required") || exec.content["reason"] == JsonPrimitive(BatchRunner.NEW_MESSAGE)
    track.after(tools.verb(first.name), !isError || confirm, runner?.verdicts?.isNotEmpty() == true || exec.content["advice"] != null, exec.changed, false, currentPlace?.name)
    // The batch stopped (review did not pass, or confirmation was needed): the next step goes to the main model
    if (isError) track.markRecovery()
    if (picture != null && shotId != null) {
        record { sink.media(shotId, picture) }
        media.getOrPut(step) { mutableListOf() } += shotId
        emit(RunEvent.Media(now(), shotId, step, RunEvent.placeLabel(currentPlace)))
    }
    return guards.stop?.let(StopRules::guard)
}

internal fun AgentLoop.previewFor(step: Int): ((String) -> Unit)? = if (!streamReplies) null else { raw ->
    val preview = CompletionPlanBlock.visible(raw)
    val current = _state.value as? TaskState.Running
    // After removal the network thread may still deliver another chunk: do not show it, so the screen does not hold more than the history
    if (current?.step == step && interruptedStep != step) _state.value = current.copy(preview = preview.takeIf(String::isNotEmpty))
}

/** At least one substep in the do_steps result was actually executed (stop items that did not execute are marked executed=false). */
internal fun AgentLoop.ranAnyStep(content: JsonObject): Boolean =
    (content["steps"] as? JsonArray)?.any { (it as? JsonObject)?.get("executed") != JsonPrimitive(false) } == true

/** Things recorded separately from the result in the run record: reported obstacles (so evaluation can attribute them to the barrier category; ones rejected for bad arguments do not count), personal skills that were viewed, and memories saved. */
internal fun AgentLoop.noteResultEvents(verb: Verb?, content: JsonObject) {
    if (verb == Verb.HandleObstacle && content["ok"] == JsonPrimitive(true)) {
        (content["kind"] as? JsonPrimitive)?.contentOrNull?.let { emit(RunEvent.Barrier(now(), it)) }
    }
    GuideRecommender.viewedPersonal(verb, content)?.let { emit(RunEvent.SkillUsed(now(), it, "viewed")) }
    if (verb == Verb.MemorySave && content["ok"] == JsonPrimitive(true)) emit(RunEvent.MemorySaved(now(),
        content["id"]?.jsonPrimitive?.content ?: "", content["kind"]?.jsonPrimitive?.content ?: "", content["status"]?.jsonPrimitive?.content ?: ""))
}

/** While an MCP call is in progress, mark Running with the connector name and take it back when the call ends (including failure and cancellation); the caller invokes the returned closure in a finally block. */
internal fun AgentLoop.showMcpCall(connector: String): () -> Unit {
    val before = _state.value
    if (before !is TaskState.Running) return {}
    val calling = before.copy(mcpConnector = connector)
    _state.compareAndSet(before, calling)
    return { _state.compareAndSet(calling, before) }
}
