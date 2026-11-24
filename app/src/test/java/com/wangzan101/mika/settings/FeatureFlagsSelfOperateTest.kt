package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Allow Claire to operate its own interface" is off by default and only the user can change it; it is not in the whitelist of settings the model may modify. */
class FeatureFlagsSelfOperateTest {
    @Test fun defaultsOffAndToggles() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertFalse(flags.selfOperate())
        flags.setSelfOperate(true)
        assertTrue(flags.selfOperate())
    }

    @Test fun theModelCannotTurnItOnThroughSettings() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val keys = SelfConfigImpl(flags).options().map { it.key }
        assertFalse("self_operate" in keys || "self_config" in keys)
    }
}
