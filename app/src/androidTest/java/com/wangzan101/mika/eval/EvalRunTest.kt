package com.wangzan101.mika.eval

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.task.ConfirmationCopy
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.recordedTools
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.eval.AppCapabilities
import com.wangzan101.mika.core.eval.Check
import com.wangzan101.mika.core.eval.Checks
import com.wangzan101.mika.core.eval.EvalCategory
import com.wangzan101.mika.core.eval.EvalPairing
import com.wangzan101.mika.core.eval.EvalReport
import com.wangzan101.mika.core.eval.EvalTask
import com.wangzan101.mika.core.eval.EvalTasks
import com.wangzan101.mika.core.eval.Observed
import com.wangzan101.mika.core.eval.Prices
import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.eval.Scoring
import com.wangzan101.mika.core.eval.TaskScore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunMetrics
import com.wangzan101.mika.core.run.record.RunMetricsStore
import com.wangzan101.mika.core.safety.gate.IrrevAction
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.testTasks
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device evaluation: runs a task set one task at a time through the real [TaskService] path, scores them, and writes a summary. Runs only when the arguments are passed explicitly.
 *
 * `-e evalRun <name> [-e evalSet <assets dir or id prefix>] [-e evalPriceIn <price per million input tokens> -e evalPriceOut <output price>
 *  [-e evalPriceCached <cached price>]] [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true] [-e evalFlags none|<key=on,…>]
 *  [-e evalChannel web|app] [-e evalSplit tune|holdout]`
 *
 * Tasks come from the category directories in the test package's assets (the repository's eval/tasks/<category>/), the web task directory web, and the smoke-test directory eval-smoke; without evalSet, everything except the smoke tests runs.
 * When evalSplit is given, only that pool's tasks run (by the task's partition; without it, all run). Results from the holdout pool are used only for release decisions, not for tuning prompts and knowledge.
 * When evalChannel is given, every task's channel is forced to web or app (prepending "use the web" / "use the app" to the goal) to compare the two routes.
 * The main model is the one already configured in settings (configure it first with LiveRunTest#configureProvider).
 * Results are written to the app's external files directory eval/<evalRun>/: summary.md, scores.jsonl, <task id>/run/ (the full run record for that task).
 *
 * evalFlags (default none) takes effect through [FeatureFlags.setSpeed], and the original values are restored afterwards; the flag combination is written into the summary.
 * evalPace (fast / standard / human, default fast) is the pace at which this evaluation operates the app; it does not change the value in settings. When omitted, the evaluation still runs fast as before.
 *
 * `-e evalVariants on` (replay evaluation, serial): runs only tasks that have variants; each task first runs the original (recording the route if it succeeded), then runs its variants one after another.
 * The summary metrics count only the variants, with the originals in a separate section; variant run records live under <original task id>_v<n>/.
 *
 * `-e evalParallel <n>` (n ≥ 2, requires parallelTasks to be on): greedily groups tasks so that the apps they use are disjoint ([EvalPairing]), and tasks in the same group run at the same time,
 * with "total wall-clock time" added to the summary. Tasks in a group share one set of automatic answers (each task's answers found by keyword in the question) and one step limit (the largest in the group).
 * When a task fails because another task in the group is holding an app, the failure reason is recorded as [Scoring.INTERFERENCE_PREFIX] (a separate class, not counted as an evaluator problem).
 *
 * Each evaluated task uses a new conversation and runs in ask mode (with autonomous mode off in developer options) so the confirmation gate is active; confirmations are answered automatically through [testTasks.evalHooks.autoDecideConfirmation]
 * (when that is not wired up, it waits for the timeout and then declines). Write-category and irreversible tasks run only when the auto-decline hook is wired up and all requires are met; irreversible tasks additionally need
 * `-e evalAllowIrreversible true` and do not run by default.
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    private var savedFlags: SpeedFlags? = null

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        savedFlags?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        testTasks.evalHooks.onTakeover = onTakeover
        // The evaluation never really sends, deletes, or places orders: every confirmation gate is declined
        testTasks.evalHooks.autoDecideConfirmation = { false }
        // By default the evaluation learns no skills and does not memorize automatically, so the resulting scores are not affected by what a previous round learned; turn these on explicitly when testing them
        testTasks.evalHooks.learnSkills = args.getString("learnSkills") == "on"
        testTasks.evalHooks.rememberMemory = args.getString("memory") == "on"
        testTasks.evalHooks.autoApproveSkillDrafts = args.getString("autoApproveSkills") == "true"
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("evalPace")) ?: OperatingPace.FAST
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val prices = prices()
        val flags = SpeedFlags.parse(args.getString("evalFlags") ?: "none")
        val featureFlags = FeatureFlags.get(context)
        savedFlags = featureFlags.speedFlags()
        featureFlags.setSpeed(flags)

        // Skill-learning and memory state: cleared by default; pass keepState=true for the third round (run with the skills learned in the second round) to keep it
        if (args.getString("keepState") != "true") MemoryRuntime.of(context).wipeAll()
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        // evalChannel web|app: forces the same batch of tasks through the web or the app (to compare the two routes)
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        // Tasks of the same session run in the same conversation in id order, so sort by (session, id)
        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        DeviceSupport.shell()
        DeviceSupport.service()
        val model = ProviderStore.get(context).configFor(ModelRole.Main)?.model ?: error("no main model configured")
        val probes = EvalProbes(DeviceSupport::sh, File(ext, "eval/logged-in.txt"))
        val runs = RunStore(context)

        val parallel = args.getString("evalParallel")?.toInt() ?: 1
        require(parallel == 1 || flags.parallelTasks) { "evalParallel needs evalFlags parallelTasks=on" }
        val variants = args.getString("evalVariants") == "on"
        // Variants need the route the original task has just recorded, so they can only run one task after another
        require(!variants || parallel == 1) { "evalVariants runs serially; drop evalParallel" }
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val runnable = mutableListOf<EvalTask>()
        for (t in tasks) {
            // Tasks that modify the device or involve irreversible operations: they are skipped unless the auto-decline hook is wired up; irreversible ones also need explicit permission
            if (t.category in GATED && testTasks.evalHooks.autoDecideConfirmation == null) {
                skipped += t.id to "评测器不跑 ${t.category.wire()} 类题(确认关卡自动拒绝没有接上)"
                continue
            }
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "不可逆类题默认不跑(要跑须传 evalAllowIrreversible=true)"
                continue
            }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) {
                skipped += t.id to unmet.joinToString(";")
                continue
            }
            if (variants && t.variants.isEmpty()) {
                skipped += t.id to "回放评测只跑带变体的题"
                continue
            }
            runnable += t
        }
        if (variants) {
            val originals = mutableListOf<Pair<TaskScore, Observed>>()
            for (t in runnable) {
                val (task, observed) = runOne(t, run, probes, runs, File(out, t.id))
                originals += Scoring.score(task, observed) to observed
                scoresFile.appendText(scoreLine(originals.last().first, observed) + "\n")
                for (v in EvalTasks.variantsOf(t)) {
                    val (vt, vo) = runOne(v, run, probes, runs, File(out, v.id.replace('~', '_')))
                    val score = Scoring.score(vt, vo)
                    scores += score to vo
                    scoresFile.appendText(scoreLine(score, vo) + "\n")
                }
            }
            File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), originals = originals))
            File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
            return
        }
        val t0 = System.currentTimeMillis()
        for (group in EvalPairing.groups(runnable, parallel)) {
            val results = if (parallel == 1) listOf(runOne(group.single(), run, probes, runs, File(out, group.single().id), sessionConversation(group.single())))
                else runGroup(group, run, probes, runs, out)
            for ((task, observed) in results) {
                val score = Scoring.score(task, observed)
                scores += score to observed
                scoresFile.appendText(scoreLine(score, observed) + "\n")
            }
        }
        val total = if (parallel > 1) System.currentTimeMillis() - t0 else null
        File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), total, parallel))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        // How many times each learned skill was used (for the comparison report)
        val skillUse = scores.flatMap { it.second.skillsUsed }.groupingBy { it }.eachCount()
        File(out, "skill-use.json").writeText(JsonObject(skillUse.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /** Runs a group of tasks at the same time (parallel evaluation); each task gets its own conversation, and each is watched to completion through its own task object. */
    private fun runGroup(group: List<EvalTask>, run: String, probes: EvalProbes, runs: RunStore, out: File): List<Pair<EvalTask, Observed>> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val prepared = group.map { t ->
            val before = mutableMapOf<String, String>()
            var bad: String? = null
            for ((name, probe) in t.probesBefore) {
                val v = runCatching { probes.run(probe) }.getOrNull()
                if (v.isNullOrBlank() || v == "null") { bad = "探针 $probe 读不到"; break }
                before[name] = v
            }
            if (bad != null) t to bad else EvalTasks.substitute(t, run, before) to null
        }
        val tasks = prepared.filter { it.second == null }.map { it.first }
        testTasks.evalHooks.autoAnswer = { prompt, _ -> tasks.firstNotNullOfOrNull { t -> t.answers.entries.firstOrNull { it.key in prompt }?.value } }
        testTasks.evalHooks.maxSteps = tasks.mapNotNull { it.maxSteps }.maxOrNull()
        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return group.map { it to harness("上一组任务一直没有收尾") }

        class Live(val task: EvalTask, val conversation: String, var run: TaskRun? = null, var t0: Long = 0, var tookOver: Boolean = false,
                   var timedOut: Boolean = false, var end: TaskState? = null, var wallMs: Long = 0, var failed: String? = null)
        val live = tasks.map { Live(it, UUID.randomUUID().toString()) }
        for (l in live) {
            l.t0 = System.currentTimeMillis()
            TaskService.start(context, l.task.goal, l.conversation)
            if (!waitFor(START_WAIT_MS) { DeviceTaskState.run(l.conversation) != null }) l.failed = "任务没有开始" else l.run = DeviceTaskState.run(l.conversation)
        }
        while (live.any { it.run != null && it.end == null && it.failed == null }) {
            for (l in live) {
                val r = l.run ?: continue
                if (l.end != null || l.failed != null) continue
                val s = r.state.value
                if (s is TaskState.WaitingTakeover && !l.tookOver) {
                    l.tookOver = true
                    if (testTasks.evalHooks.onTakeover == "cancel") r.cancel()
                }
                val timeoutMs = (l.task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
                val elapsed = System.currentTimeMillis() - l.t0
                if (!l.timedOut && elapsed > timeoutMs) { l.timedOut = true; r.cancel() }
                if (l.timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) l.failed = "超时取消后任务一直没有收尾"
                // The run is only deregistered once the wrap-up is done (the run record is closed)
                if (DeviceTaskState.run(l.conversation) == null) {
                    l.end = r.state.value
                    l.wallMs = System.currentTimeMillis() - l.t0
                }
            }
            Thread.sleep(POLL_MS)
        }
        val results = prepared.filter { it.second != null }.map { (t, why) -> t to harness(why!!) }.toMutableList()
        for (l in live) {
            if (l.failed != null) { results += l.task to harness(l.failed!!); continue }
            val record = l.run?.runId?.value?.let { id -> runs.list().firstOrNull { it.id == id } }
            val events = record?.let { runs.events(it.id) }.orEmpty()
            record?.let { runs.copyTo(it.id, File(File(out, l.task.id), "run")) }
            val task = resolveApps(l.task, events, probes)
            val observed = observe(task, l.end!!, events, record != null, l.timedOut, l.tookOver, l.wallMs, probes, record?.id.orEmpty())
            // Did not succeed and ran into another task holding an app along the way: listed separately as mutual interference
            val interfered = observed.outcome != "succeeded" && events.any { it is RunEvent.ToolResult && "app_in_use_by_other_task" in it.resultJson }
            results += task to if (interfered) observed.copy(failReason = Scoring.INTERFERENCE_PREFIX + "同组另一题占着要用的 App") else observed
        }
        return results
    }

    /** Tasks of the same session share one conversation (memory tasks: the second task continues in the first task's conversation). */
    private val sessions = mutableMapOf<String, String>()
    private fun sessionConversation(t: EvalTask): String? = t.session?.let { sessions.getOrPut(it) { UUID.randomUUID().toString() } }

    /**
     * Comparison and threshold between two evaluation rounds: `-e evalCompareBase <baseline run name> -e evalCompareTreated <skill-bearing run name>`,
     * reads scores.jsonl from both runs and skill-use.json from the skill-bearing run, and writes compare.md into the skill-bearing run's directory.
     */
    @Test
    fun compareRuns() {
        val base = args.getString("evalCompareBase")
        val treated = args.getString("evalCompareTreated")
        assumeTrue(base != null && treated != null)
        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        fun read(run: String): List<Pair<TaskScore, Observed>> = File(ext, "eval/$run/scores.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TaskScore((o["id"] as JsonPrimitive).content, b("passed"), b("falseSuccess"), b("unverified"), emptyList(), null) to
                Observed((o["outcome"] as? JsonPrimitive)?.content.orEmpty(), "", emptyList(), null, emptyMap(), emptyMap(), l("steps").toInt(), l("wallMs"), 0, 0, 0, null)
        }
        val b = read(base!!)
        val t = read(treated!!)
        val use = runCatching { (Json.parseToJsonElement(File(ext, "eval/$treated/skill-use.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() } }
            .getOrDefault(emptyMap())
        File(ext, "eval/$treated/compare.md").writeText(
            "# 对比 $base → $treated\n\n" + EvalReport.compare(b.map { it.first }, t.map { it.first }, use, b.map { it.second }, t.map { it.second }),
        )
    }

    /** Runs one task and returns the task with placeholders substituted plus the observed facts. When [conversation] is not null, continues running in that conversation. */
    private fun runOne(t: EvalTask, run: String, probes: EvalProbes, runs: RunStore, dir: File, conversation: String? = null): Pair<EvalTask, Observed> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("探针 $probe 读不到")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        testTasks.evalHooks.autoAnswer = { prompt, _ -> task.answers.entries.firstOrNull { it.key in prompt }?.value }
        testTasks.evalHooks.maxSteps = task.maxSteps

        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return task to harness("上一个任务一直没有收尾")
        val prevConversation = DeviceTaskState.conversationId.value
        val t0 = System.currentTimeMillis()
        TaskService.start(context, task.goal, conversation)
        // With a new conversation, a changed id means this request has been accepted; when continuing in the same conversation, watch for the service to become busy
        val started = if (conversation == null) waitFor(START_WAIT_MS) { DeviceTaskState.conversationId.value != prevConversation }
            else waitFor(START_WAIT_MS) { DeviceTaskState.busy.value && DeviceTaskState.conversationId.value == conversation }
        if (!started) return task to harness("任务没有开始")
        val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
        var tookOver = false
        var timedOut = false
        // busy only flips back to false after the state reaches an outcome and the run record is closed as well
        while (DeviceTaskState.busy.value) {
            val s = DeviceTaskState.state.value
            if (s is TaskState.WaitingTakeover && !tookOver) {
                tookOver = true
                if (testTasks.evalHooks.onTakeover == "cancel") TaskService.cancel(context)
            }
            if (!timedOut && System.currentTimeMillis() - t0 > timeoutMs) {
                timedOut = true
                TaskService.cancel(context)
            }
            if (timedOut && System.currentTimeMillis() - t0 > timeoutMs + BUSY_WAIT_MS) {
                return task to harness("超时取消后任务一直没有收尾")
            }
            Thread.sleep(POLL_MS)
        }
        val wallMs = System.currentTimeMillis() - t0
        val end = DeviceTaskState.state.value

        // After the answer is delivered, the outcome is judged separately (in the background): wait until it is recorded in the run record before reading it; even a failed judgement leaves an unknown entry; if it never arrives, treat it as unjudged
        if (end is TaskState.Succeeded) waitFor(JUDGE_WAIT_MS) {
            runs.list().firstOrNull { it.startedAtMs >= t0 }?.judged != null
        }
        val record = runs.list().firstOrNull { it.startedAtMs >= t0 }
        val events = record?.let { runs.events(it.id) }.orEmpty()
        record?.let { runs.copyTo(it.id, File(dir, "run")) }
        val resolved = resolveApps(task, events, probes)
        return resolved to observe(resolved, end, events, record != null, timedOut, tookOver, wallMs, probes, record?.id.orEmpty())
    }

    /** After the run, replaces `{app:<capability>}` with package names on this phone: candidates the Agent opened take priority (see [AppCapabilities.choose]). */
    private fun resolveApps(task: EvalTask, events: List<RunEvent>, probes: EvalProbes): EvalTask {
        val launched = RunFacts.appsLaunched(events, recordedTools())
        val written = RunFacts.appsWrittenIn(events, recordedTools())
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), launched, context.packageName, written)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /** After a task ends: assembles the observed facts from the outcome, the run record, and the probes run after it. */
    private fun observe(
        task: EvalTask, end: TaskState, events: List<RunEvent>, hasRecord: Boolean, timedOut: Boolean, tookOver: Boolean,
        wallMs: Long, probes: EvalProbes, runId: String,
    ): Observed {
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>()
        // Stopped at a gate: the last declaration was (automatically, in the evaluation) declined, after which the Agent reported finishing
        val stoppedAtGate = RunFacts.stoppedAtGate(events)
        val outcome = when {
            timedOut -> "timeout"
            stoppedAtGate -> "confirmation"
            // A reply without tool calls is the answer: ones that were never checked still count as succeeded (checks are reviewed separately from the answer and the ledger); one that was checked but could not be verified counts as unverified
            end is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
            end is TaskState.Failed -> "failed"
            end == TaskState.Cancelled -> if (tookOver) "takeover" else "cancelled"
            else -> "timeout"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] =
                runCatching { runBlocking { probes.screenHas(c.packageName, c.text, c.deepLink) } }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] =
                runCatching { runBlocking { probes.webHas(c.url, c.text) } }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        val sources = RunFacts.evidence(events, recordedTools(), task.goal, answer)
        return Observed(
            outcome = outcome,
            answer = answer,
            // Same evidence ledger and same selection rule as the runtime verifier
            evidence = sources,
            today = java.time.LocalDate.now(),
            // What is compared is the text shown on the card: actions use the card title's Chinese wording
            confirmation = confirmations.lastOrNull { it.decision != "mismatch" && it.decision != "required" }?.let { c ->
                val action = IrrevAction.entries.firstOrNull { it.name == c.action }?.let(ConfirmationCopy::actionLabel) ?: c.action
                listOfNotNull("action" to action, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap()
            },
            screenHas = screenHas,
            webHas = webHas,
            probes = after,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = wallMs,
            inputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.inputTokens ?: 0).toLong() },
            outputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.outputTokens ?: 0).toLong() },
            cachedTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.cachedTokens ?: 0).toLong() },
            // The failure reason is generated from the failure source, not taken from the Agent's own words
            leftScope = if (task.apps.isEmpty()) emptyList() else RunFacts.appsSeen(events, recordedTools()) - task.apps.toSet(),
            goal = task.goal,
            verdict = events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict,
            modelCalls = events.count { it is RunEvent.ModelReply },
            modelMs = events.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs },
            toolMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.durationMs },
            waitMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.waitMs },
            // A route counts as completed when its last step matched; if any step did not match, it counts as handed back
            replayHits = events.filterIsInstance<RunEvent.Replay>().count { it.matched && it.step == it.total },
            replayHandBacks = events.filterIsInstance<RunEvent.Replay>().count { !it.matched },
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            skillsUsed = events.filterIsInstance<RunEvent.SkillUsed>().map { it.name }.distinct(),
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, recordedTools()),
            judged = events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
            metrics = RunMetrics.of(events, runId),
            failReason = when {
                timedOut -> null
                end is TaskState.Failed -> Scoring.failReason(end)
                !hasRecord -> Scoring.HARNESS_PREFIX + "找不到这次运行的记录"
                else -> null
            },
        )
    }

    private fun harness(why: String) = Observed(
        "failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why,
    )

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /** All .json tasks under the test package's assets; [set] is a directory (such as eval-smoke, read) or an id prefix. */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        // Tasks live only in the category directories (eval/tasks/<category>/), the special task suites, the web task directory, and the smoke-test directory; the assets root also holds other json brought in by dependency libraries
        val dirs = EvalCategory.entries.map { it.wire() } + SUITE_DIRS + WEB_DIR + SMOKE_DIR
        return dirs.flatMap(::walk)
            .filter { path -> set != null || (SUITE_DIRS + SMOKE_DIR).none { path.startsWith("$it/") } }
            .map { path ->
                val task = runCatching { EvalTasks.parse(assets.open(path).bufferedReader().use { it.readText() }) }
                    .getOrElse { throw IllegalStateException("cannot parse eval task $path", it) }
                path to task
            }
            .filter { (path, task) -> set == null || path.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    /** The category name used in task files and directories. */
    private fun EvalCategory.wire(): String = when (this) {
        EvalCategory.Read -> "read"
        EvalCategory.Write -> "write"
        EvalCategory.Irreversible -> "irreversible"
        EvalCategory.Impossible -> "impossible"
        EvalCategory.Barrier -> "barrier"
    }

    private fun scoreLine(s: TaskScore, o: Observed): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", s.id)
        put("passed", s.passed)
        put("falseSuccess", s.falseSuccess)
        put("unverified", s.unverified)
        put("failedChecks", JsonArray(s.failedChecks.map(::JsonPrimitive)))
        put("failureClass", s.failureClass?.name)
        put("outcome", o.outcome)
        put("judged", o.judged)
        put("answer", o.answer.take(2000))
        put("failReason", o.failReason)
        put("verdict", o.verdict)
        put("leftScope", JsonArray(s.leftScope.map(::JsonPrimitive)))
        put("steps", o.steps)
        put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens)
        put("outputTokens", o.outputTokens)
        put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls)
        put("modelMs", o.modelMs)
        put("toolMs", o.toolMs)
        put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", RunMetricsStore.JSON.encodeToJsonElement(RunMetrics.serializer(), it)) }
    })

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val SMOKE_DIR = "eval-smoke"
        /** Special task suites: pairing tasks (run over three rounds) and memory tasks. Not run unless evalSet is passed; they must be selected explicitly. */
        private val SUITE_DIRS = listOf("skills", "memory")
        /** The web task directory (eval/tasks/web/), with a variety of categories. */
        private const val WEB_DIR = "web"
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 15_000L
        private const val BUSY_WAIT_MS = 60_000L
        /** The outcome-judging model call has its own 30-second cap, once per each of the two candidate models. */
        private const val JUDGE_WAIT_MS = 65_000L
        private val GATED = setOf(EvalCategory.Write, EvalCategory.Irreversible)
    }
}
