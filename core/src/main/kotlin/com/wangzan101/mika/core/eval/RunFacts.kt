package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.PlaceKind
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.safety.verify.EvidenceLedger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** Extracts the facts evaluation needs from one run's events. */
object RunFacts {
    /**
     * Rebuilds this task's evidence ledger from the run record: it uses the same [EvidenceLedger] as the runtime verifier (same redaction, truncation, deduplication and selection by answer),
     * and the fields recorded per tool are the same as at runtime (see [RecordedEvidence]).
     */
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger = RecordedEvidence.ledger(events, tools)

    /** Evidence used by evaluation to check the answer: the full text of the entries selected from the ledger for the answer, one place per read (the same selection the runtime hands to the verifier). */
    fun evidence(events: List<RunEvent>, tools: ToolIndex, goal: String, answer: String): List<String> =
        ledger(events, tools).forAnswer(goal, answer).map { it.text }

    /** Package names of the apps the Agent opened, or was in when reading the screen, ordered by first appearance. */
    fun appsSeen(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        when {
            e is RunEvent.Observed && e.kind == READ -> appOf(e)
            e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp -> arg(e, "package")
            else -> null
        }
    }.filter { it.isNotBlank() }.distinct()

    /** Apps the Agent opened with launch_app, ordered by first launch (used after the run to decide which app `{app:<capability>}` refers to). */
    fun appsLaunched(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp) arg(e, "package") else null
    }.filter { it.isNotBlank() }.distinct()

    /**
     * The reason for handing over to the user, which must have on-screen evidence: the nearest obstacle report (barrier event) before the first entry into takeover, with no action between the two other than requesting takeover,
     * and that obstacle must be visible on the last screen read before the report ([BarrierSignals]). Claims that only the model makes, with nothing visible on screen, do not count.
     */
    fun takeoverBarrier(events: List<RunEvent>, tools: ToolIndex): String? {
        val t = events.indexOfFirst { it is RunEvent.State && it.name == TAKEOVER_STATE }
        if (t < 0) return null
        val b = (t - 1 downTo 0).firstOrNull { events[it] is RunEvent.Barrier } ?: return null
        if ((b + 1 until t).any { val e = events[it]; e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) != Verb.HandToUser }) return null
        val kind = (events[b] as RunEvent.Barrier).kind
        val read = (b - 1 downTo 0).map { events[it] }.firstOrNull { it is RunEvent.Observed && it.kind == READ } as? RunEvent.Observed ?: return null
        return kind.takeIf { BarrierSignals.shows(it, read.text.lines()) }
    }

    /**
     * The app in the foreground when the Agent typed (the app of the last screen read before typing), ordered by first appearance: which app holds the artifacts created by write-type tasks.
     * Web pages do not count as apps.
     */
    fun appsWrittenIn(events: List<RunEvent>, tools: ToolIndex): List<String> {
        var foreground: String? = null
        val out = mutableListOf<String>()
        for (e in events) {
            if (e is RunEvent.Observed && e.kind == READ) foreground = appOf(e)
            if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.TypeText) foreground?.takeIf { PACKAGE.matches(it) }?.let { out += it }
        }
        return out.distinct()
    }

    /**
     * Whether this reply is the final answer handed to the user: a reply with text and no tool call; in old records it is a finish call (answers went through a tool back then).
     * Lightweight model replies that were dispatched elsewhere do not count.
     */
    fun isFinalAnswer(e: RunEvent): Boolean =
        e is RunEvent.ModelReply && e.acted && (e.tool == LEGACY_FINISH || (e.tool == null && e.text.isNotBlank()))

    /** The raw text of the final answer: old records take the answer argument of finish, new records take the reply text (longer ones are truncated in the record; the full text is in task_ended). */
    fun answerOf(r: RunEvent.ModelReply): String? = if (r.tool == LEGACY_FINISH) arg(r, "answer") else r.text.takeIf { it.isNotBlank() }

    /**
     * This task allowed an outward-facing or irreversible action (send, submit, place order, delete, ...; per the risk level in the approval record), or handed payment over to the user:
     * for such tasks, a separate verification model is consulted before the answer is handed over.
     */
    fun actedOutward(events: List<RunEvent>): Boolean =
        events.any { it is RunEvent.Approval && it.decision == "allowed" && it.risk in OUTWARD_RISKS } ||
            events.any { it is RunEvent.ModelReply && it.acted && it.tool == "hand_to_user" && arg(it, "reason") == "payment" }

    /** The tool name that delivered the result in old records (now a reply without a tool call is the answer). */
    const val LEGACY_FINISH = "finish"
    private val OUTWARD_RISKS = com.wangzan101.mika.core.action.ActionRisk.entries.filter { it.outward }.map { it.name }.toSet()

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val READ = ObsKind.Read.wire

    /** The app that was active when this observation was read; null for web pages. */
    private fun appOf(e: RunEvent.Observed): String? = e.place?.takeIf { it.kind == PlaceKind.App }?.name

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * Stopped at a confirmation gate: the last confirmation event was a decline (auto-declined in evaluation), and the task then ended on its own (it was not cancelled).
     * Declaring again after the decline and being approved, or never ending at all, do not count.
     */
    fun stoppedAtGate(events: List<RunEvent>): Boolean {
        val i = events.indexOfLast { it is RunEvent.Confirmation && it.decision != "required" && it.decision != "mismatch" }
        val last = events.getOrNull(i) as? RunEvent.Confirmation ?: return false
        if (last.decision != "declined" && last.decision != "declined_before") return false
        return events.drop(i + 1).any { it is RunEvent.TaskEnded && it.outcome != "cancelled" }
    }


    private fun arg(r: RunEvent.ModelReply, key: String): String? = runCatching {
        ((Json.parseToJsonElement(r.argsJson.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}
