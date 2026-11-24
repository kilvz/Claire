package com.wangzan101.mika.acceptance

import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.DeviceTaskState
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.browser.PageAskModel
import com.wangzan101.mika.browser.PageConversations
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.AttachedPage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.apiKey
import com.wangzan101.mika.settings.choose
import com.wangzan101.mika.settings.configFor
import com.wangzan101.mika.settings.save
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.appString
import com.wangzan101.mika.ui.awaitOne
import com.wangzan101.mika.ui.typeAndSend
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device acceptance: run a task with the real model configured in Settings; it runs only when arguments are passed
 * explicitly and is skipped in the regular test suite.
 *
 * - Configure a model (the Key only comes in through the arguments and is written into the app's own encrypted storage,
 *   nowhere else):
 *   `-e liveKey <key> -e liveBaseUrl <url> -e liveModel <model> [-e liveId <id>]`, runs [configureProvider].
 * - Switch the main model: `-e liveMain <id>`, which also runs [configureProvider].
 * - Run a task: `-e liveGoal <goal> -e liveRun <record dir name> [-e liveTimeoutSec 600] [-e liveOnTakeover cancel|handback]
 *   [-e livePace fast|standard|human] [-e liveMoveAwayAtStep N] [-e liveCancelAtStep N]`, runs [runGoal]. Through the real
 *   UI: type the goal into the input box and tap send.
 *   liveCancelAtStep: at step N stop the task as if tapping the stop key, then wait for the wrap-up after the stop to be
 *   recorded into the conversation (written to the summary in result.txt).
 *   `-e liveKeepCheckSec N`: after the task ends, check whether a page was left running in the background (web_keep_open);
 *   if so, read the page's media playback progress twice, N seconds apart
 *   (the currentTime of video/audio, readable only for same-origin content) plus a counter-only timer, and write them into
 *   result.txt's kept* (keptPage, keptMediaAdvanced, keptTimerAdvanced, keptNotification); then save a picture of it as
 *   kept-frame.png; that page is not closed but kept for a human to look at.
 *   livePace: the pace of operating inside the app, fast / standard / human (defaults to fast, which runs quickly; it does
 *   not change the one in Settings); pass standard when comparing speeds.
 *   liveMoveAwayAtStep: at step N move the app on the virtual display to the main screen (simulating the user opening the
 *   same app on the main screen), record the paused state and the notification, and a few seconds later resume as if
 *   tapping "Resume" on the notification.
 *
 * - Ask on a browser page: `-e liveUrl <url> -e liveGoal <question> -e liveRun <record dir name> [-e liveTimeoutSec 600] [-e liveDesktop true]
 *   [-e liveNew true] [-e liveTouchAtStep N -e liveResumeAfterSec S] [-e liveOnTakeover cancel|handback]`, runs [runPageAsk].
 *   Through the real UI: go to the browser from the drawer, open the URL (switching to the desktop version when
 *   liveDesktop), wait for the page to finish loading, type the question into "Ask Claire" at the bottom of the page and
 *   tap send (tapping "New conversation" first when liveNew); this page is lent to that task. liveTouchAtStep: at step N,
 *   swipe up on the web page with a finger through the window (through PageFrame like a real person, without hitting any
 *   link), record whether it paused and whether the banner and the small card appeared, wait S seconds (5 by default), tap
 *   "Resume" on the banner and record the resume.
 *   When liveTouchAtStep is not given, a pause of the task because "you are interacting" counts as a failure (Claire's own
 *   taps and scrolling must not pause the task), and the record is still written.
 *   Besides the ones in runGoal, result.txt also has: borrowed (the page was lent to the task), firstToolCalls, urlsOpened
 *   (URLs opened by web_navigate), reopenedSameUrl (this page was opened again, which should not happen), userTabUrlAtEnd,
 *   pausedByTouch, resumed, pauses (the count of each kind of pause), userPauses, conversationId, pageOrigin, listedAsWeb
 *   (this conversation carries the "web page" small tag in the drawer). One main-screen screenshot is taken at every step,
 *   pause, resume, end and drawer (step-NN.png etc.).
 *
 * - Multi-turn chat through the desk: `-e deskScript "<step>||<step>…" -e deskRun <record dir> [-e deskWaitSec 300] [-e deskParallelMax N] [-e deskNew false]`,
 *   runs [runDesk]. Steps: `say:<sentence>` (sent through the input box, waits for the desk's turn to finish), `wait:running|queued|needsyou|idle|report|tasks=N`
 *   (wait for the task board/report to reach that state), `sleep:<seconds>`, `home` (go to the home screen; later reports use the fallback), `notifications` (record Claire's notifications at this moment).
 *   deskParallelMax changes the parallel limit only for this run and restores it at the end. In result.txt each sentence gets a turnN line (ms to first character/first task/finish, dispatched task, tool chips, reply),
 *   each report spoken by the desk gets a reportN line, followed by the final task board, backend tools called, and notifications.
 *
 * Records are written to the app's external files dir acceptance/<liveRun>/: log.txt (what the model said at each step, tool calls and result summaries),
 * result.txt (model, goal, result, step count, duration, final answer), per-step virtual-screen frames and home-screen screenshots;
 * when `-e liveSettleSec N` is passed, wait up to N seconds for outcome judgement and learning to finish, with learned items written to learned.txt.
 */
@RunWith(AndroidJUnit4::class)
class LiveRunTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    @After
    fun tearDown() {
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.evalHooks.operatingPace = null
    }

    @Test
    fun configureProvider() {
        val key = args.getString("liveKey")
        val main = args.getString("liveMain")
        assumeTrue(key != null || main != null)
        val store = ProviderStore.get(context)
        var saved: String? = null
        if (key != null) {
            val model = args.getString("liveModel") ?: error("liveModel missing")
            val id = args.getString("liveId") ?: model
            val baseUrl = args.getString("liveBaseUrl") ?: ProviderPresets.all.first().baseUrl
            // Take the capabilities from the preset whose address matches (the same as picking a preset in Settings, including the prompt caching mode)
            val preset = ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.all.first()
            store.save(
                ProviderConfig(
                    id, ProviderConfig.Kind.OpenAiCompat, baseUrl,
                    // Vision main model and vision grounding verified: enabled by argument (grounding verification is only given when there is measured evidence)
                    model, preset.capabilities.copy(vision = args.getString("liveVision") == "true",
                        groundingVerified = args.getString("liveGroundingVerified") == "true"), name = id,
                ),
                key,
            )
            check(store.apiKey(id) == key) { "key not stored" }
            saved = id
        }
        // A configuration must be in use, otherwise it is cleaned up as unused: when no main model is given, use the one just saved
        (main ?: saved)?.let { check(store.choose(ModelRole.Main, RoleChoice.Model(it))) { "cannot assign $it" } }
        // Vision main model: use the main model for vision
        if (args.getString("liveVision") == "true") store.choose(ModelRole.Vision, RoleChoice.SameAsMain)
    }

    @Test
    fun runGoal() {
        val goal = args.getString("liveGoal")
        assumeTrue(goal != null)
        // The process the test launches does not have the app's language applied, so set the default language to it as a normal launch would: the built-in browser asks sites for the page language based on it
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.getSystemService(android.app.LocaleManager::class.java).applicationLocales
                .takeUnless { it.isEmpty }?.let { android.os.LocaleList.setDefault(it) }
        }
        val run = args.getString("liveRun") ?: "run"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val moveAwayAt = args.getString("liveMoveAwayAtStep")?.toInt() ?: -1
        val cancelAt = args.getString("liveCancelAtStep")?.toInt() ?: -1
        val pace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        testTasks.evalHooks.operatingPace = pace
        var cancelled = false
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")

        DeviceSupport.shell()
        DeviceSupport.service()
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} pace=${pace.name.lowercase()} goal=$goal")
        // Learning runs in a process-level scope after the task ends; record how many resident jobs are already in there at the start, and wait for it to come back to that number at the end
        val memory = MemoryRuntime.of(context)
        val learningJob = memory.scope.coroutineContext[Job]!!
        val resident = learningJob.children.count { it.isActive }

        val before = DeviceTaskState.runs.value.toSet()
        compose.typeAndSend(goal!!)
        // Put the keyboard away as soon as it is sent: only then does the background-display thumbnail under the chat page show, just like a real person who puts the keyboard away after sending
        compose.runOnUiThread {
            val w = compose.activity.window
            compose.activity.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        val chat = (context.applicationContext as MikaApp).chat
        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
        // Once it goes out, this question is in the conversation; if it is not, fail right away instead of waiting for the timeout
        check(chat.ui.value.exchanges.any { it.goal == goal.trim() }) {
            "send did not go out: composer=${chat.ui.value.composer} exchanges=${chat.ui.value.exchanges.size} task=${DeviceTaskState.state.value}"
        }
        val t0 = System.currentTimeMillis()
        // Chat goes through the desk: this sentence first reaches the desk, which dispatches it as a background task (running in its own sub-conversation); follow the dispatched task;
        // when the desk only replies without dispatching, the desk's reply is this turn's answer
        val dispatched: Any? = DeviceSupport.pollUntil(timeoutMs) {
            compose.mainClock.advanceTimeBy(250)
            DeviceTaskState.runs.value.firstOrNull { it !in before }
                ?: chat.ui.value.exchanges.lastOrNull { it.goal == goal.trim() }
                    ?.takeIf { it.status != com.wangzan101.mika.chat.ui.ExchangeStatus.Working && it.deskTasks.isEmpty() }
                    ?.let { it.deskReply.orEmpty() }
        }
        val task = dispatched as? TaskRun
        val deskOnly = dispatched as? String
        note("desk dispatched=${task?.key} child=${task?.conversationId} deskOnlyReply=${deskOnly?.take(300)}")

        var lastStep = -1
        var takeovers = 0
        var movedAway = false
        var pausesHandled = 0
        var end: TaskState? = null
        var last: TaskState? = null
        while (task != null && System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (s != last) {
                note("state $s")
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                frame(dir, "step-%02d".format(s.step))
            }
            if (moveAwayAt > 0 && !movedAway && s is TaskState.Running && s.step >= moveAwayAt) {
                val shell = DeviceSupport.shell()
                val task = shell.topTaskId(testTasks.agentDisplayId.value)
                movedAway = true
                note("moving task $task (${shell.packageOfTask(task)}) to the main screen: ${task >= 0 && shell.moveTask(task, 0)}")
            }
            if (cancelAt > 0 && !cancelled && s is TaskState.Running && s.step >= cancelAt) {
                cancelled = true
                note("stopping at step ${s.step}")
                TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Held && s.reason.resumable && pausesHandled == 0) {
                pausesHandled++
                Thread.sleep(3000)
                val shell = DeviceSupport.shell()
                note("paused; still paused after 3s: ${task.state.value}; main top task=${shell.topTaskId(0)} pkg=${shell.packageOfTask(shell.topTaskId(0))}")
                note("notifications: " + mikaNotifications())
                mainShot(dir, "paused-main")
                TaskService.resume(context, task.key)
                note("resume requested")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                frame(dir, "takeover-agent")
                mainShot(dir, "takeover-main")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                note("desk board at end: ${chat.ui.value.deskTasks.joinToString { "${it.taskId} ${it.state}" }}")
                break
            }
            // The UI test clock only advances when the test pushes it: push it forward while waiting so the chat page's step, timer and thumbnail keep refreshing as usual (needed when recording a demo)
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null && task != null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        compose.waitForIdle()
        mainShot(dir, "end-main")
        // Handoff after stopping: the task runs in the sub-conversation dispatched by the desk; wait for it to be recorded there as that turn's answer
        val app = context.applicationContext as MikaApp
        val summary = if (end == TaskState.Cancelled) DeviceSupport.pollUntil(30_000) {
            app.conversations.turns(task!!.conversationId).lastOrNull()?.let(com.wangzan101.mika.core.agent.Conversation::stopSummary)
        }.also {
            note("stop summary: $it")
            compose.waitForIdle()
            mainShot(dir, "stopped-summary")
        } else null
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> if (deskOnly != null) "desk_reply" else "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> deskOnly.orEmpty()
        }
        // liveSettleSec: wait for the outcome decision and the learning after handover to finish, and write what was learned into learned.txt
        val settleMs = (args.getString("liveSettleSec")?.toLong() ?: 0) * 1000
        if (settleMs > 0) {
            val settled = DeviceSupport.pollUntil(settleMs) { true.takeIf { learningJob.children.count { it.isActive } <= resident } } != null
            note("learning settled=$settled")
            File(dir, "learned.txt").writeText(learned(memory))
        }
        val keepSec = args.getString("liveKeepCheckSec")?.toLongOrNull() ?: 0
        val kept = if (keepSec > 0) keptPageCheck(keepSec, dir, ::note) else ""
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\npauses=$pausesHandled\nwallSec=$wall\ninputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "answer=$answer\n" + (summary?.let { "summary=$it\n" } ?: "") + kept,
        )
    }

    /**
     * Multi-turn acceptance for the chat page through the desk: follow [deskScript] step by step, typing into the input box and tapping send through the real UI, watching each desk turn read-only
     * ([DeskWatch]): how long until the first character, first task, and finish; what was dispatched; the tool chips at the top; every task-board change.
     * Take a home-screen screenshot at each step, and write the turns and task board to result.txt at the end.
     */
    @Test
    fun runDesk() {
        val script = args.getString("deskScript")
        assumeTrue(script != null)
        val run = args.getString("deskRun") ?: "desk"
        val waitMs = (args.getString("deskWaitSec")?.toLong() ?: 300) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        DeviceSupport.shell()
        DeviceSupport.service()
        val app = context.applicationContext as MikaApp
        val chat = app.chat
        val flags = FeatureFlags.get(context)
        val parallelBefore = flags.parallelMax()
        // The parallel limit is changed only for this acceptance run (Claire's own setting) and restored at the end
        args.getString("deskParallelMax")?.toInt()?.let { flags.setParallelMax(it) }
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        if (args.getString("deskNew") != "false") compose.runOnUiThread { chat.newConversation() }
        compose.waitForIdle()
        note("parallelMax=${flags.parallelMax()} (was $parallelBefore) limit=${app.taskState.parallelLimit.value} script=$script")
        val watch = DeskWatch(chat.ui, ::note, System::currentTimeMillis).apply { start() }
        val sent = mutableListOf<Pair<String, Long>>()
        val results = StringBuilder()
        fun board() = chat.ui.value.deskTasks
        fun waitUntil(what: String, cond: () -> Boolean): Boolean {
            val ok = DeviceSupport.pollUntil(waitMs) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { cond() }
            } != null
            note("wait $what: ${if (ok) "ok" else "TIMEOUT"} board=${board().joinToString { "${it.taskId} ${it.state}${it.position?.let { p -> "#$p" } ?: ""}" }}")
            return ok
        }
        val ended = setOf(DeskTaskPhase.Done, DeskTaskPhase.Failed, DeskTaskPhase.Cancelled)
        try {
            script!!.split("||").map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, step ->
                val (kind, value) = step.substringBefore(':') to step.substringAfter(':', "")
                note("step $i: $step")
                val ok = when (kind) {
                    "say" -> {
                        val seen = watch.turns().map { it.runId }.toSet()
                        val at = System.currentTimeMillis()
                        compose.typeAndSend(value)
                        compose.runOnUiThread {
                            val w = compose.activity.window
                            compose.activity.currentFocus?.clearFocus()
                            WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.ime())
                        }
                        chat.ui.value.blocked?.let { error("blocked by prerequisites: $it") }
                        sent += value.trim() to at
                        // Move on only after this desk turn finishes (finishes speaking or only dispatches a task)
                        waitUntil("reply to \"$value\"") { watch.turnFor(value.trim(), seen)?.doneAt != null }
                    }
                    "wait" -> when {
                        value == "running" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.Running } }
                        value == "needsyou" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.NeedsYou } }
                        value == "queued" -> waitUntil(value) { board().any { it.state == DeskTaskPhase.Queued } }
                        value == "idle" -> waitUntil(value) {
                            board().isNotEmpty() && board().all { it.state in ended } && watch.turns().none { it.doneAt == null }
                        }
                        value.startsWith("tasks=") -> value.removePrefix("tasks=").toInt().let { n -> waitUntil(value) { board().size >= n } }
                        value == "report" -> watch.turns().count { it.isReport }.let { n -> waitUntil(value) { watch.turns().count { it.isReport && it.doneAt != null } > n } }
                        else -> error("unknown wait $value")
                    }
                    "sleep" -> { Thread.sleep(value.toLong() * 1000); true }
                    // Page goes to the background: later reports should use the fallback (write back to the conversation, send a notification); reopening starts from the home-screen entry
                    "home" -> { sh("input keyevent KEYCODE_HOME"); Thread.sleep(1500); true }
                    "notifications" -> { note("notifications: " + mikaNotifications()); true }
                    else -> error("unknown step $step")
                }
                compose.mainClock.advanceTimeBy(500)
                runCatching { compose.waitForIdle() }
                mainShot(dir, "step-%02d".format(i))
                results.append("step$i=$step ok=$ok\n")
            }
        } finally {
            watch.stop()
            flags.setParallelMax(parallelBefore)
            val turns = watch.turns()
            sent.forEachIndexed { i, (text, at) ->
                val turn = turns.firstOrNull { it.goal == text && it.seenAt >= at }
                results.append("turn$i.text=$text\nturn$i.${turn?.describe(at) ?: "missing"}\n")
            }
            turns.filter { it.isReport }.forEachIndexed { i, t -> results.append("report$i.${t.describe()}\n") }
            results.append("board=" + board().joinToString(" ; ") { "${it.taskId} ${it.state}${it.position?.let { p -> "#$p" } ?: ""} ${it.title}" } + "\n")
            results.append("conversationId=${chat.ui.value.conversationId}\n")
            // Saved turns: reports while the page is away are written back to the conversation by the fallback (status = desk_report), visible here
            chat.ui.value.conversationId?.let { id ->
                app.conversations.turns(id).forEachIndexed { i, t ->
                    results.append("saved$i=${t.status} user=${t.userText.take(40)} said=${t.outcome.replace('\n', ' ').take(160)}\n")
                }
            }
            results.append("backendSteps=${recorder.calls} backendTools=${recorder.toolCalls.map { it.first }.distinct().joinToString(",")}\n")
            results.append("notifications=${mikaNotifications()}\n")
            File(dir, "result.txt").writeText(results.toString())
            // Stop anything still running so nothing is left on the phone
            board().filter { it.state !in ended }.forEach { v ->
                DeviceTaskState.runs.value.firstOrNull { it.conversationId == v.taskId }?.let { TaskService.cancel(context, it.key) }
            }
        }
    }

    /**
     * Whether the page left in the background after the task ends is still running: every [seconds] seconds, read the page's media playback progress and a count-only timer twice (added to the page just for this acceptance run,
     * without touching the page's own content), and save a frame of what it displays. Returns the lines written to result.txt.
     */
    private fun keptPageCheck(seconds: Long, dir: File, note: (String) -> Unit): String {
        val keeper = (context.applicationContext as MikaApp).pageKeeper
        val page = keeper.page.value
        val tab = keeper.keptTab()
        if (page == null || tab == null) {
            note("no kept page after the run")
            return "keptPage=none\n"
        }
        val probe = "(function(){if(!window.__keepCheck){window.__keepCheck=0;setInterval(function(){window.__keepCheck++},200);}" +
            "return JSON.stringify({timer:window.__keepCheck,media:Array.from(document.querySelectorAll('video,audio')).map(function(m){return {t:m.currentTime,paused:m.paused}})});})()"
        fun read(): org.json.JSONObject? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var raw: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (tab.destroyed) latch.countDown() else tab.view.evaluateJavascript(probe) { raw = it; latch.countDown() }
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return raw?.let { r -> runCatching { org.json.JSONObject(org.json.JSONTokener(r).nextValue() as String) }.getOrNull() }
        }
        val first = read()
        Thread.sleep(seconds * 1000)
        val second = read()
        note("kept page ${page.host}: before=$first after=$second")
        fun times(o: org.json.JSONObject?) = o?.optJSONArray("media")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optDouble("t") } }.orEmpty()
        val before = times(first)
        val after = times(second)
        val mediaAdvanced = after.indices.any { i -> before.getOrNull(i)?.let { after[i] > it } == true }
        val timerAdvanced = (second?.optInt("timer") ?: 0) > (first?.optInt("timer") ?: 0)
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { keeper.frames().filterNotNull().first() } }?.let { bmp ->
            File(dir, "kept-frame.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val notified = mikaNotifications().contains(appString(R.string.kept_notif_title))
        // A WebView can only be queried on the main thread
        var keptUrl: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { keptUrl = if (tab.destroyed) null else tab.view.url }
        return "keptPage=${page.host}\nkeptUrl=$keptUrl\nkeptMedia=${before.size}\nkeptMediaAdvanced=$mediaAdvanced\n" +
            "keptTimerAdvanced=$timerAdvanced\nkeptPageAlive=${!tab.destroyed && keeper.page.value?.id == page.id}\nkeptNotification=$notified\n"
    }

    @Test
    fun runPageAsk() {
        val url = args.getString("liveUrl")
        val goal = args.getString("liveGoal")
        assumeTrue(url != null && goal != null)
        val run = args.getString("liveRun") ?: "page"
        val timeoutMs = (args.getString("liveTimeoutSec")?.toLong() ?: 600) * 1000
        val onTakeover = args.getString("liveOnTakeover") ?: "cancel"
        val touchAt = args.getString("liveTouchAtStep")?.toInt() ?: -1
        val resumeAfterMs = (args.getString("liveResumeAfterSec")?.toLong() ?: 5) * 1000
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("livePace")) ?: OperatingPace.FAST
        val dir = File(context.getExternalFilesDir(null), "acceptance/$run").apply { deleteRecursively(); mkdirs() }
        val log = File(dir, "log.txt")
        fun note(s: String) = log.appendText("[${System.currentTimeMillis()}] $s\n")
        val instr = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as MikaApp
        val store = ProviderStore.get(context)
        val cfg = store.configFor(ModelRole.Main) ?: error("no main model configured")
        val recorder = Recorder(::note)
        testTasks.modelFactory = { ctx, s -> TaskEnvironment.defaultModelFactory(ctx, s)?.let { recorder.wrap(it) } }
        note("model=${cfg.model} url=$url goal=$goal")

        // Go to the browser from the drawer, open the URL and wait for the page to finish loading
        compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performClick()
        compose.awaitOne(appString(R.string.drawer_browser)).performClick()
        compose.waitForIdle()
        val browser = UserBrowser.of(context)
        compose.runOnUiThread { browser.open(url!!)?.let { error("cannot open $url: $it") } }
        fun loaded() = DeviceSupport.pollUntil(30_000) { browser.page.value?.takeIf { !it.loading && it.progress >= 100 } } != null
        note("page loaded=${loaded()}")
        if (args.getString("liveDesktop") == "true") {
            compose.runOnUiThread { browser.setDesktop(true) }
            Thread.sleep(1000)
            note("desktop page loaded=${loaded()}")
        }
        Thread.sleep(1500)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page-ask-bar").fetchSemanticsNodes().isNotEmpty() }
        if (args.getString("liveNew") == "true" && compose.onAllNodesWithTag("page-ask-fresh").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("page-ask-fresh").performClick()
        }
        mainShot(dir, "step-00")
        val before = DeviceTaskState.runs.value.toSet()
        compose.onNodeWithTag("page-ask-field").performTextInput(goal!!)
        compose.onNodeWithTag("page-ask-send").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        val task = DeviceSupport.pollUntil(15_000) {
            DeviceTaskState.runs.value.firstOrNull { it !in before && AttachedPage.parse(it.goal).words == goal.trim() }
        } ?: error("the page question did not start a task: runs=${DeviceTaskState.runs.value.map { it.goal.take(60) }}")
        val conversationId = task.conversationId
        note("task ${task.key} conversation=$conversationId")
        val t0 = System.currentTimeMillis()

        var lastStep = -1
        var last: TaskState? = null
        var borrowed = false
        var pauses = 0
        var userPauses = 0
        var takeovers = 0
        var asked = false
        var touched = false
        var pausedByTouch = false
        var resumed = false
        var end: TaskState? = null
        // Count every pause that appeared in the state flow (polling would miss brief pauses, and also the stretch the test itself waits through between a touch and the resume)
        val seen = java.util.Collections.synchronizedList(mutableListOf<TaskState>())
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var prev: TaskState? = null
            task.state.collect { st ->
                if (st is TaskState.Held && prev !is TaskState.Held) seen += st
                prev = st
            }
        }
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val s = task.state.value
            if (browser.loans.lentTo.value == conversationId) borrowed = true
            if (s != last) {
                note("state $s")
                if (s is TaskState.WaitingInput) asked = true
                last = s
            }
            if (s is TaskState.Running && s.step != lastStep) {
                lastStep = s.step
                compose.mainClock.advanceTimeBy(250)
                mainShot(dir, "step-%02d".format(s.step))
            }
            if (touchAt > 0 && !touched && s is TaskState.Running && s.step >= touchAt) {
                touched = true
                note("finger swipe on the page at step ${s.step}: ${swipeOnPage()}")
                pausedByTouch = DeviceSupport.pollUntil(3_000) { true.takeIf { PageAskModel.pausedForUser(task.state.value) } } != null
                compose.mainClock.advanceTimeBy(500)
                compose.waitForIdle()
                val banner = compose.onAllNodesWithTag("page-paused-banner").fetchSemanticsNodes().isNotEmpty()
                val card = compose.onAllNodesWithTag("page-card").fetchSemanticsNodes().isNotEmpty()
                note("paused by touch=$pausedByTouch banner=$banner card=$card state=${task.state.value}")
                mainShot(dir, "paused")
                Thread.sleep(resumeAfterMs)
                note("still paused after ${resumeAfterMs / 1000}s: ${PageAskModel.pausedForUser(task.state.value)}")
                if (banner) compose.onNodeWithTag("page-paused-resume").performClick() else TaskService.resume(context, task.key)
                resumed = DeviceSupport.pollUntil(5_000) { true.takeIf { !PageAskModel.pausedForUser(task.state.value) } } != null
                note("resumed=$resumed state=${task.state.value}")
                compose.waitForIdle()
                mainShot(dir, "resumed")
            }
            if (s is TaskState.WaitingTakeover && takeovers == 0) {
                takeovers++
                Thread.sleep(800)
                mainShot(dir, "takeover")
                note("takeover requested; action=$onTakeover")
                if (onTakeover == "handback") TaskService.handBack(context, runKey = task.key) else TaskService.cancel(context, task.key)
            }
            if (s is TaskState.Succeeded || s is TaskState.Failed || s == TaskState.Cancelled) {
                end = s
                break
            }
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(250)
        }
        val wall = (System.currentTimeMillis() - t0) / 1000.0
        if (end == null) {
            note("timeout, cancelling")
            TaskService.cancel(context, task.key)
        }
        Thread.sleep(1500)
        watch.cancel()
        synchronized(seen) {
            pauses = seen.size
            userPauses = seen.count(PageAskModel::pausedForUser)
            note("pauses: ${seen.joinToString { (it as TaskState.Held).reason.why }}")
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        mainShot(dir, "end")
        val userTabUrl = browser.page.value?.url
        val origin = app.conversations.pageOrigin(conversationId)
        val urls = recorder.toolCalls.filter { it.first == "web_navigate" }.mapNotNull { Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(it.second)?.groupValues?.get(1) }
        val reopened = urls.any { PageConversations.pageKey(it) == PageConversations.pageKey(url!!) }
        // Go back to the chat page and open the drawer: this conversation carries the "web page" small tag. Collapse the
        // notification shade and heads-up notifications first (they cover it and the drawer cannot be captured);
        // the UI test clock has to be pushed along for the drawer's slide-out animation to finish
        fun menuShown() = compose.onAllNodesWithContentDescription(appString(R.string.chat_menu)).fetchSemanticsNodes().isNotEmpty()
        for (i in 0 until 8) {
            if (menuShown()) break
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
        }
        var listedAsWeb = false
        for (attempt in 1..3) {
            sh("cmd statusbar collapse")
            Thread.sleep(1500)
            runCatching { compose.onNodeWithContentDescription(appString(R.string.chat_menu)).performSemanticsAction(SemanticsActions.OnClick) }
                .onFailure { note("menu not clickable: $it") }
            repeat(8) {
                compose.mainClock.advanceTimeBy(250)
                Thread.sleep(150)
            }
            compose.waitForIdle()
            listedAsWeb = DeviceSupport.pollUntil(5_000) {
                compose.mainClock.advanceTimeBy(250)
                true.takeIf { compose.onAllNodesWithTag("conversation-web", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            } != null
            val drawerOpen = compose.onAllNodesWithTag("drawer").fetchSemanticsNodes().isNotEmpty()
            note("drawer attempt $attempt: open=$drawerOpen webTag=$listedAsWeb")
            if (drawerOpen) break
        }
        mainShot(dir, "drawer")
        val outcome = when (end) {
            is TaskState.Succeeded -> "succeeded"
            is TaskState.Failed -> "failed"
            TaskState.Cancelled -> if (takeovers > 0) "takeover(cancelled)" else "cancelled"
            else -> "timeout"
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        File(dir, "result.txt").writeText(
            "model=${cfg.model}\nurl=$url\ngoal=$goal\noutcome=$outcome\nsteps=${recorder.calls}\nmaxStep=$lastStep\n" +
                "takeovers=$takeovers\nasked=$asked\npauses=$pauses\nuserPauses=$userPauses\nwallSec=$wall\n" +
                "inputTokens=${recorder.inTokens}\noutputTokens=${recorder.outTokens}\n" +
                "borrowed=$borrowed\nfirstToolCalls=${recorder.toolCalls.take(5).joinToString(" ; ") { "${it.first} ${it.second.take(120)}" }}\n" +
                "urlsOpened=${urls.joinToString(" ")}\nreopenedSameUrl=$reopened\nuserTabUrlAtEnd=$userTabUrl\n" +
                "touchAtStep=$touchAt\npausedByTouch=$pausedByTouch\nresumed=$resumed\n" +
                "conversationId=$conversationId\npageOrigin=${origin?.let { "${it.host} ${it.url} ${it.title}" }}\nlistedAsWeb=$listedAsWeb\n" +
                "answer=$answer\n",
        )
        // Claire's own taps and scrolling must not make the task think the user is interacting
        if (touchAt <= 0) check(userPauses == 0) { "the run paused as if the user touched the page ($userPauses times) though nobody did" }
        else check(pausedByTouch && resumed) { "touch at step $touchAt: paused=$pausedByTouch resumed=$resumed" }
    }

    /** Swipes up a short distance with a finger on the web page inside the browser page through the window (the upper half of the page, which the panel does not cover; the swipe does not hit links); returns where it swiped. */
    private fun swipeOnPage(): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        var at: IntArray? = null
        var size = 0 to 0
        instr.runOnMainSync {
            fun find(v: android.view.View): android.webkit.WebView? = when (v) {
                is android.webkit.WebView -> v.takeIf { it.isShown }
                is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
                else -> null
            }
            find(compose.activity.window.decorView)?.let { w ->
                at = IntArray(2).also(w::getLocationOnScreen)
                size = w.width to w.height
            }
        }
        val o = at ?: return "no page view"
        val x = o[0] + size.first / 2f
        val y0 = o[1] + size.second * 0.3f
        val down = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, y: Float, t: Long) {
            val e = android.view.MotionEvent.obtain(down, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            instr.sendPointerSync(e)
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, y0, down)
        for (i in 1..5) send(android.view.MotionEvent.ACTION_MOVE, y0 - i * 12f, down + i * 16L)
        send(android.view.MotionEvent.ACTION_UP, y0 - 60f, down + 100)
        return "x=$x y=$y0"
    }

    /** Everything learned so far: personal skills (in use and drafts), each addition to the app knowledge, and the number of remembered routes. */
    private fun learned(memory: MemoryRuntime): String = buildString {
        val skills = memory.skills()
        skills.list().forEach { appendLine("skill ${it.name} status=${it.status} outcomes=${it.outcomes}\n${it.markdown}\n") }
        skills.drafts().forEach { (id, s) -> appendLine("skill-draft $id ${s.name}\n${s.markdown}\n") }
        memory.appKnowledge().all().forEach { appendLine("app ${it.appName} stage=${it.stage} lesson=${it.lesson} revises=${it.revises} trials=${it.trialOutcomes}\n${it.markdown}\n") }
        appendLine("routes=${(context.applicationContext as MikaApp).routes.count()}")
    }

    /** The titles, texts and buttons of Claire's current notifications (extracted from dumpsys notification). */
    private fun mikaNotifications(): String = sh("dumpsys notification --noredact")
        .split("NotificationRecord(").drop(1).filter { "pkg=com.wangzan101.mika " in it }
        .joinToString(" || ") { rec ->
            rec.lines().map { it.trim() }
                .filter { it.startsWith("android.title=") || it.startsWith("android.text=") || it.startsWith("[") && "\"" in it }
                .joinToString(" | ")
        }.take(1500)

    private fun frame(dir: File, name: String) {
        val bmp = DeviceTaskState.frames.value ?: return
        runCatching { File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun mainShot(dir: File, name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 80, it) }
    }

    /** Records only, changes nothing: every reply of the real model and the previous step's tool results go into the log. */
    private class Recorder(private val note: (String) -> Unit) {
        @Volatile var calls = 0
        @Volatile var inTokens = 0
        @Volatile var outTokens = 0
        /** Tools the model called (name, arguments), in the order they were called. */
        val toolCalls: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())

        fun wrap(inner: ChatModel) = object : ChatModel {
            override val capabilities = inner.capabilities
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.forEach {
                    note("  result${if (it.isError) " ERROR" else ""}: ${it.content.take(400).replace('\n', ' ')}")
                }
                messages.lastOrNull()?.parts?.filterIsInstance<Part.Text>()?.forEach {
                    note("  user/tool text: ${it.text.take(300).replace('\n', ' ')}")
                }
                val t = System.currentTimeMillis()
                val reply = try {
                    inner.complete(system, messages, tools)
                } catch (e: Exception) {
                    note("model error: $e")
                    throw e
                }
                calls++
                reply.usage?.let { inTokens += it.inputTokens; outTokens += it.outputTokens }
                note("call#$calls ${System.currentTimeMillis() - t}ms stop=${reply.stopReason}")
                reply.parts.forEach { p ->
                    when (p) {
                        is Part.Text -> note("  say: ${p.text.take(500).replace('\n', ' ')}")
                        is Part.ToolCall -> {
                            toolCalls += p.name to p.argsJson
                            note("  tool: ${p.name} ${p.argsJson.take(300)}")
                        }
                        is Part.Thinking -> note("  think: ${p.text.take(200).replace('\n', ' ')}")
                        else -> Unit
                    }
                }
                return reply
            }
        }
    }
}
