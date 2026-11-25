package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.learn.knowledge.Link
import com.wangzan101.mika.core.screen.observation.CORE_SOURCE
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.learn.knowledge.GuideCatalog
import com.wangzan101.mika.core.learn.memory.Change
import com.wangzan101.mika.core.learn.memory.UserUtterances
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.safety.verify.Evidence
import com.wangzan101.mika.core.screen.vision.ImageOps
import com.wangzan101.mika.core.screen.vision.ModelImage
import com.wangzan101.mika.core.screen.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.screen.visual.Shots
import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.Paced
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.capability.PromptSections
import com.wangzan101.mika.core.safety.guard.OffScreenGuard

/**
 * The entry point for tool calls: parse the arguments, find the tool by name in [ToolRegistry] and execute it, then do the wrap-up shared by all tools
 * (attach the screen after an action, clear the points obtained from looking at an image, the action log, verification material). The concrete tools all live in their capabilities; this class does not know them.
 * Built by the wiring layer from this task's capabilities: [tools] is every tool (in the order sent to the model), [sections] holds the system prompt sections per group (in concatenation order).
 *
 * Screen state for screen capabilities (phone, web) is not here: the wiring layer supplies a [SurfaceLayer], and the dispatcher does on-screen work around each call only through it.
 * Before each call it records which screen is being operated on based on the tool's screen ([aim]); when ids from the wrong screen are used, the call is not handed to the port and the right tool set is explained instead.
 */
