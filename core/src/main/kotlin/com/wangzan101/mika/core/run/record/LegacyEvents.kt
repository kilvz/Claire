package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.PlaceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Events that were renamed in old run records are converted to their current shape before reading (the conversion happens only on read; the file is not touched):
 * - screen_text (element text read from the screen and handed to the model) → observed: an observation that was read, whose place is the site or app at that time, with id 0;
 * - screen_saved (a saved screen frame) → media.
 */
internal object LegacyEvents {
    fun upgrade(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "screen_text" -> buildJsonObject {
                put("type", "observed")
                o["atMs"]?.let { put("atMs", it) }
                o["step"]?.let { put("step", it) }
                put("kind", ObsKind.Read.wire)
                val site = o.str("site")
                val app = o.str("app")
                // Back then web pages reported a site and phones reported an app
                (site?.let { PlaceKind.Site to it } ?: app?.let { PlaceKind.App to it })?.let { (k, n) ->
                    put("place", buildJsonObject { put("kind", k.wire); put("name", n) })
                    put("source", if (k == PlaceKind.Site) "web" else "phone")
                }
                put("text", (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().joinToString("\n"))
                o["inputs"]?.let { put("inputs", it) }
                o["focused"]?.let { put("focused", it) }
            }
            "screen_saved" -> buildJsonObject {
                put("type", "media")
                o["atMs"]?.let { put("atMs", it) }
                o["screenshotId"]?.let { put("mediaId", it) }
                o["step"]?.let { put("step", it) }
                o["app"]?.let { put("place", it) }
            }
            else -> o
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
