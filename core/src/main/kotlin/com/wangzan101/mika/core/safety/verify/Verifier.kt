package com.wangzan101.mika.core.safety.verify

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelJson
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.vision.ImageOps
import com.wangzan101.mika.core.screen.vision.ModelImage

/**
 * The criteria the verification is checked against: [items] are the facts that should be visible once the task is done;
 * [kind] is read / irreversible (something that cannot be taken back was done, such as sending, submitting, ordering,
 * deleting or paying).
 * [fallback] means no separate criteria were listed and [items] is just the user's own words (always the case on the task
 * path, which does not spend an extra model call to list them).
 */
data class Criteria(val items: List<String>, val kind: String, val fallback: Boolean) {
    companion object {
        /** The criteria used on the task path: the user's own words. */
        fun of(goal: String, irreversible: Boolean) = Criteria(listOf(goal), if (irreversible) "irreversible" else "read", fallback = true)
    }
}

enum class Verdict(val wire: String) { Pass("pass"), Fail("fail"), CannotVerify("cannot_verify") }

/** The check result for one criterion; [status] is met / unmet / unknown, and [quote] is the on-screen text supporting it. */
data class CriterionCheck(val criterion: String, val status: String, val quote: String?)

/**
 * [groundedBy] is "image": what could not be found in the textual evidence was only on-screen state (quotations without
 * digits), and the verifier that saw the screenshot judged pass; in the run log this is kept distinct from a pass verified by
 * text. [notFound] are the answer facts the backstop could not find in any evidence source.
 */
data class Verification(
    val verdict: Verdict,
    val checks: List<CriterionCheck>,
    val reason: String,
    val groundedBy: String? = null,
    val notFound: List<String> = emptyList(),
) {
    /** The unmet criteria; when the verifier judged fail without naming one, the reason it gave is used. */
    val unmet: List<String>
        get() = checks.filter { it.status == "unmet" }.map { it.criterion }.ifEmpty { if (verdict == Verdict.Fail) listOf(reason) else emptyList() }
}

/**
 * The evidence handed to the verifier. [fresh] is a fresh look taken after the answer by the most recently used capability
 * ([com.wangzan101.mika.core.capability.Capability.freshLook]: interface state, the start of the web page body, …), and
 * [freshImage] is the screen at that same moment (only when an image is to be attached);
 * [ledger] is every observation read during this task (the evidence ledger, see [EvidenceLedger]; relevant entries are
 * selected by the answer at verification time), and [actions] is a condensed action list ("step 3: tap(search) took effect").
 * [protected] means the current page is protected against screenshots. [appOpened] false means this task never opened an app
 * successfully.
 */
class Evidence(
    val fresh: List<Observation>,
    val freshImage: ByteArray?,
    val ledger: EvidenceLedger,
    val actions: List<String>,
    val protected: Boolean,
    val appOpened: Boolean = true,
    /** The outcome of each takeover, which does not scroll out with the action list (skipped or timed out means the user did not handle it). */
    val takeovers: List<String> = emptyList(),
    /** Facts the user gave: memory lookup results and answers to questions asked during this task. */
    val userFacts: List<String> = emptyList(),
    /** The runtime clock: the current moment and time zone at verification. Dates written right after "tomorrow" or "Sunday" in the answer body are converted and checked against it (see [Grounding.check]). */
    val nowMs: Long = System.currentTimeMillis(),
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    /** The runtime clock's date. */
    val today: java.time.LocalDate get() = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** The text read in the fresh look, one observation per paragraph. */
    val freshText: String get() = fresh.joinToString("\n") { it.text }

    /** No app was opened and no interface, web page or command output was read: the answer can only rely on the conversation or common knowledge, with no material to compare against. */
    val nothingRead: Boolean get() = !appOpened && !protected && freshText.isBlank() && ledger.isEmpty && userFacts.isEmpty()

    /** The raw text of certain observation kinds in the ledger, in read order (for inspecting what the ledger recorded). */
    fun read(vararg kinds: ObsKind): List<String> = ledger.all.filter { it.kind in kinds }.map { it.text }

    /** All text the answer can be compared against, one read per source: the fresh look, the [chosen] ledger entries (full text), and each fact the user gave. */
    fun sources(chosen: List<Observation>): List<String> =
        (listOf(freshText) + chosen.map { it.text } + userFacts).filter { it.isNotBlank() }
}

