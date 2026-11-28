package com.wangzan101.mika.voice.ui

import android.content.Context
import android.content.res.Configuration
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.tr
import java.util.Locale

/** The UI has a translation for this language: the audition sentence can be spoken in the voice's own language. */
internal fun hasUiTranslation(locale: Locale): Boolean {
    val code = AppLanguage.modernCode(locale.language)
    return code.isNotEmpty() && AppLanguage.entries.any { it.language == code }
}

/**
 * A sentence for audition/testing, from the string resource voice_try_sample. When the voice has a language and the UI has a translation for it, use that language,
 * otherwise (no language given, or the UI has no such translation) use the current UI language; never hard-code a sentence for any language in code.
 */
internal fun voiceSample(context: Context, voiceLocale: Locale? = null): String {
    val locale = voiceLocale?.takeIf(::hasUiTranslation) ?: return tr(R.string.voice_try_sample)
    val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
    return context.createConfigurationContext(config).getString(R.string.voice_try_sample)
}
