package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSpeedTest {
    @Test fun freshInstallTurnsOnDefaultsIncludingReplay() {
        val f = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).speedFlags()
        assertTrue(f.inlineScreen && f.batchSteps && f.adaptiveVision && f.replay && f.lightSteps && f.tablesAtBirth)
    }

    @Test fun earlierUpgradeOnlyAddsReplayOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        // Upgraded in the previous release, and the user turned continuous steps back off: this pass only adds replay, large/small model split and screen table compaction, nothing else
        prefs.edit().putString("speed_flags", "inlineScreen,adaptiveVision").putBoolean("speed_defaults_applied_v2", true).commit()
        val flags = FeatureFlags(prefs)
        assertEquals(SpeedFlags(inlineScreen = true, adaptiveVision = true, replay = true, lightSteps = true, tablesAtBirth = true), flags.speedFlags())
        // If the user manually turns replay off after the upgrade, it stays off
        flags.setSpeed(flags.speedFlags().copy(replay = false))
        assertFalse(FeatureFlags(prefs).speedFlags().replay)
    }
}
