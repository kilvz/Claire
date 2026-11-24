package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.device.access.AppAccessDenied
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppAccessDeviceTest {
    @Test fun blockedLaunchIsDeniedWhileShellRemainsAvailable() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        val script = "/sdcard/Android/data/${app.packageName}/files/usb-start.sh"
        DeviceSupport.sh("sh $script")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName)
            app.appAccess.setBlocked(pkg, true)
            try { screen.openApp(pkg); fail("Blocked launch must be denied") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            val command = screen.systemCommand("printf app-access-shell-ok")
            assertEquals(0, command?.exitCode)
            assertTrue(command?.output?.contains("app-access-shell-ok") == true)
            app.appAccess.setBlocked(pkg, false)
            assertNull(app.appAccess.current().denial(pkg))
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }

    /**
     * An app that was never configured is allowed by default: it can be opened and read. Disable it while a task is
     * running and the very next screen read is rejected; allow it again and reading works again — the permission always
     * reads the latest state, with no task restart; when a verification result is reused, a change of the list stops the
     * reuse as well.
     */
    @Test fun blockingWhileRunningStopsTheNextRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MikaApp
        val pkg = "com.android.settings"
        val original = app.appAccess.current()
        DeviceSupport.sh("sh /sdcard/Android/data/${app.packageName}/files/usb-start.sh")
        val shell = DeviceSupport.shell()
        val display = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        try {
            val native = AndroidScreen(DeviceSupport.service(), shell, DeviceSupport.ready(display), appAccess = app.appAccess)
            val screen = com.wangzan101.mika.core.screen.uistate.AppAccessScreenPort(native, app.appAccess,
                packages = native::accessPackages, protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() })
            app.appAccess.setBlocked(pkg, false)
            app.appAccess.setShareAllowed(pkg, false)
            assertNull("a package nobody configured must be allowed", app.appAccess.current().denial(pkg))
            screen.openApp(pkg)
            screen.checkAppAccess()
            screen.checkScreen()

            app.appAccess.setBlocked(pkg, true)
            try { screen.checkScreen(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }
            // The screen read checks window ownership using this screen's own one: it is rejected just the same
            try { screen.snapshot(); fail("Blocked app must not be read mid-task") }
            catch (e: AppAccessDenied) { assertEquals("app_blocked", e.code) }

            app.appAccess.setBlocked(pkg, false)
            screen.checkScreen()
            screen.checkAppAccess()
        } finally {
            app.appAccess.setBlocked(pkg, pkg in original.blocked)
            app.appAccess.setShareAllowed(pkg, pkg in original.shareAllowed)
            display.release()
        }
    }
}
