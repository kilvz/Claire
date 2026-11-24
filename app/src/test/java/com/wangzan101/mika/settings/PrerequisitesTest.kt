package com.wangzan101.mika.settings

import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.ShellTransport
import com.wangzan101.mika.core.device.shell.ShizukuPlatformState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrerequisitesTest {
    private val ready = SystemStatus(
        sdkInt = 34,
        builtinShell = BuiltinShellStatus.Running,
        accessibility = AccessibilityState.On,
        notificationsAllowed = true,
    )

    @Test
    fun allReadyCanStart() {
        val c = Prerequisites.check(ready, hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.problems.isEmpty())
    }

    @Test
    fun android11CanStartWithoutPhoneControl() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertTrue(c.blockers.isEmpty())
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidDoesNotAskToSetUpUnsupportedPhoneFeatures() {
        val c = Prerequisites.check(
            ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
            hasMainModel = true,
        )
        assertTrue(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.SystemTooOld), c.problems)
    }

    @Test
    fun oldAndroidStillNeedsAMainModel() {
        val c = Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = false)
        assertFalse(c.canStartTask)
        assertFalse(c.phoneReady)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun noMainModelBlocksStart() {
        val c = Prerequisites.check(ready, hasMainModel = false)
        assertFalse(c.canStartTask)
        assertEquals(listOf(Problem.NoModel), c.blockers)
    }

    @Test
    fun accessibilityOnlyNeededForPhoneApps() {
        for (a in listOf(AccessibilityState.Off, AccessibilityState.EnabledNotRunning)) {
            val c = Prerequisites.check(ready.copy(accessibility = a), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.Accessibility(a)), c.problems)
        }
    }

    @Test
    fun restrictedSettingsStateReachesTheAccessibilityProblem() {
        val c = Prerequisites.check(
            ready.copy(accessibility = AccessibilityState.EnabledNotRunning, restrictedSettings = true),
            hasMainModel = true,
        )
        assertEquals(
            listOf(Problem.Accessibility(AccessibilityState.EnabledNotRunning, restrictedSettings = true)),
            c.problems,
        )
    }

    @Test
    fun notificationsOffOnlyWarns() {
        val c = Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)
        assertTrue(c.canStartTask)
        assertEquals(listOf(Problem.NotificationsOff), c.problems)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun android12And13CanControlAppsWithAFocusWarning() {
        for (sdk in 31..33) {
            val c = Prerequisites.check(ready.copy(sdkInt = sdk), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertTrue(c.phoneReady)
            assertEquals(listOf(Problem.FocusMayLeak), c.problems)
        }
        assertTrue(Prerequisites.check(ready.copy(sdkInt = 34), hasMainModel = true).problems.isEmpty())
        assertEquals(listOf(Problem.SystemTooOld), Prerequisites.check(ready.copy(sdkInt = 30), hasMainModel = true).problems)
    }

    @Test
    fun android12StillNeedsHelperAndAccessibilityForPhoneControl() {
        for (sdk in 31..32) {
            val c = Prerequisites.check(
                ready.copy(sdkInt = sdk, builtinShell = BuiltinShellStatus.NotPaired, accessibility = AccessibilityState.Off),
                hasMainModel = true,
            )
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf(Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off), Problem.FocusMayLeak), c.problems)
        }
    }

    @Test
    fun problemsOrderedBySeverity() {
        val c = Prerequisites.check(
            SystemStatus(33, BuiltinShellStatus.NotPaired, AccessibilityState.Off, false), hasMainModel = false,
        )
        assertEquals(
            listOf(
                Problem.PhoneHelper(BuiltinShellStatus.NotPaired), Problem.Accessibility(AccessibilityState.Off),
                Problem.NoModel, Problem.NotificationsOff, Problem.FocusMayLeak,
            ),
            c.problems,
        )
    }

    // Fix 1 M4: warn when the "needs you" alert is lowered by the user or the system, but do not block tasks
    @Test
    fun attentionLoweredIsAWarning() {
        val c = Prerequisites.check(ready.copy(attentionLowered = true), hasMainModel = true)
        assertEquals(listOf<Problem>(Problem.AttentionLowered(attention = true, result = false)), c.problems)
        assertTrue(c.canStartTask)
    }

    // Every combination of the two channels "needs your attention" and "task result": one check reports which one was lowered
    @Test
    fun loweredChannelCombinations() {
        fun problems(attention: Boolean, result: Boolean) =
            Prerequisites.check(ready.copy(attentionLowered = attention, resultLowered = result), hasMainModel = true).problems
        assertEquals(emptyList<Problem>(), problems(false, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, false)), problems(true, false))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(false, true)), problems(false, true))
        assertEquals(listOf<Problem>(Problem.AttentionLowered(true, true)), problems(true, true))
        assertTrue(Prerequisites.check(ready.copy(resultLowered = true), hasMainModel = true).canStartTask)
    }

    // When the master switch is off, only "notifications not allowed" is reported; the channels are not reported again
    @Test
    fun masterSwitchOffHidesChannelProblems() {
        val c = Prerequisites.check(
            ready.copy(notificationsAllowed = false, attentionLowered = true, resultLowered = true), hasMainModel = true,
        )
        assertEquals(listOf<Problem>(Problem.NotificationsOff), c.problems)
    }

    // Web and sandbox tasks do not need the built-in helper: they can still start, and phone_unavailable is only reported once the phone is used; the problem is still listed as a hint
    @Test
    fun phoneHelperOnlyNeededForPhoneApps() {
        listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting,
            BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode),
        ).forEach { s ->
            val c = Prerequisites.check(ready.copy(builtinShell = s), hasMainModel = true)
            assertTrue(c.canStartTask)
            assertFalse(c.phoneReady)
            assertEquals(listOf<Problem>(Problem.PhoneHelper(s)), c.problems)
        }
        // Below Android 12, only the system-too-old problem is reported
        assertEquals(
            listOf<Problem>(Problem.SystemTooOld),
            Prerequisites.check(ready.copy(sdkInt = 30, builtinShell = BuiltinShellStatus.NotPaired), hasMainModel = true).problems,
        )
    }

    @Test
    fun shizukuTransportFieldsAndPhoneHelperGating() {
        val shizukuStatus = ready.copy(
            shellTransport = ShellTransport.Shizuku,
            shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = true),
            wirelessPaired = false,
            shizukuStart = true,
        )
        val ok = Prerequisites.check(shizukuStatus, hasMainModel = true)
        assertTrue(ok.canStartTask)
        assertTrue(ok.phoneReady)
        assertTrue(ok.problems.isEmpty())

        val needPerm = BuiltinShellStatus.Failed(BuiltinShellFailure.ShizukuPermissionNeeded)
        val blocked = Prerequisites.check(
            shizukuStatus.copy(
                builtinShell = needPerm,
                shizuku = ShizukuPlatformState(installed = true, running = true, permissionGranted = false),
            ),
            hasMainModel = true,
        )
        assertTrue(blocked.canStartTask)
        assertFalse(blocked.phoneReady)
        assertEquals(listOf<Problem>(Problem.PhoneHelper(needPerm)), blocked.problems)
    }
}
