package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupFlowTest {
    private val fresh = SetupInputs(developerOptions = false, wirelessDebugging = false, status = BuiltinShellStatus.NotPaired, usbDebugging = false, accessibility = false)
    private val switchesOn = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)

    private fun SetupView.markList() = SetupStep.entries.map { marks.getValue(it) }

    @Test
    fun walksTheStepsInOrderAsSettingsChange() {
        val v1 = PhoneSetupFlow.view(fresh)
        assertEquals(SetupPhase.Guide, v1.phase)
        assertEquals(SetupStep.DeveloperOptions, v1.current)
        assertEquals(listOf(StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v1.markList())

        val v2 = PhoneSetupFlow.view(fresh.copy(developerOptions = true))
        assertEquals(SetupStep.UsbDebugging, v2.current)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2.markList())

        val v2a = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true))
        assertEquals(SetupStep.Accessibility, v2a.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo, StepMark.Todo), v2a.markList())

        val v2b = PhoneSetupFlow.view(fresh.copy(developerOptions = true, usbDebugging = true, accessibility = true))
        assertEquals(SetupStep.WirelessDebugging, v2b.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current, StepMark.Todo, StepMark.Todo), v2b.markList())

        val v3 = PhoneSetupFlow.view(switchesOn)
        assertEquals(SetupStep.PairingCode, v3.current)
        assertTrue(v3.autoRead)
        assertFalse(v3.portFound)
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(discovery = PairingDiscovery.Found(37000))).portFound)

        val v4 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v4.phase)
        assertNull(v4.current)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Done, StepMark.Current), v4.markList())

        val v5 = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v5.phase)
        assertTrue(v5.markList().all { it == StepMark.Done })
    }

    @Test
    fun everyStepCanBePassedByHandWhenDetectionReadsOff() {
        // This phone reads every switch as off: the user taps "I've turned it on, next" step by step and still reaches pairing
        var i = fresh
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.DeveloperOptions)
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.UsbDebugging)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(i).current)
        i = i.copy(accessibilitySkipped = true)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(i).current)
        i = i.copy(confirmed = i.confirmed + SetupStep.WirelessDebugging)
        val pairing = PhoneSetupFlow.view(i)
        assertEquals(SetupStep.PairingCode, pairing.current)
        assertEquals(SetupPhase.Guide, pairing.phase)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Skipped, StepMark.Done, StepMark.Current, StepMark.Todo), pairing.markList())
        // Once connected it counts as success, whatever the earlier steps read
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Running)).phase)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(status = BuiltinShellStatus.Connecting)).phase)
    }

    @Test
    fun confirmedStepStaysPassedAndDetectionStillTicksTheRest() {
        // Only developer options was confirmed: USB debugging is ticked automatically once it reads as on, with no further tap
        val v = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions)))
        assertEquals(SetupStep.PairingCode, v.current)
        assertEquals(StepMark.Done, v.marks[SetupStep.DeveloperOptions])
        assertEquals(StepMark.Done, v.marks[SetupStep.UsbDebugging])
        // If a step that was not confirmed reads as off, the flow still goes back to it
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, confirmed = setOf(SetupStep.DeveloperOptions))).current)
        assertEquals(true, PhoneSetupFlow.detected(SetupStep.UsbDebugging, switchesOn))
        assertNull(PhoneSetupFlow.detected(SetupStep.WirelessDebugging, switchesOn.copy(wirelessDebugging = null)))
        assertNull(PhoneSetupFlow.detected(SetupStep.PairingCode, switchesOn))
    }

    @Test
    fun confirmedWirelessButHelperReadsItOffPointsBackInsteadOfSpinning() {
        val i = SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff, confirmed = setOf(SetupStep.WirelessDebugging))
        val v = PhoneSetupFlow.view(i)
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(StepMark.Failed, v.marks[SetupStep.Connect])
        assertNull(v.failure)
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), v.suspect)
        // It reads as on while the status has not caught up yet: still connecting
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(i.copy(wirelessDebugging = true)).phase)
        // Phones that cannot read the switch also say "unreadable" truthfully
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(i.copy(wirelessDebugging = null)).suspect)
    }

    @Test
    fun connectionFailurePointsAtTheMostLikelyEarlierStep() {
        val connect = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)
        // Developer options reads as off (the user could only have got here by confirming it)
        val dev = PhoneSetupFlow.view(switchesOn.copy(developerOptions = false, confirmed = setOf(SetupStep.DeveloperOptions), status = connect))
        assertEquals(SetupPhase.Failed, dev.phase)
        assertEquals(SetupSuspect(SetupStep.DeveloperOptions, unreadable = false), dev.suspect)
        // Wireless debugging reads as off
        val off = PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = false, confirmed = setOf(SetupStep.WirelessDebugging), status = connect))
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = false), off.suspect)
        // Wireless debugging cannot be read
        assertEquals(SetupSuspect(SetupStep.WirelessDebugging, unreadable = true), PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = connect)).suspect)
        // A closed pairing window may also be down to the switch
        val closed = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = closed)).suspect?.step)
        // All read as on, or the code is wrong / the helper will not start: do not point at anything
        assertNull(PhoneSetupFlow.view(switchesOn.copy(status = connect)).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).suspect)
        assertNull(PhoneSetupFlow.view(switchesOn.copy(wirelessDebugging = null, status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed))).suspect)
    }

    @Test
    fun anyEarlierStepCanBeRevisited() {
        val atPairing = switchesOn
        val back = PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.UsbDebugging))
        assertEquals(SetupPhase.Guide, back.phase)
        assertEquals(SetupStep.UsbDebugging, back.current)
        assertTrue(back.revisiting)
        assertEquals(listOf(StepMark.Done, StepMark.Current, StepMark.Done, StepMark.Done, StepMark.Todo, StepMark.Todo), back.markList())
        assertFalse(PhoneSetupFlow.view(atPairing).revisiting)
        // It can still go back after a failure, and the failed step stays marked
        val failed = PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.WirelessDebugging))
        assertEquals(SetupStep.WirelessDebugging, failed.current)
        assertNull(failed.failure)
        assertEquals(StepMark.Failed, failed.marks[SetupStep.Connect])
        // It can also go back to pair again
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), revisit = SetupStep.PairingCode)).current)
        // The automatic connect step cannot be tapped; while connecting or already successful the helper's state decides
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(atPairing.copy(revisit = SetupStep.Connect)).current)
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Connecting, revisit = SetupStep.DeveloperOptions)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(atPairing.copy(status = BuiltinShellStatus.Running, revisit = SetupStep.DeveloperOptions)).phase)
        // If an earlier step reads as off, go back to that step first
        assertEquals(SetupStep.DeveloperOptions, PhoneSetupFlow.view(atPairing.copy(developerOptions = false, revisit = SetupStep.WirelessDebugging)).current)
    }

    @Test
    fun accessibilityCanBeSkippedAndPairingFallsBackToTheNotification() {
        val off = switchesOn.copy(accessibility = false)
        assertEquals(SetupStep.Accessibility, PhoneSetupFlow.view(off).current)
        val skipped = PhoneSetupFlow.view(off.copy(accessibilitySkipped = true))
        assertEquals(SetupStep.PairingCode, skipped.current)
        assertFalse(skipped.autoRead)
        assertEquals(StepMark.Skipped, skipped.marks[SetupStep.Accessibility])
        // Turned on in the system after being skipped: it is read automatically all the same
        val laterOn = PhoneSetupFlow.view(switchesOn.copy(accessibilitySkipped = true))
        assertTrue(laterOn.autoRead)
        assertEquals(StepMark.Done, laterOn.marks[SetupStep.Accessibility])
        // Accessibility is also consulted when retyping after a wrong code
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))).autoRead)
    }

    @Test
    fun restrictedSettingsHintOnlyAtTheAccessibilityStep() {
        val off = switchesOn.copy(accessibility = false, restrictedSettings = true)
        assertTrue(PhoneSetupFlow.view(off).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(restrictedSettings = false)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(accessibility = true)).showAppInfo)
        assertFalse(PhoneSetupFlow.view(off.copy(developerOptions = false)).showAppInfo)
    }

    @Test
    fun usbDebuggingOffBlocksSetupButNotARunningHelper() {
        // USB debugging off: even when paired with wireless debugging on, the flow stops at this step
        val v = PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting))
        assertEquals(SetupStep.UsbDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        // Already running counts as set up (the settings screen has its own hint)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(switchesOn.copy(usbDebugging = false, status = BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun turningASwitchOffGoesBackToThatStep() {
        // Paired but wireless debugging turned off: go back to that step without demanding re-pairing
        val v = PhoneSetupFlow.view(SetupInputs(true, false, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        // Developer options off: even if something failed earlier, go back to the first step
        val f = PhoneSetupFlow.view(SetupInputs(false, true, BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)))
        assertEquals(SetupStep.DeveloperOptions, f.current)
        assertEquals(SetupPhase.Guide, f.phase)
    }

    @Test
    fun pairedAndSwitchOnMeansConnectingEvenIfStatusLags() {
        val v = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.WirelessDebuggingOff))
        assertEquals(SetupPhase.Working, v.phase)
    }

    @Test
    fun unreadableWirelessSwitchDoesNotBlock() {
        // Phones whose switch cannot be read: do not get stuck on the wireless-debugging step; follow the helper's state
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.NotPaired)).current)
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.WirelessDebuggingOff)).current)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(SetupInputs(true, null, BuiltinShellStatus.Running)).phase)
    }

    @Test
    fun failuresLandOnTheStepTheUserMustRedo() {
        val wrong = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)))
        assertEquals(SetupPhase.Failed, wrong.phase)
        assertEquals(SetupStep.PairingCode, wrong.current)
        assertEquals(StepMark.Failed, wrong.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.RePair, wrong.failure?.fix)

        val revoked = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)))
        assertEquals(SetupStep.PairingCode, revoked.current)

        val helper = PhoneSetupFlow.view(SetupInputs(true, true, BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)))
        assertEquals(SetupStep.Connect, helper.current)
        assertEquals(StepMark.Done, helper.marks[SetupStep.PairingCode])
        assertEquals(BuiltinShellFix.Retry, helper.failure?.fix)

        assertEquals(BuiltinShellFix.OpenWifi, BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi).fix)
    }

    @Test
    fun repairWhileRunningStaysOnPairingStepAndFinishesAfterNewPairing() {
        val running = SetupInputs(true, true, BuiltinShellStatus.Running, repair = true, discovery = PairingDiscovery.Found(4000))
        val waiting = PhoneSetupFlow.view(running)
        assertEquals(SetupPhase.Guide, waiting.phase)
        assertEquals(SetupStep.PairingCode, waiting.current)
        assertTrue(waiting.portFound)
        assertTrue(waiting.autoRead)
        assertEquals(BuiltinShellStatus.NotPaired, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = true))
        assertEquals(BuiltinShellStatus.Connecting, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Connecting, repair = true))
        // After a new pairing starts, once re-pairing finishes and the helper keeps running it is success
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(running.copy(status = BuiltinShellStatus.Connecting)).phase)
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(running.copy(repair = false)).phase)
        assertEquals(BuiltinShellStatus.Running, PhoneSetupFlow.effectiveStatus(BuiltinShellStatus.Running, repair = false))
    }

    @Test
    fun asksForNotificationsOnlyAtThePairingStep() {
        val off = fresh.copy(notificationsAllowed = false)
        assertFalse(PhoneSetupFlow.view(off).needsNotifications)
        assertTrue(PhoneSetupFlow.view(off.copy(developerOptions = true, usbDebugging = true, accessibility = true, wirelessDebugging = true)).needsNotifications)
    }

    @Test
    fun pairingStepSaysWhatIsWrongWithTheNotification() {
        val pairing = fresh.copy(developerOptions = true, usbDebugging = true, accessibility = false, accessibilitySkipped = true, wirelessDebugging = true)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.view(pairing).current)
        assertNull(PhoneSetupFlow.view(pairing).notifyIssue)
        // Not being allowed to notify matters most; next, the pairing notification being off; reduced to alert-only does not count as undeliverable
        val off = PhoneSetupFlow.view(pairing.copy(notificationsAllowed = false, pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.NotificationsOff, off.notifyIssue)
        val channelOff = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, channelOff.notifyIssue)
        assertTrue(channelOff.needsNotifications)
        val lowered = PhoneSetupFlow.view(pairing.copy(pairingChannel = PairingChannelHealth.Lowered))
        assertEquals(PairingNotifyIssue.NotHeadsUp, lowered.notifyIssue)
        assertFalse(lowered.needsNotifications)
        // Other steps do not care about notifications
        assertNull(PhoneSetupFlow.view(fresh.copy(pairingChannel = PairingChannelHealth.Off)).notifyIssue)
        // It still warns when a pairing failure leaves the flow on the pairing step
        val failed = PhoneSetupFlow.view(pairing.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), pairingChannel = PairingChannelHealth.Off))
        assertEquals(PairingNotifyIssue.ChannelOff, failed.notifyIssue)
    }

    @Test
    fun pairingCodeNormalization() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize(" 123 456 "))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize("１２３４５６"))
        // Full-width space, full-width dash, and the en/em dashes typed by input methods
        assertEquals("123456", PairingCode.normalize("１２３\u3000４５６"))
        assertEquals("123456", PairingCode.normalize("123－456"))
        assertEquals("123456", PairingCode.normalize("12–34—56"))
        assertNull(PairingCode.normalize(""))
        assertNull(PairingCode.normalize("123 45"))
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12a456"))
        assertEquals(37123, PairingCode.port(" 37123 "))
        assertNull(PairingCode.port("0"))
        assertNull(PairingCode.port("70000"))
    }

    @Test
    fun fakeControlDrivesStatus() = runTest {
        val fake = FakeBuiltinShellControl(pairingResult = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode))
        fake.startDiscovery()
        assertEquals(PairingDiscovery.Searching, fake.pairingDiscovery.value)
        fake.startPairing("123456", 40000)
        assertEquals(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), fake.status.value)
        fake.retry()
        assertEquals(BuiltinShellStatus.Running, fake.status.value)
        fake.unpair()
        assertEquals(BuiltinShellStatus.NotPaired, fake.status.value)
        assertEquals(listOf("discover", "pair 123456 40000", "retry", "unpair"), fake.calls)
    }

    // ---------- "Start from a computer instead" is offered only when needed ----------

    private val atWireless = switchesOn.copy(wirelessDebugging = false)

    @Test
    fun usbFallbackIsHiddenInTheNormalFlow() {
        // Following the normal path: it is not offered at any step, while connecting, or on success
        listOf(
            fresh, fresh.copy(developerOptions = true), atWireless, switchesOn,
            switchesOn.copy(discovery = PairingDiscovery.Found(37000)),
            switchesOn.copy(status = BuiltinShellStatus.Connecting),
            switchesOn.copy(status = BuiltinShellStatus.Running),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode)),
            switchesOn.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi)),
        ).forEach { assertFalse(it.toString(), PhoneSetupFlow.view(it).usbFallback) }
        // Nor when it stalls for a while without reaching the deadline
        assertFalse(PhoneSetupFlow.view(atWireless.copy(stalledMs = PhoneSetupFlow.STALL_MS - 1)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheSystemHasNoWirelessDebugging() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(wirelessMissing = true)).usbFallback)
        // The first two steps (developer options, USB debugging) are needed for a computer start too, so it is not offered before they are done
        assertFalse(PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).usbFallback)
        // Not offered once it succeeds
        assertFalse(PhoneSetupFlow.view(switchesOn.copy(wirelessMissing = true, status = BuiltinShellStatus.Running)).usbFallback)
    }

    @Test
    fun usbFallbackWhenTheUserCannotFindIt() {
        assertTrue(PhoneSetupFlow.view(atWireless.copy(usbAsked = true)).usbFallback)
        // Once shown it stays: it is still there when connecting begins
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(usbAsked = true, status = BuiltinShellStatus.Connecting)).usbFallback)
        // Not offered when the user taps back to redo the first step
        assertFalse(PhoneSetupFlow.view(atWireless.copy(usbAsked = true, revisit = SetupStep.DeveloperOptions)).usbFallback)
    }

    @Test
    fun usbFallbackAfterStallingWhereTheSystemShouldAnswer() {
        val stalled = PhoneSetupFlow.STALL_MS
        assertEquals(SetupStep.WirelessDebugging, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(atWireless)))
        assertTrue(PhoneSetupFlow.view(atWireless.copy(stalledMs = stalled)).usbFallback)
        // The pairing step never finds a pairing window (the system has no pairing service)
        assertEquals(SetupStep.PairingCode, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(switchesOn)))
        assertTrue(PhoneSetupFlow.view(switchesOn.copy(stalledMs = stalled)).usbFallback)
        // After the pairing window is found it is waiting for the user to enter the code, which does not count as stalled
        val found = switchesOn.copy(discovery = PairingDiscovery.Found(37000))
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(found)))
        assertFalse(PhoneSetupFlow.view(found.copy(stalledMs = stalled)).usbFallback)
        // It keeps trying to connect automatically
        val connecting = switchesOn.copy(status = BuiltinShellStatus.Connecting)
        assertEquals(SetupStep.Connect, PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(connecting)))
        assertTrue(PhoneSetupFlow.view(connecting.copy(stalledMs = stalled)).usbFallback)
        // The earlier steps require the user to go and find things; however long that takes does not count
        assertNull(PhoneSetupFlow.stallPoint(PhoneSetupFlow.view(fresh)))
        assertFalse(PhoneSetupFlow.view(fresh.copy(stalledMs = stalled * 10)).usbFallback)
    }

    @Test
    fun usbFallbackAfterFailuresThatMeanWirelessDoesNotWork() {
        listOf(BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.ConnectFailed, BuiltinShellFailure.UsbHelperStopped).forEach { f ->
            val v = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Failed(f)))
            assertEquals(SetupPhase.Failed, v.phase)
            assertTrue(f.name, v.usbFallback)
        }
        // The user says wireless debugging is on, yet the helper reads it as off and does not connect
        val claimed = atWireless.copy(confirmed = setOf(SetupStep.WirelessDebugging), wirelessDebugging = null, status = BuiltinShellStatus.WirelessDebuggingOff)
        val v = PhoneSetupFlow.view(claimed)
        assertEquals(SetupStep.WirelessDebugging, v.suspect?.step)
        assertTrue(v.usbFallback)
    }

    /** A helper started only via the USB command (this phone may have no usable wireless debugging at all); wireless debugging reads as off. */
    private val usbOnly = switchesOn.copy(wirelessDebugging = false, restart = HelperRestart.UsbCommand)

    /** The helper is running: success, with the wireless-debugging and pairing steps marked skipped and wireless debugging never mentioned. */
    @Test
    fun usbStartedHelperRunningIsSuccessWithWirelessStepsSkipped() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Running))
        assertEquals(SetupPhase.Success, v.phase)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        assertEquals(StepMark.Done, v.marks[SetupStep.Connect])
        assertEquals(HelperRestart.UsbCommand, v.restart)
        assertFalse(v.usbFallback)
    }

    /** Mid-handover (the helper is swapping over and the app has just started, waiting for it): show connecting, and do not fall back to that step just because wireless debugging is off. */
    @Test
    fun handoverDoesNotFlipBackToWirelessStep() {
        val v = PhoneSetupFlow.view(usbOnly.copy(status = BuiltinShellStatus.Connecting))
        assertEquals(SetupPhase.Working, v.phase)
        assertNull(v.current)
        // The same for a paired phone that is turning wireless debugging on for the user and connecting
        assertEquals(SetupPhase.Working, PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.Connecting)).phase)
        // When an earlier switch really is off, it goes back to that step as before
        assertEquals(SetupStep.UsbDebugging, PhoneSetupFlow.view(usbOnly.copy(usbDebugging = false, status = BuiltinShellStatus.Connecting)).current)
    }

    /** The helper stopped: stay on the connect step, say that it stopped and offer the USB command without steering to wireless debugging; whoever wants to pair can still tap back to the pairing step. */
    @Test
    fun usbHelperStoppedShowsTheCommandNotWirelessDebugging() {
        val stopped = BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped)
        val v = PhoneSetupFlow.view(usbOnly.copy(status = stopped))
        assertEquals(SetupPhase.Failed, v.phase)
        assertEquals(SetupStep.Connect, v.current)
        assertEquals(stopped, v.failure)
        assertEquals(BuiltinShellFix.RunUsbCommand, v.failure?.fix)
        assertTrue(v.usbFallback)
        assertNull(v.suspect)
        assertEquals(StepMark.Skipped, v.marks[SetupStep.WirelessDebugging])
        assertEquals(StepMark.Skipped, v.marks[SetupStep.PairingCode])
        // The same when the system has no wireless debugging entry at all
        assertEquals(SetupStep.Connect, PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = null, wirelessMissing = true, status = stopped)).current)
        // Tapping back to the pairing step: pairing is offered as usual
        val back = PhoneSetupFlow.view(usbOnly.copy(wirelessDebugging = true, status = stopped, revisit = SetupStep.PairingCode))
        assertEquals(SetupStep.PairingCode, back.current)
        assertTrue(back.revisiting)
    }

    /** Paired phones that can use wireless debugging are unaffected: with wireless debugging off and no helper, they still go back to that step (and connect automatically once it is on). */
    @Test
    fun pairedPhoneStillGoesBackToWirelessStep() {
        val v = PhoneSetupFlow.view(atWireless.copy(status = BuiltinShellStatus.WirelessDebuggingOff, restart = HelperRestart.Wireless))
        assertEquals(SetupStep.WirelessDebugging, v.current)
        assertEquals(SetupPhase.Guide, v.phase)
        val ok = PhoneSetupFlow.view(switchesOn.copy(status = BuiltinShellStatus.Running, restart = HelperRestart.Wireless))
        assertTrue(ok.markList().all { it == StepMark.Done })
    }

    private val noWay = fresh.copy(developerSettingsMissing = true, wirelessMissing = true)

    @Test
    fun environmentWithoutDeveloperOptionsOrWirelessDebuggingSaysSoUpFront() {
        val v = PhoneSetupFlow.view(noWay)
        assertEquals(SetupPhase.Unavailable, v.phase)
        assertNull(v.current)
        assertFalse(v.usbFallback)
        assertTrue(v.markList().all { it == StepMark.Todo })
        // The same after a pairing failure and tapping "pair again"
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed))).phase)
        assertEquals(SetupPhase.Unavailable, PhoneSetupFlow.view(noWay.copy(repair = true)).phase)
    }

    @Test
    fun onlyOneOfTheTwoMissingStillGuides() {
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(developerSettingsMissing = true)).phase)
        assertEquals(SetupPhase.Guide, PhoneSetupFlow.view(fresh.copy(wirelessMissing = true)).phase)
    }

    @Test
    fun aHelperThatRunsOrRanBeforeMeansThereIsAWay() {
        assertEquals(SetupPhase.Success, PhoneSetupFlow.view(noWay.copy(status = BuiltinShellStatus.Running)).phase)
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(status = BuiltinShellStatus.Connecting)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.UsbCommand)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.Wireless)))
        assertFalse(PhoneSetupFlow.unavailable(noWay.copy(restart = HelperRestart.Shizuku)))
        assertTrue(PhoneSetupFlow.unavailable(true, true, BuiltinShellStatus.NotPaired, HelperRestart.None))
    }
}
