package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.Selector
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.SurfaceLayer
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.tool.UntilOutcome
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.writesScreen
import com.wangzan101.mika.core.screen.visual.Shots
import com.wangzan101.mika.core.screen.vision.LocateResult
import com.wangzan101.mika.core.screen.vision.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where one action lands, provided by the UI side; the execution entry's irreversibility decision and approval only
 * read it: [args] are the arguments used for judging (for a tap on a visually located @vN, replaced with its
 * registered coordinates and what was seen), [aimed] is the model's look (the screen read found by snapshotId, or the
 * screen coordinate conversion would use when no ID is given), [bySight] means the tapped position came from visual
 * location, [seen] is the text the vision model re-checked and confirmed, [readAgeMs] is how long ago that read was
 * (null when unknown). [now] takes a fresh look right now (read only when irreversibility must be judged; no ID is
 * registered).
 */
class ActionTarget internal constructor(
    val args: JsonObject,
    val aimed: ScreenSnapshot?,
    val bySight: Boolean,
    val seen: String?,
    val readAgeMs: Long?,
    private val look: suspend () -> ScreenSnapshot?,
) {
    suspend fun now(): ScreenSnapshot? = look()
}

/** Argument that points at a target by description: the element's displayed text, name or input field placeholder; a role may be added. */
const val DESCRIBED_ELEMENT = "element"
const val DESCRIBED_ROLE = "role"
/** Given together with the description, it is unclear which one to use. */
private val DESCRIBED_CONFLICTS = listOf("ref", "snapshotId", "x", "y")
/** Error code when a page dialog is open (see DIALOG_OPEN in cap/web). */
private const val DIALOG_OPEN_CODE = "dialog_open"
/** At most how many matches to list when more than one matches. */
private const val DESCRIBED_LISTED = 6
/** Reasons from a visual search where it found nothing or was not confirmed (the others are the frame being unavailable or unviewable). */
private val SIGHT_MISSED = setOf("not_found", "name_mismatch", "not_confirmed", "unverified_grounding", "vision_unavailable")

/** Result of finding a target by description: [Resolved] is the arguments converted to a ref plus the element found, [Failed] is the failure result handed to the model. */
sealed interface Described {
    /**
     * [note]: a note attached to the action result when matched by prefix or containment (what was described, what
     * was actually matched); null when exactly equal.
     * [element] null: it was not in the tree; the point found visually was used (arguments converted to @vN).
     */
    class Resolved(val args: JsonObject, val element: UiElement?, val note: JsonObject? = null) : Described
    class Failed(val result: ToolExecution) : Described
}

/**
 * The UI state shared by the capabilities that have a UI (phone, web) in one task: one screen-read state per surface,
 * which surface is being operated, the action target seen by the execution entry, and the targets and anchors routes
 * need. The dispatcher uses it before and after each call for UI clean-up (a look before the action, computing the
 * actual effect, attaching the post-action UI, clearing visually located points); the dispatcher and the core loop do
 * not know it and use it only through [SurfaceLayer]. At task start [attach] puts it into the tool context, and tools
 * with a UI take it from there.
 */
