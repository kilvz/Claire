package com.wangzan101.mika.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real-device smoke test: synthesizes a complete WAV through the real [AndroidSystemTtsEngine]. When the device has no usable engine or is missing language data,
 * the assertion is that an explicit system-unavailable result comes back (rather than a crash or a switch to the cloud); when the language happens to be supported it passes without error.
 */
@RunWith(AndroidJUnit4::class)
class SystemTtsDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun synthesize(language: AppLanguage, text: String) =
        SystemTtsModel(
            context = context,
            engineFactory = { AndroidSystemTtsEngine(it) },
            resolvedLanguage = { language },
            outputDir = { File(context.cacheDir, "voice") },
        ).synthesize(TtsRequest(text))

    @Test
    fun systemEngineSynthesizesNonEmptyWavForTheResolvedLanguage() = runBlocking {
        val result = runCatching { synthesize(AppLanguage.resolved(context), "Hello, this is a system speech test.") }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            assertTrue(error is VoiceModelError)
            assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
            return@runBlocking
        }
        val response = result.getOrThrow()
        assertEquals("audio/wav", response.mime)
        assertTrue(response.bytes.isNotEmpty())
    }

    @Test
    fun unsupportedLanguageReturnsTheExplicitCapabilityFailure() = runBlocking {
        val error = runCatching {
            synthesize(AppLanguage.Japanese, "こんにちは。")
        }.exceptionOrNull() ?: return@runBlocking

        assertTrue(error is VoiceModelError)
        assertEquals(VoiceErrorCategory.SYSTEM_UNAVAILABLE, (error as VoiceModelError).category)
    }
}
