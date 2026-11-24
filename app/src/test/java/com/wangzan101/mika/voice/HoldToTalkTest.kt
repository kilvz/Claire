package com.wangzan101.mika.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldToTalkTest {
    /** Fake session: only counts how often release and cancel were called; events are fed to [HoldToTalk.onEvent] directly by the test. */
    private class FakeSession(override val live: Boolean = true, override val unlimited: Boolean = false) : SpeechSession {
        override val events: Flow<SpeechEvent> = emptyFlow()
        var released = 0
        var cancelled = 0
        override fun release() { released++ }
        override fun cancel() { cancelled++ }
    }

    private var now = 0L
    private val hold = HoldToTalk(clock = { now })

    private fun pressed(live: Boolean = true): FakeSession = FakeSession(live).also { hold.press(it) }

    @Test
    fun partialShowsLive() {
        pressed()
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertEquals("帮我", s.stable)
        assertEquals("订票", s.tail)
        assertTrue(s.live)
    }

    @Test
    fun releaseGivesDone() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun cancelArmedDropsText() {
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Partial("帮我", "订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(1, session.cancelled)
        assertEquals(0, session.released)
        // Results arriving late after a cancel no longer surface
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun dragBackDisarms() {
        pressed()
        hold.drag(100f, 80f)
        hold.drag(10f, 80f)
        assertFalse((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
    }

    @Test
    fun tooShortIsNoMatch() {
        val session = pressed()
        now = 300
        hold.release()
        // Too short, so it is not handed to the session for transcription (the provider's engine costs quota too): drop it and treat it as not hearing clearly
        assertEquals(0, session.released)
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
        // A late result from the session does not overwrite the "no match" problem
        hold.onEvent(SpeechEvent.Final("帮我"))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun blankFinalIsNoMatch() {
        val session = pressed()
        now = 2_000
        hold.release()
        assertEquals(1, session.released)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.onEvent(SpeechEvent.Final("  "))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun minimumIsConfigurable() {
        // When the input bar starts listening the finger has already been down for 300ms, so only 200ms more is counted: only under half a second in total is too short
        val short = HoldToTalk(clock = { now }, minMs = 200)
        val session = FakeSession().also { short.press(it) }
        now = 250
        short.release()
        assertEquals(1, session.released)
        assertEquals(0, session.cancelled)
    }

    @Test
    fun maxDurationReleases() {
        val session = pressed()
        now = 30_000
        hold.tick()
        assertEquals(0, session.released)
        assertEquals(30_000L, (hold.state.value as HoldToTalk.State.Listening).elapsedMs)
        now = 60_000
        hold.tick()
        assertEquals(1, session.released)
        // Ticking after the cap is reached does not wind down again
        now = 61_000
        hold.tick()
        assertEquals(1, session.released)
    }

    // Streaming recognition has no cap: it keeps listening past 60 seconds without winding down, and only the release winds it down
    @Test
    fun unlimitedSessionKeepsListeningPastTheCap() {
        val session = FakeSession(unlimited = true).also { hold.press(it) }
        assertTrue((hold.state.value as HoldToTalk.State.Listening).unlimited)
        now = 180_000
        hold.tick()
        assertEquals(0, session.released)
        assertEquals(180_000L, (hold.state.value as HoldToTalk.State.Listening).elapsedMs)
        hold.release()
        assertEquals(1, session.released)
    }

    @Test
    fun providerShowsConverting() {
        pressed(live = false)
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Converting)
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        // The provider's engine may deliver a level event late: while converting, do not go back to listening
        hold.onEvent(SpeechEvent.Level(0.5f))
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final("你好"))
        assertEquals(HoldToTalk.State.Done("你好"), hold.state.value)
    }

    @Test
    fun providerConvertsRightOnRelease() {
        val session = pressed(live = false)
        now = 2_000
        hold.release()
        // The recorder takes a moment to stop, so the Converting event arrives late; the instant of release must already show converting, without flashing back to the placeholder
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        assertEquals(1, session.released)
        hold.tick()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        hold.onEvent(SpeechEvent.Final(""))
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
    }

    @Test
    fun failureSurfaces() {
        pressed()
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertEquals(HoldToTalk.State.Problem(SpeechFailure.Busy), hold.state.value)
    }

    @Test
    fun disposeCancels() {
        val session = pressed()
        hold.dispose()
        hold.dispose()
        assertEquals(1, session.cancelled)
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun levelMovesWhileListening() {
        pressed()
        hold.onEvent(SpeechEvent.Level(0.7f))
        assertEquals(0.7f, (hold.state.value as HoldToTalk.State.Listening).level, 0.0001f)
    }

    @Test
    fun resetAfterDoneGoesIdle() {
        pressed()
        now = 2_000
        hold.release()
        hold.onEvent(SpeechEvent.Final("好"))
        hold.reset()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun pressAgainDropsTheOldSession() {
        val first = pressed()
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
    }

    @Test
    fun earlyFinalWaitsForReleaseThenAppliesOnce() {
        // The phone's built-in recognition finished on its own while the finger is still down: do not hand it off yet; it counts only once the finger lifts
        val session = pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        val s = hold.state.value as HoldToTalk.State.Listening
        assertTrue(s.ended)
        assertEquals("帮我订票", s.stable)
        now = 3_000
        hold.tick()
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
        // The session has already ended, so it is not wound down again; releasing once more does not hand it off again
        assertEquals(0, session.released)
        hold.reset()
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalWithCancelArmedIsDropped() {
        val session = pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(0, session.released)
    }

    @Test
    fun earlyFinalThenDragToCancelIsDropped() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(100f, 80f)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).cancelArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalThenDragBackOffCancelIsApplied() {
        pressed()
        now = 2_000
        hold.drag(100f, 80f)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.drag(10f, 80f)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票"), hold.state.value)
    }

    @Test
    fun earlyFailureWithCancelArmedIsDropped() {
        pressed()
        now = 2_000
        hold.armCancel(true)
        hold.onEvent(SpeechEvent.Failed(SpeechFailure.Busy))
        assertTrue((hold.state.value as HoldToTalk.State.Listening).ended)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
    }

    @Test
    fun earlyFinalAppliedWhenTimeRunsOut() {
        pressed()
        now = 2_000
        hold.onEvent(SpeechEvent.Final("好"))
        now = 60_000
        hold.tick()
        assertEquals(HoldToTalk.State.Done("好"), hold.state.value)
    }

    @Test
    fun pressWhileConvertingDropsTheOldSession() {
        // The user presses again while the provider's transcription is stuck: the old segment is cancelled, and the new one listens and produces a result as usual
        val first = pressed(live = false)
        now = 2_000
        hold.release()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        val second = pressed()
        assertEquals(1, first.cancelled)
        assertEquals(0, second.cancelled)
        assertTrue(hold.state.value is HoldToTalk.State.Listening)
        now = 4_000
        hold.release()
        assertEquals(1, second.released)
        hold.onEvent(SpeechEvent.Final("新的"))
        assertEquals(HoldToTalk.State.Done("新的"), hold.state.value)
    }

    @Test
    fun joinRules() {
        assertEquals("你好", HoldToTalk.join("", "你好"))
        assertEquals("帮我订票", HoldToTalk.join("帮我", "订票"))
        assertEquals("book a train", HoldToTalk.join("book", "a train"))
        assertEquals("帮我 book", HoldToTalk.join("帮我", "book"))
        assertEquals("好的，订票", HoldToTalk.join("好的，", "订票"))
        assertEquals("book ", HoldToTalk.join("book ", ""))
        assertEquals("book a", HoldToTalk.join("book ", "a"))
    }

    @Test
    fun sendArmedGivesDoneToSend() {
        val session = pressed()
        now = 2_000
        hold.aim(cancel = false, send = true)
        assertTrue((hold.state.value as HoldToTalk.State.Listening).sendArmed)
        hold.release()
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票", send = true), hold.state.value)
    }

    @Test
    fun sendDisarmedFillsAsBefore() {
        pressed()
        now = 2_000
        hold.aim(cancel = false, send = true)
        hold.aim(cancel = false, send = false)
        hold.release()
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票", send = false), hold.state.value)
    }

    @Test
    fun sendArmedKeepsPendingResultAndSendsOnRelease() {
        pressed()
        now = 2_000
        // The session ended first while the finger is still down: keep the result; it is sent directly only if released over the release-and-send target
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.aim(cancel = false, send = true)
        hold.release()
        assertEquals(HoldToTalk.State.Done("帮我订票", send = true), hold.state.value)
    }

    @Test
    fun sendArmedConvertsThenSends() {
        val session = pressed(live = false)
        now = 2_000
        hold.aim(cancel = false, send = true)
        hold.release()
        assertEquals(HoldToTalk.State.Converting, hold.state.value)
        assertEquals(1, session.released)
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        assertEquals(HoldToTalk.State.Done("帮我订票", send = true), hold.state.value)
    }

    @Test
    fun sendArmedButTooShortIsNoMatch() {
        val session = pressed()
        now = 300
        hold.aim(cancel = false, send = true)
        hold.release()
        assertEquals(HoldToTalk.State.Problem(null), hold.state.value)
        assertEquals(0, session.released)
    }

    @Test
    fun cancelWinsOverSend() {
        val session = pressed()
        now = 2_000
        hold.aim(cancel = true, send = true)
        val s = hold.state.value as HoldToTalk.State.Listening
        assertTrue(s.cancelArmed)
        assertFalse(s.sendArmed)
        hold.release()
        assertEquals(HoldToTalk.State.Idle, hold.state.value)
        assertEquals(1, session.cancelled)
    }

    @Test
    fun nextPressStartsWithoutSend() {
        pressed()
        now = 2_000
        hold.aim(cancel = false, send = true)
        hold.release()
        hold.onEvent(SpeechEvent.Final("帮我订票"))
        hold.reset()
        // The previous segment was released over the release-and-send target, which does not affect the next segment
        pressed()
        now = 4_000
        hold.release()
        hold.onEvent(SpeechEvent.Final("再订一张"))
        assertEquals(HoldToTalk.State.Done("再订一张", send = false), hold.state.value)
    }
}
