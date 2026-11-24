package com.wangzan101.mika.cap.web.cdp

import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Which language web pages are requested in. When the user has picked an interface language in Claire, web pages request that one too (the Accept-Language request header,
 * and the engine makes navigator.language(s) follow it as well); when following the system nothing changes, and the engine sends the device's string.
 * It is set through `Emulation.setUserAgentOverride`'s acceptLanguage: the desktop version adds it to the desktop identity ([withLanguage]),
 * and the mobile version needs an override that copies the default mobile identity and only adds the language ([mobileOverride]), leaving UA and UA-CH unchanged.
 * JS Intl formatting is not changed (`Emulation.setLocaleOverride` is one per render process; tabs fight over it and one tab disconnecting reverts it), so it still follows the system.
 */
object PageLanguage {
    /**
     * The language list for a language tag (completed, e.g. en-Latn-US): with a region it is written as 'language-region,language',
     * and the script is not written (many sites only recognize 'language-region'); without a region only the language. No other languages are appended.
     * Only the list, no q values: the engine sets navigator.languages from this list and adds q values to the request-header items itself, so writing them would duplicate.
     * Android's Locale gives legacy codes for a few languages such as Indonesian (in), while sites recognize the current code (id), so it is converted before writing.
     */
    fun acceptLanguage(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = AppLanguage.modernCode(l.language.ifEmpty { return tag })
        return if (l.country.isEmpty()) lang else "$lang-${l.country},$lang"
    }

    /** Add the language to a UA override; returns it unchanged when [acceptLanguage] is null (following the system). */
    fun withLanguage(override: JsonObject, acceptLanguage: String?): JsonObject =
        if (acceptLanguage == null) override else JsonObject(override + ("acceptLanguage" to JsonPrimitive(acceptLanguage)))

    /**
     * Parameters for the mobile version's `Emulation.setUserAgentOverride`. When following the system it is an empty UA, i.e. revert the override and go back to the default identity in WebView settings;
     * when a language is picked it copies the default identity: [defaultUa] is WebView's default UA, and [metadata] is the default UA-CH fetched before changing the settings (in CDP form).
     * When the default UA-CH cannot be fetched from the system (old WebView) only the UA is sent, in which case the UA-CH the page reads may be incomplete.
     */
    fun mobileOverride(defaultUa: String, metadata: JsonObject?, acceptLanguage: String?): JsonObject = buildJsonObject {
        if (acceptLanguage == null || defaultUa.isEmpty()) {
            put("userAgent", "")
            return@buildJsonObject
        }
        put("userAgent", defaultUa)
        put("acceptLanguage", acceptLanguage)
        metadata?.let { put("userAgentMetadata", it) }
    }
}
