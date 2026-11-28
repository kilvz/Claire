package com.wangzan101.mika.voice.ondevice

import android.os.Build
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.wangzan101.mika.voice.Wav
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sherpa-onnx offline recognizer (`OfflineRecognizer`, from the AAR bundled in the APK, arm64-v8a only).
 * The manifest's `family` selects the model config and files are filled in by `role`; to add another model sherpa-onnx already supports,
 * add a branch in both [FAMILIES] and [modelConfig].
 */
class SherpaOnnxBackend : OnDeviceSttBackend {
    override val id = ID

    override fun supports(family: String) = family in FAMILIES

    override fun abiSupported() = ABI in Build.SUPPORTED_ABIS

    override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine {
        if (!abiSupported()) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "sherpa-onnx needs $ABI")
        val model = modelConfig(pack, options)
        pack.entry.files.map { File(pack.dir, it.name) }.firstOrNull { !it.isFile }?.let {
            throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${pack.entry.id} is missing ${it.name}")
        }
        val config = OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = Wav.RATE, featureDim = 80), modelConfig = model)
        // When the native side cannot get the model it only returns a null pointer (require throws IllegalArgumentException); OnDeviceEngines records that as corrupt files
        return Engine(OfflineRecognizer(assetManager = null, config = config), config, pack.entry.family)
    }

    /** One loaded recognizer. Decoding and release exclude each other, so the native object is never released mid-decode. */
    private class Engine(
        private val recognizer: OfflineRecognizer,
        private val config: OfflineRecognizerConfig,
        private val family: String,
    ) : OnDeviceSttEngine {
        private val lock = Any()
        private var closed = false
        private var senseVoiceLanguage = config.modelConfig.senseVoice.language

        override suspend fun transcribe(pcm: ShortArray, language: String?): String =
            withContext(Dispatchers.Default) { synchronized(lock) { decode(pcm, language.orEmpty()) } }

        private fun decode(pcm: ShortArray, language: String): String {
            if (closed) throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "recognizer released")
            // SenseVoice's language lives only in the recognizer config (read at decode time), so changing it rewrites the config; an empty string means auto
            if (family == SENSE_VOICE && language != senseVoiceLanguage) {
                recognizer.setConfig(
                    config.copy(modelConfig = config.modelConfig.copy(senseVoice = config.modelConfig.senseVoice.copy(language = language))),
                )
                senseVoiceLanguage = language
            }
            val stream = recognizer.createStream()
            try {
                if (family == WHISPER) {
                    // Whisper takes the language per segment, with an empty string for auto; the default cap is 6 tokens per second of audio, which truncates fast speech, so raise it to 25
                    stream.setOption("language", language)
                    stream.setOption("max_tokens_per_second", "25")
                }
                stream.acceptWaveform(toFloat(pcm), Wav.RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                recognizer.release()
            }
        }
    }

    companion object {
        const val ID = "sherpa-onnx"
        private const val ABI = "arm64-v8a"
        private const val SENSE_VOICE = "sense_voice"
        private const val WHISPER = "whisper"
        private const val DOLPHIN = "dolphin"
        private val FAMILIES = setOf(SENSE_VOICE, WHISPER, DOLPHIN)

        /** Fills the pack's files (by role) into the model config per family; the language starts empty (auto) and is set on every recognition. */
        internal fun modelConfig(pack: InstalledVoicePack, options: EngineOptions): OfflineModelConfig {
            fun path(role: String) = pack.file(role).path
            val m = OfflineModelConfig(tokens = path("tokens"), numThreads = options.threads)
            when (pack.entry.family) {
                SENSE_VOICE -> m.senseVoice = OfflineSenseVoiceModelConfig(model = path("model"), language = "")
                WHISPER -> m.whisper = OfflineWhisperModelConfig(encoder = path("encoder"), decoder = path("decoder"), language = "", task = "transcribe")
                DOLPHIN -> m.dolphin = OfflineDolphinModelConfig(model = path("model"))
                else -> throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "sherpa-onnx family ${pack.entry.family}")
            }
            return m
        }

        internal fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }
    }
}
