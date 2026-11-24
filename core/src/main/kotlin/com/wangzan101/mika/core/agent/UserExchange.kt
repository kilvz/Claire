package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.Refusals
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.gate.DeclareResult
import com.wangzan101.mika.core.general.RequestConfirmation
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Waiting on the user: answering a follow-up question, handing back a takeover, answering a confirmation. While waiting, the waiting state is shown externally; afterwards it switches back to [running] (supplied by the caller).
 * The user's decision (or a timeout or skip) becomes this call's tool result. The UI feeds the user's answer in through methods such as [answerInput].
 */
internal class UserExchange(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    /** Time limit for waiting on the user's confirmation answer; on expiry it counts as declined; null means wait indefinitely. */
    private val confirmTimeoutMs: Long?,
    private val inputTimeoutMs: Long,
    /** What the user said in this run (used to validate quotes for memory_save); null when not collected. */
    private val utterances: () -> UserUtterances?,
    private val setState: (TaskState) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    private var threadId = "local"
    private var provenance = RunProvenance()
    private var interaction: Interaction = LocalInteraction
    @Volatile var activeAsk: Delivery.Ask? = null
        private set
    @Volatile private var takeoverReply: CompletableDeferred<HandBack>? = null
    @Volatile private var inputReply: CompletableDeferred<String>? = null
    @Volatile private var confirmationReply: CompletableDeferred<ConfirmChoice>? = null
    /** Converts an answer into the form shown to the model (with a user screen attached it becomes a description, see [UserScreenTurns.asText]); identity by default. */
    var answerText: suspend (String) -> String = { it }

    fun begin(thread: String, run: RunProvenance, channel: Interaction = LocalInteraction) {
        threadId = thread
        provenance = run
        interaction = channel
        activeAsk = null
    }

    private fun ask(kind: Delivery.Ask.Kind, text: String): Delivery.Ask =
        Delivery.Ask(UUID.randomUUID().toString(), threadId, UUID.randomUUID().toString(), kind, text)

    private suspend fun announce(ask: Delivery.Ask): DeliveryState {
        activeAsk = ask
        return interaction.publish(ask)
    }

    private fun resolve(ask: Delivery.Ask) {
        if (activeAsk?.askId == ask.askId) activeAsk = null
        interaction.resolved(ask.askId)
    }

    private fun matches(id: String?): Boolean = id == null || activeAsk?.askId == id

    fun resumeAfterTakeover(result: HandBack, askId: String? = null): Boolean =
        if (matches(askId)) takeoverReply?.complete(result) ?: false else false

    /** Returns false when there is no question being waited on (already timed out or already answered). */
    fun answerInput(text: String, askId: String? = null): Boolean =
        if (matches(askId)) inputReply?.complete(text) ?: false else false

    /** A new instruction from the channel supersedes the old question: end this wait so the old task does not hold the conversation indefinitely. */
    fun cancelInput(askId: String): Boolean {
        val ask = activeAsk
        if (ask?.askId != askId || ask.kind != Delivery.Ask.Kind.Input) return false
        val pending = inputReply ?: return false
        pending.cancel()
        return true
    }

    fun answerConfirmation(choice: ConfirmChoice, askId: String? = null): Boolean =
        if (matches(askId)) confirmationReply?.complete(choice) ?: false else false

    /** Task finished: no longer wait for any answer. */
    fun clear() {
        takeoverReply = null
        inputReply = null
        confirmationReply = null
        activeAsk = null
    }

    /** Waits for the user to answer the follow-up question, at most [inputTimeoutMs]; returns null on timeout. The user's answer may be something like a verification code, so it is not recorded. */
    suspend fun awaitAnswer(prompt: String, options: List<String>?): String? {
        if (provenance.attendance == Attendance.Unattended) return null
        val pending = CompletableDeferred<String>().also { inputReply = it }
        val ask = ask(Delivery.Ask.Kind.Input, prompt)
        activeAsk = ask
        setState(TaskState.WaitingInput(prompt, options))
        emit(RunEvent.State(now(), "waiting_input", detail(prompt)))
        return try {
            if (announce(ask) == DeliveryState.Failed) null
            else withTimeoutOrNull(inputTimeoutMs) { pending.await() }
        } finally {
            inputReply = null
            resolve(ask)
        }.also { if (it == null) emit(RunEvent.State(now(), "no_answer", detail(prompt))) else utterances()?.add(UserWords.said(com.wangzan101.mika.core.screen.user.UserScreenMessage.parse(it).words, tools)) }?.let { answerText(it) }
    }

    suspend fun input(o: Outcome.Input, running: TaskState): ToolExecution {
        val answer = awaitAnswer(o.prompt, o.options)
        // Facts the user supplies during the task are also evidence for verification
        answer?.let { tools.noteUserAnswer(o.prompt, it) }
        setState(running)
        return ToolExecution(buildJsonObject {
            if (answer != null) { put("ok", true); put("answer", answer) }
            else { put("ok", false); put("error", "no_answer"); put("message", INPUT_TIMEOUT_MESSAGE); put("recoverable", true); put("counts_as_failure", false) }
        })
    }

    /** Waits for the user to hand back; returns null when the task is aborted. The wait never times out, so the task context is not lost while the user still needs to act. */
    suspend fun takeover(o: Outcome.Takeover, running: TaskState): ToolExecution? {
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) return null
        val pending = CompletableDeferred<HandBack>().also { takeoverReply = it }
        val ask = ask(Delivery.Ask.Kind.Takeover, o.message)
        activeAsk = ask
        setState(TaskState.WaitingTakeover(o.reason, o.message, o.item, o.amount, o.address))
        emit(RunEvent.State(now(), "waiting_takeover", detail(o.message)))
        val since = now()
        val back = try {
            if (announce(ask) == DeliveryState.Failed) return null
            pending.await()
        } finally { takeoverReply = null; resolve(ask) } ?: return null
        back.note?.let { utterances()?.add(it) }
        tools.noteTakeover(o.reason, back)
        val outcome = if (back.userFinished && back.outcome == HandBackOutcome.complete) "user_finished" else back.outcome.name
        emit(RunEvent.Takeover(now(), o.reason, outcome, back.durationMs ?: (now() - since), back.inputCount))
        if (back.outcome == HandBackOutcome.aborted) return null
        setState(running)
        return ToolExecution(buildJsonObject {
            put("outcome", back.outcome.name)
            if (back.outcome == HandBackOutcome.complete) {
                put("ok", true)
                put("durationMs", back.durationMs ?: (now() - since))
                back.inputCount?.let { put("inputCount", it) }
                back.stoppedAt?.let { put("stoppedAt", it) }
                back.url?.let { put("url", it) }
                put("note", HANDBACK_NOTE)
                if (back.userFinished) { put("userFinished", true); put("instruction", USER_FINISHED_PROMPT) }
                back.note?.takeIf { it.isNotBlank() }?.let { put("userNote", it) }
            } else {
                put("ok", false)
                put("reason", if (back.outcome == HandBackOutcome.skipped) "takeover_skipped" else "takeover_timeout")
                put("counts_as_failure", false)
                put("note", NO_HANDBACK_NOTE)
            }
        })
    }

    /** If the declaration matches the foreground app, wait for the user's answer (on timeout it counts as declined; cancellable); the tool result is the user's decision. */
    suspend fun confirm(o: Outcome.Confirm, step: Int, running: TaskState): ToolExecution {
        val g = confirmations ?: return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "unknown_tool"); put("tool", RequestConfirmation.name)
            put("message", Feedback["confirmation_unavailable"]); put("recoverable", true)
        })
        val r = o.request
        fun event(decision: String?) = RunEvent.Confirmation(now(), r.action.name, detail(r.target), detail(r.content), r.amount?.let(::detail), decision)
        fun error(code: String, message: String, recoverable: Boolean) = ToolExecution(buildJsonObject {
            put("ok", false); put("error", code); put("message", message)
            put("recoverable", recoverable); put("counts_as_failure", false)
        })
        when (g.declare(r, o.foregroundApp)) {
            DeclareResult.Mismatch -> {
                emit(event("mismatch"))
                return ToolExecution(buildJsonObject {
                    put("ok", false); put("error", "confirmation_mismatch")
                    put("message", Feedback.format("confirm_app_mismatch", "app" to r.app, "foreground" to (o.foregroundApp ?: "unknown")))
                    put("recoverable", true)
                })
            }
            DeclareResult.Declined -> {
                emit(event("declined_before"))
                return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
            }
            DeclareResult.Unsupported -> return error("payment_requires_takeover", Refusals.PAYMENT_TAKEOVER, recoverable = true)
            DeclareResult.Pending -> Unit
        }
        if (provenance.attendance == Attendance.Unattended || provenance.origin == RunOrigin.Child) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_unattended"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        // Payments are approved only on the phone: tasks started from a channel, a schedule, or MCP are not asked
        if (provenance.origin != RunOrigin.Ui) {
            g.decide(ConfirmChoice.Deny, step)
            emit(event("declined_remote"))
            return error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
        }
        val pending = CompletableDeferred<ConfirmChoice>().also { confirmationReply = it }
        val ask = ask(Delivery.Ask.Kind.Confirm, "${r.action.name}: ${r.target} ${r.content}")
        activeAsk = ask
        setState(TaskState.WaitingConfirmation(r))
        emit(event(null))
        // With no answer (user not near the phone) it counts as declined; never auto-approve
        val timeout = if (provenance.attendance == Attendance.Reachable) confirmTimeoutMs ?: 15 * 60_000L else confirmTimeoutMs
        val answer = try {
            if (announce(ask) == DeliveryState.Failed) null
            else if (timeout == null) pending.await() else withTimeoutOrNull(timeout) { pending.await() }
        } finally { confirmationReply = null; resolve(ask) }
        val choice = answer ?: ConfirmChoice.Deny
        g.decide(choice, step)
        setState(running)
        emit(event(when {
            answer == null -> "timed_out"
            choice.approved -> "approved"
            else -> "declined"
        }))
        return if (choice.approved) ToolExecution(buildJsonObject { put("ok", true); put("approved", true); put("message", CONFIRMATION_APPROVED) })
        else error("confirmation_declined", CONFIRMATION_DECLINED, recoverable = false)
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    companion object {
        val INPUT_TIMEOUT_MESSAGE: String get() = Feedback["no_answer_message"]
        val HANDBACK_NOTE: String get() = Feedback["handback_note"]
        val NO_HANDBACK_NOTE: String get() = Feedback["no_handback_note"]
        val USER_FINISHED_PROMPT: String get() = Feedback["user_finished_prompt"]
        val CONFIRMATION_DECLINED: String get() = Feedback["confirmation_declined"]
        val CONFIRMATION_APPROVED: String get() = Feedback["confirmation_approved"]
    }
}