class ToolDispatcher internal constructor(
    private val capabilities: List<Capability>,
    tools: List<AgentTool>,
    private val sections: List<PromptSections>,
    config: ToolConfig,
    /** The layer shared by screen capabilities; built from this task's tool context (screen state goes into the context, and the capability's tools take it from there). */
    surfaceLayer: (ToolContext) -> SurfaceLayer,
    /** How tool names from before the rename are recognized (see [LegacyNames]). */
    formerPrefix: Map<String, String> = emptyMap(),
    renamed: Map<String, String> = emptyMap(),
    /** Old names removed without a replacement tool → how to do it now (see [LegacyNames]). */
    retired: Map<String, String> = emptyMap(),
    /** Old names renamed wholesale → the current names (see [LegacyNames]). */
    moved: Map<String, List<String>> = emptyMap(),
    /** Capabilities whose availability an external choice can change; only these tools are re-checked on listing and before execution. */
    dynamicCapabilities: Set<String> = emptySet(),
) {
    private val evidenceLog = EvidenceLog()
    private val shots = Shots()
    internal val ctx = ToolContext(
        config, evidenceLog, ToolIndex(tools), capabilities.firstNotNullOfOrNull { it.scripts }, shots,
    )
    /** The layer shared by screen capabilities (phone, web); all on-screen work goes through it. */
    private val surfaces = surfaceLayer(ctx)

    init {
        capabilities.forEach { it.onTaskStart(ctx) }
    }

    /** Groups expanded only on use: one per folded capability, plus one for the general tools shared by screen capabilities ([ToolGroups.SCREENS]; when no screen is folded, they are always present). */
    private val folding: List<String> = capabilities.filter { it.folds }.map { it.id }
    private val screenGroups: Set<String> = capabilities.filter { it.folds && it.family != null }.map { it.id }.toSet()

    private fun groupOf(t: AgentTool): String? = when {
        t.capability in folding -> t.capability
        t.withScreens && screenGroups.isNotEmpty() -> ToolGroups.SCREENS
        else -> null
    }

    private val registry = ToolRegistry(ctx, tools, ::groupOf, dynamicCapabilities)

    /** Capability groups already expanded in this task (in expansion order). */
    private val opened = linkedSetOf<String>()
    /** Capability groups actually used in this task (a tool from the group was called; entry tools that are enough on their own do not count): the next round pre-expands based on this. */
    private val usedGroups = linkedSetOf<String>()

    /** Groups whose tools this task offers but with no entry at all (running commands in a read-only task does not give one): not folded, expanded from the start so the whole group is not invisible. */
    private val entryless: Set<String> by lazy { folding.filter { registry.members(it).isNotEmpty() && !registry.hasEntry(it) }.toSet() }

    /** Expanded capability groups (excluding [ToolGroups.SCREENS]). */
    val expanded: Set<String> get() = entryless + opened

    /** Capability groups actually used in this task. */
    val used: Set<String> get() = usedGroups.toSet()

    /** Expand [group] (only when this task offers its tools); returns true when it was newly expanded. */
    fun expand(group: String): Boolean = group in folding && group !in entryless && registry.members(group).isNotEmpty() && opened.add(group)

    /**
     * Pre-expand at startup: the groups used in the previous round ([previous]) and the ones a capability considers certainly needed for this utterance (the sandbox when there are files in the workspace, see [Capability.preload]).
     * Returns the groups that were expanded.
     */
    fun preload(task: String, previous: Collection<String>): List<String> =
        capabilities.filter { it.folds && (it.id in previous || it.preload(task)) }.map { it.id }.filter(::expand)

    /** The user's utterance with each capability's own wording stripped (see [Capability.userWords]); what they typed this time additionally needs quoted and attachment lines stripped, see [com.wangzan101.mika.core.agent.UserWords.typed]. */
    fun userWords(text: String): String = capabilities.fold(text) { t, c -> c.userWords(t) }

    private fun openSet(): Set<String> = expanded.let { e -> if (e.any { it in screenGroups }) e + ToolGroups.SCREENS else e }

    /**
     * The tools sent to the model right now: the always-present ones and the expanded groups (full text), while folded groups give only the entry (short description), in fixed order (see [ToolGroups]).
     * [screenExtra] holds definitions the loop adds itself that appear together with the screen groups (several steps at once). With [all], every group counts as expanded.
     */
    fun offeredNow(screenExtra: List<ToolSpec> = emptyList(), all: Boolean = false): List<ToolSpec> =
        registry.layered(if (all) folding.toSet() + ToolGroups.SCREENS else openSet(), if (screenGroups.isEmpty()) emptyMap() else mapOf(ToolGroups.SCREENS to screenExtra)).let {
            if (screenGroups.isEmpty()) it + screenExtra else it
        }

    /** Tool names from before the rename → current names; used to read old personal skills and practices, and to suggest the new name when an old one is called. */
    val legacy = LegacyNames(ctx.tools, formerPrefix, renamed, retired, moved)

    /** The vision model; when empty (no vision-capable model in settings) vision tools are not offered. */
    val vision: VisionService? get() = ctx.config.vision
    /** The main model can see images itself. */
    val seesImages: Boolean get() = ctx.config.visionEnabled
    /** After a successful write, wait for the screen to settle, read one screen, and put the diff against the previous screen into the result's screen field. */
    val inlineScreen: Boolean get() = ctx.config.inlineScreen
    /** What the user has said this time; quotes from memory_save / memory_edit are checked against it; AgentLoop adds to it. */
    val utterances: UserUtterances? get() = ctx.config.utterances
    private val imageOps: ImageOps? get() = ctx.config.imageOps

    /** Used by the loop to build the "available channels" line (to see whether a skill has a web approach). */
    internal val skillCatalog: GuideCatalog? get() = ctx.config.skills
    /** The id of the screen capability most recently in use; at task start it is the first one (phone). */
    val surface: String get() = surfaces.current

    /** The version of the current place written into the run state (the web page's mobile or desktop version); null when there is none or it cannot be read. */
    suspend fun placeMode(): String? = surfaces.mode()

    /** Whether this task offers the tool that runs skill scripts (the reference material's footnote is written from this). */
    fun scriptsOffered(): Boolean = ctx.scripts?.offered() == true

    /**
     * The system prompt sections of each capability and of core, deciding what exists from the same tool list [specs] sent to the model; a folded capability group has its own sections only after it is expanded.
     * With [all], every group counts as expanded (the prompt version is computed as if everything were offered).
     */
    fun promptSections(specs: List<ToolSpec>, all: Boolean = false): List<String> {
        val offered = specs.map { it.name }.toSet()
        val screens = capabilities.filter { it.family != null }.count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val open = if (all) null else capabilities.filter { !it.folds || it.id in expanded }.map { it.id }.toSet()
        val openScreens = capabilities.filter { it.family != null && (open == null || it.id in open) }
            .count { c -> offered.any { n -> ctx.tools[n]?.capability == c.id } }
        val env = PromptEnv(offered, inlineScreen, screens, open, openScreens)
        return sections.flatMap { it.sections(env) }
    }

    /** The guard for tools that do not touch the screen (one per task); null when there is none. */
    fun offScreenGuard(): OffScreenGuard? = capabilities.firstNotNullOfOrNull { it.guard() }

    fun definitions(): List<ToolSpec> = registry.definitions()

    /** Whether it changes the screen or page state (and must pass the lock-screen/pause gates): derived from the risk the tool declares. */
    fun writesScreen(name: String): Boolean = registry.declared(name)?.writesScreen == true

    /** A tool that does not touch the screen (sandbox and the like): it does not go through the screen guard and is governed by its own guard instead; images in its result are files. */
    fun offScreen(name: String): Boolean = registry.declared(name)?.offScreen == true

    /** The intent the model wrote for this call (the attached reason parameter); null when the tool has its own field named reason, or when it is missing or blank. */
    fun callReason(name: String, args: JsonObject): String? {
        if (registry.declared(name)?.def?.ownReason == true) return null
        return (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
    }

    /** Look up a tool's declaration by name (verb, whether it can be batched, its on-screen wording, ...). */
    val index: ToolIndex get() = ctx.tools

    /** Names of tools that can go into do_steps, in the order listed in the definition. */
    fun batchable(): List<String> = registry.batchable()

    /** The verb this tool performs; null for an unknown name. */
    fun verb(name: String?): Verb? = ctx.tools.verb(name)

    /** Which screen this tool acts on (the capability id of a screen tool); null when it is not a screen tool. */
    fun surfaceOf(name: String): String? = surfaces.surfaceOf(registry.declared(name))

    /** A tool that does not touch the screen itself, whose actions come back one by one through the execution entry (a page script). */
    fun composite(name: String): Boolean = registry.declared(name)?.composite == true

    /** What the irreversible-action check looks at as this tool's target; only tools declared as External have it. */
    fun gateTarget(name: String): GateTarget? = registry.declared(name)?.takeIf { it.risk == Risk.External }?.gateTarget

    /** After each trim or compaction of history the loop tells the dispatcher which reads the model can still see (by id); when the base of a diff is not among them, the full version is sent. */
    fun setVisibleObservations(readIds: Set<String>) = surfaces.setVisible(readIds)

    /**
     * A call starts: record which screen it operates on (its own screen for a screen tool, the argument for a general tool that takes a surface parameter; otherwise keep the current one),
     * until the matching [release]. The execution entry enters once before the checks and again at dispatch time.
     */
    fun aim(name: String, args: JsonObject) {
        surfaces.enter(registry.declared(name)?.aimAt(args))
    }

    /** That call ends; only on success does its screen count as the most recently operated one (a failed call does not change the default). */
    fun release(succeeded: Boolean) = surfaces.leave(succeeded)

    /**
     * A tool for this screen driven with ids from another screen: not handed to the port; the result is wrong_surface, saying which screen the ids belong to and which tool to use.
     * When it cannot tell which screen the ids belong to (expired for too long, misspelled) it returns null and the call goes to the tool as usual (handled as expired).
     */
    fun misaimed(name: String, args: JsonObject): ToolExecution? = registry.declared(name)?.let { surfaces.misaimed(it, args, ctx.tools) }

    /** Read-only calls (screen reads, reporting failures and the like): anything the tool declares as changing the screen, the page or the sandbox is rejected, since those may only go through the execution entry. */
    suspend fun executeRead(call: Part.ToolCall, step: Int? = null): ToolExecution {
        val tool = registry.declared(call.name)
        if (tool != null && (!tool.readOnly || !tool.readOnly(parseArgs(call.argsJson)))) {
            return fail("not_a_read", Feedback.format("not_a_read", "tool" to call.name), recoverable = false)
        }
        return dispatch(call, step, null)
    }

    /**
     * [pass] can only be supplied by the execution entry (guards, gates, irreversible-action checks and records all live there).
     * [step]: the loop's round number, matching "step N" in folded summaries; when not given, the number of calls executed so far is used.
     * [sub]: actions in web_script come back to the loop through it (guards, gates and records as usual); when not given, actions inside scripts are never performed.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun execute(call: Part.ToolCall, pass: ActionPass, step: Int? = null, sub: SubActions? = null): ToolExecution = dispatch(call, step, sub)

    private var executed = 0

    /** The host may provide run-level manual control; core does not depend on the UI or on device input to implement it. */
    var manualControl: com.wangzan101.mika.core.run.ManualControl? = null
    private val manualObserver = com.wangzan101.mika.core.run.ManualControl.Observer()

    private suspend fun dispatch(call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        return try { withScreenControl(call.name, successful = { it: ToolExecution -> it.content["ok"] != JsonPrimitive(false) }) {
            dispatchAutomatic(call, step, sub)
        } } catch (e: com.wangzan101.mika.core.run.ManualControl.Interrupted) {
            fail(com.wangzan101.mika.core.run.ManualControl.CHANGED, e.message.orEmpty(), recoverable = true)
        }
    }

    /** Locks a single screen operation only; sub-steps of a composite script each pass through this entry, and non-screen tools do not take the touch right. */
    internal suspend fun <T> withScreenControl(name: String, successful: (T) -> Boolean, block: suspend () -> T): T {
        val control = manualControl ?: return block()
        if (offScreen(name) || composite(name)) return block()
        val verb = verb(name)
        val observation = verb in setOf(Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.ReadPage)
        // Background page text cannot prove that the current screen was seen; each dispatcher records its own observation generation separately.
        return control.automatic(observation, refresh = observation && verb != Verb.ReadPage,
            refreshed = successful, observer = manualObserver, block = block)
    }

    private suspend fun dispatchAutomatic(call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        surfaces.skip()
        val tool = registry[call.name] ?: return unknown(call.name, parseArgs(call.argsJson))
        // A tool from a folded group (an entry, or one the model called from memory without being shown it) executes as usual; its group is expanded by this, a line in the result says so, and the whole group is visible from the next call on
        val group = groupOf(tool)
        if (group != null && group != ToolGroups.SCREENS && tool.entry != Entry.Alone) usedGroups += group
        val opening = when {
            group == null || tool.entry == Entry.Alone -> null
            group == ToolGroups.SCREENS -> surfaces.current.takeIf { it in screenGroups }
            else -> group
        }?.takeIf(::expand)
        val result = dispatchTool(tool, call, step, sub)
        val nextGroups = result.followUpTools.mapNotNull { verb ->
            ctx.tools.nameOf(verb)?.let { registry[it] }?.let(::groupOf)
        }.distinct().filter(::expand)
        val openings = listOfNotNull(opening) + nextGroups
        return if (openings.isEmpty()) result else result.with(content = JsonObject(result.content + (MORE_TOOLS to JsonPrimitive(openings.joinToString("\n") { opened ->
            Feedback.format("group_opened", "group" to opened,
                "examples" to registry.members(opened).filter { registry[it]?.entry == null }.take(4).joinToString())
        }))))
    }

    private suspend fun dispatchTool(tool: AgentTool, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        val args = try {
            val el = if (call.argsJson.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(call.argsJson)
            Args(tool.def(ctx), el as? JsonObject ?: throw ArgError("arguments", "must be a JSON object"))
        } catch (e: ArgError) {
            return invalid(e)
        } catch (e: kotlinx.serialization.SerializationException) {
            return invalid(ArgError("arguments", "not valid JSON"))
        }
        shots.idAsSnapshot(args.o)?.let { return it }
        misaimed(call.name, args.o)?.let { return it }
        aim(call.name, args.o)
        var ok = false
        try {
            return run(tool, args, call, step, sub).also { ok = it.content["ok"] == JsonPrimitive(true) }
        } finally {
            release(ok)
        }
    }

    private suspend fun run(tool: AgentTool, args: Args, call: Part.ToolCall, step: Int?, sub: SubActions?): ToolExecution {
        executed++
        ctx.currentStep = step ?: executed
        evidenceLog.step = ctx.currentStep
        ctx.callId = call.id
        ctx.sub = sub
        // Actions inside page scripts go through here too: each records its own pauses, and the outer one still includes the inner ones
        val outerWait = ctx.pacedWaitMs
        val outerIdle = ctx.idleScrolls
        ctx.pacedWaitMs = 0
        ctx.idleScrolls = 0
        var mcpConnector: String? = null
        // The on-screen preparation (targets a route needs, a look before an action that changes the screen) and the wrap-up both happen on the screen side
        val result = surfaces.around(tool, args.o, inlineScreen) { reportsEffect ->
            // Nested call (an action inside a page script): the connector the inner call reached belongs to that inner call, and the outer one restores its own
            val outerMcpConnector = ctx.mcpConnector
            ctx.mcpConnector = null
            ctx.reportsEffect = reportsEffect
            try {
                if (!tool.yieldsToSteer) tool.execute(args, ctx)
                else waitOrSteer { tool.execute(args, ctx) } ?: failCode(SKIPPED_FOR_NEW_MESSAGE)
            } catch (e: com.wangzan101.mika.core.device.access.AppAccessDenied) {
                throw e
            } catch (e: ArgError) {
                invalid(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("operation_failed", Feedback.format("operation_failed", "tool" to tool.name, "error" to (e.message ?: e::class.simpleName)))
            } finally {
                ctx.reportsEffect = false
                mcpConnector = ctx.mcpConnector
                ctx.mcpConnector = outerMcpConnector
            }
        }
        val waited = ctx.pacedWaitMs
        ctx.pacedWaitMs = outerWait + waited
        val idled = ctx.idleScrolls
        ctx.idleScrolls = outerIdle + idled
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (tool.logged) evidenceLog.logAction(stepLabel(), tool.name, tool.logTarget(args.o, result, ctx), tool.logTyped(args.o, result), result, tool.wideTarget)
        // What was read (given by the capability in the result, plus what the tool adds from the result) is recorded into the ledger, and the result is replaced with those ledger entries
        val seen = remember(result.observations + tool.observations(args.o, result, evidenceLog), tool.capability)
        // Text typed into an input field (the port explicitly says it is not a password field): later echoes in an input field read from the screen cannot back an answer as evidence
        if (tool.verb == Verb.TypeText && ok && result.content["secret"] == JsonPrimitive(false)) args.o.argStr("text")?.let(evidenceLog::noteTyped)
        // Gentle page scrolls during pauses: recorded only in the run record, not given to the model; the same goes for the MCP connector that was reached
        val record = if (idled == 0 && mcpConnector == null) result.record else JsonObject(buildMap {
            putAll(result.record)
            if (idled > 0) put("idleScrolls", JsonPrimitive(idled))
            mcpConnector?.let { put("mcpConnector", JsonPrimitive(it)) }
        })
        return result.with(observations = seen, place = placeAfter(tool.verb, result.content), waitMs = waited, record = record)
    }

    /** Record into the ledger; entries without a source are attributed to [capability] (core tools are core). Returns the entries recorded in the ledger, each also passed to [observer]. */
    private fun remember(observations: List<Observation>, capability: String?): List<Observation> =
        observations.mapNotNull { o -> evidenceLog.observe(if (o.source != null) o else o.copy(source = capability ?: CORE_SOURCE))?.also { observer?.invoke(it) } }

    /** Every observation recorded into the evidence ledger (the run record stores them verbatim, so evaluation can rebuild the ledger); set by the loop. */
    var observer: ((Observation) -> Unit)? = null

    /** Notification of an MCP call starting/ending (the loop marks the task status as calling which connector). */
    fun observeMcpCalls(onStarted: (String) -> (() -> Unit)) {
        ctx.onMcpCallStarted = onStarted
    }

    /** Unknown tool; when it is a name from before the rename, say what it is called now (without swapping in the new name and executing it for the model). */
    private fun unknown(name: String, args: JsonObject): ToolExecution {
        val connector = registry.connectorForUnknown(name)
        return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "unknown_tool"); put("tool", name)
            val now = legacy.replacements(name, args).filter { registry[it] != null }
            val instead = legacy.retiredAs(name)
            put("message", if (instead != null) Feedback.format("unknown_tool_retired", "name" to name, "instead" to instead)
                else if (now.isNotEmpty()) Feedback.format("unknown_tool_renamed", "name" to name, "tools" to now.joinToString(" or "))
                else Feedback.format("unknown_tool", "name" to name, "tools" to (registry.listedNames().joinToString())))
            put("recoverable", true)
        }, record = connector?.let { buildJsonObject { put("mcpConnector", it) } } ?: JsonObject(emptyMap()))
    }

    /** The current position of the evidence ledger (see [EvidenceLedger.lastId]); taken before an action, then [readSince] shows whether anything was read again after the action. */
    fun ledgerMark(): Int = evidenceLog.ledger.lastId

    fun readSince(mark: Int): Boolean = evidenceLog.ledger.readSince(mark)

    /** Find a tool registered in this task by name; null when unknown. */
    internal fun declared(name: String): AgentTool? = registry.declared(name)

    /** The risk the tool declares; null for an unknown name. */
    fun declaredRisk(name: String): Risk? = registry.declared(name)?.risk
    /** The approval risk the tool declares for these arguments (only off-screen tools have one); null when not declared. */
    fun approvalRisk(name: String, args: JsonObject = JsonObject(emptyMap())): com.wangzan101.mika.core.action.Risk? =
        registry.declared(name)?.approvalRisk(args)

    /** The user's answer obtained by ask_user; the verifier compares it against the facts the user supplied. */
    /** A screen sent by the user (the text on it) is evidence too: on-screen content mentioned in an answer comes from here. */
    fun noteUserScreen(screen: com.wangzan101.mika.core.screen.user.UserScreen) {
        if (screen.blocked || screen.lines.isEmpty()) return
        evidenceLog.observe(Observation(ObsKind.Read, screen.lines.joinToString("\n") { it.text }, source = CORE_SOURCE,
            place = screen.packageName?.let(com.wangzan101.mika.core.screen.observation.Place::app),
            trust = com.wangzan101.mika.core.screen.observation.Trust.Untrusted))
    }

    fun noteUserAnswer(prompt: String, answer: String) {
        evidenceLog.observe(Observation(ObsKind.UserSaid, "Asked the user \"${prompt.take(200)}\"; the user answered: $answer", source = CORE_SOURCE))
        capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /**
     * Evidence for the verifier: a fresh look through the most recently used capability ([Capability.freshLook], without affecting what the model holds), plus this task's evidence ledger and action log.
     * With [withImage] a frame is included too; screens protected against capture are not captured. With [fresh] false no fresh look is taken (when only a literal check against the ledger is needed).
     */
    suspend fun evidence(withImage: Boolean = false, fresh: Boolean = true): Evidence {
        val e = evidenceLog
        // Even without a view of the current state, hand over what was read before and let the verifier judge
        val look = if (fresh) capabilities.firstOrNull { it.id == surface }?.freshLook(ctx, withImage) else null
        return Evidence(look?.observations.orEmpty(), look?.image, e.ledger.copy(), e.actionLog.toList(), look?.protected == true, ctx.appOpened,
            e.takeovers.toList(), userFacts = e.userFacts.toList())
    }

    /** When on, capture the target element and anchor before a write action (set by the loop when the replay switch is on). */
    var captureTargets: Boolean
        get() = surfaces.captureTargets
        set(v) { surfaces.captureTargets = v }

    /** For the run record: the port id that the model's id maps to right now (given by the screen side; empty when there is none). */
    fun portRecord(tool: String, args: JsonObject): JsonObject = surfaces.portRecord(registry.declared(tool), args)

    /** Wait for the place currently in use to settle, at most [timeoutMs]; returns normally even when the state cannot be read. */
    suspend fun settle(timeoutMs: Long) = surfaces.settle(timeoutMs)

    /** The text visible right now at the place in use (one entry per element, registering no ids and not changing what the model holds); empty when it cannot be read. */
    suspend fun visibleText(): List<String> = surfaces.visibleText()

    /** At the place currently in use, wait until [until] holds (or something unexpected is seen, the deadline is reached, or [interrupted]); see [SurfaceLayer.waitUntil]. */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome = surfaces.waitUntil(until, clock, interrupted)

    /** An outward action (send, submit) is about to happen on this screen: hand it to this screen's capability ([Capability.onOutwardStart]); it is always followed by [outwardEnd] when done. */
    fun outwardStart(site: String) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardStart(ctx, site)
    }

    /** The outward action is finished ([done]: it succeeded). */
    fun outwardEnd(site: String, done: Boolean) {
        capabilities.firstOrNull { it.id == surface }?.onOutwardEnd(ctx, site, done)
    }

    /** An action that changes the screen is about to happen on this screen: pause first according to this screen's capability pacing ([Capability.beforeWrite]). */
    internal suspend fun beforeWrite(tool: String, outward: Boolean): Paced {
        suspend fun prepare() = capabilities.firstOrNull { it.id == surface }?.beforeWrite(ctx, verb(tool), outward) ?: Paced.NONE
        return manualControl?.automatic(observer = manualObserver) { prepare() } ?: prepare()
    }

    /** That action is done: hand it to this screen's capability ([Capability.afterWrite]). */
    internal fun afterWrite(tool: String, result: JsonObject) {
        capabilities.firstOrNull { it.id == surface }?.afterWrite(ctx, verb(tool), result)
    }

    /** The takeover's outcome is known only after it is handed back, so the loop adds it to the action log (this is how the verifier knows whether the takeover really happened). */
    fun noteTakeover(reason: String, back: HandBack) {
        val effect = when (back.outcome) {
            HandBackOutcome.complete -> if (back.userFinished) "the user said they finished it themselves (unverified)" else "the user took it back (unverified)"
            HandBackOutcome.skipped -> "takeover skipped"
            HandBackOutcome.timeout -> "takeover timed out with nobody to handle it"
            HandBackOutcome.aborted -> "the task was cancelled"
        }
        evidenceLog.noteTakeover("${stepLabel()} ${ctx.tools.nameOf(Verb.HandToUser)}($reason) $effect")
        if (back.outcome == HandBackOutcome.complete) capabilities.forEach { it.onUserAnswered(ctx) }
    }

    /** "Step N" in the action log matches the loop's round (several steps in a batch belong to the same round); replay has no rounds. */
    private fun stepLabel() = if (ctx.currentStep > 0) "Step ${ctx.currentStep}" else "Replay"

    /** An image sent to the main model, encoded uniformly via [ModelImage]; [screenshotId] is the recall id of the screen image. */
    fun modelImage(img: ByteArray, screenshotId: Int?): Part.Image = ModelImage.part(img, imageOps, screenshotId)

    /**
     * When the main model can see images, the current frame of the place in use and its id (recallable); not given for protected pages or payment pages, and null when nothing is open yet or it cannot be obtained.
     * Used when handing back a replay.
     */
    suspend fun imageNow(): Pair<ByteArray, Int>? = surfaces.imageNow()

    /** How it looks right now: the content given to the model ([content], with ids registered as for a read) together with the image from the same moment ([image], null when there is none). */
    class Glance(val content: JsonObject, val image: ByteArray?, val imageId: Int, val observations: List<Observation> = emptyList())

    /** A look after the action when the main model can see images: content and image are from the same moment; null when nothing is open. What was read is recorded into the ledger. */
    suspend fun lookNow(): Glance? = surfaces.viewNow()?.let { v -> Glance(v.content, v.image, v.imageId, remember(v.observations, surface)) }

    /** Whether the link-opening tool would let these arguments through right now (only link rules and reference material are considered; nothing is executed). */
    fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean =
        LinkPolicy.check(action, uri, pkg) == null && (action != null || (uri to pkg) in ctx.allowedLinks)

    /** The loop registers these links when it hands reference material containing links to the model. */
    fun allowLinks(links: List<Link>) {
        links.forEach { ctx.allowedLinks += it.uri to it.packageName }
    }

    /** What memory_save / memory_edit wrote in this task (the result card shows the change and allows undoing it). */
    val savedChanges: List<Change> get() = ctx.savedChanges

    private companion object {
        /** The field in the result that explains when a group was expanded. */
        const val MORE_TOOLS = "moreTools"
    }

    /** For tests: whether any image is kept in the recall records. */
    internal fun keepsRecallImages(): Boolean = shots.recalls.any { it.png != null }
}
