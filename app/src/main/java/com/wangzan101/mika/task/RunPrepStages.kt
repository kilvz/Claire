package com.wangzan101.mika.task

import com.wangzan101.mika.cap.sandbox.SandboxDirs
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.prompt.PersonaPrompt
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.learn.knowledge.KnowledgeStore
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CancellationException
import androidx.webkit.WebViewCompat
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebStack
import com.wangzan101.mika.cap.web.KeptOrigin
import com.wangzan101.mika.cap.web.WebTab
import com.wangzan101.mika.browser.PageLoans
import com.wangzan101.mika.browser.UserBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.task.TaskRun.Companion.WEB_FRAME_MS
import com.wangzan101.mika.task.TaskRun.Companion.TAG

// Run assembly: models, run record, memory and knowledge, web, prompt
internal suspend fun TaskRun.assembleModels(): RunModels? {
    val model = env.modelFactory(service, session) ?: return null
    // The user asked about a page on a browser tab: that page is lent to this task as its current web tab (see PageLoans); it can only be borrowed if the page is still open when the run starts
    val pageLoan = if (provenance.origin == RunOrigin.Ui) withContext(Dispatchers.Main) { UserBrowser.existing()?.claim(conversationId, key) } else null
    val light = env.lightModelFactory(service, session)
    val decision = env.decisionModel(service)
    // Splitting the same model into two instances is neither cheaper nor faster; denying it to batch/finishing tools would only add more reassignments and step-by-step work.
    // This only skips the step-level split; the light role is still available for auxiliary calls such as intent detection, titles and finishing.
    val routedLight = light?.takeIf {
        !ProviderStore.get(service).roles.value.sameModel(ModelRole.Main, ModelRole.Light)
    }
    val vision = env.visionModelFactory(service, session)
    val visionFallbacks = env.visionFallbackFactory(service, session)
    val lightFallbacks = env.lightFallbackFactory(service, session)

    val (knowledge, skills) = withContext(Dispatchers.IO) {
        // The user's additions for a particular app/site live under the private directories personal/apps and personal/sites
        val userNotes = KnowledgeStore.loadNotes(File(service.filesDir, "personal"))
        // In self-learned app knowledge, tool names written before the rename are replaced with the current ones
        val learnedNotes = legacyNames().let { legacy -> memoryRuntime.appKnowledge().notes().mapValues { it.value.copy(text = legacy.rewrite(it.value.text)) } }
        KnowledgeStore(KnowledgeStore.loadBundled(), userNotes, learnedNotes) to GuideCatalog.loadBundled()
    }
    return RunModels(model, pageLoan, light, decision, routedLight, vision, visionFallbacks, lightFallbacks, knowledge, skills)
}

internal suspend fun TaskRun.assembleRecord(a: RunAssembly): RunRecord {
    var record by a::record
    var runStore by a::runStore
    val resources = a.resources
    val traces = a.traces
    val traceTicket = a.traceTicket
    // If the record can't be opened, don't record; the task still runs
    val sink: RunSink = withContext(Dispatchers.IO) {
        runCatching {
            val runs = RunStore(service).also { runStore = it }
            runs.open(goal).also { opened ->
                record = opened
                runState._runId.value = opened.runId
                service.rememberRecord(this@assembleRecord, opened.runId)
                mcpExternalId?.let { ext ->
                    (service.application as? com.wangzan101.mika.MikaApp)?.remoteTasks?.rememberRun(ext, opened.runId)
                }
            }
        }.onFailure { Log.w(TAG, "run record unavailable, running without it", it) }.getOrNull()
    } ?: RunSink.NONE
    val trace = record?.takeIf { traceTicket != null }?.let { run ->
        resources.acquire(Dispatchers.IO, traces.store::finish) { traces.start(traceTicket, run.runId) }
    }
    return RunRecord(sink, trace)
}

