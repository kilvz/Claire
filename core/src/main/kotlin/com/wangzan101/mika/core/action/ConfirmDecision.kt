package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.safety.gate.IrreversibleDetector
import com.wangzan101.mika.core.safety.gate.Permit
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.run.RunProvenance

/** The approval conclusion reached before an action executes. */
sealed interface Decision {
    /** No judgment needed, or judged and allowed (including when the user's approval for a payment matched). */
    data object Clear : Decision
    /** Allowed, but which element was tapped cannot be determined: leave a trace in the run record. */
    data class Traced(val detail: String) : Decision
    /** Which element was tapped cannot be determined, and this origin does not tap blindly. */
    data object Unresolved : Decision
    /**
     * This step needs the user's approval for this one time (it costs money, or [ask] is non-null for an off-screen step the tool declares must be asked about) and none is available.
     * When [ask] is present the caller asks the user on the spot if it can reach one (see [FollowUp.approve]).
     */
    data class Required(val hit: Irreversible, val ask: ConfirmationRequest? = null) : Decision
    /** An approval exists but does not match this step; that approval is now void. */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** It costs money, but this run cannot reach a user next to the phone, so it is refused outright. */
    data class Denied(val hit: Irreversible) : Decision
    /** It acts on Claire itself, so it is not done. */
    data object OwnApp : Decision
}

/**
 * Irreversible detection, approval, and consent checking. This is the only place in the whole codebase that calls [IrreversibleDetector]'s detection and [ConfirmationGate.permits]
 * (guarded by tests); each origin only states where it differs in [OriginPolicy]; whether to ask is decided by [ApprovalPolicy.decide] (payments and what tools declare must be asked are asked).
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** Starting out already autonomous (no confirmation gate): payments are not asked about either, and an uncertain UI target does not block the action; switching to autonomous mid-way is governed by [ApprovalSettings.autonomous]. */
    private val autonomous: Boolean = false,
    /** Claire's own package name: write operations acting on it are never done (never tap approval or change settings on itself). */
    private val selfPackage: String? = null,
) {
    /**
     * When the step just judged is an outward action (send, submit: the detection hit and it is what will happen after being allowed), the site or app it is on (see [OutwardPacing.siteOf]);
     * the execution entry point uses it to space this action from the previous outward one; null when it is not. It is also recognized under autonomous execution (judged from the screen the model holds, with no extra read), so pacing does not stop working because of it.
     */
    var outwardSite: String? = null
        private set

    /** The judgment happens after the locked-screen wait, so it is made against the screen re-read at this moment. */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin): Decision {
        outwardSite = null
        if (autonomous || approvals.autonomous()) {
            val target = tools.gateTarget(tool)
            if (IrreversibleDetector.needsCheck(target, args)) {
                val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
                outwardSite = outward(IrreversibleDetector.classify(target, aim.args, aim.aimed), aim.aimed)
            }
            return Decision.Clear
        }
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        // Where this step lands is supplied by the UI side; when a @vN found by looking at the screenshot is tapped, the arguments have already been replaced with its registered coordinates and matching text
        val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
        val checked = aim.args
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp
        tools.approvalRisk(tool, checked)?.let { declared ->
            return approve(tool, checked, origin, null, declared, null, null, null) ?: Decision.Clear
        }
        if (confirmations == null && !policy.judgeWithoutGate) return Decision.Clear
        val verb = tools.verb(tool)
        val typing = if (verb == Verb.TypeText) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        // An action with no irreversible wording to judge (an ordinary change, moving between screens) costs nothing; no need to ask
        if (!IrreversibleDetector.needsCheck(target, args)) return Decision.Clear
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // Re-check the visible text that was confirmed (only available for @vN): when the screen read has no text (custom-drawn UI), a payment approval is matched against it
        val seen = aim.seen
        val hit = IrreversibleDetector.classify(target, checked, snap, fresh)
        val risk = risks.assess(verb, hit) { fresh ?: snap }
        outwardSite = outward(hit, fresh ?: snap)
        approve(tool, checked, origin, hit, risk, fresh ?: snap, typing, seen)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** The site or app on this screen when what was hit is send or submit. */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** The write operation acts on Claire itself: the screen is Claire, or Claire is to be opened (including its app details page). */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    /** Judge according to the origin; when it costs money, check the user's single-use approval. Returns null when allowed. */
    private fun approve(
        tool: String, checked: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?,
    ): Decision? {
        val policy = origin.policy
        // Not hit by irreversible detection (the tool declares its own risk): record as declared if declared, otherwise as "other" based on the element the action lands on
        val asked = hit ?: risk.ask?.let { Irreversible(it.action, it.content) } ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.verb(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // Record outward actions, money-spending actions, and anything not allowed; UI changes are not recorded
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (ApprovalPolicy.decide(risk, provenance)) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied(asked).also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked, risk.ask)
        val gate = confirmations?.takeIf { policy.usesGrant } ?: return required.also { audit("required") }
        val permit = risk.ask?.let { gate.permitsExactly(it, origin.step) } ?: gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))
        return when (val p = permit) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    /** A write operation was executed: the approval is voided or kept according to its rules. */
    fun noteWrite(tool: String) {
        confirmations?.noteWrite(tools.verb(tool))
    }

    /** Leftover trace: which id was tapped and how long ago that screen was read, or the tapped coordinates. */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * Pre-judgment without reading the screen (used for the small/large model split): based on the last screen read, this step may be irreversible, or what is tapped may be unknown (obtained from a screenshot, element not found);
         * such a step is handed to the main model.
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
