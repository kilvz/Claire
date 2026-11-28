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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * A non-streaming voice model that synthesises a complete WAV with the system [TextToSpeech]. Each synthesis creates a new engine, initialises it, synthesises to a temp file,
 * waits for the completion callback of [SystemTtsEngine.setOnUtteranceProgressListener] and then reads the bytes; on completion, error or cancellation it always stops/shuts down.
 * The [TtsResponse] is handed back and played by the existing [VoicePlayer], without another playback path or background service. Initialisation and synthesis each have a timeout,
 * and fail by timeout when the engine does not respond or dies midway. Which engine to use is decided by [engineFactory] (the adapter builds it from [com.wangzan101.mika.voice.model.TtsBinding.engine] in the catalog).
 */
class SystemTtsModel(
    private val context: Context,
    private val engineFactory: (Context) -> SystemTtsEngine,
    private val resolvedLanguage: () -> AppLanguage,
    private val outputDir: () -> File,
    private val maxChars: Int = TtsCapabilities().maxChars,
    private val initTimeoutMs: Long = INIT_TIMEOUT_MS,
    private val synthesisTimeoutMs: Long = SYNTHESIS_TIMEOUT_MS,
) : TtsModel {
    override val name: String = "system-tts"
    override val capabilities: TtsCapabilities = TtsCapabilities(voices = null, maxChars = maxChars, formats = setOf("wav"))

    override suspend fun synthesize(request: TtsRequest): TtsResponse {
        val engine = engineFactory(context)
        return try {
            val ready = CompletableDeferred<Int>()
            engine.init { ready.complete(it) }
            // When the engine does not respond (process cannot start, killed), do not wait forever
            val status = withTimeoutOrNull(initTimeoutMs) { ready.await() }
                ?: throw VoiceModelError.Protocol(
                    VoiceErrorCategory.TIMEOUT, "System text-to-speech engine did not respond",
                )
            if (status != TextToSpeech.SUCCESS) {
                throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech engine is unavailable",
                )
            }

            // The user-selected voice carries its own language, so once applied it is no longer chosen by UI language; when this engine does not have it (engine changed, a voice name from another vendor left by old settings,
            // voice data deleted), use the engine's default voice for the UI language
            val voiceName = request.voice?.trim().orEmpty()
            val voiceApplied = voiceName.isNotEmpty() && when (engine.setVoice(voiceName)) {
                SystemVoiceResult.Applied -> true
                SystemVoiceResult.NotOffered -> false
                SystemVoiceResult.Failed -> throw VoiceModelError.Protocol(
                    VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech could not use the selected voice",
                )
            }
            if (!voiceApplied) {
                val locale = resolvedLanguage().speechTag?.let(Locale::forLanguageTag) ?: Locale.getDefault()
                when (engine.setLanguage(locale)) {
                    TextToSpeech.LANG_MISSING_DATA -> throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech language data is missing",
                    )
                    TextToSpeech.LANG_NOT_SUPPORTED -> throw VoiceModelError.Protocol(
                        VoiceErrorCategory.SYSTEM_UNAVAILABLE, "System text-to-speech does not support this language",
                    )
                }
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
                // When the engine dies midway through synthesis, the progress callback will not come again
                withTimeoutOrNull(synthesisTimeoutMs) { complete.await() }
                    ?: throw VoiceModelError.Protocol(
                        VoiceErrorCategory.TIMEOUT, "System text-to-speech engine stopped responding",
                    )

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

    companion object {
        const val INIT_TIMEOUT_MS = 10_000L
        const val SYNTHESIS_TIMEOUT_MS = 60_000L
    }
}
