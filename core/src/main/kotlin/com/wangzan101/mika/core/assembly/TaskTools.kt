package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.agent.CoreSections
import com.wangzan101.mika.core.agent.DO_STEPS_TOOL
import com.wangzan101.mika.core.cap.phone.AppPacing
import com.wangzan101.mika.core.cap.phone.AppTempo
import com.wangzan101.mika.core.cap.phone.PHONE
import com.wangzan101.mika.core.cap.phone.PhoneCapability
import com.wangzan101.mika.core.cap.root.RootCapability
import com.wangzan101.mika.core.cap.root.RootGroup
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.sandbox.SandboxCapability
import com.wangzan101.mika.core.cap.sandbox.SANDBOX
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_BUDGET_MS
import com.wangzan101.mika.core.cap.sandbox.SandboxGroup
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.FolderFileImporter
import com.wangzan101.mika.core.cap.devicefiles.DEVICE_FILES
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesCapability
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesGroup
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.cap.web.WebCapability
import com.wangzan101.mika.core.cap.web.WebGroup
import com.wangzan101.mika.core.cap.web.WebPort
import com.wangzan101.mika.core.screen.uistate.IdleScroll
import com.wangzan101.mika.core.cap.web.PagePacing
import com.wangzan101.mika.core.screen.uistate.PlacePace
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.tool.PluginCapability
import com.wangzan101.mika.core.tool.PluginScriptTool
import com.wangzan101.mika.core.tool.PluginToolSpec
import com.wangzan101.mika.core.tool.ScriptRunner
import com.wangzan101.mika.core.general.GeneralGroup
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.learn.memory.ProfileStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.learn.skills.PersonalSkills
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.screen.vision.ImageOps
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.run.ChildRunHost
import com.wangzan101.mika.core.screen.user.UserScreenSource
import java.io.File

private val helperFileTools = DeviceFilesGroup.withSearchAndCopy(searchAndCopyTools(DEVICE_FILES))

/**
 * A task's tools: installs the phone, web, and sandbox capabilities plus the core tools. This is the only place that knows several capabilities at once.
 * Capabilities whose port was not supplied are still installed (they offer no tools), and their tools can still be found when looking up declarations by name. Root commands are the exception: without a port, nothing is installed at all.
 */
