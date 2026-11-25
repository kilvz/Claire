package com.wangzan101.mika.cap.web

import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.cap.web.PageRead
import com.wangzan101.mika.core.run.ResourceScope
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.Collections
import kotlin.coroutines.resume

/** On-device test: tab pool navigation, popups, external navigations, permissions, crashes, cookie retention, memory trimming, and task isolation. Needs no built-in assistant and does not touch the main screen. */
@RunWith(AndroidJUnit4::class)
class WebTabPoolTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var pool: WebTabPool
    private val events: MutableList<PoolEvent> = Collections.synchronizedList(mutableListOf())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            host = WebHost(ctx, bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi)
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            // /status/403: a page that returns 403, used to test the main document's status
            val status = WebViewAssetLoader.PathHandler { path ->
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream("<title>forbidden</title>访问受限".toByteArray()))
                    .apply { setStatusCodeAndReasonPhrase(path.toIntOrNull() ?: 200, "Status") }
            }
            val main = WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).addPathHandler("/status/", status).build()
            val other = WebViewAssetLoader.Builder().setDomain("fixtures2.androidplatform.net").addPathHandler("/assets/", assets).build()
            // Another registrable domain: subdomains under androidplatform.net are same-site with appassets
            val foreign = WebViewAssetLoader.Builder().setDomain("fixtures.mika-other.test").addPathHandler("/assets/", assets).build()
            pool = WebTabPool(ctx, host, assetLoaders = listOf(main, other, foreign))
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { pool.events.collect { events += it } }
    }

    @After
    fun tearDown() {
        scope.cancel()
        instr.runOnMainSync {
            pool.close()
            host.close()
        }
    }

    private fun screen(task: String) = WebScreen(pool, task, host, SystemClock::uptimeMillis)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun current(task: String) = runBlocking(Dispatchers.Main) { pool.current(task) }

    private inline fun <reified T : PoolEvent> awaitEvent(timeoutMs: Long = 5_000, crossinline test: (T) -> Boolean = { true }): T {
        val found = DeviceSupport.pollUntil(timeoutMs) { synchronized(events) { events.filterIsInstance<T>().firstOrNull { test(it) } } }
        assertNotNull("no ${T::class.simpleName} in $events", found)
        return found!!
    }

    @Test
    fun navigateReportsFinalUrlTitleAndStatus() = runBlocking<Unit> {
        val s = screen("A")
        val r = s.navigate(fixture("redirect.html"), newTab = false)
        assertTrue(r.loaded)
        assertEquals(fixture("host.html?redirected=1"), r.tab.url)
        assertEquals("host", r.tab.title)
        assertEquals(200, r.httpStatus)
        assertNull(r.externalLink)
        val forbidden = s.navigate("https://appassets.androidplatform.net/status/403", newTab = false)
        assertEquals(403, forbidden.httpStatus)
        assertFalse(forbidden.newTab)
    }

    @Test
    fun parallelChildrenKeepTheirCurrentPagesAfterAnotherChildNavigates() = runBlocking<Unit> {
        val parent = screen("parent")
        val firstResources = ResourceScope()
        val secondResources = ResourceScope()
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
        val density = ctx.resources.displayMetrics.densityDpi
        val first = WebStack.childPort(ctx, "child-a", host.width, host.height, density, firstResources, listOf(loader))
        val second = WebStack.childPort(ctx, "child-b", host.width, host.height, density, secondResources, listOf(loader))
        val currentPage = PageRead(maxChars = 2_000, offset = 0, lazy = false)
        try {
            assertTrue(parent.navigate(fixture("host.html"), newTab = false).loaded)
            assertTrue(first.navigate(fixture("digest.html"), newTab = false).loaded)
            assertTrue(second.navigate(fixture("elements.html"), newTab = false).loaded)

            assertEquals(fixture("digest.html"), first.read(currentPage).url)
            assertEquals(fixture("elements.html"), second.read(currentPage).url)
            assertEquals(fixture("host.html"), parent.read(currentPage).url)

            firstResources.close()
            assertTrue(first.tabs().isEmpty())
            assertEquals(fixture("elements.html"), second.read(currentPage).url)
        } finally {
            secondResources.close()
            firstResources.close()
        }
    }

    @Test
    fun popupOpensNewTabAndSwitches() = runBlocking<Unit> {
        val s = screen("A")
        val first = s.navigate(fixture("popup.html"), newTab = false).tab.tabId
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#one" }.ref, snap.snapshotId)
        val opened = awaitEvent<PoolEvent.NewTab> { it.taskId == "A" && it.fromPopup }
        assertEquals(2, s.tabs().size)
        assertEquals(opened.tabId, s.tabs().single { it.current }.tabId)
        DeviceSupport.pollUntil(5_000) { runBlocking(Dispatchers.Main) { pool.current("A")?.view?.url }?.takeIf { it.endsWith("host.html") } }
        assertTrue(s.tabs().any { it.tabId == first && !it.current })
    }

    @Test
    fun popupStormCappedAt4() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        // Each gesture lets only one window.open through: tap six times in a row instead of opening six at once
        repeat(6) {
            val again = s.snapshot()
            s.tapRef(again.elements.first { it.resourceId == "#one" }.ref, again.snapshotId)
            delay(300)
            withContext(Dispatchers.Main) { pool.list("A").firstOrNull { !it.current }?.let { pool.switchTo("A", it.tabId) } }
        }
        awaitEvent<PoolEvent.Closed> { it.reason == "limit" }
        delay(1_000)
        assertTrue("${s.tabs()}", s.tabs().size <= 4)
    }

    @Test
    fun popupToOtherSiteAndScriptPopupsRefused() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#other" }.ref, snap.snapshotId)
        delay(1_500)
        assertEquals("${s.tabs()}", 1, s.tabs().size)
        s.navigate(fixture("popup-auto.html"), newTab = false)
        delay(1_000)
        assertEquals("${s.tabs()}", 1, s.tabs().size)
    }

    @Test
    fun externalSchemeNotLaunched() = runBlocking<Unit> {
        fun topResumed() = DeviceSupport.sh("dumpsys activity activities").lines().map { it.trim() }
            .firstOrNull { it.startsWith("topResumedActivity") || it.startsWith("mTopResumedActivity") }
        val before = topResumed()
        assertNotNull(before)
        val s = screen("A")
        s.navigate(fixture("external.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#intent" }.ref, snap.snapshotId)
        val link = awaitEvent<PoolEvent.ExternalLink>()
        assertEquals("com.example.app", link.link.packageName)
        val snap2 = s.snapshot()
        s.tapRef(snap2.elements.first { it.resourceId == "#script" }.ref, snap2.snapshotId)
        awaitEvent<PoolEvent.ExternalLink> { it.link.url.startsWith("demoapp://script") }
        assertEquals(before, topResumed())
        assertTrue(current("A")!!.view.let { v -> runBlocking(Dispatchers.Main) { v.url } }!!.endsWith("external.html"))
    }

    @Test
    fun permissionRequestsDenied() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("perm.html"), newTab = false)
        val tab = current("A")!!
        val geo = DeviceSupport.pollUntil(5_000) { runBlocking { js(tab, "window.geo") }.takeIf { it != "\"pending\"" } }
        val cam = DeviceSupport.pollUntil(5_000) { runBlocking { js(tab, "window.cam") }.takeIf { it != "\"pending\"" } }
        assertEquals("\"denied\"", geo)
        assertTrue(cam, cam == "\"NotAllowedError\"" || cam == "\"unavailable\"")
    }

    @Test
    fun rendererCrashDoesNotKillApp() = runBlocking<Unit> {
        val s = screen("A")
        val crashedId = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        val crashing = current("A")!!
        withContext(Dispatchers.Main) { crashing.view.loadUrl("chrome://crash") }
        assertEquals(crashedId, awaitEvent<PoolEvent.Crashed>().tabId)
        assertTrue(s.tabs().none { it.tabId == crashedId })
        assertTrue(s.tapXY(10, 10) is ActionResult.Failed)
        val again = s.navigate(fixture("host.html"), newTab = false)
        assertTrue(again.loaded)
        assertTrue(again.tab.tabId != crashedId)
    }

    @Test
    fun closeAllAtTaskEndKeepsCookies() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("cookie.html"), newTab = false)
        withContext(Dispatchers.Main) { pool.closeAll("A") }
        assertTrue(s.tabs().isEmpty())
        s.navigate(fixture("host.html"), newTab = false)
        assertTrue(js(current("A")!!, "document.cookie").contains("mika_test=1"))
    }

    // TRIM_MEMORY_RUNNING_LOW is deprecated because newer systems do not send it, but the pool still handles it.
    @Suppress("DEPRECATION")
    @Test
    fun trimMemoryClosesBackgroundTabsAndReloadsOnSwitch() = runBlocking<Unit> {
        val s = screen("A")
        val first = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        s.navigate(fixture("elements.html"), newTab = true)
        withContext(Dispatchers.Main) { pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        assertFalse(runBlocking(Dispatchers.Main) { pool.isLive(first) })
        assertEquals(2, s.tabs().size)
        assertNotNull(s.switchTab(first))
        assertEquals(first, awaitEvent<PoolEvent.Reloaded>().tabId)
        DeviceSupport.pollUntil(5_000) { current("A")?.view?.let { v -> runBlocking(Dispatchers.Main) { v.url } }?.takeIf { it.endsWith("host.html") } }
    }

    @Test
    fun popupStaysOnItsSiteForItsWholeLife() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("popup.html"), newTab = false)
        val snap = s.snapshot()
        s.tapRef(snap.elements.first { it.resourceId == "#one" }.ref, snap.snapshotId)
        val popup = awaitEvent<PoolEvent.NewTab> { it.fromPopup }
        DeviceSupport.pollUntil(5_000) { current("A")?.view?.let { v -> runBlocking(Dispatchers.Main) { v.url } }?.takeIf { it.endsWith("host.html") } }
        val tab = current("A")!!
        // Another registrable domain (subdomains under androidplatform.net are same-site with each other)
        js(tab, "location.href = 'https://fixtures.mika-other.test/assets/web-fixtures/host.html'")
        delay(1_500)
        assertTrue(runBlocking(Dispatchers.Main) { tab.view.url }!!.startsWith("https://appassets.androidplatform.net/"))
        assertTrue(s.tabs().any { it.tabId == popup.tabId })
    }

    @Test
    fun visitsAreRegisteredAndSiteDataCleared() = runBlocking<Unit> {
        val registry = SiteRegistry(java.io.File(ctx.cacheDir, "pool-sites-${System.nanoTime()}"))
        lateinit var p: WebTabPool
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            p = WebTabPool(ctx, host, assetLoaders = listOf(loader), registry = registry)
        }
        try {
            val s = WebScreen(p, "A", host, SystemClock::uptimeMillis)
            s.navigate(fixture("cookie.html"), newTab = false)
            assertEquals(listOf("appassets.androidplatform.net"), registry.list().map { it.host })
            var cleared = 0
            instr.runOnMainSync { cleared = p.clearSiteData("appassets.androidplatform.net") }
            assertTrue("cleared=$cleared", cleared >= 1)
            assertTrue(registry.list().isEmpty())
            val left = js(p.current("A")!!, "document.cookie")
            assertFalse(left, left.contains("mika_test"))
        } finally {
            instr.runOnMainSync { p.close() }
        }
    }

    @Test
    fun nonWebAddressesRefused() = runBlocking<Unit> {
        val s = screen("A")
        s.navigate(fixture("host.html"), newTab = false)
        val tab = current("A")!!
        assertFalse(tab.load("file:///sdcard/x.html"))
        assertFalse(tab.load("javascript:alert(1)"))
        assertTrue(runBlocking(Dispatchers.Main) { tab.view.url }!!.endsWith("host.html"))
    }

    // The pool still reacts to the legacy level even though newer systems no longer send it.
    @Suppress("DEPRECATION")
    @Test
    fun lockedTabIsLeftAloneWhileHandedOver() = runBlocking<Unit> {
        val s = screen("A")
        val id = s.navigate(fixture("host.html"), newTab = false).tab.tabId
        withContext(Dispatchers.Main) { pool.lock(id) }
        assertEquals(ActionResult.Failed("handed_over"), s.tapXY(10, 10))
        assertFalse(s.closeTab(id))
        withContext(Dispatchers.Main) {
            pool.closeAll("A")
            pool.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
        assertTrue(s.tabs().any { it.tabId == id })
        assertTrue(runBlocking(Dispatchers.Main) { pool.isLive(id) })
        withContext(Dispatchers.Main) {
            pool.unlock(id)
            pool.closeAll("A")
        }
        assertTrue(s.tabs().isEmpty())
    }

    /** Two tasks each get their own tab pool but share a single global limit: together they stay within the limit, and the least recently used tab is closed (even if it belongs to another task). */
    @Test
    fun globalCapIsSharedAcrossRuns() = runBlocking<Unit> {
        val budget = TabBudget(3)
        lateinit var a: WebTabPool
        lateinit var b: WebTabPool
        instr.runOnMainSync {
            a = WebTabPool(ctx, host, budget = budget)
            b = WebTabPool(ctx, host, budget = budget)
        }
        try {
            val first = withContext(Dispatchers.Main) { a.open("A", null).id }
            withContext(Dispatchers.Main) { a.open("A", null) }
            withContext(Dispatchers.Main) { b.open("B", null) }
            assertEquals(3, budget.total())
            // Full: first close this task's (B's) own least recently used tab; B has only one, so that is what gets replaced, and both of A's stay (the order makeRoom uses)
            withContext(Dispatchers.Main) { b.open("B", null) }
            assertEquals(3, budget.total())
            assertTrue(runBlocking(Dispatchers.Main) { a.isLive(first) })
            assertEquals(1, runBlocking(Dispatchers.Main) { b.list("B").size })
            assertEquals(2, runBlocking(Dispatchers.Main) { a.list("A").size })
            // Nothing closable in this task (the first tab of the new task C): only then close the least recently used one elsewhere, i.e. A's first
            lateinit var c: WebTabPool
            instr.runOnMainSync { c = WebTabPool(ctx, host, budget = budget) }
            try {
                withContext(Dispatchers.Main) { c.open("C", null) }
                assertEquals(3, budget.total())
                assertFalse(runBlocking(Dispatchers.Main) { a.isLive(first) })
            } finally {
                instr.runOnMainSync { c.close() }
            }
        } finally {
            instr.runOnMainSync {
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun tabsIsolatedByTask() = runBlocking<Unit> {
        val a = screen("A")
        val b = screen("B")
        a.navigate(fixture("host.html"), newTab = false)
        val bTab = b.navigate(fixture("elements.html"), newTab = false).tab.tabId
        assertNull(a.switchTab(bTab))
        assertFalse(a.closeTab(bTab))
        assertFalse(runBlocking(Dispatchers.Main) { pool.close("A", bTab) })
        assertEquals(1, b.tabs().size)
        assertTrue(a.tabs().none { it.tabId == bTab })
        withContext(Dispatchers.Main) { pool.closeAll("A") }
        assertEquals(1, b.tabs().size)
    }
}
