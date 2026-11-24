package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Maximum scheduled-task run time: unlimited by default, only the known tiers are accepted, anything else counts as unlimited. */
class FeatureFlagsTriggerRunTest {
    @Test fun defaultsToNoLimitAndKeepsOnlyKnownChoices() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertNull(flags.triggerRunMinutes())
        flags.setTriggerRunMinutes(30)
        assertEquals(30, flags.triggerRunMinutes())
        flags.setTriggerRunMinutes(null)
        assertNull(flags.triggerRunMinutes())
        // Values outside the tiers (hand-edited, or a tier removed later) count as unlimited
        flags.setTriggerRunMinutes(45)
        assertNull(flags.triggerRunMinutes())
        prefs.edit().putInt("trigger_run_limit_minutes", 7).commit()
        assertNull(flags.triggerRunMinutes())
    }
}