fun toolDispatcher(
    screen: ScreenPort,
    visionEnabled: Boolean = false,
    /** Read-only knowledge and skill catalog; when null, knowledge_search / knowledge_read / skill_search / skill_read are not offered. */
    skills: GuideCatalog? = null,
    /** Also capture a frame for the run record when reading the screen (so frames exist even for models that cannot see images). */
    recordFrames: Boolean = false,
    /** Vision model; when null (no vision-capable model configured) no vision tools are offered. */
    vision: VisionService? = null,
    /** Built-in browser; when null no web tools are offered, only the phone UI. */
    web: WebPort? = null,
    /** Workspace path check for web uploads (normalization and symlink resolution are done on the app side). */
    workspacePath: ((String) -> WorkspacePathCheck)? = null,
    /** Pacing for viewing web pages one by one (top up the dwell before changing pages, see [PagePacing]); no waiting by default. */
    webPacing: () -> PagePacing = { PagePacing.Off },
    /** Each site's browsing-speed requirement (by host name); defaults to the pace from site knowledge in [skills]. */
    webPlacePace: (String) -> PlacePace = { host -> PlacePace.of(skills?.sitePace(host)) },
    /** Gently scroll the page a few times while dwelling on a web page (see [IdleScroll]); no scrolling by default. */
    webIdleScroll: () -> IdleScroll? = { null },
    /** Pacing for step-by-step operation inside apps (top up the dwell before actions that change the screen, see [AppPacing]); no waiting by default. */
    appPacing: () -> AppPacing = { AppPacing.Off },
    /** The app's actual operation tempo (by package name, see [AppTempo]); defaults to the pace from app knowledge in [skills]. */
    appTempo: (String) -> AppTempo = { pkg -> AppTempo.of(skills?.appPace(pkg)) },
    /** Which apps cap browsing speed (by package name; screen counts, subject to a limit); by default the same as [appTempo], taking apps marked careful in app knowledge. */
    appCapped: (String) -> Boolean = { pkg -> PlacePace.of(skills?.appPace(pkg)) == PlacePace.CAREFUL },
    /** Gently scroll a few times while dwelling on a new screen in an app (see [IdleScroll]); no scrolling by default. */
    appIdleScroll: () -> IdleScroll? = { null },
    /** Stable references across screen reads: screen reads give the model indexes and s<N>, translated back to port ids before actions. */
    stableRefs: Boolean = false,
    /** After a write action succeeds, wait for the UI to settle and read a screen, putting the diff from the previous screen into the result's `screen` field; implies [stableRefs]. */
    inlineScreen: Boolean = false,
    /** Give the model the element table first; attach an image automatically only when element information is clearly insufficient. Actions still return element changes. */
    adaptiveVision: Boolean = false,
    /** Speed-up switch deepLinks: offers the tool for opening links. */
    deepLinks: Boolean = false,
    /** Memory; memory_search / memory_save / memory_edit are offered only when [utterances] is supplied too. */
    memory: MemoryStore? = null,
    profile: ProfileStore? = null,
    /** What the user said in this run; quotes for memory_save / memory_edit are validated against it; AgentLoop appends to it. */
    utterances: UserUtterances? = null,
    /** The conversation this task belongs to; recorded as the source of memories. */
    conversationId: String? = null,
    /** Linux sandbox; sandbox tools are offered when the device supports it (if it is not ready, tool calls wait for it). */
    sandbox: SandboxPort? = null,
    /** This task's workspace (conversation ID). */
    workspace: () -> String = { "default" },
    /** Called when the sandbox starts running a command or script (the task card shows "Sandbox: …"). */
    onSandboxCommand: (String) -> Unit = {},
    /** Progress reported by `mika progress` inside the sandbox. */
    onSandboxProgress: (String) -> Unit = {},
    /** A sandbox tool is waiting for the runtime to become ready (the task card shows "Preparing"). */
    onSandboxPreparing: () -> Unit = {},
    /** Called when a skill script starts running; when absent, reported via [onSandboxCommand]. */
    onSandboxScript: ((String) -> Unit)? = null,
    /** Maximum cumulative runtime for sandbox commands in this task (the working tier under settings); defaults to [SANDBOX_BUDGET_MS]. */
    sandboxBudgetMs: Long = SANDBOX_BUDGET_MS,
    /** Used to encode images sent to the main model uniformly; when null, images are sent as-is. */
    imageOps: ImageOps? = vision?.ops,
    /** Capabilities allowed by the initial run constraints; legacy callers that do not pass it still get today's tools. */
    runSpec: RunSpec = RunSpec(RunId("legacy"), "legacy", "legacy"),
    /** All external MCP endpoints connected for this task (regardless of source; the caller assembles them in one place); each becomes a capability, distinguished by serverId. */
    mcpServers: List<McpConnection> = emptyList(),
    /** Stores a scheduled task and reports whether it can run on time (see [Scheduled]). */
    scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    scheduleAdmin: ScheduleAdmin? = null,
    childHost: ChildRunHost? = null,
    /** Screens the user sent in this conversation; view_user_screen is offered when supplied and this utterance or an earlier turn carries a screen. */
    userScreens: UserScreenSource? = null,
    /** The user's personal skills; skill_save is offered only when this, [skills], and [utterances] are all supplied (write or edit a personal skill when the user asks). */
    personalSkills: PersonalSkills? = null,
    /** Whitelisted entry points for changing Claire's own settings; when null, settings_list and settings_set are not offered (see [SelfConfig]). */
    selfConfig: com.wangzan101.mika.core.self.SelfConfig? = null,
    /** Stored run records; history_read is offered when supplied and this conversation has earlier turns with recorded runs (look up what earlier turns read). */
    runRecords: RunRecords? = null,
    /** Audio generation/saving; when null the audio capability is not installed (no audio model configured or the user has not enabled it). */
    audio: com.wangzan101.mika.core.cap.audio.AudioPort? = null,
    /** Whether the user allows saving audio to the music library; off by default. */
    audioSaveEnabled: Boolean = false,
    /** Whether to ask the user before each save; asks by default. */
    audioAskBeforeSave: Boolean = true,
    /** Whether the user allows the model to speak proactively; off by default. */
    audioSpeakTool: Boolean = false,
    /** Fixed audio transcription port; when null and [transcribeProvider] is not supplied, the transcription capability is not installed. */
    transcribe: com.wangzan101.mika.core.cap.transcribe.TranscribePort? = null,
    /** Resolves the transcription port dynamically according to the current STT selection; both listing and execution re-check it without rebuilding other task state. */
    transcribeProvider: (() -> com.wangzan101.mika.core.cap.transcribe.TranscribePort?)? = null,
    /** Image generation/editing/saving to the album; when null the image capability is not installed (no image model configured or the user has not enabled it). */
    image: com.wangzan101.mika.core.cap.image.ImagePort? = null,
    /** Whether the user allows saving images to the album; off by default. */
    imageSaveEnabled: Boolean = false,
    /** Whether to ask the user before each save; asks by default. */
    imageAskBeforeSave: Boolean = true,
    /** Maximum images to generate per task; beyond that the tool refuses instead of spending the provider's money. */
    imageMaxPerTask: Int = 8,
    /** Run commands as root (supplied only when the user enabled "allow Claire to run root commands" on a rooted phone); when null, root tools are not registered. */
    root: RootPort? = null,
    /** General web search configured and enabled by the user; when null, the original browser search path is kept. */
    webSearch: com.wangzan101.mika.core.search.WebSearch? = null,
    /** Reads Claire's own state; supplied only for UI main tasks on the phone that have a user. */
    self: com.wangzan101.mika.core.self.SelfPort? = null,
    /** Operate Claire's own UI/files/notifications/clipboard; supplied only when the user enables "allow Claire to operate its own UI". */
    selfOperate: com.wangzan101.mika.core.self.SelfPort? = null,
    phoneCalls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    smsLogin: com.wangzan101.mika.core.cap.phone.sms.SmsLoginPort? = null,
    /** Cross-session task summaries; task_memory_search / task_memory_timeline / task_memory_step are offered only when supplied. */
    taskMemory: com.wangzan101.mika.core.learn.memory.TaskMemoryStore? = null,
    /** Raw notifications from the last two days plus stored daily, weekly, and monthly digests (accumulated by proactive capabilities and eligible to be brought into tasks); notices_read is offered only when supplied. */
    notices: com.wangzan101.mika.core.proactive.NoticeSource? = null,
    /** Device files accessible when the user starts a task from the UI. */
    deviceFiles: com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort? = null,
    /** Outlet that delivers task results to the owner's bound WeChat; when null (the task did not explicitly ask to send WeChat), wechat_send_owner is not offered. When nothing is bound the outlet still exists, and sending honestly reports it as unavailable. */
    wechatOwner: com.wangzan101.mika.core.cap.wechat.WechatOwnerPort? = null,
    /** Enabled plugin tools (names already composed as plugin_<id>_<name>); added to the task only when [pluginScriptRunner] is supplied. */
    pluginTools: List<PluginToolSpec> = emptyList(),
    /** Entry point for running plugin scripts inside the app process; when null, plugin tools are not registered. */
    pluginScriptRunner: ScriptRunner? = null,
    /** Where temporary plugin script argument files go; the app uses cacheDir, JVM tests use java.io.tmpdir. */
    pluginArgsDir: File = File(System.getProperty("java.io.tmpdir"), "mika-plugin-args"),
    /** Streams a document from a system-granted folder to this task's workspace. */
    folderFileImporter: FolderFileImporter? = null,
): ToolDispatcher {
    val attendedUi = runSpec.provenance.origin == RunOrigin.Ui && runSpec.provenance.attendance != Attendance.Unattended
    val helper = deviceFiles?.takeIf { attendedUi && (runSpec.capabilities == null || DEVICE_FILES in runSpec.capabilities) }
    val screenshotStore = sandbox?.takeIf {
        it.status() !is com.wangzan101.mika.core.cap.sandbox.SandboxStatus.Unsupported &&
            !runSpec.readOnly && runSpec.provenance.origin != com.wangzan101.mika.core.run.RunOrigin.Child &&
            (runSpec.capabilities == null || "sandbox" in runSpec.capabilities)
    }?.let { com.wangzan101.mika.core.cap.sandbox.WorkspaceScreenshots(it, workspace) }
    val config = ToolConfig(visionEnabled, skills, recordFrames, vision, inlineScreen, adaptiveVision, memory, profile, utterances, conversationId,
        imageOps, scheduleTrigger, scheduleAdmin, runSpec, childHost, userScreens, personalSkills, selfConfig, runRecords, webSearch, self, selfOperate,
        screenshotStore?.let { store -> { path, png -> store.save(path, png) } }, taskMemory = taskMemory, notices = notices)
    val providers: List<CapabilityProvider> = listOf(
        object : CapabilityProvider {
            override val id = PHONE
            override fun open(spec: RunSpec) = PhoneCapability(screen, deepLinks, appPacing(), appTempo, appCapped, appIdleScroll(), smsLogin)
        },
        object : CapabilityProvider {
            override val id = WEB
            override fun open(spec: RunSpec) = WebCapability(web, workspacePath, webPacing(), webPlacePace, webIdleScroll())
        },
        object : CapabilityProvider {
            override val id = SANDBOX
            override fun open(spec: RunSpec) = SandboxCapability(sandbox, workspace, visionEnabled,
                onSandboxCommand, onSandboxProgress, onSandboxPreparing, onSandboxScript,
                folderAccess = attendedUi && !spec.readOnly,
                folderFileImporter = folderFileImporter,
                // When both sources are available, the helper group already registers the shared tools.
                folderTools = if (helper == null) searchAndCopyTools(SANDBOX) else emptyList(),
                budgetMs = sandboxBudgetMs)
        },
        // When the port is null the whole capability is not assembled (the task did not explicitly ask to send WeChat)
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.wechat.WechatOwnerCapability.WECHAT_OWNER
            override fun open(spec: RunSpec) =
                wechatOwner?.let { com.wangzan101.mika.core.cap.wechat.WechatOwnerCapability(it) }
        },
    ) + listOfNotNull(if (transcribe != null || transcribeProvider != null) {
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.transcribe.TRANSCRIBE
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.transcribe.TranscribeCapability(transcribe, transcribeProvider)
        }
    } else null) + listOfNotNull(audio?.let { port ->
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.audio.AUDIO
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.audio.AudioCapability(port, audioSaveEnabled, audioAskBeforeSave, audioSpeakTool)
        }
    }) + listOfNotNull(image?.let { port ->
        object : CapabilityProvider {
            override val id = com.wangzan101.mika.core.cap.image.IMAGE
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.cap.image.ImageCapability(
                port, imageSaveEnabled, imageAskBeforeSave, imageMaxPerTask,
            )
        }
    }) + listOfNotNull(root?.let { port ->
        object : CapabilityProvider {
            override val id = RootCapability.KEY.name
            override fun open(spec: RunSpec) = RootCapability(port)
        }
    }, phoneCalls?.let { port ->
        object : CapabilityProvider {
            override val id = "calls"
            override fun open(spec: RunSpec) = com.wangzan101.mika.core.calls.PhoneCallsCapability(port, spec.threadId)
        }
    }, helper?.let { port ->
        object : CapabilityProvider {
            override val id = DEVICE_FILES
            override fun open(spec: RunSpec) = DeviceFilesCapability(port, workspace, helperFileTools)
        }
    }, pluginScriptRunner?.takeIf { pluginTools.isNotEmpty() }?.let { runner ->
        object : CapabilityProvider {
            override val id = "plugin"
            override fun open(spec: RunSpec) = PluginCapability(pluginTools.map { PluginScriptTool(it, runner, pluginArgsDir) })
        }
    }) + mcpServers.map(::McpClientProvider)
    return RunAssembler(providers).assemble(runSpec, config, stableRefs, FORMER_PREFIX, RENAMED, RETIRED, MOVED)
}

