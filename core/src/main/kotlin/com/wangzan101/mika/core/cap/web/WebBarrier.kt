package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.ScreenCheck
import com.wangzan101.mika.core.screen.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.screen.uistate.ScreenFacts
import com.wangzan101.mika.core.screen.uistate.ScreenState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * Facts gathered on a web page: [base] is the same as on the phone (visible text, password fields, black frames…), plus the main document's HTTP status,
 * the URLs of frames in the page (cross-origin ones only) and the current page's host name.
 */
data class WebFacts(val base: ScreenFacts, val httpStatus: Int?, val frameUrls: List<String>, val host: String?)

/** Web barrier verdict: web-specific signals are checked first, then it falls back to the same [ScreenDiagnosis.classify] used on the phone. */
object WebBarrier {
    private val RISK_STATUS = setOf(403, 429, 503)
    private val RISK_WORDS = listOf("访问受限", "频繁", "异常", "blocked", "too many requests")

    /** Frame hosts of human-verification services (recognized by domain suffix). */
    private val CAPTCHA_HOSTS = listOf(
        "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "geetest.com", "arkoselabs.com", "funcaptcha.com",
    )
    /** Hosts that do not do verification themselves but distinguish it by path (host, path prefix). */
    private val CAPTCHA_PATHS = listOf("google.com" to "/recaptcha", "gstatic.com" to "/recaptcha")
    private val CAPTCHA_HOST_WORDS = listOf("captcha")

    /**
     * Wording found on risk-control interception pages (the site thinks this visit does not look human or the environment is off); appearing on its own is enough (whitespace is removed and case ignored when comparing).
     * It is only recognized when the page has little text: pages with long body text (articles discussing this kind of issue) do not count.
     */
    private val INTERSTITIAL_WORDS = listOf(
        "访问异常", "异常访问", "访问行为异常", "访问存在异常", "环境异常", "环境存在异常", "存在安全风险", "当前访问存在风险",
        "操作频繁", "操作过于频繁", "访问过于频繁", "请求过于频繁", "访问频繁",
        "unusualtraffic", "unusualactivity", "suspiciousactivity", "automatedqueries", "automatedrequests", "checkingyourbrowser",
        "accesstemporarilyrestricted", "requestwasblocked", "requesthasbeenblocked",
    )
    /** For a title alone (a security notice, a security check), the same page must also contain words such as unusual, verify or frequent. */
    private val INTERSTITIAL_TITLES = listOf("安全提示", "安全检查", "安全警告", "securitycheck", "securitynotice", "securityverification")
    private val INTERSTITIAL_CUES = listOf("异常", "验证", "频繁", "风险", "unusual", "verify", "suspicious", "robot", "automated")
    /** The caps for "the page has little text": total characters of visible text (whitespace removed), and the number of text items. */
    private const val INTERSTITIAL_MAX_CHARS = 800
    private const val INTERSTITIAL_MAX_TEXTS = 40

    /** Wording in API responses that says the request was stopped by risk control: the same kind as [INTERSTITIAL_WORDS], plus the spellings APIs commonly use. */
    private val API_RISK_WORDS = INTERSTITIAL_WORDS + listOf(
        "环境存在风险", "账号存在风险", "请求异常", "访问受限", "abnormal", "riskcontrol", "toomanyrequests", "ratelimit",
    )
    /** Only these top-level message fields of the response are looked at (compared in lowercase). */
    private val API_MESSAGE_KEYS = setOf("message", "msg", "errmsg", "errormsg", "error_msg", "errormessage", "error", "tip", "tips", "toast", "reason")
    private const val API_MESSAGE_MAX = 200

    /** Whitespace is removed when comparing: "please download the App to continue" is recognized the same with or without spaces. */
    private val UNSUPPORTED_WORDS = listOf(
        "请在浏览器中打开", "当前浏览器不支持", "不支持此浏览器", "请下载app继续", "browserisnotsupported", "browsernotsupported",
    )

    fun classify(f: WebFacts): ScreenCheck {
        // When the phone's own rules already decided a human is needed (password field, captcha wording, payment), that verdict stands
        val base = ScreenDiagnosis.classify(f.base)
        if (base.state == ScreenState.NEEDS_HUMAN) return base
        val text = f.base.texts.joinToString("\n").lowercase()
        val compact = text.filterNot(Char::isWhitespace)
        val app = f.base.currentApp ?: f.host
        if (f.httpStatus in RISK_STATUS) {
            RISK_WORDS.firstOrNull { it in text }?.let {
                return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "HTTP ${f.httpStatus} with text: $it")
            }
        }
        f.frameUrls.firstOrNull { captchaFrame(it, f.host) }?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "captcha", "verification frame from ${WebUrlPolicy.host(it)}")
        }
        interstitial(f.base.texts, compact)?.let {
            return ScreenCheck(ScreenState.NEEDS_HUMAN, app, true, "access_blocked", "risk-control page: \"$it\"")
        }
        UNSUPPORTED_WORDS.firstOrNull { it in compact }?.let {
            return ScreenCheck(ScreenState.NORMAL, app, false, ScreenDiagnosis.UNSUPPORTED_BROWSER,
                "the site refuses this embedded browser (\"$it\"): switch route to the app if it is installed, or end_not_done")
        }
        return base
    }

    /** Risk-control interception wording on a page with little text; null when there is none. */
    private fun interstitial(texts: List<String>, compact: String): String? {
        if (texts.size > INTERSTITIAL_MAX_TEXTS || compact.length > INTERSTITIAL_MAX_CHARS) return null
        INTERSTITIAL_WORDS.firstOrNull { it in compact }?.let { return it }
        val title = INTERSTITIAL_TITLES.firstOrNull { it in compact } ?: return null
        return INTERSTITIAL_CUES.firstOrNull { it in compact }?.let { "$title … $it" }
    }

    /**
     * Whether an API response the page itself received, [body], says it was stopped by risk control: one of the JSON object's top-level message fields (message, msg, error…) carries that kind of wording.
     * Returns that wording (truncated); null when it is not JSON, has no such field, or does not say so.
     */
    fun apiRisk(body: String?): String? {
        val text = body?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        for ((k, v) in o) {
            if (k.lowercase() !in API_MESSAGE_KEYS) continue
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val compact = s.lowercase().filterNot(Char::isWhitespace)
            if (compact.length > API_MESSAGE_MAX) continue
            if (API_RISK_WORDS.any { it in compact }) return s.trim().take(API_MESSAGE_MAX)
        }
        return null
    }

    /** A frame that is cross-origin (its host differs from the page's) and points at a human-verification service. */
    private fun captchaFrame(url: String, pageHost: String?): Boolean {
        val h = WebUrlPolicy.host(url) ?: return false
        if (pageHost != null && h == pageHost.lowercase().removePrefix("www.")) return false
        fun under(d: String) = h == d || h.endsWith(".$d")
        val path = runCatching { URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return CAPTCHA_HOSTS.any(::under) || CAPTCHA_PATHS.any { (d, p) -> under(d) && path.startsWith(p) } ||
            CAPTCHA_HOST_WORDS.any { it in h }
    }
}
