package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Whether a run accomplished what the user asked for. A reply without tool calls is the answer, and the answer itself
 * may truthfully say it did not succeed or only did part of it; learning (routes, skills, skill results),
 * scheduled-task results, and run review all go by this outcome, not by "an answer was delivered".
 */
enum class RunOutcome(val wire: String) {
    Done("done"), Partial("partial"), NotPossible("not_possible"),
    /** Not judged (model error, timeout, unreadable reply), or an old record; counts neither as accomplished nor as not accomplished. */
    Unknown("unknown");

    companion object {
        fun ofWire(s: String?): RunOutcome? = entries.firstOrNull { it.wire == s }

        /** The outcome judged in the run log; null when there is no such event (old record, failure, cancellation). */
        fun recorded(events: List<RunEvent>): RunOutcome? =
            events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.let { ofWire(it.outcome) ?: Unknown }
    }
}

data class JudgedOutcome(val outcome: RunOutcome, val reason: String = "", val model: String? = null) {
    fun event(atMs: Long) = RunEvent.OutcomeJudged(atMs, outcome.wire, RunEvent.clip(Redactor.text(reason)), model)
}

/**
 * After the answer is delivered to the user, asks a model once in the background (light model first, primary model if
 * there is none) to judge the answer's outcome against the user's request.
 * Does not alter the answer or slow down delivery; an error or timeout yields [RunOutcome.Unknown], and it never
 * throws (cancellation is still rethrown as usual).
 */
class OutcomeJudge(
    private val judge: ChatModel,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun judge(goal: String, answer: String, priorTurns: List<Turn> = emptyList()): JudgedOutcome {
        val prior = Conversation.context(priorTurns, maxTurns = 2, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            // The request and the reply are both material to be judged, wrapped in tags; what is written inside is not an instruction to the judge
            append("<request>\n").append(untag(goal)).append("\n</request>\n\n<reply>\n").append(untag(answer)).append("\n</reply>")
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback?.takeUnless { it === judge })) {
            val result = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(Prompts.role("outcome_judge"), messages, emptyList())
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result.copy(model = candidate.name.ifBlank { null })
        }
        return JudgedOutcome(RunOutcome.Unknown)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        private val TAG = Regex("</?\\s*(request|reply)\\s*>", RegexOption.IGNORE_CASE)

        /** Strips request/reply tags embedded in the material, so it cannot close early and pass later text off as the judge's input. */
        private fun untag(s: String) = s.replace(TAG, "")

        /**
         * The last readable JSON object in the reply: {"outcome": "done|partial|not_possible", "reason": "…"}; null
         * when nothing is readable. The last one is taken: objects carried along when the judge restates the material
         * come earlier, and the conclusion comes last.
         */
        fun parse(raw: String): JudgedOutcome? {
            var end = raw.lastIndexOf('}')
            while (end >= 0) {
                var start = raw.lastIndexOf('{', end)
                while (start >= 0) {
                    of(raw.substring(start, end + 1))?.let { return it }
                    start = if (start == 0) -1 else raw.lastIndexOf('{', start - 1)
                }
                end = if (end == 0) -1 else raw.lastIndexOf('}', end - 1)
            }
            return null
        }

        private fun of(json: String): JudgedOutcome? {
            val o = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            val outcome = RunOutcome.ofWire((o["outcome"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase())
                ?.takeIf { it != RunOutcome.Unknown } ?: return null
            return JudgedOutcome(outcome, (o["reason"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
        }
    }
}