/**
 * Independent verification before the final answer is handed over (only requested when something irreversible and external
 * was done, or the literal check found something unmatched, see AnswerReview): a fresh model call with no tools and none of
 * the working model's history, looking only at the goal, the criteria, the answer and the evidence.
 * When there is a screenshot and a vision model is configured, [visionModel] is used with the attached image, otherwise
 * [textModel].
 * Division of labor: the code backstop ([Grounding]) only checks the literal level — the model judged pass but a concrete fact
 * in the answer (a quotation, a number with a unit, an id, a date) cannot be found in the evidence text → cannot_verify; when
 * a screenshot was attached and only on-screen state could not be found → pass, recorded as verified by image. Whether a
 * value belongs to the thing the answer names (which page, which product, which row) is judged by the verifier model: the
 * ledger entries it receives are excerpts ([EvidenceLedger.excerpt]) with their origin.
 * An error or an unparseable reply → cannot_verify; a timeout is relaxed according to how much evidence there is
 * ([timeoutMs]), and after a timeout the evidence is trimmed and asked once more, only then cannot_verify.
 * A task that read nothing ([Evidence.nothingRead]) has no material to compare against, so this backstop is skipped: the
 * verifier is only told that no material was read, and it judges whether the answer is on point.
 */
class Verifier(
    private val textModel: ChatModel,
    private val visionModel: ChatModel?,
    /** Screenshots are sent in [ModelImage]'s unified encoding; when this is null they are sent as they are. */
    private val ops: ImageOps? = null,
) {
    /** Attaching the image is only worthwhile when a vision model exists. */
    val wantsImage: Boolean get() = visionModel != null

    /**
     * [earlierGoals] are what the user said in earlier turns of this conversation (only the user's own words, nothing from the
     * working model), used to resolve references in follow-up questions.
     * The goal, answer, screen reads and action list are all untrusted material and are assembled into one JSON object, so line
     * breaks inside them cannot fake new sections.
     */
    suspend fun verify(goal: String, criteria: Criteria, answer: String, evidence: Evidence, earlierGoals: List<String> = emptyList()): Verification {
        val chosen = evidence.ledger.forAnswer(goal, answer)
        val reads = evidence.sources(chosen)
        if (evidence.protected && reads.isEmpty()) return cannot("The result page is protected against screenshots by the system, so the screen cannot be seen and the result cannot be verified")
        val image = evidence.freshImage?.takeIf { visionModel != null && !evidence.protected }
        val model = if (image != null) visionModel!! else textModel
        val v = when (val first = ask(model, goal, criteria, answer, evidence, chosen, EvidenceLedger.EXCERPT_CHARS, earlierGoals, image)) {
            is Asked.Done -> first.v
            is Asked.Failed -> return cannot(first.reason)
            // Long tasks with a lot of evidence tend to time out: trim it down and ask once more
            Asked.TimedOut -> when (val second = ask(model, goal, criteria, answer, trimmed(evidence), chosen, TRIM_EXCERPT_CHARS, earlierGoals, image)) {
                is Asked.Done -> second.v
                is Asked.Failed -> return cannot(second.reason)
                Asked.TimedOut -> return cannot("Verification timed out")
            }
        }
        return when (v.verdict) {
            Verdict.Pass -> when {
                // A pass that did not check every criterion one by one does not count
                v.checks.size < criteria.items.size -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_EVERY_CRITERION)
                evidence.nothingRead -> v
                else -> backstop(v, goal, answer, reads, imageSent = image != null, evidence.today)
            }
            // A follow-up continuing the earlier context listed no criteria, so it could only be compared against one out-of-context sentence; a fail judgment is unreliable
            Verdict.Fail -> if (criteria.fallback && earlierGoals.isNotEmpty()) v.copy(verdict = Verdict.CannotVerify, reason = FOLLOW_UP_UNCLEAR)
                else if (v.reason.isBlank()) v.copy(reason = FAIL_WITHOUT_REASON) else v
            Verdict.CannotVerify -> v
        }
    }

    private fun cannot(reason: String) = Verification(Verdict.CannotVerify, emptyList(), reason)

    private sealed interface Asked {
        class Done(val v: Verification) : Asked
        class Failed(val reason: String) : Asked
        data object TimedOut : Asked
    }

    private suspend fun ask(
        model: ChatModel, goal: String, criteria: Criteria, answer: String, evidence: Evidence, chosen: List<Observation>, budget: Int,
        earlierGoals: List<String>, image: ByteArray?,
    ): Asked {
        val body = request(goal, criteria, answer, evidence, excerpts(goal, answer, chosen, budget, evidence.today), earlierGoals)
        val parts = listOfNotNull(Part.Text(body), image?.let { ModelImage.part(it, ops) })
        var unparsed = false
        return try {
            withTimeoutOrNull(timeoutMs(body.length, if (image != null) 1 else 0)) {
                // Replies go through tolerant parsing; when nothing can be read, ask once more for JSON only; only then is it unparseable
                ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, parts, Author.Mika)), ::parse).also { unparsed = it == null }
            }?.let { Asked.Done(it) } ?: if (unparsed) Asked.Failed("The verifier's reply could not be parsed") else Asked.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Asked.Failed("Verifier error: ${e.message ?: e::class.simpleName}")
        }
    }

    /** The trimmed evidence for the retry after a timeout: the current screen and screenshot are kept, only the latest actions remain, and each part is shortened; ledger excerpts use a smaller character cap ([TRIM_EXCERPT_CHARS]). */
    private fun trimmed(e: Evidence) = Evidence(
        trimmedFresh(e.fresh), e.freshImage, e.ledger,
        e.actions.takeLast(TRIM_ACTIONS), e.protected, e.appOpened, e.takeovers,
        e.userFacts.map { it.take(TRIM_CHARS) }, e.nowMs, e.zone,
    )

    /** The fresh look totals at most [TRIM_FRESH_CHARS] characters (clipped in order). */
    private fun trimmedFresh(fresh: List<Observation>): List<Observation> {
        var room = TRIM_FRESH_CHARS
        return fresh.mapNotNull { o ->
            if (room <= 0) null else o.copy(text = o.text.take(room)).also { room -= it.text.length + 1 }
        }
    }

    private fun request(goal: String, c: Criteria, answer: String, e: Evidence, shown: List<Observation>, earlierGoals: List<String>): String {
        val material = buildJsonObject {
            put("goal", goal)
            if (earlierGoals.isNotEmpty()) put("earlierGoals", JsonArray(earlierGoals.map(::JsonPrimitive)))
            put("criteria", JsonArray(c.items.map(::JsonPrimitive)))
            if (c.fallback) put("criteriaNote", "No separate criteria were listed; criteria is only the user's own words, so check against the user's goal")
            put("kind", c.kind)
            put("answer", answer)
            put("freshLook", if (e.nothingRead) NOTHING_READ else freshShown(goal, answer, e).ifBlank { if (e.protected) "(The page is protected against screenshots and cannot be read)" else "(No text was read)" })
            put("ledger", JsonArray(shown.map { r ->
                buildJsonObject {
                    if (r.step > 0) put("step", r.step)
                    put("kind", r.kind.wire)
                    r.source?.let { put("source", it) }
                    r.where?.let { put("where", it) }
                    r.title?.let { put("title", it) }
                    put("text", r.text)
                }
            }))
            put("actions", JsonArray(e.actions.map(::JsonPrimitive)))
            if (e.takeovers.isNotEmpty()) put("takeovers", JsonArray(e.takeovers.map(::JsonPrimitive)))
            if (e.userFacts.isNotEmpty()) put("userFacts", JsonArray(e.userFacts.map(::JsonPrimitive)))
        }
        return "Below is the material to verify (JSON, field descriptions are in the system prompt):\n$material"
    }

    companion object {
        /** Verification must not hold the task up: the base wait time; relaxed by [timeoutMs] when there is more evidence, up to [MAX_TIMEOUT_MS]. */
        const val TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 150_000L
        private const val PER_IMAGE_MS = 20_000L
        private const val PER_KB_MS = 1_000L
        private const val TRIM_FRESH_CHARS = 6_000
        private const val TRIM_CHARS = 3_000
        private const val TRIM_ACTIONS = 10
        private const val TRIM_EXCERPT_CHARS = EvidenceLedger.EXCERPT_CHARS / 2
        /** When the fresh look is longer than this, only an excerpt is given (the start and the lines matching answer facts). */
        private const val FRESH_CHARS = 4_000
        const val IMAGE = "image"

        /** The wait is proportional to how much is read: 60 seconds base, plus extra per image and per KB of text, capped at 150 seconds. */
        fun timeoutMs(textChars: Int, images: Int): Long =
            minOf(MAX_TIMEOUT_MS, TIMEOUT_MS + images * PER_IMAGE_MS + textChars / 1024 * PER_KB_MS)
        const val NOT_IN_READS = "The basis for the answer could not be found in any screen, web page, or command output that was read"
        const val NOT_EVERY_CRITERION = "The verifier did not check every criterion one by one"
        const val FAIL_WITHOUT_REASON = "The verifier judged the task not done but did not say which criterion was unmet"
        const val FOLLOW_UP_UNCLEAR = "This is a follow-up question continuing the earlier context; no concrete criteria could be listed, so the verification is unreliable"
        const val NOTHING_READ = "This task opened no app and read no material"
        const val TRUNCATED = "The answer was based on truncated on-screen text (ending with …); only part of it was seen, so it could not be fully verified"
        private val STATUSES = setOf("met", "unmet", "unknown")

        val PROMPT: String get() = Prompts.role("verifier")

        /**
         * The ledger entries handed to the verifier model: each becomes an excerpt ([EvidenceLedger.excerpt]), lines matching
         * answer facts are always kept, and no entry is dropped. First [EvidenceLedger.EXCERPT_CONTEXT] lines on each side are
         * taken; if the total exceeds [budget] the number of surrounding lines is reduced, and if it still exceeds, each entry
         * is clipped to an equal share (at least [EvidenceLedger.MIN_EXCERPT] characters).
         */
        internal fun excerpts(goal: String, answer: String, chosen: List<Observation>, budget: Int, today: java.time.LocalDate? = null): List<Observation> {
            val hits = chosen.map { Grounding.hitLines(goal, answer, it.text, today) }
            for (ctx in EvidenceLedger.EXCERPT_CONTEXT downTo 0) {
                val ex = chosen.mapIndexed { i, r -> EvidenceLedger.excerpt(r.text, hits[i], ctx) }
                if (ex.sumOf { it.length } <= budget) return chosen.mapIndexed { i, r -> r.copy(text = ex[i]) }
            }
            val cap = maxOf(EvidenceLedger.MIN_EXCERPT, budget / chosen.size.coerceAtLeast(1))
            return chosen.mapIndexed { i, r -> r.copy(text = EvidenceLedger.excerpt(r.text, hits[i], 0, cap)) }
        }

        /** The fresh look handed to the verifier model: unchanged when short, otherwise the start plus the lines matching answer facts. */
        private fun freshShown(goal: String, answer: String, e: Evidence): String {
            val t = e.freshText
            if (t.length <= FRESH_CHARS) return t
            return EvidenceLedger.excerpt(t, Grounding.hitLines(goal, answer, t, e.today), 1, FRESH_CHARS, withHead = true)
        }

        /**
         * The backstop after the model judges pass (rules in [Grounding.check]): only checks the literal level and does not judge
         * which thing a value belongs to. When the text does not match: if a screenshot was attached and what did not match was
         * only on-screen state (quotations without digits; quotations also require that elsewhere in the answer they were matched
         * in text), the pass that saw the image counts, recorded as verified by image; numbers and dates can be written as text,
         * so a mismatch vetoes.
         * [today] is the runtime clock's date (relative dates are resolved with it, see [Grounding.check]). Recomputing a recorded
         * run offline uses it too.
         */
        internal fun backstop(v: Verification, goal: String, answer: String, screens: List<String>, imageSent: Boolean, today: java.time.LocalDate? = null): Verification {
            val g = Grounding.check(goal, answer, screens, today)
            val ok = when (g.found) {
                true -> true
                false -> false
                // Nothing checkable in the answer, or only numbers matched with no quotation to locate: at least one met criterion's quotation must really be on screen
                null -> v.checks.any { it.status == "met" && it.quote != null && Grounding.quoteInReads(it.quote, screens) }
            }
            val missing = g.unmatched.map { it.text }
            val result = when {
                ok -> v
                imageSent && g.unmatched.all { it.visualOnly } && (g.anchored || g.unmatched.none { it.kind == Grounding.Kind.Quote }) ->
                    v.copy(groundedBy = IMAGE, notFound = missing)
                else -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_IN_READS, notFound = missing)
            }
            // The basis is truncated on-screen text (ending with …): only part of it was seen, so it cannot be fully verified
            return if (result.verdict == Verdict.Pass && Grounding.builtOnTruncated(answer, screens)) result.copy(verdict = Verdict.CannotVerify, reason = TRUNCATED) else result
        }

        /**
         * The first readable JSON object in the reply (see [ModelJson]: tolerates code fences, surrounding prose and key-name
         * spellings); null when the verdict is missing or unrecognized.
         * When the individual checks contradict the overall verdict, the individual checks win.
         */
        internal fun parse(text: String): Verification? {
            val obj = ModelJson.firstObject(text) ?: return null
            val verdict = when ((obj.field("verdict", "result", "conclusion") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
                "pass", "passed" -> Verdict.Pass
                "fail", "failed" -> Verdict.Fail
                "cannot_verify", "cannotverify", "unknown" -> Verdict.CannotVerify
                else -> return null
            }
            val checks = (obj.field("checks", "criteria_checks", "results") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val criterion = (o.field("criterion", "criteria", "item") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val status = (o.field("status", "state") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it in STATUSES } ?: "unknown"
                val quote = (o.field("quote", "evidence") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
                CriterionCheck(criterion, status, quote)
            }
            val reason = (obj.field("reason", "explanation") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val effective = when {
                checks.any { it.status == "unmet" } -> Verdict.Fail
                verdict == Verdict.Pass && checks.any { it.status == "unknown" } -> Verdict.CannotVerify
                else -> verdict
            }
            return Verification(effective, checks, reason)
        }
    }
}

