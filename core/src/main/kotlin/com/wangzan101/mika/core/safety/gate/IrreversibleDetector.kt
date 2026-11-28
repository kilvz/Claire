package com.wangzan101.mika.core.safety.gate

import com.wangzan101.mika.core.net.str
import com.wangzan101.mika.core.screen.uistate.CdpKeys
import com.wangzan101.mika.core.screen.uistate.KeyCombo
import com.wangzan101.mika.core.screen.uistate.KeyModifier
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ElementContext
import com.wangzan101.mika.core.screen.uistate.Hant
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.core.screen.uistate.PressKeys
import com.wangzan101.mika.core.tool.LOCATED_REF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [matched]: the text that matched (button name, resource ID, target argument, input field, or the start point of a swipe). */
data class Irreversible(val action: IrrevAction, val matched: String)

/**
 * Word-list backstop before a write operation executes: it only recognizes a few signals — button names, Enter/confirm
 * keys, and sideways swipes on list rows — with no semantic understanding. When unsure it errs on the side of blocking
 * more (the model can continue after one extra request_confirmation); only submit is deliberately matched on exact
 * equality.
 *
 * Screen reads: the snapshot of [classify] is the read the ref belongs to (the latest one when there is no ref), and
 * fresh is a screen re-read right now. Coordinates, Enter and swipes are judged against fresh (falling back to snapshot
 * only when it cannot be read); refs are judged against both, and a hit in either one counts.
 */
object IrreversibleDetector {
    /**
     * A hit when it appears in the name (a short name, or any segment of a composite name); but a match preceded by the
     * pending/done prefixes and followed by one of [STATUS_SUFFIXES] is an order status or entry point (pending payment,
     * payment succeeded, purchase history), so it does not count.
     */
    val STRONG: Map<String, IrrevAction> = linkedMapOf(
        "支付" to IrrevAction.pay, "付款" to IrrevAction.pay, "转账" to IrrevAction.pay,
        "删除" to IrrevAction.delete, "撤回" to IrrevAction.delete,
        "下单" to IrrevAction.order, "购买" to IrrevAction.order, "结算" to IrrevAction.order, "结账" to IrrevAction.order,
        "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order, "退款" to IrrevAction.order,
        "转发" to IrrevAction.send,
    )
    private const val STRONG_MAX_LEN = 12
    private val STATUS_PREFIXES = listOf('待', '已')
    val STATUS_SUFFIXES = listOf("成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码")

    /** These payment words only count on the button itself, or when preceded by "immediately", "confirm" or "go" (top-up centers, phone credit top-up and discounted checkout are entry points and do not count). */
    val EXACT_PAY = listOf("收银台", "买单", "充值", "储值", "提现", "转出", "打赏", "缴费", "捐赠", "捐款")
    private val EXACT_PAY_PREFIXES = listOf("", "立即", "确认", "去")

    /** Red packets only count in these phrasings on a button (a WeChat red-packet bubble in a chat does not count). */
    val RED_PACKET = listOf("红包", "发红包", "塞钱进红包")

    /** Chinese: equal to the word, or starting with it and only a few extra characters (such as send(1)). */
    val PREFIX_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(
        IrrevAction.send to listOf("发送", "传送", "发布", "发表"),
        IrrevAction.delete to listOf("清空", "移除", "注销", "退出群聊"),
        IrrevAction.order to listOf("提交订单"),
    )
    private const val PREFIX_SLACK = 4

    /** Equal to the word, or followed only by punctuation (comment(23) and reply 3 are entry points and counters, so they do not count). */
    val PUNCT_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(IrrevAction.send to listOf("评论", "回复"))

    /** Equal to the word, or preceded by "immediately" or "confirm". */
    val BOOKING_WORDS = listOf("预约", "报名")
    private val BOOKING_PREFIXES = listOf("", "立即", "确认")

    /** Only exact equality counts: things like submit search are not blocked. */
    val SUBMIT_WORDS = listOf("提交", "确认提交", "送出", "submit")

    /**
     * Buttons that contact someone on the user's behalf or submit an application in one tap (tapping them makes the site
     * send the greeting or the résumé to the other party) are treated as submit: equal to the word, or preceded by
     * "immediately", "right away", "one-tap" or "go" with only punctuation after it.
     * Continue chatting, already delivered, contact history, contact support, contact us, direct message and messages do
     * not count: those are existing conversations, records, a support page, or just the place where a message is composed
     * (what actually sends it is the send action that follows).
     */
    val CONTACT_WORDS = listOf(
        "沟通", "打招呼", "打个招呼", "发起聊天", "发起沟通", "联系ta", "联系他", "联系她", "联系对方",
        "投递", "投简历", "投递简历", "应聘",
    )
    private val CONTACT_PREFIXES = listOf("", "立即", "马上", "一键", "去")
    /** "Apply" alone is too broad (apply for a refund, request a permission): only these phrasings count. */
    val APPLY_WORDS = listOf("立即申请", "一键申请", "申请职位", "申请岗位", "申请该职位")
    /** English: the whole name equals one of these (case-insensitive, whitespace collapsed, trailing punctuation stripped); a bare Apply (apply on a filter panel), Contact or Message does not count. */
    val CONTACT_ENGLISH = listOf(
        "say hi", "say hello", "send greeting", "apply now", "easy apply", "quick apply", "apply for this job", "apply to this job",
        "submit application", "contact now", "contact recruiter", "contact employer", "contact seller", "contact host", "contact owner",
        "contact landlord", "contact agent",
    )

