package com.wangzan101.mika.core.model

import okhttp3.OkHttpClient

/**
 * Connection information needed to build a video model: address, key, model name and request headers all come from the provider the user chose.
 * The key is passed to the adapter only here; it never goes into logs or error text.
 */
class VideoEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val http: OkHttpClient,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Declaration of one video generation API (protocol): which provider addresses speak it, what each model can do, and how to build an adapter.
 *
 * Video generation is a provider capability that not every provider has: the settings page lists only providers recognized by [serves], and tasks build a port only for recognized ones.
 * To add a provider: write a [VideoModel] adapter plus a [VideoBackend] and register it in [VideoBackends.all];
 * the tool (`video_generate`), the app-side generation port and tasks do not need changes. The provider must be mainstream and trustworthy (see the project rules).
 */
interface VideoBackend {
    /** Name stored in settings; only append, never rename or remove. */
    val id: String

    /** Example for the model field on the settings page (a model ID from the official documentation). */
    val modelHint: String

    /** Whether this provider Base URL speaks this protocol. Only addresses stated in the official documentation are recognized; custom addresses and relays are never recognized. */
    fun serves(baseUrl: String): Boolean

    /** What [model] can do, declared per model from the official documentation; unrecognized models (such as inference endpoint IDs) get the subset that every model supports. */
    fun capabilities(model: String): VideoCapabilities

    fun create(endpoint: VideoEndpoint): VideoModel
}

/** Video generation protocols that are integrated. */
object VideoBackends {
    val all: List<VideoBackend> = listOf(ArkSeedance)

    fun of(id: String?): VideoBackend? = all.firstOrNull { it.id == id }

    /** Which protocol this provider address can use to generate video; null when none is recognized. */
    fun forBaseUrl(baseUrl: String): VideoBackend? = all.firstOrNull { it.serves(baseUrl) }
}
