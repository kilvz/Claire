package com.wangzan101.mika.settings

import com.wangzan101.mika.R
import com.wangzan101.mika.core.oauth.AntigravitySignInFailure
import com.wangzan101.mika.i18n.tr

/** Antigravity 服务商在界面上的账号;没有这家时为 null。 */
data class AntigravityAccount(val email: String, val signedIn: Boolean)

/**
 * 用户填的 Antigravity OAuth 客户端(Client ID、Client Secret)。
 * 登录前就得有,存在单独的小文件里、整份加密(见 [ProviderStore.antigravityClient]);
 * 登录成功后这对值也写进登录里,退出登录后留着,再登录不用重填。
 */
data class AntigravityCredentials(val clientId: String, val clientSecret: String) {
    /** 两项都填了,「Sign in with Google」才能开始。 */
    val complete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

/** 用 Google 账号登录 Antigravity 没成的原因(界面和回调网页共用)。 */
fun antigravityFailure(reason: AntigravitySignInFailure, code: String? = null): String = when (reason) {
    AntigravitySignInFailure.Denied -> tr(R.string.antigravity_fail_denied)
    AntigravitySignInFailure.StateMismatch -> tr(R.string.antigravity_fail_mismatch)
    AntigravitySignInFailure.NoClient -> tr(R.string.antigravity_fail_no_client)
    AntigravitySignInFailure.TimedOut -> tr(R.string.antigravity_fail_timed_out)
    AntigravitySignInFailure.Exchange -> tr(R.string.antigravity_fail_exchange, code?.takeIf { it.isNotBlank() } ?: "unknown")
    AntigravitySignInFailure.CodeExpired -> tr(R.string.antigravity_fail_code_expired)
    AntigravitySignInFailure.Blocked -> tr(R.string.antigravity_fail_blocked)
    AntigravitySignInFailure.Network -> tr(R.string.antigravity_fail_network)
}
