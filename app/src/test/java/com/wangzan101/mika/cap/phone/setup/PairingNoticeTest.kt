package com.wangzan101.mika.cap.phone.setup

import android.app.NotificationManager
import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.device.shell.BuiltinShellFailure
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.core.device.shell.PairingChannelHealth
import com.wangzan101.mika.core.device.shell.PairingDiscovery
import com.wangzan101.mika.core.device.shell.PairingScreen
import com.wangzan101.mika.core.device.shell.PairingScreenReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNoticeTest {
    @get:Rule val texts = ChineseTexts()

    /** Only pops the input once the pairing window is found; while still searching, post one quietly first (so it goes out before the background process is frozen); post nothing when not searching. */
    @Test
    fun codeEntryPopsOnceThePairingWindowIsFoundAndWaitsQuietlyBefore() {
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(BuiltinShellStatus.WirelessDebuggingOff, PairingDiscovery.Searching))
        assertNull(PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Idle))
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Found(37001)))
    }

    /** The quietly prepared one: posts right away even with accessibility on (it must beat the freeze), and never re-alerts afterwards. */
    @Test
    fun quietEntryIsPostedAtOnceAndNeverRealerts() {
        val quiet = PairingNotice.EnterCode(null, quiet = true)
        assertEquals(0L, PairingNotice.delayBefore(quiet, autoRead = true))
        assertNull(PairingNotice.realertAfter(quiet, 0))
    }

    @Test
    fun enterCodeThenPairingThenSuccess() {
        val found = PairingDiscovery.Found(1)
        assertEquals(PairingNotice.EnterCode(1), PairingNotice.of(BuiltinShellStatus.NotPaired, found))
        assertEquals(PairingNotice.Working, PairingNotice.of(BuiltinShellStatus.Connecting, found))
        assertEquals(PairingNotice.Done, PairingNotice.of(BuiltinShellStatus.Running, PairingDiscovery.Idle))
    }

    @Test
    fun pairingFailuresKeepTheInputWithHowToRetry() {
        assertEquals(
            PairingNotice.EnterCode(5, "配对码不对或已失效：在配对窗口重新点「使用配对码配对设备」拿新的码，再填一次"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), PairingDiscovery.Found(5)),
        )
        // Window closed (pairing window not found) still keeps the input, with the same instructions
        val closed = PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), PairingDiscovery.Searching)
        closed as PairingNotice.EnterCode
        assertNull(closed.port)
        assertTrue(closed.error!!.endsWith("拿新的码，再填一次"))
        // Failures after pairing ask the user to go back to Claire
        assertEquals(
            PairingNotice.Problem("连上了,但没能准备好"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), PairingDiscovery.Found(5)),
        )
    }

    /** Pairing revoked: the guide page makes the user re-pair; once the pairing window is found it offers the input exactly as when not paired, and while still searching it quietly prepares one the same way; when not searching it still asks the user to go back to Claire. */
    @Test
    fun revokedPairingGetsTheInputOnceThePairingWindowIsFound() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(revoked, PairingDiscovery.Found(37001)))
        assertEquals(PairingNotice.EnterCode(null, quiet = true), PairingNotice.of(revoked, PairingDiscovery.Searching))
        assertEquals(PairingNotice.Problem("配对失效了"), PairingNotice.of(revoked, PairingDiscovery.Idle))
        // Helper started by the USB launch command stopped: no input even while searching for the pairing window
        assertEquals(
            PairingNotice.Problem("手机助手已停止"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Searching),
        )
        // Helper started by the USB launch command stopped and needs the command run again: no input even when the pairing window is found
        assertEquals(
            PairingNotice.Problem("手机助手已停止"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Found(37001)),
        )
    }

    /** When the pairing window is found, whether the notice offers code input matches whether auto-read would try (PairingScreen.attempt) for every status. */
    @Test
    fun codeInputMatchesWhatAutoReadWouldTry() {
        val reading = PairingScreenReading("123456", null)
        val statuses = listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
        ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }
        statuses.forEach { s ->
            val input = PairingNotice.of(s, PairingDiscovery.Found(5)) is PairingNotice.EnterCode
            assertEquals(s.toString(), PairingScreen.attempt(reading, portFound = true, s, emptySet()) != null, input)
        }
    }

    @Test
    fun codeEntryPopsAtOnceWithoutAccessibilityAndWaitsForAutoRead() {
        val enter = PairingNotice.EnterCode(5)
        assertEquals(0L, PairingNotice.delayBefore(enter, autoRead = false))
        assertEquals(PairingNotice.AUTO_READ_GRACE_MS, PairingNotice.delayBefore(enter, autoRead = true))
        assertEquals(0L, PairingNotice.delayBefore(PairingNotice.Working, autoRead = true))
    }

    @Test
    fun realertsAreCappedAtThree() {
        val enter = PairingNotice.EnterCode(5)
        val waits = generateSequence(0) { it + 1 }.map { PairingNotice.realertAfter(enter, it) }.takeWhile { it != null }.toList()
        assertEquals(3, waits.size)
        assertEquals(PairingNotice.MAX_REALERTS, waits.size)
        // Each later interval is longer than the one before
        assertEquals(waits.filterNotNull().sorted(), waits)
        // The wrong-code hint re-alerts too; other states do not
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(PairingNotice.EnterCode(5, "x"), 0))
        listOf(PairingNotice.Working, PairingNotice.Done, PairingNotice.Problem("x")).forEach { assertNull(PairingNotice.realertAfter(it, 0)) }
    }

    @Test
    fun aSubmittedCodeShowsPairingUntilThePairingStarts() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        // Code submitted, pairing not started yet: ignore the pre-submission status and do not finish (same when the helper was already running; the status will still change once pairing starts)
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(revoked, PairingDiscovery.Searching, repairing = false, submitted = true))
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = false, submitted = true))
        // Without a submitted code, follow the status as usual
        assertEquals(PairingNotice.Problem("配对失效了") to false, PairingNotice.follow(revoked, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    @Test
    fun followingEndsOnlyWhenRunningOutsideARepair() {
        // During re-pairing the stale Running status does not count as paired: do not finish, and quietly prepare the code-entry notice just as when not paired
        assertEquals(PairingNotice.EnterCode(null, quiet = true) to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = true, submitted = false))
        assertEquals(PairingNotice.Done to true, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    /** Notice order within one pairing run: prepare quietly first, switch to the popping one once the window is found, Working after the code is submitted, then success and finish; each consecutive pair differs (a notice is posted again only when it changed). */
    @Test
    fun aPairingRunGoesQuietThenAlertThenWorkingThenDone() {
        val steps = listOf(
            BuiltinShellStatus.NotPaired to PairingDiscovery.Searching,
            BuiltinShellStatus.NotPaired to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Connecting to PairingDiscovery.Found(37001),
            BuiltinShellStatus.Running to PairingDiscovery.Idle,
        ).map { (s, d) -> PairingNotice.follow(s, d, repairing = false, submitted = false) }
        assertEquals(
            listOf(
                PairingNotice.EnterCode(null, quiet = true) to false,
                PairingNotice.EnterCode(37001) to false,
                PairingNotice.Working to false,
                PairingNotice.Done to true,
            ),
            steps,
        )
        // The notice after the window is found pops and re-alerts
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(steps[1].first!!, 0))
    }

    @Test
    fun channelIsCreatedHighWhenMissing() {
        assertEquals(ChannelPlan.Create(PairingChannel.IDS.last()), PairingChannel.plan(emptyList()))
        assertEquals(PairingChannelHealth.Ready, PairingChannel.plan(emptyList()).health)
    }

    @Test
    fun channelKeepsWhatWorksMigratesOldOnesAndRespectsTheUser() {
        val old = PairingChannel.IDS.first()
        val new = PairingChannel.IDS.last()
        // Created by an older version but already high enough: keep using it, do not switch ids
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Ready),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_HIGH, userSet = false))),
        )
        // Created by an older version at too low an importance and untouched by the user: rebuild under the new id
        assertEquals(
            ChannelPlan.Create(new),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = false))),
        )
        // The user lowered it themselves: do not change it back, only warn
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = true))),
        )
        // The user turned it off: nothing can be posted
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Off),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_NONE, userSet = true))),
        )
        // The newest id is not high enough either and there is no newer one to switch to: only warn
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_LOW, userSet = false))),
        )
        // When both old and new exist, the new one wins
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Ready),
            PairingChannel.plan(
                listOf(
                    ChannelSnapshot(old, NotificationManager.IMPORTANCE_LOW, userSet = true),
                    ChannelSnapshot(new, NotificationManager.IMPORTANCE_HIGH, userSet = false),
                ),
            ),
        )
    }
}
