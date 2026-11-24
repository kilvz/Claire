package com.wangzan101.mika.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Language of the app UI: follow the system, or pick one of the options below. In resources English is the default (`values/`), Simplified Chinese is in `values-zh/`, Traditional Chinese is in `values-b+zh+Hant/`,
 * Arabic is in `values-ar/`, and every other language is in `values-<language>/` (Indonesian keeps Android's legacy code `values-in/`). Android selects resources by language and script,
 * so with "follow system" the system language is shown when it has a translation and English is shown otherwise; if a particular string is missing in a language, that string also falls back to English.
 *
 * From Android 13 on this is managed by the system "app language" (LocaleManager): the system remembers the choice, applies it to the whole process and recreates the UI,
 * and the "app language" entry in system settings and the switch here are the same value. Android 11 and 12 have no such feature, so this class stores the choice locally,
 * applies it to the UI Context through [wrap] and to the places that fetch text outside the UI through [resources], and recreates the current UI after a change.
 */
enum class AppLanguage(val tag: String?, val speechTag: String?) {
    System(null, null),
    SimplifiedChinese("zh-Hans", "zh-CN"),
    TraditionalChinese("zh-Hant", "zh-TW"),
    Arabic("ar", "ar-MA"),
    Persian("fa", "fa-IR"),
    English("en", "en-US"),
    Japanese("ja", "ja-JP"),
    Korean("ko", "ko-KR"),
    Spanish("es", "es-ES"),
    Portuguese("pt", "pt-BR"),
    Russian("ru", "ru-RU"),
    Indonesian("id", "id-ID"),
    Turkish("tr", "tr-TR");

    /** Language code (without script and region); null when following the system. */
    val language: String? get() = tag?.let { Locale.forLanguageTag(it).language.let(::modernCode) }

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"
        private const val KEY_PROMPTED = "language_prompted"

        /** Whether the first-run/after-update language selection prompt has already been shown to the user. */
        fun hasPrompted(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PROMPTED, false)

        /** Marks the prompt as shown or the language as set, so it is not offered proactively again. */
        fun markPrompted(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PROMPTED, true).apply()
        }

        /** Language selected on Android 12 and earlier; null means follow the system. Read once per process and updated when changed. */
        @Volatile
        private var legacyTag: String? = null
        @Volatile
        private var legacyLoaded = false

        /** Resources built for the selected language on Android 12 and earlier; they change together with the language. */
        @Volatile
        private var cached: Pair<String, Resources>? = null

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun current(context: Context): AppLanguage = fromTag(
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                legacy(context)
            },
        )

        /**
         * The language actually in effect: an explicit selection is returned as is; when following the system, the system's current language is mapped to one of the supported entries
         * (Chinese is split into Simplified and Traditional by script, unsupported ones fall back to English). For places that need a definite language tag while the user's explicit choice is unavailable,
         * such as pinned notes and language instructions written into the UI, this avoids treating "follow system" as Chinese.
         */
        fun resolved(context: Context): AppLanguage {
            val cur = current(context)
            if (cur != System) return cur
            return fromTag(Locale.getDefault().toLanguageTag())
        }

        /** Which entry a language tag maps to: see [fromTag]; when the tag does not specify a script, it is filled in with ICU's likely script. */
        fun fromTag(tag: String?): AppLanguage = fromTag(tag) { ULocale.addLikelySubtags(ULocale.forLanguageTag(it)).script }

        /**
         * Chinese is split by script: Hant, whether written explicitly or supplied by [likelyScript], means Traditional Chinese, and any other Chinese is Simplified Chinese;
         * other languages are matched to one entry by language code (region is ignored), and a language with no match falls back to English. A zh-Hans tag saved by the old "Chinese" option still maps to Simplified Chinese.
         */
        internal fun fromTag(tag: String?, likelyScript: (String) -> String): AppLanguage {
            if (tag.isNullOrEmpty()) return System
            val l = Locale.forLanguageTag(tag)
            val code = modernCode(l.language)
            if (code == "zh") return if (l.script.ifEmpty { likelyScript(tag) } == "Hant") TraditionalChinese else SimplifiedChinese
            return entries.firstOrNull { it.tag != null && it.language == code } ?: English
        }

        /** On Android, java.util.Locale still returns legacy codes for a few languages (Indonesian is in, Hebrew is iw…); normalize them to the current codes before comparing. */
        internal fun modernCode(language: String): String = when (language) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> language
        }

        /** Changes the language and recreates the UI. From Android 13 on this is handed to the system (which recreates it itself); before that the choice is stored locally and the UI hosting [context] is recreated. */
        fun set(context: Context, language: AppLanguage) {
            markPrompted(context)
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
            } else {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, language.tag).commit()
                legacyTag = language.tag
                legacyLoaded = true
                cached = null
                applyDefaultLocale(context)
                context.activity()?.recreate()
            }
            // Prompts sent to the model stay English, whatever the UI language is.
        }

        /** Called at app startup: on Android 12 and earlier, when a language has been selected, the process default Locale (formatting of dates and numbers) is switched to it as well. */
        fun applyDefaultLocale(context: Context) {
            if (systemManaged) return
            val locales = legacyLocales(context) ?: Resources.getSystem().configuration.locales
            Locale.setDefault(locales[0])
        }

        /** A Context with the app language applied: used in the Activity's attachBaseContext and wherever non-Activity UI is created. From Android 13 on it returns the base context unchanged. */
        fun wrap(base: Context): Context {
            val locales = legacyLocales(base) ?: return base
            return base.createConfigurationContext(Configuration().apply { setLocales(locales) })
        }

        /**
         * Resources for fetching text outside the UI: they follow the app language. From Android 13 on a separate copy is still built for the app language recorded by the system
         * instead of using the Application's resources directly: when the process was not launched normally by the system for the app (e.g. a test launch), those resources are still in the system language.
         */
        fun resources(app: Context): Resources {
            val tag = if (systemManaged) {
                app.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.toLanguageTags()
            } else {
                legacy(app)
            }
            if (tag == null) return app.resources
            cached?.takeIf { it.first == tag }?.let { return it.second }
            val r = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }).resources
            cached = tag to r
            return r
        }

        private fun legacyLocales(context: Context): LocaleList? =
            if (systemManaged) null else legacy(context)?.let(LocaleList::forLanguageTags)

        private fun legacy(context: Context): String? {
            if (!legacyLoaded) {
                legacyTag = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
                legacyLoaded = true
            }
            return legacyTag
        }

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
