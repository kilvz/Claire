package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Cumulative sandbox command time per task: defaults to 30 minutes, only the known tiers are accepted, anything else falls back to the default. */
class FeatureFlagsSandboxBudgetTest {
    @Test fun defaultsToThirtyMinutesAndKeepsOnlyKnownTiers() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertEquals(30, flags.sandboxBudgetMinutes())
        flags.setSandboxBudgetMinutes(120)
        assertEquals(120, flags.sandboxBudgetMinutes())
        flags.setSandboxBudgetMinutes(30)
        assertEquals(30, flags.sandboxBudgetMinutes())
        // Values outside the tiers (hand-edited, or a tier removed later) fall back to the default
        flags.setSandboxBudgetMinutes(45)
        assertEquals(30, flags.sandboxBudgetMinutes())
        prefs.edit().putInt("sandbox_budget_limit_minutes", 7).commit()
        assertEquals(30, flags.sandboxBudgetMinutes())
    }
}
