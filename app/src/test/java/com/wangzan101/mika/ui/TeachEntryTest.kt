package com.wangzan101.mika.ui

import com.wangzan101.mika.core.learn.teach.TeachPhase
import com.wangzan101.mika.core.learn.teach.TeachSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.teach.ui.showDone

class TeachEntryTest {
    private fun saved(goal: String) = TeachSession(id = "s1", goal = goal, phase = TeachPhase.Saved)

    /** After saving a goal, coming in with another goal asks about the new one first instead of stopping on the previous "learned" page. */
    @Test fun aNewGoalBringsBackTheIntroInsteadOfTheOldDonePage() {
        assertFalse(showDone(saved("在小红书搜装修"), "在地图里找充电桩"))
    }

    @Test fun enteringWithoutANewGoalStaysOnTheDonePage() {
        assertTrue(showDone(saved("在小红书搜装修"), null))
        assertTrue(showDone(saved("在小红书搜装修"), ""))
        assertTrue(showDone(saved("在小红书搜装修"), "在小红书搜装修"))
    }

    @Test fun onlyTheSavedPageIsDone() {
        assertFalse(showDone(saved("目标").copy(phase = TeachPhase.Recording), "别的目标"))
        assertFalse(showDone(null, "别的目标"))
    }
}
