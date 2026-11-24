package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.cap.web.cdp.CdpSession
import com.wangzan101.mika.core.cap.web.cdp.dispatchMouseEvent
import com.wangzan101.mika.core.cap.web.cdp.evaluate
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Device test: a web tab's DevTools session (direct, forced relay, forced off), the mapping between tabs and targets, desktop mode by default, and switching per site to mobile.
 * The relay case needs the built-in assistant running (run BuiltinHelperDeviceTest first); skipped if it is not.
 */
@RunWith(AndroidJUnit4::class)
class WebDevtoolsDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** This test's own copy: changing the session source or the connection method does not affect other tests. */
    private val devtools = WebDevtools(PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub::openDevtoolsSocket)
    private lateinit var host: WebHost
    private lateinit var pool: WebTabPool
    private var phoneWidth = 0

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        devtools.resetProvider()
        devtools.forceRoute = null
        devtools.resetRoute()
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        phoneWidth = bounds.width()
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()
            pool = WebTabPool(ctx, host, devtools = devtools, assetLoaders = listOf(main))
        }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            host.close()
        }
        devtools.setSiteMode("appassets.androidplatform.net", mobile = false)
    }

    private fun open(task: String = "t", url: String? = fixture("elements.html")): WebTab {
        val tab = runBlocking(Dispatchers.Main) { pool.open(task, null) }
        if (url != null) assertTrue(runBlocking { tab.load(url) })
        return tab
    }

    /** What the page itself sees (through the WebView's injection, not through DevTools). */
    private fun js(tab: WebTab, script: String): String = runBlocking(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun pageFacts(tab: WebTab): JsonObject {
        val raw = js(
            tab,
            "JSON.stringify({ua:navigator.userAgent,mobile:navigator.userAgentData?navigator.userAgentData.mobile:null," +
                "platform:navigator.userAgentData?navigator.userAgentData.platform:null,navPlatform:navigator.platform," +
                "iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,vvs:visualViewport?visualViewport.scale:null})",
        )
        // evaluateJavascript wraps a string result in one more layer of JSON
        return Json.parseToJsonElement(Json.parseToJsonElement(raw).jsonPrimitive.content).jsonObject.also { Log.i(TAG, "page facts: $it") }
    }

    private fun session(tab: WebTab): CdpSession? = runBlocking { devtools.awaitSession(tab, 5_000) }

    private fun eval(s: CdpSession, expr: String): String? =
        runBlocking { s.evaluate(expr, timeoutMs = 3_000)["value"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun sessionAttachesAndEvaluates() {
        val tab = open()
        val s = session(tab)
        assertNotNull("no devtools session (route ${devtools.route.value})", s)
        assertEquals(WebDevtools.Route.Direct, devtools.route.value)
        assertTrue(devtools.available.value)
        assertEquals("elements", eval(s!!, "document.title"))
        // The same tab reuses the same session
        assertTrue(s === devtools.session(tab))
        assertNotNull(devtools.targetId(tab))
        // The marker has been deleted after use
        assertEquals("\"undefined\"", js(tab, "typeof window.__mikaDevtoolsMark"))
    }

    @Test
    fun sameUrlTabsMapToTheirOwnTargets() {
        val a = open("t")
        val b = open("t")
        js(a, "window.__who='a'")
        js(b, "window.__who='b'")
        val sa = session(a)!!
        val sb = session(b)!!
        assertNotEquals(devtools.targetId(a), devtools.targetId(b))
        assertEquals("a", eval(sa, "window.__who"))
        assertEquals("b", eval(sb, "window.__who"))
        // The mapping survives navigation
        assertTrue(runBlocking { a.load(fixture("inputs.html")) })
        assertEquals("inputs", eval(sa, "document.title"))
        assertEquals("elements", eval(sb, "document.title"))
    }

    @Test
    fun desktopByDefault() {
        val tab = open()
        assertNotNull(session(tab))
        val f = pageFacts(tab)
        assertTrue(f["ua"]!!.jsonPrimitive.content, "X11; Linux x86_64" in f["ua"]!!.jsonPrimitive.content)
        assertFalse(f["mobile"]!!.jsonPrimitive.boolean)
        assertEquals("Linux", f["platform"]!!.jsonPrimitive.content)
        assertEquals("Linux x86_64", f["navPlatform"]!!.jsonPrimitive.content)
        assertTrue("innerWidth ${f["iw"]}", abs(f["iw"]!!.jsonPrimitive.double - 1280) <= 2)
        // The height is scaled up by the host's aspect ratio
        val expectH = 1280.0 * host.height / host.width
        assertTrue("innerHeight ${f["ih"]} vs $expectH", abs(f["ih"]!!.jsonPrimitive.double - expectH) <= 4)
    }

    @Test
    fun desktopTabsRenderOnADesktopSizeHost() {
        val phoneW = phoneWidth
        val tab = open()
        val s = session(tab)!!
        // Desktop mode: attached to a 1280×800 host display, one CSS pixel per view pixel, so Input coordinates match CSS
        assertEquals(1280, host.width)
        assertEquals(800, host.height)
        val viewW = runBlocking(Dispatchers.Main) { tab.view.width }
        assertEquals(1280, viewW)
        val f = pageFacts(tab)
        assertEquals(1280.0, f["iw"]!!.jsonPrimitive.double, 1.0)
        assertEquals(800.0, f["ih"]!!.jsonPrimitive.double, 2.0)
        Thread.sleep(500)
        val png = host.latestFramePng()!!
        val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        Log.i(TAG, "desktop host: inputScale=${s.inputScale} frame=${bmp.width}x${bmp.height} dpr=${f["dpr"]}")
        assertEquals(1280, bmp.width)
        assertEquals(800, bmp.height)
        // Switch to mobile: back to a host display the size of the main screen; then switch back to desktop
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        assertTrue(runBlocking { screen.setSiteMode(true) }!!.mobile)
        assertEquals(phoneW, host.width)
        assertEquals(1.0, s.inputScale, 1e-9)
        assertFalse(runBlocking { screen.setSiteMode(false) }!!.mobile)
        assertEquals(1280, host.width)
        assertEquals(1280.0, pageFacts(tab)["iw"]!!.jsonPrimitive.double, 1.0)
    }

    @Test
    fun tabAttachedOutsideThePoolFollowsItsMode() {
        val phoneW = phoneWidth
        lateinit var tab: WebTab
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            tab = WebTab(ctx, "solo", "solo-task", listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build()), devtools = devtools)
            host.attach(tab)
        }
        try {
            assertEquals(1280, host.width)
            devtools.setSiteMode("appassets.androidplatform.net", mobile = true)
            assertTrue(runBlocking { tab.load(fixture("elements.html")) })
            // Switching to mobile: even outside the tab pool, the view moves to a display the size of the main screen
            assertEquals(phoneW, host.width)
            assertTrue(pageFacts(tab)["iw"]!!.jsonPrimitive.double < 1000)
        } finally {
            instr.runOnMainSync { tab.destroy() }
        }
    }

    @Test
    fun tapsLandInDesktopLayout() {
        val tab = open()
        assertNotNull(session(tab))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        val snap = runBlocking { screen.snapshot() }
        val button = snap.elements.first { it.role == "button" && it.name == "按钮文字" }
        js(tab, "window.__hits=[];document.addEventListener('click',function(e){window.__hits.push((e.target.textContent||'').trim())},true)")
        val r = runBlocking { screen.tapRef(button.ref, snap.snapshotId) }
        assertFalse("tap failed: $r", r is ActionResult.Failed)
        val hits = js(tab, "JSON.stringify(window.__hits)")
        assertTrue("clicked $hits", "按钮文字" in hits)
        // The coordinates read from the screen lie inside the view (the full page width of 1280 CSS pixels is scaled to the view width)
        assertTrue(snap.elements.all { it.centerX in 0..host.width })
    }

    @Test
    fun siteModeSwitchesToMobileAndBack() {
        val tab = open()
        assertNotNull(session(tab))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        val m = runBlocking { screen.setSiteMode(true) }!!
        assertEquals("appassets.androidplatform.net", m.host)
        assertTrue(m.mobile)
        assertTrue(m.reloaded)
        val mf = pageFacts(tab)
        assertTrue(mf["mobile"]!!.jsonPrimitive.boolean)
        assertFalse("X11" in mf["ua"]!!.jsonPrimitive.content)
        assertTrue("innerWidth ${mf["iw"]}", mf["iw"]!!.jsonPrimitive.double < 1000)
        // Remembered: a new tab opening the same site is in mobile mode too
        val other = open()
        assertTrue(pageFacts(other)["mobile"]!!.jsonPrimitive.boolean)
        val d = runBlocking { screen.setSiteMode(false) }!!
        assertFalse(d.mobile)
        assertFalse(pageFacts(other)["mobile"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun relayWhenForced() {
        assumeTrue("phone helper is not running", PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext).hub.shell.value != null)
        devtools.forceRoute = WebDevtools.Route.Relay
        devtools.resetRoute()
        val tab = open()
        val s = session(tab)
        if (s == null) {
            // System policy may forbid the shell from connecting to the app's socket (observed on devices under SELinux enforcing): it must degrade cleanly
            assertEquals(WebDevtools.Route.None, devtools.route.value)
            assertNull(devtools.session(tab))
            assumeTrue("the system refused the helper's connection to the devtools socket (see avc in logcat)", false)
        }
        assertEquals(WebDevtools.Route.Relay, devtools.route.value)
        Log.i(TAG, "relay session attached via the phone helper")
        assertEquals("elements", eval(s!!, "document.title"))
        assertFalse(pageFacts(tab)["mobile"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun degradesWhenForcedOff() {
        devtools.forceRoute = WebDevtools.Route.None
        devtools.resetRoute()
        val tab = open()
        assertNull(session(tab))
        assertNull(devtools.session(tab))
        assertFalse(devtools.available.value)
        assertEquals(WebDevtools.Route.None, devtools.route.value)
        // Fallback: the desktop UA in the WebView settings is still there, so the page works as usual
        val f = pageFacts(tab)
        assertTrue("X11" in f["ua"]!!.jsonPrimitive.content)
        assertEquals("\"elements\"", js(tab, "document.title"))
        val screen = WebScreen(pool, "t", host, SystemClock::uptimeMillis)
        assertTrue(runBlocking { screen.snapshot() }.elements.isNotEmpty())
    }

    @Test
    fun closedTabDropsItsSession() {
        val tab = open()
        val s = session(tab)!!
        runBlocking(Dispatchers.Main) { pool.close(tab.id) }
        assertTrue(runBlocking { withTimeoutOrNull(3_000) { s.closed.first { it } } } == true)
        assertNull(devtools.session(tab))
    }

    @Test
    fun cdpMouseUsesCssPixelsUnderDesktopEmulation() {
        val tab = open()
        val s = session(tab)!!
        js(tab, "window.__p=[];document.addEventListener('pointerdown',function(e){window.__p.push(Math.round(e.clientX)+','+Math.round(e.clientY))},true)")
        runBlocking {
            s.dispatchMouseEvent("mousePressed", 640.0, 900.0, "left", 1)
            s.dispatchMouseEvent("mouseReleased", 640.0, 900.0, "left", 1)
        }
        Thread.sleep(200)
        assertEquals("\"[\\\"640,900\\\"]\"", js(tab, "JSON.stringify(window.__p)"))
    }

    private companion object {
        const val TAG = "WebDevtoolsTest"
    }
}
