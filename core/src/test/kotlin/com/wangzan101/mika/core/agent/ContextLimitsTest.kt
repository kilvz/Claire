package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smallest window whose trigger point is not below [trigger] (the trigger point is monotonic non-decreasing in the window). */
internal fun windowFor(trigger: Int): Int {
    var lo = 1
    var hi = 100_000_000
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (ContextLimits.of(mid).trigger >= trigger) hi = mid else lo = mid + 1
    }
    return lo
}

/** The estimate for the whole request before calibration (messages + fixed overhead, multiplied by [ContextCompressor.UNCALIBRATED_SCALE]). */
internal fun uncalibrated(ms: List<Message>, overhead: Int = 0) = UsageMeter.of(ms, overhead, ContextCompressor.UNCALIBRATED_SCALE)

class ContextLimitsTest {
    @Test fun smallLocalWindow32k() {
        val l = ContextLimits.of(32_000)
        // Output reservation 8K (a quarter of the window), margin 6K (a quarter of the usable part)
        assertEquals(24_000, l.usable)
        assertEquals(6_000, l.margin)
        assertEquals(18_000, l.trigger)
        assertEquals(12_000, l.target)
        // The 11K fixed overhead is counted within the whole request and not deducted from the trigger point a second time: 7K remains for messages, so no compaction happens at the start
        assertTrue(l.trigger - 11_000 >= 7_000)
    }

    @Test fun commonWindow128k() {
        val l = ContextLimits.of(128_000)
        assertEquals(108_000, l.usable)
        assertEquals(95_000, l.trigger)
        assertEquals(54_000, l.target)
    }

    @Test fun hugeWindow1m() {
        val l = ContextLimits.of(1_000_000)
        assertEquals(980_000, l.usable)
        assertEquals(967_000, l.trigger)
        assertEquals(490_000, l.target)
    }

    @Test fun triggerNeverBelowFloorNorAboveUsable() {
        for (w in listOf(100, 1_000, 4_096, 8_192, 32_000, 128_000, 1_000_000)) {
            val l = ContextLimits.of(w)
            assertTrue("$w", l.trigger >= w * ContextLimits.MIN_TRIGGER_SHARE && l.trigger <= l.usable)
            assertTrue("$w", l.target < l.trigger)
        }
        // Using the trigger point given by the model: clamped between the floor and the usable window; the target drops accordingly
        assertEquals(16_000, ContextLimits.of(32_000, triggerOverride = 1_000).trigger)
        assertEquals(24_000, ContextLimits.of(32_000, triggerOverride = 99_999).trigger)
        val low = ContextLimits.of(1_000_000, triggerOverride = 600_000)
        assertEquals(600_000, low.trigger)
        assertEquals(490_000, low.target)
        assertEquals(0, ContextLimits.of(0).trigger)
    }
}
