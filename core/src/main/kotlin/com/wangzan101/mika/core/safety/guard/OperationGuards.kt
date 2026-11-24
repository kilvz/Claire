package com.wangzan101.mika.core.safety.guard

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

enum class Severity(val wire: String) { Warn("warn"), Stop("stop") }

/**
 * One verdict from the guard. A nudge ([Severity.Warn]) carries [nudge]: a paragraph (sent to the model) that states where
 * it is stuck and offers a few ways out; a stop has none.
 */
data class GuardVerdict(val code: String, val severity: Severity, val evidence: JsonObject, val nudge: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code); put("severity", severity.wire); put("evidence", evidence)
        nudge?.let { put("nudge", it) }
    }
}

/** Stable serialization with sorted keys, so argument order does not affect the signature. */
private fun stable(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${stable(e[it]!!)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { stable(it) }
    else -> e.toString()
}

private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val SWIPE_KEYS = listOf("startX", "startY", "endX", "endY")

/** Action signature: reason is ignored, coordinates are floored to multiples of 50, and swipes only record a direction. */
fun actionSignature(tool: String, args: JsonObject): String {
    val n = args.toMutableMap()
    n.remove("reason")
    val x = num(n["x"]); val y = num(n["y"])
    if (x != null && y != null) {
        n["x"] = JsonPrimitive(floor(x / 50).toLong())
        n["y"] = JsonPrimitive(floor(y / 50).toLong())
    }
    val s = SWIPE_KEYS.map { num(n[it]) }
    if (s.all { it != null }) {
        val dx = s[2]!! - s[0]!!; val dy = s[3]!! - s[1]!!
        val vertical = abs(dy) >= abs(dx)
        n["swipeDirection"] = JsonPrimitive("${if (vertical) "V" else "H"}${if ((if (vertical) dy else dx) < 0) "-" else "+"}")
        SWIPE_KEYS.forEach { n.remove(it) }
    }
    return "$tool|${stable(JsonObject(n))}"
}

/**
 * Operation guard, one per task. It only nudges and eventually stops when actions really have no effect, repeated reads
 * bring no new content, or tools fail consecutively. Identical clicks/scrolling and already-seen screen fingerprints do not
 * by themselves mean stagnation; a new page or newly read content clears the no-progress counters.
 */
class OperationGuards(private val stepBudget: Int? = null) {

    private var frameId: String? = null
    private var frameHash: Long? = null
    private var acted = false
    /** The previous action already recorded whether it had an effect based on the effect it reported: the following screen is no longer recorded again from its fingerprint. */
    private var effectNoted = false

    /** Number of consecutive actions with no effect. */
    private var unchanged = 0
    private var unchangedWarnedAt = 0
    /** How many actions in the current no-effect run were taps made when the tree could not reveal the operated position; scrolling to the bottom on its own does not borrow this evidence to stop early. */
    private var treeBlindActions = 0

    /** Every interface state visited (element-list fingerprint). */
    private val seenStates = HashSet<Long>()
    /** Since the last new state was reached, the number of returns to each state and the threshold already notified for it. */
    private val visits = HashMap<Long, Int>()
    private val visitsWarnedAt = HashMap<Long, Int>()
    /** The states seen so far (adjacent duplicates removed), the latest few. */
    private val trail = ArrayDeque<Long>()
    private var cycleWarnedAt = 0
    /** How many actions have been performed since the last new state was reached. */
    private var sinceNewState = 0
    private var sinceNewWarnedAt = 0

    /** The task's counters; [epoch] increments on every screen change and the per-scope read counts are invalidated by it. */
    private val reads = GuardScope(canStop = true)
    /** Remembers read content across pages; returning to the same page does not treat old content as new progress on the task. */
    private val taskReads = HashSet<Long>()
    private var epoch = 0

    private var consecutiveFailures = 0
    private var unrecoverableFailures = 0
    private val recentFailures = ArrayDeque<JsonObject>()

    var stop: GuardVerdict? = null
        private set
    val unchangedFrames: Int get() = unchanged
    var progressVersion: Long = 0
        private set

    /** A nudge carries where it is stuck (the guard.<code> line, filled with [count]) and ways out; a stop carries neither. */
    private fun verdict(code: String, severity: Severity, evidence: JsonObject, count: Int, waysOut: Boolean = true, period: Int? = null): List<GuardVerdict> {
        val nudge = if (severity == Severity.Warn) listOfNotNull(
            if (period != null) Feedback.format(LEADS.getValue(code), "count" to count, "states" to period) else Feedback.format(LEADS.getValue(code), "count" to count),
            Feedback["guard.ways_out"].takeIf { waysOut },
        ).joinToString(" ") else null
        val v = GuardVerdict(code, severity, evidence, nudge)
        if (severity == Severity.Stop) stop = v
        return listOf(v)
    }

    private fun <T> ArrayDeque<T>.pushCapped(v: T, cap: Int = 10) { addLast(v); if (size > cap) removeFirst() }

    /** Kept as an entry point; action signatures take no part in stagnation detection. */
    fun beforeAction(tool: String, args: JsonObject, diagnostic: Boolean = false, scope: GuardScope = reads): List<GuardVerdict> {
        // The same action can read new content page by page; the action signature alone cannot indicate task stagnation.
        return emptyList()
    }

    /** An action that changes the interface is about to be performed. */
    fun noteAction() {
        acted = true
        effectNoted = false
        sinceNewState += 1
    }

    /**
     * The actual effect the action reports back (the kind of effect, see core.uistate.EffectKind): no_effect records one
     * "no effect"; a clear change, an app switch or a page switch restarts the count; a small local change also restarts it.
     * With two [treeBlind] actions in the same no-effect run it nudges first and stops at three in total: even the screen shows
     * no visible change, so hidden state cannot be probed through the control tree and it should not keep trying other
     * coordinates. With only one piece of blank-tree evidence it still follows the normal ladder, and does not treat earlier
     * unrelated no-effects such as scrolling to the bottom as proof that "injecting gets no response".
     */
    fun effect(kind: String, treeBlind: Boolean = false): List<GuardVerdict> {
        effectNoted = true
        when (kind) {
            NO_EFFECT -> {
                unchanged += 1
                if (treeBlind) treeBlindActions += 1
            }
            else -> { resetNoEffect(); return emptyList() }
        }
        return noProgress()
    }

    private fun resetNoEffect() {
        unchanged = 0
        unchangedWarnedAt = 0
        treeBlindActions = 0
    }

    private fun noProgress(): List<GuardVerdict> {
        if (treeBlindActions >= 2) {
            return verdict("no_progress", if (unchanged >= 3) Severity.Stop else Severity.Warn, buildJsonObject {
                put("unchanged", unchanged); put("treeBlind", true); put("treeBlindActions", treeBlindActions)
            }, unchanged)
        }
        val rung = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, canStop = true) ?: return emptyList()
        unchangedWarnedAt = rung.threshold
        return verdict("no_progress", rung.severity, buildJsonObject { put("unchanged", unchanged) }, unchanged)
    }

    /**
     * The model received an interface (a read, or one included in an action result) and [hash] is its fingerprint. Only
     * "the interface is still the same after acting" counts as no effect (an action that already reported its effect is not
     * recorded again); merely looking repeatedly does not count. Which state the action arrived at is also used to detect
     * returning to the same state, cycling, and never reaching a new state.
     */
    fun frame(id: String, hash: Long): List<GuardVerdict> {
        if (frameId == id) return emptyList()
        val same = frameHash == hash
        val didAct = acted
        val noted = effectNoted
        acted = false
        effectNoted = false
        frameId = id
        frameHash = hash
        val out = mutableListOf<GuardVerdict>()
        if (!same) {
            pageChanged()
            if (trail.lastOrNull() != hash) {
                trail.addLast(hash)
                while (trail.size > TRAIL_KEEP) trail.removeFirst()
            }
            if (seenStates.add(hash)) progress() else out += arrived(hash)
            out += cycle()
        }
        if (didAct && !noted) {
            if (same) { unchanged += 1; out += noProgress() } else resetNoEffect()
        }
        // An old interface does not prove failure by itself; only nudge the model to assess whether there is new content or to change route.
        if (didAct) out += noNewState()
        return out
    }

    /** The action returned to a state already visited. While cycling, [cycle] reports it and this does not duplicate. */
    private fun arrived(hash: Long): List<GuardVerdict> {
        val n = (visits[hash] ?: 1) + 1
        visits[hash] = n
        if (GuardRules.cycle(trail.toList()) != null) return emptyList()
        val rung = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), visitsWarnedAt[hash] ?: 0, canStop = true) ?: return emptyList()
        visitsWarnedAt[hash] = rung.threshold
        return verdict("same_state", rung.severity, buildJsonObject { put("visits", n) }, n)
    }

    private fun cycle(): List<GuardVerdict> {
        val c = GuardRules.cycle(trail.toList())
        if (c == null) { cycleWarnedAt = 0; return emptyList() }
        val rung = GuardRules.climb(c.rounds, GuardRules.cycleLadder(stepBudget), cycleWarnedAt, canStop = true) ?: return emptyList()
        cycleWarnedAt = rung.threshold
        return verdict("state_cycle", rung.severity, buildJsonObject { put("states", c.period); put("rounds", c.rounds) }, c.rounds, period = c.period)
    }

    private fun noNewState(): List<GuardVerdict> {
        val rung = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, canStop = false) ?: return emptyList()
        sinceNewWarnedAt = rung.threshold
        return verdict("no_new_state", rung.severity, buildJsonObject { put("actions", sinceNewState) }, sinceNewState)
    }

    /**
     * One screen read / page read / web script run: reading repeatedly while the screen is unchanged and no effective action
     * was taken yields nothing new (what is being looked for is not on this page).
     * [fresh] true means this read returned content not read before (e.g. reading a whole page from a different start), so it
     * does not count as a re-read. Reading with a different tool or a different script also counts: if the page has not
     * changed, the answer is what the page already shows.
     * When [window] is given the count goes into it separately (reads within one page-script call) without touching the
     * task's counters.
     */
    fun read(tool: String, fresh: Boolean, window: GuardScope = reads, fingerprint: Long? = null): List<GuardVerdict> {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress()
            return emptyList()
        }
        window.sync(epoch)
        window.rereads += 1
        val n = window.rereads
        val rung = GuardRules.climb(n, GuardRules.REREAD_LADDER, window.warnedAt, window.canStop) ?: return emptyList()
        window.warnedAt = rung.threshold
        return verdict("read_loop", rung.severity, buildJsonObject {
            put("tool", tool); put("rereadsOfUnchangedPage", n)
        }, n)
    }

    /** Whether this read result is the first time it has been read since the screen last changed. */
    fun firstSeen(fingerprint: Long, window: GuardScope = reads): Boolean {
        window.sync(epoch)
        return window.seen.add(fingerprint)
    }

    /** The screen changed (or an effective action was taken): earlier reads no longer count (all read counters are invalidated together). */
    fun pageChanged() {
        epoch += 1
    }

    private fun progress() {
        progressVersion += 1
        resetNoEffect()
        sinceNewState = 0
        sinceNewWarnedAt = 0
        if (stop?.code == "no_progress" || stop?.code == "read_loop") stop = null
    }

    fun result(tool: String, content: JsonObject, neutral: Boolean = false): List<GuardVerdict> {
        val ok = (content["ok"] as? JsonPrimitive)?.booleanOrNull
        val countsAsFailure = (content["counts_as_failure"] as? JsonPrimitive)?.booleanOrNull
        if (neutral || countsAsFailure == false || ok != false) {
            consecutiveFailures = 0
            unrecoverableFailures = 0
            return emptyList()
        }
        val recoverable = (content["recoverable"] as? JsonPrimitive)?.booleanOrNull != false
        consecutiveFailures += 1
        if (!recoverable) unrecoverableFailures += 1
        recentFailures.pushCapped(buildJsonObject {
            put("tool", tool); put("recoverable", recoverable)
            (content["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("error", it.content) }
        })
        val severity = GuardRules.failures(consecutiveFailures, unrecoverableFailures) ?: return emptyList()
        return verdict("too_many_failures", severity, buildJsonObject {
            put("consecutive", consecutiveFailures)
            put("unrecoverable", unrecoverableFailures)
            put("recent", JsonArray(recentFailures.toList().takeLast(5)))
        }, consecutiveFailures, waysOut = consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0)
    }

    private companion object {
        const val NO_EFFECT = "no_effect"
        /** How many recent states to keep (enough to see the longest cycle safety net). */
        const val TRAIL_KEEP = 64

        /** The opening line of each nudge that says where it is stuck (prompts/feedback/tools.md). */
        val LEADS = mapOf(
            "repeating_action" to "guard.repeating_action", "no_progress" to "guard.no_progress", "same_state" to "guard.same_state",
            "state_cycle" to "guard.state_cycle", "no_new_state" to "guard.no_new_state", "read_loop" to "guard.read_loop",
            "too_many_failures" to "guard.too_many_failures",
        )
    }
}

/**
 * One counter scope: the latest action signatures (repeated operations), the result fingerprints already read, and the number
 * of times an unchanged page was read again.
 * With [canStop] false it only nudges and never stops the task. Read counters are invalidated and restarted when the screen
 * changes ([OperationGuards.pageChanged]).
 */
class GuardScope(val canStop: Boolean) {
    internal val loop = ArrayDeque<String>()
    internal var loopWarnedAt = 0
    internal val seen = HashSet<Long>()
    internal var rereads = 0
    internal var warnedAt = 0
    private var epoch = 0

    internal fun sync(now: Int) {
        if (now == epoch) return
        epoch = now
        seen.clear(); rereads = 0; warnedAt = 0
    }
}
