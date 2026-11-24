package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeWaitTest {
    private val before = IntArray(288) { 200 }
    private val after = IntArray(288) { 20 }
    /** An intermediate state where the frame differs every 50 ms (still drawing, still playing). */
    private fun moving(t: Long) = IntArray(288) { ((t / 50 * 37 + it * 11) % 256).toInt() }

    /** Fake clock: sleep advances time; frameAt/lastEventAt give the frame and the time of the most recent event for a given time. */
    private class Fake(
        val frameAt: (Long) -> IntArray?,
        val lastEventAt: (Long) -> Long = { -10_000 },
        val treeChangedAt: Long = Long.MAX_VALUE,
    ) {
        var now = 0L
        suspend fun run(before: IntArray?, fallback: (suspend () -> Boolean)? = null) = ChangeWait.awaitStableChange(
            now = { now },
            sleep = { now += it },
            frame = { frameAt(now) },
            quietForMs = { now - lastEventAt(now) },
            treeChanged = { now >= treeChangedAt },
            beforeFrame = before,
            fallback = fallback,
        )
    }

    @Test fun slowPageChangeIsReportedAfterFramesSettle() = runTest {
        // The page only starts drawing after 1.2 s and finishes at 1.6 s; events stay quiet the whole time and the view tree does not change (custom-drawn UI)
        val f = Fake(frameAt = { t -> when { t < 1_200 -> before; t < 1_600 -> moving(t); else -> after } })
        assertTrue(f.run(before))
        assertTrue("waited for the frame to settle, at ${f.now}", f.now >= 1_600)
        assertTrue(f.now <= ChangeWait.CAP_MS)
    }

    @Test fun fastPathReturnsAsSoonAsChangeIsStable() = runTest {
        val f = Fake(frameAt = { t -> if (t < 100) before else after })
        assertTrue(f.run(before))
        assertTrue("returned early, at ${f.now}", f.now <= 2 * ChangeWait.POLL_MS)
    }

    @Test fun normalPageChangeReturnsSoonAfterEventsQuiet() = runTest {
        // Ordinary page flip: events last until 100 ms; both the view tree and the frame change
        val f = Fake(frameAt = { t -> if (t < 100) before else after }, lastEventAt = { t -> minOf(t, 100) }, treeChangedAt = 50)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now in 100 + ChangeWait.QUIET_MS..100 + ChangeWait.QUIET_MS + ChangeWait.POLL_MS)
    }

    @Test fun neverStillPageWithTreeChangeReturnsFast() = runTest {
        // Video or live stream: the frame keeps moving, but events have gone quiet and the view tree changed, so there is no need to wait for the frame to stop
        val f = Fake(frameAt = ::moving, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now <= ChangeWait.POLL_MS)
    }

    @Test fun nothingChangesReturnsFalseAtCap() = runTest {
        val f = Fake(frameAt = { before })
        assertFalse(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
        assertTrue(f.now < ChangeWait.CAP_MS + ChangeWait.POLL_MS)
    }

    @Test fun waitsForEventQuietBeforeJudging() = runTest {
        // The view tree changed right from the start, but events last until 1 s
        val f = Fake(frameAt = { after }, lastEventAt = { t -> minOf(t, 1_000) }, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("judged at ${f.now}", f.now >= 1_000 + ChangeWait.QUIET_MS)
    }

    @Test fun treeChangeWithoutFrameCounts() = runTest {
        // No frame (preview frame unavailable): rely on the view tree only
        val f = Fake(frameAt = { null }, treeChangedAt = 900)
        assertTrue(f.run(null))
        assertTrue(f.now in 900..ChangeWait.CAP_MS)
    }

    @Test fun stillAnimatingAtCapButChangedCountsAsChanged() = runTest {
        val f = Fake(frameAt = ::moving)
        assertTrue(f.run(before))
        assertTrue(f.now >= ChangeWait.CAP_MS)
    }

    @Test fun eventsNeverQuietDecidesAtCapWithCurrentState() = runTest {
        // Events never stop (for example a progress bar): at the cap, decide from the state at that moment
        val changed = Fake(frameAt = { after }, lastEventAt = { it }, treeChangedAt = 0)
        assertTrue(changed.run(before))
        assertTrue(changed.now >= ChangeWait.CAP_MS)
        assertFalse(Fake(frameAt = { before }, lastEventAt = { it }).run(before))
    }

    @Test fun fallbackRunsAtProbeWhenPrimaryHasNoEffect() = runTest {
        var fallbackAt = -1L
        val f = Fake(
            frameAt = { t -> if (fallbackAt in 0..t) after else before },
            lastEventAt = { t -> if (fallbackAt in 0..t) fallbackAt else -10_000 },
        )
        val changed = f.run(before) {
            fallbackAt = f.now
            true
        }
        assertTrue(changed)
        assertTrue("fallback triggered at $fallbackAt", fallbackAt == ChangeWait.PROBE_MS)
        assertTrue("settled at ${f.now}", f.now in (ChangeWait.PROBE_MS + ChangeWait.QUIET_MS)..(ChangeWait.PROBE_MS + ChangeWait.QUIET_MS + ChangeWait.POLL_MS))
    }

    @Test fun fallbackSkippedWhenPrimaryAlreadyAnimatingOrEmittingEvents() = runTest {
        var calls = 0
        // At 550 ms the frame is already moving, so the fallback click must not be triggered by mistake
        val animating = Fake(frameAt = { t -> when { t < 300 -> before; t < 900 -> moving(t); else -> after } })
        assertTrue(animating.run(before) { calls++; true })
        assertTrue(calls == 0)

        // At 550 ms accessibility events have not gone quiet yet, so the supplementary click must not be fired early
        val noisy = Fake(
            frameAt = { t -> if (t < 800) before else after },
            lastEventAt = { t -> minOf(t, 700) },
            treeChangedAt = 800,
        )
        assertTrue(noisy.run(before) { calls++; true })
        assertTrue(calls == 0)
    }

    @Test fun fallbackExtendsDeadlineAndSkippedFallbackKeepsOriginalCap() = runTest {
        // After the fallback channel fires at 550 ms, the page only changes at 2_700 ms (beyond the original 2_500 ms cap), and the new window can still wait for it
        var fallbackAt = -1L
        val extended = Fake(frameAt = { t -> if (t < 2_700) before else after })
        assertTrue(extended.run(before) { fallbackAt = extended.now; true })
        assertTrue(fallbackAt == ChangeWait.PROBE_MS)
        assertTrue("settled at ${extended.now}", extended.now in 2_700..(ChangeWait.PROBE_MS + ChangeWait.CAP_MS))

        // When the fallback channel returns false (no usable fallback target), it ends at the original 2_500 ms cap instead of wasting double the time
        val noTarget = Fake(frameAt = { before })
        assertFalse(noTarget.run(before) { false })
        assertTrue("ended at ${noTarget.now}", noTarget.now in ChangeWait.CAP_MS..(ChangeWait.CAP_MS + ChangeWait.POLL_MS))
    }
}