/** Tool-name prefixes per capability before the rename: phone had none, web used browser_. */
private val FORMER_PREFIX = mapOf(PHONE to "", WEB to "browser_")
/** Verb-part changes in the screen tools' second rename (before → now); identical for the phone and web sets. */
private val SCREEN_VERBS_MOVED = mapOf(
    "dump_elements" to "list_elements", "locate_on_screen" to "find_visually",
    "screen_description" to "describe", "check_screen" to "diagnose",
)

/** Names whose non-prefix parts also changed (current name → earliest name; for the screen tools renamed a second time, the earliest name was the one shared by several screens). */
private val RENAMED = mapOf("web_wait_for" to "browser_wait") +
    SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$now" to old } }

/**
 * Names that changed entirely (old name → current name). One split into knowledge and skill lists both: when the model calls the old name, the choice is made by argument (see [LegacyNames.replacements]),
 * and for the old name in text both are written.
 */
private val MOVED: Map<String, List<String>> = mapOf(
    "find_skills" to listOf("knowledge_search", "skill_search"),
    "skill_view" to listOf("knowledge_read", "skill_read"),
    "run_script" to listOf("run_bundled_script"),
    "report_barrier" to listOf("handle_obstacle"),
    "report_failure" to listOf("end_not_done"),
    "request_input" to listOf("ask_user"),
    "user_takeover" to listOf("hand_to_user"),
    "recall_screenshot" to listOf("view_past_screens"),
) + SCREEN_VERBS_MOVED.flatMap { (old, now) -> listOf(PHONE, WEB).map { "${it}_$old" to listOf("${it}_$now") } }

