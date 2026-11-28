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
    /** 本机离线识别,用已安装的语音包 [packId]。 */
    data class OnDevice(val packId: String) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /**
     * [SystemNoResponse]:手机自带识别系统说有,但请求发过去一直没有回应(已记下,用户重新选它时清掉)。
     * [SystemFailed]:手机自带识别未能启动,可以重试;不据此认定服务不存在或麦克风被占用。
     * [VoicePackMissing]:选中的本机语音包没装(比如从备份恢复后),下载后不用重新选就能用。
     * [OnDeviceUnsupported]:这台设备跑不了本机识别(处理器架构不支持),或者是通话这种用不了它的场合。
     */
    enum class Why {
        Off, NoSystemNoModel, SystemGone, SystemNoResponse, SystemFailed, ProviderGone, DoubaoNotConfigured, BailianNotConfigured,
        VoicePackMissing, OnDeviceUnsupported,
    }
}

/** Matches the user's choice and the device's capabilities against the available providers to decide which engine is used this time. Pure function, touches no platform APIs. */
object SpeechResolver {
    /**
     * [autoPack] 是「自动」可以退到的已安装语音包(见 [com.wangzan101.mika.voice.ondevice.OnDeviceAuto]);
     * 只有手机自带识别不能用时才用它,其他选择不看它。
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
        // 自带识别能用就用它(能边说边出字);不能用时退到装好的语音包;都没有就照实说、指去设置,
        // 不悄悄改用要花钱的服务商模型
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
