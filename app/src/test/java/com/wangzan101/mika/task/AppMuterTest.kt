package com.wangzan101.mika.task

import com.wangzan101.mika.cap.phone.shell.AppAudioKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Muting rules: mute before launch, mute a newly appearing App once mid-task, mute a copy under another user separately, restore everything at the end, and record honestly when muting did not succeed. */
class AppMuterTest {
    /** Fake shell that records every PLAY_AUDIO write; keys are [AppAudioKey]. */
    private class FakeAudio : AudioPort {
        val modes = mutableMapOf<String, String>()
        val mutes = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        override fun mode(packageName: String) = if (packageName in failing) null else modes[packageName] ?: "default"
        override fun mute(packageName: String): Boolean {
            if (packageName in failing) return false
            mutes += packageName
            modes[packageName] = "ignore"
            return true
        }
        override fun setMode(packageName: String, mode: String): Boolean {
            if (packageName in failing) return false
            modes[packageName] = mode
            return true
        }
    }

    private val audio = FakeAudio()
    private val store = MemoryMarker()
    private val record = AudioMute(audio, store)
    private var enabled = true
    private val onMain = mutableSetOf<String>()
    private val failed = mutableListOf<String>()
    private val muter = AppMuter(
        record,
        enabled = { enabled },
        mutable = { it != "mika.self" && it != "system.ui" },
        onMainScreen = { pkg, user -> AppAudioKey.of(pkg, user) in onMain },
        failed = { failed += it },
    )

