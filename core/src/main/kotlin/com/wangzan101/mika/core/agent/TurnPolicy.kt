package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb

/** A reminder the loop sends to the model: a source tag and the body (wrapped into context by [ContextBuilder.notice]). */
internal data class Notice(val source: String, val text: String)

/** The snapshot the turn policy inspects: current step and step limit. */
internal data class TurnSnapshot(val step: Int, val maxSteps: Int)

/** Same rule as the base prompt: a requested result missing the needed feature, item, or value counts as failure; only for a "is there any" question does an explicit none from the app's search count as the answer. */
internal val ANSWER_OR_FAILURE: String get() = Feedback["answer_or_failure"]

/** Why further actions are forbidden and only a final wrap-up chance is granted. */
internal enum class Closeout { StepLimit, Completed, NoNewData }

/**
 * Turn policy: whether to remind at the start of each step (mid-run self-check), whether to remind or end when the reply is empty (or truncated by the output limit), how to remind when verification does not match,
 * what to say in the final turn after steps run out, and which tools to offer. Pure functions that look only at what is passed in.
 * A reply without calls is itself the answer and is not handled here; only empty replies get a reminder, and only once.
 */
internal object TurnPolicy {
    /** Mid-run self-check: remind once each time step usage reaches these fractions; once everything has been searched, give up honestly. Tasks with too few steps get no reminder. */
    private val PROGRESS_CHECKS = listOf(0.5, 0.75)
    private const val PROGRESS_CHECK_MIN_STEPS = 10
    /** After steps run out, the final turn offers only the give-up tool; the answer is written as text. */
    val FINAL_VERBS = setOf(Verb.EndNotDone)

    /** The reminder prepended before this step is sent to the model; null when there is none. */
    fun beforeStep(s: TurnSnapshot): Notice? {
        if (s.maxSteps < PROGRESS_CHECK_MIN_STEPS) return null
        val used = s.step - 1
        if (PROGRESS_CHECKS.none { (s.maxSteps * it).toInt() == used }) return null
        return Notice(Feedback["progress_source"],
            Feedback.format("progress_check", "used" to used, "max" to s.maxSteps, "answer_or_failure" to ANSWER_OR_FAILURE))
    }

    /**
     * The reply has neither calls nor text ([truncated]: the text was cut off by the output limit, so it is not a complete answer). [already] true means such a reply has just been reminded once:
     * do not remind again this time; return null and the task ends here.
     */
    fun afterEmpty(already: Boolean, truncated: Boolean): Notice? =
        if (already) null else Notice(Feedback["nudge_source"], Feedback[if (truncated) "truncated_reply" else "empty_reply"])

    /**
     * The reminder used when answer verification fails and the answer is sent back once: [gaps] are the spots not found in the material that was read, and [objections] are the verifier model's reasons for judging it not done; they are stated separately.
     */
    fun unconfirmed(gaps: List<String>, objections: List<String> = emptyList()): Notice {
        val findings = listOfNotNull(
            gaps.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_gaps", "items" to it.joinToString("\n") { g -> "- $g" }) },
            objections.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_objection", "reasons" to it.joinToString("\n") { o -> "- $o" }) },
        ).joinToString("\n")
        return Notice(Feedback["unconfirmed_source"], Feedback.format("unconfirmed_nudge", "findings" to findings))
    }

    /** The answer body is not in the language of the user's utterance ([m] is the language family on both sides, see [ReplyLanguage]): send it back once to rewrite the whole passage in the user's language. */
    fun language(m: ReplyLanguage.Mismatch): Notice =
        Notice(Feedback["language_source"], Feedback.format("language_nudge", "answer" to m.answer.display, "user" to m.user.display))

    /** A call to [tool] leaked into the reply body (see [LeakedToolCall]): send it back once to re-invoke through the tool interface, or to answer in text. */
    fun leakedToolCall(tool: String): Notice =
        Notice(Feedback["leaked_call_source"], Feedback.format("leaked_call_nudge", "tool" to tool))

    /** The user agreed or answered ([what]), but the model is about to deliver an answer without doing anything: remind once to follow through, or to state clearly why not. */
    fun followUp(what: String): Notice = Notice(Feedback["followup_source"], Feedback.format("followup_nudge", "what" to what))

    /** The reminder given just before the final turn after steps run out. */
    fun finalTurn(): Notice = Notice(Feedback["final_source"], Feedback["final_turn"])

    /** Wrap-up after completion or after consecutive runs with no new data: action tools are no longer offered, but the result can still be delivered or partial completion reported honestly. */
    fun closeout(reason: Closeout, progress: RunProgress.Task? = null): Notice {
        val confirmed = progress?.display?.takeIf { it.isNotBlank() }?.let { "\nLatest confirmed progress: $it" }.orEmpty()
        return when (reason) {
            Closeout.StepLimit -> finalTurn()
            Closeout.Completed -> Notice(Feedback["closeout_source"], Feedback.format("completed_closeout", "progress" to confirmed))
            Closeout.NoNewData -> Notice(Feedback["closeout_source"],
                Feedback.format("no_new_data_closeout", "count" to NewDataTracker.LIMIT, "progress" to confirmed))
        }
    }

    /** Tools offered to the model in the final turn: only give-up; if the work is done, just answer in text. */
    fun finalTools(specs: List<ToolSpec>, verbOf: (String) -> Verb?): List<ToolSpec> = specs.filter { verbOf(it.name) in FINAL_VERBS }
}
