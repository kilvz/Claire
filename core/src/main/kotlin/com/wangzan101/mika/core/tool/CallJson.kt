package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.screen.observation.Place
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Call arguments; anything that is not a JSON object is treated as empty arguments (the tool layer does the rejecting). */
internal fun parseArgs(json: String): JsonObject = try {
    Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
} catch (e: SerializationException) {
    JsonObject(emptyMap())
}

/** The "where" reported in the result: the foreground app on the phone, the site on the web (also counted when reported inside the action's own screen); null when neither is reported. */
internal fun placeOf(content: JsonObject): Place? {
    val screen = content["screen"] as? JsonObject
    fun f(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
    (f(content, "currentApp") ?: f(screen, "currentApp"))?.let { return Place.app(it) }
    return (f(content, "currentSite") ?: f(screen, "currentSite"))?.let(Place::site)
}

/** Actions whose results carry a site (the currentSite from a screen read, the url from opening a web page or reading a page). */
private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

/**
 * Where the task is after this call; null when the result does not say (keep the previous place). "Where" distinguishes phone apps from websites: moving to a web page or to a different site both count as changing place.
 * Results of opening a web page or reading a page carry only a url: the site is derived from the url's host (the same algorithm as the currentSite reported by a screen read).
 */
internal fun placeAfter(verb: Verb?, content: JsonObject): Place? =
    placeOf(content) ?: if (verb in SITE_RESULT_VERBS && content["ok"] == JsonPrimitive(true))
        (content["url"] as? JsonPrimitive)?.contentOrNull?.let(UrlHost::of)?.let(Place::site) else null
