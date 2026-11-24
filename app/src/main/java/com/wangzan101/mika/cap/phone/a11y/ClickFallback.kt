package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.core.safety.gate.IrreversibleDetector
import com.wangzan101.mika.core.screen.uistate.Hant
import kotlin.math.hypot

/**
 * Pure-logic criteria for the click-channel fallback:
 * - [mayBeOutward]: when the button text or resource ID looks like an outward or irreversible action such as send,
 *   submit, pay, delete or confirm, do not add a click on the second channel; better to try only once;
 * - [pickTarget]: when a coordinate tap has no effect, among the clickable targets containing (x, y) pick the
 *   smallest one whose area is within the share limit of the whole screen and switch to a node click.
 */
internal object ClickFallback {
    /**
     * When reverse-looking-up a clickable node from coordinates, the target area must not exceed this share of
     * the whole screen and its height not this share of the screen height (excludes full-screen or half-screen
     * background containers).
     */
    const val MAX_AREA_SHARE = 0.35
    const val MAX_HEIGHT_SHARE = 0.50
    private const val MAX_BUTTON_LABEL_LEN = 24

    private val CONFIRM_WORDS = setOf(
        "确认", "确定", "完成", "好", "好的", "允许", "同意",
        "ok", "okay", "confirm", "done", "yes", "allow", "agree",
    )
    private val OUTWARD_ID_WORDS = setOf(
        "send", "submit", "pay", "payment", "checkout", "order",
        "delete", "remove", "confirm", "post", "publish", "reply",
    )

    /**
     * Toggle-like buttons: a second click on the second channel would turn a just-enabled state back off
     * (favorite, follow, like, mute), or start it again (answer, dial). When a custom-drawn icon only changes color
     * the picture differs by fewer than 3 cells and the tree does not change either, so only the name can stop it.
     */
    private val STATEFUL_CJK = setOf("关注", "订阅", "收藏", "点赞", "喜欢", "静音", "接听", "拨打", "挂断")
    private val STATEFUL_EN = setOf(
        "follow", "following", "unfollow", "like", "liked", "favorite", "favourite",
        "subscribe", "subscribed", "mute", "muted",
    )
    private val ID_SPLIT = Regex("(?=[A-Z])|[/_\\-.:]+")

    /**
     * A clickable candidate containing a point: [item] is the caller's object, [left]/[top]/[right]/[bottom]
     * are pixel bounds clipped to the screen.
     */
    data class Candidate<T>(
        val item: T,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val label: String? = null,
        val resourceId: String? = null,
        val scrollable: Boolean = false,
        val webView: Boolean = false,
    ) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
        val area: Long get() = width.toLong() * height
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
        fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
    }

    /**
     * Returns true when the name or resource ID looks like a send, submit, pay, delete or confirm button, or a
     * toggle button; no automatic second-channel click then: better that this tap does nothing than to turn a
     * just-enabled state back off or repeat the action.
     * With [clickable] true (the default), [label] is looked up against the same kind of names as the
     * irreversible check before tapping (IrreversibleDetector's elementHit): the tapped node, its clickable
     * ancestors, the candidates, and for unnamed clickable containers the joined text of their children. Here, as
     * there, irreversible words ignore total length: English buttons often carry an object ("Send message to …"),
     * and container names often carry an amount ("Pay now ¥1,299.00 (incl. shipping…)").
     * Passing false is for non-clickable body text in the tapped area; only names of at most
     * [MAX_BUTTON_LABEL_LEN] characters are checked then.
     */
    fun mayBeOutward(label: String?, resourceId: String?, clickable: Boolean = true): Boolean {
        val trimmed = label?.trim().orEmpty()
        val short = trimmed.isNotEmpty() && trimmed.length <= MAX_BUTTON_LABEL_LEN
        if ((short || (clickable && trimmed.isNotEmpty())) && IrreversibleDetector.matchName(trimmed) != null) return true
        // Confirm words and English toggle words are compared as the whole string, Chinese toggle words by
        // containment (a toggle label followed by a count, e.g. "<label>(12)"), which shows up easily inside long body
        // text; both are only checked when short
        if (short) {
            val norm = Hant.fold(trimmed).filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
            if (norm in CONFIRM_WORDS) return true
            if (STATEFUL_CJK.any { it in norm } || norm in STATEFUL_EN) return true
        }
        if (!resourceId.isNullOrBlank()) {
            val tail = resourceId.substringAfterLast('/')
            val words = tail.split(ID_SPLIT).map { it.lowercase() }.filter { it.isNotEmpty() }
            if (words.any { it in OUTWARD_ID_WORDS || it in STATEFUL_EN }) return true
        }
        return false
    }

    /**
     * Picks from the candidates the smallest clickable target that contains ([x], [y]), is not a scrolling
     * container, not a WebView, not an outward action and not too large; among equal areas, the one whose center is
     * closest to ([x], [y]).
     */
    fun <T> pickTarget(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        candidates: Iterable<Candidate<T>>,
    ): T? {
        if (screenWidth <= 0 || screenHeight <= 0) return null
        val maxArea = (screenWidth.toLong() * screenHeight * MAX_AREA_SHARE).toLong()
        val maxHeight = (screenHeight * MAX_HEIGHT_SHARE).toInt()
        return candidates
            .filter { c ->
                !c.scrollable && !c.webView &&
                    c.width > 0 && c.height in 1..maxHeight &&
                    c.area in 1..maxArea &&
                    c.contains(x, y) &&
                    !mayBeOutward(c.label, c.resourceId)
            }
            .minWithOrNull(
                compareBy<Candidate<T>> { it.area }
                    .thenBy { hypot((it.centerX - x).toDouble(), (it.centerY - y).toDouble()) }
            )?.item
    }
}
