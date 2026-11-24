package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.safety.verify.RecordedVerifications
import com.wangzan101.mika.core.safety.verify.Verdict
import com.wangzan101.mika.core.safety.verify.Grounding
import com.wangzan101.mika.core.safety.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Rescore an eval run pulled back offline: recompute with the current tasks, check rules and verifier backstop, without calling a model or connecting a phone. Skipped when no directory is set.
 *
 *   MIKA_EVAL_RUN=/path/to/<run name> ./gradlew :core:test --tests '*RescoreEvalRun*' --rerun
 *
 * The directory must contain scores.jsonl and <task id>/run/events.jsonl (as the eval harness writes them). Per task:
 * - Outcome (the headline number, using the current way of ending, see [natural]): the task ends at the first final answer (a textual reply with no calls; in old records this was finish);
 *   checked against risk: if a value in the answer cannot be matched in the ledger at that time, or an outward action was done and the check back then was not a pass, step back once like at runtime and take the next final answer;
 *   if it still doesn't match it counts as unverified (marked as handed over unconfirmed), if it does it counts as succeeded. Giving up, handing to the user or being stopped before the first final answer stays as it was.
 *   Nudges in old records (a reminder for not calling a tool) and verification failures that stepped back to the end are now all replaced by the ending above.
 * - Comparison: the outcome and pass status under the old rule (look only at the last verification, rechecked with the current backstop [Verifier.backstop]).
 * - Checks: answer, screens, barriers, follow-up questions and memory are recomputed from the run record; ones that need on-device inspection (screen_has, web_has, probes, confirmation card) and expected values coming from pre-run probes keep their results from back then.
 * Evidence is rebuilt from the evidence ledger ([RecordedVerifications.ledgerSources], picked the same way as the runtime verifier); this is the report's headline number;
 * the answer_grounded check on the answer uses the same ledger ([RunFacts.evidence]).
 * The outcome judged after the answer was handed over (the outcome event, see [com.wangzan101.mika.core.agent.OutcomeJudge]) is taken from the record ([Scoring.recorded]):
 * an answer that honestly says it did not finish, or only did part, counts as an honest give-up; only a judgment on the same answer counts (if the current way of ending picks a different answer, it is not used). Old records have no such event.
 * Relative dates are converted using the moment of that verification (time zone from MIKA_RESCORE_ZONE, local if unset). Handed over to the user is listed separately and does not count as passing. The result is written to <directory>/rescore.md.
 */
class RescoreEvalRun {
    private val tasksDir = listOf("eval/tasks", "../eval/tasks").map(::File).first { it.isDirectory }

