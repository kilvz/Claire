package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.screen.observation.Place
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import com.wangzan101.mika.core.agent.AgentLoop.Companion.VERIFYING_NOTE
import com.wangzan101.mika.core.agent.AgentLoop.Companion.UNCONFIRMED_NOTE

// Agent loop: finishing, interruption, redirection, and the closeout turn
/**
 * A reply without tool calls has already been added to the history and displayed, and now an interjection arrives: continue on top of the interjection (return true).
 * The reply on screen must be kept: the live text in the UI is cleared as soon as the next step starts, so `reply_finished_before_steer` is emitted to attach it above the interjection.
 * With no interjection, return false and change nothing.
 */
internal suspend fun AgentLoop.keepFinished(history: MutableList<Message>, inbox: RunInbox?): Boolean {
    val steers = inbox?.takeSteers().orEmpty()
    if (steers.isEmpty()) return false
    val current = _state.value as? TaskState.Running
    val shown = current?.preview?.trim()
    if (current?.preview != null) _state.value = current.copy(preview = null)
    // Do not truncate: this is a complete answer, and the UI must show the full text as it would without an interjection (the length is already bounded by the model output limit)
    if (!shown.isNullOrEmpty()) emit(RunEvent.State(now(), "reply_finished_before_steer", Redactor.text(shown)))
    appendSteers(history, steers)
    return true
}

/** An interjection cancels only the model request being awaited (this step is not written into the history; the next step re-asks with the interjection); once a tool call enters the execution entry point, that entry point completes it itself. */
internal suspend fun AgentLoop.routedOrRedirect(
    inbox: RunInbox?, history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
    onText: ((String) -> Unit)?,
): Pair<ModelReply, ChatModel>? {
    if (inbox == null) return caller.routed(history, specs, track, step, restarted, onText)
    return coroutineScope {
        val pending = async { caller.routed(history, specs, track, step, restarted, onText) }
        select<Pair<ModelReply, ChatModel>?> {
            pending.onAwait { it }
            inbox.redirects.onReceive {
                pending.cancelAndJoin()
                null
            }
        }
    }
}

/**
 * An interjection cancelled the request for step [step]: the text the user has already seen (the preview in the UI) is recorded in the history as a text-only reply,
 * followed by the interjection, so what the model sees next is the same as what the user saw. Nothing is recorded when no text was seen or there is no interjection.
 * Reasoning content and half-finished tool calls are not in the preview to begin with. The text on screen is cleared here and instead attached above the interjection by the `reply_interrupted` event.
 */
internal suspend fun AgentLoop.keepInterrupted(history: MutableList<Message>, inbox: RunInbox?, step: Int) {
    val steers = inbox?.takeSteers().orEmpty()
    interruptedStep = step
    val current = _state.value as? TaskState.Running
    val partial = current?.takeIf { it.step == step }?.preview?.trim()
    if (current?.preview != null) _state.value = current.copy(preview = null)
    if (!partial.isNullOrEmpty() && steers.isNotEmpty()) {
        history += Message(Role.Assistant, listOf(Part.Text(partial)), Author.Mika)
        emit(RunEvent.State(now(), "reply_interrupted", RunEvent.clip(Redactor.text(partial))))
    }
    appendSteers(history, steers)
}

/**
 * The operation the user approved but which has not been used up yet, or a follow-up question that was answered ([answered] is that question; null once something else has been done since): the loose end still owed before the answer is handed over;
 * null when there is neither.
 */
internal fun AgentLoop.owedFollowUp(answered: String?): String? {
    confirmations?.granted?.let { r ->
        val what = listOfNotNull(r.action.name, r.target.takeIf(String::isNotBlank), r.content.takeIf(String::isNotBlank)?.let { "「$it」" }).joinToString(" ")
        return Feedback.format("followup_approved", "action" to what)
    }
    return answered?.let { Feedback.format("followup_answered", "prompt" to it.take(200)) }
}

/**
 * The reply at step [step] made no tool calls, so the text [answer] is the final answer: review it by risk ([AnswerReview]), with sources found by code.
 * If the review does not match: when a retry is possible ([history] is non-null and this task has not retried yet), remind the model once and return null to keep going;
 * otherwise hand it over as is, marking the mismatched points as unconfirmed. [facts] is the task situation the review looks at (see [ReviewFacts]).
 */
