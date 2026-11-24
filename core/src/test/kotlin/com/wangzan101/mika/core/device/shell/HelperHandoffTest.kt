package com.wangzan101.mika.core.device.shell

import com.wangzan101.mika.core.device.shell.HelperHandoff.Reject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HelperHandoffTest {
    private class MemStore : NonceStore {
        var slots = NonceSlots()
        override fun load() = slots
        override fun save(slots: NonceSlots) { this.slots = slots }
    }

    private var now = 1_000L
    private var wall = 1_700_000_000_000L

    private fun nonces(store: NonceStore = MemStore()): HandoffNonces {
        var n = 0
        return HandoffNonces(store, clock = { now }, wallClock = { wall }) { "n${++n}" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun usbScriptIsRewrittenWithAFreshNonceWhenNoncesAreClearedWhileNoHelperIsConnected() = runTest {
        var n = 0
        val gate = HandoffNonces(MemStore(), clock = { now }, wallClock = { wall }) { "f00d%04d".format(++n) }
        val helper = MutableStateFlow<Any?>(null)
        val written = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HelperHandoff.usbScriptWrites(helper, gate.resets).collect { connected ->
                written += HelperHandoff.usbFile(connected, "/a.apk", "p", 1, { "pin" }, gate::usbNonce, "/s.sh")
            }
        }
        assertEquals(1, written.size)
        val stale = gate.usbNonce()
        assertTrue(written[0].contains(stale))
        // Unpairing: the helper is always absent, yet the script must be rewritten, the new script carries a new nonce and the old one is no longer accepted
        gate.clear()
        assertEquals(2, written.size)
        assertFalse(written[1].contains(stale))
        assertFalse(gate.matches(stale))
        assertTrue(gate.matches(gate.usbNonce()))
        assertTrue(written[1].contains(gate.usbNonce()))
    }

    @Test
    fun onlyShellWithMatchingIdsAndCurrentApkPasses() {
        fun check(uid: Int = 2000, pid: Int = 77, rUid: Int = uid, rPid: Int = pid, apk: String? = "/a.apk", binder: Boolean = true) =
            HelperHandoff.check(uid, pid, rUid, rPid, apk, "/a.apk", binder)
        assertNull(check())
        assertEquals(Reject.NotShell, check(uid = 10123))
        assertEquals(Reject.NotShell, check(uid = 0))
        assertEquals(Reject.IdMismatch, check(rPid = 78))
        assertEquals(Reject.IdMismatch, check(rUid = 0))
        assertEquals(Reject.StaleApk, check(apk = "/old.apk"))
        assertEquals(Reject.StaleApk, check(apk = null))
        assertEquals(Reject.NoBinder, check(binder = false))
    }

    @Test
    fun rejectionDiagnosticsAreScopedAndClassifyTheCallerWithoutNumericUids() {
        val diagnostics = HandoffDiagnostics()
        diagnostics.reject(Reject.NotShell, callingUid = 0)
        assertEquals("NotShell(caller=Root)", diagnostics.lastRejection)

        diagnostics.launchStarted()
        assertNull(diagnostics.lastRejection)
        diagnostics.reject(Reject.NotShell, callingUid = 102_000)
        assertEquals("NotShell(caller=ShellOtherUser)", diagnostics.lastRejection)
        diagnostics.reject(Reject.NotShell, callingUid = 10_123)
        assertEquals("NotShell(caller=OrdinaryApp)", diagnostics.lastRejection)
        assertFalse(diagnostics.lastRejection!!.contains("10123"))
        diagnostics.reject(Reject.NotShell, callingUid = 1_000)
        assertEquals("NotShell(caller=Other)", diagnostics.lastRejection)
        diagnostics.reject(Reject.StaleApk, callingUid = 0)
        assertEquals("StaleApk", diagnostics.lastRejection)

        diagnostics.clear()
        assertNull(diagnostics.lastRejection)
        assertEquals(HelperHandoff.CallerClass.Shell, HelperHandoff.callerClass(2_000))
    }

    @Test
    fun launchNonceIsSingleUseAndRotates() {
        val g = nonces()
        val launch = g.issueLaunch()
        assertFalse(g.matches("nope"))
        assertFalse(g.matches(null))
        assertTrue(g.matches(launch))
        val next = g.accept(launch)
        assertNotNull(next)
        assertNotEquals(launch, next)
        // Once used, it is no longer accepted
        assertNull(g.accept(launch))
        // The reissued nonce can also be used only once
        val after = g.accept(next)
        assertNotNull(after)
        assertNull(g.accept(next))
    }

    @Test
    fun acceptingEitherSlotVoidsTheOther() {
        val g = nonces()
        val resume = g.accept(g.issueLaunch())!!
        val launch = g.issueLaunch()
        // The old helper hands off first: the newly issued nonce becomes void, so the new one is rejected and exits by itself
        assertNotNull(g.accept(resume))
        assertNull(g.accept(launch))

        val resume2 = g.accept(g.issueLaunch())!!
        val launch2 = g.issueLaunch()
        assertNotNull(g.accept(launch2))
        assertNull(g.accept(resume2))
    }

    @Test
    fun resumeNonceSurvivesProcessRestart() {
        val store = MemStore()
        val resume = nonces(store).accept(nonces(store).issueLaunch())
        // New process: a new object reads the same store
        assertNotNull(nonces(store).accept(resume))
    }

    @Test
    fun clearVoidsEverything() {
        val g = nonces()
        val launch = g.issueLaunch()
        g.clear()
        assertNull(g.accept(launch))
    }

    @Test
    fun randomNoncesAreLongAndDistinct() {
        val a = HandoffNonces.randomNonce()
        assertEquals(64, a.length)
        assertNotEquals(a, HandoffNonces.randomNonce())
    }

    @Test
    fun unusedLaunchNonceExpires() {
        val g = nonces()
        val launch = g.issueLaunch()
        now += HandoffNonces.LAUNCH_TTL_MS
        assertTrue(g.matches(launch))
        now += 1
        assertFalse(g.matches(launch))
        assertNull(g.accept(launch))
    }

    @Test
    fun launchNonceFromTheFutureIsNotTrusted() {
        // The recorded issue time is later than now (e.g. the clock starts from zero after a reboot): treat it as expired
        val store = MemStore()
        val launch = nonces(store).issueLaunch()
        now -= 1
        assertFalse(nonces(store).matches(launch))
    }

    @Test
    fun resumeNonceDoesNotExpire() {
        val g = nonces()
        val resume = g.accept(g.issueLaunch())
        now += 10 * HandoffNonces.LAUNCH_TTL_MS
        assertNotNull(g.accept(resume))
    }

    @Test
    fun commandRunsHelperDetachedAndFeedsNonceThroughStdin() {
        val cmd = HelperHandoff.command("/data/app/~~x/com.wangzan101.mika-y/base.apk", "com.wangzan101.mika", 10234, "ab'cd")
        assertEquals(
            "read n; (echo \"\$n\" | CLASSPATH='/data/app/~~x/com.wangzan101.mika-y/base.apk' setsid app_process /system/bin " +
                "--nice-name='com.wangzan101.mika:helper' com.wangzan101.mika.shell.HelperMain " +
                "'com.wangzan101.mika' 10234 'ab'\\''cd' >/dev/null 2>&1 &); read _",
            cmd,
        )
        assertEquals("com.wangzan101.mika.helper", HelperHandoff.authority("com.wangzan101.mika"))
    }

    @Test
    fun nonceGoesOnItsOwnLine() {
        assertEquals("abc\n", String(HelperHandoff.stdinLine("abc"), Charsets.UTF_8))
    }

    @Test
    fun certPinIsSha256HexAndIgnoresSignerOrder() {
        val a = "abc".toByteArray()
        val b = "xyz".toByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", HelperHandoff.certPin(listOf(a)))
        assertEquals(HelperHandoff.certPin(listOf(a, b)), HelperHandoff.certPin(listOf(b, a)))
        assertNotEquals(HelperHandoff.certPin(listOf(a)), HelperHandoff.certPin(listOf(a, b)))
        assertNull(HelperHandoff.certPin(emptyList()))
        assertNull(HelperHandoff.certPin(null))
    }

    // ---------- Starting from a computer over USB ----------

    private val apk = "/data/app/~~x/com.wangzan101.mika-y/base.apk"
    private val scriptPath = "/storage/emulated/0/Android/data/com.wangzan101.mika/files/usb-start.sh"

    @Test
    fun usbCommandUsesTheShortSdcardPathForTheMainUser() {
        assertEquals("adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh", HelperHandoff.usbCommand(scriptPath))
        // For other users the storage path is written exactly as given
        assertEquals(
            "adb shell sh /storage/emulated/10/Android/data/com.wangzan101.mika/files/usb-start.sh",
            HelperHandoff.usbCommand("/storage/emulated/10/Android/data/com.wangzan101.mika/files/usb-start.sh"),
        )
    }

    @Test
    fun usbScriptStartsTheSameHelperDetachedWithTheNonceOnStdin() {
        val script = HelperHandoff.usbScript(apk, "com.wangzan101.mika", 10234, "ab'cd", "f00d", scriptPath)
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        // Same entry point and same arguments as wireless debugging; single quotes in the fingerprint are escaped; hangup is ignored, a new session is started, it is moved to the background and its output is discarded
        assertTrue(
            script,
            script.contains(
                "(echo \"\$n\" | CLASSPATH='$apk' nohup setsid app_process /system/bin --nice-name='com.wangzan101.mika:helper' " +
                    "com.wangzan101.mika.shell.HelperMain 'com.wangzan101.mika' 10234 'ab'\\''cd' >/dev/null 2>&1 &)",
            ),
        )
        // The nonce lives only in a variable and travels through a pipe via the built-in echo, never as a command-line argument
        assertTrue(script.contains("\nn='f00d'\n"))
        assertEquals(1, script.split("f00d").size - 1)
        // It starts only under the shell uid; if the APK is missing (after an update), ask the user to open Claire first
        assertTrue(script.contains("if [ \"\$(id -u)\" != \"2000\" ]; then"))
        assertTrue(script.contains("if [ ! -f '$apk' ]; then"))
        // Wait for setsid to take effect before returning, and state the outcome and how to confirm it
        assertTrue(script.contains("\nsleep 2\n"))
        assertTrue(script.contains("if pidof 'com.wangzan101.mika:helper' >/dev/null 2>&1; then"))
        assertTrue(script.contains("Claire helper starting... Open Claire to confirm"))
        assertTrue(script.contains("adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh"))
        assertTrue(script.endsWith("fi\n"))
    }

    @Test
    fun usbScriptDoesNotPromiseThatTheHelperSurvivesUnplugging() {
        val script = HelperHandoff.usbScript(apk, "com.wangzan101.mika", 10234, "pin", "f00d", scriptPath)
        assertTrue(script.contains("On most phones it keeps running after you unplug the cable"))
        assertTrue(script.contains("if phone control stopped after unplugging, plug in and run this command again"))
        assertFalse(script.contains("It keeps running after you unplug the cable."))
        // The new message does not change the launch mechanism, and the nonce still appears only once
        assertTrue(script.contains("nohup setsid app_process"))
        assertEquals(1, script.split("f00d").size - 1)
    }

    @Test
    fun usbScriptQuotesEverythingItInterpolates() {
        val script = HelperHandoff.usbScript("/a b/it's.apk", "p", 1, "pin", "n'; rm -rf /; '", scriptPath)
        assertTrue(script.contains("CLASSPATH='/a b/it'\\''s.apk'"))
        assertTrue(script.contains("n='n'\\''; rm -rf /; '\\'''"))
        assertFalse(script.contains("n='n'; rm"))
    }

    @Test
    fun connectedGetsAStubWithoutANonce() {
        var asked = 0
        val stub = HelperHandoff.usbFile(true, apk, "com.wangzan101.mika", 10234, { asked++; "pin" }, { asked++; "secret" }, scriptPath)
        assertEquals(0, asked)
        assertFalse(stub.contains("secret"))
        assertFalse(stub.contains("app_process"))
        assertTrue(stub.contains("already running"))
        assertTrue(stub.contains("pidof 'com.wangzan101.mika:helper'"))
        val live = HelperHandoff.usbFile(false, apk, "com.wangzan101.mika", 10234, { "pin" }, { "secret" }, scriptPath)
        assertTrue(live.contains("n='secret'"))
        assertTrue(live.contains("app_process"))
    }

    @Test
    fun usbNonceIsReusedWhileFreshAndHandsOffOnce() {
        val g = nonces()
        val usb = g.usbNonce()
        // Still fresh when the script is rewritten: reuse it so the copy the computer is running stays valid
        wall += HandoffNonces.USB_TTL_MS / 2
        assertEquals(usb, g.usbNonce())
        assertTrue(g.matches(usb))
        assertNotNull(g.accept(usb))
        // Once used it becomes void; the next script gets a fresh nonce
        assertNull(g.accept(usb))
        assertNotEquals(usb, g.usbNonce())
    }

    @Test
    fun usbNonceRotatesWhenHalfUsedAndExpires() {
        val g = nonces()
        val usb = g.usbNonce()
        wall += HandoffNonces.USB_TTL_MS / 2 + 1
        val fresh = g.usbNonce()
        assertNotEquals(usb, fresh)
        assertFalse(g.matches(usb))
        wall += HandoffNonces.USB_TTL_MS
        assertTrue(g.matches(fresh))
        wall += 1
        assertFalse(g.matches(fresh))
        assertNull(g.accept(fresh))
    }

    @Test
    fun usbNonceFromTheFutureIsNotTrusted() {
        val g = nonces()
        val usb = g.usbNonce()
        wall -= 1
        assertFalse(g.matches(usb))
    }

    @Test
    fun usbNonceSurvivesARebootButNotAnotherHandoff() {
        val store = MemStore()
        val usb = nonces(store).usbNonce()
        // After a reboot the uptime counter is back to zero while wall-clock time keeps running: the script still works
        now = 0
        val g = nonces(store)
        assertTrue(g.matches(usb))
        // A helper started over wireless debugging is accepted first: the script's nonce becomes void
        assertNotNull(g.accept(g.issueLaunch()))
        assertFalse(g.matches(usb))
        assertNull(store.slots.usb)
    }

    @Test
    fun clearVoidsTheUsbNonce() {
        val g = nonces()
        val usb = g.usbNonce()
        g.clear()
        assertFalse(g.matches(usb))
    }
}
