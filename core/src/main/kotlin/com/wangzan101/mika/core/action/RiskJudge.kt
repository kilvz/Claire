package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.safety.gate.IrreversibleDetector
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

/**
 * The risk of an action. [hit] is the result of irreversible detection (null when no judgment was needed or nothing was hit); [screen] is the screen the judgment is based on, read only when needed.
 * Once a tool declares its own risk, swapping in another implementation is enough; detection and approval do not need to change.
 */
fun interface RiskJudge {
    fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk
}

/** Grades by the existing irreversible detection and screen-read signals; whether it costs money is determined by the action type and the amount on screen, not by the app. */
object ClassifiedRisk : RiskJudge {
    /** Actions that change things in the UI; the other write operations (back, swipe, open app, open URL) only move between screens. */
    private val CHANGE_VERBS = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact)

    /** This action changes things in the UI (not merely moving between screens); irreversible and outward actions are produced by these same action types too. */
    fun changesState(verb: Verb?): Boolean = verb in CHANGE_VERBS

    override fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk {
        val level = when (hit?.action) {
            null -> if (verb in CHANGE_VERBS) ActionRisk.Change else ActionRisk.Read
            IrrevAction.send, IrrevAction.submit -> ActionRisk.External
            IrrevAction.pay, IrrevAction.order, IrrevAction.delete -> ActionRisk.Irreversible
            IrrevAction.other -> ActionRisk.Unknown
        }
        if (hit == null) return Risk(level)
        // An amount is page content and does not mean this step costs money. Explicit payment/order always blocks; other actions need payable context such as a total or a payment panel.
        val payment = when (hit.action) {
            IrrevAction.pay, IrrevAction.order -> true
            IrrevAction.send, IrrevAction.submit -> false
            else -> screen()?.let(IrreversibleDetector::hasPayableAmount) == true
        }
        return Risk(level, payment)
    }

    /** Which element the action lands on: one with an id is found by id, typing lands on the focused input field, and a coordinate tap takes the smallest element containing that point. */
    fun target(verb: Verb?, args: JsonObject, s: ScreenSnapshot): UiElement? {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        str("ref")?.let { ref -> return s.elements.firstOrNull { it.ref == ref } }
        if (verb == Verb.TypeText || verb == Verb.ClearText) return s.elements.firstOrNull { it.focused && it.editable }
        val x = str("x")?.toDoubleOrNull() ?: return null
        val y = str("y")?.toDoubleOrNull() ?: return null
        return s.elements.filter { e -> e.bounds.size == 4 && x >= e.bounds[0] && x <= e.bounds[2] && y >= e.bounds[1] && y <= e.bounds[3] }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
    }
}