internal suspend fun AgentLoop.conclude(task: String, answer: String, step: Int, facts: ReviewFacts, history: MutableList<Message>?): TaskState? {
    if (!reviewFinalAnswer) {
        val sources = review.sourcesOnly(task, answer, media)
        if (sources.isNotEmpty()) emit(RunEvent.Sources(now(), sources))
        return TaskState.Succeeded(answer, verified = false, sources = sources, reviewed = false)
    }
    val r = review.review(task, answer, step, facts, earlierGoals, media) {
        _state.value = TaskState.Running(step, VERIFYING_NOTE, reason = VERIFYING_NOTE,
            preview = (_state.value as? TaskState.Running)?.preview)
    }
    val missing = r.unconfirmed
    if (missing.isNotEmpty() && history != null && !gapsNudged) {
        gapsNudged = true
        TurnPolicy.unconfirmed(r.gaps, r.objections).let { history += ctx.notice(it.source, it.text) }
        return null
    }
    if (missing.isNotEmpty()) emit(RunEvent.Unconfirmed(now(), step, missing.map(::detail)))
    if (r.sources.isNotEmpty()) emit(RunEvent.Sources(now(), r.sources))
    return if (missing.isEmpty()) TaskState.Succeeded(answer, r.verified, r.note, r.sources, reviewed = r.reviewed)
    else TaskState.Succeeded(answer, verified = false, note = UNCONFIRMED_NOTE + missing.joinToString("、"), sources = r.sources, unconfirmed = missing)
}

/**
 * One last turn after the step budget is used up, with only end_not_done left among the tools: if the task is done, answer in text (still reviewed, but no more retries; mismatches are marked unconfirmed);
 * if not, state truthfully what was checked. If this turn produces nothing (or is truncated), the step budget still counts as used up.
 */
internal suspend fun AgentLoop.finalTurn(
    task: String, history: MutableList<Message>, specs: List<ToolSpec>, step: Int, labels: Map<String, String>, place: Place?, recorder: ActionRecorder,
    facts: ReviewFacts, inbox: RunInbox?, progress: TaskProgressTracker, closeout: Closeout = Closeout.StepLimit,
): TaskState {
    fun acceptProgress(parsed: CompletionPlanBlock.Extracted) = progress.acceptParsed(parsed, step)
    TurnPolicy.closeout(closeout, progress.state(step)).let { history += ctx.notice(it.source, it.text) }
    repeat(3) { attempt ->
        appendSteers(history, inbox)
        appendRuntimeState(history, place, labels, step, progress.pending(step))
        _state.value = TaskState.Running(step, "", progress = progress.state(step))
        val asked = now()
        val (reply, used) = try {
            caller.askRouted(history, TurnPolicy.finalTools(specs, tools::verb), step, onText = previewFor(step))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            return StopRules.modelFailed(e)
        } catch (e: ModelError.DailyBudgetExceeded) {
            return StopRules.modelFailed(e)
        } catch (e: Exception) {
            return StopRules.closeoutFailed(closeout, maxSteps)
        }
        val rawText = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
        val parsed = CompletionPlanBlock.extract(rawText)
        parsed.stage?.let { stages?.reported(it) }
        acceptProgress(parsed)
        val text = parsed.text
        history += Message(Role.Assistant, cleanReplyParts(reply.parts, text), Author.Mika)
            .also { caller.noteReply(it, used, reply.usage) }
        val calls = ReplyCalls(reply.parts, ::acceptProgress)
        val call = calls.actions.firstOrNull { tools.verb(it.name) in TurnPolicy.FINAL_VERBS }
        val retryWaitMs = caller.lastRetryWaitMs
        val repliedAtMs = now()
        val latencyMs = repliedAtMs - asked - retryWaitMs
        emit(RunEvent.ModelReply(
            now(), step, RunEvent.clip(Redactor.text(text)), call?.name, call?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = false) },
            reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.cachedInputTokens, latencyMs, retryWaitMs = retryWaitMs,
            model = used.name, role = if (caller.recordRoles) caller.roleOf(used) else null,
            group = caller.groupOf(used), repliedAtMs = repliedAtMs,
        ))
        // The closeout model can also receive an interjection while generating. Re-answer at most twice; the rest goes to the next task.
        if (attempt < 2 && inbox?.hasSteers() == true) {
            if (!calls.isEmpty) history += calls.results()
            if (keepFinished(history, inbox)) return@repeat
        }
        if (call == null) {
            if (calls.action == null && !calls.isEmpty && text.isEmpty()) {
                history += calls.results()
                return@repeat
            }
            if (calls.action != null || text.isEmpty() || reply.stopReason == StopReason.MaxTokens) return StopRules.closeoutFailed(closeout, maxSteps)
            return conclude(task, text, step, facts, history = null)!!
        }
        val started = now()
        // Only giving up is left; the phone is not changed
        val exec = tools.executeRead(call)
        recorder.result(step, call.name, tools.verb(call.name), exec.content, exec.record, null, null, now() - started, "model", call.id)
        return StopRules.afterFinalTurn(exec.outcome, maxSteps)
    }
    return StopRules.closeoutFailed(closeout, maxSteps)
}
