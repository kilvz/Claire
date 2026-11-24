package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val launchers = setOf("com.home")
    private val home = launchers + "com.wangzan101.mika"

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(
        vararg a: MainScreenSpill.Arrival,
        topBefore: Int = 3,
        taps: List<MainScreenSpill.Tap> = emptyList(),
        userActiveBefore: Boolean = false,
    ) = MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps, launchers, userActiveBefore)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        // The system brings the app's existing background task to the front instead of creating a new task
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherMikaAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.wangzan101.mika")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        // A takeover (of this task or another task) means moving the task from Claire's display to the main screen
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
    }

    @Test fun userActivityJustBeforeTheActionIsConservative() {
        // The user just touched the main screen before the action started (tapped between two actions, so only a count exists with no exact time): ownership is unclear, so it is not pulled back and is reported as the user using it
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, userActiveBefore = true))
        // No touch before the action: pulled back as usual
        assertEquals(listOf(PullBack(a)), decide(a, userActiveBefore = false))
    }

    @Test fun systemTouchWithoutASemanticClickKeepsTheUsersAppOnTheMainScreen() {
        // If the system delivers touch events, they are supplementary evidence beyond widget clicks
        assertTrue(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertFalse(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        val touch = MainScreenSpill.Tap(since + 400, null)
        assertTrue(decide(arrival(at = since + 900), taps = listOf(touch)).isEmpty())
    }

    @Test fun returningHomeBeforeOpeningAnAppKeepsTheUsersAppOnTheMainScreen() {
        val desktop = arrival(task = 2, pkg = "com.home", at = since + 200, from = MainScreenSpill.MAIN)
        val opened = arrival(at = since + 900)
        // When the desktop and notification shade entries do not send widget clicks, a preceding main-screen navigation back to the desktop is still enough to prove it was a user entry
        assertTrue(decide(desktop, opened).isEmpty())
        // Claire's own main-screen page is not the desktop, so a genuine spill from the Agent must not be taken for a user action
        val mika = arrival(task = 2, pkg = "com.wangzan101.mika", at = since + 200, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(opened)), decide(mika, opened))
    }

    @Test fun userTappingThePopupItselfLeavesItThere() {
        // Right after the popup appears the user taps on it (confirming on screen): the user is using it, so it is not moved
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        // The tap was on another app's window: unclear, so it is moved back as usual
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        // After the one that opened is moved away, the previously topmost one is revealed again
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        // A system chooser, or options in a system package's window matching an app installed twice: left for the user to choose, never moved and never relaunched
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        // Ordinary system pages such as system settings, and ordinary apps: moved back
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        // A tap on an option by the user just now also counts (that tap is the choice)
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        // The previous app revealed after the chooser closes does not count: the user dismissed the question
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        // Something that surfaced long before the chooser closed was not what was chosen
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        // The app that was already in the background on the main screen being brought to the front: that is the copy that was chosen
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        // An app whose tree is unreadable anyway does not count as needing the user, and is moved back as usual
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test fun accountServiceWithoutLauncherIsAUserStep() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // The system account sign-in service: an authenticator is registered and there is no launcher icon, so add-account and full-screen verification-code pages that surface on the main screen are left for the user even without a password field
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
    }

    @Test fun authenticatorAppWithLauncherIsMovedBackAsBefore() {
        val auth = setOf("com.example.accounts", "com.example.chat")
        // A chat app that registered an authenticator for syncing contacts still has a launcher icon: conversation and share pages that surface on the main screen are moved back as usual and are not left behind because of the authenticator
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        // A package that is not an authenticator does not count even without a launcher icon
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }
}
