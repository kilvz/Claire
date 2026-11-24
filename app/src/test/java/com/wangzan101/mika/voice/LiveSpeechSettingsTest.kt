package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import com.wangzan101.mika.settings.KeyCipher
import java.io.IOException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Selection, persistence and engine resolution for self-hosted Fun-ASR and Bailian, plus the wording shown on the settings page. */
class LiveSpeechSettingsTest {
    @get:Rule val texts = ChineseTexts()

    private fun cipher(): KeyCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmKeyCipher { key }
    }

    @Test fun choicesSurviveARestart() {
        for (c in listOf(
            SpeechChoice.FunAsr("ws://192.168.1.10:10095"), SpeechChoice.Bailian(),
            SpeechChoice.Bailian("paraformer-realtime-v2", BailianRegion.Singapore), SpeechChoice.Bailian(workspace = "llm-abc123"),
            SpeechChoice.FunAsr("ws://h:1", AsrLanguage.Japanese), SpeechChoice.Bailian(language = AsrLanguage.Cantonese),
        )) {
            val prefs = MemoryPrefs()
            SpeechSettings(prefs).set(c)
            assertEquals(c, SpeechSettings(prefs).choice.value)
        }
    }

    @Test fun brokenStoredChoicesFallBackToAuto() {
        for (raw in listOf(
            """{"type":"funasr","url":"ftp://x"}""", """{"type":"funasr"}""",
            """{"type":"bailian","model":"fun-asr-realtime","region":"Mars"}""", """{"type":"bailian","model":"bad model","region":"Beijing"}""",
            """{"type":"bailian","model":"fun-asr-realtime","region":"Beijing","workspace":"a.b"}""",
        )) {
            val prefs = MemoryPrefs().also { it.edit().putString("speech_choice", raw).apply() }
            assertEquals(raw, SpeechChoice.Auto, SpeechSettings(prefs).choice.value)
        }
    }

    // An unknown language code does not invalidate the whole choice; it is treated as automatic
    @Test fun unknownLanguageIsAuto() {
        val prefs = MemoryPrefs().also { it.edit().putString("speech_choice", """{"type":"funasr","url":"ws://h:1","language":"xx"}""").apply() }
        assertEquals(SpeechChoice.FunAsr("ws://h:1"), SpeechSettings(prefs).choice.value)
    }

    @Test fun languagesAreRememberedPerTab() {
        val s = SpeechSettings(MemoryPrefs(), cipher())
        assertEquals(AsrLanguage.Auto, s.funAsrLanguage)
        s.saveFunAsr("h", AsrLanguage.English)
        assertEquals(AsrLanguage.English, s.funAsrLanguage)
        assertEquals(SpeechChoice.FunAsr("ws://h:10095", AsrLanguage.English), s.lastLive)
        s.saveBailian("k", SpeechChoice.Bailian(region = BailianRegion.Singapore, language = AsrLanguage.Korean))
        assertEquals(AsrLanguage.Korean, s.bailianLanguage(BailianRegion.Singapore))
        assertEquals(AsrLanguage.Auto, s.bailianLanguage(BailianRegion.Beijing))
        assertEquals(
            SpeechEngineSpec.Bailian("fun-asr-realtime", BailianRegion.Singapore, "", AsrLanguage.Korean),
            SpeechResolver.resolve(s.choice.value, SystemCapability(false, false), emptyList()),
        )
        s.clearBailian(BailianRegion.Singapore)
        assertEquals(AsrLanguage.Auto, s.bailianLanguage(BailianRegion.Singapore))
    }

    @Test fun funAsrAddressIsNormalizedAndRemembered() {
        val prefs = MemoryPrefs(); val s = SpeechSettings(prefs)
        assertNull(s.funAsrUrl)
        s.saveFunAsr("192.168.1.10")
        assertEquals(SpeechChoice.FunAsr("ws://192.168.1.10:10095"), s.choice.value)
        s.set(SpeechChoice.Off)
        assertEquals("ws://192.168.1.10:10095", SpeechSettings(prefs).funAsrUrl)
        assertThrows(IllegalArgumentException::class.java) { s.saveFunAsr("ftp://nope") }
        assertEquals(SpeechChoice.Off, s.choice.value)
    }

    @Test fun bailianKeysAreEncryptedPerRegionKeptAndRemoved() {
        val prefs = MemoryPrefs(); val cipher = cipher()
        val s = SpeechSettings(prefs, cipher)
        val sg = SpeechChoice.Bailian("paraformer-realtime-v2", BailianRegion.Singapore, "llm-abc123")
        // With no key stored for this region, the model alone cannot be changed
        assertThrows(IllegalArgumentException::class.java) { s.saveBailian("", sg) }
        s.saveBailian(" sk-test-only ", sg)
        assertFalse(prefs.all.toString().contains("sk-test-only"))
        val again = SpeechSettings(prefs, cipher)
        assertEquals(sg, again.choice.value)
        assertEquals("sk-test-only", again.bailianKey(BailianRegion.Singapore))
        assertTrue(again.hasBailianKey(BailianRegion.Singapore))
        // The Beijing entry is a separate one
        assertFalse(again.hasBailianKey(BailianRegion.Beijing)); assertNull(again.bailianKey(BailianRegion.Beijing))
        assertEquals("paraformer-realtime-v2", again.bailianModel(BailianRegion.Singapore))
        assertEquals("llm-abc123", again.bailianWorkspace(BailianRegion.Singapore))
        assertEquals(BailianProtocol.DEFAULT_MODEL, again.bailianModel(BailianRegion.Beijing))
        assertEquals("", again.bailianWorkspace(BailianRegion.Beijing))
        // Left empty, the stored key is reused and only the model is changed
        again.set(SpeechChoice.Off)
        again.saveBailian("", SpeechChoice.Bailian("fun-asr-realtime", BailianRegion.Singapore))
        assertEquals("sk-test-only", again.bailianKey(BailianRegion.Singapore))
        assertEquals(SpeechChoice.Bailian("fun-asr-realtime", BailianRegion.Singapore), again.choice.value)
        // With a different key the stored value cannot be read, so the wrong key is never used to connect
        assertNull(SpeechSettings(prefs, cipher()).bailianKey(BailianRegion.Singapore))
        assertThrows(IllegalArgumentException::class.java) { again.saveBailian("k", SpeechChoice.Bailian("bad model")) }
        assertThrows(IllegalArgumentException::class.java) { again.saveBailian("k", SpeechChoice.Bailian(workspace = "abc")) }
        // The region being deleted is the one in use: voice input is turned off
        again.clearBailian(BailianRegion.Singapore)
        assertFalse(again.hasBailianKey(BailianRegion.Singapore)); assertNull(again.bailianKey(BailianRegion.Singapore))
        assertEquals(SpeechChoice.Off, again.choice.value)
        assertEquals("", again.bailianWorkspace(BailianRegion.Singapore))
    }

    @Test fun clearingOneRegionKeepsOtherChoices() {
        val s = SpeechSettings(MemoryPrefs(), cipher())
        s.saveBailian("k1", SpeechChoice.Bailian())
        s.saveBailian("k2", SpeechChoice.Bailian(region = BailianRegion.Singapore))
        s.clearBailian(BailianRegion.Beijing)
        assertEquals(SpeechChoice.Bailian(region = BailianRegion.Singapore), s.choice.value)
        assertEquals("k2", s.bailianKey(BailianRegion.Singapore))
        s.set(SpeechChoice.System)
        s.clearBailian(BailianRegion.Singapore)
        assertEquals(SpeechChoice.System, s.choice.value)
    }

    // Tapping the radio button on the "Qwen real-time speech recognition" row reuses the last kind used; if that kind is no longer usable, there is none
    @Test fun lastLiveFollowsTheLastSavedKind() {
        val prefs = MemoryPrefs(); val cipher = cipher()
        val s = SpeechSettings(prefs, cipher)
        assertNull(s.lastLive); assertFalse(s.hasLive)
        s.saveFunAsr("192.168.1.10")
        assertEquals(SpeechChoice.FunAsr("ws://192.168.1.10:10095"), s.lastLive)
        s.saveBailian("k", SpeechChoice.Bailian(region = BailianRegion.Singapore))
        s.set(SpeechChoice.Off)
        assertEquals(SpeechChoice.Bailian(region = BailianRegion.Singapore), SpeechSettings(prefs, cipher).lastLive)
        s.clearBailian(BailianRegion.Singapore)
        assertNull(s.lastLive)
        // The self-hosted address is still stored and is prefilled on the settings page
        assertEquals("ws://192.168.1.10:10095", s.funAsrUrl)
    }

    @Test fun resolverGivesTheLiveEnginesWithoutTouchingSystemSpeech() {
        val none = SystemCapability(false, false)
        assertEquals(SpeechEngineSpec.FunAsr("ws://h:1"), SpeechResolver.resolve(SpeechChoice.FunAsr("ws://h:1"), none, emptyList()))
        assertEquals(
            SpeechEngineSpec.Bailian("fun-asr-realtime", BailianRegion.Beijing, "llm-abc"),
            SpeechResolver.resolve(SpeechChoice.Bailian(workspace = "llm-abc"), SystemCapability(true, true), emptyList()),
        )
    }

    @Test fun summariesNameTheService() {
        val none = SystemCapability(false, false)
        assertEquals(SpeechOptions.Summary("千问（自建）", "192.168.1.10:10095", false), SpeechOptions.summary(SpeechChoice.FunAsr("ws://192.168.1.10:10095"), none, emptyList()))
        assertEquals(SpeechOptions.Summary("fun-asr-realtime", "阿里云百炼 · 北京", false), SpeechOptions.summary(SpeechChoice.Bailian(), none, emptyList()))
        assertEquals(
            SpeechOptions.Summary("paraformer-realtime-v2", "阿里云百炼 · 新加坡", false),
            SpeechOptions.summary(SpeechChoice.Bailian("paraformer-realtime-v2", BailianRegion.Singapore), none, emptyList()),
        )
    }

    @Test fun probeFailuresAreExplainedWithoutServerText() {
        assertEquals("服务拒绝了这个 Key。检查 Key 是否正确、是否属于所选地域。", SpeechOptions.probeFailure(ModelError.Auth()))
        assertEquals("服务拒绝了这个 Key。检查 Key 是否正确、是否属于所选地域。", SpeechOptions.probeFailure(LiveAsrError("InvalidApiKey", auth = true)))
        assertEquals("服务拒绝了请求（ModelNotFound）。", SpeechOptions.probeFailure(LiveAsrError("ModelNotFound")))
        assertEquals("连不上服务。检查地址，以及服务是否在运行。", SpeechOptions.probeFailure(IOException("refused")))
        assertEquals("连不上服务。检查地址，以及服务是否在运行。", SpeechOptions.probeFailure(ModelError.Network()))
    }
}
