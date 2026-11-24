package com.wangzan101.mika.core.screen.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecognizerTest {
    private fun node(id: String, vararg anchors: String) = ScreenNode(id = id, app = "com.example.app", anchors = anchors.associateWith { 1 })

    @Test fun fullMatchRecognizesScreen() {
        val n = node("a", "关于本机", "设置", "更多")
        val r = ScreenRecognizer.identify(setOf("关于本机", "设置", "更多"), listOf(n))
        assertNotNull(r)
        assertEquals("a", r!!.id)
        assertEquals(setOf("关于本机", "设置", "更多"), r.matched)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test fun missingOneAnchorStillRecognizes() {
        // The same page in two structural variants, "banner missing": one skeleton anchor short is still recognized (coverage < 1.0 tolerated)
        val n = node("a", "关于本机", "设置", "更多")
        val r = ScreenRecognizer.identify(setOf("关于本机", "设置"), listOf(n))
        assertNotNull(r)
        assertEquals("a", r!!.id)
        assertEquals(setOf("关于本机", "设置"), r.matched)
        assertTrue(r.confidence >= 0.5)
    }

    @Test fun commonWordAcrossScreensDoesNotMergeThem() {
        // "OK" appears on both screens: seeing only this common word, rarity pushes confidence below the threshold, so no screen is recognized
        val a = node("a", "保存", "确定")
        val b = node("b", "取消", "确定")
        assertNull(ScreenRecognizer.identify(setOf("确定"), listOf(a, b)))
    }

    @Test fun rarityDisambiguatesWhenUniqueAnchorAlsoPresent() {
        // common word + "Save" that belongs only to A -> recognize A, not B
        val a = node("a", "保存", "确定")
        val b = node("b", "取消", "确定")
        val r = ScreenRecognizer.identify(setOf("确定", "保存"), listOf(a, b))
        assertNotNull(r)
        assertEquals("a", r!!.id)
    }

    @Test fun screenNotInGraphReturnsNull() {
        val n = node("a", "关于本机", "设置")
        assertNull(ScreenRecognizer.identify(setOf("某个全新界面的标题"), listOf(n)))
    }

    @Test fun onlyFuzzyAnchorsReturnsNull() {
        // both screens share "OK" and "Back"; seeing only these fuzzy words -> below the threshold -> unknown
        val a = node("a", "确定", "返回", "关于本机")
        val b = node("b", "确定", "返回", "设置")
        assertNull(ScreenRecognizer.identify(setOf("确定", "返回"), listOf(a, b)))
    }
}