internal class Interfaces(
    surfaces: List<Surface>,
    stableRefs: Boolean,
    private val config: ToolConfig,
    val shots: Shots,
    /** The UI text read most recently (shared across surfaces); if what is read after an action equals it, it is not recorded again. */
    lastRead: () -> String?,
) : SurfaceLayer {
    companion object {
        /**
         * The UI state for this task: [surfaces] are the surfaces with ports attached, and when [stableRefs] is on,
         * refs stay stable across screen reads (always on when actions carry their own screen and when element
         * priority is on). Put into [ctx], where tools with a UI take it from.
         */
        fun attach(ctx: ToolContext, surfaces: List<Surface>, stableRefs: Boolean): Interfaces =
            Interfaces(surfaces, stableRefs || ctx.config.inlineScreen || ctx.config.adaptiveVision, ctx.config, ctx.shots) {
                ctx.evidence.recentReads.lastOrNull()
            }.also { ctx[INTERFACES] = it }
    }

    val focus = SurfaceFocus(surfaces)
    val sessions: Map<String, ScreenSession> = focus.surfaces.associate { s ->
        s.id to ScreenSession(s, stableRefs, config.visionEnabled, config.recordFrames, lastRead, shots, config.vision, config.adaptiveVision)
    }
    private val gateView = GateView()

    suspend fun checkAppAccess(tool: AgentTool?, after: Boolean = false) {
        if (tool == null || tool.offScreen || tool.capability !in sessions || tool.verb == Verb.ListApps) return
        sessionFor(tool).checkAppAccess(current = after || tool.verb !in APP_TARGET_VERBS)
    }

    /** The screen-read state of the surface being operated right now. */
    val session: ScreenSession get() = sessions.getValue(focus.current.id)

    override val current: String get() = focus.current.id

    override fun enter(id: String?) = focus.enter(id)

    override fun leave(succeeded: Boolean) = focus.leave(succeeded)

    /** When on, takes the target element and anchors before a write executes (set by the loop when the playback switch is on). */
    override var captureTargets: Boolean = false

    /** The screen-read state of the surface for tool [tool]; the most recently operated one when it is not a screen tool. */
    private fun sessionFor(tool: AgentTool?): ScreenSession = tool?.capability?.let(sessions::get) ?: session

    /** Which surface this tool acts on; null when it is not a screen tool. */
    override fun surfaceOf(tool: AgentTool?): String? = tool?.capability?.takeIf { it in sessions }

    /**
     * A tool for one surface operated with another surface's ref: the result is wrong_surface, stating which surface
     * the ref belongs to and which tool should be used instead. Null when the ref cannot be attributed to any surface
     * (expired too long ago, mistyped), and it is passed to the tool as usual (handled as expired).
     */
    override fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution? {
        val own = tool.capability?.let(sessions::get) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        if (own.owns(sid)) return null
        val owner = sessions.values.firstOrNull { it.owns(sid) }
            ?: sessions.values.firstOrNull { s -> sid.startsWith(s.surface.family.snapshotPrefix) && sid.drop(s.surface.family.snapshotPrefix.length).firstOrNull()?.isDigit() == true }
            ?: return null
        if (owner === own) return null
        val ownerId = owner.surface.id
        return fail("wrong_surface", Feedback.format("wrong_surface",
            "id" to sid, "owner" to ownerId, "tool" to tool.name, "surface" to own.surface.id,
            "instead" to (tools.counterpart(tool.name, ownerId) ?: tools.nameOf(Verb.Observe, ownerId) ?: ownerId),
            "observe" to (tools.nameOf(Verb.Observe, own.surface.id) ?: tool.name)))
    }

    /** What is done on the UI before one call: the targets routes need, and a look before actions that modify the UI. Wrapped up by [finish]. */
    private class Prep(
        val session: ScreenSession,
        val probe: ScreenSession.EffectProbe?,
        val aimed: Pair<UiElement?, List<Int>?>?,
        val playback: com.wangzan101.mika.core.run.record.PlaybackCapture? = null,
    ) {
        /** This call reports the actual effect (effect) from the UI side. */
        val reportsEffect: Boolean get() = probe != null
    }

    override suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution {
        if (tool.offScreen || tool.verb == Verb.ListApps) return body(false)
        // A target-based launch may leave a restricted app; in that case the old page is not captured, and only the allowed page is attached after the launch.
        if (tool.verb in APP_TARGET_VERBS) {
            val raw = body(false)
            return finish(tool, args, Prep(session, null, null), raw, inlineScreen)
        }
        val prep = prepare(tool, args)
        return finish(tool, args, prep, body(prep.reportsEffect), inlineScreen)
    }

    private suspend fun prepare(tool: AgentTool, o: JsonObject): Prep {
        val session = this.session
        gateView.capture = if (captureTargets && tool.routable) gateView.captureTarget(session, tool.verb, o) else null
        // Actions that modify the UI: look before the action (element table + frame), compute the actual effect after it
        val probe = if (tool.reportsEffect) session.probe(o.argStr("snapshotId")) else null
        val aimed = probe?.let { effectTarget(session, tool.verb, o, it.before) }
        val playback = if (config.recordFrames && tool.writesScreen) {
            val frame = session.playbackFrame()
            val checked = gateView.gateArgs(session, tool.verb, o)
            val snap = gateView.snapshotFor(session, o) ?: probe?.before
            val element = aimed?.first ?: snap?.elements?.firstOrNull { it.ref == o.argStr("ref") }
            com.wangzan101.mika.core.run.record.PlaybackCapture(frame, null,
                com.wangzan101.mika.core.run.record.PlaybackMark.of(tool.verb, checked, element, snap))
        } else null
        return Prep(session, probe, aimed, playback)
    }

    /** The call was not on the UI (or never reached [prepare]): no route target is kept. */
    override fun skip() { gateView.capture = null }

    /**
     * After one call: for a successful action, attach the UI difference per [inlineScreen]; attach the frame per the
     * observation policy and report the actual effect; once this surface has been touched (failed actions count,
     * calls not executed because of bad arguments do not), visually located points previously made on this surface are
     * no longer trustworthy.
     */
    private suspend fun finish(tool: AgentTool, o: JsonObject, prep: Prep, raw: ToolExecution, inlineScreen: Boolean): ToolExecution {
        val session = prep.session
        session.probeDone()
        val readsAtAction = session.reads
        var result = raw
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (inlineScreen && tool.showsScreenAfter && ok) result = session.withScreen(result)
        // In legacy mode the visual main model looks at the post-action frame directly; tiered mode adds the image only when the UI difference or the next observe needs it.
        if (tool.showsScreenAfter && ok && !config.adaptiveVision && result.images.isEmpty() && "screen" !in result.content) result = session.withFrame(result)
        val probe = prep.probe
        val aimed = prep.aimed
        if (ok && probe != null && aimed != null) {
            val typed = if (tool.verb == Verb.TypeText) o.argStr("text") else null
            result = session.withEffect(result, probe, tool.verb, readsAtAction, aimed.first, aimed.second, typed)
        }
        // The pre-action location is used for attribution; for app and web launches the destination each returns is read instead.
        if (ok && tool.verb != Verb.LaunchApp && tool.verb != Verb.Navigate) {
            probe?.before?.let { before ->
                val location = if (before.web != null) UrlHost.of(before.web.url)?.let { "actionSite" to JsonPrimitive(it) }
                else (before.packageName ?: before.unreadableApp)?.let { "actionApp" to JsonPrimitive(it) }
                if (location != null) result = result.with(record = JsonObject(result.record + location))
            }
        }
        if (tool.writesScreen && result.content["error"] != JsonPrimitive("invalid_arguments")) session.clearLocated()
        if (config.recordFrames && tool.writesScreen && result.content["error"] != JsonPrimitive("invalid_arguments")) {
            // Re-check the current page's permission and screenshot-protection state; capture only the surface this action was on.
            result = result.with(playback = com.wangzan101.mika.core.run.record.PlaybackCapture(
                prep.playback?.before, result.frame ?: result.image ?: session.playbackFrame(), if (ok) prep.playback?.mark else null))
        }
        return result
    }

    /**
     * The target used to judge the effect: for ref-based actions it is that element in the model's screen; for typing
     * and clearing without a ref it is the input field that had focus before the action; the frame region is the
     * target's bounds, and for coordinate actions (including visually located taps) a small area around the landing
     * point. When there is neither, it is (null, null).
     */
    private fun effectTarget(session: ScreenSession, verb: Verb, o: JsonObject, before: ScreenSnapshot?): Pair<UiElement?, List<Int>?> {
        val ref = o.argStr("ref")?.takeIf { !LOCATED_REF.matches(it) }
        val el = when {
            ref != null -> gateView.snapshotFor(session, o)?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.TypeText || verb == Verb.ClearText -> before?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        fun num(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
        val point = gateView.gateArgs(session, verb, o).let { a -> num(a, "x")?.let { x -> num(a, "y")?.let { y -> x to y } } }
        val region = el?.bounds?.takeIf { it.size == 4 } ?: point?.let { (x, y) ->
            val d = EffectTuning.POINT_REGION
            listOf((x - d).coerceAtLeast(0), (y - d).coerceAtLeast(0), (x + d).coerceAtMost(999), (y + d).coerceAtMost(999))
        }
        return el to region
    }

    /**
     * Tap and type that point at the target by description ([DESCRIBED_ELEMENT] argument, no ref and no coordinates):
     * re-reads a screen right now (registering its ID), finds the unique matching element, and replaces the arguments
     * with its ref + snapshotId, after which judging, approval, execution and verification are the same as with a ref.
     * When typing and the tree is missing a part or unusable, or contains no matching input field, it falls back to
     * visual location (see [bySight]).
     * Null for other calls; when nothing is found, more than one matches, or the arguments conflict, it is a failure
     * result for the model and nothing is executed.
     */
    suspend fun described(tool: AgentTool?, args: JsonObject): Described? {
        val verb = tool?.verb
        if (verb != Verb.Tap && verb != Verb.TypeText) return null
        val session = tool.capability?.let(sessions::get) ?: return null
        val text = args.argStr(DESCRIBED_ELEMENT)
        if (text == null) {
            if (DESCRIBED_ROLE in args) return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ROLE: only used together with $DESCRIBED_ELEMENT"))
            return null
        }
        DESCRIBED_CONFLICTS.firstOrNull { it in args }?.let {
            return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ELEMENT: give either $DESCRIBED_ELEMENT or $it, not both"))
        }
        val role = args.argStr(DESCRIBED_ROLE)?.trim()?.ifEmpty { null }
        // An internally read screen: the model did not see it, so it does not count as read (it does not clear "the screen in hand is stale"); this screen is kept as the "before" for the following action
        val snap = screenAttempt { session.snapshot(internal = true) }
            ?: return Described.Failed(fail("screen_unavailable", Feedback["screen_unavailable"]))
        // A page dialog is open: the page is halted and nothing can be tapped
        if (snap.web?.dialog != null) {
            session.clearPrimed()
            return Described.Failed(failCode(DIALOG_OPEN_CODE))
        }
        val observe = session.surface.family.tool("observe")
        val shown = text.trim().take(80)
        val m = DescribedTarget.match(snap.elements, ElementQuery(text, role, field = verb == Verb.TypeText))
        if (m !is DescribedMatch.Found) session.clearPrimed()
        return when (m) {
            is DescribedMatch.Found -> Described.Resolved(
                JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                    mapOf("ref" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
                m.element,
                // Not an exactly identical name: state honestly which element the description matched
                if (m.exact) null else JsonObject(mapOf("resolvedFrom" to JsonPrimitive(shown), "matched" to JsonPrimitive(m.element.name.take(80)),
                    "matchedRef" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
            )
            is DescribedMatch.Ambiguous -> Described.Failed(fail("element_ambiguous", Feedback.format("described.ambiguous",
                "text" to shown, "count" to m.candidates.size, "snapshot" to snap.snapshotId,
                "candidates" to m.candidates.take(DESCRIBED_LISTED).joinToString("; ") { e ->
                    val near = DescribedTarget.neighbour(e, snap.elements)?.let { " near 「${it.take(30)}」" }.orEmpty()
                    val off = if (DescribedTarget.usable(e, verb == Verb.TypeText)) "" else " (not actionable)"
                    "${e.ref} ${e.role} 「${e.name.take(40)}」 at ${e.bounds}$near$off"
                })))
            DescribedMatch.None -> {
                // Typing and this screen's tree is missing a part or unusable: not matching in the tree does not mean it is not on screen, so locate the field visually instead
                val locator = session.locator
                val named = DescribedTarget.match(snap.elements, ElementQuery(text, role)) as? DescribedMatch.Found
                val unexposedField = named?.exact == true && !named.element.role.endsWith("Button", ignoreCase = true)
                if (verb == Verb.TypeText && locator != null && (session.treeBlind(snap) || unexposedField)) bySight(session, locator, args, shown)
                else Described.Failed(fail("element_not_found", Feedback.format("described.none",
                    "text" to shown, "role" to (role?.let { " with role $it" } ?: ""), "observe" to observe)))
            }
        }
    }

    /**
     * Locate the input field pointed at by description visually (with the same checks as find_visually: re-check with
     * a red box, flag ones without evidence), and replace the arguments with the @vN found: from then on it is the same
     * as the model passing @vN itself — verify the frame has not changed first, the tap goes through the
     * irreversibility decision, and after pasting the on-screen text is checked for whether the tap hit.
     */
    private suspend fun bySight(session: ScreenSession, locator: Locator, args: JsonObject, shown: String): Described =
        when (val r = locator.locate(shown, null)) {
            // Looked and found nothing (or the visual step itself failed): rephrase or give a point; frame unavailable or unviewable (protected, no app open yet) follows find_visually's explanation
            is LocateResult.NotFound -> Described.Failed(fail("element_not_found", Feedback.format("described.not_seen",
                "text" to shown, "reason" to r.reason,
                "next" to if (r.reason in SIGHT_MISSED) Feedback["described.not_seen.retry"] else r.message)))
            is LocateResult.Found -> {
                session.locatedInfo[r.ref] = Located(shown, null, r.seen, r.unverified)
                Described.Resolved(
                    JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                        mapOf("ref" to JsonPrimitive(r.ref), "snapshotId" to JsonPrimitive(r.snapshotId))),
                    null,
                    JsonObject(listOfNotNull("resolvedFrom" to JsonPrimitive(shown), "foundBySight" to JsonPrimitive(true),
                        r.seen?.let { "seen" to JsonPrimitive(it) }).toMap()),
                )
            }
        }

    /** The target of this step as seen by the execution entry (see [ActionTarget]); read-only, no action is executed. */
    fun actionTarget(tool: AgentTool?, args: JsonObject): ActionTarget {
        val session = sessionFor(tool)
        val verb = tool?.verb
        val checked = gateView.gateArgs(session, verb, args)
        return ActionTarget(checked, gateView.snapshotFor(session, checked), gateView.visionSourced(session, verb, args),
            gateView.confirmedSeen(session, verb, args), snapshotAgeMs(args.argStr("snapshotId")), ::peek)
    }

    /** How many milliseconds ago that screen was read; null when it is not among any surface's recent reads. */
    private fun snapshotAgeMs(snapshotId: String?): Long? = sessions.values.firstNotNullOfOrNull { s ->
        s.recentSnapshots.firstOrNull { it.snapshotId == snapshotId }?.let { snap -> s.readAt[snap.snapshotId]?.let { System.currentTimeMillis() - it } }
    }

    /** That screen read (looked up on the surface of tool [tool] by the snapshotId in the arguments); null when it is not among the recent reads. */
    fun snapshotFor(tool: AgentTool?, args: JsonObject): ScreenSnapshot? = gateView.snapshotFor(sessionFor(tool), args)

    /** For run records: the port ref the model's ref corresponds to right now; empty when stable refs are off, there is no @eN, or the ref has expired. */
    override fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject = gateView.portRecord(sessionFor(tool), args)

    /** When the previous call was a write, its target element (null when absent or unrecognized) and the pre-action anchors; otherwise null. */
    fun lastActionTarget(): Pair<Selector?, Anchors>? = gateView.capture?.let { it.target to it.before }

    /** The screen before the previous write was protected by the system against screenshots. */
    fun lastActionProtected(): Boolean = gateView.capture?.snapshot?.protected == true

    /** Reads a screen right now after the previous write (without registering an ID) and takes the text that newly appeared after the action; null when it was not a write or the read fails. */
    suspend fun actionAfter(): Anchors? {
        if (gateView.capture == null) return null
        return peek()?.let(::anchorsAfter)
    }

    /** Same as above, using the post-action screen the caller has already read. */
    fun anchorsAfter(after: ScreenSnapshot): Anchors? = gateView.anchorsAfter(after)

    /** Re-reads the most recently operated surface right now, without registering an ID or changing the screen the model holds; null when the read fails. */
    suspend fun peek(): ScreenSnapshot? = session.peek()

    /** Waits for the most recently operated surface to settle, at most [timeoutMs]; returns normally even when the state cannot be read. */
    override suspend fun settle(timeoutMs: Long) {
        screenAttempt { session.screen.waitStable(timeoutMs) }
    }

    /** A frame (original image) of the most recently operated surface right now and its frame ID, registered for review; protected pages and payment pages are not captured, and null when nothing can be captured. */
    override suspend fun visibleText(): List<String> = peek()?.elements?.map { it.name }.orEmpty()

    override suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome =
        UntilWait(session.screen, ::peek, clock, interrupted).run(until)

    override suspend fun imageNow(): Pair<ByteArray, Int>? = session.currentFrame()

    /** The screen sent after an action when the main model can see images: the frame and the element table are from the same moment; null when there is no app on screen yet. */
    override suspend fun viewNow(): ToolDispatcher.Glance? = session.currentView()

    /** Which screen reads the model can still see right now (after trimming and compaction); when the diff's base is not among them, a full snapshot is sent. */
    override fun setVisible(ids: Set<String>) { sessions.values.forEach { it.visible = ids } }

    /** The version of this surface right now as written in the run state (web mobile vs desktop); null when absent or unreadable. */
    override suspend fun mode(): String? = focus.surfaces.firstNotNullOfOrNull { it.mode }?.invoke()
}

private val APP_TARGET_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent)
