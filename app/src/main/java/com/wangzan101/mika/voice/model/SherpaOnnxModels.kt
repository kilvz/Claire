package com.wangzan101.mika.voice.model

import android.content.Context
import com.wangzan101.mika.voice.SherpaOnnx

/**
 * The on-device recognition service: one local engine, two models.
 *
 * - [EN20M] ships inside the APK (assets/sherpa/en-20m), English, ~43 MB, runs on
 *   weak CPUs — the default and always available offline.
 * - [MULTILINGUAL] is the sherpa-onnx 8-language streaming zipformer
 *   (Arabic/English/Indonesian/Japanese/Russian/Thai/Vietnamese/Chinese). Its
 *   encoder alone is ~297 MB, so it is never bundled: the catalog page downloads
 *   it once into app storage (see [SherpaModelStore]) and it stays available
 *   offline afterwards.
 *
 * Neither model needs an account, a key or network access while listening.
 */
object SherpaOnnxModels {
    const val SERVICE_ID = "sherpa-onnx"
    const val EN20M_ID = "en-20m"
    const val MULTILINGUAL_ID = "multi-8-lang"

    val SERVICE = VoiceService(SERVICE_ID, "Sherpa-ONNX", VoiceAccount.None)

    /** Bundled in the APK; nothing to install. */
    val EN20M = VoiceModel(
        serviceId = SERVICE_ID,
        id = EN20M_ID,
        displayName = "Sherpa-ONNX English (on-device)",
        stt = SttBinding(SttProtocol.SherpaStreaming, language = "en"),
    )

    /** Optional download; covers English and Indonesian (plus ar/ja/ru/th/vi/zh). */
    val MULTILINGUAL = VoiceModel(
        serviceId = SERVICE_ID,
        id = MULTILINGUAL_ID,
        displayName = "Sherpa-ONNX multilingual (download)",
        stt = SttBinding(SttProtocol.SherpaStreaming, language = "en"),
    )

    val BUILTIN = listOf(EN20M, MULTILINGUAL)

    /** What to fetch for a downloadable model: the release archive and the entries to keep. */
    data class Download(
        val url: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val approximateBytes: Long,
    )

    val DOWNLOADS = mapOf(
        MULTILINGUAL_ID to Download(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10.tar.bz2",
            encoder = "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            decoder = "decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
            joiner = "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
            approximateBytes = 258_900_000L,
        ),
    )

    /** Everything the engine needs for [modelId] is present on the device. */
    fun available(context: Context, modelId: String): Boolean =
        modelId == EN20M_ID || SherpaModelStore.ready(context, modelId)

    /** Model files for [modelId]; the caller must have checked [available] first. */
    fun files(context: Context, modelId: String): SherpaOnnx.ModelFiles =
        if (modelId == EN20M_ID) SherpaOnnx.bundledFiles()
        else SherpaOnnx.downloadedFiles(SherpaModelStore.dir(context, modelId))
}
