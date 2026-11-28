package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ScreenJson {
    /** observe's element array; center becomes 0–999 like tap coordinates (pixels stay inside the port); bounds becomes size=[width,height] (same grid, saving two redundant corner coordinates); returns the array and the number of dropped elements. */
    fun elements(s: ScreenSnapshot, maxElements: Int): Pair<JsonArray, Int> {
        val all = s.elements.filter { it.meaningful }
        val arr = buildJsonArray {
            for (e in all.take(maxElements)) add(buildJsonObject {
                put("ref", e.ref)
                put("role", e.role)
                put("name", e.name)
                put("center", buildJsonObject { put("x", normalize(e.centerX, s.width)); put("y", normalize(e.centerY, s.height)) })
                if (e.bounds.size == 4) put("size", JsonArray(listOf(JsonPrimitive(e.bounds[2] - e.bounds[0]), JsonPrimitive(e.bounds[3] - e.bounds[1]))))
                e.resourceId?.let { put("resourceId", it) }
                if (e.hint) put("hint", true)
            })
        }
        return arr to (all.size - arr.size).coerceAtLeast(0)
    }
}
