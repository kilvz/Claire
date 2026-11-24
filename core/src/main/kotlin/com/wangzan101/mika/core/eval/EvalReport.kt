package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.run.record.RunStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.ceil

/** Price per million tokens; passed in at runtime, not written into the repository. */
data class Prices(val inputPerM: Double, val outputPerM: Double, val cachedPerM: Double)

/** Run summary markdown. Only metrics and per-task conclusions; no full answers or screenshots (it gets committed to the repository). */
object EvalReport {
    fun markdown(
        run: String,
        model: String,
        scores: List<Pair<TaskScore, Observed>>,
        skipped: List<Pair<String, String>>,
        prices: Prices?,
        /** The combination of switches for this run ([com.wangzan101.mika.core.agent.SpeedFlags.describe]). */
        flags: String = "none",
        /** In parallel evaluation, the time of the whole round from the first task starting to the last task finishing; empty when serial, in which case the row is omitted. */
        totalWallMs: Long? = null,
        /** How many tasks run at once (evalParallel); 1 means serial. */
        parallel: Int = 1,
        /** In replay evaluation (evalVariants), the original tasks run first to record the routes; listed in a separate section and not counted in the metrics. */
        originals: List<Pair<TaskScore, Observed>> = emptyList(),
    ): String {
        val n = scores.size
        val passed = scores.count { it.first.passed }
        // An answer that honestly says it was not accomplished (an outcome was judged) does not count as reported success
        val reportedSuccess = scores.count { Scoring.recorded(it.second).outcome == "succeeded" }
        val falseSuccess = scores.count { it.first.falseSuccess }
        val unverified = scores.count { it.first.unverified }
        val steps = scores.map { it.second.steps.toDouble() }
        val secs = scores.map { it.second.wallMs / 1000.0 }
        val inT = scores.sumOf { it.second.inputTokens }
        val outT = scores.sumOf { it.second.outputTokens }
        val cachedT = scores.sumOf { it.second.cachedTokens }
        val calls = scores.map { it.second.modelCalls.toDouble() }
        val wallT = scores.sumOf { it.second.wallMs }

        val sb = StringBuilder()
        sb.appendLine("# Eval summary $run")
        sb.appendLine()
        sb.appendLine("Model: $model; switches: $flags; ran $n tasks, skipped ${skipped.size}" + (if (parallel > 1) "; $parallel in parallel." else "."))
        sb.appendLine()
        sb.appendLine("## Metrics")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Success rate | ${ratio(passed, n)} |")
        if (totalWallMs != null) sb.appendLine("| Total wall time (s) | ${num(totalWallMs / 1000.0)} |")
        sb.appendLine("| False success rate (of reported successes) | ${ratio(falseSuccess, reportedSuccess)} |")
        sb.appendLine("| False success rate (of all) | ${ratio(falseSuccess, n)} |")
        sb.appendLine("| Unverified successes | $unverified |")
        sb.appendLine("| Unverified and failed | ${scores.count { it.first.failureClass == FailureClass.unverified_wrong }} |")
        // Hit a barrier visible on screen such as a login wall and handed over to the user: listed separately and not counted as accomplished (whether it counts is left to humans)
        scores.mapNotNull { it.first.handedOver }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (kind, c) -> sb.appendLine("| Handed to the user: $kind (not counted as done) | $c |") }
        sb.appendLine("| Median steps | ${num(median(steps))} |")
        sb.appendLine("| Steps P90 | ${num(p90(steps))} |")
        sb.appendLine("| Median duration (s) | ${num(median(secs))} |")
        sb.appendLine("| Duration P90 (s) | ${num(p90(secs))} |")
        sb.appendLine("| Median model calls | ${num(median(calls))} |")
        sb.appendLine("| Model calls P90 | ${num(p90(calls))} |")
        sb.appendLine("| Model wait share | ${percent(scores.sumOf { it.second.modelMs }, wallT)} |")
        sb.appendLine("| Tool execution share | ${percent(scores.sumOf { it.second.toolMs }, wallT)} |")
        val waitT = scores.sumOf { it.second.waitMs }
        // Time waiting for the UI has to be reported by tools; if there is not a single entry it was not measured, so do not write 0%
        sb.appendLine("| Of which waiting for the UI | ${if (waitT == 0L) "(no data yet)" else percent(waitT, wallT)} |")
        // Beyond model and tools: waiting for the user to answer or approve, retry backoff, UI overhead; the three add up to 100%
        val otherT = (wallT - scores.sumOf { it.second.modelMs } - scores.sumOf { it.second.toolMs }).coerceAtLeast(0)
        sb.appendLine("| Other/waiting for the user share | ${percent(otherT, wallT)} |")
        sb.appendLine("| Replay hits | ${scores.sumOf { it.second.replayHits }} |")
        sb.appendLine("| Replay hand-backs | ${scores.sumOf { it.second.replayHandBacks }} |")
        if (scores.any { it.second.replayHits + it.second.replayHandBacks > 0 }) {
            val handedBack = scores.filter { it.second.replayHandBacks > 0 }
            sb.appendLine("| Replay hit rate (tasks that completed the route) | ${ratio(scores.count { it.second.replayHits > 0 }, n)} |")
            sb.appendLine("| Replay hand-back rate (tasks handed back midway) | ${ratio(handedBack.size, n)} |")
            sb.appendLine("| Success rate after hand-back | ${ratio(handedBack.count { it.first.passed }, handedBack.size)} |")
        }
        sb.appendLine("| Input tokens | $inT |")
        sb.appendLine("| Of which cached tokens | $cachedT |")
        sb.appendLine("| Output tokens | $outT |")
        sb.appendLine("| Cache hit rate (cached/input) | ${percent(cachedT, inT)} |")
        val perTask = scores.filter { it.second.inputTokens > 0 }.map { it.second.cachedTokens * 100.0 / it.second.inputTokens }
        sb.appendLine("| Median per-task cache hit rate | ${if (perTask.isEmpty()) "—" else num(median(perTask)) + "%"} |")
        if (prices != null) {
            // Input tokens include the cached part, and the cached part is priced at the cached rate
            val cost = ((inT - cachedT) * prices.inputPerM + cachedT * prices.cachedPerM + outT * prices.outputPerM) / 1_000_000
            sb.appendLine("| Cost | ${num(cost)} |")
        }
        sb.appendLine()

