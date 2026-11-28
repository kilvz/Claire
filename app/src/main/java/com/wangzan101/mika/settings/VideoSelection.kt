package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.VideoBackend
import com.wangzan101.mika.core.model.VideoBackends
import com.wangzan101.mika.core.model.VideoCapabilities

/** A provider that can generate video, and the video protocol it declares. */
data class VideoProvider(val provider: Provider, val backend: VideoBackend)

/**
 * Whether the stored video choice matches the current providers.
 * When it does not match ([Stale]) the choice is not cleared (the user can keep using it after changing the address back), but no generation port is built for tasks, the settings page explains why, and a request is never sent to an address that does not declare this protocol.
 */
sealed interface VideoSelection {
    /** No provider selected yet. */
    data object None : VideoSelection

    /** A usable provider is selected, but no model has been entered yet. */
    data class NoModel(val target: VideoProvider) : VideoSelection

    data class Ready(val target: VideoProvider, val model: String, val capabilities: VideoCapabilities) : VideoSelection

    data class Stale(val reason: Reason) : VideoSelection

    enum class Reason {
        /** The selected provider was deleted. */
        ProviderRemoved,

        /** The provider still exists, but its address changed and it no longer declares the video protocol chosen originally (or this version does not recognize that protocol). */
        NoLongerCompatible,
    }
}

/** Filter providers by [VideoBackends] declarations and verify the stored choice; a pure function that does not touch storage. */
object VideoProviders {
    fun compatible(providers: List<Provider>): List<VideoProvider> =
        providers.mapNotNull { p -> VideoBackends.forBaseUrl(p.baseUrl)?.let { VideoProvider(p, it) } }

    /** Names of the provider templates that can generate video (chosen when adding a provider): the settings page prompts the user when there are none. */
    fun presetLabels(): List<String> =
        ProviderPresets.all.filter { VideoBackends.forBaseUrl(it.baseUrl) != null }.map { it.label }.distinct()

    fun resolve(choice: VideoChoice?, providers: List<Provider>): VideoSelection {
        choice ?: return VideoSelection.None
        val provider = providers.firstOrNull { it.id == choice.providerId }
            ?: return VideoSelection.Stale(VideoSelection.Reason.ProviderRemoved)
        val backend = VideoBackends.of(choice.backendId)?.takeIf { it.serves(provider.baseUrl) }
            ?: return VideoSelection.Stale(VideoSelection.Reason.NoLongerCompatible)
        val target = VideoProvider(provider, backend)
        val model = choice.model.trim()
        if (model.isEmpty()) return VideoSelection.NoModel(target)
        return VideoSelection.Ready(target, model, backend.capabilities(model))
    }
}
