package com.wangzan101.mika.core.trigger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Machine result of a condition check; neither an unparseable result nor an execution failure may be treated as the condition being unmet. */
sealed interface ConditionOutcome {
    /** [value] is the key value to compare against next time (e.g. "lowest ¥620"); null when the check did not supply one. */
    data class Checked(val met: Boolean, val evidence: String?, val value: String? = null) : ConditionOutcome
    data class Error(val reason: String) : ConditionOutcome

    companion object {
        const val VALUE_MAX = 80
        private const val EVIDENCE_MAX = 500

        fun parse(answer: String): ConditionOutcome {
            val raw = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return Error("the condition check returned no readable result")
            val met = (body["met"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return Error("the condition check result is missing the met status")
            val evidence = text(body["evidence"])?.take(EVIDENCE_MAX)
            val value = text(body["value"])?.let(::normalize)?.ifEmpty { null }
            return Checked(met, evidence, value)
        }

        /** Strings, numbers and booleans are taken as they are; JSON null, objects and arrays count as absent. */
        private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

        private val FORMAT = Regex("\\p{Cf}")
        // Android's regex (ICU) does not recognize inline flags such as (?U); use \p{Z} to cover full-width spaces, no-break spaces and line/paragraph separators
        private val BLANK = Regex("[\\s\\p{Cc}\\p{Z}]+")

        /** Observed values enter the plan page and the next check instruction: drop format characters, merge whitespace and control characters into one space, and truncate to [VALUE_MAX] characters (code points). */
        private fun normalize(raw: String): String {
            val flat = raw.replace(FORMAT, "").replace(BLANK, " ").trim()
            if (flat.codePointCount(0, flat.length) <= VALUE_MAX) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, VALUE_MAX)).trimEnd()
        }
    }
}
