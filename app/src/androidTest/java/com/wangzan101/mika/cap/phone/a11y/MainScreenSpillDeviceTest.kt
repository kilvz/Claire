package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.os.SystemClock
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device test: a page on Claire's display opens another app through a service in another process (the same as
 * opening it from the background) and the system puts it on the main screen; the result of that tap must report opened,
 * it must be moved back to Claire's display, and the top of the main screen must return to what it was.
 * Which app to open is given by the instrumentation argument spillTarget (system Settings by default); the test does not
 * run while that app already has a task (moving it over and then destroying it with the display would lose that task).
 * An app installed twice first asks which copy to open: that question is not moved and stays on the main screen, reporting
 * picker and onUserScreen, while the task waits for the user to choose; when the instrumentation argument pickOption is
 * given, the test acts as the user and picks on the main screen (or closes it with back), then checks that the chosen copy
 * was moved back to Claire's display and which copy was reported as opened.
 */
@RunWith(AndroidJUnit4::class)
class MainScreenSpillDeviceTest {
    // As in a task, the display's size and density match the main screen: a page moved over need not be recreated because the size changed (the system's chooser closes itself when it is recreated)
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
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    @After
    fun tearDown() {
        // When it was not moved back, move the one left on the main screen to Claire's display so it is destroyed with the display
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        // If it failed midway and the question is still on the main screen: close it with back
        screen.userPick.value?.let { p -> if (main >= 0 && shell.packageOfTask(main) == p.packageName) sh("input keyevent 4") }
        if (main >= 0 && shell.packageOfTask(main) == target) shell.moveTask(main, agent.display.displayId)
        agent.release()
    }

