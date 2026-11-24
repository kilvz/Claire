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
    /** On-device sherpa-onnx recognition; [model] is the catalog model id that picks the files. */
    data class Sherpa(val model: String) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /**
     * [SystemNoResponse]: the system reports the built-in recognition exists, but requests get no response at all (recorded, and cleared when the user selects it again).
     * [SystemFailed]: the built-in recognition could not start; retrying is safe, and this is not taken to mean the service is missing or the microphone is occupied.
     * [SherpaModelMissing]:on-device model was never downloaded; the catalog page can fetch it.
     * [SherpaFailed]:the on-device engine could not load or run; retrying is safe.
     */
    enum class Why {
        Off, NoSystemNoModel, SystemGone, SystemNoResponse, SystemFailed, ProviderGone,
        DoubaoNotConfigured, BailianNotConfigured, SherpaModelMissing, SherpaFailed,
    }
}

/** Matches the user's choice and the device's capabilities against the available providers to decide which engine is used this time. Pure function, touches no platform APIs. */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        is SpeechChoice.Doubao -> if (choice.resourceId in DoubaoProtocol.resources) SpeechEngineSpec.Doubao(choice.resourceId)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)
        is SpeechChoice.FunAsr -> SpeechEngineSpec.FunAsr(choice.url, choice.language)
        // Whether a key is stored is checked later by VoiceInput when it opens the session (keys are not part of the selection); this only goes by the selection
        is SpeechChoice.Bailian -> SpeechEngineSpec.Bailian(choice.model, choice.region, choice.workspace, choice.language)
        // When the built-in recognition does not respond, say so plainly and point to settings rather than silently switching to a billable provider model
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
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
