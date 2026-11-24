package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.safety.gate.Irreversible
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.Verb

/**
 * The action run record: tool results, the confirmation gate, and frames. Screenshot ids are handed out here in one place (frames the loop saves itself also take their numbers from here).
 * A failed record does not affect the task. When [saveScreen] is null, substep frames are not saved (and no id is handed out).
 */
class ActionRecorder(
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val saveScreen: ((Int, ByteArray) -> Unit)? = null,
) {
    private var shots = 0

    val savesScreens: Boolean get() = saveScreen != null

    fun now(): Long = clock()

    fun nextShot(): Int = ++shots

    fun event(e: RunEvent) {
        try {
            sink.event(e)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // A failed record does not affect the task
        }
    }

    fun confirmation(hit: Irreversible, decision: String) =
        event(RunEvent.Confirmation(clock(), hit.action.name, RunEvent.clip(Redactor.text(hit.matched)), null, null, decision))

    /**
     * The evidence ledger position before the most recent state-changing action (a UI change, an outward action, an irreversible one, or a write inside the sandbox; one that reported failure and did not say the UI changed does not count);
     * if nothing has been re-read since, a review must be requested separately before the answer is handed over (see AnswerReview). Null while there has been no such action yet.
     */
    var lastChangeMark: Int? = null
        private set

    fun noteChange(ledgerMark: Int) { lastChangeMark = ledgerMark }

    /** How many outward or irreversible actions were let through in this task (send, submit, order, delete, …); before the answer is handed over this decides whether to ask the review model. */
    var outwardActs = 0
        private set

    fun approval(origin: Origin, tool: String, hit: Irreversible, risk: Risk, decision: String) {
        if (decision == "allowed" && risk.level.outward) outwardActs++
        event(RunEvent.Approval(clock(), origin.step, origin.policy.source, tool, hit.action.name, RunEvent.clip(Redactor.text(hit.matched)),
            risk.level.name, risk.payment, decision))
    }

    /** The result of one call; [content] is the copy handed to the model (or the caller), [extra] holds fields that go into the run record only. */
    fun result(
        step: Int, tool: String, verb: Verb?, content: JsonObject, extra: JsonObject, changed: Boolean?, shotId: Int?, durationMs: Long,
        source: String, callId: String, waitMs: Long = 0,
    ) {
        // The user's answer may be a verification code and the like, so it does not go into the run record
        val shown = if (verb == Verb.AskUser && content.containsKey("answer")) JsonObject(content + ("answer" to JsonPrimitive(HIDDEN_ANSWER))) else content
        val recorded = Redactor.result(verb, shown).let { if (extra.isEmpty()) it else JsonObject(it + extra) }
        event(RunEvent.ToolResult(clock(), step, tool, RunEvent.clipResult(Redactor.text(recorded.toString())), changed, shotId, durationMs,
            waitMs = waitMs, source = source, callId = callId))
    }

    /** Save a substep frame on the spot and return the screenshot id; null when frames are not saved. */
    fun saveFrame(png: ByteArray): Int? {
        val save = saveScreen ?: return null
        val id = nextShot()
        try {
            save(id, png)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // Same as above
        }
        return id
    }

    private companion object {
        const val HIDDEN_ANSWER = "(the user's answer, not recorded)"
    }
}
