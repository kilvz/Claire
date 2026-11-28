package com.wangzan101.mika.core.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Declaration of Volcengine Ark Seedance video generation. Based on the official Ark documentation (checked 2026-11):
 * - The data-plane Base URL is only `https://ark.cn-beijing.volces.com/api/v3` ("Base URL and authentication"); Coding Plan uses a different address and cannot generate videos.
 * - Values for each model (resolution / ratio / duration from "Create video generation task"):
 *   Seedance 2.5: 4–30 seconds, 480p/720p/1080p/4k; Seedance 2.5 flash: 4–30 seconds, 480p/720p;
 *   Seedance 2.0: 4–15 seconds, 480p/720p/1080p/4k; Seedance 2.0 fast / mini: 4–15 seconds, 480p/720p.
 *   All support the aspect ratios 16:9, 4:3, 1:1, 3:4, 9:16 and 21:9; Seedance 2.5 first-frame video generation supports only adaptive (inherits the first frame's aspect ratio).
 *   generate_audio is supported by all 2.x models (default true).
 * The documentation does not give full model IDs for flash / fast / mini, so they are recognized by the words in the name; unrecognized models (inference endpoints ep-…, older versions) get the subset that all models support.
 */
object ArkSeedance : VideoBackend {
    override val id = "ark-seedance"
    override val modelHint = "doubao-seedance-2-0-260128"

    private const val HOST = "ark.cn-beijing.volces.com"
    private const val PATH = "/api/v3"

    private val ASPECTS = setOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
    private val BASIC_RESOLUTIONS = setOf("480p", "720p")
    private val ALL_RESOLUTIONS = setOf("480p", "720p", "1080p", "4k")

    /** Unknown models: values accepted by both 2.0 and 2.5; with a first frame send adaptive (accepted by both generations) and do not send generate_audio (use the provider default). */
    internal val COMMON = VideoCapabilities(
        durations = 4..15, aspects = ASPECTS, resolutions = BASIC_RESOLUTIONS,
        supportsFirstFrame = true, firstFrameKeepsAspect = true, supportsAudio = false,
    )

    override fun serves(baseUrl: String): Boolean {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return false
        return url.isHttps && url.host == HOST && url.port == 443 && url.encodedPath.trimEnd('/') == PATH
    }

    override fun capabilities(model: String): VideoCapabilities {
        val id = model.trim().lowercase()
        val light = listOf("flash", "fast", "mini").any { it in id }
        return when {
            "seedance-2-5" in id -> VideoCapabilities(
                durations = 4..30, aspects = ASPECTS, resolutions = if (light) BASIC_RESOLUTIONS else ALL_RESOLUTIONS,
                supportsFirstFrame = true, firstFrameKeepsAspect = true, supportsAudio = true,
            )
            "seedance-2-0" in id -> VideoCapabilities(
                durations = 4..15, aspects = ASPECTS, resolutions = if (light) BASIC_RESOLUTIONS else ALL_RESOLUTIONS,
                supportsFirstFrame = true, firstFrameKeepsAspect = false, supportsAudio = true,
            )
            else -> COMMON
        }
    }

    override fun create(endpoint: VideoEndpoint): VideoModel = ArkSeedanceModel(
        endpoint.baseUrl, endpoint.apiKey, endpoint.model, capabilities(endpoint.model), endpoint.http, endpoint.headers,
    )
}
