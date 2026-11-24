package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.safety.gate.ConfirmationRequest
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.MemoryStore
import com.wangzan101.mika.core.learn.memory.ProfileStore
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.screen.vision.ImageOps
import com.wangzan101.mika.core.screen.vision.VisionService
import com.wangzan101.mika.core.screen.visual.Shots
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.learn.knowledge.BundledScripts
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.run.ChildRunHost
import com.wangzan101.mika.core.run.RunSpec

/** This task's configuration (models, skills, memory, switches); each capability's own ports and switches live in the capability. */
internal class ToolConfig(
    val visionEnabled: Boolean,
    val skills: GuideCatalog?,
    val recordFrames: Boolean,
    val vision: VisionService?,
    val inlineScreen: Boolean,
    /** Elements first: readable pages do not send screenshots to the main model, and an image is added automatically when the page cannot be read. */
    val adaptiveVision: Boolean,
    val memory: MemoryStore?,
    val profile: ProfileStore?,
    val utterances: UserUtterances?,
    val conversationId: String?,
    val imageOps: ImageOps?,
    /** Stores a scheduled task and says whether it can be on time (see [Scheduled]). */
    val scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    val scheduleAdmin: ScheduleAdmin? = null,
    val runSpec: RunSpec? = null,
    val childHost: ChildRunHost? = null,
    /** Screens the user sent in this conversation; when absent, view_user_screen is not offered. */
    val userScreens: com.wangzan101.mika.core.screen.user.UserScreenSource? = null,
    /** The user's personal skills; skill_save is offered only when this is given (and there is a skill catalog and something the user said this time). */
    val personalSkills: com.wangzan101.mika.core.learn.skills.PersonalSkills? = null,
    /** Allowlisted entry for changing Claire's own settings; when empty (the user has not allowed it), settings_list and settings_set are not offered. */
    val selfConfig: com.wangzan101.mika.core.self.SelfConfig? = null,
    /** Stored run records; history_read is offered when this is given and this conversation has earlier rounds with recorded runs. */
    val runRecords: com.wangzan101.mika.core.run.record.RunRecords? = null,
    /** Web search configured and enabled by the user; when empty, web_search is not offered. */
    val webSearch: com.wangzan101.mika.core.search.WebSearch? = null,
    /** Reads Claire's own state; given only to a main UI task on the phone that has a user. */
    val self: com.wangzan101.mika.core.self.SelfPort? = null,
    /** Operates Claire's own screens / files / notifications / clipboard; given only when the user turns on "allow Claire to operate its own UI". */
    val selfOperate: com.wangzan101.mika.core.self.SelfPort? = null,
    /** Save a captured PNG in this task's workspace; screen tools do not depend on the sandbox capability. */
    val saveScreenshot: (suspend (String, ByteArray) -> String)? = null,
    /** Cross-session task summaries; task_memory_search / task_memory_timeline / task_memory_step are offered only when this is given. */
    val taskMemory: com.wangzan101.mika.core.learn.memory.TaskMemoryStore? = null,
    /** Raw notifications from the last two days plus the stored daily, weekly and monthly summaries (accumulated by the proactive capability); notices_read is offered only when this is given. */
    val notices: com.wangzan101.mika.core.proactive.NoticeSource? = null,
)

/** In one task, up to this many memory writes refused by the rules do not count toward consecutive failures; beyond that they count as usual (see [ToolContext.noteMemoryRefusal]). */
internal const val MAX_QUIET_MEMORY_REFUSALS = 12

/**
 * What tool execution shares: configuration, verification material, things capabilities put in ([get]), and a little cross-tool state (rejected payment declarations, links allowed to open, ...).
 * Tools themselves are stateless and take everything from here. The screen-read state of screen capabilities is also one of the things put in, used by the screen-state side.
 */
