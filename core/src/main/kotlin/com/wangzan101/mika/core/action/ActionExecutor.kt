package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.safety.gate.TargetAppRestoreFailed
import com.wangzan101.mika.core.safety.guard.DIAGNOSTIC_VERBS
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.safety.guard.PAGE_READ_VERBS
import com.wangzan101.mika.core.safety.guard.GuardScope
import com.wangzan101.mika.core.safety.guard.elementsHash
import com.wangzan101.mika.core.safety.guard.resultFingerprint
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.effectiveWrite
import com.wangzan101.mika.core.screen.uistate.Described
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.interfaces
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.safety.guard.OffScreenGuard
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.capability.Paced

/**
 * The single execution entry point for every action that changes the outside world: [ToolDispatcher.execute] requires an [ActionPass], and only this class holds one.
 * Every origin follows the same order: operation guards → locked-screen / taken-away gate (write operations) → irreversible assessment and consent check (against the screen as it is now after passing the gate) →
 * execution → guard review (failure circuit breaker, screen change, re-read) → run record. Intentional differences between origins live only in [OriginPolicy].
 */
class ActionExecutor(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    confirmations: ConfirmationGate?,
    private val gate: suspend () -> Unit,
    val recorder: ActionRecorder,
    /** Guard for tools that do not touch the screen; null when there is none. */
    private val offScreen: OffScreenGuard? = null,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    provenance: RunProvenance = RunProvenance(),
    selfPackage: String? = null,
    autonomous: Boolean = false,
    /** Interval between two consecutive outward actions (send, submit) on the same site or app (see [OutwardPacing]); no wait by default. */
    private val pacing: OutwardPacing = OutwardPacing.Off,
    private val onScreenAction: ((Verb?, JsonObject, ToolExecution) -> Unit)? = null,
) {
    private val decision = ConfirmDecision(tools, confirmations, approvals, recorder = recorder, provenance = provenance, selfPackage = selfPackage, autonomous = autonomous)
    private val pass = ActionPass()

    /** A confirmation gate is configured (so a payment can be made with user consent). */
    val confirms: Boolean = confirmations != null

    /** Verdict in which the guard for off-screen tools halted; null when it did not halt. */
    val offScreenStop: GuardVerdict? get() = offScreen?.stop

    suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp = FollowUp.NONE): ActionOutcome =
        run(call, origin, followUp, reads = null)

    /** [reads]: the scope in which reads inside a single page-script call are counted separately (when the origin requires isolation); actions there still count against the task. */
    private suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val control = tools.manualControl ?: return runAutomatic(call, origin, followUp, reads)
        return try { control.plan(tools) { runAutomatic(call, origin, followUp, reads) } }
        catch (e: com.wangzan101.mika.core.run.ManualControl.Interrupted) {
            val content = buildJsonObject {
                put("ok", false); put("error", com.wangzan101.mika.core.run.ManualControl.CHANGED)
                put("message", e.message); put("executed", false)
            }
            if (origin.policy.recordsRefusal) followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, content, null, null, 0, origin.policy.source, call.id)
            ActionOutcome.Refused(com.wangzan101.mika.core.run.ManualControl.CHANGED, content, emptyList())
        }
    }

    private suspend fun runAutomatic(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val args = parseArgs(call.argsJson)
        // Tools that do not touch the screen (sandbox and the like): they do not enter the screen guard, their own guards handle them
        val offScreen = tools.offScreen(call.name)
        val verb = tools.verb(call.name)
        // Using an id from another screen: do not judge or execute, do not record which screen is being operated; hand it straight to the dispatcher to say which tool should be used
        if (tools.misaimed(call.name, args) != null) {
            return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads))
        }
        // Later gates and judgments all use the screen this call aimed at (until this call ends)
        tools.aim(call.name, args)
        try {
            return aimed(call, origin, followUp, reads, args, offScreen, verb)
        } catch (e: com.wangzan101.mika.core.device.access.AppAccessDenied) {
            val content = buildJsonObject {
                put("ok", false); put("error", e.code); put("message", e.message); put("retryable", false)
                e.packageName?.let { put("package", it) }
            }
            // Single step: given to the model as the result of this tool call so it can change course or explain to the user; batches and scripts handle it themselves via Refused
            if (origin.policy.recordsRefusal) followUp.refused(content)
            recorder.result(origin.step, call.name, verb, content, content, null, null, 0, origin.policy.source, call.id)
            return ActionOutcome.Refused(e.code, content, emptyList())
        } finally {
            // Success or failure is recorded by the dispatcher; this only tears down this layer
            tools.release(false)
        }
    }

    private suspend fun aimed(
        given: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?, givenArgs: JsonObject, offScreen: Boolean, verb: Verb?,
    ): ActionOutcome {
        // A target given by description is first translated into the id on the current screen: the later judgment, approval, execution are exactly the same as with an id; if it is not found or not unique, nothing is done
        var call = given
        var args = givenArgs
        var note: JsonObject? = null
        tools.interfaces.checkAppAccess(tools.declared(call.name))
        when (val d = tools.interfaces.described(tools.declared(call.name), args)) {
            is Described.Failed -> return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads, preset = d.result))
            is Described.Resolved -> { args = d.args; call = call.copy(argsJson = d.args.toString()); note = d.note }
            null -> Unit
        }
        val policy = origin.policy
        val readScope = reads?.takeIf { verb in PAGE_READ_VERBS }
        val before = when {
            offScreen -> emptyList()
            readScope != null -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS, readScope)
            else -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS)
        }
        if (guards.stop != null) return refuse("guard_stopped", origin, before)
        if (policy.stopOnGuardWarning && before.isNotEmpty()) return refuse("guard_warning", origin, before)
        val writing = tools.writesScreen(call.name)
        if (writing && !policy.gatedByCaller) try {
            gate()
        } catch (_: com.wangzan101.mika.core.safety.gate.ScreenRecoveryRequired) {
            // The screen was locked while waiting at the gate: this step was aimed at the screen from before the lock, so it is not executed; have the model re-read the current screen and decide
            val notice = ToolExecution(buildJsonObject {
                put("ok", false); put("error", "screen_changed_during_lock"); put("executed", false)
                put("message", "The phone locked and was unlocked while the task was running. This pending action was not executed. Read the current screen and verify whether the previous action already completed before taking another action.")
            })
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = notice))
        } catch (e: TargetAppRestoreFailed) {
            val failure = ToolExecution(buildJsonObject {
                put("ok", false); put("error", TargetAppRestoreFailed.CODE); put("message", e.message)
                put("package", e.packageName); put("executed", false); put("recoverable", true); put("counts_as_failure", false)
            })
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = failure))
        }
        // Without an available consent nothing is executed, and it does not count toward the failure circuit breaker
        var d = decision.judge(call.name, args, origin)
        // A step that is off-screen and that the tool declares must be asked about (a root command that changes things): if the caller can reach
        // a human, ask on the spot, and after approval judge once more with that approval; if no human can be reached (this run cannot ask, or the
        // caller is not a model single step), do not execute and record it as blocked
        val ask = if (d is Decision.Required || d is Decision.Denied) tools.approvalRisk(call.name, args)?.ask else null
        if (ask != null) {
            val hit = Irreversible(ask.action, ask.content)
            val answer = (if (d is Decision.Required) followUp.approve(ask) else null)
                ?: return declined(call, args, origin, before, hit, ToolExecution(Refusals.unavailable()), followUp, "approval_unavailable")
            if (answer.content["ok"] != JsonPrimitive(true)) return declined(call, args, origin, before, hit, answer, followUp, "confirmation_declined")
            d = decision.judge(call.name, args, origin)
        }
        when (d) {
            is Decision.Required -> return blocked(call, args, origin, before, d.hit, null, followUp)
            is Decision.Mismatch -> return blocked(call, args, origin, before, d.hit, d.reason, followUp)
            is Decision.Denied -> return approvalUnavailable(call, args, origin, before, d.hit, followUp)
            Decision.Unresolved -> return refuse("target_unresolved", origin, before)
            Decision.OwnApp -> return ownApp(call, args, origin, before, followUp)
            else -> Unit
        }
        // Outward action: spacing from the previous one on the same site or app (the task can still be cancelled while waiting; the action has not started)
        val site = decision.outwardSite
        val paced = site?.let { pacing.waitBefore(it, recorder.now()) }?.takeIf { it > 0 } ?: 0L
        // The user interjected while waiting out the interval: skip this time and let the model read that message first. The interval counts from the previous completion, and the remainder is still applied next time
        if (paced > 0 && waitOrSteer { delay(paced) } == null) {
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = failCode(SKIPPED_FOR_NEW_MESSAGE)))
        }
        // Screen-changing action: pause first at the pace of this screen's capability (see AppDwell in the app); single steps and every step in a
        // batch pause here, while a composite action (script) does not pause itself — each step inside pauses here on its own. At the screen limit,
        // or when the user interjects during the pause, nothing is done
        val screenPaced = writing && !offScreen && !tools.composite(call.name)
        val dwelt = if (screenPaced) tools.beforeWrite(call.name, outward = site != null) else Paced.NONE
        dwelt.stop?.let { stop ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = stop, pacedMs = paced + dwelt.waitMs))
        }
        if (writing) {
            guards.noteAction()
            decision.noteWrite(call.name)
        }
        val step = Step(call, args, origin, before, writing, offScreen, verb, (d as? Decision.Traced)?.detail, followUp, reads, note = note,
            pacedMs = paced + dwelt.waitMs, idleScrolls = dwelt.idleScrolls, outward = site != null)
        // Once an action starts it is completed and recorded: neither a script timeout nor a task cancellation may cut it off midway or lose the record
        // An outward action tells this screen's capability (the web page-count limit only governs browsing; see PageDwell)
        site?.let(tools::outwardStart)
        var done = false
        try {
            val outcome = if (policy.actionCapMs == null) ran(step) else {
                // Acquire the execution right in the cancellable region first, then protect the script action and its record; waiting for a manual hand-back must not happen inside NonCancellable.
                suspend fun finishAction() = withContext(NonCancellable) { ran(step) }
                tools.withScreenControl(call.name, successful = {
                    it is ActionOutcome.Ran && it.exec.content["ok"] != JsonPrimitive(false)
                }) { finishAction() }
            }
            if (screenPaced && outcome is ActionOutcome.Ran) tools.afterWrite(call.name, outcome.exec.content)
            if (site != null && outcome is ActionOutcome.Ran && outcome.exec.content["ok"] != JsonPrimitive(false)) {
                done = true
                pacing.noteDone(site, recorder.now())
            }
            return outcome
        } finally {
            site?.let { tools.outwardEnd(it, done) }
        }
    }

    private class Step(
        val call: Part.ToolCall, val args: JsonObject, val origin: Origin, val before: List<GuardVerdict>, val writing: Boolean,
        val offScreen: Boolean, val verb: Verb?, val traced: String?, val followUp: FollowUp, val reads: GuardScope?,
        /** A result obtained without handing off to the tool (target not found by description). */
        val preset: ToolExecution? = null,
        /** Note attached to the result (which element the description resolved to). */
        val note: JsonObject? = null,
        /** How long execution waited beforehand for the outward-action interval and this screen's rhythm; counted into this call's wait time. */
        val pacedMs: Long = 0,
        /** How many gentle scrolls happened during the pre-execution dwell; goes into the run record only. */
        val idleScrolls: Int = 0,
        /** The irreversible assessment identified an outward action (send, submit); recorded in this activity. */
        val outward: Boolean = false,
    )

    private suspend fun ran(s: Step): ActionOutcome {
        val call = s.call
        val policy = s.origin.policy
        val n = s.origin.step
        // Every action inside a page script comes back through here and also completes the whole entry path; reads inside a script are counted only within this one call
        val subVerdicts = mutableListOf<GuardVerdict>()
        val sub = if (tools.composite(call.name)) {
            val script = Origin.Script(n)
            val window = if (script.policy.isolatedReads) GuardScope(canStop = false) else null
            SubActions { c ->
                val o = run(c, script, FollowUp.NONE, window)
                subVerdicts += o.before
                if (o is ActionOutcome.Ran) subVerdicts += o.after
                o.execution()
            }
        } else null
        val started = recorder.now()
        // State-changing action: record the ledger position before execution; whether it was re-read afterwards (including the screen this result carries) is checked before answering
        val changes = tools.declaredRisk(call.name) != Risk.Read && (s.offScreen || ClassifiedRisk.changesState(s.verb))
        val mark = tools.ledgerMark()
        val raw = (s.preset ?: offScreen?.exhausted(call.name)?.let { ToolExecution(it) } ?: execute(s, sub))
            .let { r -> s.note?.let { r.with(content = JsonObject(r.content + it)) } ?: r }
            .let { r -> if (s.pacedMs > 0) r.with(waitMs = r.waitMs + s.pacedMs) else r }
            .let { r -> if (s.idleScrolls > 0) r.with(record = JsonObject(r.record + ("idleScrolls" to JsonPrimitive(s.idleScrolls)))) else r }
        // An action that reported failure and did not say the UI changed changed nothing: no need to re-read for it before answering
        val failed = raw.content["ok"] == JsonPrimitive(false) && raw.changed != true
        if (changes && s.preset == null && !failed) recorder.noteChange(mark)
        val durationMs = recorder.now() - started
        val resolved = s.followUp.resolve(raw) ?: return ActionOutcome.Cancelled(s.before)
        tools.interfaces.checkAppAccess(tools.declared(call.name), after = true)

        val after = subVerdicts.toMutableList()
        after += if (s.offScreen) offScreen?.result(call.name, s.args, resolved.content).orEmpty() else guards.result(call.name, resolved.content)
        // Record the actual effect the action reports first (no effect, moved elsewhere); the following screen is then no longer recorded again by fingerprint
        (resolved.content["effect"] as? JsonObject)?.takeIf { !s.offScreen }?.let { effect ->
            val kind = (effect["kind"] as? JsonPrimitive)?.content ?: return@let
            after += guards.effect(kind, (effect["treeBlind"] as? JsonPrimitive)?.booleanOrNull == true)
        }
        val elements = resolved.screenElements ?: resolved.content["elements"] as? JsonArray
        val beforeFrameProgress = guards.progressVersion
        elementsHash(elements)?.let { after += guards.frame("$n:${call.id}", it) }
        if (guards.progressVersion != beforeFrameProgress) after.removeAll { it.code == "no_progress" }
        if (!s.offScreen) {
            if (s.verb in PAGE_READ_VERBS) {
                // Reading content not seen since the screen last changed (a new screen, a page that switched its origin, a new result from the script) does not count as a re-read
                val fp = elementsHash(elements) ?: resultFingerprint(resolved.content)
                val beforeReadProgress = guards.progressVersion
                after += if (s.reads != null) guards.read(call.name, guards.firstSeen(fp, s.reads), s.reads, fp)
                else guards.read(call.name, guards.firstSeen(fp), fingerprint = fp)
                if (guards.progressVersion != beforeReadProgress) after.removeAll { it.code == "no_progress" }
            } else if (s.writing && resolved.changed != false && resolved.content["ok"] == JsonPrimitive(true)) {
                guards.pageChanged()
            }
        }
        val (exec, content) = s.followUp.present(resolved, s.before + after)

        if (!s.offScreen) onScreenAction?.invoke(s.verb, s.args, exec)

        // Screen: for a model single step the caller saves it in its own order; a substep saves on the spot only if the UI changed; playback saves nothing
        val picture = if (policy.frames == Frames.Caller && s.verb != Verb.ViewPastScreens && !s.offScreen) exec.image ?: exec.frame else null
        val shotId = when (policy.frames) {
            Frames.Caller -> picture?.let { recorder.nextShot() }
            Frames.WhenChanged -> (exec.image ?: exec.frame)?.takeIf { exec.changed != false }?.let(recorder::saveFrame)
            Frames.None -> null
        }
        recorder.result(n, call.name, s.verb, content, exec.record, exec.changed, shotId, durationMs, policy.source, call.id, exec.waitMs)
        if (recorder.savesScreens) exec.playback?.let { capture ->
            val beforeId = capture.before?.let(recorder::saveFrame)
            val afterId = capture.after?.let(recorder::saveFrame)
            recorder.event(RunEvent.TaskPlayback(recorder.now(), n, call.name, call.id, beforeId, afterId,
                capture.mark, exec.content["ok"] == JsonPrimitive(true), policy.source))
        }
        ActivityRecord.of(tools, call.name, s.args, exec, n, policy.source, call.id, recorder.now(), s.outward)?.let(recorder::event)
        if (policy.frames == Frames.WhenChanged && shotId != null) recorder.event(RunEvent.Media(recorder.now(), shotId, n, RunEvent.placeLabel(exec.place)))
        val seen = if (policy.routeTargets) routeTarget(call, s.verb, s.origin, exec) else null
        s.traced?.let { recorder.event(RunEvent.State(recorder.now(), "gate_unresolved", "${call.name} $it")) }
        return ActionOutcome.Ran(exec, s.before, after, content, durationMs, shotId.takeIf { picture != null }, picture, seen)
    }

    private suspend fun execute(s: Step, sub: SubActions?): ToolExecution {
        val call = if (s.offScreen && offScreen != null) offScreen.capTimeout(s.call, s.args) else s.call
        val cap = s.origin.policy.actionCapMs ?: return tools.execute(call, pass, s.origin.step, sub)
        // The action has its own cap
        return withTimeoutOrNull(cap) { tools.execute(call, pass, s.origin.step, sub) } ?: ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_timeout"); put("message", "the action did not finish within ${cap / 1000} s; read the screen before going on")
        })
    }

    /**
     * With replay enabled, record the target element and the before/after anchors that the route needs (read one extra screen after the action and return it).
     * If the target cannot be captured but the action took effect: a route must not miss a step, so mark it incomplete and do not record the whole route.
     */
    private suspend fun routeTarget(call: Part.ToolCall, verb: Verb?, origin: Origin, exec: ToolExecution): ScreenSnapshot? {
        if (!tools.captureTargets || tools.index[call.name]?.routable != true) return null
        // Only record the raw text when the port explicitly says it is not a password field
        val secret = (exec.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
        val args = Redactor.args(verb, call.argsJson, secret)
        val effective = effectiveWrite(verb, exec.content)
        val source = origin.policy.source
        val none = Anchors(null, emptyList())
        val ui = tools.interfaces
        val captured = ui.lastActionTarget()
        if (captured == null) {
            if (effective) recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, null, none, none, true,
                callId = call.id, source = source, incomplete = true))
            return null
        }
        val seen = if (effective) ui.peek() else null
        val after = seen?.let(ui::anchorsAfter) ?: none
        recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, captured.first, captured.second, after, effective,
            callId = call.id, protected = ui.lastActionProtected(), source = source))
        return seen
    }

    /** Acting on Claire itself: not done; a model single step still receives the explanation and it is recorded in the run record. */
    private fun ownApp(call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, followUp: FollowUp): ActionOutcome {
        val content = Refusals.content(Refusals.OWN_APP, origin.policy.refusals, before, null, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(Refusals.OWN_APP, content, before)
    }

    private fun refuse(code: String, origin: Origin, before: List<GuardVerdict>) =
        ActionOutcome.Refused(code, Refusals.content(code, origin.policy.refusals, before, null, null), before)

    private fun approvalUnavailable(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, followUp: FollowUp,
    ): ActionOutcome {
        val code = "approval_unavailable"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }

    /** A step that must ask was not approved (the user declined, or no human could be reached): [answer] is the result, recorded the same way as a block. */
    private fun declined(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, answer: ToolExecution, followUp: FollowUp,
        code: String,
    ): ActionOutcome {
        recorder.confirmation(hit, if (code == "confirmation_declined") "declined" else "denied")
        if (origin.policy.recordsRefusal) {
            followUp.refused(answer.content)
            recorder.result(origin.step, call.name, tools.verb(call.name), answer.content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, answer.content, before, hit)
    }

    private fun blocked(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, reason: String?, followUp: FollowUp,
    ): ActionOutcome {
        recorder.confirmation(hit, if (reason != null) "mismatch" else "required")
        val code = if (reason != null) "confirmation_mismatch" else "confirmation_required"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, reason)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }
}
