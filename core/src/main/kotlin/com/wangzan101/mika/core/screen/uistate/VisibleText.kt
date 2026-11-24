package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** OCR only fills in visible text the accessibility tree lacks, and never mistakes text in an image for a clickable control. */
internal object VisibleText {
    /** Drop lines whose confidence ML Kit provided and which falls below this value; lines without one are kept. */
    private const val MIN_CONFIDENCE = 0.4f
    /** On a predominantly Chinese screen, only fragments with no Chinese and at most this many alphanumerics can be misreads. */
    private const val FRAGMENT = 4

    /**
     * Drops misread lines: low confidence; no letter or digit at all; more symbols than characters; and fragments without Chinese on a predominantly Chinese screen
     * (a single letter, or up to [FRAGMENT] characters mixing letters and digits with symbols, is most likely an avatar or icon read as text). Pure numbers (times, quantities) and complete short words are kept.
     */
    fun legible(lines: List<VisibleTextLine>): List<VisibleTextLine> {
        val letters = lines.flatMap { line -> line.text.filter(Char::isLetter).toList() }
        val cjkScreen = letters.isNotEmpty() && letters.count(::cjk) * 2 >= letters.size
        return lines.filterNot { noise(it, cjkScreen) }
    }

    private fun noise(line: VisibleTextLine, cjkScreen: Boolean): Boolean {
        if (line.confidence != null && line.confidence < MIN_CONFIDENCE) return true
        val text = line.text.filterNot(Char::isWhitespace)
        val word = text.filter(Char::isLetterOrDigit)
        if (word.isEmpty() || text.length - word.length > word.length) return true
        if (!cjkScreen || word.any(::cjk) || word.all(Char::isDigit)) return false
        return word.length == 1 || word.length <= FRAGMENT && (word.any(Char::isDigit) || text.length > word.length)
    }

    private fun cjk(c: Char) = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    fun missing(screen: ScreenSnapshot, recognized: List<VisibleTextLine>): List<VisibleTextLine> {
        val secret = screen.elements.filter { it.secret }.map { it.bounds } +
            screen.windows.flatMap { window ->
                val out = mutableListOf<List<Int>>()
                fun visit(node: UiTreeNode) {
                    if (node.secret) out += node.bounds
                    node.children.forEach(::visit)
                }
                window.nodes.forEach(::visit)
                out
            }
        val known = screen.elements.filter { !it.secret && it.name.isNotBlank() }
        val unique = mutableListOf<VisibleTextLine>()
        for (line in recognized.sortedWith(compareBy({ it.bounds.getOrElse(1) { 0 } }, { it.bounds.getOrElse(0) { 0 } }))) {
            val value = key(line.text)
            if (value.isEmpty() || line.bounds.size != 4 || line.bounds[2] <= line.bounds[0] || line.bounds[3] <= line.bounds[1]) continue
            if (secret.any { overlap(line.bounds, it) >= 0.3 }) continue
            if (known.any { overlap(line.bounds, it.bounds) >= 0.5 && same(value, key(it.name)) }) continue
            val duplicate = unique.indexOfFirst { overlap(line.bounds, it.bounds) >= 0.65 && same(value, key(it.text)) }
            if (duplicate >= 0) {
                if (value.length > key(unique[duplicate].text).length) unique[duplicate] = line
            } else unique += line
        }
        return unique
    }

    fun json(lines: List<VisibleTextLine>): JsonArray = buildJsonArray {
        for (line in lines) add(buildJsonObject {
            put("text", line.text)
            put("bounds", JsonArray(line.bounds.map(::JsonPrimitive)))
            // Same 0–999 coordinate system as tap's x/y, so the model does not have to convert
            put("center", JsonArray(listOf(JsonPrimitive((line.bounds[0] + line.bounds[2]) / 2), JsonPrimitive((line.bounds[1] + line.bounds[3]) / 2))))
            put("source", "ocr")
        })
    }

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun same(a: String, b: String) = a == b || a.length >= 3 && b.contains(a) || b.length >= 3 && a.contains(b)

    /** Ratio of the intersection to the OCR line's own area; a large container node cannot mask line text by matching areas instead. */
    private fun overlap(a: List<Int>, b: List<Int>): Double {
        if (b.size != 4) return 0.0
        val width = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0)
        val height = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0)
        val area = (a[2] - a[0]).toLong() * (a[3] - a[1])
        return if (area > 0) width.toLong() * height / area.toDouble() else 0.0
    }
}
