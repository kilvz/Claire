package com.wangzan101.mika.cap.web

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * Device test: a borrowed tab (the one the user is looking at on the browser page) is used as the task's current tab. Without going through tasks, it uses the tab pool and the web port directly:
 * the "user's tab" is attached to a separate web display (standing in for the browser page's UI), borrowed into the task's pool, and the web tools read and tap on it;
 * a borrowed tab is not attached to the task's display, does not count toward the cap, and opening a URL spawns a new tab; handing it back and the task ending both leave it undestroyed; when the user closes the page the task adopts it and keeps using it.
 * No built-in assistant is needed, and the main screen is not touched.
 */
@RunWith(AndroidJUnit4::class)
class BorrowedTabDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var userHost: WebHost
    private lateinit var pool: WebTabPool
    private lateinit var userTab: WebTab
    private val task = "task-borrow"

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val loaders = listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build())
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            userHost = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            pool = WebTabPool(ctx, host, perTask = 2, budget = TabBudget(), assetLoaders = loaders)
            // The user's tab: not managed by the pool, attached to its own display (on a real device the browser page's UI is the Activity's window)
            userTab = WebTab(ctx, "browser1", "user-browser", loaders)
            userTab.devtools.setHandOperated(userTab)
            userHost.attach(userTab, desktop = false)
        }
        runBlocking { assertTrue(userTab.load(fixture("inputs.html"))) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            if (!userTab.destroyed) userTab.destroy()
            host.close()
            userHost.close()
        }
    }

    private fun screen() = WebScreen(pool, task, host, SystemClock::uptimeMillis)
    private fun main(block: () -> Unit) = instr.runOnMainSync(block)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String =
        elements.firstOrNull { it.resourceId == resourceId }?.ref ?: error("$resourceId not in ${elements.map { it.resourceId }}")

    @Test
    fun webToolsActOnTheBorrowedTabWhereItIs() = runBlocking<Unit> {
        var attached = false
        main { attached = pool.attachExternal(task, userTab) }
        assertTrue(attached)
        main {
            assertSame(userTab, pool.current(task))
            assertTrue(pool.isExternal(userTab.id))
            // Not attached to the task's web display, so it does not count toward the cap
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
            assertEquals(0, pool.tabCount())
        }
        val s = screen()
        val snap = s.snapshot()
        assertTrue(snap.snapshotId.startsWith("wbrowser1-"))
        val r = s.tapRef(snap.ref("#counter"), snap.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("1", js(userTab, "window.clicks"))
        assertEquals("true", js(userTab, "window.lastTrusted"))
        // The screenshot draws this view itself
        assertNotNull(s.screenshotPng())
        // Navigating does not take the user's page away: a new tab is opened
        val nav = s.navigate(fixture("elements.html"), newTab = false)
        assertTrue(nav.newTab)
        main {
            assertNotSame(userTab, pool.current(task))
            assertTrue(userTab.view.url!!.endsWith("inputs.html"))
            assertEquals(1, pool.tabCount())
        }
        // Switching back works as usual; when the task ends it is handed back, not destroyed
        main { assertSame(userTab, pool.switchTo(task, userTab.id)) }
        main { pool.closeAll(task) }
        main {
            assertFalse(userTab.destroyed)
            assertFalse(pool.isExternal(userTab.id))
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
        }
    }

    @Test
    fun detachReturnsAndAdoptKeepsThePage() = runBlocking<Unit> {
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // Borrowing it a second time just switches to it
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // Another task cannot borrow the same tab
        main { assertFalse(pool.attachExternal("other", userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertEquals(null, pool.current(task))
            assertFalse(userTab.destroyed)
            assertFalse(pool.detachExternal(userTab.id))
        }
        // The user closes the page: the task adopts it as a normal tab, attached to the task's display, and the page state is preserved
        js(userTab, "document.getElementById('counter').click()")
        main { assertTrue(pool.attachExternal(task, userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertTrue(pool.adopt(task, userTab, current = true))
            assertSame(userTab, pool.current(task))
            assertFalse(pool.isExternal(userTab.id))
            assertEquals(task, userTab.ownerTaskId)
            assertSame(host, (userTab.view.parent as android.view.View).tag)
            assertEquals(1, pool.tabCount())
        }
        assertEquals("1", js(userTab, "window.clicks"))
        // Adopted tabs are closed together with the task
        main { pool.closeAll(task) }
        main { assertTrue(userTab.destroyed) }
    }
}
