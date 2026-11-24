package com.wangzan101.mika.eval

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.eval.APP_CAPABILITIES
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.PROBE_ALLOWLIST
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.cap.web.PageScript
import com.wangzan101.mika.cap.web.WebHost
import com.wangzan101.mika.cap.web.WebSites
import com.wangzan101.mika.cap.web.WebTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The evaluator's own read-only view of the device: allowlisted probes, preconditions, and opening an app on a virtual display after a task finishes to look for text.
 * [sh] runs commands as the shell user; [loggedIn] is the file listing packages manually marked as "signed in" (one package name per line).
 */
class EvalProbes(private val sh: (String) -> String, private val loggedIn: File) {

    /** Only executes the commands in [PROBE_ALLOWLIST]; a task file cannot inject arbitrary commands. */
    fun run(name: String): String {
        val cmd = PROBE_ALLOWLIST[name] ?: error("probe not in allowlist: $name")
        return sh(cmd).trim()
    }

    /**
     * Apps on this phone that can handle a kind of work (see [APP_CAPABILITIES]): looked up by standard Intent, with the default handler listed first.
     * The commands are composed only from the capability table; a task file cannot inject any. Returns empty for a capability without a standard Intent.
     */
    fun appCandidates(capability: String): List<String> = APP_CAPABILITIES[capability].orEmpty()
        .flatMap { i -> AppCapabilities.commands(i).flatMap { cmd -> AppCapabilities.packagesIn(runCatching { sh(cmd) }.getOrDefault("")) } }
        .distinct()

    /**
     * `installed:<package>`, `app:<capability>`, `signedIn:<package-or-site>` (legacy forms `login:<package>`, `site:<host>`).
     * Login state: websites only count when the site registry says "the user signed in inside Claire" (signed in under the settings item "Website logins"); apps only count when manually marked ([loggedIn]),
     * and the evaluator does not judge an app's login state on the user's behalf (no probe is reliable for every app). Tasks that are not signed in are skipped, with the reason recorded as "not signed in".
     */
    fun requirementMet(req: String): Pair<Boolean, String> {
        val kind = req.substringBefore(':')
        val pkg = req.substringAfter(':', "")
        when (kind) {
            "site" -> return siteSignedIn(pkg)?.let { if (it) true to "" else false to "未登录:$pkg 没有在设置「网站登录」里登录过" }
                ?: (false to "前置条件写法不对:$req")
            "signedIn" -> return if (siteSignedIn(pkg) == true || appMarked(pkg)) true to ""
                else false to "未登录:$pkg(网站要在设置「网站登录」里登录过,App 要标记在 ${loggedIn.name} 里)"
            "app" -> return if (appCandidates(pkg).isNotEmpty()) true to "" else false to "这台手机上没有能处理「$pkg」的 App"
        }
        if (!PACKAGE.matches(pkg)) return false to "前置条件写法不对:$req"
        return when (kind) {
            "installed" ->
                if (sh("pm path $pkg").contains("package:")) true to "" else false to "没装 $pkg"
            "login" -> if (appMarked(pkg)) true to "" else false to "未登录:$pkg 没有标记为已登录(${loggedIn.name})"
            else -> false to "不认识的前置条件:$req"
        }
    }

    /** Apps manually marked as signed in (one package name per line). */
    private fun appMarked(pkg: String) = runCatching { loggedIn.readLines().map { it.trim() } }.getOrDefault(emptyList()).contains(pkg)

    /** The website was signed in under the settings item "Website logins": only the same registrable domain counts (when the registrable domain cannot be determined, the host names must match exactly); null when the value is not a website. */
    private fun siteSignedIn(site: String): Boolean? {
        val host = WebUrlPolicy.host("https://$site") ?: return null
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val want = WebUrlPolicy.registrable(host) ?: host
        return WebSites.registry(ctx).list().any { it.loggedInByUser && (WebUrlPolicy.registrable(it.host) ?: it.host) == want }
    }

    /** Cold-starts [pkg] on a fresh virtual display (or opens a deep link with it) and reads the screen until [text] is seen or the timeout expires. */
    suspend fun screenHas(pkg: String, text: String, deepLink: String?): Boolean {
        require(PACKAGE.matches(pkg)) { "bad package: $pkg" }
        val shell = DeviceSupport.shell()
        val metrics = DisplayMetrics().also {
            InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val display = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val screen = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display))
            if (deepLink == null) {
                if (screen.openApp(pkg, cold = true) !is ActionResult.Ok) return false
            } else {
                // The link is passed to am as data only; it must not carry quotes that could compose other commands
                require('\'' !in deepLink && '\n' !in deepLink) { "bad deep link" }
                sh("am start --display ${display.displayId} -a android.intent.action.VIEW -d '$deepLink' -p $pkg")
            }
            val want = text.replace(WS, "")
            val end = System.currentTimeMillis() + SCREEN_WAIT_MS
            while (true) {
                val seen = screen.snapshot().elements.joinToString("") { it.name }.replace(WS, "")
                if (want in seen) return true
                if (System.currentTimeMillis() > end) return false
                delay(500)
            }
        } finally {
            display.release()
        }
    }

    /**
     * Opens [url] in a new background web tab, reads the whole page text (at most [WEB_PAGES] chunks), and looks for [text] (whitespace ignored).
     * Uses the mika web profile, so the login state is the same as the Agent's.
     */
    suspend fun webHas(url: String, text: String): Boolean {
        require(WebUrlPolicy.checkNavigable(url) == null) { "bad url: $url" }
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val metrics = DisplayMetrics().also {
            ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        lateinit var host: WebHost
        lateinit var tab: WebTab
        withContext(Dispatchers.Main) {
            host = WebHost(ctx, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            tab = WebTab(ctx, "probe", "eval")
            host.attach(tab)
        }
        try {
            tab.load(url)
            val page = PageScript(tab)
            page.install()
            val want = text.replace(WS, "")
            var offset = 0
            repeat(WEB_PAGES) {
                val o = runCatching { Json.parseToJsonElement(page.readPage(30_000, offset)).jsonObject }.getOrNull() ?: return false
                val got = (o["text"] as? JsonPrimitive)?.content.orEmpty().replace(WS, "")
                if (want in got) return true
                offset = (o["nextOffset"] as? JsonPrimitive)?.intOrNull ?: return false
            }
            return false
        } finally {
            withContext(Dispatchers.Main + NonCancellable) {
                tab.destroy()
                host.close()
            }
        }
    }

    private companion object {
        const val WEB_PAGES = 5
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val WS = Regex("\\s+")
        const val SCREEN_WAIT_MS = 10_000L
    }
}
