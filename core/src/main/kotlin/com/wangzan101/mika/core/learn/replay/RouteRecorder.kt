package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.net.jsonObjectOrNull
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/**
 * Records a successful run as a route. It only looks at [RunEvent.ActionTarget] and at events such as takeover,
 * follow-up questions, confirmation, barriers and outcome; any one of them indicating that this run should not be
 * recorded (needs the user to step in, payment, password field, screenshot protection, used the browser tools or the
 * sandbox tools) makes it return null.
 */
object RouteRecorder {
    /** If one of these was used, the approach depended on the user or on another channel, and replaying it is unsafe. */
    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET = Regex("\"secret\"\\s*:\\s*false")

    /** Slot values must be at least this long: shorter strings occur by coincidence in the utterance too easily. */
    private const val MIN_SLOT = 2

    /**
     * Typed text may only be a fragment that occurs exactly once in the current utterance (recorded as a slot);
     * anything else (fixed words, text obtained from a follow-up question, text copied from elsewhere) means the whole
     * route is not recorded — a route never stores text the user did not say in the utterance. When the name of a
     * target element appears in the utterance it must also be bound as a slot, so replay identifies it from the new
     * utterance.
     * Whether the task succeeded has already been judged by the caller via
     * [com.wangzan101.mika.core.agent.LearnGate] before recording; here only whether the route itself can be recorded
     * is checked.
     */
    fun record(goal: String, events: List<RunEvent>, nowMs: Long, tools: ToolIndex): Route? {
        if (events.lastOrNull { it is RunEvent.TaskEnded }?.let { (it as RunEvent.TaskEnded).outcome } != "succeeded") return null
        if (events.any { spoils(it, tools) }) return null
        if (events.any { it is RunEvent.ActionTarget && it.incomplete }) return null

        val acts = events.filterIsInstance<RunEvent.ActionTarget>().filter { it.effective && tools[it.tool]?.routable == true }
        if (acts.isEmpty() || acts.size > MAX_ROUTE_STEPS || acts.any { it.protected }) return null
        val results = events.filterIsInstance<RunEvent.ToolResult>()

        // slot values are numbered in the order they are first used
        val slots = mutableListOf<String>()
        fun bind(v: String): Boolean {
            if (v in slots) return true
            if (v.trim().length < MIN_SLOT || occurrences(goal, v) != 1) return false
            slots += v
            return true
        }
        val args = mutableListOf<JsonObject>()
        val targets = mutableListOf<Selector?>()
        for (a in acts) {
            val o = parse(a.argsJson) ?: return null
            var cleaned = o.filterKeys { it != "ref" && it != "snapshotId" && it != "target" }
            if (tools.verb(a.tool) == Verb.TypeText) {
                val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return null
                // input that the port has not explicitly reported as not being a password field is never recorded
                if (text == Redactor.MASK) return null
                val r = a.callId?.let { id -> results.lastOrNull { it.callId == id } } ?: return null
                if (!NOT_SECRET.containsMatchIn(r.resultJson)) return null
                if (!bind(text)) return null
                cleaned = cleaned + ("text" to JsonPrimitive("{${slots.indexOf(text) + 1}}"))
            }
            args += JsonObject(cleaned)
            targets += a.target?.let { t ->
                val n = t.name
                when {
                    n.isEmpty() -> t
                    n in goal -> if (bind(n)) t.copy(name = "{${slots.indexOf(n) + 1}}") else return null
                    // the name contains a parameter from this run (e.g. "send to Ann"): do not identify it by name
                    slots.any { it in n } -> t.copy(name = "", uniqueAtRecord = false)
                    else -> t
                }
            }
        }
        // the slot positions in the utterance must not overlap (a short one swallowed by a long one could not be substituted on its own)
        val ranges = slots.map { v -> goal.indexOf(v).let { it until it + v.length } }
        if (ranges.indices.any { i -> ranges.indices.any { j -> i < j && ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last } }) return null
        var template = goal
        ranges.withIndex().sortedByDescending { it.value.first }.forEach { (i, r) -> template = template.replaceRange(r, "{${i + 1}}") }

        fun anchors(a: Anchors, target: Selector?) = Anchors(a.packageName, a.texts.map(String::trim).filter { s ->
            RouteAnchors.stable(s) && s != target?.name?.trim() && slots.none { it.trim() in s }
        }.distinct().take(RouteAnchors.MAX))

        val steps = acts.mapIndexed { i, a -> RouteStep(a.tool, args[i].toString(), targets[i], anchors(a.before, a.target), anchors(a.after, null)) }
        val packages = (acts.mapNotNull { a -> if (tools.verb(a.tool) == Verb.LaunchApp) parse(a.argsJson)?.get("package")?.let { (it as? JsonPrimitive)?.contentOrNull } else null } +
            acts.flatMap { listOfNotNull(it.before.packageName, it.after.packageName) }).distinct()
        val route = Route(UUID.randomUUID().toString(), template, slots.size, packages, emptyMap(), steps, lastUsedMs = nowMs)
        return route.takeIf { RouteCodec.encode(it).toByteArray().size <= MAX_ROUTE_BYTES }
    }

    private fun occurrences(s: String, v: String): Int {
        var n = 0; var i = s.indexOf(v)
        while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
        return n
    }

    private fun spoils(e: RunEvent, tools: ToolIndex): Boolean = when (e) {
        is RunEvent.Takeover, is RunEvent.Confirmation -> true
        is RunEvent.State -> e.name in WAITING_STATES
        is RunEvent.Barrier -> e.kind == "payment"
        is RunEvent.ModelReply -> e.tool?.let { barred(it, tools) } == true
        is RunEvent.ToolResult -> barred(e.tool, tools) || PROTECTED.containsMatchIn(e.resultJson)
        is RunEvent.ActionTarget -> barred(e.tool, tools)
        else -> false
    }

    /** Routes only record on-screen approaches that can be replayed: if tools from other capabilities (web, sandbox) were used, replaying it would be incomplete. */
    private fun barred(tool: String, tools: ToolIndex): Boolean {
        val t = tools[tool] ?: return false
        if (t.verb in NO_ROUTE_VERBS) return true
        val routed = tools.names.mapNotNull { tools[it]?.takeIf { i -> i.routable }?.capability }.toSet()
        return t.capability != null && t.capability !in routed
    }

    private fun parse(s: String): JsonObject? = Json.jsonObjectOrNull(s)
}