/** Removed with no replacement tool (old name → what to do now): results used to be delivered with a tool; now a reply without calls is the answer. */
private val RETIRED = mapOf(
    "finish" to "reply to the user with your answer as text, without calling any tool",
    "phone_ask_app_ai" to "read the app's own screens yourself (asking an app's built-in AI assistant is no longer available)",
)

/** Tool names before the rename → current names (used when reading old routes, run records, and personal skills). */
fun legacyNames(): LegacyNames =
    LegacyNames(knownTools(), FORMER_PREFIX, RENAMED, retired = RETIRED, moved = MOVED)

/** Tool declarations used for reading run records: old names recorded before the rename are also recognized as the current tool (same action and capability), so old records score the same as new ones. */
fun recordedTools(): ToolIndex = legacyNames().let { legacy -> ToolIndex(allTools()) { legacy.resolve(it) } }

/** All tools Claire may offer, in the order sent to the model. */
internal fun allTools(): List<AgentTool> =
    GeneralGroup.tools + PhoneCapability.tools() + WebGroup.tools + SandboxGroup.tools + com.wangzan101.mika.core.cap.audio.AudioGroup.tools + com.wangzan101.mika.core.cap.transcribe.TranscribeGroup.tools + com.wangzan101.mika.core.cap.image.ImageGroup.tools + RootGroup.tools + com.wangzan101.mika.core.calls.callAgentTools + helperFileTools + com.wangzan101.mika.core.cap.wechat.wechatOwnerAgentTools

/** Declarations of all tools Claire may offer. */
fun knownTools(): ToolIndex = ToolIndex(allTools())

/** All tool names Claire may offer (tool names mentioned in skill documents are validated against these). */
fun knownToolNames(): Set<String> = knownTools().names + DO_STEPS_TOOL
