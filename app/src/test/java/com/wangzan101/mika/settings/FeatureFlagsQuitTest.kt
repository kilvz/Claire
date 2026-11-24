package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "full quit" flag: absent by default, persists across processes once set, and is emitted once when cleared. */
class FeatureFlagsQuitTest {
    @Test fun quitDefaultsOffAndIsKept() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        assertEquals(false, FeatureFlags(prefs).quitRequested())
        FeatureFlags(prefs).setQuitRequested(true)
        assertEquals(true, FeatureFlags(prefs).quitRequested())
        FeatureFlags(prefs).setQuitRequested(false)
        assertEquals(false, FeatureFlags(prefs).quitRequested())
    }

    @Test fun changesEmitCurrentThenEachChange() = runTest {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertEquals(false, flags.quitChanges().first())
        val seen = mutableListOf<Boolean>()
        val job = launch { flags.quitChanges().take(2).toList(seen) }
        runCurrent()
        flags.setQuitRequested(true)
        job.join()
        assertEquals(listOf(false, true), seen)
    }
}
