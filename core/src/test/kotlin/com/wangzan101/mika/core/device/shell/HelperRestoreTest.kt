package com.wangzan101.mika.core.device.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRestoreTest {
    private val mika = PermissionCommands.accessibilityService("com.wangzan101.mika")
    private val other = "com.example.reader/.ReaderService"
    private val yes = RestoreWish(revive = true, accessibility = true)

    @Test
    fun wishFollowsCapsuleModeTasksAndAccessibility() {
        assertTrue(RestoreWish.of(capsuleAlways = true, taskRunning = false, accessibilityWanted = true, accessibilityOn = false).revive)
        assertTrue(RestoreWish.of(capsuleAlways = false, taskRunning = true, accessibilityWanted = true, accessibilityOn = false).revive)
        // Accessibility is still on: it must also come back and have its grant restored after an abnormal death; repair must not be limited to permanent capsule mode or to having a task
        val accessibility = RestoreWish.of(capsuleAlways = false, taskRunning = false, accessibilityWanted = true, accessibilityOn = true)
        assertTrue(accessibility.revive)
        assertTrue(accessibility.accessibility)
        assertEquals(RestoreWish.NONE, RestoreWish.of(capsuleAlways = false, taskRunning = false, accessibilityWanted = true, accessibilityOn = false))
    }

    @Test
    fun explicitExitSuppressesEveryRestorePath() {
        for (input in listOf(
            RestoreWish.of(capsuleAlways = true, taskRunning = false, accessibilityWanted = true, accessibilityOn = true, userExited = true),
            RestoreWish.of(capsuleAlways = false, taskRunning = true, accessibilityWanted = true, accessibilityOn = true, userExited = true),
            RestoreWish.of(capsuleAlways = false, taskRunning = false, accessibilityWanted = true, accessibilityOn = true, userExited = true),
        )) assertEquals(RestoreWish.NONE, input)
    }

    @Test
    fun accessibilityOnlyWhenWantedAndOn() {
        assertTrue(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = true).accessibility)
        // The user switched off "turn on accessibility for me" in Claire
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = false, accessibilityOn = true).accessibility)
        // The user turned it off in system settings while Claire was alive
        assertFalse(RestoreWish.of(true, false, accessibilityWanted = true, accessibilityOn = false).accessibility)
    }

    @Test
    fun flagsRoundTrip() {
        for (w in listOf(RestoreWish.NONE, yes, RestoreWish(true, false), RestoreWish(false, true))) {
            assertEquals(w, RestoreWish.fromFlags(w.flags))
        }
        assertEquals(0, RestoreWish.NONE.flags)
        assertEquals(RestoreWish.REVIVE or RestoreWish.ACCESSIBILITY, yes.flags)
    }

    @Test
    fun senderSendsOnNewShellOrChangedWish() {
        val sent = mutableListOf<Pair<String, RestoreWish>>()
        val sender = RestoreWishSender<String> { s, w -> sent += s to w; true }
        val a = "helper-a"
        sender.update(null, yes)
        assertTrue("no helper, nothing to hand over", sent.isEmpty())
        sender.update(a, yes)
        sender.update(a, yes)
        assertEquals(listOf(a to yes), sent)
        sender.update(a, RestoreWish.NONE)
        assertEquals(a to RestoreWish.NONE, sent.last())
        // The helper was replaced (a new Binder): hand the wish over again even though it has not changed
        val b = String(charArrayOf('h'))
        sender.update(b, RestoreWish.NONE)
        assertEquals(3, sent.size)
        // The helper disappears and comes back: hand it over again
        sender.update(null, RestoreWish.NONE)
        sender.update(b, RestoreWish.NONE)
        assertEquals(4, sent.size)
    }

    @Test
    fun senderRetriesAfterFailure() {
        var ok = false
        var calls = 0
        val sender = RestoreWishSender<String> { _, _ -> calls++; ok }
        sender.update("h", yes)
        ok = true
        sender.update("h", yes)
        sender.update("h", yes)
        assertEquals("failures do not count as handed over and are retried next time; a successful handover is not repeated", 2, calls)
    }

    @Test
    fun policyDoesNotRestoreForceStopButRecoversAfterManualLaunch() {
        val p = HelperRestorePolicy()
        assertEquals(HelperRestorePolicy.Decision.ForceStopped, p.decide(yes, appBack = false, now = 1_000, forceStopped = true))
        assertTrue("a force stop does not consume exception-recovery attempts", p.report(1_000).empty)
        // Launching it manually makes the system clear the stopped flag; if it dies abnormally again later, normal restore still works
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, appBack = false, now = 2_000, forceStopped = false))
    }

    @Test
    fun policyRestoresOnlyWhenWantedAndAppNotBack() {
        val p = HelperRestorePolicy()
        assertEquals(HelperRestorePolicy.Decision.NotWanted, p.decide(RestoreWish.NONE, appBack = false, now = 1_000))
        assertEquals(HelperRestorePolicy.Decision.AppBack, p.decide(yes, appBack = true, now = 1_000))
        assertTrue("never attempted", p.report(1_000).empty)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, appBack = false, now = 2_000))
        assertEquals(HelperRestoreReport(2_000, 1, 0), p.report(2_000))
    }

    @Test
    fun policyBacksOffWithinWindowThenAllowsAgain() {
        val p = HelperRestorePolicy(maxAttempts = 3, windowMs = 600_000)
        repeat(3) { assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 10_000L + it * 30_000)) }
        // Raised and immediately died again: the fourth attempt within the window does not raise it
        assertEquals(HelperRestorePolicy.Decision.BackedOff, p.decide(yes, false, now = 100_000))
        assertEquals(HelperRestoreReport(70_000, 3, 100_000), p.report(100_000))
        // Once the first attempt slides out of the window, another one is allowed
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 610_000))
        assertEquals(3, p.report(610_000).recent)
        assertEquals(0, p.report(10 * 600_000L).recent)
    }

    @Test
    fun policyIgnoresClockGoingBack() {
        val p = HelperRestorePolicy(maxAttempts = 1, windowMs = 600_000)
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 500_000))
        assertEquals(HelperRestorePolicy.Decision.Restore, p.decide(yes, false, now = 1_000))
    }

    @Test
    fun accessibilityCommandsAppendWithoutDuplicates() {
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$other:$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility(other, mika),
        )
        assertEquals(
            listOf("settings put secure enabled_accessibility_services '$mika'", "settings put secure accessibility_enabled 1"),
            RestoreCommands.accessibility("null", mika),
        )
        // Already in the list (either spelling counts): do not rewrite the list, only turn on the master switch
        assertEquals(listOf(RestoreCommands.ENABLE_ACCESSIBILITY), RestoreCommands.accessibility("$mika:$other", mika))
        assertEquals(
            listOf(RestoreCommands.ENABLE_ACCESSIBILITY),
            RestoreCommands.accessibility("com.wangzan101.mika/com.wangzan101.mika.a11y.MikaAccessibilityService", mika),
        )
    }

    @Test
    fun startCheckClassifiesLoss() {
        val removed = A11yObservation(enabled = false, connected = false)
        val unbound = A11yObservation(enabled = true, connected = false)
        val fine = A11yObservation(enabled = true, connected = true)
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = false, expected = true))
        // Removed at startup and repaired afterwards: a removal is still recorded
        assertEquals(A11yLoss.Removed, A11yStartCheck.loss(removed, connectedSoon = true, expected = true))
        assertEquals(A11yLoss.Unbound, A11yStartCheck.loss(unbound, connectedSoon = false, expected = true))
        assertNull("the system bound it by itself within seconds", A11yStartCheck.loss(unbound, connectedSoon = true, expected = true))
        assertNull(A11yStartCheck.loss(fine, connectedSoon = true, expected = true))
        assertNull("never connected (not set up yet) does not count", A11yStartCheck.loss(removed, connectedSoon = false, expected = false))
    }
}
