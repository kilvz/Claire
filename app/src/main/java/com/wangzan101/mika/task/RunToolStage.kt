package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.screen.uistate.PlacePace
import kotlin.random.Random
import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.settings.ui.StatusCopy
import com.wangzan101.mika.cap.sandbox.GrantedFolderFiles
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.phone.setup.RootShell
import com.wangzan101.mika.cap.root.RootCommands
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.wechat.explicitWechatOwnerRequest
import com.wangzan101.mika.wechat.AndroidWechatOwnerPort
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.media.BitmapImageOps
import com.wangzan101.mika.cap.web.LazyWebPort
import com.wangzan101.mika.cap.web.WebStack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.WEB

// Run assembly: tool dispatcher
internal suspend fun TaskRun.assembleTools(a: RunAssembly, m: RunModels, ph: RunPhone, mem: RunMemory, rec: RunRecord, pr: RunPrompt, web: LazyWebPort?): RunTools {
    var smsLogin by a::smsLogin
    var webStack by a::webStack
    var dispatcher by a::dispatcher
    var approval by a::approval
    val resources = a.resources
    val approvalControl = a.approvalControl
    val side = a.side
    val model = m.model
    val vision = m.vision
    val visionFallbacks = m.visionFallbacks
    val appAccess = ph.appAccess
    val metrics = ph.metrics
    val pace = ph.pace
    val accessedPhone = ph.accessedPhone
    val privatePhone = ph.privatePhone
    val speed = mem.speed
    val memory = mem.memory
    val taskMemory = mem.taskMemory
    val utterances = mem.utterances
    val catalog = mem.catalog
    val sink = rec.sink
    val userScreens = pr.userScreens
    val basePrompt = pr.basePrompt
    val personaLabel = pr.personaLabel
    val runSpec = pr.runSpec
    // External MCP servers are only connected for the main task; subtasks/condition checks/evals don't connect them, to avoid bypassing the parent task's approvals and hurting eval reproducibility
    if (!conditionCheck) {
        // Connected ones belong to resources right away: they are closed when the scope is torn down (including cancellation, failure and being stopped mid-connect), releasing the stdio server processes and sandbox leases with them
        mcpConnections = (service.application as MikaApp).mcpServers.connectionsFor(
            resources,
            com.wangzan101.mika.mcp.ConversationMcpResultStore(SandboxDirs(service), workspaceKey),
            workspaceKey.wire,
        )
        // Servers that failed to connect or didn't connect before the overall deadline only get a one-line note on the task card, without interrupting the task; details are in the health status on the settings page
        mcpConnections?.failures?.takeIf { it.isNotEmpty() }?.let { failures ->
            val store = (service.application as MikaApp).mcpServers
            val names = store.servers.value.associate { it.id to it.name }
            (runState._state.value as? TaskState.Running)?.let {
                publish(it.copy(note = tr(R.string.mcp_servers_run_failed, failures.joinToString(", ") { f -> names[f.first] ?: f.first })))
            }
        }
    }
    // When the conversation has notice alerts (third-party text) it is treated like a task handed over from the computer: no matter whether "autonomous mode" is on, payments still ask first
    val runApproval = approvalControl.start(alwaysAsk = provenance.origin == RunOrigin.Mcp || noticeTainted).also { approval = it }
    // Plugins: environment variables, tool specs and the prompt hook share the same script entry point
    val pluginRunner = com.wangzan101.mika.plugin.PluginScriptRunner(
        envProvider = { com.wangzan101.mika.settings.EnvVarStore.get(service).all() })
    val pluginTools = (service.application as MikaApp).plugins.toolSpecs()
    val promptHook = com.wangzan101.mika.plugin.PluginPromptHook(
        (service.application as MikaApp).plugins, pluginRunner, { model.name },
        { com.wangzan101.mika.settings.EnvVarStore.get(service).all() })
    // Cap on the cumulative sandbox command time within one task (Settings › While working, 30 minutes by default); the main task and the subtasks it spawns share the same tier but accumulate independently
    val sandboxBudgetMs = FeatureFlags.get(service).sandboxBudgetMinutes() * 60_000L
    val childRunner = if (provenance.origin == RunOrigin.Ui && web != null) ChildAgentRunner(
        create = { childSpec, childResources ->
            val childWeb = if (childSpec.parallelChild) {
                WebStack.childPort(service, childSpec.id.value, metrics.widthPixels, metrics.heightPixels,
                    metrics.densityDpi, childResources)
            } else web
            val childTools = toolDispatcher(privatePhone, model.capabilities.vision,
                web = childWeb, webPacing = { PagePacing.human() }, webPlacePace = { PlacePace.of(catalog.sitePace(it)) },
                webIdleScroll = { IdleScroll(Random.Default) },
                appPacing = { pace.appPacing() }, appTempo = { pace.of(it).tempo }, appCapped = { pace.capped(it) }, appIdleScroll = { IdleScroll(Random.Default) },
                sandbox = sandbox, workspace = { workspaceKey.wire }, runSpec = childSpec,
                conversationId = conversationId, inlineScreen = speed.inlineScreen,
                sandboxBudgetMs = sandboxBudgetMs,
                adaptiveVision = speed.adaptiveVision)
            childTools.manualControl = manualControl
            AgentLoop(model, childTools, basePrompt, maxSteps = childSpec.stepBudget ?: AgentLoop.UNLIMITED_STEPS,
                // Subtasks follow ask/autonomous; under ask they go through the gate too, and any step that spends money is never performed in a subtask
                confirmations = runApproval.gate(), approvals = runApproval, autonomous = runApproval.startedAutonomous,
                promptTransform = { promptHook.transform(it) },
                reviewFinalAnswer = false, flags = speed, outwardPacing = OutwardPacing.human(), personaLabel = personaLabel)
        },
        store = (service.application as MikaApp).childRuns,
        ownerAlive = { job?.isActive == true },
    ).also { children = it } else null
    // Self port: only for UI main tasks on the phone with a user present; subtasks, condition checks, scheduled, channel and MCP tasks don't get it.
    // Read-only state tools are always provided; operating tools wait until the user enables "allow Claire to operate its own UI".
    val selfPort = if (provenance.origin == RunOrigin.Ui && !conditionCheck) {
        (service.application as? MikaApp)?.let { app -> com.wangzan101.mika.self.AndroidSelfPort(app, conversationId, key) }
    } else null
    val deviceImports = if (provenance.origin == RunOrigin.Ui &&
        provenance.attendance != Attendance.Unattended && !runSpec.readOnly) {
        DeviceWorkspaceImports(SandboxDirs(service), sandbox::freeBytes, DeviceImportStore.get(service),
            resolveWorkspace = { workspaceKey }, conversationIdFor = { conversationId },
            limits = { FeatureFlags.get(service).attachmentLimits() }) // read the tier fresh on each import, the same set as chat attachments
    } else null
    val folders = sandbox.folderFiles as? GrantedFolderFiles
    val tools = toolDispatcher(privatePhone, model.capabilities.vision, catalog, recordFrames = true,
        vision = vision?.let {
            VisionService(it, BitmapImageOps, fallbacks = visionFallbacks, onModelSwitch = { from, to, _ ->
                sink.event(RunEvent.State(System.currentTimeMillis(), "vision_fallback", "${from.name} -> ${to.name}"))
            })
        },
        smsLogin = if (provenance.origin == RunOrigin.Ui && !conditionCheck && !runSpec.readOnly) {
            com.wangzan101.mika.cap.phone.sms.AndroidSmsLogin(service, phoneServices.smsLogin, appAccess,
                accessedPhone, privatePhone) { phone?.screen }.also { smsLogin = it }
        } else null,
        phoneCalls = (service.application as MikaApp).callPort.takeIf {
            !conditionCheck && (service.application as MikaApp).calls.settings.state.value.enabled
        },
        // Only when settings allow Claire to change itself are settings_list and settings_set exposed to the model
        selfConfig = if (FeatureFlags.get(service).selfConfig()) {
            com.wangzan101.mika.settings.SelfConfigImpl(FeatureFlags.get(service))
        } else null,
        stableRefs = speed.inlineScreen, inlineScreen = speed.inlineScreen,
        adaptiveVision = speed.adaptiveVision,
        deepLinks = speed.deepLinks, memory = memory, profile = if (memory != null) memoryRuntime.profile() else null,
        taskMemory = taskMemory,
        utterances = utterances, conversationId = conversationId,
        web = web,
        // Reading the same site page by page: top up the dwell time before changing pages instead of opening them back to back
        webPacing = { PagePacing.human() },
        // While dwelling, scroll the page gently a few times like a human
        webIdleScroll = { IdleScroll(Random.Default) },
        // Operating inside apps step by step: pause between actions, pause longer in apps that throttle browsing speed, with a cap on the number of screens
        // (global tier + per-app overrides in settings)
        appPacing = { pace.appPacing() },
        appTempo = { pace.of(it).tempo },
        appCapped = { pace.capped(it) },
        appIdleScroll = { IdleScroll(Random.Default) },
        // Uploads only accept regular files inside the current workspace (same path rules as the sandbox); the skills directory and anything outside the workspace are out of bounds
        workspacePath = web?.let { { p: String -> sandbox.uploadCheck(workspaceKey, p) } },
        // Conversations in the same project share p:<projectId>; the rest are isolated per conversation
        sandbox = sandbox, workspace = { workspaceKey.wire },
        sandboxBudgetMs = sandboxBudgetMs,
        deviceFiles = if (deviceImports != null && FeatureFlags.get(service).deviceFiles()) {
            AndroidDeviceFiles(phoneServices.hub, com.wangzan101.mika.settings.DeviceDirectoryStore.get(service), deviceImports)
        } else null,
        folderFileImporter = if (deviceImports != null && folders != null) {
            FolderFileImporter { path, workspace -> deviceImports.fromFolder(path, workspace, folders::openForImport) }
        } else null,
        audio = audioPort,
        image = imagePort,
        imageSaveEnabled = imageSettings.state.value.saveEnabled,
        imageAskBeforeSave = imageSettings.state.value.askBeforeSave,
        imageMaxPerTask = imageSettings.state.value.maxPerTask,
        transcribeProvider = ::transcribePort,
        audioSaveEnabled = audioSettings.state.value.saveEnabled,
        audioAskBeforeSave = audioSettings.state.value.askBeforeSave,
        audioSpeakTool = audioSettings.state.value.speakTool,
        onSandboxCommand = { phoneRuntime.sandboxNote(StatusCopy.sandboxCommand(it, phoneRuntime.sandboxOnline())) },
        onSandboxProgress = { phoneRuntime.sandboxNote(StatusCopy.sandboxProgress(it, phoneRuntime.sandboxOnline())) },
        onSandboxPreparing = { phoneRuntime.sandboxNote(StatusCopy.RUNTIME_PREPARING) },
        onSandboxScript = { phoneRuntime.sandboxNote(StatusCopy.sandboxScript(phoneRuntime.sandboxOnline())) },
        imageOps = BitmapImageOps, runSpec = runSpec,
        userScreens = userScreens,
        scheduleTrigger = if (provenance.origin == RunOrigin.Ui) { trigger -> TriggerRuntime.schedule(service, trigger) } else null,
        scheduleAdmin = if (provenance.origin == RunOrigin.Ui) LocalScheduleAdmin(service) else null,
        childHost = childRunner,
        // Personal skills written down or rewritten at the user's request (stored in the same place as the self-learned ones, immediately visible in settings)
        personalSkills = memoryRuntime.skills(),
        // Run records from earlier turns of this conversation (read-only; history_read only accepts turns of this conversation that are in runSpec)
        runRecords = RunStore(service).reader(),
        // Raw notices from the last two days plus the stored daily, weekly and monthly digests (accumulated by the proactive feature): only for tasks the user started on the phone; not touched when the proactive feature is off
        notices = if (provenance.origin == RunOrigin.Ui && !conditionCheck && FeatureFlags.get(service).proactive()) {
            (service.application as MikaApp).proactive.noticeSource()
        } else null,
        // Owner result push: only for phone UI main tasks where the user explicitly asked to "send it to my WeChat"; the binding snapshot, re-check and same-text idempotency all live on the sender side
        wechatOwner = if (provenance.origin == RunOrigin.Ui && !conditionCheck && explicitWechatOwnerRequest(goal)) {
            AndroidWechatOwnerPort(service.application as MikaApp, key)
        } else null,
        mcpServers = mcpEndpoints(),
        // General search is only offered when the user has explicitly configured and enabled it; otherwise browser search keeps being used.
        webSearch = com.wangzan101.mika.settings.WebSearchSettings.get(service).service(),
        // Root tools are only registered when the user has enabled "allow Claire to run root commands" on a rooted phone (not given to subtasks or condition checks)
        root = phoneServices.shellManager.root.takeIf { !conditionCheck && it.commandsAllowed() }
            ?.let { r -> RootCommands(RootShell::su, r::commandsAllowed) { a -> if (a == null) r.suGone() else r.commandsDenied(a) } },
        // See/operate Claire itself; only read-only state is provided while the switch is off
        self = selfPort,
        selfOperate = selfPort?.takeIf { FeatureFlags.get(service).selfOperate() },
        pluginTools = pluginTools, pluginScriptRunner = pluginRunner,
        pluginArgsDir = java.io.File(service.cacheDir, "plugin-args"))
    dispatcher = tools
    tools.manualControl = manualControl
    manualTargetNow = target@{
        if (stopPressed || job?.isActive != true || takeover?.handedOver == true) return@target null
        val frame = runState._frames.value ?: return@target null
        if (dispatcher?.surface == WEB) {
            val stack = webStack ?: return@target null
            val tab = stack.pool.current(key) ?: return@target null
            if (!runState._webOpen.value || stack.handedOver || stack.onBorrowedTab) return@target null
            TaskRun.ManualTarget(stack.screen, "web:${tab.id}", frame.width, frame.height, stack.host.width, stack.host.height)
        } else {
            val p = phone ?: return@target null
            val display = p.display.current ?: return@target null
            if (runState._webOpen.value || !a.previewAllowed()) return@target null
            TaskRun.ManualTarget(accessedPhone, "phone:${display.displayId}", frame.width, frame.height, display.width, display.height)
        }
    }
    side.launch { manualControl.state.collect { loop?.state?.value?.let(::publish) } }
    return RunTools(runApproval, promptHook, tools)
}
