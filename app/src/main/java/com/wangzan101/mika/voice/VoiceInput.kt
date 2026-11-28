package com.wangzan101.mika.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.wangzan101.mika.R
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.clientHeaders
import com.wangzan101.mika.settings.providerKey
import com.wangzan101.mika.voice.model.SttProtocol
import com.wangzan101.mika.voice.model.SttSelection
import com.wangzan101.mika.voice.model.SttSelectionController
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import com.wangzan101.mika.voice.model.VoiceModelError
import com.wangzan101.mika.voice.model.adapters.SttHooks
import com.wangzan101.mika.voice.ondevice.OnDeviceAuto
import com.wangzan101.mika.voice.ondevice.OnDeviceCatalog
import kotlinx.coroutines.CancellationException

/** Where the UI (input bar, floating capsule) gets a voice session; in the app it is [VoiceInput], and UI tests substitute a fake. */
interface VoiceSource {
    fun spec(): SpeechEngineSpec
    fun start(language: String? = null): SpeechSession
}

/**
 * The voice input entry point: the conversation input box and the floating capsule get sessions only through here.
 * Before migration the old choice is used; after migration the same catalog resolves the explicit model, and the implementation is no longer chosen by provider or model name.
 */
class VoiceInput(
    context: Context,
    private val settings: SpeechSettings,
    private val providers: ProviderStore,
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, providers.clientHeaders(p)) },
    private val voiceModels: VoiceModelEnvironment = VoiceModelEnvironment.get(context),
    private val systemCapability: () -> SystemCapability = {
        SystemSpeech.capability(context, settings.systemSilent.value)
    },
    private val systemCompatibility: () -> Boolean = { SystemSpeech.serviceCompatibility() },
    /** 「自动」在手机自带识别不能用时退到哪个已安装的语音包(按识别语言挑);没有为 null。 */
    private val autoPack: (speechTag: String?) -> String? = { tag -> OnDeviceAuto.installedPack(voiceModels, tag) },
    /** 没指定识别语言时用的语言:跟 App 界面语言。 */
    private val appSpeechTag: () -> String = { SystemSpeech.speechTag(AppLanguage.current(context)) },
) : VoiceSource {
    private val context = context.applicationContext

    /** The UI asks first whether it can be used and which one is used (the small label at the top of the card). */
    override fun spec(): SpeechEngineSpec {
        if (!voiceModels.stt.migrated) return legacySpec()
        return try {
            migratedSpec(appSpeechTag)
        } catch (error: VoiceModelError) {
            SpeechEngineSpec.Unavailable(onDeviceWhy(error) ?: SpeechEngineSpec.Why.ProviderGone)
        }
    }

    /**
     * 本机语音包用不了时界面该说的原因:没装是「需要下载」(下好后不用重新选),设备跑不了照实说;
     * 别的错误返回 null,照原来的处理。
     */
    private fun onDeviceWhy(error: Throwable): SpeechEngineSpec.Why? = when {
        error is VoiceModelError.PackMissing -> SpeechEngineSpec.Why.VoicePackMissing
        error is VoiceModelError.Unsupported && error.protocol == SttProtocol.OnDevice.name ->
            SpeechEngineSpec.Why.OnDeviceUnsupported
        else -> null
    }

    private fun legacySpec(): SpeechEngineSpec =
        SpeechResolver.resolve(settings.choice.value, systemCapability(), providers.providers.value)

    /** [speechTag] 只在「自动」要挑语音包时才取(取界面语言要碰系统)。 */
    private fun migratedSpec(speechTag: () -> String?): SpeechEngineSpec {
        if (voiceModels.stt.catalog.state.value.error != null) {
            throw VoiceModelError.IncompleteConfig("Voice catalog is not readable")
        }
        return when (val selection = voiceModels.stt.current()) {
            SttSelection.Auto -> {
                val system = systemCapability()
                // 自带识别能用时不去看语音包:不让「自动」悄悄换成离线模型
                val pack = if (system.usable) null else autoPack(speechTag())
                SpeechResolver.resolve(SpeechChoice.Auto, system, emptyList(), pack)
            }
            SttSelection.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
            is SttSelection.Model -> if (selection.ref == SttSelectionController.SYSTEM_STT_REF) {
                SpeechResolver.resolve(SpeechChoice.System, systemCapability(), emptyList())
            } else {
                voiceModels.stt.spec(selection.ref)
            }
        }
    }

    /**
     * Open a session, called when the button is pressed (on the main thread). [language] is the recognition language
     * (BCP-47); null follows the App UI language (self-hosted streaming recognition ignores it and uses the language set
     * on the service). Without recording permission or when the engine is unusable, returns a session that only emits
     * Failed, which the UI handles the same way.
     */
    override fun start(language: String?): SpeechSession =
        if (voiceModels.stt.migrated) startMigrated(language) else startLegacy(language)

    private fun startMigrated(language: String?): SpeechSession {
        val tag by lazy { language ?: appSpeechTag() }
        val spec = try {
            migratedSpec { tag }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onDeviceWhy(error)?.let { return FailedSession(SpeechFailure.Unavailable(it)) }
            return FailedSession(SpeechFailure.Provider(VoiceModelError.from(error)))
        }
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val selection = voiceModels.stt.current()
        val ref = when (selection) {
            // 自动退到语音包时用 spec 挑好的那个(同一个识别语言挑的),否则是手机自带识别
            SttSelection.Auto -> (spec as? SpeechEngineSpec.OnDevice)?.let { OnDeviceCatalog.ref(it.packId) }
                ?: SttSelectionController.SYSTEM_STT_REF
            SttSelection.Off -> return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.Off))
            is SttSelection.Model -> selection.ref
        }
        val hooks = if (ref == SttSelectionController.SYSTEM_STT_REF) {
            SttHooks(
                onNoResponse = settings::noteSystemSilent,
                onAnswered = settings::noteSystemAnswered,
                systemCapability = systemCapability(),
                rememberedService = settings.systemService,
                compatibility = systemCompatibility(),
                serviceWithoutMic = { named, onDevice -> SpeechServiceMic.missing(context, named, onDevice) },
            )
        } else {
            SttHooks()
        }
        return try {
            // 自动不用备援链:它自己在自带识别和语音包之间挑
            val backups = if (selection == SttSelection.Auto) emptyList() else voiceModels.stt.backupsFor(ref)
            voiceModels.stt.start(context, ref, tag, hooks, backups)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FailedSession(SpeechFailure.Provider(VoiceModelError.from(error)))
        }
    }

    private fun startLegacy(language: String?): SpeechSession {
        val spec = legacySpec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        return when (spec) {
            is SpeechEngineSpec.Doubao -> {
                val credentials = settings.doubaoCredentials()
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured))
                DoubaoSpeech(PcmRecorder(streaming = true), { DoubaoTransport().connect(credentials, spec.resourceId) })
            }
            is SpeechEngineSpec.FunAsr -> LiveSpeech(PcmRecorder(streaming = true, unbounded = true), FunAsrProtocol(spec.language), { LiveAsrTransport().connect(spec.url) })
            is SpeechEngineSpec.Bailian -> {
                val key = settings.bailianKey(spec.region)
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.BailianNotConfigured))
                LiveSpeech(PcmRecorder(streaming = true, unbounded = true), BailianProtocol(spec.model, spec.language), {
                    LiveAsrTransport().connect(spec.region.endpoint(spec.workspace), mapOf("Authorization" to "Bearer $key"))
                })
            }
            is SpeechEngineSpec.System -> {
                val compatibility = SystemSpeech.serviceCompatibility()
                val capability = systemCapability()
                SystemSpeechSession(
                    context, spec.onDevice, tag,
                    onNoResponse = settings::noteSystemSilent, onAnswered = settings::noteSystemAnswered,
                    attempts = SystemSpeech.attempts(
                        SystemSpeech.services(context), settings.systemService, compatibility,
                        hasSystemEntry = capability.defaultService != null || capability.onDevice,
                    ),
                    compatibility = compatibility,
                    serviceWithoutMic = { named, onDevice -> SpeechServiceMic.missing(context, named, onDevice) },
                )
            }
            is SpeechEngineSpec.Provider -> {
                val iso = SystemSpeech.isoCode(tag)
                ProviderSpeech(PcmRecorder(), { wav ->
                    val provider = providers.provider(spec.provider.id)
                        ?: throw ModelError.BadResponse(tr(R.string.voice_unavailable_provider_gone))
                    transcriber(provider).transcribe(provider, providers.providerKey(provider.id), spec.model, spec.method, wav, iso)
                })
            }
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
            // Vendor and on-device recognition exist only in the catalog path; the legacy path never resolves to them.
            is SpeechEngineSpec.Vendor, is SpeechEngineSpec.OnDevice ->
                FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone))
        }
    }
}
