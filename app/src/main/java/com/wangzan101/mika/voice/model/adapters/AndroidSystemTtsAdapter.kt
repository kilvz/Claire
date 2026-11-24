package com.wangzan101.mika.voice.model.adapters

import android.content.Context
import com.wangzan101.mika.core.model.TtsCapabilities
import com.wangzan101.mika.core.model.TtsModel
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.AndroidSystemTtsEngine
import com.wangzan101.mika.voice.SystemTtsEngine
import com.wangzan101.mika.voice.SystemTtsModel
import com.wangzan101.mika.voice.model.ResolvedVoiceModel
import com.wangzan101.mika.voice.model.TtsProtocol
import com.wangzan101.mika.voice.model.VoiceCapability
import com.wangzan101.mika.voice.model.VoiceModelError
import java.io.File

/** Built-in Android system TTS: synthesizes one complete WAV at a time, non-streaming, and its capability declaration is not delegated to the cloud or to a second playback path. */
class AndroidSystemTtsAdapter(
    private val engineFactory: (Context) -> SystemTtsEngine = { AndroidSystemTtsEngine(it) },
) : TtsAdapter {
    override val protocol = TtsProtocol.AndroidSystem

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
        val app = context.applicationContext
        return SystemTtsModel(
            context = app,
            engineFactory = engineFactory,
            resolvedLanguage = { AppLanguage.resolved(app) },
            outputDir = { File(app.cacheDir, "voice") },
            maxChars = capabilities.maxChars,
        )
    }
}
