package com.wangzan101.mika.cap.web

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.cdp.CdpDiscovery
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import com.wangzan101.mika.cap.phone.PhoneServices

/**
 * Device test: with a DevTools session, reading the screen merges the page script's element table with the accessibility tree.
 * The test page (fusion.html) has a plain button, a div with only a listener, open and closed shadow buttons, and buttons inside a same-origin frame and a cross-origin frame
 * (the cross-origin frame is served under a second asset domain, fixtures2.androidplatform.net, without touching the network; plaintext HTTP to the local loopback is blocked by the network security policy and is unusable).
 * DevTools session: when the product's entry point cannot provide one, the test connects itself through this process's abstract socket (finding the target by marking the page). Skipped if it cannot connect.
 */
@RunWith(Parameterized::class)
class ElementFusionDeviceTest(private val mobile: Boolean) {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** This test's own copy: changing the session source or the connection method does not affect other tests. */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var tab: WebTab
    private lateinit var screen: WebScreen
    private var session: CdpSession? = null
    private val savedProvider = devtools.provider

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            WebView.setWebContentsDebuggingEnabled(true)
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            val other = WebViewAssetLoader.Builder().setDomain("fixtures2.androidplatform.net").addPathHandler("/assets/", assets).build()
            tab = WebTab(ctx, "t1", "task-1", listOf(main, other), devtools = devtools)
            host.attach(tab)
        }
        screen = WebScreen({ tab }, host, SystemClock::uptimeMillis)
        // Runs once in desktop mode (emulated wide viewport, Input coordinates carry an extra multiplier) and once in mobile mode
        devtools.setSiteMode(SITE, mobile)
        runBlocking {
            assertTrue("load", tab.load(fixture("fusion.html")))
            // If the test page's own script fails partway through, later assertions would be misreported as product bugs: first confirm it finished
            assertTrue("fixture script did not finish", js("window.__ready === true") == "true")
        }
        if (runBlocking { devtools.awaitSession(tab) } == null && devtools.session(tab) == null) {
            session = runBlocking { connectOwn() }
            devtools.provider = { t -> if (t === tab) session else savedProvider(t) }
        }
        assumeTrue("no DevTools session for the tab", devtools.session(tab) != null)
        // Only the product's own session has a site mode: confirm this run really uses the mode under test
        if (session == null) {
            val vw = runBlocking { js("innerWidth") }.toDouble()
            assertTrue("mobile=$mobile innerWidth=$vw", if (mobile) vw < 1000 else vw >= 1000)
        }
    }

    @After
    fun tearDown() {
        devtools.setSiteMode(SITE, mobile = false)
        session?.close()
        instr.runOnMainSync {
            tab.destroy()
            host.close()
        }
    }

    @Test
    fun mergedSnapshotListsShadowFrameAndListenerElements() = runBlocking<Unit> {
        val s = readFused()
        val plain = s.named("Plain")
        val lis = s.named("Listener only")
        assertTrue("监听器佐证可点:$lis", lis.clickable && lis.role == "button")
        s.named("Shadow")
        s.named("Closed shadow")
        s.named("SameChild")
        val cross = s.named("CrossChild")
        assertTrue("$cross", cross.clickable && cross.role == "button")
        assertNotNull("跨源框架里的元素登记了节点号", screen.backendNodeId(cross.ref, s.snapshotId))
        assertNotNull(screen.backendNodeId(plain.ref, s.snapshotId))
        // The center point agrees with what was measured in the page (the button inside the cross-origin frame starts at 10,10 in the frame and is 120x40)
        val css = JSONObject(js("(function(){var f=document.getElementById('cross').getBoundingClientRect();return {x:f.left+70,y:f.top+30,vw:innerWidth};})()"))
        val k = s.width / css.getDouble("vw")
        val ex = (css.getDouble("x") * k).toInt()
        val ey = (css.getDouble("y") * k).toInt()
        assertTrue("cross center ${cross.centerX},${cross.centerY} expected ~$ex,$ey", abs(cross.centerX - ex) <= 8 && abs(cross.centerY - ey) <= 8)
        s.elements.forEach { assertTrue("$it", it.centerX in 0 until s.width && it.centerY in 0 until s.height) }
    }

    @Test
    fun tapsReachShadowAndCrossOriginElements() = runBlocking<Unit> {
        for ((name, logged) in listOf("CrossChild" to "xclick", "Closed shadow" to "closed", "Shadow" to "shadow", "Listener only" to "lis")) {
            val s = readFused()
            val r = screen.tapRef(s.named(name).ref, s.snapshotId)
            assertTrue("$name: $r", r is ActionResult.Ok)
            val log = waitLog(logged)
            assertTrue("$name: log=$log", logged in log)
        }
    }

    @Test
    fun fusionStaysWithinBudget() = runBlocking<Unit> {
        screen.snapshot()
        val times = (1..5).map {
            val t = SystemClock.elapsedRealtime()
            screen.snapshot()
            SystemClock.elapsedRealtime() - t
        }
        Log.i(TAG, "snapshot with fusion ms=$times")
        assertTrue("$times", times.max() < 2_500)
    }

    /** Read back only once the cross-origin frame has loaded and its button is in the merged result; waits at most 5 seconds. */
    private suspend fun readFused(): ScreenSnapshot {
        val end = SystemClock.elapsedRealtime() + 5_000
        var s = screen.snapshot()
        while (s.elements.none { it.name == "CrossChild" } && SystemClock.elapsedRealtime() < end) {
            delay(200)
            s = screen.snapshot()
        }
        Log.i(TAG, "elements: ${s.elements.map { "${it.ref} ${it.role} ${it.name} (${it.centerX},${it.centerY})" }}")
        return s
    }

    private fun ScreenSnapshot.named(name: String): UiElement {
        val e = elements.firstOrNull { it.name == name }
        assertNotNull("$name not in ${elements.map { it.role to it.name }}", e)
        return e!!
    }

    private suspend fun waitLog(want: String): String {
        val end = SystemClock.elapsedRealtime() + 3_000
        var log = ""
        while (SystemClock.elapsedRealtime() < end) {
            log = js("JSON.stringify(window.__log)")
            if (want in log) return log
            delay(100)
        }
        return log
    }

    private suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    /** This process's WebView DevTools abstract socket; marks the page and evaluates against each target to find this tab. */
    private suspend fun connectOwn(): CdpSession? {
        val socket = "webview_devtools_remote_${Process.myPid()}"
        val transport = LocalTransport()
        val marker = UUID.randomUUID().toString()
        js("window.__fusionMarker='$marker'")
        val targets = runCatching { CdpDiscovery.list(transport, socket) }.getOrElse {
            Log.w(TAG, "devtools socket unavailable", it)
            return null
        }
        for (t in targets) {
            val ws = t.webSocketDebuggerUrl ?: continue
            if (t.type != "page") continue
            val s = runCatching { CdpSession.connect(transport, socket, CdpDiscovery.webSocketPath(ws)) }.getOrNull() ?: continue
            val v = runCatching { s.evaluate("window.__fusionMarker||''", timeoutMs = 3_000)["value"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (v == marker) return s
            s.close()
        }
        return null
    }

    private class LocalTransport : CdpTransport {
        override fun open(endpoint: String): CdpStream {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
            return object : CdpStream {
                override val input: InputStream = s.inputStream
                override val output: OutputStream = s.outputStream
                override fun close() {
                    runCatching { s.shutdownInput() }
                    runCatching { s.close() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ElementFusionTest"
        private const val SITE = "appassets.androidplatform.net"

        @JvmStatic
        @Parameterized.Parameters(name = "mobile={0}")
        fun modes() = listOf(false, true)
    }
}
