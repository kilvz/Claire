package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.screen.map.ScreenGraph
import com.wangzan101.mika.core.screen.map.ScreenMapRender
import com.wangzan101.mika.core.screen.map.ScreenRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot
import com.wangzan101.mika.core.tool.Verb

sealed interface ReplayOutcome {
    /** The number of steps completed (or verified and passed). */
    val done: Int

    data class Completed(override val done: Int) : ReplayOutcome
    /**
     * [executed]: whether step done+1 was really executed (the screen may already have changed); false when it was
     * rejected before execution (bad arguments, link, expired ID, app busy, etc.).
     * [error] is the error code of that step's tool result, which is also reported to the model when handing back.
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
        /** Which screen it landed on and where one can go from that screen (provided when a screen graph exists; used in the hand-back message). */
        val screenMap: String? = null,
    ) : ReplayOutcome
    /** The next step is an irreversible action (or cannot be judged), so stop before it and hand back to the model for the normal confirmation flow. */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * Follows a route step by step without calling the model. Each step: screen-lock gate → read the screen → lightweight
 * checks (foreground package name, pre-action anchors, unique selector match) → execute through [ActionExecutor] (stop
 * as soon as the guard gives any warning; stop on anything irreversible or unclear — replay never assumes the user has
 * already agreed) → verify the change after the action.
 * Any mismatch stops the replay and hands back to the model. Typed text comes only from the fixed text in the route and
 * the slot values in the current utterance.
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
    /** This app's screen graph (when present, verify against the graph; when absent, fall back to the current state). */
    private val graph: ScreenGraph? = null,
) {
    private var seq = 0

    suspend fun run(choice: RouteChoice): ReplayOutcome {
        val steps = choice.route.steps
        val total = steps.size
        fun event(i: Int, matched: Boolean, detail: String) =
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, Redactor.text(detail)))

        for ((i, step) in steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null, screenMap: String? = null): ReplayOutcome {
                event(i, false, why)
                return ReplayOutcome.Diverged(i, describe(step, choice.slots) + " ($why)", seen, executed, error, screenMap)
            }
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // if the screen is locked or has been taken over, wait first; read the screen after the wait, and base both
            // the checks and the execution on the screen as it is after waiting (the execution entry point does not
            // wait a second time)
            try { gate() } catch (_: com.wangzan101.mika.core.safety.gate.ScreenRecoveryRequired) {
                return diverged("screen changed during lock; read the current state before continuing", "")
            } catch (e: TargetAppRestoreFailed) {
                return diverged("could not restore the target app", e.message.orEmpty(), error = TargetAppRestoreFailed.CODE)
            }
            val snap = read() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN)
            val seen = summary(snap)
            step.before.packageName?.let { if (snap.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap.elements.map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (step.before.texts.count { it in names } < need) return diverged("the page before the action does not match", seen)

            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val target = step.target?.let { sel ->
                find(snap, sel, choice.slots) ?: return diverged("the target element is not unique or not found", seen)
            }
            if (verb == Verb.Tap && target == null) return diverged("the tap has no target to check", seen)
            // open a link only if it is still present in the reference material right now: once the material has
            // changed, links recorded in the route are no longer allowed
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val call = target?.let { JsonObject(args + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))) } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on Claire itself", seen)
                    // #217: when a replay from a non-UI / unattended source reaches a payment step, the approval policy
                    // rejects it automatically — approval will never arrive in this run, so this is not "waiting for
                    // confirmation"; hand back to the model, have it explain to the user, and wrap up as incomplete.
                    "approval_unavailable" -> return diverged(
                        "this run cannot get approval for a payment step", seen,
                        error = "approval_unavailable",
                    )
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // read only one screen after the action: it serves both the route anchors and the check for whether the
            // expected text appeared
            val fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            if (!landed) return diverged("no expected change after the action", now, executed = true)
            // screen-graph check: even with changed=true it may have landed on the wrong screen, so identify the
            // landing screen and compare it with the expected one; if it is wrong, read the screen once more (during a
            // transition a read may not have caught up with navigation) and hand back only if it is still wrong
            val g = graph
            if (g != null && step.after.texts.isNotEmpty()) {
                val expected = ScreenRecognizer.identify(step.after.texts.toSet(), g.nodes())
                if (expected != null) {
                    var landedId = fresh?.let { ScreenRecognizer.identify(RouteAnchors.texts(it).toSet(), g.nodes())?.id }
                    if (landedId != expected.id) {
                        val retry = tools.interfaces.peek()
                        landedId = retry?.let { ScreenRecognizer.identify(RouteAnchors.texts(it).toSet(), g.nodes())?.id }
                        if (landedId != expected.id) {
                            val pos = ScreenMapRender.renderPosition(g, landedId)
                            return diverged("landed on a different screen", pos.ifEmpty { now }, executed = true, screenMap = ScreenMapRender.renderMap(g, landedId))
                        }
                    }
                }
            }
            event(i, true, describe(step, choice.slots))
        }
        return ReplayOutcome.Completed(total)
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // read the screen the route is currently on (the one from the most recent action step)
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Drops the ID; every `{n}` in a string argument is replaced with the slot value. Null when a slot index is out of range or a non-string argument contains a slot. */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * Finds the single target in the current screen by its selector. An empty name is not compared against names; `{n}`
     * is first replaced with the slot value.
     * Unique when recorded but more than one now: the page is no longer what it was then (e.g. an entry with the same
     * name was added to the list), so none is accepted.
     * Already non-unique when recorded: take the one with the same index, and accept it only if it is within 15% of the
     * screen width of the position where it was recorded.
     */
    private fun find(s: ScreenSnapshot, sel: Selector, slots: List<String>): UiElement? {
        val name = fill(sel.name, slots) ?: return null
        val same = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (same.size == 1) return same.single()
        if (same.isEmpty() || sel.uniqueAtRecord || sel.bounds.size != 4) return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR
        val pick = same.getOrNull(sel.ordinal)?.takeIf(::near) ?: return null
        return pick.takeIf { same.count(::near) == 1 }
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String =
        "${s.packageName ?: "unknown app"}: " + s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* a recording failure does not affect the replay */ }
    }

    companion object {
        /** These errors are rejected before the action runs, so the screen was not touched. */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** 15% of screen width (0–999 coordinates). */
        private const val NEAR = 150.0
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")

        /** Replaces `{n}` with the n-th slot value; null when that slot index does not exist. */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** A description of one step, for the model. */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}
