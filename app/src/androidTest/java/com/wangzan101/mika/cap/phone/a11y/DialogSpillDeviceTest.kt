package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device test: a background process opens a dialog-styled page on the main screen (a popup such as a sign-in
 * verification or an account confirmation that the user has to confirm on the main screen). Such popups must stay on the
 * main screen for the user; Claire's display must not be covered by one, nor move one onto a background display.
 */
@RunWith(AndroidJUnit4::class)
class DialogSpillDeviceTest {
    // As in a task, the display's size and density match the main screen: a page moved over need not be recreated because the size changed
    private val agent = run {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val shell = DeviceSupport.shell()
        val d = com.wangzan101.mika.cap.phone.display.AgentDisplay(shell, m.widthPixels, m.heightPixels, m.densityDpi, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        DeviceSupport.Agent(d, AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(d)))
    }
    private val screen get() = agent.screen
    private val shell = DeviceSupport.shell()

    @After
    fun tearDown() {
        // On failure the popup may still be left on the main screen: close it
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        agent.release()
    }

    /** Type, package and bounds of each window on the main screen: a dialog-styled window is clearly smaller than the whole screen. */
    private fun dumpMainWindows(label: String) {
        val service = DeviceSupport.service()
        for (w in service.windowsOn(Display.DEFAULT_DISPLAY)) {
            val b = Rect()
            w.getBoundsInScreen(b)
            println("$label window type=${w.type} pkg=${w.root?.packageName} bounds=$b")
        }
    }

    @Test
    fun dialogOnTheMainScreenIsLeftForTheUser() = runBlocking<Unit> {
        // First look at this kind of dialog's window on the main screen on its own (without going through Claire's actions)
        DeviceSupport.sh("am start -n ${DeviceSupport.TEST_PACKAGE}/${DialogSpillTestActivity::class.java.name}")
        DeviceSupport.pollUntil(5_000) {
            shell.topActivity(Display.DEFAULT_DISPLAY)?.takeIf { it.contains("DialogSpillTestActivity") }
        }
        dumpMainWindows("dialog")
        DeviceSupport.sh("input keyevent 4")
        delay(500)

        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_DIALOG} ${DialogSpillTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open dialog" } }
        }
        assertNotNull("spill page did not come up", s)
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val vdBefore = shell.topTaskId(vd)
        val since = SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open dialog" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // Identify this popup task that spilled onto the main screen from the helper's record (a task keeps its id when moved)
        val b = shell.mainArrivals(since)
        val packages = b?.getStringArray(MainScreenWatch.KEY_PACKAGE)
        val tasks = b?.getIntArray(MainScreenWatch.KEY_TASK)
        val dialogTask = packages?.indices?.firstOrNull { packages[it] == DeviceSupport.TEST_PACKAGE }?.let { tasks?.get(it) }
        assertNotNull("no arrival on the main screen was reported for the popup", dialogTask)
        dumpMainWindows("after action")

        // Give Claire a chance to handle this task that spilled onto the main screen (whatever the action did not catch up with is made up for on the next screen read)
        delay(1_500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? com.wangzan101.mika.core.screen.uistate.ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println(
            "after: opened=$opened main top=${shell.topTaskId(Display.DEFAULT_DISPLAY)} " +
                "dialogTask=$dialogTask stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore, main before $mainBefore)",
        )
        // The popup is still on the main screen, so the user can go on and tap confirm
        assertTrue("the dialog was moved off the user's screen", stillThere)
        // Claire's display still shows the same page as before
        assertEquals("Claire's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the popup was not reported to the model", opened)
        assertEquals(DeviceSupport.TEST_PACKAGE, opened!!.packageName)
        assertTrue("the popup was reported as pulled back: $opened", !opened.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
