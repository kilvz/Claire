package com.wangzan101.mika.core.device.shell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryPolicyTest {
    private fun inputs(
        status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed),
        paired: Boolean = true,
        wireless: Boolean? = true,
    ) = RecoveryInputs(paired = paired, wirelessDebugging = wireless, status = status)

    @Test
    fun backoffDoublesUpToACap() {
        assertEquals(
            listOf(0L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(RecoveryPolicy::backoffMs),
        )
    }

    @Test
    fun runningNeedsNothing() {
        assertEquals(RecoveryDecision.Healthy, RecoveryPolicy.decide(inputs(BuiltinShellStatus.Running), 0))
    }

    @Test
    fun helperVanishedIsRetriedWithBackoff() {
        // Unplugging USB / restarting adbd kills every shell-uid process: whatever was reported last, retry unless the failure requires the user to act
        listOf(
            BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed),
            BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed),
            BuiltinShellStatus.Failed(BuiltinShellFailure.Unknown),
            BuiltinShellStatus.WirelessDebuggingOff,
        ).forEach { s ->
            assertEquals("$s", RecoveryDecision.Try(0), RecoveryPolicy.decide(inputs(s), 0))
            assertEquals("$s", RecoveryDecision.Try(4_000), RecoveryPolicy.decide(inputs(s), 2))
        }
    }

    @Test
    fun unknownWirelessStateStillTries() {
        // Some phones cannot read the wireless debugging switch, nor whether the network is trusted: both are treated as "give it a try"
        assertEquals(RecoveryDecision.Try(0), RecoveryPolicy.decide(inputs(wireless = null), 0))
    }

    @Test
    fun preconditionsThatStopTrying() {
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(paired = false), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(BuiltinShellStatus.NotPaired), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.WirelessDebuggingOff), RecoveryPolicy.decide(inputs(wireless = false), 0))
    }

    @Test
    fun failuresThatNeedTheUserStopImmediately() {
        listOf(
            BuiltinShellFailure.AuthorizationRevoked, BuiltinShellFailure.WrongCode,
            BuiltinShellFailure.PairingWindowClosed, BuiltinShellFailure.NoWifi,
        ).forEach { f ->
            assertEquals("$f", RecoveryDecision.Stop(StopReason.NeedsUser), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Failed(f)), 1))
        }
    }

    @Test
    fun connectingWaitsInsteadOfPilingOn() {
        assertEquals(RecoveryDecision.Wait(2_000), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Connecting), 1))
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        assertEquals(RecoveryDecision.Stop(StopReason.OutOfAttempts), RecoveryPolicy.decide(inputs(), RecoveryPolicy.MAX_ATTEMPTS))
        assertEquals(RecoveryDecision.Stop(StopReason.OutOfAttempts), RecoveryPolicy.decide(inputs(BuiltinShellStatus.Connecting), RecoveryPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun loopRecoversAfterAFewFailures() = runTest {
        var status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed)
        var tries = 0
        val slept = mutableListOf<Long>()
        val outcome = RecoveryPolicy.recover(
            inputs = { inputs(status) },
            attempt = { tries++; if (tries == 3) status = BuiltinShellStatus.Running },
            sleep = { slept += it },
        )
        assertEquals(RecoveryOutcome(recovered = true, attempts = 3, stoppedBecause = null), outcome)
        assertEquals(listOf(0L, 2_000L, 4_000L), slept)
    }

    @Test
    fun loopStopsWhenUserMustAct() = runTest {
        var status: BuiltinShellStatus = BuiltinShellStatus.Failed(BuiltinShellFailure.ConnectFailed)
        val outcome = RecoveryPolicy.recover(
            inputs = { inputs(status) },
            attempt = { status = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked) },
            sleep = {},
        )
        assertEquals(RecoveryOutcome(recovered = false, attempts = 1, stoppedBecause = StopReason.NeedsUser), outcome)
    }

    @Test
    fun loopIsBounded() = runTest {
        var tries = 0
        val outcome = RecoveryPolicy.recover(inputs = { inputs() }, attempt = { tries++ }, sleep = {})
        assertEquals(RecoveryPolicy.MAX_ATTEMPTS, tries)
        assertEquals(StopReason.OutOfAttempts, outcome.stoppedBecause)
    }

    @Test
    fun alreadyRunningDoesNotTouchAnything() = runTest {
        var tries = 0
        val outcome = RecoveryPolicy.recover(inputs = { inputs(BuiltinShellStatus.Running) }, attempt = { tries++ }, sleep = {})
        assertEquals(0, tries)
        assertEquals(true, outcome.recovered)
        assertNull(outcome.stoppedBecause)
    }

    @Test
    fun bootRestoreGate() {
        // On by default and filled in once a connection is available: the switch was never toggled, so having it applied counts as consent
        val on = RecoveryConsent(applied = setOf(RecoveryCapability.RestoreAfterReboot))
        for (sdk in 31..33) assertEquals(BootRestore.Gate.Act, BootRestore.gate(sdkInt = sdk, consent = on, holdsPermission = true))
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 30, consent = on, holdsPermission = true))
        // Wanted by default but not applied yet (never paired), or turned off by the user in person: do nothing
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 34, consent = RecoveryConsent(), holdsPermission = true))
        val declined = RecoveryConsent(declined = setOf(RecoveryCapability.RestoreAfterReboot))
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(sdkInt = 34, consent = declined, holdsPermission = true))
        // It was turned on, but the grant is gone (revoked by the system or the user): report it truthfully instead of forcing a write
        assertEquals(BootRestore.Gate.PermissionMissing, BootRestore.gate(sdkInt = 34, consent = on, holdsPermission = false))
    }
}
