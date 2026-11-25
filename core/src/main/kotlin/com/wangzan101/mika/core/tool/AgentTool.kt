package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.action.Risk as ApprovalRisk
import kotlinx.serialization.json.JsonObject

/**
 * What a tool declares it does to the outside world.
 * Read: read-only (reading the screen, reading a web page, looking up memory, reporting a failure and the like); read-only calls can be done directly;
 * Write: changes the screen, a page or the sandbox; only through the execution entry point;
 * External: besides changing the UI it may send based on the target element, pay or submit (the irreversible decision looks at its target).
 */
enum class Risk { Read, Write, External }

/** Entry tool: [Opens] expands its group after it is called; [Alone] is enough on its own (listing apps, reading the body of one URL), calling it expands nothing. */
enum class Entry { Opens, Alone }

/** Whether it can run at the same time as other calls; one at a time by default. */
enum class Concurrency { Serial, ParallelSafe }

/**
 * What action a tool performs. Everything that decides by action (guards, trimming, routes, batching, model division of labour, …) looks at it, not at the tool name;
 * on-screen actions and screen reading each have a tool with the same verb in each of several interfaces.
 */
enum class Verb {
    Observe, ListElements, Query, Collect, PhoneRun, DoEach, PhoneFind, PhoneExtract, PhoneDialog, PhoneEvent, PhoneGesture, PhoneCommand, Screenshot, ViewPastScreens, ViewUserScreen, Describe, FindVisually, Diagnose,
    Tap, TypeText, ClearText, Scroll, Swipe, Back, PressKey,
    ListApps, LaunchApp, OpenLink, ShareText, ShareAudio, ShareImage, AppAfterTask, SmsLogin,
    SetAlarm, SetTimer, ShowAlarms, AddCalendarEvent, BulkAddCalendarEvents, SetWheel,
    Navigate, ReadPage, EvaluateJs, SiteCommand, Tabs, Downloads, Upload, SiteMode, Script, Interact, Capture, WaitFor, KeepOpen,
    RunCommand, RunBundledScript, PluginScript, ReadFile, WriteFile, SearchFiles, ShareFile, ViewImage, ListFolderFiles,
    ListDeviceFiles, CopyDeviceFile, SearchDeviceFiles, ListDeviceDirectoryHints, UpdateDeviceDirectoryDescription,
    AudioGenerate, AudioSave, AudioTranscribe, Speak,
    ImageGenerate, ImageEdit, ImageSave,
    RootCommand,
    CallState, CallDial, CallAnswer, CallHangup, CallTakeover,
    Wait, AskUser, RequestConfirmation, HandToUser, HandleObstacle, EndNotDone,
    KnowledgeSearch, KnowledgeRead, SkillSearch, SkillRead, SkillSave, MemorySearch, MemorySave, MemoryEdit, ProfileEdit, HistoryRead, NoticesRead, WebSearch, WechatSendOwner,
    TaskMemorySearch, TaskMemoryTimeline, TaskMemoryStep,
    ScheduleTask,
    SpawnAgent, SettingsList, SettingsSet,
    SelfStatus, SelfOpenScreen, SelfOpenFile, SelfNotify, SelfCopy,
    SelfOpenBrowser, SelfSearch, SelfSites, SelfLogin, SelfConfirmLogin, SelfFavorites, SelfUserPage,
}

/** A tool declaration that can be looked up by name. */
interface ToolInfo {
    val name: String
    val verb: Verb
    /** Id of the capability it belongs to; null for the core's own tools. */
    val capability: String?
    /** Can be put into one multi-step call (do_steps). */
    val batchable: Boolean
    /** Experience routes record it and replay it. */
    val routable: Boolean
    /** The wording in the UI; the tool name is shown when it is absent. */
    val label: String?
    /** `mika phone <operation>` in the sandbox can call it (phone capability, the result is text); everything else is rejected. */
    val sandboxCallable: Boolean get() = false
}

/**
 * Look up a tool declaration by name; null for unknown names. [former] maps a pre-rename name to the current one (used when reading old run records, see LegacyNames);
 * [names] contains only the currently registered names.
 */
class ToolIndex(tools: Collection<ToolInfo>, private val former: (String) -> String? = { null }) {
    private val byName: Map<String, ToolInfo> = tools.associateBy { it.name }

    val names: Set<String> get() = byName.keys

    operator fun get(name: String?): ToolInfo? = name?.let { byName[it] ?: former(it)?.let(byName::get) }

    fun verb(name: String?): Verb? = get(name)?.verb

    /** The name of the tool that performs this action: when [capability] is given only tools of that capability are searched (the set whose core tools have null), otherwise the first registered one is taken. */
    fun nameOf(verb: Verb, capability: String? = null): String? =
        byName.values.firstOrNull { it.verb == verb && (capability == null || it.capability == capability) }?.name

    /** What the tool performing the same action is called in the [to] set of screen capabilities, given its name in the [from] set; null when there is none. */
    fun counterpart(name: String, to: String): String? = get(name)?.let { nameOf(it.verb, to) }
}

/**
 * A tool: definition (name, description, parameters) + declared risk + execution. A new tool = add an implementation and register it in its capability; the dispatcher does not know concrete tools.
 * All state is in [ToolContext]; the tool itself is stateless.
 */
