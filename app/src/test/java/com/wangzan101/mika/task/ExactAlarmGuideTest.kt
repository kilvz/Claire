package com.wangzan101.mika.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmGuideTest {
    // Without the exact alarm permission, prompt only once; with the permission, do not disturb
    @Test fun promptsOnceOnlyWhenPermissionMissing() {
        assertTrue(ExactAlarmGuide.shouldPrompt(granted = false, prompted = false))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = false, prompted = true))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = true, prompted = false))
    }
}
