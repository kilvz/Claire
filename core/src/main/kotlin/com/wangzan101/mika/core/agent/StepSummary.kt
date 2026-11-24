package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Structured summary of folded steps: goal / confirmed facts / progress / next step. Each fold updates it on top of the
 * previous one ([next]).
 * All content is derived deterministically from the task's original words, tool results, and the model's own words,
 * without another model call: the summary is recomputed on every compaction, a model call would add a round trip, and
 * the same history could yield different summaries, making the request prefix unstable.
 * Every section is capped (facts [MAX_FACT_VALUES] per kind, progress [MAX_PROGRESS] lines, earlier outcomes
 * [MAX_EARLIER_KINDS] kinds, each item clipped to [CLIP] characters), so the summary never grows without bound no
 * matter how many steps there are.
 */
data class StepSummary(
    val goal: String,
    /** Fact kind → values (deduplicated, newest last). */
    val facts: Map<String, List<String>> = emptyMap(),
    val progress: List<String> = emptyList(),
    /** Earlier steps merged into counts: outcome → step count. */
    val earlier: Map<String, Int> = emptyMap(),
    /** The last stated plan from the model within the folded section. */
    val intent: String? = null,
) {
    /** Folds one round in: [line] is this step's progress line (ending in "→ outcome"), [results] are this round's successful tool results, [said] is what the model said in this round. */
    fun next(line: String, results: List<JsonObject>, said: String?): StepSummary {
        val f = facts.toMutableMap()
        for (r in results) for ((key, kind) in FACT_KEYS) {
            val v = (r[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            f[kind] = ((f[kind].orEmpty() - clip(v)) + clip(v)).takeLast(MAX_FACT_VALUES)
        }
        var p = progress + line
        var e = earlier
        while (p.size > MAX_PROGRESS) {
            val kind = p.first().substringAfterLast("→ ").trim()
            // New outcomes beyond the kind limit are all merged into OTHER, and OTHER itself takes a slot: reserve its place first, or a sixth kind would appear
            val outcome = when {
                kind in e -> kind
                e.size + 1 < MAX_EARLIER_KINDS -> kind
                OTHER in e && e.size < MAX_EARLIER_KINDS -> kind
                else -> OTHER
            }
            e = e + (outcome to (e[outcome] ?: 0) + 1)
            p = p.drop(1)
        }
        return StepSummary(goal, f, p, e, said?.let(::clip)?.takeIf { it.isNotEmpty() } ?: intent)
    }

    fun render(header: String): String = buildString {
        appendLine(header)
        appendLine("Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only.")
        appendLine("Goal: $goal")
        appendLine("Confirmed facts:")
        if (facts.isEmpty()) appendLine("- (none yet)")
        FACT_KEYS.values.forEach { kind -> facts[kind]?.let { appendLine("- $kind: ${it.joinToString(", ")}") } }
        appendLine("Progress:")
        if (earlier.isNotEmpty()) appendLine("- ${earlier.values.sum()} earlier steps: " + earlier.entries.joinToString(", ") { "${it.key} ${it.value}" })
        progress.forEach { appendLine("- $it") }
        append("Next: continue from the most recent steps kept below")
        intent?.let { append(" (last stated plan before folding: $it)") }
    }

    private companion object {
        /** Short fields in results that can be recorded as facts → fact kind. */
        val FACT_KEYS = linkedMapOf("currentApp" to "apps visited", "url" to "pages opened", "path" to "files read or written")
        const val MAX_FACT_VALUES = 4
        const val MAX_PROGRESS = 15
        const val MAX_EARLIER_KINDS = 5
        const val OTHER = "other"
        const val CLIP = 100

        fun clip(s: String): String {
            val one = s.replace('\n', ' ').trim()
            return if (one.length <= CLIP) one else one.take(CLIP) + "…"
        }
    }
}
