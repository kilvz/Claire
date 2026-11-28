package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Locale

class OnDeviceLanguageTest {
    private val catalog = VoicePackCatalog.parse(File("src/main/assets/voice-models/catalog.json").readText()) { _, _ -> true }

    @Test
    fun scriptAndRegionTagsMapToLanguagePart() {
        assertEquals("zh", OnDeviceLanguage.languagePart("zh-Hans-CN"))
        assertEquals("pt", OnDeviceLanguage.languagePart("pt-BR"))
        assertEquals("id", OnDeviceLanguage.languagePart("in-ID"))
        assertNull(OnDeviceLanguage.languagePart(null))
        assertNull(OnDeviceLanguage.languagePart(""))
    }

    @Test
    fun pickReturnsNullWhenUnsupported() {
        assertNull(OnDeviceLanguage.pick("fa-IR", listOf("zh", "en")))
        assertEquals("en", OnDeviceLanguage.pick("en-US", listOf("zh", "en")))
        assertNull(OnDeviceLanguage.pick(null, listOf("zh", "en")))
    }

    @Test
    fun pickUsesModelLegacyCodes() {
        assertEquals("iw", OnDeviceLanguage.pick("he-IL", listOf("en", "iw")))
        assertEquals("jw", OnDeviceLanguage.pick("jv-ID", listOf("en", "jw")))
    }

    @Test
    fun systemLanguageResolvesRecommendation() {
        assertEquals("whisper-base-int8", OnDeviceLanguage.recommended(catalog, AppLanguage.System, Locale("in", "ID"))?.id)
        // Unsupported system languages fall back to English.
        assertEquals("whisper-base-int8", OnDeviceLanguage.recommended(catalog, AppLanguage.System, Locale("de", "DE"))?.id)
        // The curated catalog has no pack recommended for Vietnamese or Chinese.
        assertNull(OnDeviceLanguage.recommended(catalog, AppLanguage.System, Locale("vi", "VN")))
        assertNull(OnDeviceLanguage.recommended(catalog, AppLanguage.System, Locale("zh", "TW")))
    }

    @Test
    fun explicitAppLanguageWins() {
        assertEquals("whisper-base-int8", OnDeviceLanguage.recommended(catalog, AppLanguage.Indonesian, Locale("vi", "VN"))?.id)
        assertNull(OnDeviceLanguage.recommended(catalog, AppLanguage.Persian))
    }

    @Test
    fun onlyEnglishAndIndonesianHaveRecommendations() {
        val recommended = AppLanguage.entries.filter { it.tag != null }.mapNotNull { OnDeviceLanguage.recommended(catalog, it)?.id }.toSet()
        assertEquals(setOf("whisper-base-int8"), recommended)
    }

    @Test
    fun chineseScriptFromRegion() {
        assertEquals(AppLanguage.TraditionalChinese, OnDeviceLanguage.appLanguageFor(Locale("zh", "HK")))
        assertEquals(AppLanguage.SimplifiedChinese, OnDeviceLanguage.appLanguageFor(Locale("zh", "CN")))
        assertEquals(AppLanguage.SimplifiedChinese, OnDeviceLanguage.appLanguageFor(Locale.forLanguageTag("zh-Hans-TW")))
    }
}
