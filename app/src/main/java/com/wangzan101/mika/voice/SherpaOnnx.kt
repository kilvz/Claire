package com.wangzan101.mika.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * On-device streaming recognition. Bundled models stream straight out of the
 * APK's assets through the AssetManager; optional downloaded models live under
 * filesDir and load from absolute paths. No network, no account, no vendor.
 *
 * The model set is a streaming Zipformer transducer (encoder/decoder/joiner +
 * tokens) at 16 kHz, mono PCM16 — the same audio [PcmRecorder] delivers.
 */
object SherpaOnnx {
    const val SAMPLE_RATE = 16_000

    /** Files one streaming transducer needs; [asset] picks assets vs. absolute file paths. */
    data class ModelFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val asset: Boolean,
    )

    /** The English model shipped in the APK (see assets/sherpa/en-20m/). */
    fun bundledFiles(): ModelFiles = ModelFiles(
        encoder = "sherpa/en-20m/encoder-epoch-99-avg-1.int8.onnx",
        decoder = "sherpa/en-20m/decoder-epoch-99-avg-1.onnx",
        joiner = "sherpa/en-20m/joiner-epoch-99-avg-1.int8.onnx",
        tokens = "sherpa/en-20m/tokens.txt",
        asset = true,
    )

    /** A model unpacked by [com.wangzan101.mika.voice.model.SherpaModelStore]. */
    fun downloadedFiles(dir: File): ModelFiles = ModelFiles(
        encoder = File(dir, "encoder.onnx").absolutePath,
        decoder = File(dir, "decoder.onnx").absolutePath,
        joiner = File(dir, "joiner.onnx").absolutePath,
        tokens = File(dir, "tokens.txt").absolutePath,
        asset = false,
    )

    /**
     * Loads the transducer with sherpa-onnx's Java API. Throws if the native
     * library or model files are unusable; callers map that to
     * [com.wangzan101.mika.voice.SpeechEngineSpec.Why.SherpaFailed].
     */
    fun recognizer(context: Context, files: ModelFiles): OnlineRecognizer {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = files.encoder,
                    decoder = files.decoder,
                    joiner = files.joiner,
                ),
                tokens = files.tokens,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return if (files.asset) {
            OnlineRecognizer(assetManager = context.assets, config = config)
        } else {
            OnlineRecognizer(config = config)
        }
    }
}
