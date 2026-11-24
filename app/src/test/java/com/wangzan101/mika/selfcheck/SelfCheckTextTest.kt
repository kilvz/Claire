package com.wangzan101.mika.selfcheck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-check recognizing page text: it still matches when the case and whitespace reported by the screen reader differ from what is written on the page, and it does not mistake other text for it. */
class SelfCheckTextTest {
    @Test
    fun buttonTextReadBackInCapitalsStillMatches() {
        assertTrue(SelfCheckText.same("TAP ME", "Tap me"))
        assertTrue(SelfCheckText.same("tap me", "Tap me"))
    }

    @Test
    fun extraOrOddWhitespaceStillMatches() {
        assertTrue(SelfCheckText.same("  Tap  me\n", "Tap me"))
        assertTrue(SelfCheckText.same("Claire  self-check\tpage", "Claire self-check page"))
    }

    @Test
    fun differentTextDoesNotMatch() {
        assertFalse(SelfCheckText.same("Tap me now", "Tap me"))
        assertFalse(SelfCheckText.same("Tapme", "Tap me"))
    }

    @Test
    fun nonLatinTextMatchesAsIs() {
        assertTrue(SelfCheckText.same("点我", "点我"))
        assertTrue(SelfCheckText.same("НАЖМИ МЕНЯ", "Нажми меня"))
    }

    @Test
    fun containsIgnoresCaseAndWhitespace() {
        assertTrue(SelfCheckText.contains("CLAIRE 123", "Claire 123"))
        assertTrue(SelfCheckText.contains("text  CLICKED 1 here", "Clicked 1"))
        assertFalse(SelfCheckText.contains("Clicked 0", "Clicked 1"))
    }

    @Test
    fun numberedTextIgnoresCaseAndWhitespace() {
        val taps = SelfCheckText.Numbered("Taps: 987654321", "987654321")
        assertTrue(taps.matches("Taps: 0"))
        assertTrue(taps.matches("TAPS:  12"))
        assertFalse(taps.matches("Taps: "))
        assertFalse(taps.matches("Taps: x"))
        assertFalse(taps.matches("Ticks: 3"))
    }

    @Test
    fun numberedTextWithWordsAfterTheNumber() {
        val taps = SelfCheckText.Numbered("点了 987654321 次", "987654321")
        assertTrue(taps.matches("点了 3 次"))
        assertFalse(taps.matches("点了 3"))
    }

    @Test
    fun numberedTextMatchesArabicIndicDigits() {
        // In Arabic and similar languages %d is rendered as Arabic-Indic digits, and that is what the screen reader reports back.
        val taps = SelfCheckText.Numbered("النقرات: ٩٨٧٦٥٤٣٢١", "987654321")
        assertTrue(taps.matches("النقرات: ٠"))
        assertTrue(taps.matches("النقرات: ١٢"))
        assertFalse(taps.matches("النبضة: ٠"))
    }

    @Test
    fun digitsToAsciiNormalizesVariants() {
        assertTrue(SelfCheckText.same("Taps: ٠", "Taps: 0"))
        assertTrue(SelfCheckText.same("Taps: ٩٨٧", "Taps: 987"))
        assertFalse(SelfCheckText.same("Taps: ٠", "Taps: 1"))
    }
}
