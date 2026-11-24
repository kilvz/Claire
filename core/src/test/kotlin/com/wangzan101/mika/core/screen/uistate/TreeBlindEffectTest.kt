package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the tree cannot see the tapped spot, say honestly that no change is visible instead of "it probably did not land"; the kind is still no_effect. */
class TreeBlindEffectTest {
    private val button = UiElement("@e1", "button", "设置", 500, 100, listOf(400, 50, 600, 150), null, true, false, false)
    private val before = ScreenSnapshot("s1", "com.example.chat", listOf(button), 1080, 2400)
    private val onButton = listOf(480, 80, 520, 120)
    private val offTree = listOf(480, 880, 520, 920)

    @Test fun unusableTreeIsBlindForTapsAndGestures() {
        assertTrue(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Unusable, before, null, offTree))
        assertTrue(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Unusable, before, button, null))
        assertTrue(ActionEffect.treeBlind(Verb.PhoneGesture, TreeQuality.Unusable, before, null, null))
        // Back, swipe and key presses have their own wording when no change is visible
        assertFalse(ActionEffect.treeBlind(Verb.Back, TreeQuality.Unusable, before, null, null))
        assertFalse(ActionEffect.treeBlind(Verb.Swipe, TreeQuality.Unusable, before, null, offTree))
    }

    @Test fun partialTreeIsBlindOnlyOffItsElements() {
        assertTrue(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Partial, before, null, offTree))
        assertFalse(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Partial, before, null, onButton))
        assertFalse(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Partial, before, button, onButton))
    }

    @Test fun adequateOrUnknownTreeKeepsTheUsualVerdict() {
        assertFalse(ActionEffect.treeBlind(Verb.Tap, TreeQuality.Adequate, before, null, offTree))
        assertFalse(ActionEffect.treeBlind(Verb.Tap, null, before, null, offTree))
    }

    @Test fun hintFollowsTheFlagAndKindStaysNoEffect() {
        val plain = ActionEffect(EffectKind.NoEffect)
        assertEquals(Feedback["effect.no_effect"], EffectFeedback.hint(plain, Verb.Tap, portSwitched = false))
        val blind = plain.copy(treeBlind = true)
        assertEquals(Feedback["effect.no_visible_change"], EffectFeedback.hint(blind, Verb.Tap, portSwitched = false))
        assertEquals("no_effect", blind.toJson()["kind"].toString().trim('"'))
        assertEquals("true", blind.toJson()["treeBlind"].toString())
        assertFalse("treeBlind" in plain.toJson())
    }
}
