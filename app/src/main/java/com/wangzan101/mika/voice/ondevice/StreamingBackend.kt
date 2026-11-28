package com.wangzan101.mika.voice.ondevice

import android.os.Build
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.wangzan101.mika.voice.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The sherpa-onnx streaming transducer (`OnlineRecognizer`), used by live voice packs. The manifest's `family` is the
 * transducer generation sherpa-onnx must be told (`zipformer` for the compact English model, `zipformer2` for the newer
 * multilingual export); files are filled in by `role`.
 *
 * One recognizer is shared between sessions (`OnDeviceEngines` keeps it loaded), and every push-to-talk session creates
 * its own [OnlineStream]. Bundled packs read straight from the APK assets; downloaded packs load from absolute paths.
 */
class StreamingBackend : OnDeviceSttBackend {
    override val id = ID

    override fun supports(family: String) = family in FAMILIES

    override fun abiSupported() = ABI in Build.SUPPORTED_ABIS

    override fun load(pack: InstalledVoicePack, options: EngineOptions): OnDeviceSttEngine {
        if (!abiSupported()) throw OnDeviceSttException(OnDeviceSttException.Kind.Unsupported, "sherpa-onnx needs $ABI")
        val config = config(pack, options)
        // A bundled pack lives in the APK; a downloaded one is a set of absolute paths. A native load failure only returns
        // a null pointer (require throws IllegalArgumentException), which OnDeviceEngines records as corrupt files.
        val recognizer = if (pack.bundled) {
            val assets = pack.assets
                ?: throw OnDeviceSttException(OnDeviceSttException.Kind.Corrupt, "bundled pack ${pack.entry.id} has no assets")
            OnlineRecognizer(assetManager = assets, config = config)
        } else {
            pack.entry.files.map { pack.file(it.role) }.firstOrNull { !it.isFile }?.let {
                throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "voice pack ${pack.entry.id} is missing ${it.name}")
            }
            OnlineRecognizer(assetManager = null, config = config)
        }
        return Engine(recognizer)
    }

    /** One loaded recognizer. Stream operations and release exclude each other, so the native object is never released mid-use. */
    private class Engine(private val recognizer: OnlineRecognizer) : OnDeviceSttEngine {
        private val lock = Any()
        private var closed = false

        override suspend fun transcribe(pcm: ShortArray, language: String?): String = withContext(Dispatchers.Default) {
            synchronized(lock) {
                if (closed) throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "recognizer released")
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(toFloat(pcm), Wav.RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }

        override fun newStream(language: String?): OnDeviceSttStream = synchronized(lock) {
            if (closed) throw OnDeviceSttException(OnDeviceSttException.Kind.Missing, "recognizer released")
            Stream(recognizer, recognizer.createStream(), lock)
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                recognizer.release()
            }
        }
    }

    /**
     * One session's stream; [text] decodes whatever arrived since the last call. All native calls share the engine's
     * lock, so two streams (or a whole-clip decode) never drive the same recognizer at once and release waits for them.
     */
    private class Stream(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
        private val lock: Any,
    ) : OnDeviceSttStream {
        private var closed = false

        override fun accept(pcm: ShortArray) = synchronized(lock) {
            if (!closed) stream.acceptWaveform(toFloat(pcm), Wav.RATE)
        }

        override fun text(): String = synchronized(lock) {
            if (closed) return ""
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        }

        override fun isEndpoint(): Boolean = synchronized(lock) {
            !closed && recognizer.isEndpoint(stream)
        }

        override fun reset() = synchronized(lock) {
            if (!closed) recognizer.reset(stream)
        }

        override fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                stream.release()
            }
        }
    }

    companion object {
        const val ID = "sherpa-onnx-streaming"
        private const val ABI = "arm64-v8a"

        /** Manifest families this backend loads; the family is also the model type sherpa-onnx must trust. */
        val FAMILIES = setOf("zipformer", "zipformer2")

        /** Fills the pack's files (by role) into the streaming transducer config; bundled packs use asset paths. */
        internal fun config(pack: InstalledVoicePack, options: EngineOptions): OnlineRecognizerConfig {
            fun path(role: String) = if (pack.bundled) pack.assetPath(role) else pack.file(role).absolutePath
            return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = Wav.RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = path("encoder"),
                        decoder = path("decoder"),
                        joiner = path("joiner"),
                    ),
                    tokens = path("tokens"),
                    numThreads = options.threads,
                    provider = "cpu",
                    modelType = pack.entry.family,
                ),
                endpointConfig = EndpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            )
        }

        internal fun toFloat(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }
    }
}
