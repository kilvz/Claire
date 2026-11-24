package com.wangzan101.mika.settings

import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.cap.phone.OperatingPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whitelist for settings_list / settings_set: only preferences that can be changed back easily; models, keys and any data deletion are never included. */
class SelfConfigImplTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun offersOnlyPreferencesAvailableToday() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }.toSet()
        assertEquals(setOf("operating_pace", "mute_apps", "appearance", "accent_color", "capsule", "keep_screen_on"), keys)
    }

    @Test fun modelsKeysAndAnythingThatDeletesDataAreNotOffered() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }
        assertTrue(keys.none { k -> listOf("model", "key", "delete", "reset", "clear").any { it in k } })
        // The container's networking and proxy would let data leave the phone, so the user has to decide in Settings
        assertTrue("container_network" !in keys)
    }

    @Test fun changingAPreferenceTakesEffect() {
        val f = flags()
        val s = SelfConfigImpl(f)
        assertNull(s.set("operating_pace", "fast"))
        assertEquals(OperatingPace.FAST, f.operatingPace())
        assertNull(s.set("mute_apps", "off"))
        assertEquals(false, f.muteTarget())
        assertNull(s.set("appearance", "dark"))
        assertEquals(ThemeMode.Dark, f.themeMode())
        assertNull(s.set("accent_color", "Purple"))
        assertEquals(ThemeAccent.Purple, f.themeAccent())
        assertNull(s.set("capsule", "off"))
        assertEquals(CapsuleMode.Off, f.capsuleMode())
        assertNull(s.set("keep_screen_on", "off"))
        assertEquals(false, f.keepScreenOn())
    }

    @Test fun badValuesAndUnknownKeysAreReported() {
        val s = SelfConfigImpl(flags())
        assertNotNull(s.set("operating_pace", "turbo"))
        assertNotNull(s.set("appearance", "sepia"))
        assertNotNull(s.set("accent_color", "green"))
        assertNotNull(s.set("mute_apps", "maybe"))
        assertEquals("there is no such setting", s.set("api_key", "sk-123"))
        assertEquals("there is no such setting", s.set("container_network", "on"))
    }
}
