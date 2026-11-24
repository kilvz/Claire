package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ScreenText {
    /** One element per line: `@e3 button "Search" (500,50)`; the center point uses the same 0–999 coordinates as tap; when over budget, truncate and state the remaining count at the end. */
    fun render(s: ScreenSnapshot, maxChars: Int = 6000): String {
        val lines = s.elements.filter { it.meaningful }.map { line(it, s) }
        val out = StringBuilder()
        var shown = 0
        for (l in lines) {
            val remaining = lines.size - shown - 1
            // Reserve room for the footer so the output as a whole stays within budget
            val reserve = if (remaining > 0) footer(remaining).length + 1 else 0
            if (out.length + l.length + 1 + reserve > maxChars) break
            out.append(l).append('\n')
            shown++
        }
        val hidden = lines.size - shown
        if (hidden > 0) out.append(footer(hidden))
        return out.toString().trimEnd('\n').let { if (it.length > maxChars) it.take(maxChars) else it }
    }

    private fun footer(n: Int) = "…$n more nodes not shown; scroll or narrow the range"

    private fun line(e: UiElement, s: ScreenSnapshot): String {
        val name = if (e.name.isBlank()) "" else " \"${e.name.replace('\n', ' ')}\""
        return "${e.ref} ${e.role}$name${if (e.hint) " hint" else ""} (${normalize(e.centerX, s.width)},${normalize(e.centerY, s.height)})"
    }
}

object ScreenJson {
    /** Element array for observe; center is converted to the same 0–999 coordinates as tap (pixels are only used inside the port); bounds becomes size=[width,height] (same grid, saving the two redundant corner coordinates); returns the array and the number of elements truncated. */
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
