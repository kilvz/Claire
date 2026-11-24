package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When the appearance changes (from the settings page or from a task) the UI updates in place, without recreating the interface. */
class FeatureFlagsThemeTest {
    @Test fun defaultsToSystemAndUnknownFallsBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
        prefs.edit().putString("theme_mode", "Sepia").commit()
        assertEquals(ThemeMode.System, FeatureFlags(prefs).themeMode())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val seen = mutableListOf<ThemeMode>()
        val job = launch { flags.themeModeChanges().take(3).toList(seen) }
        runCurrent()
        flags.setThemeMode(ThemeMode.Dark)
        runCurrent()
        SelfConfigImpl(flags).set("appearance", "light")
        job.join()
        assertEquals(listOf(ThemeMode.System, ThemeMode.Dark, ThemeMode.Light), seen)
    }

    @Test fun accentDefaultsToBlueAndUnknownFallsBack() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertEquals(ThemeAccent.Blue, FeatureFlags(prefs).themeAccent())
        prefs.edit().putString("theme_accent", "Neon").commit()
        assertEquals(ThemeAccent.Blue, FeatureFlags(prefs).themeAccent())
    }

    @Test fun accentChangesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val seen = mutableListOf<ThemeAccent>()
        val job = launch { flags.themeAccentChanges().take(3).toList(seen) }
        runCurrent()
        flags.setThemeAccent(ThemeAccent.Teal)
        runCurrent()
        SelfConfigImpl(flags).set("accent_color", "graphite")
        job.join()
        assertEquals(listOf(ThemeAccent.Blue, ThemeAccent.Teal, ThemeAccent.Graphite), seen)
    }

    @Test fun skinStartsEmptyWithReadableDefaults() {
        val skin = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).skin()
        assertEquals(SkinSpec(), skin)
        assertEquals(false, skin.active)
        assertNull(skin.accentHue)
    }

    @Test fun skinRoundTripsAndOutOfRangeValuesAreClamped() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        val spec = SkinSpec(version = 7L, scrim = 88, blur = SkinBlur.Strong, focus = -0.5f, accentFromImage = false, hue = 210f)
        flags.setSkin(spec)
        assertEquals(spec, flags.skin())
        // Hand-edited or out-of-range values persisted by older versions: the scrim never drops below its minimum, position is kept within -1..1
        prefs.edit().putInt("skin_scrim", 10).putFloat("skin_focus", 9f).putString("skin_blur", "Gauss").commit()
        val read = flags.skin()
        assertEquals(SkinSpec.MIN_SCRIM, read.scrim)
        assertEquals(1f, read.focus)
        assertEquals(SkinBlur.Light, read.blur)
        // Switching back to a solid colour also clears the colour sampled from the image
        flags.setSkin(read.copy(version = 0L, hue = null))
        assertNull(flags.skin().hue)
    }

    @Test fun imageAccentOnlyWhenThereIsAnImageAndItIsWanted() {
        assertEquals(30f, SkinSpec(version = 1L, hue = 30f).accentHue)
        assertNull(SkinSpec(version = 0L, hue = 30f).accentHue)
        assertNull(SkinSpec(version = 1L, hue = 30f, accentFromImage = false).accentHue)
        assertNull(SkinSpec(version = 1L, hue = null).accentHue)
    }

    @Test fun skinChangesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        val seen = mutableListOf<SkinSpec>()
        val job = launch { flags.skinChanges().take(3).toList(seen) }
        runCurrent()
        flags.setSkin(SkinSpec(version = 100L, hue = 40f))
        runCurrent()
        flags.setSkin(SkinSpec(version = 100L, hue = 40f, scrim = 90))
        job.join()
        assertEquals(listOf(SkinSpec(), SkinSpec(version = 100L, hue = 40f), SkinSpec(version = 100L, hue = 40f, scrim = 90)), seen)
    }
}
