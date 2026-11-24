package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.safety.verify.AnswerSource
import com.wangzan101.mika.core.safety.verify.AnswerSources
import com.wangzan101.mika.core.safety.verify.Criteria
import com.wangzan101.mika.core.safety.verify.Grounding
import com.wangzan101.mika.core.safety.verify.Verdict
import com.wangzan101.mika.core.safety.verify.Verification
import com.wangzan101.mika.core.safety.verify.Verifier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The task facts the review looks at before the answer is handed over. */
internal data class ReviewFacts(
    /** This task called tools (if not, it is chat or common knowledge, with no material to verify against). */
    val acted: Boolean,
    /** Did something outward-facing or irreversible (such an action was let through, or payment was handed to the user). */
    val outward: Boolean,
    /** Nothing was re-read after the most recent state-changing action: nobody has looked at the result the answer describes. */
    val unreadChange: Boolean,
)

/**
 * Verification before the final answer is handed over. Driven by risk, without bouncing back and forth:
 * - This task called no tool at all (chat, common knowledge, answerable from the conversation): not checked, counted as unverified.
 * - Literal check ([Grounding.check]): numbers, IDs, dates, quotes and explicitly stated arithmetic (a operator b = c) in the answer must appear verbatim in the material read during this task.
 *   If the answer contains such facts and all of them match, it counts as verified; if the answer contains no such facts, there is nothing to check literally, so it counts as unverified.
 * - If an outward-facing or irreversible action was done, nothing was re-read after the most recent state-changing action, or the literal check found mismatches, a separate verifier model ([Verifier]) is asked; it counts as verified only if that model returns pass.
 * When the conclusion contains mismatches ([Review.gaps], [Review.objections]), the loop sends it back to the model once; if that still fails, those points are marked as unconfirmed and handed over (see AgentLoop).
 * Sources ([Review.sources]) are also found here, in code ([AnswerSources]).
 */
internal class AnswerReview(
    private val tools: ToolDispatcher,
    private val verifier: Verifier?,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
) {
    /** The product path only assembles sources and no longer launches another verification round for the final answer. */
    suspend fun sourcesOnly(goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> =
        AnswerSources.of(tools.evidence(fresh = false).ledger, goal, answer, media)

    /**
     * [gaps] are the places in the answer that cannot be found in the material that was read, [objections] are the reasons the verifier model judged it not done; together they form [unconfirmed].
     * [reviewed] false means nothing was checked at all (nothing checkable, and no verifier model was asked); [verified] is true only when it was checked and everything matched.
     * [note] is why the checker could not verify; [verdict] is the verifier model's conclusion (null if it was not asked).
     */
    class Review(
        val gaps: List<String> = emptyList(), val objections: List<String> = emptyList(), val sources: List<AnswerSource> = emptyList(),
        val reviewed: Boolean = true, val verified: Boolean = true, val note: String? = null, val verdict: Verdict? = null,
    ) {
        val unconfirmed: List<String> get() = (gaps + objections).distinct()
    }

    /** Check the answer [answer] given at step [step]. [media]: the screen image IDs stored at each step; [verifying]: call it first when the verifier model is about to be asked (the UI shows "verifying"). */
    suspend fun review(
        goal: String, answer: String, step: Int, facts: ReviewFacts, earlierGoals: List<String>, media: Map<Int, List<Int>>, verifying: () -> Unit,
    ): Review {
        if (!facts.acted) return Review(reviewed = false, verified = false)
        val v = verifier
        // The literal check only looks at the ledger (the latest read is already in it) and does not re-read; only when the verifier model is to be asked do we re-read the current screen
        val read = tools.evidence(fresh = false)
        val sources = AnswerSources.of(read.ledger, goal, answer, media)
        val g = Grounding.check(goal, answer, read.sources(read.ledger.forAnswer(goal, answer)), read.today)
        val gaps = g.unmatched.map { it.text }.distinct()
        val risky = facts.outward || facts.unreadChange
        emit(RunEvent.AnswerCheck(now(), step, g.claims.size, gaps.map(::detail), risky))
        val literalOk = gaps.isEmpty() && g.claims.isNotEmpty()
        if (!risky && gaps.isEmpty()) return Review(sources = sources, reviewed = literalOk, verified = literalOk)
        if (v == null) return Review(gaps = gaps, sources = sources, reviewed = literalOk || gaps.isNotEmpty(), verified = literalOk)
        verifying()
        val ev = tools.evidence(withImage = v.wantsImage)
        val r = v.verify(goal, Criteria.of(goal, facts.outward), answer, ev, earlierGoals)
        emit(RunEvent.Verifier(now(), r.verdict.wire, RunEvent.clip(Redactor.text(detailOf(r).toString())), step))
        return when (r.verdict) {
            Verdict.Pass -> Review(sources = sources, verdict = r.verdict)
            // The criteria are just the user's own words, so an unmet "criterion" is those words themselves: what needs explaining is the reason the checker gives
            Verdict.Fail -> Review(gaps = gaps, objections = listOf(r.reason).filter(String::isNotBlank), sources = sources, verified = false, verdict = r.verdict)
            Verdict.CannotVerify -> (gaps + r.notFound).distinct().let { missing ->
                Review(gaps = missing, sources = sources, verified = false, note = r.reason, verdict = r.verdict)
            }
        }
    }

    private fun detail(s: String) = RunEvent.clip(Redactor.text(s))

    private fun detailOf(v: Verification) = buildJsonObject {
        put("reason", v.reason)
        v.groundedBy?.let { put("groundedBy", it) }
        if (v.notFound.isNotEmpty()) put("notFound", JsonArray(v.notFound.map(::JsonPrimitive)))
        put("checks", JsonArray(v.checks.map { c ->
            buildJsonObject { put("criterion", c.criterion); put("status", c.status); c.quote?.let { put("quote", it) } }
        }))
    }
}
