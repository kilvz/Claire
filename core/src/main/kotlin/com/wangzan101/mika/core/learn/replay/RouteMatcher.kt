package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.model.decision.DecisionModel
import com.wangzan101.mika.core.model.decision.DecisionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The chosen route and the slot values from the current utterance (in the order of `{1}`, `{2}`). */
data class RouteChoice(val route: Route, val slots: List<String>)

/**
 * Finds a previously successful route at the start of a task: first take candidates by app and literal similarity,
 * then use a single model call (skipped when there are no candidates) to pick one and extract the slot values.
 * Code then verifies once more: the number of slot values must be right, and each of them must be a verbatim segment of
 * the current utterance. [store] is also used by the loop to update route counters.
 */
class RouteMatcher(
    private val model: ChatModel,
    val store: RouteStore,
    private val knowledge: KnowledgeStore?,
    private val decision: DecisionModel? = null,
    private val onDecision: (String) -> Unit = {},
) {

    fun candidates(goal: String, limit: Int = 5): List<Route> {
        val apps = knowledge?.matchTask(goal, Int.MAX_VALUE)?.flatMap { it.packages }?.toSet().orEmpty()
        val g = bigrams(goal)
        return store.all().filter { !it.disabled }
            .map { it to jaccard(g, bigrams(SLOT.replace(it.template, "\u0000"))) }
            .filter { (r, sim) -> sim >= MIN_SIMILARITY || r.packages.any(apps::contains) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun choose(goal: String): RouteChoice? {
        val list = candidates(goal)
        if (list.isEmpty()) return null
        if (decision != null) {
            // only routes whose parameters can be uniquely determined from the utterance are handed to the decision
            // model; it is not used to generate text.
            // Ambiguous templates, no match, service failure or low confidence all keep the original model extraction
            // and the original strict validation.
            val choices = list.mapNotNull { route -> RouteSlots.extract(route, goal)?.let { validated(route, it, goal) } }
            if (choices.isNotEmpty()) {
                val routesById = choices.mapIndexed { i, c -> "route_$i" to c }.toMap()
                val options = routesById.mapValues { it.value.route.template } +
                    ("skip" to "Do not reuse a route; let the main model plan this request.")
                val picked = try {
                    withTimeoutOrNull(10_000L) {
                        decision.choose(buildJsonObject {
                            put("currentRequest", goal)
                            put("routes", buildJsonObject {
                                routesById.forEach { (id, c) -> put(id, buildJsonObject {
                                    put("template", c.route.template)
                                    put("boundParameters", JsonArray(c.slots.map(::JsonPrimitive)))
                                    put("tools", JsonArray(c.route.steps.map { JsonPrimitive(it.tool) }))
                                }) }
                            })
                        }, Prompts.role("route_decision"), options,
                            "Does at least one supplied route fulfill the entire currentRequest without adding an action, dropping a constraint, or changing its meaning?")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val confident = picked != null && picked.probability.isFinite() &&
                    picked.probability in DecisionPolicy.MIN_PROBABILITY..1.0
                if (confident && picked?.choice == "skip") {
                    onDecision("skipped")
                    return null
                }
                if (confident && picked != null && picked.choice in routesById &&
                    picked.applicable?.let { it.isFinite() && it in DecisionPolicy.MIN_APPLICABLE..1.0 } == true) {
                    onDecision("selected")
                    return routesById.getValue(picked.choice)
                }
                onDecision("fallback")
            }
        }
        val text = list.withIndex().joinToString("\n", "用户原话:$goal\n\n做法模板:\n") { (i, r) -> "${i + 1}. ${r.template}" }
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) { model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(text)), Author.Mika)), emptyList()) } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, list, goal)
    }

    companion object {
        const val MIN_SIMILARITY = 0.3
        const val TIMEOUT_MS = 20_000L
        private val SLOT = Regex("\\{\\d+\\}")
        private val SLOT_N = Regex("\\{(\\d+)\\}")

        private fun squash(s: String) = s.filterNot { it.isWhitespace() }

        private fun occurrences(s: String, v: String): Int {
            var n = 0; var i = s.indexOf(v)
            while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
            return n
        }

        val PROMPT: String get() = Prompts.role("route_match")

        internal fun parse(text: String, list: List<Route>, goal: String): RouteChoice? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject } catch (e: Exception) { null } ?: return null
            val pick = o["route"]
            if (pick == null || pick is JsonNull) return null
            val idx = (pick as? JsonPrimitive)?.intOrNull ?: return null
            val route = list.getOrNull(idx - 1) ?: return null
            val slots = (o["slots"] as? JsonArray ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
            return validated(route, slots, goal)
        }

        internal fun validated(route: Route, slots: List<String>, goal: String): RouteChoice? {
            if (slots.size != route.slots) return null
            // each slot value occurs in the utterance exactly once, and filling the slot values back into the template
            // must reproduce the utterance exactly: the slot boundaries line up with the template's fixed text
            // (e.g. "Ann" cannot be carved out of "Anna")
            if (slots.any { it.isBlank() || occurrences(goal, it) != 1 }) return null
            var unknown = false
            val filled = SLOT_N.replace(route.template) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { unknown = true; "" } }
            if (unknown || squash(filled) != squash(goal)) return null
            return RouteChoice(route, slots)
        }

        /** Character bigrams; a placeholder (\u0000) cuts off what precedes and follows it. */
        internal fun bigrams(s: String): Set<String> =
            s.lowercase().filterNot { it.isWhitespace() }.windowed(2).filter { '\u0000' !in it }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
    }
}
