package com.wangzan101.mika.cap.web

import android.util.Log
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.util.concurrent.Executors

/** Login state observed on the page. */
enum class LoginState { SignedIn, SignedOut, Unknown }

/**
 * Page signs used to judge login state, taken only from what is visible in the account area at the top of the page (header, nav bar, banner, or the top strip of the page):
 * [url] the current address; [passwordVisible] whether a visible password field is on the page; [account] the text of visible links and buttons in the account area;
 * [avatars] visible avatars in the account area (those in comments, lists or articles do not count).
 */
data class LoginSignals(
    val url: String?,
    val passwordVisible: Boolean,
    val account: List<String>,
    val avatars: Int,
)

/**
 * Generic login-state judgement, not tied to any particular site:
 * - Signed in: the account area shows a logout-style link (an entry of [LOGOUT_EXACT] or [LOGOUT], in Chinese or English; the whole text is that, or starts with it), or an account entry like [ACCOUNT],
 *   and no visible sign-in entry;
 * - Signed out: a visible password field, or a sign-in entry ([SIGN_IN]) visible in the account area with neither an account entry nor an avatar;
 * - Avatars are only used to rule out "signed out"; on their own they can never make the state signed in; when the address looks like a login page (SSO, midway through a passport redirect) no conclusion is drawn.
 * Everything else is [LoginState.Unknown]: the registry is left untouched.
 */
object LoginProbe {
    private val LOGIN_PATH = Regex("""(^|[/_.-])(log-?in|sign-?in|sign_in|logon|passport|sso|oauth|authorize|auth/login|account/login|user/login)([/_.?#-]|$)""", RegexOption.IGNORE_CASE)
    /** Counts only when the whole text is it (too common; text starting with it usually means something else, e.g. "exit fullscreen"). */
    private val LOGOUT_EXACT = setOf("退出", "注销", "logout", "signout")
    /** The whole text is it, or starts with it (followed by at most [LOGOUT_SLACK] more characters). */
    private val LOGOUT = listOf("退出登录", "退出账号", "安全退出", "注销登录", "log out", "sign out", "log off")
    /** Text containing any of these is not a logout link (descriptions of account deletion, help pages). */
    private val NOT_LOGOUT = listOf("说明", "帮助", "须知", "协议", "账号注销", "注销账号", "注销账户", "help", "how to", "delete account")
    private val SIGN_IN = listOf("登录", "登入", "请登录", "立即登录", "登录/注册", "注册/登录", "登录 / 注册", "注册 / 登录", "log in", "login", "sign in", "signin", "sign in / register", "sign up / log in")
    private val ACCOUNT = listOf("个人中心", "我的账号", "我的账户", "账号设置", "账户设置", "my account", "your account", "account settings")
    /** At most this many more characters may follow a "logout"-style word and still count (e.g. "log out >"; "Log out of Example" does not count, being too long and reading like a sentence). */
    private const val LOGOUT_SLACK = 4

    fun classify(s: LoginSignals): LoginState {
        val texts = s.account.map(::norm)
        val logout = texts.any(::isLogout)
        val account = texts.any { t -> ACCOUNT.any { t == it || t.startsWith(it) } }
        val signIn = texts.any { it in SIGN_IN }
        // Login, SSO and authorization pages are mostly mid-redirect: draw no conclusion, leave the registry untouched
        if (loginUrl(s.url)) return LoginState.Unknown
        if (s.passwordVisible && !logout) return LoginState.SignedOut
        if ((logout || account) && !signIn && !s.passwordVisible) return LoginState.SignedIn
        if (signIn && !logout && !account && s.avatars == 0) return LoginState.SignedOut
        return LoginState.Unknown
    }

    private fun isLogout(t: String) =
        NOT_LOGOUT.none { t.contains(it) } &&
            (t in LOGOUT_EXACT || LOGOUT.any { w -> t == w || (t.startsWith(w) && t.length <= w.length + LOGOUT_SLACK) })

