package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.TokenEstimate
import com.wangzan101.mika.core.agent.StepSummary
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** [estimate]: a rough (uncalibrated) token estimate of [messages], used to compare against real usage after sending. [rewritten]: whether this call rewrote the request prefix. */
data class Compressed(
    val messages: List<Message>,
    val foldedSteps: Int,
    val estimate: Int = TokenEstimate.of(messages),
    val rewritten: Boolean = false,
)

/**
 * The request is partitioned by cache: section 0 system prompt and tools (unchanged within a task) → section 1 opening context (written once) → section 2 history (append-only) → section 3 tail
 * (this step's results, screens, runtime state). Providers cache by prefix, so messages already sent are normally always resent as-is.
 *
 * Compact once, only when the size of the whole request ([UsageMeter]: the latest real usage + the estimates after it) crosses the trigger point ([ContextLimits.trigger]):
 * first shorten old results and old images before the most recent [keepRecent] tool round trips
 * ([HistoryPruner.prune]), and if it is still over, fold those round trips into one deterministic "earlier steps" summary (no model call); the task's own words, steers, reference material,
 * and the hand-back and follow-up round trips are not folded. Compaction must get below [ContextLimits.target] (if it cannot, use the lowest level it can reach),
 * and later requests = the result of this compaction + messages appended since — append-only, until the trigger point is crossed again (at least one more [ContextLimits.margin] of growth after compacting).
 * Round trips are deleted as whole blocks, so call/result pairing always stays complete.
 * Only a copy is returned; the caller's full history is unchanged. One instance per task; state is recorded separately for each context size.
 *
 * [structured]: the summary is written as "goal / confirmed facts / progress / next step" ([StepSummary]).
 * [diffScreens]: action results carry a diff screen (inlineScreen).
 */
