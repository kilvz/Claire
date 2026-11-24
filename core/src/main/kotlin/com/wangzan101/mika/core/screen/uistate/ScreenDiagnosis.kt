package com.wangzan101.mika.core.screen.uistate

enum class ScreenState(val wire: String) {
    NORMAL("normal"),
    /** No window, or no readable elements. */
    BLANK("blank"),
    /** The interface keeps changing; it has not finished loading. */
    LOADING("loading"),
    /** A screen-capture-protected page established by clear evidence. */
    PROTECTED("protected"),
    /** The picture stays unavailable; black pixels alone cannot prove capture protection versus a virtual display that failed to composite content. */
    CAPTURE_UNAVAILABLE("capture_unavailable"),
    /** A page that requires the user to handle it personally, such as login, verification codes, or payment. */
    NEEDS_HUMAN("needs_human"),
}

/**
 * [takeoverReason] is the reason suggested for hand_to_user; [cause] is a short note on what the verdict is based on.
 * Exception: [ScreenDiagnosis.UNSUPPORTED_BROWSER] (the site rejects the built-in browser) does not need a human; the
 * state is NORMAL, needsHuman is false, and the route should be changed instead.
 */
data class ScreenCheck(
    val state: ScreenState,
    val currentApp: String?,
    val needsHuman: Boolean,
    val takeoverReason: String? = null,
    val cause: String? = null,
)

/**
 * Facts the port collected. [blackFrames] holds, in time order, whether the picture was entirely black each time the
 * interface went quiet; empty when no picture is available.
 * [hasWindow] means there is an App window whose root node can be read; [anyWindow] means there is an App window (even
 * if its root node cannot be read).
 * [foregroundApp] is the foreground App learned from the focused window or the topmost task when windows cannot be
 * read; null when unknown.
 */
data class ScreenFacts(
    val currentApp: String?,
    val hasWindow: Boolean,
    val readableElements: Int,
    val changing: Boolean,
    val blackFrames: List<Boolean>,
    val texts: List<String>,
    val passwordField: Boolean,
    val anyWindow: Boolean = hasWindow,
    val foregroundApp: String? = null,
)

object ScreenDiagnosis {
    /** Not a single App window on the screen (this is the case right after a virtual display is created). */
    const val NO_WINDOW = "no window"
    const val UNREADABLE_WINDOW = "unreadable window"
    const val DISPLAY_CAPTURE_UNAVAILABLE = "display_capture_unavailable"
    const val DISPLAY_CAPTURE = "display_capture"
    /** Web: the site does not support the built-in browser (it demands another browser or an App download). */
    const val UNSUPPORTED_BROWSER = "unsupported_browser"

    private val CAPTCHA = listOf(
        "captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot",
    )
    private val PAYMENT = listOf(
        "支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now",
    )
    private val SMS_CODE = listOf(
        "获取验证码", "发送验证码", "短信验证码", "get code", "send code", "verification code",
    )

    /** The first payment wording in the on-screen text (confirm payment, payment password and the like); null if none. */
    fun paymentText(texts: List<String>): String? {
        val text = Hant.fold(texts.joinToString("\n")).lowercase()
        return PAYMENT.firstOrNull { it in text }
    }

    fun classify(f: ScreenFacts): ScreenCheck {
        val text = Hant.fold(f.texts.joinToString("\n")).lowercase()
        fun hit(words: List<String>) = words.firstOrNull { it in text }
        fun human(reason: String, cause: String) = ScreenCheck(ScreenState.NEEDS_HUMAN, f.currentApp, true, reason, cause)

        // The picture may also go briefly black during a page transition: treat it as loading while the interface is still changing
        if (f.changing && f.blackFrames.any { it }) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "black frame while the screen keeps changing")
        }
        val human = when {
            hit(CAPTCHA) != null -> "captcha" to "captcha text: ${hit(CAPTCHA)}"
            hit(PAYMENT) != null -> "payment" to "payment text: ${hit(PAYMENT)}"
            f.passwordField -> "login" to "password field"
            hit(SMS_CODE) != null -> "login" to "verification code text: ${hit(SMS_CODE)}"
            else -> null
        }
        // Black at both quiet checks means only that the picture is unusable. Black pixels cannot distinguish a secure window from failed virtual-display compositing.
        // If the tree suffices, degrade to the controls; only when the tree has no information either does the user need to take over on their own screen.
        if ((f.hasWindow || f.anyWindow || f.foregroundApp != null) && f.blackFrames.size >= 2 && f.blackFrames.all { it }) {
            val needsHuman = human != null || f.readableElements == 0
            return ScreenCheck(
                ScreenState.CAPTURE_UNAVAILABLE,
                f.currentApp ?: f.foregroundApp,
                needsHuman,
                if (needsHuman) human?.first ?: DISPLAY_CAPTURE else null,
                "frames stay black; secure window and display rendering failure cannot be distinguished",
            )
        }
        human?.let { (reason, cause) -> return human(reason, cause) }
        // Event activity alone is not evidence that usable content is still loading:
        // animations, timers and video can keep emitting events indefinitely.
        if (f.changing && (!f.hasWindow || f.readableElements == 0)) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "screen keeps changing without readable content")
        }
        if (!f.hasWindow || f.readableElements == 0) {
            val cause = when {
                f.hasWindow -> "no readable elements"
                f.anyWindow || f.foregroundApp != null -> UNREADABLE_WINDOW
                else -> NO_WINDOW
            }
            return ScreenCheck(ScreenState.BLANK, f.currentApp ?: f.foregroundApp, false, cause = cause)
        }
        return ScreenCheck(ScreenState.NORMAL, f.currentApp, false)
    }
}