    /** English: the name starts with this word (at a word boundary, case-insensitive), any length, e.g. "Send message", "Delete conversation". */
    val ENGLISH: Map<String, IrrevAction> = linkedMapOf(
        "send" to IrrevAction.send, "post" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay, "donate" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "clear all" to IrrevAction.delete,
        "place order" to IrrevAction.order, "buy now" to IrrevAction.order, "checkout" to IrrevAction.order,
    )

    /** Generic confirmations on dialogs and bottom sheets: only count when the same screen has a strong word or an amount. */
    val GENERIC = listOf("确认", "确定", "ok", "完成", "confirm")
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")
    /** An amount labeled with one of these is a total and counts as context whether or not it is inside a scrollable area. */
    val TOTAL_LABELS = listOf("合计", "实付", "应付", "总计")
    /** When the same screen shows an amount, these words indicate a payment sheet (payment method, cashier, password verification). */
    val PAY_SHEET_WORDS = listOf("支付方式", "付款方式", "支付宝", "银行卡", "收银台", "免密")
    /** These words are also common on ordinary pages (account balance, login password): they only count as a payment sheet when they are in the bottom half and that area also has a confirm/pay-style button. */
    val WEAK_PAY_SHEET_WORDS = listOf("余额", "密码", "指纹", "零钱")
    /** Wording that describes payment methods (common on product pages and spec sheets) is not a payment action: strip it before judging. */
    private val PAYMENT_ADS = Regex("支持[^\\s，,。]{0,8}?(?:付款|支付)|货到付款|先用后付|花呗|分期|付款方式说明")
    /** When the page has these order buttons it is a product page or spec sheet: do not judge by payment-sheet wording. */
    private val ORDER_CONTEXT = listOf("立即购买", "加入购物车", "立即下单")
    /** On the 0–999 scale, anything below this line is the bottom region of the screen (payment sheets, bottom overlays). */
    private const val BOTTOM_REGION = 500
    private val PAY_SHEET_ENGLISH = Regex("(?<![A-Za-z0-9_])(pay|payment|checkout total)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** Strip these from the name first, then check strong words: payment-method options are not payment actions. */
    val EXCLUDED = listOf("支付宝", "支付方式", "支付设置")

    /** A financial term immediately followed by a complete job title, up to a word boundary: "payment product manager" is not a payment, while "pay the engineer's salary" still counts as a payment. */
    private val FINANCIAL_JOB = Regex(
        "(支付|付款|转账|结算)(?:产品(?:经理|总监)|(?:研发|开发|测试|运维)?工程师|架构师|运营(?:经理|专员)?|专员|会计)(?![\\p{L}\\p{N}])",
    )
    private val JOB_ACTION_CUES = listOf("请", "确认", "确定", "立即", "马上", "现在", "去", "点击", "点此", "滑动", "继续", "完成", "一键", "同意", "提交", "给", "向", "为")
    private val JOB_PAYMENT_DETAILS = listOf("工资", "薪资", "薪酬", "薪水", "费用", "服务费", "手续费", "劳务费", "报酬", "款项")

    /** Words obtained by splitting a resource ID on _ - . and camelCase; submit and post are not here (too common in resource IDs). */
    private val RESOURCE_WORDS: Map<String, IrrevAction> = mapOf(
        "send" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete,
        "checkout" to IrrevAction.order,
    )

    /** Only a field that is clearly a search box is exempt from counting as send: Enter in any other field is always blocked as send. These words are looked for in the hint, and the word search in the resource ID. */
    private val SEARCH_FIELD = listOf("search", "搜索", "搜寻")
    private val SEARCH_BUTTONS = setOf("搜索", "搜寻", "search")

    private const val KEY_ENTER = 66
    private const val KEY_NUMPAD_ENTER = 160
    private const val KEY_DPAD_CENTER = 23
    private const val KEY_SPACE = 62
    private const val KEY_DEL = 67
    private const val KEY_FORWARD_DEL = 112
    private val ACTIVATING_KEYS = setOf(KEY_ENTER, KEY_NUMPAD_ENTER, KEY_DPAD_CENTER, KEY_SPACE)

    /** A sideways swipe longer than this fraction of the screen width (0–999) that starts on a list row counts as swipe-to-delete. */
    private const val SWIPE_DELETE_MIN = 400
    /** Clickable elements wider than this (0–999) count as list rows. */
    private const val ROW_MIN_WIDTH = 600
    /** Elements taller than this (0–999) do not count as being on the same row (containers, large blocks of text). */
    private const val ROW_MAX_HEIGHT = 150
    /** Scrollable areas shorter than this (0–999) are treated as carousels. */
    private const val CAROUSEL_MAX_HEIGHT = 400

    /** Names of clear-style buttons next to an input field (whitespace stripped, lowercased). */
    private val FIELD_CLEAR_WORDS = setOf("清空", "清除", "清空输入", "清除输入", "清空搜索", "清除搜索", "清空内容", "清除内容",
        "clear", "cleartext", "clearsearch", "clearquery", "×", "✕", "x")
    /** A clear key may be at most this far (0–999) from the left or right edge of the input field. */
    private const val FIELD_NEAR = 80

    // What to judge and how is declared by the tool ([GateTarget], only External tools have one); going back, clearing an input field and opening an app submit nothing, so they are not judged
    /** Keys that change the selection: arrow keys, Home/End, paging. */
    private val SELECTION_KEYS = setOf(19, 20, 21, 22, 122, 123, 92, 93)
    /** Controls where changing the selection may trigger a page action (an onchange submit). */
    private val SELECTION_ROLES = setOf("combobox", "listbox", "option", "radio", "radiogroup", "menu", "menubar", "menuitem",
        "menuitemradio", "menuitemcheckbox", "tree", "treeitem")
    private val OPTION_ROLES = setOf("option", "radio", "menuitem", "menuitemradio", "menuitemcheckbox", "treeitem")

    /** Whether this call needs a check (only then is a screen read taken). */
    fun needsCheck(target: GateTarget?, args: JsonObject): Boolean = when (target) {
        // Upload taps the file control, judged the same way as a tap
        GateTarget.Tap, GateTarget.FileControl -> true
        // Judged when typing carries Enter; typing at a point also judges that tap (it may land on a send-style button)
        GateTarget.Enter -> bool(args, "pressEnter") || atPoint(args)
        GateTarget.Key -> key(args) in ACTIVATING_KEYS
        GateTarget.Swipe -> horizontal(args) != null
        // Every web interaction that can trigger a submit is judged: each key combo by focus, drags by start and end point
        // (swipe to pay, drag into a delete zone), and selects by the dropdown and the chosen option (the page may submit
        // on change); hovering never submits
        GateTarget.Interact -> when (args.str("action")) {
            "key" -> combo(args) != null
            "drag", "select" -> true
            // Clicking "OK" on a page dialog may be a submit (confirm delete, confirm payment): judged by the dialog text; cancel is not judged
            "dialog" -> args.str("answer") == "accept"
            else -> false
        }
        null -> false
    }

    /** null = not an irreversible operation. */
    fun classify(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Irreversible? {
        if (!needsCheck(target, args)) return null
        return when (target) {
            GateTarget.Tap, GateTarget.FileControl -> tapHit(args, snapshot, fresh)
            GateTarget.Enter -> (if (atPoint(args)) tapHit(args, snapshot, fresh) else null)
                ?: if (bool(args, "pressEnter")) enterHit(args, snapshot, fresh ?: snapshot, KEY_ENTER) else null
            GateTarget.Key -> enterHit(args, snapshot, fresh ?: snapshot, key(args)!!)
            GateTarget.Swipe -> swipeHit(args, fresh ?: snapshot)
            GateTarget.Interact -> when (args.str("action")) {
                "key" -> comboHit(args, snapshot, fresh ?: snapshot)
                "select" -> selectHit(args, snapshot, fresh)
                "dialog" -> dialogHit(fresh ?: snapshot)
                else -> dragHit(args, snapshot, fresh)
            }
            null -> null
        }
    }

    private fun combo(args: JsonObject) = args.str("key")?.let(CdpKeys::parseCombo)

    /** Typing targets a point (coordinates, or an @vN before it is resolved to coordinates), not an element in the tree. */
    private fun atPoint(args: JsonObject) = args["x"] != null || args.str("ref")?.let(LOCATED_REF::matches) == true

    /**
     * A key combo is judged by what it can do to the focus:
     * Enter and Space (with or without modifiers) activate the focus and are judged by the press_key Enter/Space rules
     * (the same as tapping it; Space inside an input field is typing);
     * arrow keys, Home/End and paging change the selection and are only judged when the focus is a selection control,
     * judged by the control and the items it can select (same as select);
     * when the focus is not an input field, the page may treat Delete, Backspace, a single alphanumeric key, or
     * Ctrl/Alt/Meta + alphanumeric as a command: with the focus on an element it is judged as tapping it (a letter on a
     * selection control jumps to the first match, judged by the control and its options); with the focus on the page
     * itself (no focused element) Delete and Backspace are always blocked, and the other command keys are only judged by
     * the control when the page declares that it is that control's shortcut (aria-keyshortcuts, accesskey);
     * Tab, Escape and the like only move the focus or close things, and keys inside an input field are editing, so none
     * of them are judged.
     */
    private fun comboHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?): Irreversible? {
        val combo = combo(args)!!
        val code = combo.key.androidKeyCode
        if (code == KEY_ENTER || code == KEY_SPACE) return enterHit(args, snapshot, s, code)
        val screen = s ?: snapshot ?: return null
        val focused = screen.elements.firstOrNull { it.focused }
        if (focused?.editable == true) return null
        // Keys that change the selection on a selection control (arrows, type-ahead jump) are judged by the control and its selectable items
        fun selection(e: UiElement) = selectionTargets(e, screen).firstNotNullOfOrNull { elementHit(it, screen) }
        if (code in SELECTION_KEYS) return focused?.takeIf { it.role in SELECTION_ROLES }?.let(::selection)
        val deleting = code == KEY_DEL || code == KEY_FORWARD_DEL
        val printable = combo.key.text != null && combo.key.text != " "
        if (!deleting && !printable) return null
        val typeAhead = printable && combo.modifiers.all { it == KeyModifier.Shift }
        if (focused != null) {
            val own = if (typeAhead && focused.role in SELECTION_ROLES) selection(focused) else elementHit(focused, screen)
            return own ?: declaredHit(combo, screen)
        }
        if (deleting) return Irreversible(IrrevAction.delete, "${combo.key.key}: no clear focus")
        return declaredHit(combo, screen)
    }

    /** The control the page declares this combo for (aria-keyshortcuts, accesskey), judged as tapping it. */
    private fun declaredHit(combo: KeyCombo, s: ScreenSnapshot): Irreversible? = s.elements
        .filter { e -> e.keyShortcuts.any { sameCombo(CdpKeys.parseCombo(it), combo) } }
        .firstNotNullOfOrNull { elementHit(it, s) }

    private fun sameCombo(a: KeyCombo?, b: KeyCombo) = a != null && a.key.code == b.key.code && a.modifiers.toSet() == b.modifiers.toSet()

    /** A selection control and the items it can select: the options inside the control, plus, when the focus itself is an option, the other options of the same kind (radio group, menu). */
    private fun selectionTargets(focused: UiElement, s: ScreenSnapshot): List<UiElement> {
        val inside = s.elements.filter { it !== focused && it.role in OPTION_ROLES && inside(it, focused) }
        val peers = if (focused.role in OPTION_ROLES) s.elements.filter { it !== focused && it.role == focused.role } else emptyList()
        return listOf(focused) + inside + peers
    }

    /** Page confirm dialog: the dialog text is judged as a button name (a generic "OK" looks at the same-screen context), then judged by strong words in the whole sentence. */
    private fun dialogHit(s: ScreenSnapshot?): Irreversible? {
        val message = s?.web?.dialog?.message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val action = matchName(message, s) ?: message.split(Regex("[\\s，,。.！!？?：:；;]+")).firstNotNullOfOrNull { strong(it) } ?: return null
        return Irreversible(action, "dialog:${message.take(40)}")
    }

    /** Dropdown select: the dropdown is judged as a tap, and the chosen option's text as a button name. */
    private fun selectHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        tapHit(args, snapshot, fresh)?.let { return it }
        val option = args.str("option") ?: return null
        return matchName(option, fresh ?: snapshot)?.let { Irreversible(it, "option:${option.trim()}") }
    }

