package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-app operating pace: the first read seeds the built-in careful apps as "human-like", after that only the stored list is consulted; clearing them all does not seed again. */
class FeatureFlagsAppPaceTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun seedsBuiltInCarefulAppsOnFirstRead() {
        val f = flags()
        val overrides = f.appPaceOverrides()
        // Every package name marked careful in the bundled app knowledge, seeded as "human-like"
        val careful = GuideCatalog.loadBundled().carefulApps()
        assertTrue(careful.isNotEmpty())
        assertEquals(careful.associateWith { OperatingPace.HUMAN }, overrides)
        // Already persisted: a second read of the same store does not seed again
        assertEquals(overrides, f.appPaceOverrides())
    }

    @Test fun keepsEditsAndEmptyMeansEmpty() {
        val f = flags()
        f.appPaceOverrides()
        f.setAppPaceOverrides(mapOf("com.xing" to OperatingPace.FAST))
        assertEquals(mapOf("com.xing" to OperatingPace.FAST), f.appPaceOverrides())
        // Clearing them all means explicitly no per-app distinction, and does not seed again
        f.setAppPaceOverrides(emptyMap())
        assertEquals(emptyMap<String, OperatingPace>(), f.appPaceOverrides())
    }

    @Test fun survivesOddPackageNamesAndUnknownPaces() {
        val f = flags()
        f.setAppPaceOverrides(mapOf("com.\"we\\ird\"" to OperatingPace.HUMAN))
        assertEquals(mapOf("com.\"we\\ird\"" to OperatingPace.HUMAN), f.appPaceOverrides())
        // Bad values written by hand: unrecognized pace names are skipped (forward compatibility, so renaming a pace in a newer version does not void the whole table stored by an older one)
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("app_pace_overrides", """{"com.a":"FAST","com.b":"quick"}""").commit()
        assertEquals(mapOf("com.a" to OperatingPace.FAST), FeatureFlags(prefs).appPaceOverrides())
    }

    @Test fun corruptStoredJsonFallsBackToSeeding() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit().putString("app_pace_overrides", "{oops").commit()
        val overrides = FeatureFlags(prefs).appPaceOverrides()
        assertEquals(GuideCatalog.loadBundled().carefulApps().associateWith { OperatingPace.HUMAN }, overrides)
    }
}
