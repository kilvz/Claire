package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider

/**
 * Capabilities of the device's built-in recognition: whether the system says it exists ([available]), whether recognition happens on this device (no network),
 * and whether it answers us when tried on this phone ([responsive]; recorded as false when the system says it exists but a request produces no callback at all).
 * [defaultService] is the system's default recognition component when it can be read; an installed service that is not
 * the default is still usable by name, which is why [available] also covers registered services.
 */
data class SystemCapability(
    val available: Boolean,
    val onDevice: Boolean,
    val responsive: Boolean = true,
    val defaultService: String? = null,
) {
    /** Actually usable for dictation: the system has it and it is not recorded as unresponsive. */
    val usable get() = available && responsive
}

/** The recognition engine actually used this time; when the selected thing is gone, [Unavailable] is returned with the reason for the UI to show. */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val provider: com.wangzan101.mika.settings.Provider, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Doubao(val resourceId: String) : SpeechEngineSpec
    data class FunAsr(val url: String, val language: AsrLanguage = AsrLanguage.Auto) : SpeechEngineSpec
    data class Bailian(val model: String, val region: BailianRegion, val workspace: String = "", val language: AsrLanguage = AsrLanguage.Auto) : SpeechEngineSpec
    /** A vendor's own recognition API (upload per utterance). */
    data class Vendor(val model: String) : SpeechEngineSpec
    /** On-device offline recognition with the installed voice pack [packId]. */
    data class OnDevice(val packId: String) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /**
     * [SystemNoResponse]: the built-in recognition says it is available but never answers a request (remembered, and cleared when the user selects it again).
     * [SystemFailed]: built-in recognition could not start and can be retried; this alone does not mean the service is absent or the microphone is busy.
     * [VoicePackMissing]: the selected on-device pack is not installed (for example after a backup restore); once downloaded it works without re-selecting.
     * [OnDeviceUnsupported]: this device cannot run on-device recognition (unsupported CPU architecture), or this is a case such as calls where it cannot be used.
     */
    enum class Why {
        Off, NoSystemNoModel, SystemGone, SystemNoResponse, SystemFailed, ProviderGone, DoubaoNotConfigured, BailianNotConfigured,
        VoicePackMissing, OnDeviceUnsupported,
    }
}

/** Matches the user's choice and the device's capabilities against the available providers to decide which engine is used this time. Pure function, touches no platform APIs. */
object SpeechResolver {
    /**
     * [autoPack] is the installed pack "Auto" can fall back to (see [com.wangzan101.mika.voice.ondevice.OnDeviceAuto]);
     * it is used only when built-in recognition is unusable, and other choices ignore it.
     */
    fun resolve(
        choice: SpeechChoice,
        system: SystemCapability,
        providers: List<Provider>,
        autoPack: String? = null,
    ): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        is SpeechChoice.Doubao -> if (choice.resourceId in DoubaoProtocol.resources) SpeechEngineSpec.Doubao(choice.resourceId)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)
        is SpeechChoice.FunAsr -> SpeechEngineSpec.FunAsr(choice.url, choice.language)
        // Whether a key is stored is checked later by VoiceInput when it opens the session (keys are not part of the selection); this only goes by the selection
        is SpeechChoice.Bailian -> SpeechEngineSpec.Bailian(choice.model, choice.region, choice.workspace, choice.language)
        // Use built-in recognition when usable (it produces text while speaking); fall back to an installed pack when not; otherwise say so plainly and point to settings
        // rather than silently switching to a paid provider model
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            autoPack != null -> SpeechEngineSpec.OnDevice(autoPack)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        }
        SpeechChoice.System -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        }
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.id == choice.providerId }
            when {
                p == null -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
                else -> SpeechEngineSpec.Provider(p, choice.model, choice.method)
            }
        }
    }
}
