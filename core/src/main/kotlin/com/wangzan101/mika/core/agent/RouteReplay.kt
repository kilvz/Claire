package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.screen.observation.tag
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.learn.replay.ReplayOutcome
import com.wangzan101.mika.core.learn.replay.Replayer
import com.wangzan101.mika.core.learn.replay.Route
import com.wangzan101.mika.core.learn.replay.RouteMatcher
import com.wangzan101.mika.core.screen.map.ScreenMapStore
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val REPLAY_SOURCE: String get() = Feedback["replay_source"]
private val REPLAY_SCREEN_HEADER: String get() = Feedback["replay_screen_header"]
private val REPLAY_NO_SCREEN: String get() = Feedback["replay_no_screen"]

/**
 * Experience replay: before the first model call, if a suitable route exists, follow it first, and hand back to the
 * model whether it finishes or diverges; after the task ends, update the route's counts by the outcome.
 * Problems with the route itself (matching, reading or writing routes) never affect the task. [reset] at the start of
 * every task.
 */
internal class RouteReplay(
    private val routes: RouteMatcher,
    private val tools: ToolDispatcher,
    private val gate: suspend () -> Unit,
    /** Exposes replay progress to the outside (also the state restored after the gate finishes waiting). */
    private val show: (TaskState.Running) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val clock: () -> Long,
    /** Screen maps (when present, the route is checked against them during replay, and hand-back gives "which screen it landed on + go from here"). */
    private val screenMaps: ScreenMapStore? = null,
) {
    /** The route replayed in this task and its result. */
    private var replayed: Pair<Route, ReplayOutcome>? = null

    fun reset() { replayed = null }

    /** The element-list tool for the screen most recently operated on, used for the screen read on hand-back (whichever screen the replayed route landed on is the one read). */
    private val dumpTool: String get() = tools.index.nameOf(Verb.ListElements, tools.surface)!!

    /**
     * Finds a route and replays it; if replay happened, hands the model what happened (which steps ran, where it
     * diverged) plus a full current screen read, and returns the foreground App from that read.
     */
    suspend fun run(task: String, history: MutableList<Message>, executor: ActionExecutor, ctx: ContextBuilder, track: StepTrack): Place? {
        val choice = try {
            withContext(Dispatchers.IO) { routes.choose(task) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val total = choice.route.steps.size
        val graph = choice.route.packages.firstOrNull()?.let { pkg -> screenMaps?.load(pkg) }
        var running = TaskState.Running(0, REPLAY_NOTE, progress = RunProgress.Replay(1, total))
        show(running)
        val replayer = Replayer(
            tools, executor, gate = gate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    emit(e)
                    // Once a step matches, show the next step
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        running = running.copy(progress = RunProgress.Replay(e.step + 1, e.total))
                        show(running)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
            clock = clock,
            graph = graph,
        )
        val out = replayer.run(choice)
        replayed = choice.route to out
        update(choice.route.id) { r ->
            when (out) {
                is ReplayOutcome.Diverged -> {
                    val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                    r.copy(consecutiveMisses = misses, missStep = out.done, disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE, lastUsedMs = clock())
                }
                else -> r.copy(lastUsedMs = clock())
            }
        }
        val steps = choice.route.steps
        val doneLines = steps.take(out.done).mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }.joinToString("\n")
        val head = when (out) {
            is ReplayOutcome.Completed -> Feedback.format("replay.completed", "total" to steps.size, "steps" to doneLines)
            is ReplayOutcome.Diverged -> {
                val error = out.error?.let { Feedback.format("replay.error_code", "code" to it) }.orEmpty()
                val next = if (out.executed) Feedback.format("replay.diverged_executed", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                else Feedback.format("replay.diverged_skipped", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                val map = out.screenMap?.let { Feedback.format("replay.screen_map", "map" to it) }.orEmpty()
                Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] }, "next" to (next + map))
            }
            is ReplayOutcome.StoppedForConfirmation -> Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] },
                "next" to Feedback.format("replay.stopped", "step" to (out.done + 1), "action" to Replayer.describe(steps[out.done], choice.slots)))
        }
        // Current full screen read: the model takes IDs from here to continue; if it cannot be read, only the explanation is handed over and the task continues as usual
        val looked = try {
            tools.executeRead(Part.ToolCall("replay_handback", dumpTool, "{}"), 0).takeIf { it.content["ok"] == JsonPrimitive(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val read = looked?.content
        if (read != null) {
            // The screen read is made a matching tool call and result: trimming and "which screens the model can see" recognize it as the screen-read tool, and every provider's requirement that a call pair with its result is met
            val id = "replay_handback"
            history += Message(Role.Assistant, listOf(Part.Text(Feedback.format("replay.handback", "head" to head, "note" to REPLAY_SCREEN_HEADER)), Part.ToolCall(id, dumpTool, "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult(id, read.toString(), isError = false, looked?.observations.orEmpty().map { it.tag })), Author.Mika)
            // A primary model that can see images: on hand-back it sees the current frame just as it would after an action
            tools.imageNow()?.let { (png, shotId) -> history += Message(Role.User, listOf(Part.Text(Feedback["action_frame_note"]), tools.modelImage(png, shotId)), Author.Mika) }
        } else {
            history += ctx.notice(REPLAY_SOURCE, Feedback.format("replay.handback", "head" to head, "note" to REPLAY_NO_SCREEN))
        }
        // The step after hand-back counts as a recovery step (with large/light routing it goes to the primary model)
        track.markRecovery()
        return looked?.place
    }

    /** Replay finished and this run counts as accomplished ([LearnGate]; [outcome] is the outcome judged after the answer was delivered): success count +1, consecutive misses cleared. */
    suspend fun noteResult(end: TaskState, outcome: RunOutcome) {
        val (route, out) = replayed ?: return
        if (out !is ReplayOutcome.Completed || !LearnGate.done(end, outcome)) return
        update(route.id) { it.copy(successes = it.successes + 1, consecutiveMisses = 0, missStep = null) }
    }

    private suspend fun update(id: String, change: (Route) -> Route) {
        val store = routes.store
        try {
            // Read-modify-write in one step: when another task concurrently changes this route, no counts are lost
            withContext(Dispatchers.IO) { store.update(id, change) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Failing to write the route count does not affect the task
        }
    }

    companion object {
        /** The note for this step shown in the UI during replay. */
        const val REPLAY_NOTE = "按以往的做法操作"
        /** The route is disabled after this many consecutive misses at the same step. */
        const val ROUTE_MISSES_TO_DISABLE = 2
    }
}
