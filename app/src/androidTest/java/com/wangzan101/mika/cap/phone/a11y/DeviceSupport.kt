package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.graphics.Point
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.cap.phone.shell.ShellBinding
import com.wangzan101.mika.cap.phone.PhoneServices
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.wangzan101.mika.a11y.MikaAccessibilityService

/** Shared by the real-device tests: the shell connection, the accessibility service and AndroidScreen on the virtual display. Requires the built-in helper to be running (paired, wireless debugging on) and Claire's accessibility service to be enabled. */
object DeviceSupport {
    /** Real display metrics, used by probes that create a virtual display at the main screen's size. */
    @Suppress("DEPRECATION")
    fun realMetrics(display: Display?): DisplayMetrics = DisplayMetrics().also { display?.getRealMetrics(it) }

    @Suppress("DEPRECATION")
    fun realSize(display: Display?): Point = Point().also { display?.getRealSize(it) }

    const val SETTINGS = "com.android.settings"
    const val TEST_PACKAGE = "com.wangzan101.mika.test"
    private var connection: ShellBinding? = null
    private val phone get() = PhoneServices.of(InstrumentationRegistry.getInstrumentation().targetContext)

    /**
     * Share the in-process shell connection with the app and hold it the whole time: if each side bound and unbound on
     * its own, whichever unbinds first would end the shell service and the binder held by the other side would go invalid
     * with it.
     */
    @Synchronized
    fun shell(): IMikaShell {
        val c = connection ?: phone.sharedShell.acquire().also {
            connection = it
            rebindAccessibility()
        }
        return awaitShell(c)
    }

    /** Wait until the connection holds a live helper; if it does not arrive, fail with the current status and suggest pairing first. After the helper is killed the watchdog takes a while to restart it, and before the disconnect callback arrives a dead binder may still be held. */
    fun awaitShell(c: ShellBinding, timeoutMs: Long = 30_000): IMikaShell = runBlocking {
        withTimeoutOrNull(timeoutMs) { c.shell.filterNotNull().first { it.asBinder().pingBinder() } }
    } ?: error(
        "Claire's phone helper is not running (status=${phone.shellControl.status.value}): " +
            "pair it once in Claire (设置手机操作) with Wireless debugging on, keep Wireless debugging on, then rerun",
    )

    /** When the service is offline (an earlier test ended this process's binding, etc.), first push the system to rebind, then wait for it to connect. */
    fun service(): ScreenAccessibility {
        if (ScreenAccessibility.instance.value == null) rebindAccessibility()
        return runBlocking {
            withTimeoutOrNull(10_000) { ScreenAccessibility.instance.filterNotNull().first() }
        } ?: error("Claire accessibility service is not enabled")
    }

    class Agent(val display: AgentDisplay, val screen: AndroidScreen) {
        fun release() = display.release()
    }

    fun newAgent(): Agent {
        val shell = shell()
        val d = AgentDisplay(shell, 720, 1600, 320, InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.hardware.display.DisplayManager::class.java))
        return Agent(d, AndroidScreen(service(), shell, ready(d)))
    }

    /** A display that already exists: the test creates the display itself first (and may already have put an app on it), so a screen read need not wait for phone_launch_app. */
    fun ready(d: AgentDisplay): LazyAgentDisplay = LazyAgentDisplay(d.width, d.height) { d }.also { runBlocking { it.get() } }

    /**
     * When the test starts, the system force-stops this app, so the accessibility service is recorded as crashed and is
     * no longer rebound automatically. If it is enabled, take it out of the enabled list and put it back so the system
     * rebinds it; if it is not enabled, leave it alone.
     */
    private fun rebindAccessibility() {
        if (ScreenAccessibility.instance.value != null) return
        val key = "enabled_accessibility_services"
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val full = "$pkg/${MikaAccessibilityService::class.java.name}"
        // A component name can be written as "package/.Class" or as the fully qualified class name; the app's own restore (PermissionCommands.accessibilityService) writes the short form, so both must be accepted, otherwise the rebind is skipped
        val short = "$pkg/" + MikaAccessibilityService::class.java.name.removePrefix("$pkg.")
        val enabled = sh("settings get secure $key").trim().split(':')
        if (enabled.none { it == full || it == short }) return
        try {
            sh("settings put secure $key ${enabled.filter { it != full && it != short }.joinToString(":").ifEmpty { "\"\"" }}")
            Thread.sleep(500)
        } finally {
            sh("settings put secure $key ${enabled.joinToString(":")}")
        }
    }

    fun sh(cmd: String): String {
        // Do not suppress other accessibility services, otherwise Claire's service is paused during the test
        val ua = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val pfd = ua.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Open a page of the test package on the virtual display (given by class; the class name matches the one registered in the manifest). */
    fun startTestActivity(displayId: Int, activity: Class<out android.app.Activity>) {
        sh("am start --display $displayId -n $TEST_PACKAGE/${activity.name}")
    }

    /**
     * Main-screen state: the system's top-focused display, display 0's focused window and focused activity, and the top
     * task of the main screen. Whatever the Agent does on the virtual display, none of these should change.
     */
    fun mainScreenState(shell: IMikaShell): String {
        val topDisplay = sh("dumpsys window").lines().map { it.trim() }
            .firstOrNull { it.startsWith("mTopFocusedDisplayId=") }
        checkNotNull(topDisplay) { "mTopFocusedDisplayId not found in dumpsys window" }
        val section = sh("dumpsys window displays").substringAfter("Display: mDisplayId=0", "")
            .substringBefore("Display: mDisplayId=").lines().map { it.trim() }
        val focus = section.firstOrNull { it.startsWith("mCurrentFocus=") }
        checkNotNull(focus) { "mCurrentFocus not found for display 0" }
        val focusedApp = section.firstOrNull { it.startsWith("mFocusedApp=") }
            ?.let { Regex("u0 (\\S+)").find(it)?.groupValues?.get(1) }
        return "$topDisplay $focus focusedApp=$focusedApp topTask=${shell.topTaskId(android.view.Display.DEFAULT_DISPLAY)}"
    }

    fun <T : Any> pollUntil(timeoutMs: Long, block: () -> T?): T? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            block()?.let { return it }
            Thread.sleep(100)
        }
        return block()
    }
}