internal suspend fun TaskRun.assembleMemory(m: RunModels, ph: RunPhone): RunMemory {
    val skills = m.skills
    val phoneScreen = ph.phoneScreen
    // Which apps are installed: read once per task, treated as none if it can't be read; used as reference material
    val apps = try { phoneScreen.listApps() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    val speed = FeatureFlags.get(service).speedFlags()
    // Memory and personal skills: one shared copy per process; with the master switch off there is no memory (no retrieval, no injection, no extraction, no memory tools)
    val switches = memoryRuntime.settings().switches.value.let { sw ->
        sw.copy(learnSkills = env.evalHooks.learnSkills ?: sw.learnSkills, autoRemember = env.evalHooks.rememberMemory ?: sw.autoRemember)
    }
    val memory = if (switches.useMemory) withContext(Dispatchers.IO) { memoryRuntime.store() } else null
    val taskMemory = if (switches.taskMemoryEnabled) withContext(Dispatchers.IO) { memoryRuntime.taskMemory() } else null
    val profileSnapshot = if (switches.useMemory) withContext(Dispatchers.IO) {
        runCatching { memoryRuntime.profile().snapshot() }.onFailure { Log.w(TAG, "profile unavailable", it) }.getOrDefault(emptyList())
    } else emptyList()
    // What the user said this time: citations for memory and for skills written down at the user's request are checked against it (in memory only, cleared when the task starts)
    val utterances = UserUtterances()
    // Personal skills distilled before the rename: old tool names inside are swapped for the current ones at load time
    // Plugin skills use a snapshot taken at task start: enabling/disabling plugins mid-task only takes effect on the next task
    val catalog = GuideCatalog.withPersonal(
        skills, memoryRuntime.skills(), legacyNames()::rewrite,
        plugins = (service.application as MikaApp).plugins.skillsSnapshot(),
    )
    return RunMemory(apps, speed, switches, memory, taskMemory, profileSnapshot, utterances, catalog)
}

internal suspend fun TaskRun.assembleWeb(a: RunAssembly, ph: RunPhone): LazyWebPort? {
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    val resources = a.resources
    val side = a.side
    val metrics = ph.metrics
    // Built-in browser: each task's own background web surface, tab pool and script engine, created only when a URL is first opened (tasks that only use the phone never create it);
    // web tools are not offered when the system has no WebView
    val webInstalled = runCatching { WebViewCompat.getCurrentWebViewPackage(service) != null }.getOrDefault(false)
    val web = if (!webInstalled) null else LazyWebPort {
        val stack = resources.acquire(Dispatchers.Main, WebStack::close) { WebStack.create(
                service, key, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                // Downloads and uploads go through the workspace resolved for the current task: just a folder in the app's private directory, not requiring the sandbox to be installed
                workspace = SandboxDirs(service).let { d -> WebStack.Workspace(d.workspace(workspaceKey), d.gate, d.tree) },
            ) }
        webStack = stack
        // The page the model marked with web_keep_open stays in the background when the task ends, recorded under this turn (shown under that turn in the conversation, with a note when it's closed later)
        stack.keepOrigin = { KeptOrigin(conversationId, runState._runId.value, goal) }
        side.launch {
            // On the web surface, picture-in-picture shows what the web surface is displaying; clear the previous surface's frames when switching; see WebPreview for the display rules
            var last: String? = null
            while (true) {
                delay(WEB_FRAME_MS)
                val surface = dispatcher?.surface
                if (surface == WEB) runState._phoneFrames.value = null
                if (surface != last) {
                    runState._frames.value = null
                    last = surface
                }
                // When the current tab is the borrowed page it's right in front of the user, so no frames are shown, same as during handover
                val show = WebPreview.of(onWeb = surface == WEB, hasTab = stack.hasVisibleTab, handedOver = stack.handedOver || stack.onBorrowedTab)
                when (show) {
                    WebPreview.Frames -> withContext(Dispatchers.IO) { stack.host.latestFrame() }?.let { runState._frames.value = it }
                    WebPreview.Hidden, WebPreview.NoPage -> runState._frames.value = null
                    WebPreview.Off -> Unit
                }
                runState._webOpen.value = show.open
            }
        }
        stack.screen
    }
    return web
}

internal suspend fun TaskRun.lendPage(a: RunAssembly, m: RunModels, ph: RunPhone, web: LazyWebPort?) {
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    val pageLoan = m.pageLoan
    val phoneScreen = ph.phoneScreen
    // Borrowed page: build the whole web stack up front and borrow it in as the current tab; pause when the user touches it (not counted while operating on the phone screen at that moment)
    if (pageLoan != null && web != null && web.prepare()) webStack?.let { stack ->
        withContext(Dispatchers.Main) {
            stack.borrowedTouches = { pageLoan.touches }
            UserBrowser.existing()?.loans?.bind(pageLoan, object : PageLoans.Borrower<WebTab> {
                override fun take(tab: WebTab) = stack.borrow(tab)
                override fun giveBack(tab: WebTab) = stack.giveBack(tab)
                override fun swap(prev: WebTab, next: WebTab) = stack.swapBorrowed(prev, next)
                override fun adopt(tab: WebTab) = stack.adopt(tab)
                override fun touched() {
                    if (stack.onBorrowedTab && !(phoneScreen.acquired && dispatcher?.surface != WEB)) phoneRuntime.userOnPage()
                }
            })
        }
    }
}

internal suspend fun TaskRun.assemblePrompt(a: RunAssembly, priorTurns: List<Turn>): RunPrompt {
    val tokenBudget = a.tokenBudget
    // Screens the user sent in this conversation (stored with the conversation in the workspace)
    val userScreens = (service.application as MikaApp).userScreens.source(conversationId)
    // The persona is the active one selected in settings and is the same for every conversation; condition checks are internal read-only JSON checks and don't inherit a persona.
    // Evals that swap the model provider don't carry it either (same as the fallback chain): whatever persona is selected on this device must not change eval results.
    // Bindings previously stored in the file beside the conversation are no longer read.
    val persona = if (conditionCheck || !FeatureFlags.get(service).persona() ||
        env.modelFactory !== TaskEnvironment.defaultModelFactory) null
    else (service.application as MikaApp).personas.active()
    // The nickname is the assistant's self-designation (Claire by default): both the identity sentence and the fixed header of the persona layer use it.
    val assistant = PersonaPrompt.nickname(persona?.nickname)
    // The persona is appended after identity, guidelines and general rules; subtasks and the main task use the same one.
    // When all options are at their defaults and the body is empty, there is no layer to append.
    val personaLayer = persona?.let { PersonaPrompt.layer(it.name, it.prompt, it.style, assistant) }
    val appLang = AppLanguage.current(service)
    val promptWithPersona = SystemPrompt.withPersona(SystemPrompt.identity(assistant), personaLayer)
    val basePrompt = SystemPrompt.withLanguage(promptWithPersona, appLang.tag)
    // The persona was used this turn: a persona layer was composed, or only the nickname changed; if neither, record nothing
    val personaLabel = if (persona != null && (personaLayer != null || assistant != PersonaPrompt.DEFAULT_NICKNAME)) {
        Redactor.text("${persona.name} · v${persona.revision}")
    } else null
    val stepLimit = if (conditionCheck) null else FeatureFlags.get(service).taskStepLimit()
    val runSpec = RunSpec(RunId(key), goal, conversationId,
        priorTurns,
        provenance, if (conditionCheck) setOf("web", "sandbox") else null, readOnly = conditionCheck,
        stepBudget = stepLimit, tokenBudget = tokenBudget)
    return RunPrompt(userScreens, basePrompt, personaLabel, runSpec)
}
