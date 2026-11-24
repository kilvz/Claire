package com.wangzan101.mika.core.proactive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads model replies for the proactive features (triage, learning): only the trailing JSON value of the reply counts, since that is the model's conclusion.
 * Anything earlier (the thinking process, restated notifications) does not count even if it contains an answer-shaped object: notifications are written by third parties, and a restated "answer" could be an injection.
 * Pure functions; only standard JSON is accepted: unquoted values may only be numbers, true, false or null (kotlinx does not check this when reading the tree, so it is checked separately here),
 * and a sentence ending in `[n1, n2]` does not count as an array.
 */
internal object ReplyJson {
    /** Replies longer than this are not read (the whole batch fails). */
    const val MAX_CHARS = 32_000
    /** The most starting points tried when looking for the trailing value; beyond that it counts as unreadable. */
    const val MAX_STARTS = 200

    private val strict = Json

    sealed interface Ending {
        /** The reply ends with this JSON object or array (the outermost one). */
        data class Value(val element: JsonElement) : Ending
        /** The reply does not end with a JSON value (the last character is not `}` / `]`, or the trailing chunk does not read as a value). */
        data object None : Ending
        /** Too many starting points, not all tried: treated as unreadable. */
        data object GaveUp : Ending
    }

    /** Trims whitespace, then strips an opening code-fence line (``` or ```json) and a trailing ```, then trims again. The two ends are considered separately (when it thinks first and then gives a code block, there is no opening fence). */
    fun normalise(raw: String): String {
        var s = raw.trim()
        if (s.startsWith(FENCE)) {
            val nl = s.indexOf('\n')
            val tag = (if (nl < 0) s else s.substring(0, nl)).substring(FENCE.length).trim()
            if (tag.isEmpty() || tag.equals("json", ignoreCase = true)) s = if (nl < 0) "" else s.substring(nl + 1)
        }
        s = s.trimEnd()
        if (s.endsWith(FENCE)) s = s.substring(0, s.length - FENCE.length)
        return s.trim()
    }

    /**
     * The JSON value at the end of [text] (already [normalise]d): when it ends with `}` find the outermost object that ends exactly at the end, and when it ends with `]` find the outermost array.
     * Each `{` / `[` is tried from left to right, and the first one that "runs from there to the end as exactly such a value" is it; at most [MAX_STARTS] are tried.
     */
    fun ending(text: String): Ending {
        val open = when (text.lastOrNull()) {
            '}' -> '{'
            ']' -> '['
            else -> return Ending.None
        }
        var tried = 0
        var from = text.indexOf(open)
        while (from >= 0) {
            if (++tried > MAX_STARTS) return Ending.GaveUp
            val e = try { strict.parseToJsonElement(text.substring(from)) } catch (e: Exception) { null }
            if (e != null && (if (open == '{') e is JsonObject else e is JsonArray) && standard(e)) return Ending.Value(e)
            from = text.indexOf(open, from + 1)
        }
        return Ending.None
    }

    private const val FENCE = "```"

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** Every unquoted value is a number, true, false or null. Not recursive (so very deep nesting cannot blow the stack). */
    private fun standard(root: JsonElement): Boolean {
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            when (val e = stack.removeLast()) {
                is JsonObject -> e.values.forEach(stack::addLast)
                is JsonArray -> e.forEach(stack::addLast)
                is JsonPrimitive -> if (!e.isString && e.content != "true" && e.content != "false" && e.content != "null" &&
                    !NUMBER.matches(e.content)) return false
            }
        }
        return true
    }
}
