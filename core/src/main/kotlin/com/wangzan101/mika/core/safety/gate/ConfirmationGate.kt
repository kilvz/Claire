package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.screen.uistate.Hant
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import java.math.BigDecimal
import kotlin.math.abs
import com.wangzan101.mika.core.tool.Verb

/** A money-spending operation declared by the model; fields are trimmed and truncated, and the UI displays them as they are. [app] is the package name. */
data class ConfirmationRequest(val action: IrrevAction, val target: String, val content: String, val amount: String?, val app: String)

sealed interface DeclareResult {
    /** The declared app does not match the foreground (or the foreground cannot be read), so it is not passed to the user. */
    data object Mismatch : DeclareResult
    /** The user has already declined the same kind of operation for the same app in this task; do not ask again. */
    data object Declined : DeclareResult
    /** Payments do not go this way; they go through takeover. */
    data object Unsupported : DeclareResult
    data object Pending : DeclareResult
}

sealed interface Permit {
    data object Allowed : Permit
    /** No approval. */
    data object NoGrant : Permit
    /** There is an approval, but it does not match this step (or has expired); that approval is void. */
    data class Mismatch(val reason: String) : Permit
}

/**
 * The user's approval for one money-spending operation (when asking, only payments are asked about): it lets through only the one matching operation that immediately follows.
 * The approval is bound to the declared content: the same foreground app, the same kind of action, the target shown on the screen (in the title bar for sends), the text to send equal to the declared one,
 * and the declared amount on screen within the payable context; prices, deals and ranges on ordinary pages are not payable amounts and take no part in the check;
 * the next irreversible operation that hits consumes it whether it matches or not. If a write operation other than typing happens in between (including back and opening an app),
 * or more than [MAX_STEPS] steps pass, the approval is void. Only this single occurrence can be approved; it is valid only within one task, and is [reset] at task start and end.
 * The task loop and the UI answer may be on different threads, so all reads and writes are locked.
 */
class ConfirmationGate {
    private class Grant(val request: ConfirmationRequest, val step: Int)

    private var pending: ConfirmationRequest? = null
    private var grant: Grant? = null
    private val declined = mutableSetOf<Pair<IrrevAction, String>>()

    @Synchronized fun declare(req: ConfirmationRequest, foregroundApp: String?): DeclareResult {
        if (req.action == IrrevAction.pay) return DeclareResult.Unsupported
        if (req.action to req.app in declined) return DeclareResult.Declined
        if (foregroundApp == null || foregroundApp != req.app) return DeclareResult.Mismatch
        pending = req
        return DeclareResult.Pending
    }

    /** [step] is the step number the reply belongs to; expiry is computed from it. */
    fun decide(approved: Boolean, step: Int = 0) {
        decide(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny, step)
    }

    /** Records a one-time approval according to the user's choice. */
    @Synchronized fun decide(choice: ConfirmChoice, step: Int) {
        val req = pending ?: return
        pending = null
        if (!choice.approved) {
            declined += req.action to req.app
            return
        }
        grant = Grant(req, step)
    }