    /** The address is a login, SSO or authorization page: judged by the path (a login mentioned in the query string does not count). */
    fun loginUrl(url: String?): Boolean {
        val path = url?.let { runCatching { URI(it.trim()).rawPath }.getOrNull() } ?: return false
        return LOGIN_PATH.containsMatchIn(path)
    }

    private fun norm(t: String) = t.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Parses the result [SCRIPT] returns through evaluateJavascript (a JSON string literal); null if it cannot be read. */
    fun parse(raw: String?): LoginSignals? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        return LoginSignals(
            url = (o["url"] as? JsonPrimitive)?.contentOrNull,
            passwordVisible = (o["pw"] as? JsonPrimitive)?.booleanOrNull == true,
            account = (o["account"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            avatars = (o["avatars"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * Read-only script that collects the signs in the page's main frame: it only reads the DOM, does not modify the page or send requests. It walks at most [SCAN_BUDGET] elements in document order;
     * the account area is what lies in the header / nav / banner, or whose top edge is within the top 160 pixels of the page; avatars in comments, list items and articles do not count.
     */
    val SCRIPT = """(function(){try{
var out={url:location.href,pw:false,account:[],avatars:0},budget=$SCAN_BUDGET;
function vis(e){var r=e.getBoundingClientRect();if(r.width<2||r.height<2)return null;var c=getComputedStyle(e);return c.display!=='none'&&c.visibility!=='hidden'&&+c.opacity>0.05?r:null;}
function top(e,r){return r.top+scrollY<160||!!e.closest('header,nav,[role=banner],[role=navigation]');}
var w=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_ELEMENT),e,n=0;
while((e=w.nextNode())&&n++<budget){var t=e.tagName,r;
if(t==='INPUT'){var y=(e.getAttribute('type')||'').toLowerCase();if(y==='password'&&!out.pw&&vis(e))out.pw=true;if(y!=='submit'&&y!=='button')continue;}
if(t==='A'||t==='BUTTON'||t==='INPUT'||e.getAttribute('role')==='button'||e.getAttribute('role')==='link'||e.getAttribute('role')==='menuitem'){
if(out.account.length>=60)continue;var s=String(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||'').replace(/\s+/g,' ').trim();
if(s&&s.length<=24&&(r=vis(e))&&top(e,r))out.account.push(s);continue;}
if(t==='IMG'&&out.avatars<3){var k=(e.className&&typeof e.className==='string'?e.className:'')+' '+(e.getAttribute('alt')||'')+' '+(e.getAttribute('src')||'');
if(/avatar|头像/i.test(k)&&(r=vis(e))&&top(e,r)&&!e.closest('article,li,[class*=comment],[class*=list],[class*=feed]'))out.avatars++;}}
return JSON.stringify(out);}catch(x){return null;}})()"""

    private const val SCAN_BUDGET = 4000
    private const val TAG = "MikaLoginProbe"
    /** Parsing, judging and writing the registry all run on it, so the main thread is not taken up. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-login-probe").apply { isDaemon = true } }

    /**
     * Called on the main thread: takes a look at the login state of the current page in [tab] (the script runs inside the page); parsing and judging are done on the background thread, and the callback (site host name, state) also runs on that background thread.
     * There is no callback if the tab is no longer usable, is still loading, the result cannot be read, or the tab has already moved to another site when the result is read.
     */
    fun probe(tab: WebTab, onResult: (String, LoginState) -> Unit) {
        if (tab.destroyed || tab.crashed || tab.loading) return
        val host = tab.view.url?.takeIf { it.startsWith("http") }?.let(WebUrlPolicy::host) ?: return
        tab.view.evaluateJavascript(SCRIPT) { raw ->
            worker.execute {
                val signals = parse(raw) ?: return@execute
                if (signals.url?.let(WebUrlPolicy::host) != host) return@execute
                val state = classify(signals)
                Log.i(TAG, "login probe $host: $state")
                onResult(host, state)
            }
        }
    }
}