internal class ToolContext(
    val config: ToolConfig,
    val evidence: EvidenceLog,
    /** Declarations of every tool this task may offer (page scripts look tools up by verb). */
    val tools: ToolIndex,
    /** The capability that can run skill scripts; null when there is none. */
    val scripts: BundledScripts?,
    val shots: Shots,
) {
    private val parts = HashMap<CapabilityKey<*>, Any>()

    /** What a capability put in at task start; null when nothing was put in. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: CapabilityKey<T>): T? = parts[key] as T?

    operator fun <T : Any> set(key: CapabilityKey<T>, value: T) { parts[key] = value }

    /** The most recently rejected payment declaration; when taking over the payment it supplies the item and amount the model did not write. */
    var refusedPay: ConfirmationRequest? = null
    /** Whether an app has been opened successfully in this task; when not, the verifier knows there is no screen to compare against. */
    var appOpened = false
    /** This call's id; actions inside page scripts are numbered from it. */
    var callId = ""
    /** Which external MCP connector (the name from configuration) this call reached; non-null only while dispatch is inside an MCP operation, and it goes into the run record. */
    var mcpConnector: String? = null
    /** Called once when an MCP call starts (the UI marks the task as "calling X MCP"); the returned closure is invoked at the end of the call. */
    var onMcpCallStarted: ((String) -> (() -> Unit))? = null
    /** Actions inside tools that do not touch the screen themselves (page scripts) come back to the execution entry through this; for read-only calls, or when it is not given in tests, such actions are never performed. */
    var sub: SubActions? = null
    /** Per-call consent for self tools; AgentLoop wires in the path that waits for the user's answer, and tests substitute a fake. */
    var ask: AskPort? = null
    /** How many self notifications this task has already sent (capped at 3 per task). */
    var selfNotifies = 0
    /** How many user-browser actions this task has already done (open / search / sign-in on the user's behalf, capped at 5 per task). */
    var selfBrowserOps = 0
    /** The round this call belongs to (the loop's "step N"). */
    var currentStep: Int
        get() = shots.step
        set(v) { shots.step = v }

    /** Links given to the model in reference material; open_link without an action may open only these (both uri and package name must match). */
    val allowedLinks = mutableSetOf<Pair<String, String?>>()

    private val saved = mutableListOf<Change>()
    val savedChanges: List<Change> get() = synchronized(saved) { saved.toList() }
    fun noteSaved(c: Change) = synchronized(saved) { saved += c }

    /** Stable ids returned by the most recent memory_search; null means this task has not searched yet, and an empty list means the latest search found nothing. */
    @Volatile private var memoryCandidates: List<String>? = null
    fun noteMemorySearch(ids: List<String>) { memoryCandidates = ids }
    fun memorySearchCandidates(): List<String>? = memoryCandidates

    private val memoryRefusals = mutableSetOf<String>()
    /**
     * Record one memory write refused by the rules; returns whether this one still counts as "just an answer and not a consecutive failure": it is the first refusal of this entry,
     * and the number refused in this task has not exceeded [MAX_QUIET_MEMORY_REFUSALS]. If the same entry is proposed again unchanged (the answer said not to retry), or too many refusals pile up,
     * it counts as a failure as usual and is left to the consecutive-failure guard to stop - on the phone there is no step limit by default, so the step cap cannot be relied on as a backstop.
     */
    fun noteMemoryRefusal(key: String): Boolean =
        synchronized(memoryRefusals) { memoryRefusals.add(key) && memoryRefusals.size <= MAX_QUIET_MEMORY_REFUSALS }

    /** The dispatcher reports the actual effect (effect) for the tool in this call: "no change" is voiced by the effect, so no separate hint is given here. */
    var reportsEffect = false

    /** Time the capability deliberately paused for pacing during this call (see [ToolExecution.waitMs]); capabilities add to it when they pause, and the dispatcher clears it on every call and passes it into the result. */
    var pacedWaitMs = 0L

    /** How many gentle page scrolls happened during pauses in this call (see [com.wangzan101.mika.core.screen.uistate.IdleScroll]); like [pacedWaitMs] it is cleared on every call and goes only into the run record. */
    var idleScrolls = 0
}

/** Truncate a declaration field written by the model so it fits where the UI shows it verbatim. */
internal fun capField(s: String): String = s.trim().let { if (it.length <= CONFIRM_FIELD_MAX) it else it.take(CONFIRM_FIELD_MAX - 1) + "…" }