internal interface AgentTool : ToolInfo {
    val def: ToolDef
    override val capability: String? get() = null
    override val batchable: Boolean get() = false
    override val routable: Boolean get() = false
    override val label: String? get() = null
    /** Does not touch the screen (sandbox and the like): skips the lock-screen gate and the screen guard, and images in the result are files rather than screen frames. */
    val offScreen: Boolean get() = false
    /** Include this tool in the "unknown tool" hint even though it is off-screen (MCP tools are shown to the model but the generic list skips most off-screen tools). */
    val listWhenUnknown: Boolean get() = !offScreen
    /** The connector this tool belongs to, when it is an external MCP tool; used to attribute calls that failed before dispatch. */
    val connectorName: String? get() = null
    val risk: Risk
    /** A read tool may have an optional write mode; that invocation must use the execution entry point. */
    fun readOnly(args: JsonObject): Boolean = readOnly
    /** Non-UI capabilities can declare an approval risk directly; UI tools are still judged by target and current observation. */
    val approvalRisk: ApprovalRisk? get() = null
    /** The approval risk declared for this invocation's parameters (some invocations of the same tool ask, others do not, such as root commands); same as [approvalRisk] by default. */
    fun approvalRisk(args: JsonObject): ApprovalRisk? = approvalRisk
    val concurrency: Concurrency get() = Concurrency.Serial
    /** What the irreversible decision looks at as this invocation's target; External tools must give it, the rest are null (not judged). */
    val gateTarget: GateTarget? get() = null
    /** Does not touch the UI itself; the actions inside come back one at a time through the execution entry point (page scripts): skips the lock-screen gate and cannot be a read-only call. */
    val composite: Boolean get() = false
    /**
     * An entry that is also offered while its capability group is collapsed (see [ToolGroups]): while collapsed it uses a short description (prompts/entries/<tool name>.md), and the full text after expansion.
     * Null when it is not an entry.
     */
    val entry: Entry? get() = null
    /** A general tool that belongs to no capability and is offered only while one of the screen-bearing groups is expanded (taking over, reporting an obstacle, reviewing frames). */
    val withScreens: Boolean get() = false
    /**
     * A call that loses nothing when withdrawn (only waiting, or only asking the vision model without touching the UI): it is withdrawn as soon as the user interjects,
     * and the result reports [com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE]; the model reads that sentence first and calls again if it still wants it.
     */
    val yieldsToSteer: Boolean get() = false

    override val name: String get() = def.name
    /** The definition sent to the model; tools that add parameters depending on how many screens this task has (surface) override it. */
    fun def(ctx: ToolContext): ToolDef = def
    /** Which file the description comes from (prompts/tools/<file>.md); the name by default, written out separately when several tools share one template. */
    val promptFile: String get() = name
    val description: String get() = def.description
    /** The JSON schema of the parameters (the one sent to the model). */
    val params: String get() = def.spec().parametersJsonSchema

    /**
     * Which screen this invocation acts on (the id of a screen capability): screen tools use their own; general tools whose screen is decided by parameters look at the parameters; the rest are null (unchanged).
     * The dispatcher uses it to record which screen is being operated before the call.
     */
    fun aimAt(o: JsonObject): String? = capability

    /** Whether this task can call it (looks at config: whether there is a vision model, skill directory, web port, …). */
    fun available(ctx: ToolContext): Boolean = true

    /** Whether it goes into the tool list sent to the model right now; same as [available] by default. */
    fun offered(ctx: ToolContext): Boolean = available(ctx)

    suspend fun execute(a: Args, ctx: ToolContext): ToolExecution

    /** On success the result carries the screen after the action (when the action leaves its screen on), and a vision-capable main model gets one frame attached separately. */
    val showsScreenAfter: Boolean get() = false

    /**
     * An action that changes the UI (tap, type, clear, key press, back, swipe, …): the dispatcher looks at the screen (element table + frame) before and after the action,
     * and writes the actual effect into the result's effect field (see ActionEffect on the UI state side). It only reads the screen and does no extra action.
     */
    val reportsEffect: Boolean get() = false

    /** Goes into the action log (actions in the phone, web and sandbox that change something or go somewhere); with [wideTarget] the target is kept longer. */
    val logged: Boolean get() = false
    val wideTarget: Boolean get() = false
    /** The target written into the action log; when an element's name must be written out from its number, take this screen's most recent screen read from [ctx]. */
    fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? = null
    /** The typed text written into the action log (only when it is certain not to be a password field). */
    fun logTyped(o: JsonObject, r: ToolExecution): String? = null

    /**
     * Observations the model may cite in its final answer and that execution did not provide separately (which page was opened, the output of a command, …); the dispatcher records them together with
     * [ToolExecution.observations] in the evidence material. [log] holds the small cross-tool state (app names that were listed, files that were written).
     */
    fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> = emptyList()
}

/** A tool that changes the screen or page state: must pass the lock-screen/pause gate. Derived from the declared risk; tools that do not touch the screen and page scripts are not among them. */
internal val AgentTool.writesScreen: Boolean get() = risk != Risk.Read && !offScreen && !composite

/** Whether a read-only call can be made: read-only, touches the screen (tools that do not touch the screen have another guard), and is not a page script. */
internal val AgentTool.readOnly: Boolean get() = risk == Risk.Read && !offScreen && !composite
