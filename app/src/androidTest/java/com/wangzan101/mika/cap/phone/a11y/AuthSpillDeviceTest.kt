package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.shell.MainScreenWatch
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device test: the test package is temporarily registered as an account authenticator (see TestAuthenticatorService;
 * it is off by default in the manifest, enabled here and disabled again after the test), and the test package has no
 * launcher icon, just like the system's account sign-in services. Its full-screen sign-in page has no dialog theme, no
 * password field and no excludeFromRecents, so in shape it is like an ordinary app page; only the rule "the package is an
 * account authenticator without a launcher icon" can tell that it needs the user to handle it by hand and should stay on
 * the main screen rather than be moved onto Claire's display.
 */
@RunWith(AndroidJUnit4::class)
class AuthSpillDeviceTest {
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

    private val authenticatorService = "${DeviceSupport.TEST_PACKAGE}/${TestAuthenticatorService::class.java.name}"

    @After
    fun tearDown() {
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == DeviceSupport.TEST_PACKAGE) DeviceSupport.sh("input keyevent 4")
        // Turn it back off and wait for it to drop out of the authenticator list: in other spill-back tests the test package's pages must not be taken for an account sign-in page
        DeviceSupport.sh("pm disable $authenticatorService")
        DeviceSupport.pollUntil(10_000) { (!testAuthenticatorListed()).takeIf { it } }
        agent.release()
    }

    private fun testAuthenticatorListed(): Boolean {
        val am = AccountManager.get(InstrumentationRegistry.getInstrumentation().targetContext)
        return runCatching { am.authenticatorTypes.any { it.packageName == DeviceSupport.TEST_PACKAGE } }.getOrDefault(false)
    }

    /** Enable the test package's authenticator and wait for it to appear in the system's account authenticator list; only then does this case mean anything. */
    private fun registerTestAuthenticator(): Boolean {
        DeviceSupport.sh("pm enable $authenticatorService")
        return DeviceSupport.pollUntil(10_000) { testAuthenticatorListed().takeIf { it } } == true
    }

    /** The test package has no LAUNCHER entry (there is none in the manifest), and this is exactly what the rule needs. */
    private fun testPackageHasNoLauncher(): Boolean {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(DeviceSupport.TEST_PACKAGE)
        return pm.queryIntentActivities(launcher, 0).isEmpty()
    }

    @Test
    fun authenticatorFullScreenPageStaysOnTheMainScreen() = runBlocking<Unit> {
        assertTrue("the test package has a launcher entry", testPackageHasNoLauncher())
        assertTrue("the test package is not registered as an account authenticator", registerTestAuthenticator())
        screen.ensureDisplay()
        val vd = agent.display.displayId
        DeviceSupport.sh(
            "am start --display $vd -n ${DeviceSupport.TEST_PACKAGE}/${SpillTestActivity::class.java.name}" +
                " --es ${SpillTestActivity.EXTRA_AUTH} ${FullScreenAuthTestActivity::class.java.name}",
        )
        val s = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { it.elements.any { e -> e.name == "open auth" } }
        }
        assertNotNull("spill page did not come up", s)
        val vdBefore = shell.topTaskId(vd)
        val since = android.os.SystemClock.uptimeMillis()
        val button = s!!.elements.first { it.name == "open auth" }
        val r = screen.tapRef(button.ref, s.snapshotId)
        println("tap result: $r")

        // The service's process may be cold, so the popup may show up a second or two late; wait for it to enter the helper's record
        val b = DeviceSupport.pollUntil(5_000) {
            val arr = shell.mainArrivals(since)
            val pkgs = arr?.getStringArray(MainScreenWatch.KEY_PACKAGE)
            if (pkgs?.contains(DeviceSupport.TEST_PACKAGE) == true) arr else null
        }
        assertNotNull("no arrival on the main screen was reported for the auth page", b)
        delay(500)
        val now = screen.snapshot()
        delay(500)
        val opened = now.opened ?: (r as? ActionResult.Ok)?.opened
        val stillThere = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
            .any { it.root?.packageName?.toString() == DeviceSupport.TEST_PACKAGE }
        println("after: opened=$opened stillOnMain=$stillThere vd top=${shell.topTaskId(vd)} (before $vdBefore)")
        assertTrue("the authenticator page was moved off the user's screen", stillThere)
        assertEquals("Claire's screen changed", vdBefore, shell.topTaskId(vd))
        assertNotNull("the auth page was not reported to the model", opened)
        assertTrue("the auth page was reported as pulled back: $opened",
            !opened!!.pulledBack && opened.onUserScreen && opened.needsUser)
    }
}
