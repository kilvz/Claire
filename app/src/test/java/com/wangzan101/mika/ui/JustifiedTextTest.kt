package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Chinese justification only spreads spacing between adjacent characters; [cjkGapRanges] decides where spacing may go. */
class JustifiedTextTest {
    private fun ranges(text: String) = cjkGapRanges(text, 0, text.length)

    @Test
    fun gapsBetweenHanCharacters() {
        // Four characters give 3 adjacent character pairs
        assertEquals(listOf(0..0, 1..1, 2..2), ranges("中文字数"))
    }

    @Test
    fun noGapsAroundPunctuation() {
        // No spacing on either side of the full-width comma: Chinese punctuation already carries a full-width gap, and stretching it would break it apart
        assertEquals(listOf(0..0), ranges("中文，字"))
    }

    @Test
    fun noGapsAroundLatinAndDigits() {
        assertEquals(emptyList<IntRange>(), ranges("App123"))
        // No spacing where Han characters sit next to Latin text either, and an English word must never be stretched from the inside
        assertEquals(emptyList<IntRange>(), ranges("读PDF"))
        assertEquals(listOf(1..1), ranges("a中文b"))
    }

    @Test
    fun kanaCounted() {
        assertEquals(listOf(0..0, 1..1, 2..2), ranges("かなカナ"))
    }

    @Test
    fun surrogatePairsStayWhole() {
        // Characters in Extension B are surrogate pairs, so a range must not split them
        val text = "𠀀𠀁" // 𠀀𠀁
        assertEquals(listOf(0..1), ranges(text))
    }

    @Test
    fun clampedToLineWindow() {
        val text = "一二三四五"
        assertEquals(listOf(1..1, 2..2), cjkGapRanges(text, 1, 4))
    }
}