        metricsSection(sb, scores)

        sb.appendLine("## Count by class")
        sb.appendLine()
        val byClass = scores.mapNotNull { it.first.failureClass }.groupingBy { it }.eachCount()
        if (byClass.isEmpty()) sb.appendLine("No failures.")
        else {
            sb.appendLine("| Class | Count |")
            sb.appendLine("|---|---|")
            FailureClass.entries.filter { it in byClass }.forEach { sb.appendLine("| ${it.name} | ${byClass.getValue(it)} |") }
        }
        sb.appendLine()

        sb.appendLine("## Per task")
        sb.appendLine()
        sb.appendLine("| id | Outcome | Passed | Class | Verification | Steps | Seconds | Input tokens | Cache hit rate | Output tokens |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        scores.forEach { (s, o) ->
            // An answer was given and it was judged as not accomplished: write the judged outcome
            val judged = o.judged?.takeIf { Scoring.recorded(o) !== o }?.let { "(judged: $it)" }.orEmpty()
            sb.appendLine("| ${s.id} | ${o.outcome}$judged${s.handedOver?.let { "(handed to the user: $it)" }.orEmpty()} | ${if (s.passed) "yes" else "no"} | ${s.failureClass?.name ?: ""} | ${o.verdict ?: ""} | ${o.steps} | " +
                "${num(o.wallMs / 1000.0)} | ${o.inputTokens} | ${percent(o.cachedTokens, o.inputTokens)} | ${o.outputTokens} |")
        }
        sb.appendLine()

        if (originals.isNotEmpty()) {
            sb.appendLine("## Original tasks (routes only, not counted in the metrics above)")
            sb.appendLine()
            sb.appendLine("| id | Outcome | Passed | Steps | Seconds |")
            sb.appendLine("|---|---|---|---|---|")
            originals.forEach { (s, o) ->
                sb.appendLine("| ${s.id} | ${o.outcome}${s.handedOver?.let { "(handed to the user: $it)" }.orEmpty()} | ${if (s.passed) "yes" else "no"} | ${o.steps} | ${num(o.wallMs / 1000.0)} |")
            }
            sb.appendLine()
        }

        sb.appendLine("## Skipped")
        sb.appendLine()
        if (skipped.isEmpty()) sb.appendLine("None.") else skipped.forEach { (id, why) -> sb.appendLine("- $id: $why") }
        sb.appendLine()

