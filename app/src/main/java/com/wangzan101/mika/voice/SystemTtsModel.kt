package com.wangzan101.mika.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.core.model.TtsRequest
import com.wangzan101.mika.core.model.TtsResponse
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceErrorCategory
import com.wangzan101.mika.voice.model.VoiceModelError
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Locale

/**
 * A non-streaming voice model that synthesizes a complete WAV with the system [TextToSpeech]. Every synthesis creates a new engine, initializes it, synthesizes into a temporary file,
 * waits for the completion callback of [SystemTtsEngine.setOnUtteranceProgressListener] and then reads the bytes; completion, error and cancellation always stop/shutdown.
 * After the [TtsResponse] is returned, the existing [VoicePlayer] plays it; no separate playback path or background service is opened.
 */
class SystemTtsModel(
    private val context: Context,
    private val engineFactory: (Context) -> SystemTtsEngine,
    private val resolvedLanguage: () -> AppLanguage,
    private val outputDir: () -> File,
    private val maxChars: Int = TtsCapabilities().maxChars,
) : TtsModel {
    override val name: String = "system-tts"
    override val capabilities: TtsCapabilities = TtsCapabilities(voices = null, maxChars = maxChars, formats = setOf("wav"))

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val engine = engineFactory(context)
        return try {
            val ready = CompletableDeferred<Int>()
            engine.init { ready.complete(it) }
            if (ready.await() == TextToSpeech.ERROR) {
                throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech engine is unavailable",
                )
            }

            val locale = resolvedLanguage().speechTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            when (engine.setLanguage(locale)) {
                TextToSpeech.LANG_MISSING_DATA -> throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech language data is missing",
                )
                TextToSpeech.LANG_NOT_SUPPORTED -> throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech does not support this language",
                )
            }
            engine.setSpeechRate(request.speed ?: 1f)

            val dir = outputDir()
            dir.mkdirs()
            val file = File(dir, "mika-tts-${System.nanoTime()}.wav")
            val utteranceId = "mika-tts-${System.nanoTime()}"

            try {
                val complete = CompletableDeferred<Unit>()
                engine.setOnUtteranceProgressListener(
                    onDone = { complete.complete(Unit) },
                    onError = {
                        complete.completeExceptionally(
                            VoiceModelError.Protocol(
                                VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech synthesis failed",
                            ),
                        )
                    },
                )
                if (engine.synthesizeToFile(request.text, file, utteranceId) != TextToSpeech.SUCCESS) {
                    throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech synthesis failed",
                    )
                }
                complete.await()

                val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
                if (bytes.isEmpty()) {
                    throw VoiceModelError.Protocol(
                        VoiceErrorCategory.PROTOCOL_ERROR, "System text-to-speech produced no audio",
                    )
                }
                TtsResponse(bytes, "audio/wav")
            } finally {
                runCatching { file.delete() }
            }
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }
}