    @Test fun mutesBeforeLaunchAndDoesNotMuteAgainOnceOpened() {
        muter.beforeOpen("video.app", null)
        // The launch has not been issued yet, but the mute is already in place
        assertEquals(listOf("video.app"), audio.mutes)
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), audio.mutes)
        assertTrue(failed.isEmpty())
    }

    @Test fun refusedLaunchRestoresTheEarlyMute() {
        audio.modes["video.app"] = "allow"
        muter.beforeOpen("video.app", null)
        muter.notOpened("video.app", null)
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
        // For an App that already opened, a refused launch does not restore (it is still on screen)
        muter.beforeOpen("music.app", null)
        muter.opened("music.app", null)
        muter.notOpened("music.app", null)
        assertEquals("ignore", audio.modes["music.app"])
    }

    @Test fun earlyMuteFailureIsReportedOnlyWhenTheAppOpens() {
        audio.failing += "video.app"
        muter.beforeOpen("video.app", null)
        assertTrue(failed.isEmpty())
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), failed)
    }

    @Test fun newPackageMidTaskIsMutedOnce() {
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        // Another App launched from the target App: mute on first sight, do not mute again afterwards
        muter.opened("player.app", null)
        muter.opened("player.app", null)
        muter.opened("feed.app", null)
        assertEquals(listOf("feed.app", "player.app"), audio.mutes)
    }

    @Test fun copyInAnotherUserIsMutedSeparately() {
        muter.beforeOpen("chat.app", 10)
        muter.opened("chat.app", 10)
        assertEquals(listOf("chat.app@10"), audio.mutes)
        assertFalse("chat.app" in audio.modes)
        // Mute the original copy separately when it shows up
        muter.opened("chat.app", null)
        assertEquals(listOf("chat.app@10", "chat.app"), audio.mutes)
    }

    @Test fun restoreAllAtEndCoversEarlyAndMidTaskMutes() {
        audio.modes["feed.app"] = "allow"
        audio.modes["chat.app@10"] = "deny"
        muter.beforeOpen("feed.app", null)
        muter.opened("feed.app", null)
        muter.opened("player.app", null)
        muter.opened("chat.app", 10)
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["feed.app"])
        assertEquals("default", audio.modes["player.app"])
        assertEquals("deny", audio.modes["chat.app@10"])
        assertNull(store.saved)
    }

    @Test fun neverTouchesOwnOrSystemOrMainScreenApps() {
        muter.opened("mika.self", null)
        muter.opened("system.ui", null)
        onMain += "maps.app"
        muter.beforeOpen("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        // Visible to the user on the main screen: do not mute, record honestly that it may make sound
        muter.opened("maps.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertEquals(listOf("maps.app"), failed)
    }

    @Test fun switchedOffMeansNothingIsMuted() {
        enabled = false
        muter.beforeOpen("video.app", null)
        muter.opened("video.app", null)
        assertTrue(audio.mutes.isEmpty())
        assertTrue(failed.isEmpty())
    }

    @Test fun midTaskFailureIsNoted() {
        audio.failing += "player.app"
        muter.opened("player.app", null)
        assertEquals(listOf("player.app"), failed)
    }

    @Test fun releaseForTakeoverThenMuteAgain() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.release("video.app", null))
        assertEquals("allow", audio.modes["video.app"])
        muter.opened("video.app", null)
        assertEquals("ignore", audio.modes["video.app"])
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
    }

    @Test fun manualUnmuteKeepsSoundUntilTaskEnd() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.unmute("video.app", null))
        assertEquals("allow", audio.modes["video.app"])
        // The user explicitly wants sound: reopening, reappearing and early muting must not undo it
        muter.opened("video.app", null)
        muter.beforeOpen("video.app", null)
        assertEquals(listOf("video.app"), audio.mutes)
        assertNull(store.saved)
        // No mute is left to restore, so task cleanup does not revoke the user's choice either
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
    }

    @Test fun manualMuteAgainRestoresAutomaticMutingAndTaskEndRestore() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.unmute("video.app", null))
        assertTrue(muter.muteAgain("video.app", null))
        assertEquals("ignore", audio.modes["video.app"])
        // Back to automatic management: reappearing is not muted again
        muter.opened("video.app", null)
        assertEquals(listOf("video.app", "video.app"), audio.mutes)
        // Muting again still goes through the ledger, and cleanup restores the original mode
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
    }

    @Test fun manualMuteAgainMutesEvenWhenVisibleOnMainScreen() {
        audio.modes["maps.app"] = "allow"
        onMain += "maps.app"
        muter.opened("maps.app", null)
        assertTrue(failed.isNotEmpty())
        assertTrue(muter.unmute("maps.app", null))
        // The user explicitly mutes again: not blocked by the main-screen visibility check, symmetric with unmute's explicit precedence
        assertTrue(muter.muteAgain("maps.app", null))
        assertEquals("ignore", audio.modes["maps.app"])
    }

    @Test fun failedManualMuteAgainKeepsRestoreStateForRetry() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.unmute("video.app", null))
        audio.failing += "video.app"
        assertFalse(muter.muteAgain("video.app", null))
        assertTrue("video.app" in muter.userUnmuted.value)
        audio.failing -= "video.app"
        assertTrue(muter.muteAgain("video.app", null))
        assertTrue(muter.userUnmuted.value.isEmpty())
    }

    @Test fun userUnmutedFlowTracksManualToggles() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.userUnmuted.value.isEmpty())
        assertTrue(muter.unmute("video.app", null))
        assertEquals(setOf("video.app"), muter.userUnmuted.value)
        assertTrue(muter.muteAgain("video.app", null))
        assertTrue(muter.userUnmuted.value.isEmpty())
    }

    @Test fun manualUnmuteSurvivesTakeoverCycles() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        assertTrue(muter.unmute("video.app", null))
        // Later takeover hand-backs still call opened; the user's choice for this task stays in effect
        assertTrue(muter.release("video.app", null))
        muter.opened("video.app", null)
        assertTrue(muter.release("video.app", null))
        muter.opened("video.app", null)
        assertEquals(listOf("video.app"), audio.mutes)
        assertEquals("allow", audio.modes["video.app"])
    }

    @Test fun manualUnmuteWithoutLedgerAlsoSkipsFutureAutomaticMute() {
        assertTrue(muter.unmute("video.app", 10))
        muter.beforeOpen("video.app", 10)
        muter.opened("video.app", 10)
        assertTrue(audio.mutes.isEmpty())
        assertTrue(failed.isEmpty())
    }

    @Test fun failedManualUnmuteKeepsLedgerAndRepeatedClickRetries() {
        audio.modes["video.app"] = "allow"
        muter.beforeOpen("video.app", null)
        audio.failing += "video.app"
        assertFalse(muter.unmute("video.app", null))
        assertEquals("ignore", audio.modes["video.app"])
        assertEquals(mapOf("video.app" to "allow"), store.saved)
        // A refused launch can still be retried from the early record; tapping again restores it as well
        audio.failing -= "video.app"
        assertTrue(muter.unmute("video.app", null))
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
    }

    @Test fun failedManualUnmuteRemainsCoveredByTaskEndRestore() {
        audio.modes["video.app"] = "allow"
        muter.opened("video.app", null)
        audio.failing += "video.app"
        assertFalse(muter.unmute("video.app", null))
        audio.failing -= "video.app"
        assertTrue(record.restoreAll())
        assertEquals("allow", audio.modes["video.app"])
        assertNull(store.saved)
    }

    @Test fun audioKeyRoundTrips() {
        assertEquals("a.app", AppAudioKey.of("a.app", null))
        assertEquals("a.app@999", AppAudioKey.of("a.app", 999))
        assertEquals("a.app" to null, AppAudioKey.parse("a.app"))
        assertEquals("a.app" to 999, AppAudioKey.parse("a.app@999"))
        assertNull(AppAudioKey.parse("a app; rm"))
    }
}