    @Test fun rescore() {
        val dir = (System.getenv("MIKA_EVAL_RUN") ?: System.getProperty("mika.eval.run"))?.let(::File)
        assumeTrue("MIKA_EVAL_RUN not set", dir != null && File(dir, "scores.jsonl").isFile)
        val zone = System.getenv("MIKA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val tasks = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.map { EvalTasks.parse(it.readText()) }.associateBy { it.id }
        val rows = mutableListOf("| id | 结局 当时→现在 | 判的结局 | 通过 当时→现在 | 交给用户 | 没过的核对项 | 未确认 | 旧规则(核查兜底)时 |", "|---|---|---|---|---|---|---|---|")
        var before = 0
        var live = 0
        var legacyPassed = 0
        var nudged = 0
        var total = 0
        var falseBefore = 0
        var falseNow = 0
        var unverifiedWrong = 0
        var judgedNotDone = 0
        val handed = mutableMapOf<String, Int>()
        File(dir, "scores.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
            val old = Json.parseToJsonElement(line) as JsonObject
            val id = old.str("id") ?: return@forEach
            val task = tasks[id] ?: return@forEach
            val events = File(dir, "$id/run/events.jsonl").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }.orEmpty()
            val oldOutcome = old.str("outcome").orEmpty()
            val oldFailed = (old["failedChecks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            fun score(outcome: String, answer: String? = null, upto: Int = events.size, judged: String? = null): TaskScore {
                val observed = observed(task, events.subList(0, upto), old, outcome, oldFailed, zone, answer).copy(judged = judged)
                val s = Scoring.score(task, observed)
                // Checks whose expected value comes from a pre-run probe ({probe:…}) cannot be computed offline, so keep the result from back then
                val failed = s.failedChecks.filterNot { k ->
                    val i = k.substringBefore(':').toIntOrNull() ?: return@filterNot false
                    "{probe:" in task.checks[i].toString() && k !in oldFailed
                }
                return s.copy(passed = failed.isEmpty(), failedChecks = failed)
            }
            val n = natural(task, events, oldOutcome, zone)
            // The judgment is about the answer that was delivered; if the current way of ending picks a different answer, it is not used
            val delivered = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason
            val judged = RunOutcome.recorded(events)?.wire?.takeIf { n == null || n.answer.trim() == delivered?.trim() }
            val score = n?.let { score(it.outcome, answer = it.answer, upto = it.upto, judged = judged) } ?: score(oldOutcome, judged = judged)
            val nowOutcome = n?.outcome ?: oldOutcome
            val oldRule = recheck(task, events, oldOutcome, zone)
            val legacy = score(oldRule)
            val wasPassed = old.str("passed") == "true"
            total++
            if (wasPassed) before++
            if (score.passed) live++
            if (legacy.passed) legacyPassed++
            if (n?.nudged == true) nudged++
            if (old.str("falseSuccess") == "true") falseBefore++
            if (score.falseSuccess) falseNow++
            if (score.failureClass == FailureClass.unverified_wrong) unverifiedWrong++
            val notDone = judged == RunOutcome.Partial.wire || judged == RunOutcome.NotPossible.wire
            if (notDone) judgedNotDone++
            score.handedOver?.let { handed[it] = (handed[it] ?: 0) + 1 }
            val mark = if (wasPassed != score.passed || oldOutcome != nowOutcome || notDone || score.handedOver != null) " **" else ""
            val legacyNote = if (legacy.passed != score.passed || oldRule != nowOutcome) "$oldRule,通过 ${yes(legacy.passed)}" else ""
            rows += "| $id$mark | $oldOutcome → $nowOutcome | ${judged.orEmpty()} | ${yes(wasPassed)} → ${yes(score.passed)} | ${score.handedOver.orEmpty()} | " +
                "${score.failedChecks.joinToString(", ")} | ${n?.unconfirmed.orEmpty().joinToString("、")} | $legacyNote |"
        }
        val report = "# 重新打分 ${dir!!.name}(自然结束:不带调用的回复就是回答)\n\n" +
            "通过:当时 $before/$total → 现在 $live/$total\n\n" +
            "对比:旧规则(只看最后一次核查,现在的兜底重核)$legacyPassed/$total\n\n" +
            "退回过一次的:$nudged\n\n" +
            "假成功:当时 $falseBefore → 现在 $falseNow(判下来没办成、按如实放弃算的 $judgedNotDone);未核实且没过:$unverifiedWrong\n\n" +
            "交给用户(不算通过):" + (handed.entries.joinToString(",") { "${it.key} ${it.value}" }.ifEmpty { "无" }) + "\n\n" +
            rows.joinToString("\n") + "\n"
        File(dir, "rescore.md").writeText(report)
        println(report)
    }

    private fun yes(b: Boolean) = if (b) "是" else "否"

    /** Outcome recomputed with the current way of ending: [answer] is the answer handed over, [upto] the index of the event after it (nothing after that happens), [unconfirmed] the places marked unconfirmed. */
    private class Natural(val outcome: String, val answer: String, val upto: Int, val unconfirmed: List<String>, val nudged: Boolean)

    /**
     * The task ends at the first final answer (see the class doc). If it had already ended some other way before that (give-up, takeover, being stopped, timeout), or there is no final answer at all, return null and keep the old outcome.
     * Each answer is checked literally against the ledger as of that point ([Grounding.check]); when outward actions were taken ([RunFacts.actedOutward]), the verdict recorded at the same step is consulted.
     */
    private fun natural(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): Natural? {
        if (oldOutcome == "timeout" || oldOutcome == "cancelled" || oldOutcome == "skipped") return null
        val finals = events.indices.filter { RunFacts.isFinalAnswer(events[it]) }
        if (finals.isEmpty()) return null
        // The task ended another way before the first answer (takeover and stopping at a confirmation gate still count as things after it): keep as is
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val pairs = RecordedVerifications.pairs(events).associate { (v, f) -> f to (events[v] as RunEvent.Verifier) }
        fun check(f: Int): List<String> {
            val r = events[f] as RunEvent.ModelReply
            val answer = RecordedVerifications.answer(events, f) ?: return emptyList()
            val seen = events.subList(0, f)
            val ledger = RunFacts.ledger(seen, RECORDED_TOOLS)
            val today = java.time.Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
            val gaps = Grounding.check(goal, answer, ledger.forAnswer(goal, answer).map { it.text }, today).unmatched.map { it.text }
            val outward = RunFacts.actedOutward(seen)
            val verdict = pairs[f]?.verdict
            return when {
                gaps.isNotEmpty() -> gaps.distinct()
                outward && verdict != null && verdict != Verdict.Pass.wire -> listOf(RecordedVerifications.arg(pairs[f]!!.detailJson, "reason").orEmpty().ifBlank { verdict })
                else -> emptyList()
            }
        }
        val first = finals.first()
        val missing = check(first)
        if (missing.isEmpty()) return Natural("succeeded", RecordedVerifications.answer(events, first).orEmpty(), first + 1, emptyList(), nudged = false)
        // Step back once: take the next final answer; if there is none, mark the first one as handed over unconfirmed
        val second = finals.getOrNull(1) ?: return Natural("unverified", RecordedVerifications.answer(events, first).orEmpty(), first + 1, missing, nudged = false)
        val again = check(second)
        return Natural(if (again.isEmpty()) "succeeded" else "unverified", RecordedVerifications.answer(events, second).orEmpty(), second + 1, again, nudged = true)
    }

    /** The old rule (kept only for comparison): the last verification is of the final answer, and cases the backstop then overturned (or let pass) are rechecked with the current backstop. */
    private fun recheck(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): String {
        if (oldOutcome != "succeeded" && oldOutcome != "unverified") return oldOutcome
        val (v, f) = RecordedVerifications.pairs(events).lastOrNull() ?: return oldOutcome
        val e = events[v] as RunEvent.Verifier
        val detail = runCatching { Json.parseToJsonElement(e.detailJson) as JsonObject }.getOrNull() ?: return oldOutcome
        val reason = detail.str("reason").orEmpty()
        // Only cases the verifier passed go through the backstop: either it was a pass then, or the backstop turned it into cannot_verify (including the removed "not from the same place" rule)
        val modelPassed = e.verdict == Verdict.Pass.wire || reason == Verifier.NOT_IN_READS || reason == OLD_NOT_ON_SCREEN || reason == Verifier.TRUNCATED || reason == OLD_NOT_ONE_SOURCE
        if (!modelPassed) return oldOutcome
        val asPass = Verifier.parse(JsonObject(detail + ("verdict" to JsonPrimitive("pass"))).toString()) ?: return oldOutcome
        if (asPass.verdict != Verdict.Pass) return oldOutcome
        val answer = RecordedVerifications.answer(events, f) ?: return oldOutcome
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val sources = RecordedVerifications.ledgerSources(events, v, f, goal, answer)
        val today = java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
        val now = Verifier.backstop(asPass, goal, answer, sources, imageSent = false, today)
        return if (now.verdict == Verdict.Pass) "succeeded" else "unverified"
    }

    private fun observed(
        task: EvalTask, events: List<RunEvent>, old: JsonObject, outcome: String, oldFailed: List<String>, zone: ZoneId,
        given: String? = null,
    ): Observed {
        fun passedThen(i: Int, type: String) = "$i:$type" !in oldFailed
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val probes = mutableMapOf<String, String>()
        task.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ScreenHas -> screenHas[Checks.screenKey(c)] = passedThen(i, "screen_has")
                is Check.WebHas -> webHas[Checks.webKey(c)] = passedThen(i, "web_has")
                is Check.ProbeEquals -> probes[c.probe] = if (passedThen(i, "probe_equals")) c.expected else ""
                else -> Unit
            }
        }
        val card = task.checks.filterIsInstance<Check.ConfirmationShows>().firstOrNull()
            ?.takeIf { c -> passedThen(task.checks.indexOf(c), "confirmation_shows") }
            ?.let { c -> listOfNotNull(c.action?.let { "action" to it }, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap() }
        val answer = given ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason?.takeIf { outcome == "succeeded" || outcome == "unverified" || outcome == "failed" }
            ?: old.str("answer").orEmpty()
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val ev = RunFacts.evidence(events, RECORDED_TOOLS, goal, answer)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.atMs ?: events.lastOrNull()?.atMs
        return Observed(
            outcome = outcome,
            answer = answer,
            evidence = ev,
            today = end?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
            confirmation = card,
            screenHas = screenHas,
            probes = probes,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = old.str("wallMs")?.toLongOrNull() ?: 0,
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            failReason = old.str("failReason"),
            goal = goal,
            webHas = webHas,
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, RECORDED_TOOLS),
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** The verification reason rejected in old records by the removed "the value and the thing it describes are not from the same place" rule. */
    /** "Evidence not found" as recorded before the wording changed. */
    private val OLD_NOT_ON_SCREEN = "回答的依据在屏幕、网页或命令输出里都找不到"
    private val OLD_NOT_ONE_SOURCE = "回答里的值和它说的东西不是从同一个页面或 App 读来的"
}
