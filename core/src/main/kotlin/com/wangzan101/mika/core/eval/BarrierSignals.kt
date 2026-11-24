package com.wangzan101.mika.core.eval

/**
 * Barriers that are visible on screen: the wording of login walls and human verification respectively (login dialog, verification code entry, sign-in-again button…).
 * The evaluation honors a barrier reported by the model ([RunFacts.takeoverBarrier]) only when such wording is really on screen, not just because the model says so.
 */
object BarrierSignals {
    private val SIGNALS = mapOf(
        "login" to listOf(
            "获取验证码", "发送验证码", "短信验证码", "请输入密码", "密码登录", "验证码登录", "手机号登录", "扫码登录",
            "重新登录", "欢迎登录", "登录后查看", "sign in", "log in",
        ),
        "captcha" to listOf("人机验证", "安全验证", "滑块验证", "拖动滑块", "请完成验证", "验证您是真人", "captcha", "verify you are human", "not a robot"),
    )

    /** Whether [texts] (the element texts of one screen) contain wording for a [kind] barrier; false for unknown barrier kinds. */
    fun shows(kind: String, texts: List<String>): Boolean {
        val words = SIGNALS[kind] ?: return false
        val text = texts.joinToString("\n").lowercase()
        return words.any { it in text }
    }
}
