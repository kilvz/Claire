package com.wangzan101.mika.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads the JSON the model is supposed to return (verification verdicts, route matches, memory changes, …). Models vary:
 * code fences, surrounding sentences, a preamble, an object followed by a braced explanation, keys spelled differently —
 * all tolerated here; if it still cannot be read, ask again explicitly for JSON only ([completeParsed]).
 */
object ModelJson {
    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }

    /** The first brace-balanced fragment in the reply that parses as a JSON object (braces inside strings do not count); null if none. */
    fun firstObject(text: String): JsonObject? {
        var from = text.indexOf('{')
        while (from >= 0) {
            val end = balancedEnd(text, from)
            if (end > from) {
                val obj = try { lenient.parseToJsonElement(text.substring(from, end + 1)) as? JsonObject } catch (e: Exception) { null }
                if (obj != null) return obj
            }
            from = text.indexOf('{', from + 1)
        }
        return null
    }

    /** Position of the } matching the { at [start]; -1 if the braces do not balance. */
    private fun balancedEnd(s: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    /** Compared after stripping case, underscores, hyphens and spaces (verdict, Verdict and final_verdict count as the same). */
    private fun key(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    /** Looks up each of [names] in order (spelling variants accepted); null if none match. */
    fun JsonObject.field(vararg names: String): JsonElement? {
        for (n in names) {
            this[n]?.let { return it }
            val want = key(n)
            entries.firstOrNull { key(it.key) == want }?.let { return it.value }
        }
        return null
    }

    /** The sentence appended when the first attempt cannot be parsed. */
    const val JSON_ONLY = "The previous reply could not be read as JSON. Output only one JSON object, with no code fence and no other text, in the format from the system prompt."

    /**
     * Calls the model once and parses the reply with [parse]; if that fails, appends that reply plus [JSON_ONLY] and asks again.
     * Null if both attempts fail. Model errors (exceptions) are thrown to the caller as usual.
     */
    suspend fun <T : Any> completeParsed(model: ChatModel, system: String, messages: List<Message>, parse: (String) -> T?): T? {
        val first = model.complete(system, messages, emptyList())
        val firstText = first.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        parse(firstText)?.let { return it }
        val retry = messages + Message(Role.Assistant, listOf(Part.Text(firstText.ifBlank { "(empty)" })), Author.Mika) +
            Message(Role.User, listOf(Part.Text(JSON_ONLY)), Author.Mika)
        val second = model.complete(system, retry, emptyList())
        return parse(second.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
    }
}
