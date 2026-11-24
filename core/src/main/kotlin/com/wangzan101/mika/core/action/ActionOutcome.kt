package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The outcome of one action after completing the execution entry point. */
sealed interface ActionOutcome {
    /** Guard verdicts from before execution. */
    val before: List<GuardVerdict>

    /**
     * Not executed. [code]: guard_stopped / guard_warning / confirmation_required / confirmation_mismatch / confirmation_declined /
     * approval_unavailable / target_unresolved / own_app.
     * [content] is the result handed to this call's caller (script substeps and model single steps reply with it; batches and playback explain themselves from [code]).
     */
    data class Refused(
        val code: String, val content: JsonObject, override val before: List<GuardVerdict>, val hit: Irreversible? = null,
    ) : ActionOutcome

    /**
     * Executed. [exec] is the final result (for a model single step it may already be replaced by the result after waiting for the user, or sent back by the review); [after] holds the post-execution guard verdicts
     * (substep verdicts from page scripts come first); [content] is what is given to the model and also recorded in the run record.
     * [shotId], [picture]: the frame left for the caller to save when [Frames.Caller]; [seen] is the screen read after the action while recording route anchors (null when nothing was read).
     */
    data class Ran(
        val exec: ToolExecution, override val before: List<GuardVerdict>, val after: List<GuardVerdict>, val content: JsonObject,
        val durationMs: Long, val shotId: Int? = null, val picture: ByteArray? = null, val seen: ScreenSnapshot? = null,
    ) : ActionOutcome {
        val verdicts: List<GuardVerdict> get() = before + after
    }

    /** The task was cancelled while waiting for the user. */
    data class Cancelled(override val before: List<GuardVerdict>) : ActionOutcome
}

/** This call's result for the caller: the execution result when executed, the refusal explanation when not. */
fun ActionOutcome.execution(): ToolExecution = when (this) {
    is ActionOutcome.Ran -> exec
    is ActionOutcome.Refused -> ToolExecution(content)
    is ActionOutcome.Cancelled -> ToolExecution(buildJsonObject { put("ok", false); put("error", "cancelled"); put("executed", false) })
}

/** The explanation handed to the caller when blocked, worded according to the origin. */
internal object Refusals {
    const val SINGLE_USE = "One approval lets through only the next matching action, and that action uses it up whether it succeeds or not."
    const val OWN_APP = "own_app"
    const val PAYMENT_TAKEOVER = "The user completes payment with their own hands; do not use request_confirmation: after opening the payment page, call hand_to_user(reason=payment), " +
        "with item as the product (variant, quantity), amount as the amount due shown on screen, and address as the delivery address."

    fun content(code: String, style: RefusalStyle, before: List<GuardVerdict>, hit: Irreversible?, reason: String?): JsonObject = when (style) {
        // When a model single step is stopped by the guard the task ends right there, so the explanation is not needed
        RefusalStyle.Model -> if (code == "approval_unavailable") notRun(code,
            "This step spends money, but this run cannot collect the user's approval. The action was not run. " +
                "Do not retry this action in this run; explain the limitation to the user.") {
            hit?.let { put("action", it.action.name); put("matched", it.matched) }
            put("counts_as_failure", false)
        } else if (hit == null) notRun(code, if (code == OWN_APP) Feedback["own_app"] else code) else {
            val blocked = if (reason != null) mismatch(hit, reason) else required(hit)
            if (before.isEmpty()) blocked else JsonObject(blocked + ("guard" to JsonArray(before.map { it.toJson() })))
        }
        RefusalStyle.Script -> {
            val message = when (code) {
                "guard_stopped" -> "Claire's operation guard stopped the task."
                "guard_warning" -> "Claire's operation guard flagged this action; it was not run."
                "confirmation_required" -> "this step spends money (${hit?.matched})"
                "confirmation_mismatch" -> "this payment step (${hit?.matched}) does not match what the user approved ($reason)"
                "approval_unavailable" -> "this step spends money and this run cannot get the user's approval; the action was not run"
                "target_unresolved" -> "that ref is not on the latest snapshot; call mika.snapshot() again"
                OWN_APP -> Feedback["own_app"]
                else -> error("unknown refusal $code")
            }
            notRun(code, message) { hit?.let { put("action", it.action.name); put("matched", it.matched) } }
        }
        RefusalStyle.Code -> notRun(code, code)
    }

    /** A step that needs user approval (off-screen, declared by the tool as requiring it), but this run cannot reach anyone to ask. */
    fun unavailable(): JsonObject = buildJsonObject {
        put("ok", false); put("error", "approval_unavailable"); put("message", Feedback["approval.unavailable"])
        put("executed", false); put("recoverable", false); put("counts_as_failure", false)
    }

    private fun required(hit: Irreversible) = buildJsonObject {
        put("ok", false); put("error", "confirmation_required")
        put("action", hit.action.name); put("matched", hit.matched)
        put("message", if (hit.action == IrrevAction.pay) "This step is a payment, so it was not run. $PAYMENT_TAKEOVER"
            else "This step spends money (${hit.action.name}), so it was not run. First call request_confirmation saying where, what is bought and how much it costs, and do it after the user approves. $SINGLE_USE")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun mismatch(hit: Irreversible, reason: String) = buildJsonObject {
        put("ok", false); put("error", "confirmation_mismatch")
        put("action", hit.action.name); put("matched", hit.matched); put("reason", reason)
        put("message", "This step does not match the action the user approved ($reason). That approval is void, and this step was not run. " +
            "If it still needs doing, call request_confirmation again describing this step truthfully, and do it right after the user approves.")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun notRun(error: String, message: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("executed", false)
        extra()
    }
}