    /** The drag start and end points are each judged as a tap (they share the snapshotId). */
    private fun dragHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        fun end(ref: String, x: String, y: String) = buildJsonObject {
            val r = args.str(ref)
            if (r != null) {
                put("ref", r)
                args.str("snapshotId")?.let { put("snapshotId", it) }
            } else {
                args[x]?.let { put("x", it) }
                args[y]?.let { put("y", it) }
            }
        }
        return listOf(end("ref", "x", "y"), end("toRef", "toX", "toY")).firstNotNullOfOrNull { tapHit(it, snapshot, fresh) }
            ?.let { it.copy(matched = "drag:${it.matched}") }
    }

    /**
     * Whether this screen is a payment page or payment sheet: it has wording such as confirm payment or payment password,
     * or it has an amount and looks like a payment sheet (product pages and spec sheets do not count). Returns the evidence
     * that hit; null when it is not.
     */
    fun paymentPage(s: ScreenSnapshot): String? {
        ScreenDiagnosis.paymentText(s.elements.map { it.name })?.let { return it }
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } || AMOUNT.containsMatchIn(rowText(e, shown)) }
        return if (amount && paySheet(shown)) "payment sheet with an amount" else null
    }

    /**
     * Whether this screen has payable-amount context connected to the action: a total / amount paid / amount due / grand
     * total label, or a payment page or payment sheet carrying an amount.
     * A price, salary or range on its own is only page content; explicit pay and order actions do not go through here and
     * are always judged by the amount action.
     */
    fun hasPayableAmount(s: ScreenSnapshot): Boolean {
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { AMOUNT.containsMatchIn(rowText(it, shown)) }
        if (!amount) return false
        return shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } } ||
            ScreenDiagnosis.paymentText(shown.map { it.name }) != null || paySheet(shown)
    }

    /** The tap cannot be resolved to any element (missing screen read, a ref not from this read, no element at the coordinates); recorded in the run log. */
    fun unresolved(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Boolean =
        target == GateTarget.Tap && tapTargets(args, snapshot, fresh).isEmpty()

    /**
     * Match a button name against the word lists; when [screen] is given, a generic "confirm"/"OK" checks whether the same
     * screen has a strong word or an amount.
     * [hit] is the tapped element (when known): an amount inside a scrollable area is only disregarded when the tapped
     * element is not inside that area.
     */
    fun matchName(name: String, screen: ScreenSnapshot? = null, hit: UiElement? = null): IrrevAction? {
        val raw = Hant.fold(name).trim()
        if (raw.isEmpty()) return null
        val n = raw.filterNot(Char::isWhitespace).lowercase()
        if (n in SUBMIT_WORDS) return IrrevAction.submit
        if (contacts(raw)) return IrrevAction.submit
        if (n in RED_PACKET) return IrrevAction.pay
        if (EXACT_PAY.any { w -> EXACT_PAY_PREFIXES.any { n == it + w } }) return IrrevAction.pay
        strong(raw)?.let { return it }
        for ((action, words) in PREFIX_WORDS) for (w in words) {
            if (n.startsWith(w) && n.length <= w.length + PREFIX_SLACK) return action
        }
        for ((action, words) in PUNCT_WORDS) for (w in words) {
            if (n.startsWith(w) && n.drop(w.length).none { it.isLetterOrDigit() }) return action
        }
        if (BOOKING_WORDS.any { w -> BOOKING_PREFIXES.any { n == it + w } }) return IrrevAction.order
        val lower = raw.lowercase()
        for ((w, action) in ENGLISH) {
            if (lower.startsWith(w) && lower.getOrNull(w.length)?.isLetter() != true) return action
        }
        if (screen != null && n.trimEnd { !it.isLetterOrDigit() } in GENERIC) return contextAction(screen, hit)
        return null
    }

    /** The name is a button that contacts someone or submits an application (see [CONTACT_WORDS]). */
    private fun contacts(raw: String): Boolean {
        val n = raw.filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
        if (n in APPLY_WORDS || CONTACT_WORDS.any { w -> CONTACT_PREFIXES.any { n == it + w } }) return true
        val e = raw.lowercase().trim().trimEnd { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        return e in CONTACT_ENGLISH
    }

    /**
     * Remove only this one business term from the job phrase and keep judging the other actions; it does not exempt the
     * whole element, resource ID or app. Word boundaries are honored before whitespace stripping and splitting, so that
     * "payment product manager, Beijing" does not match again and "pay  engineer" is not taken as a job title.
     * It is conservatively kept when the current label carries an explicit amount, the preceding text has an action cue,
     * or the following text is a salary or fee; salaries on other elements of the same screen do not affect job titles.
     */
    private fun withoutFinancialJobTerms(label: String): String {
        if (AMOUNT.containsMatchIn(label)) return label
        return FINANCIAL_JOB.replace(label) { match ->
            val before = label.substring(0, match.range.first).filterNot(Char::isWhitespace)
            val after = label.substring(match.range.last + 1).dropWhile { !it.isLetterOrDigit() }
            if (JOB_ACTION_CUES.any { it in before } || JOB_PAYMENT_DETAILS.any { after.startsWith(it) }) match.value
            else match.value.drop(match.groupValues[1].length)
        }
    }

    /** A strong word occurs in the whole short name or in each segment of a composite name; business terms in job titles and payment-method wording are stripped first. */
    private fun strong(label: String): IrrevAction? {
        val folded = withoutFinancialJobTerms(Hant.fold(label))
        for (p in listOf(folded) + folded.split(Regex("\\s+"))) {
            var t = p.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            EXCLUDED.forEach { t = t.replace(it, "") }
            if (t.isEmpty() || t.length > STRONG_MAX_LEN) continue
            STRONG.entries.firstOrNull { (w, _) -> occurrences(t, w).any { actionAt(t, w, it) } }?.let { return it.value }
        }
        return null
    }

    private fun occurrences(t: String, w: String) = generateSequence(t.indexOf(w).takeIf { it >= 0 }) { i -> t.indexOf(w, i + 1).takeIf { it >= 0 } }

    /** This occurrence is an action, not a status: it is not preceded by the pending/done prefixes and, skipping punctuation, not followed by a status word. */
    private fun actionAt(t: String, w: String, i: Int): Boolean {
        if (i > 0 && t[i - 1] in STATUS_PREFIXES) return false
        val rest = t.substring(i + w.length).dropWhile { !it.isLetterOrDigit() }
        return STATUS_SUFFIXES.none { rest.startsWith(it) }
    }

    /**
     * Same-screen (dialog, bottom sheet) context: a payment word means payment; otherwise the first non-order strong word
     * (such as a delete confirmation dialog); an amount together with payment-method, cashier or password-verification
     * wording on the same screen (or pay-style wording in a segment of any length) also means payment; otherwise an order
     * word or an amount means order (an order confirmation must match the on-screen amount, see [ConfirmationGate]); with
     * none of those it is not.
     * Amounts: total-style labels always count; other amounts inside a scrollable area do not count when the tapped
     * element is not inside that area (price brackets on filter panels, product lists).
     */
    private fun contextAction(screen: ScreenSnapshot, hit: UiElement?): IrrevAction? {
        val found = screen.elements.mapNotNull { strong(it.name) }
        if (IrrevAction.pay in found) return IrrevAction.pay
        found.firstOrNull { it != IrrevAction.order }?.let { return it }
        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        val amount = shown.any { e ->
            // Total-style labels always count (the amount may be split across later segments, or not loaded yet); other amounts are recognized from the whole row joined together
            TOTAL_LABELS.any { it in Hant.fold(e.name) } ||
                (AMOUNT.containsMatchIn(rowText(e, shown)) && scrollables.none { s -> inside(e, s) && hit != null && !inside(hit, s) })
        }
        if (amount && paySheet(shown)) return IrrevAction.pay
        return if (IrrevAction.order in found || amount) IrrevAction.order else null
    }

    /**
     * Whether a screen with an amount looks like a payment sheet: payment-method, cashier or password-verification
     * wording, or pay-style wording in a segment of any length (after stripping descriptions such as "supports Huabei
     * payment" or "cash on delivery"); when the page has buy-now or add-to-cart, none of this is judged.
     * Balance, password, fingerprint and change only count in the bottom half, and only when the bottom half has a
     * confirm/pay-style button.
     */
    private fun paySheet(shown: List<UiElement>): Boolean {
        if (shown.any { e -> ORDER_CONTEXT.any { it in Hant.fold(e.name).filterNot(Char::isWhitespace) } }) return false
        val bottomButton = shown.any { b ->
            b.clickable && top(b) >= BOTTOM_REGION && withoutFinancialJobTerms(Hant.fold(b.name)).filterNot(Char::isWhitespace).lowercase().let { n ->
                n.trimEnd { !it.isLetterOrDigit() } in GENERIC || "支付" in n || "付款" in n
            }
        }
        return shown.any { e ->
            val t = withoutFinancialJobTerms(Hant.fold(e.name)).filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            PAY_SHEET_WORDS.any { it in t } || PAY_SHEET_ENGLISH.containsMatchIn(e.name) ||
                listOf("支付", "付款").any { w -> occurrences(t, w).any { actionAt(t, w, it) } } ||
                (bottomButton && top(e) >= BOTTOM_REGION && WEAK_PAY_SHEET_WORDS.any { it in t })
        }
    }

    /**
     * Starting from [start], join the segments on the same row to its right in horizontal order ("amount paid | ¥ | 39.90").
     * Same row: the vertical center of each falls between the top and bottom edges of the other, so a full-screen container
     * does not pull other rows in.
     */
    internal fun rowText(start: UiElement, elements: List<UiElement>): String {
        if (start.bounds.size != 4) return start.name
        fun cy(e: UiElement) = (e.bounds[1] + e.bounds[3]) / 2
        val row = elements.filter { e ->
            e !== start && e.bounds.size == 4 && e.bounds[0] >= start.bounds[0] &&
                cy(e) in start.bounds[1]..start.bounds[3] && cy(start) in e.bounds[1]..e.bounds[3]
        }.sortedBy { it.bounds[0] }
        return start.name + row.joinToString("") { it.name.filterNot(Char::isWhitespace) }
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0

    internal fun matchResource(id: String): IrrevAction? = resourceWords(id).firstNotNullOfOrNull { RESOURCE_WORDS[it] }

    /** Words obtained by splitting a resource ID on _ - . and camelCase. */
    private fun resourceWords(id: String): List<String> =
        id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_', '-', '.')

    /**
     * Changing the text in an input field submits nothing by itself: tapping the field (just focus; its text is the
     * content being typed), tapping an unnamed small icon inside it (the × clear icon, voice) or the clear key, or a
     * clear-style button right next to the field on the same row, are all editing. What submits is the later Enter, send
     * or search.
     */
    internal fun editsField(e: UiElement, s: ScreenSnapshot): Boolean {
        if (e.editable) return true
        if (NumericKeypadEditing.edits(e, s)) return true
        if (e.bounds.size != 4) return false
        val fields = s.elements.filter { it.editable && it.bounds.size == 4 && it !== e }
        val n = Hant.fold(e.name).filterNot(Char::isWhitespace).lowercase()
        val clears = n in FIELD_CLEAR_WORDS || e.resourceId?.let { r -> "clear" in resourceWords(r) } == true
        // An unnamed small icon inside the field (× clear, voice) only counts as editing when its resource ID also does not indicate a send-style action; named buttons (a "send" in a web editor) are judged as usual
        val bareIcon = n.isEmpty() && e.resourceId?.let(::matchResource) == null
        if ((clears || bareIcon) && fields.any { inside(e, it) }) return true
        if (!clears) return false
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return fields.any { f -> cy in f.bounds[1]..f.bounds[3] && e.bounds[0] >= f.bounds[0] - FIELD_NEAR && e.bounds[0] <= f.bounds[2] + FIELD_NEAR }
    }

    private fun elementHit(e: UiElement, s: ScreenSnapshot): Irreversible? {
        for (x in withChildren(e, s)) {
            if (editsField(x, s)) continue
            matchName(x.name, s, x)?.let { return Irreversible(it, x.name.trim()) }
            x.resourceId?.let { r -> matchResource(r)?.let { return Irreversible(it, r) } }
        }
        return null
    }

    private fun tapHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        val targets = tapTargets(args, snapshot, fresh)
        // The tap is on an input field or its clear key: editing, regardless of the target the model wrote ("clear the search box")
        if (targets.isNotEmpty() && targets.all { (e, s) -> editsField(e, s) }) return null
        for ((e, s) in targets) elementHit(e, s)?.let { return it }
        // No named element at the point (custom-drawn UI, unnamed icons; points found by looking at an image are often like this): judge by the text on the same row
        if (args.str("ref") == null && targets.all { (e, s) -> withChildren(e, s).all { it.name.isBlank() } }) {
            val s = fresh ?: snapshot
            val y = num(args, "y")
            if (s != null && y != null) rowHit(s, y)?.let { return it }
        }
        // When the element cannot be found, or is an icon without text, the target the model wrote also counts;
        // points found from an image also carry the searched text (text) and the characters the model saw (seen), judged the same way
        return listOf("target", "text", "seen").firstNotNullOfOrNull { k ->
            args.str(k)?.let { t -> matchName(t, fresh ?: snapshot)?.let { Irreversible(it, t.trim()) } }
        }
    }

    /**
     * The elements a tap acts on (together with the screen they are on): ref → the element from the original screen read,
     * plus whatever is now at its center position; coordinates → the smallest clickable element at that point right now.
     */
    private fun tapTargets(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): List<Pair<UiElement, ScreenSnapshot>> {
        val ref = args.str("ref")
        if (ref != null) {
            val s = snapshot?.takeIf { it.snapshotId == args.str("snapshotId") } ?: return emptyList()
            val e = s.elements.firstOrNull { it.ref == ref } ?: return emptyList()
            val now = fresh?.let { f -> center(e)?.let { (x, y) -> at(f, x, y) }?.let { it to f } }
            return listOfNotNull(e to s, now)
        }
        val s = fresh ?: snapshot ?: return emptyList()
        val x = num(args, "x") ?: return emptyList()
        val y = num(args, "y") ?: return emptyList()
        return listOfNotNull(at(s, x, y)?.let { it to s })
    }

    /** Whether the names of the short elements spanning [y] vertically (text and buttons on one row) contain an irreversible word. */
    private fun rowHit(s: ScreenSnapshot, y: Int): Irreversible? =
        rowElements(s, y).filterNot { editsField(it, s) }.firstNotNullOfOrNull { e -> matchName(e.name, s, e)?.let { Irreversible(it, e.name.trim()) } }

    private fun rowElements(s: ScreenSnapshot, y: Int) = s.elements
        .filter { it.name.isNotBlank() && !it.editable && it.bounds.size == 4 && y in it.bounds[1]..it.bounds[3] && height(it) <= ROW_MAX_HEIGHT }

    private fun at(s: ScreenSnapshot, x: Int, y: Int): UiElement? {
        val under = s.elements.filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull(::area) ?: under.minByOrNull(::area)
    }

    /** An unnamed clickable container is judged together with the non-clickable text children inside it; non-clickable blank nodes (such as a full-screen root) are not expanded. */
    private fun withChildren(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.name.isNotBlank() || !e.clickable) return listOf(e)
        return listOf(e) + s.elements.filter { it !== e && !it.clickable && it.name.isNotBlank() && inside(it, e) }
    }

    /**
     * Enter, the confirm key and Space act on the focus: when the focus is an input field it always counts as send unless
     * the field is clearly a search box (Space is only typing and does not count); when the focus is a button it is judged
     * by the word lists; when the focus cannot be told, it passes only if every input field on the screen is a search box,
     * otherwise it counts as a hit. When type_text provides a ref, that field is the one acted on. The IME's "search"
     * Enter-key type cannot be read, so it is not used as evidence.
     */
    private fun enterHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?, key: Int): Irreversible? {
        val keyName = if (key == KEY_SPACE) "Space" else "Enter"
        val ref = args.str("ref")
        val field = if (ref == null) null
        else snapshot?.takeIf { it.snapshotId == args.str("snapshotId") }?.elements?.firstOrNull { it.ref == ref }
        val focused = field ?: s?.elements?.firstOrNull { it.focused }
        if (focused != null) {
            if (focused.editable) {
                if (key == KEY_SPACE || isSearch(focused, s ?: snapshot)) return null
                return Irreversible(IrrevAction.send, "$keyName:${label(focused)}")
            }
            return (s ?: snapshot)?.let { elementHit(focused, it) }
        }
        val fields = s?.elements?.filter { it.editable }.orEmpty()
        if (fields.isNotEmpty() && fields.all { isSearch(it, s) }) return null
        return Irreversible(IrrevAction.send, "$keyName: no clear focus")
    }

    /**
     * Recognize a search box from the element's own semantics: a search-box control identified by the port, search in its
     * role (the web searchbox, Android's search field control class), search wording in the hint (the one that survives
     * typed text, or the one currently displayed) or in the resource ID, or a clickable search button on the same row.
     * When the field has text, the name is the typed text ("help me search" is a message), so only the hint is checked for
     * the name. Enter in a search box performs a lookup that can be undone, so it is not send.
     */
    private fun isSearch(e: UiElement, s: ScreenSnapshot?): Boolean {
        // A send-style button on the same row means it is a messaging field (a chat box that says "search or ask"), so it is judged as send even when the hint says search
        if (s != null && ElementContext.row(e, s).any { b -> b.clickable && !b.editable && sendLike(b.name) }) return false
        if (e.searchField || "search" in e.role.lowercase()) return true
        val hint = Hant.fold(listOfNotNull(e.name.takeIf { e.hint }, e.placeholder).joinToString(" ")).lowercase()
        if (SEARCH_FIELD.any { it in hint } || e.resourceId?.let { "search" in resourceWords(it) } == true) return true
        if (s == null || e.bounds.size != 4) return false
        return s.elements.any { b ->
            b !== e && b.clickable && !b.editable && b.bounds.size == 4 &&
                Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase() in SEARCH_BUTTONS &&
                (b.bounds[1] + b.bounds[3]) / 2 in e.bounds[1]..e.bounds[3]
        }
    }

    /** The button text is send-style (send out, publish, post, send). */
    private fun sendLike(name: String): Boolean {
        val n = Hant.fold(name).filterNot(Char::isWhitespace).lowercase()
        if (n.isEmpty()) return false
        return PREFIX_WORDS.getValue(IrrevAction.send).any { n.startsWith(it) && n.length <= it.length + PREFIX_SLACK } || n == "send" || n == "post"
    }

    /**
     * A long enough sideways swipe starting on a list row (a wide clickable element that is not itself scrollable): many
     * apps delete a row this way. Screen reads carry no container orientation, so only short scrollable areas (carousels)
     * are recognized; a long swipe on a full-screen-high horizontal container is still blocked (the prompt tells the model
     * to use short swipes instead).
     */
    private fun swipeHit(args: JsonObject, s: ScreenSnapshot?): Irreversible? {
        val (sx, sy) = horizontal(args) ?: return null
        s ?: return null
        // The start point is inside a short scrollable area (a horizontal carousel), so it is paging, not deleting
        if (s.elements.any { it.scrollable && contains(it, sx, sy) && height(it) < CAROUSEL_MAX_HEIGHT }) return null
        val row = s.elements.filter { contains(it, sx, sy) && it.clickable && !it.scrollable && width(it) >= ROW_MIN_WIDTH }
            .minByOrNull(::area) ?: return null
        return Irreversible(IrrevAction.delete, "swipe on ${label(row)}")
    }

    /** The start point (0–999) of a long enough sideways swipe; null when it is not a sideways swipe or is too short. For scroll, the finger's start point is computed with the dispatcher's conversion. */
    private fun horizontal(args: JsonObject): Pair<Int, Int>? {
        if (args.containsKey("startX")) {
            val x1 = num(args, "startX") ?: return null; val y1 = num(args, "startY") ?: return null
            val x2 = num(args, "endX") ?: return null; val y2 = num(args, "endY") ?: return null
            val dx = abs(x2 - x1)
            return if (dx >= SWIPE_DELETE_MIN && dx > abs(y2 - y1)) x1 to y1 else null
        }
        val dir = args.str("direction") ?: return null
        if (dir != "left" && dir != "right") return null
        val dist = min(650, (num(args, "amount") ?: 3) * 100)
        if (dist < SWIPE_DELETE_MIN) return null
        val cx = num(args, "x") ?: 500; val cy = num(args, "y") ?: 500
        val dx = if (dir == "left") dist else -dist
        return max(50, min(949, (cx - dx / 2.0).roundToInt())) to cy
    }

    private fun key(args: JsonObject): Int? = args.str("key")?.let(PressKeys::resolve)

    private fun label(e: UiElement) = e.name.trim().ifEmpty { e.resourceId ?: e.ref }

    private fun center(e: UiElement): Pair<Int, Int>? =
        if (e.bounds.size != 4) null else (e.bounds[0] + e.bounds[2]) / 2 to (e.bounds[1] + e.bounds[3]) / 2

    private fun contains(e: UiElement, x: Int, y: Int) =
        e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]

    private fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }

    private fun height(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[3] - e.bounds[1]

    private fun width(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[2] - e.bounds[0]

    private fun area(e: UiElement): Long =
        if (e.bounds.size != 4) Long.MAX_VALUE else (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    /** Tolerates [a,b] the same way tap does (taking the midpoint). */
    private fun num(o: JsonObject, k: String): Int? {
        fun d(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val v = o[k]
        if (v is JsonArray && v.size == 2) {
            val a = d(v[0]); val b = d(v[1])
            if (a != null && b != null) return ((a + b) / 2).roundToInt()
        }
        return d(v)?.roundToInt()
    }

    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"
}
