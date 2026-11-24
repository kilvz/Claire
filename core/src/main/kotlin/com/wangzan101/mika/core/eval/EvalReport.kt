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
        sb.appendLine("# 评测汇总 $run")
        sb.appendLine()
        sb.appendLine("模型:$model;开关:$flags;实跑 $n 题,跳过 ${skipped.size} 题" + (if (parallel > 1) ";并行 $parallel 题。" else "。"))
        sb.appendLine()
        sb.appendLine("## 指标")
        sb.appendLine()
        sb.appendLine("| 指标 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 办成率 | ${ratio(passed, n)} |")
        if (totalWallMs != null) sb.appendLine("| 总墙钟时间(秒) | ${num(totalWallMs / 1000.0)} |")
        sb.appendLine("| 假成功率(占报成功) | ${ratio(falseSuccess, reportedSuccess)} |")
        sb.appendLine("| 假成功率(占全部) | ${ratio(falseSuccess, n)} |")
        sb.appendLine("| 未核实成功 | $unverified |")
        sb.appendLine("| 未核实且没过 | ${scores.count { it.first.failureClass == FailureClass.unverified_wrong }} |")
        // Hit a barrier visible on screen such as a login wall and handed over to the user: listed separately and not counted as accomplished (whether it counts is left to humans)
        scores.mapNotNull { it.first.handedOver }.groupingBy { it }.eachCount().toSortedMap()
            .forEach { (kind, c) -> sb.appendLine("| 交给用户:$kind(不算办成) | $c |") }
        sb.appendLine("| 步数中位数 | ${num(median(steps))} |")
        sb.appendLine("| 步数 P90 | ${num(p90(steps))} |")
        sb.appendLine("| 耗时中位数(秒) | ${num(median(secs))} |")
        sb.appendLine("| 耗时 P90(秒) | ${num(p90(secs))} |")
        sb.appendLine("| 模型调用次数中位数 | ${num(median(calls))} |")
        sb.appendLine("| 模型调用次数 P90 | ${num(p90(calls))} |")
        sb.appendLine("| 模型等待占比 | ${percent(scores.sumOf { it.second.modelMs }, wallT)} |")
        sb.appendLine("| 工具执行占比 | ${percent(scores.sumOf { it.second.toolMs }, wallT)} |")
        val waitT = scores.sumOf { it.second.waitMs }
        // Time waiting for the UI has to be reported by tools; if there is not a single entry it was not measured, so do not write 0%
        sb.appendLine("| 其中等界面占比 | ${if (waitT == 0L) "(暂无数据)" else percent(waitT, wallT)} |")
        // Beyond model and tools: waiting for the user to answer or approve, retry backoff, UI overhead; the three add up to 100%
        val otherT = (wallT - scores.sumOf { it.second.modelMs } - scores.sumOf { it.second.toolMs }).coerceAtLeast(0)
        sb.appendLine("| 其他/等用户占比 | ${percent(otherT, wallT)} |")
        sb.appendLine("| 回放命中 | ${scores.sumOf { it.second.replayHits }} |")
        sb.appendLine("| 回放交还 | ${scores.sumOf { it.second.replayHandBacks }} |")
        if (scores.any { it.second.replayHits + it.second.replayHandBacks > 0 }) {
            val handedBack = scores.filter { it.second.replayHandBacks > 0 }
            sb.appendLine("| 回放命中率(走完路线的题) | ${ratio(scores.count { it.second.replayHits > 0 }, n)} |")
            sb.appendLine("| 回放交还率(中途交还模型的题) | ${ratio(handedBack.size, n)} |")
            sb.appendLine("| 交还后办成率 | ${ratio(handedBack.count { it.first.passed }, handedBack.size)} |")
        }
        sb.appendLine("| 输入 token | $inT |")
        sb.appendLine("| 其中缓存 token | $cachedT |")
        sb.appendLine("| 输出 token | $outT |")
        sb.appendLine("| 缓存命中率(cached/input) | ${percent(cachedT, inT)} |")
        val perTask = scores.filter { it.second.inputTokens > 0 }.map { it.second.cachedTokens * 100.0 / it.second.inputTokens }
        sb.appendLine("| 每题缓存命中率中位数 | ${if (perTask.isEmpty()) "—" else num(median(perTask)) + "%"} |")
        if (prices != null) {
            // Input tokens include the cached part, and the cached part is priced at the cached rate
            val cost = ((inT - cachedT) * prices.inputPerM + cachedT * prices.cachedPerM + outT * prices.outputPerM) / 1_000_000
            sb.appendLine("| 花费 | ${num(cost)} |")
        }
        sb.appendLine()

        metricsSection(sb, scores)

        sb.appendLine("## 按类计数")
        sb.appendLine()
        val byClass = scores.mapNotNull { it.first.failureClass }.groupingBy { it }.eachCount()
        if (byClass.isEmpty()) sb.appendLine("无失败。")
        else {
            sb.appendLine("| 类别 | 数 |")
            sb.appendLine("|---|---|")
            FailureClass.entries.filter { it in byClass }.forEach { sb.appendLine("| ${it.name} | ${byClass.getValue(it)} |") }
        }
        sb.appendLine()

        sb.appendLine("## 每题")
        sb.appendLine()
        sb.appendLine("| id | 结局 | 通过 | 类别 | 核查 | 步数 | 秒数 | 输入 token | 缓存命中率 | 输出 token |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
        scores.forEach { (s, o) ->
            // An answer was given and it was judged as not accomplished: write the judged outcome
            val judged = o.judged?.takeIf { Scoring.recorded(o) !== o }?.let { "(判:$it)" }.orEmpty()
            sb.appendLine("| ${s.id} | ${o.outcome}$judged${s.handedOver?.let { "(交给用户:$it)" }.orEmpty()} | ${if (s.passed) "是" else "否"} | ${s.failureClass?.name ?: ""} | ${o.verdict ?: ""} | ${o.steps} | " +
                "${num(o.wallMs / 1000.0)} | ${o.inputTokens} | ${percent(o.cachedTokens, o.inputTokens)} | ${o.outputTokens} |")
        }
        sb.appendLine()

        if (originals.isNotEmpty()) {
            sb.appendLine("## 原题(记下路线用,不计入上面的指标)")
            sb.appendLine()
            sb.appendLine("| id | 结局 | 通过 | 步数 | 秒数 |")
            sb.appendLine("|---|---|---|---|---|")
            originals.forEach { (s, o) ->
                sb.appendLine("| ${s.id} | ${o.outcome}${s.handedOver?.let { "(交给用户:$it)" }.orEmpty()} | ${if (s.passed) "是" else "否"} | ${o.steps} | ${num(o.wallMs / 1000.0)} |")
            }
            sb.appendLine()
        }

        sb.appendLine("## 跳过")
        sb.appendLine()
        if (skipped.isEmpty()) sb.appendLine("无。") else skipped.forEach { (id, why) -> sb.appendLine("- $id:$why") }
        sb.appendLine()

        sb.appendLine("## 离开题目范围的 App(只记录,不算失败)")
        sb.appendLine()
        val drift = scores.filter { it.first.leftScope.isNotEmpty() }
        if (drift.isEmpty()) sb.appendLine("无。") else drift.forEach { (s, _) -> sb.appendLine("- ${s.id}:${s.leftScope.joinToString("、")}") }
        sb.appendLine()

        sb.appendLine("## 留下的测试数据标记(需人工清理)")
        sb.appendLine()
        val markers = (originals + scores).mapNotNull { (s, _) -> s.marker?.let { s.id to it } }
        if (markers.isEmpty()) sb.appendLine("无。") else markers.forEach { (id, m) -> sb.appendLine("- $id:$m") }
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
        sb.appendLine("## 运行小结")
        sb.appendLine()
        sb.appendLine("按运行记录算(${rows.size} 题):时间从 Claire 开始处理算起,读、操作按执行入口给的种类分,批里的子步不重复计。")
        sb.appendLine()
        sb.appendLine("| 指标 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 用时中位数(秒) | ${num(st.medianTotalMs / 1000.0)} |")
        sb.appendLine("| 第一次反馈中位数(秒) | ${st.medianFirstFeedbackMs?.let { num(it / 1000.0) } ?: "—"} |")
        sb.appendLine("| 平均和模型来回 | ${num(Math.round(st.avgModelCalls * 10) / 10.0)} |")
        sb.appendLine("| 模型思考占比 | ${percent(st.modelMs, st.totalMs)} |")
        sb.appendLine("| 读屏占比 | ${percent(st.observeMs, st.totalMs)} |")
        sb.appendLine("| 操作占比 | ${percent(st.actMs, st.totalMs)} |")
        sb.appendLine("| 其他(等待、重试)占比 | ${percent(st.otherMs, st.totalMs)} |")
        sb.appendLine()
        sb.appendLine("| id | 秒数 | 第一次反馈(秒) | 来回 | 全部请求 | 模型(秒) | 读屏(秒) | 操作(秒) | 其他(秒) | 读 / 操作次数 | token |")
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
        if (fs > 0) reasons += "带技能的一轮有 $fs 题假成功(${treated.filter { it.falseSuccess }.joinToString("、") { it.id }})"
        val b = baseline.count { it.passed }
        val t = treated.count { it.passed }
        if (t < b - 1) reasons += "办成数 $t 比基线 $b 少了 ${b - t} 题(最多允许少 1 题)"
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
        sb.appendLine("## 基线 vs 带技能")
        sb.appendLine()
        sb.appendLine("| 指标 | 基线 | 带技能 | 变化 |")
        sb.appendLine("|---|---|---|---|")
        fun row(name: String, f: (List<TaskScore>) -> Int) {
            val b = f(baseline); val t = f(treated)
            sb.appendLine("| $name | $b/${baseline.size} | $t/${treated.size} | ${signed(t - b)} |")
        }
        row("办成") { s -> s.count { it.passed } }
        row("假成功") { s -> s.count { it.falseSuccess } }
        row("未核实成功") { s -> s.count { it.unverified } }
        if (baselineObserved.isNotEmpty() && treatedObserved.isNotEmpty()) {
            fun med(o: List<Observed>, f: (Observed) -> Double) = median(o.map(f))
            val bs = med(baselineObserved) { it.steps.toDouble() }; val ts = med(treatedObserved) { it.steps.toDouble() }
            sb.appendLine("| 步数中位数 | ${num(bs)} | ${num(ts)} | ${signedNum(ts - bs)} |")
            val bt = med(baselineObserved) { it.wallMs / 1000.0 }; val tt = med(treatedObserved) { it.wallMs / 1000.0 }
            sb.appendLine("| 耗时中位数(秒) | ${num(bt)} | ${num(tt)} | ${signedNum(tt - bt)} |")
            // Usage is paired by task id: only tasks run in both rounds are compared, taking the median per task (totals are not comparable when the two rounds have different task counts)
            val bById = baseline.map { it.id }.zip(baselineObserved).toMap()
            val pairs = treated.map { it.id }.zip(treatedObserved).mapNotNull { (id, t) -> bById[id]?.let { it to t } }
            if (pairs.isNotEmpty()) {
                fun row(name: String, f: (Observed) -> Double) {
                    val b = median(pairs.map { f(it.first) }); val t = median(pairs.map { f(it.second) })
                    sb.appendLine("| $name(配对 ${pairs.size} 题中位数) | ${num(b)} | ${num(t)} | ${signedNum(t - b)} |")
                }
                row("输入 token") { it.inputTokens.toDouble() }
                row("输出 token") { it.outputTokens.toDouble() }
                row("缓存命中率 %") { if (it.inputTokens == 0L) 0.0 else Math.round(it.cachedTokens * 1000.0 / it.inputTokens) / 10.0 }
            }
        }
        sb.appendLine()
        sb.appendLine("## 学到的技能用了几次")
        sb.appendLine()
        if (skillUse.isEmpty()) sb.appendLine("没有用到学到的技能。")
        else skillUse.entries.sortedByDescending { it.value }.forEach { (name, n) -> sb.appendLine("- $name:$n 次") }
        sb.appendLine()
        val g = gate(baseline, treated)
        sb.appendLine("## 门槛")
        sb.appendLine()
        sb.appendLine(if (g.passed) "通过:带技能一轮没有假成功,办成数不低于基线减 1。" else "不通过:")
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
