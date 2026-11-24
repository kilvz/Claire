package com.wangzan101.mika.cap.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Fake clock: every "sleep" simply pushes the clock forward, so the wait loop runs synchronously and no coroutine test framework is needed. */
private class FakeClock(start: Long = 1_000L) {
    var now = start
        private set
    val slept = mutableListOf<Long>()
    val sleep: suspend (Long) -> Unit = { ms -> slept += ms; now += ms }
    fun advance(ms: Long) { now += ms }
}

class FrameFreshnessTest {
    private fun clock(frameAt: Long?) = FakeClock() to ({ frameAt } as () -> Long?)

    @Test
    fun awaitsNothingWhenTheFrameIsAlreadyNewer() {
        val (c, f) = clock(frameAt = 1_400L)
        runBlocking { assertTrue(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L)) }
        // Not a single sleep: a new frame already in hand is not waited for needlessly
        assertTrue(c.slept.isEmpty())
    }

    @Test
    fun timesOutWhenNoFrameEverComes() {
        val (c, f) = clock(frameAt = null)
        runBlocking { assertFalse(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L, timeoutMs = 1_500L)) }
        assertTrue(c.now >= 1_000L + 1_500L)
    }

    @Test
    fun timesOutWhenOnlyTheOldFrameExists() {
        val (c, f) = clock(frameAt = 900L)
        runBlocking { assertFalse(FrameFreshness.awaitNewFrame({ c.now }, c.sleep, f, since = 1_000L, timeoutMs = 1_500L)) }
    }

    @Test
    fun servingIsOpenWithoutALaunch() {
        val (c, f) = clock(frameAt = 900L)
        assertEquals(FrameFreshness.Serve.Fresh, FrameFreshness.serve(f, since = null))
    }

    @Test
    fun aNewerFrameServesFresh() {
        val (c, f) = clock(frameAt = 1_200L)
        assertEquals(FrameFreshness.Serve.Fresh, FrameFreshness.serve(f, since = 1_000L))
    }

    @Test
    fun aStaleFrameIsGuardedInsideTheWindow() {
        val (c, f) = clock(frameAt = 900L)
        // 1 second after the launch there is still no new frame: the old frame is not served
        c.advance(1_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f, since = 1_000L))
        // The same guard applies when there has never been a frame
        val (c2, f2) = clock(frameAt = null)
        c2.advance(1_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f2, since = 1_000L))
    }

    @Test
    fun anOldFrameNeverBecomesFreshMerelyBecauseTimePassed() {
        val (c, f) = clock(frameAt = 900L)
        c.advance(60_000L)
        assertEquals(FrameFreshness.Serve.Guarded, FrameFreshness.serve(f, since = 1_000L))
        // Only a new frame from after this launch releases the guard.
        val newer = FrameFreshness.serve({ 1_001L }, since = 1_000L)
        assertEquals(FrameFreshness.Serve.Fresh, newer)
    }
}