        sb.appendLine("## Apps outside the task scope (recorded only, not counted as failures)")
        sb.appendLine()
        val drift = scores.filter { it.first.leftScope.isNotEmpty() }
        if (drift.isEmpty()) sb.appendLine("None.") else drift.forEach { (s, _) -> sb.appendLine("- ${s.id}: ${s.leftScope.joinToString(", ")}") }
        sb.appendLine()

        sb.appendLine("## Test data markers left behind (need manual cleanup)")
        sb.appendLine()
        val markers = (originals + scores).mapNotNull { (s, _) -> s.marker?.let { s.id to it } }
        if (markers.isEmpty()) sb.appendLine("None.") else markers.forEach { (id, m) -> sb.appendLine("- $id: $m") }
        return sb.toString()
    }

    /**
     * The "run summary" section: tasks that have a run record are aggregated by [RunMetrics] (first feedback, number of round trips, where time went; sub-steps inside a batch are not counted twice),
     * followed by one row per task. When no task has a record the section is omitted.
     */
    private fun metricsSection(sb: StringBuilder, scores: List<Pair<TaskScore, Observed>>) {
        val rows = scores.mapNotNull { (s, o) -> o.metrics?.let { s.id to it } }
        if (rows.isEmpty()) return
        val st = RunStats.of(rows.map { it.second })
        sb.appendLine("## Run summary")
        sb.appendLine()
        sb.appendLine("Computed from the run records (${rows.size} tasks): time is counted from when Claire starts processing; reads and actions follow the kind the execution entry gives, and sub-steps inside a batch are not counted twice.")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Median time (s) | ${num(st.medianTotalMs / 1000.0)} |")
        sb.appendLine("| Median first feedback (s) | ${st.medianFirstFeedbackMs?.let { num(it / 1000.0) } ?: "—"} |")
        sb.appendLine("| Average model round trips | ${num(Math.round(st.avgModelCalls * 10) / 10.0)} |")
        sb.appendLine("| Model thinking share | ${percent(st.modelMs, st.totalMs)} |")
        sb.appendLine("| Screen reading share | ${percent(st.observeMs, st.totalMs)} |")
        sb.appendLine("| Action share | ${percent(st.actMs, st.totalMs)} |")
        sb.appendLine("| Other (waiting, retrying) share | ${percent(st.otherMs, st.totalMs)} |")
        sb.appendLine()
        sb.appendLine("| id | Seconds | First feedback (s) | Round trips | All calls | Model (s) | Read (s) | Act (s) | Other (s) | Reads / actions | tokens |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEach { (id, m) ->
            sb.appendLine("| $id | ${secs(m.totalMs)} | ${m.firstFeedbackMs?.let(::secs) ?: "—"} | ${m.modelCalls} | ${m.allCalls ?: "—"} | " +
                "${secs(m.modelMs)} | ${secs(m.observeMs)} | ${secs(m.actMs)} | ${secs(m.otherMs)} | ${m.observeCalls} / ${m.actCalls} | ${m.tokens} |")
        }
        sb.appendLine()
    }

    /** The per-task run summary as a JSON array (metrics.json in the evaluation results directory), each item being `id` and `metrics`; tasks without a run record are not listed. */
    fun metricsJson(scores: List<Pair<TaskScore, Observed>>): String = JsonArray(scores.mapNotNull { (s, o) ->
        o.metrics?.let { m -> JsonObject(mapOf("id" to JsonPrimitive(s.id), "metrics" to METRICS_JSON.encodeToJsonElement(RunMetrics.serializer(), m))) }
    }).toString()

    private val METRICS_JSON = Json { encodeDefaults = true }

    private fun secs(ms: Long) = num(Math.round(ms / 100.0) / 10.0)

    /** Gate verdict: when [passed] is false, [reasons] lists what was not met. */
    data class Gate(val passed: Boolean, val reasons: List<String>)

    /**
     * The gate for paired tasks: the round run with the learned skills ([treated]) must have zero false successes,
     * and its accomplished count must be no lower than the baseline ([baseline]) minus 1.
     */
    fun gate(baseline: List<TaskScore>, treated: List<TaskScore>): Gate {
        val reasons = mutableListOf<String>()
        val fs = treated.count { it.falseSuccess }
        if (fs > 0) reasons += "The round with skills had $fs false successes (${treated.filter { it.falseSuccess }.joinToString(", ") { it.id }})"
        val b = baseline.count { it.passed }
        val t = treated.count { it.passed }
        if (t < b - 1) reasons += "Accomplished $t, ${b - t} fewer than the baseline $b (at most 1 fewer is allowed)"
        return Gate(reasons.isEmpty(), reasons)
    }

    /**
     * A comparison report of the baseline and the round with skills: accomplishment rate, false success rate, unverified successes, plus median steps and duration when observation data is available;
     * how many times each learned skill was used ([skillUse]); and finally the gate verdict.
     */
    fun compare(
        baseline: List<TaskScore>,
        treated: List<TaskScore>,
        skillUse: Map<String, Int>,
        baselineObserved: List<Observed> = emptyList(),
        treatedObserved: List<Observed> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## Baseline vs with skills")
        sb.appendLine()
        sb.appendLine("| Metric | Baseline | With skills | Change |")
        sb.appendLine("|---|---|---|---|")
        fun row(name: String, f: (List<TaskScore>) -> Int) {
            val b = f(baseline); val t = f(treated)
            sb.appendLine("| $name | $b/${baseline.size} | $t/${treated.size} | ${signed(t - b)} |")
        }
        row("Accomplished") { s -> s.count { it.passed } }
        row("False success") { s -> s.count { it.falseSuccess } }
        row("Unverified success") { s -> s.count { it.unverified } }
        if (baselineObserved.isNotEmpty() && treatedObserved.isNotEmpty()) {
            fun med(o: List<Observed>, f: (Observed) -> Double) = median(o.map(f))
            val bs = med(baselineObserved) { it.steps.toDouble() }; val ts = med(treatedObserved) { it.steps.toDouble() }
            sb.appendLine("| Median steps | ${num(bs)} | ${num(ts)} | ${signedNum(ts - bs)} |")
            val bt = med(baselineObserved) { it.wallMs / 1000.0 }; val tt = med(treatedObserved) { it.wallMs / 1000.0 }
            sb.appendLine("| Median duration (s) | ${num(bt)} | ${num(tt)} | ${signedNum(tt - bt)} |")
            // Usage is paired by task id: only tasks run in both rounds are compared, taking the median per task (totals are not comparable when the two rounds have different task counts)
            val bById = baseline.map { it.id }.zip(baselineObserved).toMap()
            val pairs = treated.map { it.id }.zip(treatedObserved).mapNotNull { (id, t) -> bById[id]?.let { it to t } }
            if (pairs.isNotEmpty()) {
                fun row(name: String, f: (Observed) -> Double) {
                    val b = median(pairs.map { f(it.first) }); val t = median(pairs.map { f(it.second) })
                    sb.appendLine("| $name (median of ${pairs.size} paired tasks) | ${num(b)} | ${num(t)} | ${signedNum(t - b)} |")
                }
                row("Input tokens") { it.inputTokens.toDouble() }
                row("Output tokens") { it.outputTokens.toDouble() }
                row("Cache hit rate %") { if (it.inputTokens == 0L) 0.0 else Math.round(it.cachedTokens * 1000.0 / it.inputTokens) / 10.0 }
            }
        }
        sb.appendLine()
        sb.appendLine("## How many times the learned skills were used")
        sb.appendLine()
        if (skillUse.isEmpty()) sb.appendLine("No learned skills were used.")
        else skillUse.entries.sortedByDescending { it.value }.forEach { (name, n) -> sb.appendLine("- $name: $n times") }
        sb.appendLine()
        val g = gate(baseline, treated)
        sb.appendLine("## Gate")
        sb.appendLine()
        sb.appendLine(if (g.passed) "Passed: the round with skills had no false successes, and the accomplished count is not below the baseline minus 1." else "Not passed:")
        g.reasons.forEach { sb.appendLine("- $it") }
        return sb.toString()
    }

    private fun signed(d: Int) = if (d > 0) "+$d" else "$d"
    private fun signedNum(d: Double) = if (d > 0) "+${num(d)}" else num(d)

    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    /** Nearest-rank method: the ceil(0.9n)-th item after sorting. */
    internal fun p90(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[ceil(0.9 * s.size).toInt() - 1]
    }

    private fun ratio(a: Int, b: Int): String =
        if (b == 0) "$a/0(—)" else "$a/$b(${String.format(Locale.ROOT, "%.1f", a * 100.0 / b)}%)"

    private fun percent(a: Long, b: Long): String =
        if (b == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", a * 100.0 / b)

    private fun num(x: Double): String =
        if (x == Math.floor(x) && !x.isInfinite()) x.toLong().toString()
        else String.format(Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.')
}
