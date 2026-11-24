package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.settings.ui.DeveloperSwitches

class DeveloperSwitchesTest {
    @get:Rule val texts = ChineseTexts()

    private val speed by lazy { DeveloperSwitches.speed(SpeedFlags.OFF) }
    private val experimental by lazy { DeveloperSwitches.experimental(SpeedFlags.OFF) }

    /** The switches from the original "Speed" section: each appears in only one group, and ids are unique. */
    @Test
    fun switchesInTwoGroups() {
        assertEquals(
            listOf("adaptive-vision", "inline-screen", "batch-steps", "deep-links", "light-steps"),
            speed.map { it.id },
        )
        assertEquals(listOf("replay", "parallel-tasks", "structured-compaction", "tables-at-birth"), experimental.map { it.id })
        assertEquals(9, (speed + experimental).map { it.id }.toSet().size)
    }

    /** Toggling one switch changes only its own entry; everything else keeps its original value. */
    @Test
    fun toggleChangesOnlyItsOwnFlag() {
        val base = SpeedFlags(replay = true, deepLinks = true)
        (DeveloperSwitches.speed(base) + DeveloperSwitches.experimental(base)).forEach { sw ->
            val after = sw.change(!sw.checked)
            val changed = listOf(
                base.adaptiveVision != after.adaptiveVision, base.inlineScreen != after.inlineScreen,
                base.batchSteps != after.batchSteps, base.deepLinks != after.deepLinks, base.lightSteps != after.lightSteps,
                base.replay != after.replay, base.parallelTasks != after.parallelTasks,
                base.structuredCompaction != after.structuredCompaction, base.tablesAtBirth != after.tablesAtBirth,
            )
            assertEquals("${sw.id} should change exactly one flag", 1, changed.count { it })
        }
    }

    @Test
    fun checkedFollowsFlags() {
        assertTrue(DeveloperSwitches.experimental(SpeedFlags(replay = true)).first { it.id == "replay" }.checked)
        assertFalse(DeveloperSwitches.experimental(SpeedFlags.OFF).any { it.checked })
        assertFalse(DeveloperSwitches.speed(SpeedFlags.OFF).any { it.checked })
    }
}
