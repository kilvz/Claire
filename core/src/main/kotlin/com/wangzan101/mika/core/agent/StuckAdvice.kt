package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.safety.guard.GuardVerdict
import com.wangzan101.mika.core.safety.guard.Severity
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Bookkeeping for stuck warnings: how many "stuck" warnings the guards have reported; how many times each piece of text has been successfully typed at each place (App or website). */
internal data class StuckState(val warnings: Int = 0, val typed: Map<String, Int> = emptyMap())

/**
 * The facts one action (a single step, or one sub-step in a batch) contributes to stuck warnings: the guards' verdicts
 * (repeated action + target, screen unchanged after acting, repeated reads while the page stays unchanged), the plain
 * text successfully typed this time ([StuckAdvice.typedText], null when it is not a typing action or the field is a
 * password box), and where it happened.
 */
internal data class StuckInput(val verdicts: List<GuardVerdict>, val typed: String?, val place: String?, val progressed: Boolean = false)

/**
 * Advice given to the model when stuck (placed in the tool result's advice field): from the second stuck warning on,
 * advise giving up truthfully once everything has been searched; when the same text is typed again at the same place
 * (searching for the same few things over and over with different words, which the guards' window cannot see), say how
 * many times it has already been typed. Single steps and sub-steps in a batch use the same logic. Pure functions.
 */
internal object StuckAdvice {
    private val STUCK_CODES = setOf("no_progress", "no_new_state", "read_loop")

    /** The bookkeeping after this call and the advice to give (null when there is none). */
    fun next(s: StuckState, input: StuckInput): Pair<StuckState, String?> {
        val warnings = (if (input.progressed) 0 else s.warnings) + input.verdicts.count { it.severity == Severity.Warn && it.code in STUCK_CODES }
        var typed = if (input.progressed) emptyMap() else s.typed
        // Counted by "current place + text": searching the same term in a different App does not count as a repeat
        val again = input.typed?.let { t ->
            val key = "${input.place}\u0000$t"
            val n = (typed[key] ?: 0) + 1
            typed = typed + (key to n)
            if (n >= 2 && input.verdicts.any { it.code in STUCK_CODES })
                Feedback.format("repeated_input_advice", "text" to t, "times" to n, "answer_or_failure" to ANSWER_OR_FAILURE) else null
        }
        val advice = if (warnings >= 2 && input.verdicts.any { it.code in STUCK_CODES })
            Feedback.format("give_up_advice", "answer_or_failure" to ANSWER_OR_FAILURE) else again
        return StuckState(warnings, typed) to advice
    }

    /**
     * The text successfully typed this time (trimmed; empty does not count); null when it is not a typing action, did
     * not succeed, or the endpoint does not explicitly say "not a password box": the advice includes the original text,
     * and tool results go into the run log.
     */
    fun typedText(verb: Verb?, args: JsonObject, content: JsonObject?): String? {
        if (verb != Verb.TypeText || content?.get("ok") != JsonPrimitive(true) || content["secret"] != JsonPrimitive(false)) return null
        return (args["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
