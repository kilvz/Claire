package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.CONFIRM_ACTIONS
import com.wangzan101.mika.core.tool.CONFIRM_FIELD_MAX
import com.wangzan101.mika.core.tool.HINT_PAYMENT_TAKEOVER
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.capField
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.uistate.confirmTarget
import com.wangzan101.mika.core.screen.uistate.focus
import com.wangzan101.mika.core.screen.uistate.lastSnapshot
import com.wangzan101.mika.core.screen.uistate.session

/**
 * Asks the user to agree before the step that spends money (placing an order, submitting when the screen shows an amount due, etc.); this is the only thing to ask for. Paying itself is not here: it is left for the user to do by hand.
 * The foreground check of what is declared uses a screen re-read at this moment (no ID is registered, and the refs held by the model are left untouched).
 */
internal object RequestConfirmation : SurfaceAware {
    override val verb = Verb.RequestConfirmation
    override val label = "Ask for approval"
    override val risk = Risk.Read
    override val def = ToolDef("request_confirmation",
        Prompts.tool("request_confirmation"),
        mapOf(
            "action" to str("Kind of action that spends the money (order for placing an order or booking).", CONFIRM_ACTIONS),
            "target" to str("Who or where the money goes (shop, seller, service), as shown on screen. At most $CONFIRM_FIELD_MAX characters."),
            "content" to str("What is bought: item, spec, quantity. At most $CONFIRM_FIELD_MAX characters."),
            "amount" to str("Amount and currency the user will pay, as shown on screen."),
            "app" to str(APP_NOTE),
        ), listOf("action", "target", "content", "amount", "app"))

    /** What to write for the app parameter follows this task's screens: with one screen use that screen's wording, with several screens write one clause for each. */
    override fun def(ctx: ToolContext): ToolDef {
        val base = super.def(ctx)
        val surfaces = ctx.focus.surfaces
        val how = if (surfaces.size == 1) surfaces.single().targetNote
        else surfaces.joinToString("; ") { "on the ${it.id} screen, ${it.targetNote}" }
        return ToolDef(base.name, base.description, base.props + ("app" to str("$APP_NOTE: $how.")), base.required)
    }

    private const val APP_NOTE = "Where the action happens, as on screen now"

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val action = a.str("action")!!.trim()
        val kind = IrrevAction.entries.firstOrNull { it.name == action }
            ?: throw ArgError("action", "must be one of ${CONFIRM_ACTIONS.joinToString("|")}")
        if (kind == IrrevAction.pay) {
            ctx.refusedPay = ConfirmationRequest(kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
                a.str("amount", max = 10000)?.let(::capField), a.str("app")!!.trim())
            return fail("payment_requires_takeover", HINT_PAYMENT_TAKEOVER)
        }
        // Only spending money needs asking: an action with no amount needs no approval, so just do it
        if (a.str("amount")?.any(Char::isDigit) != true) throw ArgError("amount",
            "required: the total the user will pay, as shown on screen. An action that spends no money needs no approval: do it directly")
        val app = ctx.confirmTarget(a.str("app")!!.trim())
        val req = ConfirmationRequest(
            kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
            a.str("amount", max = 10000)?.let(::capField), app,
        )
        val fg = ctx.session.peek()?.packageName ?: ctx.lastSnapshot?.packageName
        return ToolExecution(okObj { put("asked", true) }, outcome = Outcome.Confirm(req, fg))
    }
}
