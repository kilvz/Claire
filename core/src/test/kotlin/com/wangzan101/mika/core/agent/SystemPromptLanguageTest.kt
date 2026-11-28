package com.wangzan101.mika.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptLanguageTest {

    @Test
    fun testLanguageDirectiveEnglish() {
        val directive = SystemPrompt.languageDirective("en")
        assertTrue(directive.contains("Communication & Reasoning Language"))
        assertTrue(directive.contains("formulate all progress notes, tool call reasons, and final replies in English"))
        assertFalse(directive.contains("所有思考过程"))
    }

    @Test
    fun testLanguageDirectiveChinese() {
        val directive = SystemPrompt.languageDirective("zh-CN")
        assertTrue(directive.contains("Communication & Reasoning Language"))
        assertTrue(directive.contains("final replies in Chinese"))
    }

    @Test
    fun testLanguageDirectiveJapanese() {
        val directive = SystemPrompt.languageDirective("ja")
        assertTrue(directive.contains("Communication & Reasoning Language"))
        assertTrue(directive.contains("final replies in Japanese"))
    }

    @Test
    fun testLanguageDirectiveSystemOrEmpty() {
        val dirNull = SystemPrompt.languageDirective(null)
        val dirBlank = SystemPrompt.languageDirective("  ")
        val dirSystem = SystemPrompt.languageDirective("system")
        assertTrue(dirNull.contains("Always mirror the language of the user's prompt"))
        assertTrue(dirBlank.contains("Always mirror the language of the user's prompt"))
        assertTrue(dirSystem.contains("Always mirror the language of the user's prompt"))
    }

    @Test
    fun allLocalesUseTheirDirectiveAndUnsupportedOnesMirrorThePrompt() {
        val expected = mapOf(
            "en" to "final replies in English",
            "zh-Hans" to "final replies in Chinese",
            "zh-Hant" to "final replies in Chinese",
            "ja" to "final replies in Japanese",
            "ko" to "final replies in Korean",
            "es" to "final replies in Spanish",
            "pt" to "final replies in Portuguese",
            "ru" to "final replies in Russian",
            "ar" to "final replies in Arabic",
            "fa" to "final replies in Persian",
            "id" to "final replies in Indonesian",
            "tr" to "final replies in Turkish",
        )
        for ((tag, text) in expected) assertTrue(tag, SystemPrompt.languageDirective(tag).contains(text))
        for (tag in listOf("vi", "und", "not-a-language")) {
            assertTrue(tag, SystemPrompt.languageDirective(tag).contains("Always mirror the language of the user's prompt"))
        }
    }

    @Test
    fun testWithLanguageAppendsToPrompt() {
        val base = "You are Claire."
        val withEn = SystemPrompt.withLanguage(base, "en")
        assertTrue(withEn.startsWith("You are Claire."))
        assertTrue(withEn.contains("Communication & Reasoning Language"))
    }
}
