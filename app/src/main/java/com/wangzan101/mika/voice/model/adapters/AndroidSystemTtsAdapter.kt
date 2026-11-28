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

/**
 * Built-in Android system TTS: synthesises one complete WAV per call, non-streaming, and the capability declaration does not hand off to the cloud or a second playback path.
 * Which engine is used comes from [com.wangzan101.mika.voice.model.TtsBinding.engine] in the binding (empty = phone default engine).
 */
class AndroidSystemTtsAdapter(
    private val engineFactory: (context: Context, enginePackage: String?) -> SystemTtsEngine =
        { context, enginePackage -> AndroidSystemTtsEngine(context, enginePackage) },
) : TtsAdapter {
    override val protocol = TtsProtocol.AndroidSystem

    override fun create(context: Context, model: ResolvedVoiceModel, capabilities: TtsCapabilities): TtsModel {
        val binding = model.model.tts ?: throw VoiceModelError.Unsupported(VoiceCapability.TTS)
        if (binding.protocol != protocol) throw VoiceModelError.Unsupported(VoiceCapability.TTS, binding.protocol.name)
        val app = context.applicationContext
        val engine = binding.engine.trim().ifEmpty { null }
        return SystemTtsModel(
            context = app,
            engineFactory = { engineFactory(it, engine) },
            resolvedLanguage = { AppLanguage.resolved(app) },
            outputDir = { File(app.cacheDir, "voice") },
            maxChars = capabilities.maxChars,
        )
    }
}
