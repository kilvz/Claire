package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance

/**
 * The risk level of an action (recorded in the approval record so the review can tell the task did something outward). [Read] only looks or moves between UI screens; [Change] changes things in the UI (tap, type);
 * [External] hands something to someone else (send, submit); [Irreversible] cannot be undone (pay, order, delete); [Unknown] what was tapped cannot be determined.
 */
enum class ActionRisk { Read, Change, External, Irreversible, Unknown;

    /** Affects the world beyond the screen (or it is unclear whether it does). */
    val outward: Boolean get() = this == External || this == Irreversible || this == Unknown
}

/**
 * When [payment] is true this step costs money (payment, order, or an outward action while an amount due is on screen): the user is asked first.
 * When [ask] is not null it is an off-screen step that cannot be undone if changed (declared by the tool for these arguments, such as a root command that changes things); the user is asked first here too,
 * and what the user sees is it; an approval lets through only this exact step.
 */
data class Risk(val level: ActionRisk, val payment: Boolean = false, val ask: ConfirmationRequest? = null)

/** The user's choice on the confirmation card: approve this once, or decline. */
enum class ConfirmChoice { Once, Deny;

    val approved: Boolean get() = this != Deny
}

/** Ask / autonomous: whether autonomous mode is on right now (when it is switched to autonomous mid-way, a running task follows it from the next step). Provided by settings; tests swap in a fixed value. */
fun interface ApprovalSettings {
    /** Autonomous mode: not even payments are asked about. */
    fun autonomous(): Boolean

    companion object {
        /** Ask (default): payments and the steps the tool declares must be asked about (see [Risk.ask]) are asked first. */
        val ASK = ApprovalSettings { false }
    }
}

sealed interface Approval {
    /** No need to ask. */
    data object Allow : Approval
    /** Needs the user's approval for this one time (an existing single-use approval is checked and used up). */
    data object Ask : Approval
    /** This run cannot ask the user for approval, so the action must stop. */
    data object Deny : Approval
}

/**
 * Approval decision: a pure function. Payments and what the tool declares must be asked about (see [Risk.ask]) require asking; when no one can be reached (unattended, subtask) or it is not on the phone
 * (started by a channel, a schedule, or MCP), none of these are done.
 */
object ApprovalPolicy {
    fun decide(risk: Risk, run: RunProvenance = RunProvenance()): Approval = when {
        // Unattended / not on the phone: payments, steps the tool declares must be asked about, and irreversible steps are not done; reads, UI changes, and outward but revocable steps go ahead
        (run.attendance == Attendance.Unattended || run.origin != RunOrigin.Ui) &&
            (risk.payment || risk.ask != null || risk.level == ActionRisk.Irreversible) -> Approval.Deny
        !risk.payment && risk.ask == null -> Approval.Allow
        else -> Approval.Ask
    }
}
