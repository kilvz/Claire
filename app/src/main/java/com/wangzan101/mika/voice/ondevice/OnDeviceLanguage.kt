package com.wangzan101.mika.voice.ondevice

import com.wangzan101.mika.i18n.AppLanguage
import java.util.Locale

/** Mapping between speech-recognition language tags and offline model language codes, plus voice-pack recommendations per UI language. */
object OnDeviceLanguage {

    /** Some models (Whisper) still use legacy codes: iw for Hebrew and jw for Javanese. */
    private val modelAliases = mapOf("he" to "iw", "jv" to "jw")

    /** The language part of a tag (without script or region), with legacy codes replaced by current ones: `zh-Hans-CN` → `zh`, `in-ID` → `id`. */
    fun languagePart(speechTag: String?): String? {
        if (speechTag.isNullOrBlank()) return null
        return Locale.forLanguageTag(speechTag).language.takeIf { it.isNotEmpty() }?.let(AppLanguage::modernCode)
    }

    /** The language code to give the model; null when the model does not support it or no language was given, meaning the model decides. */
    fun pick(speechTag: String?, languages: List<String>): String? {
        val code = languagePart(speechTag) ?: return null
        if (code in languages) return code
        return modelAliases[code]?.takeIf { it in languages }
    }

    /** The voice pack recommended for a UI language; when following the system, [system] is resolved to a supported UI language (unsupported ones fall back to English). */
    fun recommended(catalog: VoicePackCatalog, app: AppLanguage, system: Locale = Locale.getDefault()): VoicePackEntry? {
        val tag = (if (app == AppLanguage.System) appLanguageFor(system) else app).tag ?: return null
        return catalog.packs.firstOrNull { tag in it.recommendedFor }
    }

    /**
     * The UI language for [system], matching [AppLanguage.resolved] when following the system, but without a Context or ICU:
     * when Chinese names no script, Taiwan, Hong Kong and Macao map to traditional and everything else to simplified.
     */
    internal fun appLanguageFor(system: Locale): AppLanguage =
        AppLanguage.fromTag(system.toLanguageTag()) { tag ->
            if (Locale.forLanguageTag(tag).country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
        }
}
