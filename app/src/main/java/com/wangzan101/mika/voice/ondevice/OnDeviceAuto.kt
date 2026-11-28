package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.voice.model.VoiceModelEnvironment
import java.util.Locale

/**
 * Which voice pack "Auto" uses when the phone's built-in recognition is unusable: the pack recommended for this
 * recognition language if it is installed, otherwise the first installed pack (the model decides when it does not
 * support the language), and null when none is installed. Only asked when the built-in recognition cannot be used
 * (see [com.wangzan101.mika.voice.SpeechResolver]).
 */
object OnDeviceAuto {

    /**
     * [speechTag] is the recognition language (BCP-47), converted to a UI language for the recommendation; when absent, [app] is used (the system language when following the system).
     * [installed] decides whether a pack can be used directly.
     */
    fun pick(
        speechTag: String?,
        app: AppLanguage,
        catalog: VoicePackCatalog,
        installed: (VoicePackEntry) -> Boolean,
    ): VoicePackEntry? {
        val language = speechTag?.takeIf { it.isNotBlank() }?.let { OnDeviceLanguage.appLanguageFor(Locale.forLanguageTag(it)) } ?: app
        val recommended = OnDeviceLanguage.recommended(catalog, language)
        if (recommended != null && installed(recommended)) return recommended
        return catalog.packs.firstOrNull(installed)
    }

    /**
     * What "installed" means in the app: the files are present (an older version still counts and remains usable), this device can run its engine, and it is registered in the voice catalog.
     * Only the directory and file sizes are checked, not checksums, because the UI calls this for every card label. Null when the voice-pack environment is absent (tests) or the manifest cannot be read.
     */
    fun installedPack(environment: VoiceModelEnvironment, speechTag: String?): String? {
        val store = environment.voicePacks ?: return null
        val backends = environment.voicePackBackends ?: return null
        val catalog = try {
            environment.voicePackCatalog?.invoke()
        } catch (_: Exception) {
            null
        } ?: return null
        val registered = environment.catalog.state.value.models
            .filter { it.serviceId == OnDeviceCatalog.SERVICE_ID }
            .mapTo(HashSet()) { it.id }
        return pick(speechTag, AppLanguage.System, catalog) { entry ->
            entry.id in registered &&
                backends.find(entry.backend)?.abiSupported() == true &&
                store.installedAny(entry) != null
        }?.id
    }
}
