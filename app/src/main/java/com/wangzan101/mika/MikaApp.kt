package com.wangzan101.mika

import android.app.Application
import android.net.Uri
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.sandbox.AndroidSandbox
import com.wangzan101.mika.cap.sandbox.AttachmentLimits
import com.wangzan101.mika.cap.sandbox.Attachments
import com.wangzan101.mika.cap.sandbox.RuntimePreparer
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.SandboxOwner
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.sandbox.SkillsSync
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.cap.web.SiteRegistry
import com.wangzan101.mika.cap.web.WebFiles
import com.wangzan101.mika.cap.web.WebOwner
import com.wangzan101.mika.cap.web.cdp.WebDevtools
import com.wangzan101.mika.capsule.AppForeground
import com.wangzan101.mika.capsule.CapsuleController
import com.wangzan101.mika.chat.AndroidBranchWorkspace
import com.wangzan101.mika.chat.AutoArchive
import com.wangzan101.mika.chat.ConversationDeleter
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.chat.CurrentChat
import com.wangzan101.mika.chat.ProjectDeleter
import com.wangzan101.mika.chat.ProjectStore
import com.wangzan101.mika.chat.SharedDraftStore
import com.wangzan101.mika.chat.WorkspaceResolver
import com.wangzan101.mika.frontdesk.DeskRegistry
import com.wangzan101.mika.task.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.run.Outbox
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.model.usage.UsageLedger
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.i18n.ResourceTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.memory.AppLearningActions
import com.wangzan101.mika.memory.MemoryRuntime
import com.wangzan101.mika.record.DetailedTraces
import com.wangzan101.mika.knowledge.FileRouteStore
import com.wangzan101.mika.settings.ApprovalControl
import com.wangzan101.mika.settings.ApprovalStore
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.modelFor
import com.wangzan101.mika.settings.proactiveTarget
import com.wangzan101.mika.settings.proactiveTargeted
import com.wangzan101.mika.settings.ttsModel
import com.wangzan101.mika.task.FileChildRunStore
import com.wangzan101.mika.task.FileOutboxStore
import com.wangzan101.mika.task.FileRunStateStore
import com.wangzan101.mika.task.LocalChannel
import com.wangzan101.mika.task.RemoteTaskHub
import com.wangzan101.mika.task.ResourceScheduler
import com.wangzan101.mika.task.RunRecovery
import com.wangzan101.mika.task.TaskCenterState
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.task.TaskRecovery
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TriggerOutcomes
import com.wangzan101.mika.task.TriggerRuntime
import com.wangzan101.mika.teach.TeachRuntime
import com.wangzan101.mika.chat.ui.AttachFiles
import com.wangzan101.mika.chat.ui.ChatCopy
import com.wangzan101.mika.chat.ui.ChatViewModel
import com.wangzan101.mika.chat.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App container: services shared across the process are each created once here and handed to the Android entry points
 * (UI, services, broadcasts, providers) through their respective Owner interfaces,
 * while the rest of the code receives them via constructor parameters. The assistant process (HelperMain,
 * ShellService) does not have this object and uses none of the services here.
 */
class MikaApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner,
    com.wangzan101.mika.settings.VoiceSettings.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A scope that outlives pages: cleanup that must finish after a page is destroyed (such as writing back a call transcript) goes here. */
    val appScope: CoroutineScope get() = scope

    /** Phone capability services: the built-in assistant, the shared shell connection, pairing, and the "go to settings" prompt. */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** DevTools session entry point for web tabs: one per process; when it cannot connect, it connects via the built-in assistant. */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** Upper bound on the total number of tabs shared by the tab pools of all tasks. */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }
    internal fun workflowInterrupted(attemptId: String) {
        scope.launch(Dispatchers.IO) { workflows.complete(attemptId, false, "", "interrupted") }
    }
    val workflows by lazy {
        com.wangzan101.mika.workflow.WorkflowRuntime(
            com.wangzan101.mika.workflow.WorkflowStore(
                java.io.File(filesDir, com.wangzan101.mika.workflow.WorkflowStore.FILE),
                com.wangzan101.mika.settings.KeystoreKeyCipher("mika-workflows"),
            ),
        ) { dispatch -> TaskService.startWorkflow(this, dispatch.attemptId) }
    }


    /** Call feature: the settings page owns the switch and credentials; CallRuntime hosts local state and real-time voice, and AndroidPhoneCallPort is the tool entry point given to the model. */
    private val callRuntime = lazy { com.wangzan101.mika.calls.CallRuntime(this) }
    val calls by callRuntime
    internal val runningCalls get() = if (callRuntime.isInitialized()) calls else null
    val callPort by lazy { com.wangzan101.mika.calls.AndroidPhoneCallPort(calls) }

    /** Conversations and running tasks read by the Web console; read-only, and it takes no part in task execution. */
    val remoteCompanion by lazy {
        com.wangzan101.mika.chat.RemoteConversationReader(conversations) { taskState.runs.value }
    }

    /** WeChat channel: credentials, listener and channel adapter are one per process; tasks are still executed by TaskService. */
    val wechat by lazy {
        com.wangzan101.mika.wechat.WechatRuntime(this,
            com.wangzan101.mika.wechat.WechatStore(java.io.File(filesDir, "wechat"),
                com.wangzan101.mika.settings.KeystoreKeyCipher("mika-wechat-account")))
    }

    /** Telegram channel: bot token, listener and channel adapter are one per process; tasks are still executed by TaskService. */
    val telegram by lazy {
        com.wangzan101.mika.telegram.TelegramRuntime(this,
            com.wangzan101.mika.telegram.TelegramStore(java.io.File(filesDir, "telegram"),
                com.wangzan101.mika.settings.KeystoreKeyCipher("mika-telegram-token")))
    }

    /** Device-authorized screen wake/unlock; the computer connection and unattended scheduled tasks share the same deterministic flow. */
    val deviceAccess by lazy { com.wangzan101.mika.device.AndroidDeviceAccess(this) { phone.hub.shell.value } }

    /**
     * Computer connection (MCP): switch, access token (encrypted and stored like the providers' keys), listener. The LAN
     * kind is off by default;
     * the USB kind is turned on by the computer via adb and lasts only until this shutdown (the boot id is the same as
     * what `settings get global boot_count` reads in the shell).
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        val settings = com.wangzan101.mika.settings.McpSettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-token"))
        val history = com.wangzan101.mika.task.PhoneHistory(conversations, com.wangzan101.mika.record.RunStore(this), detailedTraces.store,
            allowed = { settings.historyEnabled.value }, live = { remoteTasks.list(100) })
        com.wangzan101.mika.mcp.McpRuntime(this, settings,
            com.wangzan101.mika.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.wangzan101.mika.core.mcp.McpUsb.INFO_FILE) } },
                migrateTaskOwner = remoteTasks::migrateUsbOwner),
            remoteTasks, deviceAccess,
            com.wangzan101.mika.settings.RelaySettings(settingsDir, com.wangzan101.mika.settings.KeystoreKeyCipher("mika-remote-relay")),
            history, callPort, remoteCompanion)
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }

    /** Settings for external MCP servers (separate from the inbound settings of "Connect computer"). */
    val mcpServers by lazy {
        com.wangzan101.mika.settings.McpServerStore(
            java.io.File(filesDir, "settings"),
            com.wangzan101.mika.settings.KeystoreKeyCipher("mika-mcp-servers"),
            stdio = com.wangzan101.mika.core.cap.mcp.McpStdioLauncher { workspace, argv, environment ->
                val process = AndroidSandbox(this, SandboxState.get(this)).openProcess(workspace, argv, null, environment)
                object : com.wangzan101.mika.core.cap.mcp.McpStdioProcess {
                    override val stdin get() = process.stdin
                    override val stdout get() = process.stdout
                    override val stderr get() = process.stderr
                    override fun close() = process.close()
                }
            },
            catalog = com.wangzan101.mika.settings.McpCatalogAssets.load(this),
            packageProvisioner = com.wangzan101.mika.mcp.McpPackageManager(AndroidSandbox(this, SandboxState.get(this))),
        )
    }
    /** Installed plugins: zip installation, enable/disable and read-only skills; at the start of a task the skill catalog gets a fixed snapshot. */
    val plugins by lazy {
        com.wangzan101.mika.plugin.PluginRegistry(filesDir, scope,
            log = { msg, e -> Log.w("MikaPlugin", msg, e) })
    }

    /** Personas: named custom prompt layers, stored in the settings directory; not encrypted and containing no keys. */
    val personas by lazy { com.wangzan101.mika.settings.PersonaStore(java.io.File(filesDir, "settings")) }
    override val tabBudget get() = resources.tabBudget

    /** The page left running in the background after a task ends; when it is closed, the reason is recorded on the turn of the source conversation (the conversation page shows "background web page closed" under that turn). */
    override val pageKeeper: com.wangzan101.mika.cap.web.PageKeeper by lazy {
        com.wangzan101.mika.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("MikaApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** Memory, personal skills, switches and result card hints. */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** Local token usage ledger: every call of every model built through the model configuration is recorded here. */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** Model configuration and keys; every model built is wrapped with metering that records into [usage]. */
    override val providers: ProviderStore by lazy {
        ProviderStore.create(this, usage, "Claire/${BuildConfig.VERSION_NAME}", usage::providerTokensToday,
            proactiveUsage = usage::proactiveTokensToday, proactiveLimit = { FeatureFlags.get(this).proactiveDailyTokenBudget() },
            proactiveCalls = usage::proactiveCallsToday).also { store ->
            voiceSettings.reconcileTtsChoice(store)
            scope.launch(Dispatchers.Main.immediate) {
                store.providers.collect { voiceSettings.reconcileTtsChoice(store) }
            }
        }
    }

    /** Speech-to-text choice: one per process, so a change on the settings page is immediately visible to the input field (two instances cannot see each other's set). */
    val speech: com.wangzan101.mika.voice.SpeechSettings by lazy { com.wangzan101.mika.voice.SpeechSettings.get(this) }

    /** Entry point for voice input, shared by the input field and the floating capsule. */
    val voice: com.wangzan101.mika.voice.VoiceInput by lazy { com.wangzan101.mika.voice.VoiceInput(this, speech, providers) }

    /** Sign in with ChatGPT: runs in the App's scope, keeping the process alive with a foreground service during that time (see [com.wangzan101.mika.settings.ChatGptSignIns]). */
    val chatGptSignIn by lazy {
        com.wangzan101.mika.settings.ChatGptSignIns(scope,
            keepAlive = { com.wangzan101.mika.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("MikaChatGpt", msg, e) })
    }

    /** Learned routes. */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** Recovery of system changes left behind by an abnormal exit; running tasks are registered here. */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** Run ledger and delivery ledger: one per process, shared by the task service and startup recovery (the same file must not have two instances). */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** Persistent queue for the next task in the same conversation: both user-commanded queuing and steering fallback live here, shared by the task service and the UI. */
    val followUpQueue: com.wangzan101.mika.task.FollowUpQueue by lazy {
        com.wangzan101.mika.task.FollowUpQueue(
            com.wangzan101.mika.task.FileFollowUpQueueStore(java.io.File(filesDir, "followups-v1.json")),
        )
    }

    /** Cleanup of unfinished runs (mark interrupted, redeliver, notify): done only once per process, called from both App startup and task service startup. */
    val runRecovery: RunRecovery by lazy {
        val app = this
        fun localChannel(scheduled: Boolean) = LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId(), scheduled)
        }
        val local = localChannel(scheduled = false)
        val labels = com.wangzan101.mika.ui.ToolLabels(com.wangzan101.mika.core.assembly.knownTools())
        RunRecovery(runStore, outbox, { id -> if (id == LocalChannel.ID) local else wechat.channelFor(id) ?: telegram.channelFor(id) }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope, records = com.wangzan101.mika.record.RunStore(app).reader(), label = labels::label,
            appLabel = { com.wangzan101.mika.task.ConfirmationNotifier.appLabel(app, it) },
            languageTag = { com.wangzan101.mika.i18n.AppLanguage.resolved(app).tag },
            scheduledLocalChannel = localChannel(scheduled = true))
    }

    /** Site registry (websites logged into, etc.): shared by the settings page and the tab pools. */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** Tree lock for `files/sandbox/`: installation, reset, commands, downloads and attachments all write under its permit. */
    override val sandboxTree = SandboxTree()

    /** Model source the task gets from the App, evaluation hooks, and the screen-frame switch. */
    override val tasks = TaskEnvironment()

    /** Sandbox state: updated by the install service, and the settings page and the Agent see the same copy. */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** Conversation records: one per process, shared by the task service, the chat UI and the result card's buttons. */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /**
     * Unanswered questions from a call: the question is written into its conversation, the owner answers in the
     * conversation or from the notification, and the first answer wins.
     * Writing to the conversation goes on a dedicated IO line so questions and answers land in order.
     */
    val ownerQuestions: com.wangzan101.mika.calls.OwnerQuestions by lazy {
        val io = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)
        com.wangzan101.mika.calls.OwnerQuestions(
            write = { id, turn -> scope.launch(io) { runCatching { conversations.append(id, turn) } } },
            askedLine = { question, notified ->
                tr(R.string.calls_owner_ask_line, question) + if (notified) "" else "\n" + tr(R.string.calls_owner_ask_notify_off)
            },
            answeredLine = { tr(R.string.calls_owner_ask_answered) },
            withdrawnLine = { tr(R.string.calls_owner_ask_withdrawn) },
        )
    }

    /** Project definitions: one per process. */
    val projects: ProjectStore by lazy { ProjectStore(this) }

    /** Resolves conversation id to workspace (does not depend on [chat], to avoid a lazy cycle). */
    val workspaceResolver: WorkspaceResolver by lazy { WorkspaceResolver(conversations, projects) }

    /** Drafts from system share that have not been sent yet, and their private attachment copies. */
    val sharedDrafts: SharedDraftStore by lazy { SharedDraftStore(java.io.File(filesDir, "shared-drafts")) }

    /** Terminals opened by the user (per workspace): entered only from the UI, never handed to any task, and no run record is written. */
    val terminals: com.wangzan101.mika.terminal.TerminalHub by lazy {
        com.wangzan101.mika.terminal.TerminalHub(this, conversations, projects)
    }

    /** Deletes a project: stops the current tasks of its conversations, deletes the project's conversations and the project definition, and clears the shared workspace `p-<id>`. */
    val projectDeleter: ProjectDeleter by lazy {
        val app = this
        ProjectDeleter(
            projects, conversations, taskState.activeConversations,
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            deleteProjectWorkspace = { pid -> SandboxDirs(app).deleteWorkspace(WorkspaceKey.Project(pid)) },
            forgetRemoteTasks = { id ->
                if (com.wangzan101.mika.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            onDeleted = { pid, ids ->
                val imports = com.wangzan101.mika.task.DeviceImportStore.get(app)
                imports.removeWorkspace(WorkspaceKey.Project(pid).wire)
                ids.forEach(imports::removeConversation)
            },
        )
    }

    private val deskRegistry = lazy { DeskRegistry(this) }

    /** One resident front desk per conversation: chat page, voice call and other entry points get the same front desk, its tool executor and background from here. */
    val desks: DeskRegistry by deskRegistry

    /**
     * Asked by the task service: when run runKey ends with end, is a front desk following this run and about to speak
     * the ending (see [DeskRegistry.reportsEnding]); if so the task no longer posts its own "task complete". Does not
     * create a front desk just to answer when none has been created yet.
     */
    val deskReportsEnding: (runKey: String, end: TaskState) -> Boolean =
        { runKey, end -> deskRegistry.isInitialized() && desks.reportsEnding(runKey, end) }

    /** Delete a conversation (long-press in the sidebar): stop its current task and delete the workspace (conversations in the same project keep the shared workspace), keeping scheduled jobs. */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            forgetRemoteTasks = { id ->
                if (com.wangzan101.mika.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            deleteWorkspace = { id ->
                // The workspace holds attachments, screens sent by the user and files written by tasks; commands in the sandbox can reach it,
                // so delete with mutual exclusion against commands, imports and resets
                SandboxDirs(app).deleteWorkspace(WorkspaceKey.Conversation(id))
            },
            isSharedWorkspace = { id -> workspaceResolver.isShared(id) },
            onDeleted = { id -> com.wangzan101.mika.task.DeviceImportStore.get(app).removeConversation(id) },
        )
    }

    /** Screens sent by the user (stored in each conversation's workspace). */
    val userScreens: com.wangzan101.mika.chat.UserScreenFiles by lazy { com.wangzan101.mika.chat.UserScreenFiles(this) }

    /** Global ask / autonomous: one per process, shared by the settings page and all tasks. */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.wangzan101.mika.settings.AppAccessStore by lazy {
        com.wangzan101.mika.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** Proactive capabilities: notification allowlist, listener and the local inbox. */
    val proactive by lazy {
        com.wangzan101.mika.proactive.ProactiveRuntime.create(this,
            { com.wangzan101.mika.proactive.AppGate.of(appAccess.current()) },
            appAccess.state.map { com.wangzan101.mika.proactive.AppGate.of(it) }.distinctUntilChanged(),
            com.wangzan101.mika.proactive.TriageHooks(
            target = { providers.proactiveTarget() }, model = { call -> providers.proactiveTargeted(call) },
            tokensToday = { usage.proactiveTokensToday() }, callsToday = { usage.proactiveCallsToday() },
            usageVersion = { usage.version }, busy = taskState.busy,
        )) { phone.shellManager.commandRunner }
    }

    /** Home page recommended tasks. When the file does not exist yet, the UI initializes it once with the built-in examples in the current language. */
    val recommendedTasks: com.wangzan101.mika.settings.RecommendedTaskStore by lazy {
        com.wangzan101.mika.settings.RecommendedTaskStore(java.io.File(filesDir, com.wangzan101.mika.settings.RecommendedTaskStore.FILE))
    }

    val savedChecklists by lazy {
        com.wangzan101.mika.chat.SavedChecklistStore(
            java.io.File(filesDir, com.wangzan101.mika.chat.SavedChecklistStore.FILE),
            com.wangzan101.mika.settings.KeystoreKeyCipher("mika-saved-checklists"),
        )
    }

    /** The global ask / autonomous setting and each task's own copy; the "autonomous mode" in developer options is changed through this. */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** Queue and display state for each Run follow the App process, so the same copy is reused when the Service is recreated. */
    val taskState = TaskCenterState()

    /** The long-lived personal main conversation and its independent tasks, held by the process. */
    val personal by lazy { com.wangzan101.mika.personal.PersonalRuntime(this) }

    /** Whether Claire's own UI is visible right now. */
    val foreground = AppForeground()

    /** In-process UI requests for its own operations (opening one of Claire's pages/files); sent by the task service, handled by MainActivity. */
    val selfUi = com.wangzan101.mika.self.SelfUi()

    /** Update mirrors: the user can add, delete, edit and reorder them; both the update check and the download follow them, with the official GitHub always as a fallback. */
    val updateMirrors: com.wangzan101.mika.about.UpdateMirrorStore by lazy {
        com.wangzan101.mika.about.UpdateMirrorStore(java.io.File(filesDir, com.wangzan101.mika.about.UpdateMirrorStore.FILE))
    }

    /** Update check: the new version found follows the process, so the sidebar, settings home and about page red dots all see the same copy. */
    val updates: com.wangzan101.mika.about.UpdateChecker by lazy {
        com.wangzan101.mika.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME,
            mirrorSettings = updateMirrors::snapshot,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList())
    }

    internal val apkUpdater by lazy {
        com.wangzan101.mika.about.ApkUpdater(this, mirrorSettings = updateMirrors::snapshot)
    }
    internal val automaticUpdates by lazy { com.wangzan101.mika.about.AutomaticUpdates(this, apkUpdater) }

    /** The floating capsule above other apps. */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** Claire's own read-aloud player: one per process, shared by "read aloud" in chat and the test on the settings page. */
    val voicePlayer by lazy { com.wangzan101.mika.voice.VoicePlayer(this) }
    override val voiceSettings by lazy {
        com.wangzan101.mika.settings.VoiceSettings(getSharedPreferences(FeatureFlags.PREFS, MODE_PRIVATE))
    }

    /** Plays a piece of audio in the workspace (a generation result); does nothing if it cannot be read. */
    private suspend fun playWorkspaceAudio(conversationId: String, path: String) {
        val prefix = com.wangzan101.mika.core.workspace.SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix)) return
        val rel = path.removePrefix(prefix)
        if (rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return
        val dirs = com.wangzan101.mika.cap.sandbox.SandboxDirs(this)
        val key = workspaceResolver.resolve(conversationId)
        val bytes = dirs.tree.shared {
            dirs.gate.openRead(dirs.workspace(key), rel).use { g ->
                if (g.size > 20L * 1024 * 1024) null else g.read(0, g.size.toInt())
            }
        } ?: return
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase())
            ?.takeIf { it.startsWith("audio/") } ?: "audio/wav"
        voicePlayer.play(bytes, mime)
    }

    /** Synthesizes a sentence and plays it on the device; skipped when turned off or unconfigured, and failures are left to the chat page to display. */
    internal suspend fun speak(
        text: String,
        synthesize: suspend (String) -> Unit = { words ->
            val voiceModels = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(this)
            if (voiceModels.tts.migrated) {
                com.wangzan101.mika.media.readAloudMigrated(
                    words,
                    voiceSettings,
                    this,
                    voiceModels.tts,
                    play = { model, requests, streaming -> voicePlayer.playRequests(model, requests, streaming) },
                )
            } else {
                com.wangzan101.mika.media.readAloud(
                    words,
                    voiceSettings,
                    model = { choice, caps -> ProviderStore.get(this).ttsModel(choice, caps) },
                    play = { model, requests, streaming -> voicePlayer.playRequests(model, requests, streaming) },
                )
            }
        },
    ) = synthesize(text)

    /** Human demonstration. Created in onCreate; accessibility events before that are not collected. */
    lateinit var teach: TeachRuntime
        private set
    val teachReady: Boolean get() = ::teach.isInitialized

    /** The conversation page's state follows the process: after closing and reopening the UI, the steps of a running task are still there. */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean) =
                    TaskService.enqueueFollowUp(app, conversationId, messageId, text, startIfIdle = startIfIdle)
                override fun cancelFollowUp(conversationId: String, messageId: String): Boolean =
                    app.followUpQueue.cancel(messageId) != null
                override fun drainFollowUps(conversationId: String) = TaskService.drainFollowUps(app, conversationId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // Switch to that conversation's task id at the moment of the press: afterwards, if the conversation is
                // switched or that task is replaced, the action still does not land on another task
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
                override fun togglePipSoundIn(conversationId: String) { taskState.run(conversationId)?.togglePipSound() }
            },
            conversations,
            // While a call is waiting for the owner's reply, the next sentence in this conversation is that reply
            ownerAnswer = { id, text -> ownerQuestions.answerIn(id, text) },
            followUps = followUpQueue.changes,
            serviceBusy = taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // An app was opened on the virtual screen, or the built-in browser is in use: only then does picture-in-picture
            // have anything to show (an empty screen that was merely created does not count)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // Take the task belonging to that conversation, not the one the service exposes (during parallel runs that is
            // another task, the one being displayed)
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runStage = { taskState.run(it)?.takeoverStage },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // Attachments go into the conversation's workspace and do not need the runtime environment ready: offer the
            // attachment button whenever the device supports it
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            generatedAudios = taskState.audios,
            playAudio = { id, path -> playWorkspaceAudio(id, path) },
            generatedPhotos = taskState.photos,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                val key = workspaceResolver.resolve(id)
                val flags = FeatureFlags.get(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(key), dirs.gate, dirs.tree,
                    freeBytes = sandbox::freeBytes, workspaceBytes = { sandbox.workspaceBytes(key) },
                    limits = AttachmentLimits(flags.attachmentMaxBytes(), flags.workspaceMaxBytes()))
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = { id -> followUpQueue.dropConversation(id); conversationDeleter.delete(id) },
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.wangzan101.mika.core.cap.web.KeptPageClose.User) },
            branchWorkspace = AndroidBranchWorkspace(app),
            // Append the "branch" suffix in the current UI language after the title; if the source title is too long,
            // shorten it first and keep the suffix
            branchTitle = { base -> ChatCopy.branchTitle(base, tr(R.string.chat_branch_title_suffix)) },
            sharedDrafts = sharedDrafts,
            sharedDraftUri = { attachment ->
                sharedDrafts.attachmentFile(attachment.fileName)?.let { file ->
                    androidx.core.content.FileProvider.getUriForFile(
                        app, com.wangzan101.mika.chat.AttachFilesProvider.authority(app.packageName), file, attachment.name,
                    ).toString()
                }.orEmpty()
            },
            teaching = { teach.blocksTasks() },
            onTeachingBlocked = {
                android.widget.Toast.makeText(app, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            },
            voiceReady = voiceSettings.state.map { it.enabled && it.choice != null }
                .stateIn(scope, SharingStarted.Eagerly, voiceSettings.state.value.enabled && voiceSettings.state.value.choice != null),
            autoRead = voiceSettings.state.map { it.enabled && it.autoRead }
                .stateIn(scope, SharingStarted.Eagerly, voiceSettings.state.value.let { it.enabled && it.autoRead }),
            voicePlayback = voicePlayer.state,
            speakText = { text -> speak(text) },
            stopVoice = { voicePlayer.stop() },
            voiceFailed = voicePlayer::fail,
            projectStore = projects,
            projectDeletionImpact = projectDeleter::impact,
            removeProject = { pid ->
                projectDeleter.memberConversationIds(pid).forEach(followUpQueue::dropConversation)
                projectDeleter.delete(pid)
            },
            workspaceResolver = workspaceResolver,
            // Chat goes through the front desk: shares this conversation's front desk and ledger with calls; null when no thinking model is configured, and sending will guide the user to settings
            desk = com.wangzan101.mika.chat.ui.DeskChatDeps(
                desk = { id -> desks.desk(id) },
                executor = { id, words -> desks.executor(id, words) },
                brain = { id ->
                    com.wangzan101.mika.frontdesk.deskModel(app)?.let { model ->
                        com.wangzan101.mika.core.frontdesk.LlmBrain(
                            model,
                            com.wangzan101.mika.core.frontdesk.DeskPrompt.user(
                                spoken = false, persona = com.wangzan101.mika.frontdesk.deskPersona(app),
                            ),
                            com.wangzan101.mika.core.frontdesk.DeskTools.specs(com.wangzan101.mika.core.frontdesk.DeskToolSet.User)
                                .map(com.wangzan101.mika.core.frontdesk.LlmBrain::toolSpec),
                            background = desks.background(id),
                            log = { Log.w("MikaDesk", it) },
                        )
                    }
                },
                // New conversations taken by the front desk do not get a title from the task side, so generate one here from the first sentence; light model first, main model as fallback, usage recorded on this conversation
                title = { id, first ->
                    kotlinx.coroutines.withContext(conversations.meter(id)) {
                        com.wangzan101.mika.chat.ConversationTitler.ensure(conversations, id, first) {
                            val session = com.wangzan101.mika.core.model.ModelSession.of(id)
                            listOfNotNull(
                                providers.modelFor(com.wangzan101.mika.core.model.ModelRole.Light, session),
                                providers.modelFor(com.wangzan101.mika.core.model.ModelRole.Main, session),
                            )
                        }
                    }
                },
            ),
        )
    }

    override fun onCreate() {
        super.onCreate()
        // One-time legacy voice migration before any migrated consumer is initialized; a failure retains legacy behavior.
        runCatching { migrateLegacyVoice() }
        // Temporary files left behind when the process exited during copying or binding shared drafts last time; at most one valid draft is kept in the directory.
        runCatching { sharedDrafts.cleanup() }
        scope.launch(Dispatchers.IO) {
            runCatching { updateMirrors.ensureLoaded() }
            runCatching { com.wangzan101.mika.task.AndroidDeviceFiles.cleanupStaging(SandboxDirs(this@MikaApp)) }
                .onFailure { Log.w("MikaImport", "Could not clean device import staging", it) }
        }
        // Outside-the-UI text (notifications, services, floating capsule) follows the app language.
        AppLanguage.applyDefaultLocale(this)
        // Prompts sent to the model are always English, whatever the UI language.
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        teach = TeachRuntime(this, taskState)
        teach.recover()
        registerActivityLifecycleCallbacks(foreground)
        val flags = FeatureFlags.get(this)
        // The user tapped "Quit completely": the process may be pulled back up by the accessibility service. The capsule
        // is still wired up (the switch counts as off after quitting, see CapsuleController),
        // but runtime environment preparation and cleanup of unfinished runs are skipped for now, so no notification
        // pops up right after quitting; they are made up next time the user opens Claire (see [resumeAfterQuit]).
        val quit = flags.quitRequested()
        // Settings › Network proxy: Claire's own connections follow it from the very first request; the SOCKS proxy's
        // username and password are supplied through the process-wide authenticator
        com.wangzan101.mika.settings.NetProxyStore.get(this)
        com.wangzan101.mika.core.net.NetProxy.shared.installSocksAuth()
        // Built-in browser: set it on the WebView only when a proxy is configured (reading settings requires decryption,
        // so do it in the background; WebView is not loaded just for this when no proxy is set)
        scope.launch(Dispatchers.IO) { runCatching { com.wangzan101.mika.cap.web.WebProxy.apply(this@MikaApp) } }
        capsule.start()
        // The real implementation of the built-in assistant, and start watching the assistant (once the Claire process
        // is up, a still-alive assistant hands over the Binder by itself)
        phone.shellManager.start()
        // Fill in the capabilities that are on by default as soon as the connection is available
        phone.recovery.start()
        // Tell the built-in assistant whether to bring Claire back after it is killed (needed only when the capsule is
        // always shown or a task is running; never during a quit)
        com.wangzan101.mika.cap.phone.setup.RestoreWishSync(
            phone.hub, flags.capsuleModeChanges(), taskState.busy, phone.recoverySettings.consent,
            phone.recovery.accessibility, flags.quitChanges(),
        ).start()
        // Changes left behind by the previous abnormal exit (App muted) are restored here
        scope.launch { taskRecovery.recover() }
        // Runs left unfinished when the process was killed last time: mark them interrupted and notify now, without
        // waiting for the task service to come up
        if (!quit) runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MikaTask", "${it.size} runs interrupted by process restart") }
        // Resume listening when the user has bound the WeChat channel and turned it on; during a quit, all run entry
        // points uniformly refuse to bring it back
        wechat.ensureRunning()
        telegram.ensureRunning()
        if (flags.proactive()) proactive.start(paused = quit)
        // Sync the device time zone first; during a quit no alarms are scheduled, and they are re-armed when Claire is opened again
        if (!quit) scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.syncTimeZone(this@MikaApp); TriggerRuntime.rearm(this@MikaApp) } }
        // Auto archive: move conversations with no new messages for too long into the archive (runs only when enabled in settings); during a quit, catch up next time Claire is opened
        if (!quit) autoArchiveNow()
        // The "connect over USB" script run by the computer; also clear the USB connection info left by the previous boot
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("MikaMcp", "cannot write USB script", it) } }
        // Empty shell entries left by the previous export that was killed halfway
        scope.launch { runCatching { ShareExporter(this@MikaApp).cleanPending() } }
        // Empty shell entries left by the previous music library save that was killed halfway
        scope.launch { runCatching { com.wangzan101.mika.cap.audio.AudioExporter(this@MikaApp).cleanPending() } }
        scope.launch { runCatching { com.wangzan101.mika.media.AudioShareExporter(this@MikaApp).cleanPending() } }
        // Empty shell entries left by the previous album save that was killed halfway
        scope.launch { runCatching { com.wangzan101.mika.cap.image.ImageExporter(this@MikaApp).cleanPending() } }
        // Empty shell entries left by the previous image share copy that was killed halfway
        scope.launch { runCatching { com.wangzan101.mika.media.ShareFileExporter(this@MikaApp).cleanPending() } }
        // Web upload copies left behind by the previous process
        scope.launch { runCatching { WebFiles.clearStale(this@MikaApp, SandboxDirs(this@MikaApp).gate) } }
        // The installer package downloaded by the about page once the update has already been installed
        scope.launch(Dispatchers.IO) { runCatching { apkUpdater.dropInstalled() } }
        // If the runtime environment is not ready (first launch, after a Claire upgrade), prepare it in the background: no network access, invisible to the user
        if (!quit) RuntimePreparer.ensure(this)
        // Once the sandbox is ready (already ready at startup, or just installed), reconcile the skill catalog once: scripts brought by an App update work immediately
        scope.launch {
            SandboxState.get(this@MikaApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@MikaApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@MikaApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }

    /**
     * Moves conversations with no new messages for too long into the archive according to settings: runs once at
     * startup, and again when the number of days is changed in settings.
     * Conversations currently running a task and the conversation the user is viewing are left alone; once a new turn
     * is written afterwards, the archiving is cancelled automatically.
     */
    fun autoArchiveNow() {
        val days = FeatureFlags.get(this).autoArchiveDays()
        if (days <= 0) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                AutoArchive.run(
                    conversations, days,
                    running = taskState.activeConversations.value,
                    currentId = (conversations.currentChat() as? CurrentChat.Open)?.id,
                    isRunning = { it in taskState.activeConversations.value },
                )
            }.onFailure { Log.w("MikaChat", "could not auto archive conversations", it) }
        }
    }

    /** One-time legacy voice migration; builds the read-only snapshot without initializing [calls]. */
    private fun migrateLegacyVoice() {
        val environment = com.wangzan101.mika.voice.model.VoiceModelEnvironment.get(this)
        val source = com.wangzan101.mika.voice.model.LegacyVoiceSnapshotSource {
            val profileStore = com.wangzan101.mika.calls.CallVoiceProfileStore.create(this)
            val activeProfileId = com.wangzan101.mika.calls.CallSettings.create(this).state.value.activeVoiceProfileId
            com.wangzan101.mika.voice.model.LegacyVoiceSnapshot(
                speech = speech.legacySnapshot(),
                tts = voiceSettings.legacySnapshot(),
                realtime = profileStore.legacySnapshot(activeProfileId),
            )
        }
        environment.migrateLegacy(source)
    }

    /** The user opened Claire again: makes up the startup work skipped during the quit (see [com.wangzan101.mika.MikaQuit]). */
    fun resumeAfterQuit() {
        runRecovery.recover()
        RuntimePreparer.ensure(this)
        autoArchiveNow()
        mcp.ensureRunning()
        wechat.ensureRunning()
        telegram.ensureRunning()
        if (FeatureFlags.get(this).proactive()) proactive.start(paused = false)
        scope.launch(Dispatchers.IO) {
            runCatching {
                TriggerRuntime.syncTimeZone(this@MikaApp)
                TriggerRuntime.rearm(this@MikaApp)
            }.onFailure { Log.w("MikaTrigger", "could not restore schedules after quit", it) }
        }
    }
}
