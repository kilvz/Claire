package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsPersonaTest {
    /** On by default in release builds just like debug builds; once turned off, the choice persists. */
    @Test fun defaultsOnAndPersistsChanges() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertTrue(flags.persona())
        flags.setPersona(false)
        assertFalse(FeatureFlags(prefs).persona())
        flags.setPersona(true)
        assertTrue(FeatureFlags(prefs).persona())
    }
}
