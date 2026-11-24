package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * In recorded runs, each verification (verifier event) is paired with the final answer it checked (a reply with no tool call in new records, a finish call in old ones,
 * see [RunFacts.isFinalAnswer]), independent of their order: an ordinary step verifies first and records the reply afterwards, while the last round records the reply first and verifies after.
 *
 * - An event with a step: pairs with the answer of the same step.
 * - An old record without a step: the first model reply after it that is not yet paired is its answer (verify first, record the reply after); otherwise it pairs with the nearest earlier answer not yet paired.
 *
 * Returns (verifier index, answer index); verifications that cannot be paired are not included.
 */
internal object RecordedVerifications {
    fun pairs(events: List<RunEvent>): List<Pair<Int, Int>> {
        val used = mutableSetOf<Int>()
        fun finishAt(i: Int) = RunFacts.isFinalAnswer(events[i]) && i !in used
        val out = mutableListOf<Pair<Int, Int>>()
        events.forEachIndexed { v, e ->
            if (e !is RunEvent.Verifier) return@forEachIndexed
            val f = if (e.step != null) {
                events.indices.lastOrNull { finishAt(it) && (events[it] as RunEvent.ModelReply).step == e.step }
            } else {
                val next = (v + 1 until events.size).firstOrNull { events[it] is RunEvent.ModelReply }
                next?.takeIf(::finishAt) ?: (v - 1 downTo 0).firstOrNull(::finishAt)
            } ?: return@forEachIndexed
            used += f
            out += v to f
        }
        return out
    }

    /** The material available to verification [v] at the time: the events before it, minus the checked finish's own reply and result (an answer cannot be used to prove itself). */
    fun before(events: List<RunEvent>, v: Int, f: Int): List<RunEvent> {
        val step = (events[f] as RunEvent.ModelReply).step
        return events.subList(0, v).filterIndexed { i, e ->
            i != f && !(e is RunEvent.ToolResult && e.tool == "finish" && e.step == step)
        }
    }

    /** The text that could be compared against at verification time: all earlier screen-read text and every string in the tool results (see [before]). */
    fun sources(events: List<RunEvent>, v: Int, f: Int): List<String> = before(events, v, f).mapNotNull { b ->
        when (b) {
            is RunEvent.Observed -> b.text.takeIf { b.kind == "read" }
            is RunEvent.ToolResult -> runCatching { strings(Json.parseToJsonElement(b.resultJson)).joinToString("\n") }.getOrDefault(b.resultJson)
            else -> null
        }
    }.filter { it.isNotBlank() }

    /**
     * Rebuilds the material the runtime verifier got, from the evidence ledger: the fresh screen reread after finish was not recorded, so the last screen read stands in for it;
     * plus the entries the ledger ([RunFacts.ledger]) selects for the answer — the same selection the runtime [Verifier] uses.
     */
    fun ledgerSources(events: List<RunEvent>, v: Int, f: Int, goal: String, answer: String): List<String> {
        val seen = before(events, v, f)
        val fresh = seen.filterIsInstance<RunEvent.Observed>().lastOrNull { it.kind == "read" }?.text?.takeIf { it.isNotBlank() }
        return listOfNotNull(fresh) + RunFacts.ledger(seen, RECORDED_TOOLS).forAnswer(goal, answer).map { it.text }
    }

    /** The answer that was checked: when it ended the task, take the full text from task_ended (the reply may have been recorded truncated). */
    fun answer(events: List<RunEvent>, f: Int): String? {
        val r = events[f] as RunEvent.ModelReply
        val last = events.indexOfLast(RunFacts::isFinalAnswer) == f
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.takeIf { last && r.tool == null && it.outcome == "succeeded" }
        return ended?.answerOrReason ?: RunFacts.answerOf(r)
    }

    fun arg(json: String?, key: String): String? = runCatching {
        ((Json.parseToJsonElement(json.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    private fun strings(e: JsonElement): List<String> = when (e) {
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
        is JsonArray -> e.flatMap(::strings)
        is JsonObject -> e.values.flatMap(::strings)
    }
}