class ContextCompressor(
    /** Looks up the action performed by a called tool name. */
    private val tools: ToolIndex,
    private val keepRecent: Int = 12,
    private val structured: Boolean = false,
    private val diffScreens: Boolean = false,
    private val triggerOverride: Int? = null,
    /** Trigger by round count: when live tool round trips exceed [keepRecent] + [foldEvery], fold the earliest into the summary (null = not by round count, by size only, i.e. the current behavior). */
    private val foldEvery: Int? = null,
) {
    /** The "actual / rough estimate" ratio: smoothly updated each time usage comes back ([TokenEstimate.calibrated]); it only decides when to compact and which level to compact to, never changes what has been sent. */
    private var observed: Double? = null
    /**
     * The ratio the estimate is multiplied by. Real usage is only used to raise this conservative lower bound and never to revise later increments downward:
     * the usage of an ordinary prompt being low does not mean the punctuation-heavy JSON appended afterwards has the same characters/token ratio.
     */
    internal val scale: Double get() = observed?.coerceAtLeast(UNCALIBRATED_SCALE) ?: UNCALIBRATED_SCALE

    /** The previous compaction: the beginning of the full history it covers [covered], the compacted result [base] (rough estimate [estimate]); [aggressive]: compacted at the level used when the model reports an overflow. */
    private class View(val covered: List<Message>, val base: List<Message>, val folded: Int, val aggressive: Boolean) {
        val estimate = TokenEstimate.of(base)
    }
    private val views = HashMap<Int, View>()

    /** After one request is sent: [estimated] is the rough estimate of that request (including fixed overhead), [actual] is the input token count the provider reports. */
    fun calibrate(estimated: Int, actual: Int) {
        observed = TokenEstimate.calibrated(observed, estimated, actual)
    }

    /** New task: clear the compaction state (keep the calibration ratio, since it is the same model). */
    fun forget() = views.clear()

    private fun tokens(ms: List<Message>) = TokenEstimate.of(ms) * scale

    /** The messages that would actually be sent after the last compaction (the full history when nothing has been compacted); the caller uses this to compute the current context size. */
    fun current(history: List<Message>, contextTokens: Int): List<Message> =
        sent(valid(views[contextTokens], history), history)

    /**
     * [currentTokens]: the size of the whole request (including fixed overhead, see [UsageMeter]); when null, estimate in full from [current] plus [fixedOverhead]. Compacts only when the trigger point is crossed.
     * [aggressive]: the model has already reported an overlong context, so ignore the estimate and compact once keeping only the most recent [AGGRESSIVE_KEEP] round trips (afterwards still append-only).
     * [fixedOverhead]: the estimate of the part that must be sent every time but is not in [history] (base prompt, tool definitions) (not multiplied by the ratio);
     * it is used only when estimating the whole request and when computing how much room the messages can take; the trigger point itself does not subtract it.
     * [maxImages]: how many images the provider accepts in one request at most (images already sent are normally not replaced by placeholders; only beyond that limit are the earliest ones replaced).
     */
    fun compress(
        history: List<Message>,
        contextTokens: Int,
        currentTokens: Int? = null,
        aggressive: Boolean = false,
        fixedOverhead: Int = 0,
        maxImages: Int = Int.MAX_VALUE,
    ): Compressed {
        val limits = ContextLimits.of(contextTokens, triggerOverride)
        val last = views[contextTokens]
        val view = valid(last, history)
        val sent = sent(view, history)
        val size = currentTokens ?: UsageMeter.of(sent, fixedOverhead, scale)
        // Hysteresis: after compacting, at least one more margin of growth is needed before compacting again, and a history that cannot be brought below the trigger point is not compacted on every step either; but it must not cross the usable window, because once it does every step must compact
        val trigger = view?.let {
            maxOf(limits.trigger, UsageMeter.estimate(it.estimate + fixedOverhead, scale) + limits.margin).coerceAtMost(limits.usable)
        } ?: limits.trigger
        val compact = (last != null && view == null) || (aggressive && view?.aggressive != true) || size > trigger
        // Round-count trigger: only to control the length of the history. When the size has not exceeded the limit and the model has not reported an overflow, only the earliest excess round trips are folded into the summary (see [foldEvery]);
        // size/overflow still follow the ladder below. After compacting, the history is back to keepRecent rounds and then only grows by appending, until it has accumulated foldEvery more rounds before folding again (sawtooth, not compacting every step).
        val roundOver = foldEvery != null && toolRounds(sent) > keepRecent + foldEvery
        if (!compact && !roundOver) return Compressed(HistoryPruner.limitImages(sent, maxImages), view?.folded ?: 0)
        val next = if (compact) compact(history, limits, fixedOverhead, aggressive) else foldToRecent(history)
        views[contextTokens] = next
        return Compressed(HistoryPruner.limitImages(next.base, maxImages), next.folded, rewritten = true)
    }

    /**
     * The model reported an overlong context: compact once more from the full history at the aggressive level (also re-compact if the history has grown since the last aggressive compaction).
     * Only if the result is smaller than what was just sent is it swapped in and true returned (the caller resends once based on this); the same size means it cannot be compacted further, so false is returned.
     */
    fun overflow(history: List<Message>, contextTokens: Int, fixedOverhead: Int = 0): Boolean {
        val current = sent(valid(views[contextTokens], history), history)
        val next = compact(history, ContextLimits.of(contextTokens, triggerOverride), fixedOverhead, aggressive = true)
        if (next.estimate >= TokenEstimate.of(current)) return false
        views[contextTokens] = next
        return true
    }

    /** Keep using it only if the stretch of history the compaction covers has not been modified; otherwise (which does not happen in the normal flow) compact again. */
    private fun valid(v: View?, history: List<Message>) = v?.takeIf { it.covered.size <= history.size && history.subList(0, it.covered.size) == it.covered }

    /** Always a copy: the caller's history will be appended to afterwards. */
    private fun sent(view: View?, history: List<Message>) =
        if (view == null) history.toList() else view.base + history.subList(view.covered.size, history.size)

    /**
     * One compaction, from light to heavy, taking the first level that brings the whole request below [ContextLimits.target] (if none does, taking the heaviest):
     * shorten only the old results before the most recent [keepRecent] round trips → then fold them into the summary → keep only the most recent round trip as-is and shorten-then-fold the rest,
     * with the round trips kept as-is being [keepRecent], [AGGRESSIVE_KEEP], 1 in turn. When the model has reported an overflow, start from [AGGRESSIVE_KEEP].
     * If at the heaviest level the whole request still exceeds [ContextLimits.usable] (the recent results themselves are too large), shorten large results starting from the newest until it fits.
     * The comparison is on the message estimate: fixed overhead is subtracted from the target and the usable window first (with the same ratio), so fixed overhead is counted only once here.
     */
    private fun compact(history: List<Message>, limits: ContextLimits, fixedOverhead: Int, aggressive: Boolean): View {
        val overhead = UsageMeter.estimate(fixedOverhead, scale)
        val target = limits.target - overhead
        val window = limits.usable - overhead
        val ladder = sequence {
            if (!aggressive) {
                val pruned = HistoryPruner.prune(history, tools, HistoryPruner.Cut(keepRecent))
                yield(Compressed(pruned, 0))
                yield(fold(pruned, keepRecent, false))
            }
            val last = HistoryPruner.prune(history, tools, HistoryPruner.LAST)
            for (keep in listOfNotNull(keepRecent.takeUnless { aggressive }, AGGRESSIVE_KEEP, 1).distinct()) yield(fold(last, keep, aggressive))
        }
        var chosen = Compressed(history, 0)
        for (c in ladder) {
            chosen = c
            if (tokens(c.messages) <= target) break
        }
        if (tokens(chosen.messages) > window) chosen = chosen.copy(messages = HistoryPruner.shrinkOversized(chosen.messages) { tokens(it) <= window })
        // A copy: when nothing was trimmed, chosen may be the caller's own history that will still be appended to
        return View(history.toList(), chosen.messages.toList(), chosen.foldedSteps, aggressive)
    }

    /** Only to control round count: the size is not over and the model has not reported an overflow, so fold the earliest excess round trips into the summary and keep the most recent [keepRecent] as-is (no target-driven whole-block shortening, and no switching to a heavier level). */
    private fun foldToRecent(history: List<Message>): View {
        val pruned = HistoryPruner.prune(history, tools, HistoryPruner.Cut(keepRecent))
        val folded = fold(pruned, keepRecent, aggressive = false)
        return View(history.toList(), folded.messages.toList(), folded.foldedSteps, aggressive = false)
    }

    /** The number of round trips in a stretch of messages in which the model initiated tool calls (the folded summary has no tool calls and is not counted). */
    private fun toolRounds(ms: List<Message>) = ms.count { it.role == Role.Assistant && it.parts.any { p -> p is Part.ToolCall } }

    private fun fold(pruned: List<Message>, keep: Int, aggressive: Boolean): Compressed {
        // One round trip = one assistant message plus the messages after it up to the next assistant message; what comes before that is the head (task's own words, criteria, etc.)
        val starts = pruned.indices.filter { pruned[it].role == Role.Assistant }
        if (starts.isEmpty()) return Compressed(pruned, 0)
        val toolStarts = starts.filter { i -> pruned[i].parts.any { it is Part.ToolCall } }
        if (toolStarts.size <= keep) return Compressed(pruned, 0)
        val cut = toolStarts[toolStarts.size - keep]
        // Screens carried by actions: the most recent full screen read before the folding boundary is not folded, since the diffs kept afterwards mostly attach to it (the diff reads in between were already replaced by placeholders during shortening; under aggressive compaction they are folded anyway and the dispatcher sends a full screen afterwards);
        val rounds = starts.mapIndexed { k, start -> pruned.subList(start, starts.getOrElse(k + 1) { pruned.size }) }
        val screenRounds = rounds.map(::screenKind)
        val before = starts.count { it < cut }
        // When the screens in the kept round trips are self-explanatory (see selfContained), the full screen before the boundary is not needed
        val anchor = if (aggressive || !diffScreens || selfContained(pruned.subList(cut, pruned.size))) -1
            else (0 until before).lastOrNull { screenRounds[it] == ScreenKind.FULL } ?: -1

        val out = ArrayList<Message>(pruned.subList(0, starts.first()))
        // Each run of consecutive folded round trips gets its own summary, placed where that run was; the round trips kept in between separate the runs.
        // In structured mode there is only one summary, placed at the position of the first run, and the steps of each run are merged into it in order
        var block: MutableList<String>? = null
        var blockAt = 0
        var folded = 0
        var steps: StepSummary? = null
        var stepsAt = 0
        fun closeBlock() {
            if (!structured) block?.takeIf { it.isNotEmpty() }?.let { out.add(blockAt, summary(it)) }
            block = null
        }
        starts.takeWhile { it < cut }.forEachIndexed { k, start ->
            val end = starts.getOrElse(k + 1) { pruned.size }
            val round = pruned.subList(start, end)
            // A round executes at most one action, but a mistakenly written task_progress call may come before it:
            // decide whether to keep the round by the action actually executed, otherwise the round would be folded together with the user's answer.
            val call = round.first().parts.firstActionCall()
            if (tools.verb(call?.name) in PINNED_VERBS || (anchor >= 0 && k >= anchor && screenRounds[k] != ScreenKind.NONE)) {
                closeBlock()
                out += round
                return@forEachIndexed
            }
            val lines = block ?: mutableListOf<String>().also { block = it; blockAt = out.size }
            out += round.filter { isPinnedContext(it) || it.author == Author.User }.map(ContextBuilder::withoutRuntimeState)
            if (call != null) {
                lines += line(k + 1, call, round)
                folded++
                if (structured) {
                    if (steps == null) stepsAt = blockAt
                    steps = (steps ?: StepSummary(goal(pruned.subList(0, starts.first())))).next(lines.last(), okResults(round), said(round))
                }
            }
        }
        closeBlock()
        steps?.let { out.add(stepsAt, notice(it.render(HEADER))) }
        out += pruned.subList(cut, pruned.size)
        return Compressed(out, folded)
    }

    private enum class ScreenKind { NONE, FULL, DIFF }

    /** Whether this round's results contain a screen: a full element list from observe/list_elements or screen.full being true counts as full, any other screen counts as a diff. */
    private fun screenKind(round: List<Message>): ScreenKind {
        val verbs = round.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().associate { it.id to tools.verb(it.name) }
        var kind = ScreenKind.NONE
        for (r in round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"elements\":[" in r.content || "\"${ScreenElementTable.KEY}\":[" in r.content || "\"screen\":{" in r.content) parse(r.content) else null) ?: continue
            val screen = obj["screen"] as? JsonObject
            when {
                verbs[r.callId] in OBSERVATIONS && ScreenElementTable.hasRows(obj) -> return ScreenKind.FULL
                screen != null && screen["full"] == JsonPrimitive(true) -> return ScreenKind.FULL
                screen != null -> kind = ScreenKind.DIFF
            }
        }
        return kind
    }

    /**
     * The screens in [kept] are understandable without relying on earlier screen reads: at least one screen has not been shortened, and the `base` of every diff is among them.
     * Matching by base works for both phone and web diff chains; a screen shortened to a placeholder does not count (the model cannot see its content).
     */
    private fun selfContained(kept: List<Message>): Boolean {
        val ids = HashSet<String>()
        val bases = HashSet<String>()
        for (r in kept.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()) {
            val obj = (if ("\"snapshotId\"" in r.content) parse(r.content) else null) ?: continue
            val screens = listOfNotNull(obj.takeIf(ScreenElementTable::hasRows), obj["screen"] as? JsonObject)
            for (s in screens) {
                (s["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                (s["base"] as? JsonPrimitive)?.contentOrNull?.let(bases::add)
            }
        }
        return ids.isNotEmpty() && ids.containsAll(bases)
    }

    private fun summary(lines: List<String>) = notice((listOf(HEADER, SUMMARY_REMINDER) + lines).joinToString("\n"))

    private fun notice(text: String) = Message(Role.User, listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, SUMMARY_SOURCE, text))), Author.Mika)

    /** The user's own words in the head (the one not inside a context envelope). */
    private fun goal(head: List<Message>): String = head.firstOrNull { it.author == Author.User }
        ?.parts?.filterIsInstance<Part.Text>()?.firstOrNull()?.text?.replace('\n', ' ')?.trim()?.let { if (it.length <= GOAL_LIMIT) it else it.take(GOAL_LIMIT) + "…" }
        .orEmpty()

    /** The successful tool results of this round. */
    private fun okResults(round: List<Message>): List<JsonObject> = round.flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
        .filter { !it.isError }.mapNotNull { parse(it.content) }.filter { (it["ok"] as? JsonPrimitive)?.booleanOrNull != false }

    /** What the model said in this round. */
    private fun said(round: List<Message>): String? =
        round.first().parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }.trim().takeIf { it.isNotEmpty() }

    /** Context kept along with the rounds when folding (reference material, skills, etc., see [ContextKind.pinned]). */
    private fun isPinnedContext(m: Message) = ContextBuilder.kindOf(m)?.pinned == true


    private fun line(step: Int, call: Part.ToolCall, round: List<Message>): String {
        val target = target(parse(call.argsJson))?.let { "(${clip(it)})" }.orEmpty()
        val result = round.firstNotNullOfOrNull { m ->
            m.parts.firstNotNullOfOrNull { (it as? Part.ToolResult)?.takeIf { r -> r.callId == call.id } }
        }
        // A screen read leaves a screenshot id, so it can still be recalled with view_past_screens after folding
        // A write operation's result may also carry a screen with a screenshot id; when there is no screen, the image id attached after the action is at the top level of the result
        val shot = result?.let { parse(it.content) }?.let { o -> if (tools.verb(call.name) in OBSERVATIONS) o["screenshotId"] else (o["screen"] as? JsonObject)?.get("screenshotId") ?: o["screenshotId"] }
            ?.let { it as? JsonPrimitive }?.contentOrNull?.let { " #$it" }.orEmpty()
        return "Step $step ${clip(call.name)}$target$shot → ${outcome(result)}"
    }

    private fun outcome(r: Part.ToolResult?): String {
        r ?: return NO_RESULT
        val obj = parse(r.content)
        if (obj == null) {
            // Not JSON: on error, write the error code if one can be recognised
            val code = r.content.trim().takeIf { CODE.matches(it) }
            return if (r.isError) code ?: FAILED else APPLIED
        }
        fun s(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        return when {
            // Only error codes are kept; explanatory text can be long or short, so it does not go into the summary
            r.isError || (obj["ok"] as? JsonPrimitive)?.booleanOrNull == false -> (s("error") ?: s("reason"))?.takeIf { CODE.matches(it) } ?: FAILED
            (obj["changed"] as? JsonPrimitive)?.booleanOrNull == false -> UNCHANGED
            else -> APPLIED
        }
    }

    private fun target(args: JsonObject?): String? {
        args ?: return null
        TARGET_KEYS.forEach { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it } }
        val x = (args["x"] as? JsonPrimitive)?.contentOrNull
        val y = (args["y"] as? JsonPrimitive)?.contentOrNull
        return if (x != null && y != null) "$x,$y" else null
    }

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= TARGET_LIMIT) one else one.take(TARGET_LIMIT) + "…"
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    companion object {
        const val HEADER = "Earlier steps (compressed):"
        const val SUMMARY_REMINDER = "Only the latest user message is the current task, and the tools are still available; earlier user messages are for reference only."
        private const val SUMMARY_SOURCE = "earlier steps"
        const val AGGRESSIVE_KEEP = 6
        /**
         * The ratio the estimate is multiplied by while there is no real usage to calibrate against. The rough estimate often undershoots for English and JSON (tokenizers split more finely), so it is better to compact a little early than to go overlong first and then compact hard.
         */
        const val UNCALIBRATED_SCALE = 1.2
        private const val APPLIED = "applied"
        private const val UNCHANGED = "no change"
        private const val FAILED = "failed"
        private const val NO_RESULT = "no result"
        private const val TARGET_LIMIT = 20
        private const val GOAL_LIMIT = 120
        private val CODE = Regex("[a-z][a-z0-9_:]{0,63}")
        /** The results of hand-back, follow-up and confirmation (regardless of whether the answer is a tool result or a subsequent user message) are kept in full. */
        private val OBSERVATIONS = setOf(Verb.Observe, Verb.ListElements)
        private val PINNED_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        private val TARGET_KEYS = listOf("target", "text", "package", "query", "direction", "key", "name", "ref", "seconds")
    }
}
