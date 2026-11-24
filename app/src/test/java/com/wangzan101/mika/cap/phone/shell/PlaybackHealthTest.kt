package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHealthTest {
    @Test
    fun headStallNeedsFrozenHeadAndLiveAudio() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        // No playback-head observation and no data: not dead
        assertFalse(d.headStalled(10_000))

        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(0, 1_000)
        // Data still flowing, head frozen for 500ms: below the threshold
        assertFalse(d.headStalled(1_500))
        // Head frozen for a full 1s: dead
        assertTrue(d.headStalled(2_100))

        // Head moving again: alive
        d.recordPlaybackHead(240, 2_200)
        assertFalse(d.headStalled(2_600))
    }

    @Test
    fun headStallCoversSilentDeathWithUnplayedAudio() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000) // 240 frames went into the track
        d.recordPlaybackHead(240, 1_100) // everything played out
        d.recordWrite(960, 1_200) // another 480 frames went in
        // Data stopped long ago, but 480 frames are still queued unplayed in the track and the head is not moving: silent death
        assertTrue(d.headStalled(9_000))
    }

    @Test
    fun headStallClearsWhenTrackDrainsEverything() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(240, 1_100)
        // Fully played, no new data: normal silence, not death
        assertFalse(d.headStalled(9_000))
    }

    @Test
    fun headStallResetsAcrossFlushEpochs() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(480, 1_000)
        d.recordPlaybackHead(0, 1_000)
        assertTrue(d.headStalled(2_100))
        // Interrupting flush: a new epoch, old observations no longer count
        d.recordTrackFlush()
        assertFalse(d.headStalled(3_000))
    }

    @Test
    fun trackQueueAccountsWrittenPrimeAndHead() {
        val d = ShellCallAudio.PlaybackDiagnostics()
        d.recordWrite(960, 100) // 480 frames of data
        d.recordPrime(480) // 240 frames of leading silence
        assertEquals(-1L, d.trackQueuedFrames()) // head unknown
        d.recordPlaybackHead(240, 200)
        assertEquals(480 + 240 - 240L, d.trackQueuedFrames())
    }

    @Test
    fun rebuildPolicyThrottlesAndEventuallyGivesUp() {
        val p = ShellCallAudio.PlaybackRebuildPolicy(maxInWindow = 3, windowMs = 15_000, minIntervalMs = 1_000)
        assertFalse(p.throttled(0))
        assertTrue(p.allow(0))
        assertTrue(p.throttled(500))
        assertFalse(p.throttled(1_000))
        assertTrue(p.allow(1_000))
        assertTrue(p.allow(2_000))
        // 4th time within the window: give up, hand back to the system
        assertFalse(p.allow(3_000))
        // allowed again once the window has slid past
        assertTrue(p.allow(16_500))
    }
}
