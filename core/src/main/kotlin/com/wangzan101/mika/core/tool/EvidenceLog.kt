package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.screen.observation.ObsForm
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.safety.verify.EvidenceLedger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val RECENT_READS = 3
internal const val MAX_READ_CHARS = EvidenceLedger.READ_CHARS
private const val MAX_ACTION_LOG = 30
/** Facts the user supplied: the most recent few, each truncated at the head and tail. */
private const val USER_FACTS = 6
private const val USER_FACT_CHARS = EvidenceLedger.OUTPUT_CHARS

/**
 * The material handed to the verifier before completion. Everything read counts as an observation ([Observation]: screens and web pages that were read, sandbox output and files that were read,
 * facts inside tool results), supplied by a capability in the tool result and recorded by the dispatcher into the evidence ledger [ledger] through [observe], together with the step number and the time;
 * what the user said ([ObsKind.UserSaid]: memory search results, answers to questions) is recorded separately in [userFacts]. There is also the action log and the outcome of each takeover.
 * Each tool decides what it has read ([AgentTool.observations]) and what goes into the action log ([AgentTool.logTarget]).
 */
internal class EvidenceLog(private val clock: () -> Long = System::currentTimeMillis) {
    val ledger = EvidenceLedger()
    /** The current step number (set by the scheduler before each tool execution), recorded into the ledger as its source. */
    var step: Int = 0
    /** Text of the last few screen states that were read (used to deduplicate screen reads). */
    val recentReads = ArrayDeque<String>()
    val actionLog = ArrayDeque<String>()
    /** Facts the user supplied: memory search results and answers to ask_user within this task. */
    val userFacts = ArrayDeque<String>()
    /** App names that list_apps has returned; included in launch_app's facts. */
    val appLabels = mutableMapOf<String, String>()
    /** The outcome of each takeover; not capped, so it never rolls off the way the action log does. */
    val takeovers = mutableListOf<String>()
    /** Files the assistant has written with write_file: anything read back later with read_file was written by the assistant itself and cannot back an answer as evidence. */
    val writtenPaths = mutableSetOf<String>()

    /**
     * Record one observation: what the user said goes into [userFacts] (truncated at the head and tail), everything else goes into the ledger (redacted, truncated, echoes removed, see [EvidenceLedger.add]).
     * Returns the entry recorded in the ledger; null when nothing was recorded (what the user said, or empty after truncation).
     */
    fun observe(o: Observation): Observation? {
        if (o.kind == ObsKind.UserSaid) {
            noteUserFact(o.text)
            return null
        }
        if (o.form == ObsForm.Interface) {
            recentReads.addLast(o.text)
            while (recentReads.size > RECENT_READS) recentReads.removeFirst()
        }
        return ledger.add(o, step, clock())
    }

    /** Text the assistant typed into an input field (not a password field): the echo in the input field cannot back an answer as evidence. */
    fun noteTyped(text: String) = ledger.noteTyped(text)

    private fun noteUserFact(text: String) {
        userFacts.addLast(if (text.length <= USER_FACT_CHARS) text else text.take(USER_FACT_CHARS * 3 / 4) + "\n…(omitted in the middle)…\n" + text.takeLast(USER_FACT_CHARS / 4))
        while (userFacts.size > USER_FACTS) userFacts.removeFirst()
    }

    fun noteTakeover(line: String) {
        ledger.noteTakeover()
        takeovers += line
        actionLog.addLast(line)
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }

    /**
     * One line of the action log: [label] is "step N", [target] is what the action acted on and [typed] is the text that was typed (both supplied by the tool);
     * with [wide] the target is kept longer (urls, commands, paths).
     */
    fun logAction(label: String, name: String, target: String?, typed: String?, r: ToolExecution, wide: Boolean) {
        val effect = when {
            r.content["ok"] != JsonPrimitive(true) -> (r.content["error"] as? JsonPrimitive)?.content
                ?: (r.content["exitCode"] as? JsonPrimitive)?.let { "exit code ${it.content}" } ?: "failed"
            r.changed == false -> "no visible change"
            else -> "applied"
        }
        val what = listOfNotNull(target?.take(if (wide) 120 else 40), typed).joinToString(" ")
        actionLog.addLast("$label $name($what) $effect")
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }
}

internal fun JsonObject.argStr(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