    /**
     * [screen] is the current screen; [typing] is the text to type and enter at this step (for type_text).
     * [seen] is the visible text confirmed by re-inspecting the point found from the image: when the screen read has no text (a self-drawn UI), the target can be checked against it,
     * and if the text to send cannot be read it is not checked (text in the image cannot be checked). Whenever there is an approval it is consumed regardless of the result. Payments are never let through.
     */
    @Synchronized fun permits(hit: Irreversible, screen: ScreenSnapshot?, step: Int, typing: String? = null, seen: List<String> = emptyList()): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        val r = g.request
        val reason = when {
            hit.action == IrrevAction.pay -> "payment is never approved here"
            step - g.step > MAX_STEPS -> "the approval expired ($MAX_STEPS steps)"
            hit.action != r.action -> "approved ${r.action.name} but this step is ${hit.action.name}"
            screen?.packageName == null || screen.packageName != r.app -> "approved in ${r.app} but the screen shows ${screen?.packageName ?: "unknown"}"
            !targetShown(r, screen) && !(textless(screen) && seenShows(r, seen)) -> "the target \"${r.target}\" is not shown " + (if (r.action == IrrevAction.send) "in the title area" else "on the screen")
            r.action == IrrevAction.send && !contentMatches(r.content, screen, typing) && !(textless(screen) && seenShows(r, seen)) -> "the text to send is not exactly what was approved"
            amountBinds(hit, screen) && !amountShown(r.amount, screen, hit.matched) -> "the approved amount ${r.amount ?: "(none)"} is not on the screen"
            else -> return Permit.Allowed
        }
        return Permit.Mismatch(reason)
    }

    /**
     * A step that is not on the screen (the tool declared that it must ask, e.g. a root command that changes things): the approval is for exactly this request, so it is let through only when it matches the declaration exactly.
     * Whenever there is an approval it is consumed regardless of the result.
     */
    @Synchronized fun permitsExactly(ask: ConfirmationRequest, step: Int): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        return when {
            step - g.step > MAX_STEPS -> Permit.Mismatch("the approval expired ($MAX_STEPS steps)")
            g.request != ask -> Permit.Mismatch("approved a different action than this one")
            else -> Permit.Allowed
        }
    }

    /** The screen read has no element carrying text at all (a self-drawn UI, a canvas). */
    private fun textless(s: ScreenSnapshot) = s.elements.none { it.name.isNotBlank() }

    /** The declared target appears in the visible text that re-inspection confirmed (at least two characters). */
    private fun seenShows(r: ConfirmationRequest, seen: List<String>): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        return t.length >= 2 && seen.any { t in norm(it) }
    }

    /** A write operation was executed: typing and clearing input fields do not affect the approval, everything else (tapping elsewhere, swiping, back, opening an app) voids it. */
    /** There is an approved operation that has not been consumed yet. */
    val hasGrant: Boolean @Synchronized get() = grant != null

    /** The operation the user approved and that has not been consumed yet; null when there is none. */
    val granted: ConfirmationRequest? @Synchronized get() = grant?.request

    /** [verb]: the action of the write operation just executed; typing and clearing do not void the approval (the approval is for the step that is about to type). */
    @Synchronized fun noteWrite(verb: Verb?) {
        if (verb !in KEEPS_GRANT) grant = null
    }

    @Synchronized fun reset() {
        pending = null
        grant = null
        declined.clear()
    }

    /**
     * The target must appear in non-editable text on the screen (text typed into input fields does not count), at least two characters;
     * for sends only the title bar at the top of the screen counts (that is where the chat partner's name is), while for other actions anywhere on the screen will do.
     */
    private fun targetShown(r: ConfirmationRequest, s: ScreenSnapshot): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        if (t.length < 2) return false
        return s.elements.any { e ->
            !e.editable && t in norm(e.name) && (r.action != IrrevAction.send || (e.bounds.size == 4 && e.bounds[1] <= TITLE_BAND))
        }
    }

    /**
     * The text to send must be exactly the approved text: when tapping send, the text in one of the input fields equals it; when type_text presses enter, the text to type equals it.
     * When the declaration was truncated (ends with …), prefixes are compared.
     */
    private fun contentMatches(content: String, s: ScreenSnapshot, typing: String?): Boolean {
        val capped = content.endsWith("…")
        val c = norm(content.removeSuffix("…"))
        fun same(x: String) = norm(x).let { if (capped) it.startsWith(c) else it == c }
        if (typing != null) return same(typing)
        return s.elements.any { it.editable && !it.hint && same(it.name) }
    }

    /**
     * Whether the amount takes part in this check; the risk classification uses the same payable context. Explicit ordering and amount actions declared by a tool always require the amount;
     * an ordinary submit or delete requires it only when the screen shows a total or a payment panel, so that ranges or deals on a page are not mistaken for a payment and approval is not demanded over and over.
     */
    private fun amountBinds(hit: Irreversible, s: ScreenSnapshot): Boolean = when (hit.action) {
        IrrevAction.order, IrrevAction.pay, IrrevAction.other -> true
        IrrevAction.send -> false
        IrrevAction.submit, IrrevAction.delete -> IrreversibleDetector.hasPayableAmount(s)
    }

    /**
     * The declared amount is compared only with numbers on the screen that carry a currency (¥, ￥, $, or a trailing yuan unit): "25 sold", "spend 25, save 3" do not count; neither does what is in input fields or hints;
     * amounts immediately next to discount, minus, deduction, shipping, coupon, red packet or saved (or carrying a minus sign) do not count.
     * When there are total / paid / due / subtotal labels: every total outside scrollable areas must equal the declared amount; when they are all inside scrollable areas, the one nearest the button is used;
     * when a total label is present but the amount cannot be read (not even by joining the following pieces on the same row), it is not let through.
     * With no total, it must equal the largest amount outside the scrollable areas. A declaration with no digits is not let through.
     */
    private fun amountShown(amount: String?, s: ScreenSnapshot, button: String): Boolean {
        val declared = amount?.let { currencyFree(it) }?.let { NUMBER.find(it)?.value }?.toBigDecimalOrNull() ?: return false
        fun same(x: BigDecimal) = x.compareTo(declared) == 0
        val shown = s.elements.filter { !it.editable && !it.hint }
        val scrollables = s.elements.filter { it.scrollable }
        fun outside(e: UiElement) = scrollables.none { inside(e, it) }
        val labels = shown.filter { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } }
        if (labels.isNotEmpty()) {
            val totals = labels.map { it to totalOf(it, shown) }
            if (totals.any { it.second == null }) return false
            val out = totals.filter { outside(it.first) }
            if (out.isNotEmpty()) return out.all { same(it.second!!) }
            val b = shown.firstOrNull { it.name.trim() == button }
            val near = totals.minByOrNull { (e, _) -> if (b == null) -top(e) else abs(centerY(e) - centerY(b)) }!!
            return same(near.second!!)
        }
        val largest = shown.filter(::outside).flatMap { amounts(it.name) }.maxOrNull() ?: return false
        return same(largest)
    }

    /** The amount after a total label: first look at the label itself, then join the pieces to its right on the same row in horizontal order and look again. */
    private fun totalOf(label: UiElement, shown: List<UiElement>): BigDecimal? {
        fun after(text: String) = TOTAL_LABELS.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 }?.let { text.substring(it + w.length) } }
            .firstNotNullOfOrNull { amounts(it).firstOrNull() }
        return after(Hant.fold(label.name)) ?: after(Hant.fold(IrreversibleDetector.rowText(label, shown)))
    }

    private fun currencyFree(t: String) = t.replace(THOUSANDS, "")

    /** Currency amounts, excluding those immediately preceded by discount-type words or carrying a minus sign. */
    private fun amounts(name: String): List<BigDecimal> {
        val t = currencyFree(Hant.fold(name))
        return CURRENCY.findAll(t).filter { m ->
            val before = t.substring(maxOf(0, m.range.first - DISCOUNT_WINDOW), m.range.first)
            DISCOUNTS.none { it in before } && !before.trimEnd().endsWith("-")
        }.mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.toBigDecimalOrNull() }.toList()
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun centerY(e: UiElement) = if (e.bounds.size == 4) (e.bounds[1] + e.bounds[3]) / 2 else 0

    private fun inside(c: UiElement, p: UiElement): Boolean {
        val a = c.bounds; val b = p.bounds
        return a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    }


    private fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    companion object {
        /** The approval is void if it is not used within this many steps after approval. */
        const val MAX_STEPS = 3
        const val MATCH_CHARS = 20
        /** The send target must be above this line (0–999): the title bar in roughly the top 15% of the screen. */
        const val TITLE_BAND = 150
        private val NUMBER = Regex("\\d+(\\.\\d+)?")
        private val CURRENCY = Regex("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元")
        private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
        private val TOTAL_LABELS = IrreversibleDetector.TOTAL_LABELS
        private val DISCOUNTS = listOf("优惠", "减", "抵扣", "运费", "券", "红包", "已省")
        private const val DISCOUNT_WINDOW = 4
        private val KEEPS_GRANT = setOf(Verb.TypeText, Verb.ClearText)
    }
}