    /** Also runs when the instrumentation argument allowExisting=true (that task will be moved over and closed with the display, the same as swiping it away from recents). */
    private fun noTaskYet() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse("$target already has a task",
            sh("dumpsys activity recents").lines().any { val l = it.trim(); l.startsWith("* Recent") && " A=" in l && l.substringAfter(" A=").substringAfter(':').startsWith(target) })
    }

    private suspend fun spillPage(): com.wangzan101.mika.core.screen.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} --es ${SpillTestActivity.EXTRA_TARGET} $target")
        val s = pollUntil(5_000) { runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open elsewhere" } } }
        assertNotNull("spill page did not come up", s)
        return s!!
    }

    /** Control: inject a tap onto Claire's display directly, without going through Claire's actions; what it opens lands on the main screen (this is the behaviour to fix). */
    @Test
    fun withoutTheWatchTheLaunchLandsOnTheMainScreen() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val b = s.elements.first { it.name == "open elsewhere" }
        val vdBefore = shell.topTaskId(vd)
        assertTrue(shell.injectTap(vd, b.centerX, b.centerY))
        val landed = pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it != mainBefore } }
        println("before: main top $mainBefore -> $landed (${landed?.let { shell.packageOfTask(it) }}), Claire screen top $vdBefore -> ${shell.topTaskId(vd)}")
        assertNotNull("launch did not land on the main screen", landed)
        assertEquals("Claire's screen changed", vdBefore, shell.topTaskId(vd))
    }

    @Test
    fun appOpenedOnTheMainScreenIsPulledBack() = runBlocking<Unit> {
        noTaskYet()
        val s = spillPage()
        val vd = agent.display.displayId
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val button = s.elements.first { it.name == "open elsewhere" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        val opened = (r as ActionResult.Ok).opened
        assertNotNull("tap did not report opened: $r", opened)
        val now = screen.snapshot()
        println("spill result: $r; on Claire's screen (${now.packageName}): ${now.elements.filter { it.clickable }.map { it.name }}")
        if (!opened!!.picker) {
            assertTrue("not pulled back: $opened", opened.pulledBack)
            assertEquals(target, shell.packageOfTask(shell.topTaskId(vd)))
            assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
            return@runBlocking
        }
        // The system asks on the user's screen which copy to open: it is not moved, not reopened, and stays on the main screen waiting for the user to choose
        assertTrue("question was moved: $opened", !opened.pulledBack && opened.onUserScreen)
        val chooserTask = shell.topTaskId(Display.DEFAULT_DISPLAY)
        assertEquals("question is not on the main screen", opened.packageName, shell.packageOfTask(chooserTask))
        assertNotNull("run is not waiting for the user", screen.userPick.value)
        assertEquals("Claire's screen changed", SpillTestActivity::class.java.name, shell.topActivity(vd))
        // Instrumentation argument pickOption=<index, starting from 0> or back: here the test acts as the user, tapping that option on the main screen (or closing the question with back)
        val pick = InstrumentationRegistry.getArguments().getString("pickOption") ?: return@runBlocking
        if (pick == "back") sh("input keyevent 4")
        else {
            val label = r.chooser?.options?.firstOrNull()?.label
            val nodes = ScreenReader.contentWindows(DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY))
                .filter { it.root?.packageName?.toString() == opened.packageName }
                .flatMap { w -> ScreenReader.read(listOf(w), agent.display.width, agent.display.height).entries.map { it.element } }
                .filter { it.clickable && it.name == label }
            val option = nodes.getOrNull(pick.toInt())
            assertNotNull("no option $pick: ${nodes.map { it.name }}", option)
            sh("input tap ${option!!.centerX} ${option.centerY}")
        }
        val settled = screen.awaitUserPick()
        // What was picked needs the user to handle it by hand: here the test acts as the user and taps continue
        if (settled is AndroidScreen.PickResult.NeedsUser) screen.afterUserStep(settled)
        val after = screen.snapshot()
        val top = shell.topTaskId(vd)
        println("after the user ($pick): $settled; opened=${after.opened}; Claire's screen top ${shell.packageOfTask(top)} user ${shell.userOfTask(top)}; " +
            "main top ${shell.topTaskId(Display.DEFAULT_DISPLAY)} (before $mainBefore)")
        if (pick == "back") assertTrue("not reported closed: ${after.opened}", after.opened?.closed == true)
        else {
            assertTrue("not pulled back: ${after.opened}", after.opened?.pulledBack == true && (after.opened?.inProfile != null || after.opened?.byUser == true))
            assertEquals(target, shell.packageOfTask(top))
        }
        assertEquals("main screen top did not come back", mainBefore, pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } })
    }

    /** In a real task stack, going home first and then opening an app: even without a semantic click, this user navigation must not be judged an Agent spill. */
    @Test
    fun returningHomeBeforeOpeningAnAppIsUserNavigation() = runBlocking<Unit> {
        noTaskYet()
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val homeApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        assertTrue("no home handler", homeApps.isNotEmpty())
        val ownPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        sh("am start --display 0 -n $ownPackage/com.wangzan101.mika.ui.MainActivity")
        assertNotNull("Claire did not come to the main screen", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) == ownPackage }
        })
        val topBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val since = SystemClock.uptimeMillis()
        shell.watchMainScreen(6_000)
        sh("am start --display 0 -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}")
        assertNotNull("home did not come to the front", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) in homeApps }
        })
        val launch = pm.getLaunchIntentForPackage(target)
        assertNotNull("no launcher activity for $target", launch)
        sh("am start --display 0 -n ${launch!!.component!!.flattenToShortString()}")
        assertNotNull("target did not come to the front", pollUntil(3_000) {
            shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) == target }
        })

        val arrivals = arrivalsSince(since)
        assertTrue("home navigation was not recorded", arrivals.any { it.packageName in homeApps })
        val verdicts = MainScreenSpill.decide(arrivals, since, 6_000, topBefore,
            homeApps + InstrumentationRegistry.getInstrumentation().targetContext.packageName, emptyList(), homeApps)
        assertTrue("user-opened app was judged an agent spill: $verdicts", verdicts.none { it.arrival.packageName == target })
    }

    private fun arrivalsSince(since: Long): List<MainScreenSpill.Arrival> {
        val b = shell.mainArrivals(since)
        assertNotNull("no main-screen arrivals", b)
        val tasks = b!!.getIntArray(MainScreenWatch.KEY_TASK)!!
        val packages = b.getStringArray(MainScreenWatch.KEY_PACKAGE)!!
        val users = b.getIntArray(MainScreenWatch.KEY_USER)!!
        val at = b.getLongArray(MainScreenWatch.KEY_AT)!!
        val from = b.getIntArray(MainScreenWatch.KEY_FROM)!!
        val same = b.getBooleanArray(MainScreenWatch.KEY_SAME_TASK)!!
        val standard = b.getBooleanArray(MainScreenWatch.KEY_STANDARD)!!
        val now = b.getIntArray(MainScreenWatch.KEY_NOW)!!
        return tasks.indices.map { i ->
            MainScreenSpill.Arrival(tasks[i], packages[i], users[i], at[i], from[i], same[i], standard[i], now[i])
        }
    }
}
