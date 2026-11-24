package com.wangzan101.mika.core.learn.memory

import org.junit.Assert.assertEquals
import org.junit.Test

/** Number normalization shared by memory validation and skill redaction: decimal numbers written in different scripts must collapse to the same ASCII string. */
class NumberTextTest {
    @Test fun separatesAndCollapsesAsciiDigits() {
        assertEquals("13800138000", NumberText.collapse("138 0013 8000"))
        assertEquals("1234", NumberText.collapse("1,234"))
        assertEquals("123", NumberText.collapse("1.2.3"))
        assertEquals("12345", NumberText.collapse("１２３４５"))
    }

    @Test fun chineseDigitRunsCollapseToAscii() {
        assertEquals("13800138000", NumberText.collapse("一三八零零一三八零零零"))
        assertEquals("十百千", NumberText.collapse("十百千"))
    }

    @Test fun nonAsciiDecimalDigitsAreNormalizedBeforeCollapsing() {
        assertEquals("123456", NumberText.collapse("١٢٣ ٤٥٦"))   // Arabic-Indic
        assertEquals("123456", NumberText.collapse("۱۲۳ ۴۵۶"))   // Extended Arabic-Indic (Persian)
        assertEquals("123456", NumberText.collapse("१२३ ४५६"))   // Devanagari
        assertEquals("123456", NumberText.collapse("１２３ ４５６")) // fullwidth, separated
        assertEquals("13800138000", NumberText.collapse("١٣٨ 0013 ٨٠٠٠"))
    }

    @Test fun shortGroupsAndPlainTextStayAsTheyAre() {
        assertEquals("1 2", NumberText.collapse("1 2"))
        assertEquals("验证码 ab12", NumberText.collapse("验证码 ab12"))
        assertEquals("", NumberText.collapse(""))
    }
}
