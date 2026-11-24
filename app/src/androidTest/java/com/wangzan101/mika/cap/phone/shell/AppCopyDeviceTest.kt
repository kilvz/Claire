package com.wangzan101.mika.cap.phone.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.screen.uistate.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real device: when another usable user exists (a secondary user / clone, a work profile), open an App installed there on Claire's display; the top task belongs to that user and the main screen is unchanged.
 * Which one to test is decided by arguments: `-Pandroid.testInstrumentationRunnerArguments.copyPackage=<package name>` (optionally also copyUser=<user id>);
 * if not given, the first listed one is used. When the system first asks which one to open, the open step itself taps the option and recognizes the copy it wants.
 * Skipped when there is no other user, or the wanted App is not installed there. It only opens the App and performs no in-App actions.
 */
@RunWith(AndroidJUnit4::class)
class AppCopyDeviceTest {
    private val agent = DeviceSupport.newAgent()

    @After
    fun tearDown() = agent.release()

    @Test
    fun launchesTheCopyInItsUserOnMikasDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val users = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS)
        assumeTrue("no usable other user on this device: ${shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY)}", users != null && users.isNotEmpty())
        val args = InstrumentationRegistry.getArguments()
        val wantPackage = args.getString("copyPackage")
        val wantUser = args.getString("copyUser")?.toIntOrNull()
        val copies = agent.screen.appCopies()
        val copy = copies.firstOrNull { (wantPackage == null || it.packageName == wantPackage) && (wantUser == null || it.userId == wantUser) }
        assumeTrue("no such copy (asked for ${wantPackage ?: "any"} in user ${wantUser ?: "any"}); listed: $copies", copy != null)
        copy!!
        val main = DeviceSupport.mainScreenState(shell)
        val r = agent.screen.openAppCopy(copy.packageName, copy.userId)
        assertTrue("open ${copy.packageName} in user ${copy.userId}: $r", r is ActionResult.Ok)
        r as ActionResult.Ok
        assertEquals("result must say which copy is showing: $r", copy.userId, r.profileUser)
        val top = shell.topTaskId(agent.display.displayId)
        assertEquals(copy.packageName, shell.packageOfTask(top))
        assertEquals(copy.userId, shell.userOfTask(top))
        assertEquals(copy.userId, agent.screen.target.value?.userId)
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))
        // Users that are not on the allowed list are never started
        assertEquals(ShellService.START_USER_NOT_ALLOWED, shell.startAppInUser(agent.display.displayId, copy.packageName, 99_999, false))
    }
}
