package com.wangzan101.mika.core.device.shell

import com.wangzan101.mika.core.device.adb.AdbConnectionException
import com.wangzan101.mika.core.device.adb.AdbKeyUnreadableException
import com.wangzan101.mika.core.device.adb.AdbPairingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinShellEngineTest {
    private val helper = MutableStateFlow<Any?>(null)

    private inner class FakePlatform : BuiltinShellPlatform {
        var pairedFlag = false
        var wireless: Boolean? = true
        var wifi = true
        var pairingPort: Int? = 37000
        var pairError: AdbPairingException.Reason? = null
        /** Outcome of each helper launch: null = succeeded and handed off, anything else throws that exception; once the queue is empty, launches keep succeeding. */
        val launchResults = ArrayDeque<AdbConnectionException.Reason?>()
        var deliverOnLaunch = true
        var keyUnreadable = false
        val calls = mutableListOf<String>()

        override fun paired() = pairedFlag
        override fun setPaired(paired: Boolean) { pairedFlag = paired; calls += "paired=$paired" }
        override fun wirelessDebugging() = wireless
        override fun wifiConnected() = wifi
        /** When the pairing window is not open ([pairingPort] is null), search for the full timeout before reporting that nothing was found. */
        override suspend fun findPairingPort(timeoutMs: Long) = pairingPort ?: run { delay(timeoutMs); null }
        override fun pair(port: Int, code: String) {
            calls += "pair $port $code"
            pairError?.let { throw AdbPairingException(it, "x") }
        }
        override suspend fun launchHelper() {
            calls += "launch"
            if (keyUnreadable) throw AdbKeyUnreadableException("cannot unwrap adbkey.bin")
            launchResults.removeFirstOrNull()?.let { throw AdbConnectionException(it, "x") }
            if (deliverOnLaunch) helper.value = "binder"
        }
        override fun stopHelper() { calls += "stop"; helper.value = null }
        override fun forget() { calls += "forget" }
        var usb = false
        override fun usbStarted() = usb
        override fun setUsbStarted(on: Boolean) { usb = on }

        /** "Start with root": whether it was requested, whether su exists, and the explicit answer recorded from su; [suAnswers] holds su's answer for each launch through su, and once it runs out su keeps granting. */
        var rootOn = false
        var suPresent = true
        var answered: SuAnswer? = null
        val suAnswers = ArrayDeque<SuAnswer>()
        fun enableRoot() {
            rootOn = true
            answered = SuAnswer.Granted
        }
        override fun rootStart() = RootStart.of(rootOn, suPresent, answered)
        override suspend fun launchHelperAsRoot(): SuAnswer? {
            calls += "su"
            if (!suPresent) return null
            val a = suAnswers.removeFirstOrNull() ?: SuAnswer.Granted
            if (a == SuAnswer.Granted && deliverOnLaunch) helper.value = "root"
            return a
        }
        override fun setRootAnswer(answer: SuAnswer?) { answered = answer }
        override fun requestRootStart(): Boolean {
            calls += "ask"
            answered = SuAnswer.Granted
            return true
        }

        var transportMode: ShellTransport = ShellTransport.Auto
        var shizuku = ShizukuPlatformState.Unavailable
        val shizukuResults = ArrayDeque<BuiltinShellFailure?>()
        var deliverOnShizukuLaunch = true

        override fun transport() = transportMode
        override fun setTransport(transport: ShellTransport) {
            transportMode = transport
            calls += "transport=$transport"
        }
        override fun shizukuState() = shizuku
        override suspend fun launchHelperViaShizuku(): BuiltinShellFailure? {
            calls += "shizuku"
            val failure = shizukuResults.removeFirstOrNull()
            if (failure != null) return failure
            if (deliverOnShizukuLaunch) helper.value = "shizuku"
            return null
        }
    }

    private val platform = FakePlatform()

    private fun TestScope.engine(
        backoff: Backoff = Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3),
        autoOn: WirelessDebuggingAutoOn? = null,
    ) =
        BuiltinShellEngine(
            // Deliberately not backgroundScope: advanceUntilIdle does not wait for background tasks, so the watchdog's retries would be skipped
            platform, helper, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler), backoff,
            graceMs = 3_000, handoffTimeoutMs = 10_000, autoOn = autoOn,
        )

    /** Turns wireless debugging on for the user by writing the fake platform's switch; when [revert] is true the system flips it straight back, as on a Wi-Fi network that is not allowed. */
    private var wants = true
    private var revert = false
    private var switchWrites = 0

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { true },
        read = { platform.wireless.also { if (revert) platform.wireless = false } },
        write = { switchWrites++; platform.wireless = true; true },
        now = { testScheduler.currentTime },
    )

    private fun launches() = platform.calls.count { it == "launch" }
    private fun suCalls() = platform.calls.count { it == "su" }
    private fun shizukuCalls() = platform.calls.count { it == "shizuku" }

    @Test
    fun backoffDoublesAndStops() {
        val b = Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 4)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, null), (1..4).map { b.delayAfter(it) })
        assertEquals(5_000L, Backoff(baseMs = 1_000, maxMs = 5_000, maxAttempts = 10).delayAfter(6))
    }

    @Test
    fun failureReasonsMapToWhatTheUserShouldDo() {
        assertEquals(BuiltinShellFailure.WrongCode, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.WRONG_CODE))
        assertEquals(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.Unknown, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofPairing(AdbPairingException.Reason.NOT_LOCAL))
        assertEquals(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.UNAUTHORIZED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.CONNECT_FAILED))
        assertEquals(BuiltinShellFailure.ConnectFailed, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.TLS_FAILED))
        assertEquals(BuiltinShellFailure.NotLocal, BuiltinShellFailures.ofConnection(AdbConnectionException.Reason.NOT_LOCAL))
        assertEquals(false, BuiltinShellFailures.retryable(BuiltinShellFailure.NotLocal))
    }

    @Test
    fun notPairedNeverLaunches() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(0, launches())
    }

    @Test
    fun pairingThenLaunchEndsRunning() = runTest {
        val e = engine()
        e.startPairing("123456", null)
        assertEquals(listOf("pair 37000 123456", "paired=true", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun pairingFailuresAreReported() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), e.status.value)
        assertEquals(false, platform.pairedFlag)
        platform.pairingPort = null
        e.startPairing("123456", null)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), e.status.value)
    }

    /** Most recent error used for diagnostics: exception type, reason and description; cleared once connected. */
    @Test
    fun lastErrorRecordedAndClearedOnSuccess() = runTest {
        val e = engine()
        platform.pairError = AdbPairingException.Reason.WRONG_CODE
        e.startPairing("123456", 40000)
        assertEquals("AdbPairingException(WRONG_CODE): x", e.lastError)
        platform.pairError = null
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        e.startPairing("123456", 40000)
        assertEquals("AdbConnectionException(UNAUTHORIZED): x", e.lastError)
        e.retry()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun startupWaitsForRedeliveryBeforeLaunching() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        // The helper that is still alive handed off within the grace period: no launch through adbd is needed
        helper.value = "old"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun startupLaunchesAfterGraceWhenNobodyDelivers() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceTimeBy(2_999)
        assertEquals(0, launches())
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun deathIsFollowedByRestartWithBackoff() = runTest {
        platform.pairedFlag = true
        helper.value = "first"
        val e = engine()
        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        platform.launchResults += listOf(AdbConnectionException.Reason.CONNECT_FAILED, AdbConnectionException.Reason.CONNECT_FAILED)
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(1_001)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        // Wait 1 s after the first failure and 2 s after the second
        advanceTimeBy(1_001)
        assertEquals(2, launches())
        advanceTimeBy(1_000)
        assertEquals(2, launches())
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun retriesStopAfterMaxAttemptsUntilAskedAgain() = runTest {
        platform.pairedFlag = true
        platform.launchResults += List(10) { AdbConnectionException.Reason.CONNECT_FAILED }
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(3, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed), e.status.value)
        e.retry()
        assertEquals(4, launches())
    }

    @Test
    fun unreadableKeyAsksToPairAgainAndIsNotRetried() = runTest {
        platform.pairedFlag = true
        platform.keyUnreadable = true
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.KeyUnreadable, BuiltinShellFix.RePair), e.status.value)
        assertTrue(e.lastError!!.startsWith("AdbKeyUnreadableException"))
    }

    @Test
    fun notLocalIsNotRetriedAsANetworkFailure() = runTest {
        platform.pairedFlag = true
        platform.wifi = false // nor may it be reclassified as NoWifi
        platform.launchResults += AdbConnectionException.Reason.NOT_LOCAL
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NotLocal), e.status.value)
    }

    @Test
    fun revokedKeyAndMissingWifiAreNotRetriedAutomatically() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)

        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        e.retry()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)
    }

    @Test
    fun wifiComingBackRetriesAfterNoWifi() = runTest {
        platform.pairedFlag = true
        platform.wifi = false
        platform.launchResults += AdbConnectionException.Reason.CONNECT_FAILED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi), e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wifiComingBackIgnoredUnlessWaitingForWifi() = runTest {
        platform.pairedFlag = true
        platform.launchResults += AdbConnectionException.Reason.UNAUTHORIZED
        val e = engine()
        e.retry()
        advanceUntilIdle()
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFix.RePair), e.status.value)
    }

    @Test
    fun wirelessDebuggingOffWaitsForTheSwitch() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
        assertEquals(0, launches())
        platform.wireless = true
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun wirelessDebuggingOffIsTurnedOnWhenTheHelperIsNeeded() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun revertedOnThisWifiStopsAndSaysSoUntilWifiChanges() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)
        assertEquals(1, switchWrites)
        assertEquals(0, launches())

        // The helper is wanted and the switch changed again: write no more (every write pops a system dialog)
        e.need()
        e.wirelessDebuggingChanged()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed), e.status.value)

        // Switched to an allowed Wi-Fi network: write once more, and this time it stays on
        revert = false
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun userRetryWritesAgainAfterARevert() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        revert = true
        val e = engine(autoOn = autoOn())
        e.retry()
        assertEquals(1, switchWrites)
        // The user allowed it in the system dialog and then tapped retry
        revert = false
        e.retry()
        assertEquals(2, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun switchOffInMikaNeverWrites() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        wants = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        e.wifiAvailable()
        e.need()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)
    }

    @Test
    fun withoutWifiWaitsAndTurnsOnWhenWifiComesBack() = runTest {
        platform.pairedFlag = true
        platform.wireless = false
        platform.wifi = false
        val e = engine(autoOn = autoOn())
        e.start()
        advanceUntilIdle()
        assertEquals(0, switchWrites)
        assertEquals(BuiltinShellStatus.WirelessDebuggingOff, e.status.value)

        platform.wifi = true
        e.wifiAvailable()
        advanceUntilIdle()
        assertEquals(1, switchWrites)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun launchedButNeverHandedOffIsHelperStartFailed() = runTest {
        platform.pairedFlag = true
        platform.deliverOnLaunch = false
        val e = engine(Backoff(maxAttempts = 1))
        e.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), e.status.value)
    }

    @Test
    fun needLaunchesOnceEvenIfCalledRepeatedly() = runTest {
        platform.pairedFlag = true
        val e = engine()
        e.need()
        e.need()
        advanceUntilIdle()
        assertEquals(1, launches())
    }

    @Test
    fun unpairStopsHelperAndForgets() = runTest {
        platform.pairedFlag = true
        helper.value = "h"
        val e = engine()
        e.start()
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertTrue(platform.calls.containsAll(listOf("stop", "forget", "paired=false")))
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, launches())
    }

    /** A helper started by a computer over USB is accepted even without pairing: the state is running and it is recorded that the computer started it; phone operations work as if it had been paired. */
    @Test
    fun usbStartedHelperRunsWithoutPairing() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        helper.value = "usb"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertTrue(platform.usb)
        assertEquals(false, e.paired)
        assertEquals(0, launches())
    }

    /** When not paired, a computer-started helper that stops: do not treat it as never set up, report that it stopped; Claire does not start it itself (it cannot). */
    @Test
    fun usbHelperStoppingSaysSoAndDoesNotLaunch() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        e.need()
        e.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
        // Run the command again, rather than going to pair wireless debugging
        assertEquals(BuiltinShellFix.RunUsbCommand, (e.status.value as BuiltinShellStatus.Failed).fix)
        assertEquals(HelperRestart.UsbCommand, e.restart)
    }

    /** It drops mid-use (e.g. the helper is bringing up a new one to hand off): show connecting first, and only say it stopped after waiting a while. */
    @Test
    fun usbHelperDropMidRunGetsAGraceBeforeStopped() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        // Handoff done: the new helper takes over, so it never flashed to "stopped"
        helper.value = "usb2"
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(0, launches())
    }

    /** What brings the helper back after it stops: nothing set up, started only through the USB command, or paired wireless debugging (pairing wins, even if the last start used the command). */
    @Test
    fun restartRouteFollowsHowTheHelperCameUp() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(HelperRestart.None, e.restart)
        helper.value = "usb"
        runCurrent()
        assertEquals(HelperRestart.UsbCommand, e.restart)
        platform.pairedFlag = true
        assertEquals(HelperRestart.Wireless, e.restart)
    }

    /** After a restart (a new process) the helper does not hand off: wait a while first (a live one comes by itself) and only say it stopped if nothing arrives. */
    @Test
    fun afterRestartUsbHelperGetsAGraceThenStopped() = runTest {
        platform.usb = true
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        e.start()
        advanceTimeBy(2_000)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), e.status.value)
        assertEquals(0, launches())
    }

    @Test
    fun usbHelperComingBackWithinGraceIsRunning() = runTest {
        platform.usb = true
        val e = engine()
        e.start()
        advanceTimeBy(1_000)
        helper.value = "usb"
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** If paired, the watchdog restarts it over wireless debugging as before and does not suggest running the command. */
    @Test
    fun pairedUsbHelperIsRestartedWireless() = runTest {
        platform.usb = true
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, launches())
    }

    /** Unpairing also forgets "started by the computer": afterwards the state is not-set-up, not "stopped". */
    @Test
    fun unpairForgetsUsbStart() = runTest {
        val e = engine()
        e.start()
        helper.value = "usb"
        runCurrent()
        e.unpair()
        advanceUntilIdle()
        assertEquals(false, platform.usb)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }

    @Test
    fun discoveryReportsFoundPort() = runTest {
        val e = engine()
        e.startDiscovery()
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceUntilIdle()
        assertEquals(PairingDiscovery.Found(37000), e.pairingDiscovery.value)
        e.stopDiscovery()
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** Starting again while still searching (the user has come back to pairing) restarts the search deadline from that moment instead of giving up at the time the first search began. */
    @Test
    fun startingDiscoveryAgainRestartsTheSearchTime() = runTest {
        val e = engine()
        platform.pairingPort = null
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        e.startDiscovery()
        advanceTimeBy(9 * 60_000L)
        assertEquals(PairingDiscovery.Searching, e.pairingDiscovery.value)
        advanceTimeBy(2 * 60_000L)
        assertEquals(PairingDiscovery.Idle, e.pairingDiscovery.value)
    }

    /** "Start with root" is on: even without pairing the watchdog starts the helper through su, without touching wireless debugging, and it is not recorded as "started by the computer". */
    @Test
    fun rootStartLaunchesWithoutPairing() = runTest {
        platform.enableRoot()
        platform.wireless = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        assertTrue(e.rootStart)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        assertEquals(0, launches())
        assertEquals(false, platform.usb)
    }

    /** After the helper dies (or after a restart) the watchdog starts it again through su itself, with no pairing and no computer. */
    @Test
    fun rootRestartsTheHelperAfterItDies() = runTest {
        platform.enableRoot()
        val e = engine()
        e.start()
        advanceUntilIdle()
        helper.value = null
        runCurrent()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(2, suCalls())
    }

    /** When several routes exist, root goes first; if root cannot get it up (here a handoff timeout) and pairing exists, wireless debugging follows. */
    @Test
    fun rootIsPreferredAndWirelessIsTheFallback() = runTest {
        platform.enableRoot()
        platform.pairedFlag = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf("su"), platform.calls)
        helper.value = null
        platform.deliverOnLaunch = false
        advanceTimeBy(1_000)
        runCurrent()
        platform.deliverOnLaunch = true
        advanceUntilIdle()
        // Second attempt: su granted but no handoff arrived, so switch to starting over wireless debugging
        assertEquals(listOf("su", "su", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** su denied: record it, report "root denied", and afterwards stop asking su on its own (not even when the helper is needed) until the user taps retry. */
    @Test
    fun rootDenialIsRecordedAndNotRetriedUntilTheUserAsks() = runTest {
        platform.enableRoot()
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootDenied), e.status.value)
        assertEquals(SuAnswer.Denied, platform.answered)
        assertEquals(false, e.rootStart)
        e.need()
        advanceTimeBy(120_000)
        advanceUntilIdle()
        assertEquals(1, suCalls())
        assertEquals(BuiltinShellFix.Retry, (e.status.value as BuiltinShellStatus.Failed).fix)
        // The user tapped retry: the platform requests authorization asynchronously, so the helper is not started directly yet
        e.retry()
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)
        assertEquals(1, suCalls())
        assertEquals("ask", platform.calls.last())
        // Once authorization completes, the platform notifies the engine and only then is the helper launched per the new answer
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(2, suCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    /** su not answering, or failing to switch to the shell uid, likewise stops the repeated asking, each reporting its own reason. */
    @Test
    fun rootNoAnswerAndWrongUidStopAndSaySo() = runTest {
        platform.enableRoot()
        platform.suAnswers += SuAnswer.NoAnswer
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoAnswer), e.status.value)
        platform.suAnswers += SuAnswer.WrongUid
        e.retry()
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootNoShellUid), e.status.value)
        e.need()
        advanceUntilIdle()
        assertEquals(2, suCalls())
    }

    /** When paired, an su denial falls back to wireless debugging and the state is running (the root problem is reported in the corresponding settings item). */
    @Test
    fun rootDeniedButPairedRunsOverWireless() = runTest {
        platform.enableRoot()
        platform.pairedFlag = true
        platform.suAnswers += SuAnswer.Denied
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(listOf("su", "launch"), platform.calls)
        helper.value = null
        advanceUntilIdle()
        // After the denial it no longer asks su
        assertEquals(listOf("su", "launch", "launch"), platform.calls)
    }

    /** "Start with root" is on but su is gone: when not paired, report that root is unavailable. */
    @Test
    fun rootGoneSaysSo() = runTest {
        platform.enableRoot()
        platform.suPresent = false
        val e = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.RootUnavailable), e.status.value)
        assertEquals(0, suCalls())
    }

    /** Merely recording that the user wants it is not enough to ask su automatically; an explicit authorization answer must already exist. */
    @Test
    fun rootStartNeverAsksWithoutStoredAuthorization() = runTest {
        platform.rootOn = true
        platform.answered = null
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(0, suCalls())
    }

    /** The user grants in settings or stops auto-start: while the helper is absent, act on the new situation right away. */
    @Test
    fun rootSwitchChangesTakeEffectRightAway() = runTest {
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        platform.enableRoot()
        e.rootChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, suCalls())
        platform.rootOn = false
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
        assertEquals(1, suCalls())
    }

    // ---- Shizuku transport and switching ----

    @Test
    fun shellTransportParseDefaultsToAuto() {
        assertEquals(ShellTransport.Auto, ShellTransport.parse(null))
        assertEquals(ShellTransport.Auto, ShellTransport.parse(""))
        assertEquals(ShellTransport.Auto, ShellTransport.parse("unknown"))
        assertEquals(ShellTransport.Auto, ShellTransport.parse("auto"))
        assertEquals(ShellTransport.WirelessDebugging, ShellTransport.parse("WirelessDebugging"))
        assertEquals(ShellTransport.WirelessDebugging, ShellTransport.parse("wirelessdebugging"))
        assertEquals(ShellTransport.Shizuku, ShellTransport.parse(" shizuku "))
    }

    @Test
    fun shizukuPlatformStateMapsReadyAndFailures() {
        assertFalse(ShizukuPlatformState.Unavailable.ready)
        assertEquals(BuiltinShellFailure.ShizukuNotInstalled, ShizukuPlatformState.Unavailable.failure())
        assertEquals(
            BuiltinShellFailure.ShizukuNotRunning,
            ShizukuPlatformState(installed = true, running = false, permissionGranted = false).failure(),
        )
        assertEquals(
            BuiltinShellFailure.ShizukuPermissionNeeded,
            ShizukuPlatformState(installed = true, running = true, permissionGranted = false).failure(),
        )
        val ready = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        assertTrue(ready.ready)
        assertEquals(BuiltinShellFailure.ShizukuBindFailed, ready.failure())
        assertTrue(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuBindFailed))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuNotInstalled))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuNotRunning))
        assertFalse(BuiltinShellFailures.retryable(BuiltinShellFailure.ShizukuPermissionNeeded))
    }

    @Test
    fun autoModeConnectsViaShizukuWithoutWifiOrPairing() = runTest {
        platform.wifi = false
        platform.wireless = false
        platform.pairedFlag = false
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        val e = engine()
        assertEquals(ShellTransport.Auto, e.transport)
        assertTrue(e.shizukuStart)
        assertEquals(HelperRestart.Shizuku, e.restart)
        assertEquals(BuiltinShellStatus.Connecting, e.status.value)

        e.start()
        runCurrent()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals(1, shizukuCalls())
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertFalse(platform.usb)
        assertEquals(null, e.lastError)
    }

    @Test
    fun autoModeFallsBackToWirelessWhenShizukuNotRunningPermissionMissingOrBindFails() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        val scope1 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val e1 = BuiltinShellEngine(platform, helper, scope1, StandardTestDispatcher(testScheduler), Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3), graceMs = 3_000, handoffTimeoutMs = 10_000)
        assertEquals(HelperRestart.Wireless, e1.restart)
        e1.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e1.status.value)
        scope1.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        helper.value = null
        platform.calls.clear()
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false)
        val scope2 = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val e2 = BuiltinShellEngine(platform, helper, scope2, StandardTestDispatcher(testScheduler), Backoff(baseMs = 1_000, maxMs = 8_000, maxAttempts = 3), graceMs = 3_000, handoffTimeoutMs = 10_000)
        e2.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, e2.status.value)
        scope2.coroutineContext[kotlinx.coroutines.Job]?.cancel()

        helper.value = null
        platform.calls.clear()
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.shizukuResults += BuiltinShellFailure.ShizukuBindFailed
        val e3 = engine()
        e3.start()
        advanceUntilIdle()
        assertEquals(listOf("shizuku", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e3.status.value)
        assertEquals(null, e3.lastError)
    }

    @Test
    fun autoModeStopsInFlightShizukuBeforeFallingBackToWirelessOnTimeout() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.deliverOnShizukuLaunch = false
        platform.deliverOnLaunch = true
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(listOf("shizuku", "stop", "launch"), platform.calls)
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals("binder", helper.value)
        assertEquals(null, e.lastError)
    }

    @Test
    fun autoModeReportsShizukuNotRunningWhenInstalledAndUnpairedThenConnectsOnRetryAndShizukuChanged() = runTest {
        platform.pairedFlag = false
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        val e = engine()
        assertEquals(HelperRestart.Shizuku, e.restart)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        assertEquals(BuiltinShellFix.Retry, (e.status.value as BuiltinShellStatus.Failed).fix)
        assertEquals(0, shizukuCalls())
        assertEquals(0, launches())

        // After Shizuku starts, tapping retry connects directly
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        e.retry()
        advanceUntilIdle()
        assertEquals(1, shizukuCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // Later Shizuku stops and the helper disconnects: report ShizukuNotRunning, then reconnect automatically once Shizuku is up again and notifies the engine
        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = true)
        helper.value = null
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), e.status.value)
        assertFalse(platform.usb)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        e.shizukuChanged()
        advanceUntilIdle()
        assertEquals(2, shizukuCalls())
        assertEquals(BuiltinShellStatus.Running, e.status.value)
    }

    @Test
    fun shizukuModeReportsAllShizukuFailuresWithoutWirelessOrRootFallback() = runTest {
        platform.transportMode = ShellTransport.Shizuku
        platform.pairedFlag = true
        platform.enableRoot()

        platform.shizuku = ShizukuPlatformState.Unavailable
        val notInstalled = engine()
        assertEquals(HelperRestart.Shizuku, notInstalled.restart)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled), notInstalled.status.value)
        notInstalled.start()
        advanceUntilIdle()
        notInstalled.retry()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotInstalled), notInstalled.status.value)
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertEquals(0, shizukuCalls())

        platform.shizuku = ShizukuPlatformState(installed = true, running = false, permissionGranted = false)
        val notRunning = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuNotRunning), notRunning.status.value)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false)
        val noPerm = engine()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded), noPerm.status.value)

        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.shizukuResults += List(10) { BuiltinShellFailure.ShizukuBindFailed }
        val bindFailed = engine()
        bindFailed.start()
        advanceUntilIdle()
        assertEquals(3, shizukuCalls())
        assertEquals(0, launches())
        assertEquals(0, suCalls())
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuBindFailed), bindFailed.status.value)
        assertEquals("shizuku: ShizukuBindFailed", bindFailed.lastError)

        // When the launch returns null and no handoff ever arrives, the timeout reports ShizukuBindFailed
        platform.calls.clear()
        platform.shizukuResults.clear()
        platform.deliverOnShizukuLaunch = false
        val handoffTimeout = engine(backoff = Backoff(maxAttempts = 1))
        handoffTimeout.retry()
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuBindFailed), handoffTimeout.status.value)
        assertEquals("helper handoff timed out (shizuku)", handoffTimeout.lastError)
        assertEquals(0, launches())
        assertEquals(0, suCalls())
    }

    @Test
    fun wirelessDebuggingModeIgnoresReadyShizuku() = runTest {
        platform.transportMode = ShellTransport.WirelessDebugging
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        platform.pairedFlag = false
        val unpaired = engine()
        assertEquals(ShellTransport.WirelessDebugging, unpaired.transport)
        assertFalse(unpaired.shizukuStart)
        assertEquals(HelperRestart.None, unpaired.restart)
        unpaired.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, unpaired.status.value)
        unpaired.shizukuChanged()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.NotPaired, unpaired.status.value)
        assertEquals(0, shizukuCalls())

        platform.pairedFlag = true
        val paired = engine()
        assertFalse(paired.shizukuStart)
        assertEquals(HelperRestart.Wireless, paired.restart)
        paired.start()
        advanceUntilIdle()
        assertEquals(0, shizukuCalls())
        assertEquals(1, launches())
        assertEquals(BuiltinShellStatus.Running, paired.status.value)
    }

    @Test
    fun selectTransportSwitchesBetweenShizukuAndWirelessDebugging() = runTest {
        platform.pairedFlag = true
        platform.shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true)
        val e = engine()
        e.start()
        advanceUntilIdle()
        assertEquals(BuiltinShellStatus.Running, e.status.value)
        assertEquals("shizuku", helper.value)
        assertEquals(1, shizukuCalls())

        // Switching to the same transport does not restart the helper
        e.selectTransport(ShellTransport.Auto)
        advanceUntilIdle()
        assertEquals(0, platform.calls.count { it == "stop" })

        // Switching to wireless-debugging only: stop the Shizuku-started helper and restart it immediately over wireless debugging
        e.selectTransport(ShellTransport.WirelessDebugging)
        runCurrent()
        assertEquals(1, launches())
        assertEquals("binder", helper.value)
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // Switching to Shizuku only: stop the wireless-debugging helper and restart it immediately through Shizuku
        e.selectTransport(ShellTransport.Shizuku)
        runCurrent()
        assertEquals(2, shizukuCalls())
        assertEquals("shizuku", helper.value)
        assertEquals(BuiltinShellStatus.Running, e.status.value)

        // Switching to wireless-debugging only when not paired: stop the helper and return to NotPaired
        platform.pairedFlag = false
        e.selectTransport(ShellTransport.WirelessDebugging)
        advanceUntilIdle()
        assertEquals(null, helper.value)
        assertEquals(BuiltinShellStatus.NotPaired, e.status.value)
    }
}
