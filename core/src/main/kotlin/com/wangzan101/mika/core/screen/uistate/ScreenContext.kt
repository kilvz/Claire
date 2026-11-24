package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FAILURE_MESSAGES
import com.wangzan101.mika.core.tool.HINT_NOT_SETTLED
import com.wangzan101.mika.core.tool.HINT_TAP_UNCHANGED
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.switchHint
import com.wangzan101.mika.core.screen.vision.LocateRegistry
import com.wangzan101.mika.core.screen.vision.Locator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Interface state inside the tool context: put in at startup by [Interfaces.attach], and taken from here by tools that
 * have an interface (phone, web, and the generic tools that name a screen).
 * The tool engine (core/tool) knows nothing about this and only reaches the interface side via
 * [com.wangzan101.mika.core.tool.SurfaceLayer].
 */

/** Key of the interface state in the tool context. */
internal val INTERFACES = CapabilityKey<Interfaces>("interfaces")

/** Interface state of this task (the copy put in at startup). */
internal val ToolContext.interfaces: Interfaces get() = this[INTERFACES] ?: error("interfaces not attached")

/** Interface state of the dispatcher's task (used by the execution entry points and when replaying decisions based on a screen read). */
internal val ToolDispatcher.interfaces: Interfaces get() = ctx.interfaces

/** Screen-read state of each screen, keyed by the capability id. */
internal val ToolContext.sessions: Map<String, ScreenSession> get() = interfaces.sessions

/** Which screen was touched most recently. */
internal val ToolContext.focus: SurfaceFocus get() = interfaces.focus

/** Screen-read state of the screen being operated right now (the dispatcher records it before each call per the screen the tool belongs to, see [focus]). */
internal val ToolContext.session: ScreenSession get() = sessions.getValue(focus.current.id)
internal val ToolContext.screen: ScreenPort get() = session.screen

/** The screen being operated right now. */
internal val ToolContext.surface: Surface get() = focus.current
internal val ToolContext.lastSnapshot: ScreenSnapshot? get() = session.lastSnapshot

/** Points found by find_visually on the current screen (@vN); cleared after any write operation on that screen. */
internal val ToolContext.located: LocateRegistry get() = session.located
internal val ToolContext.locatedInfo: MutableMap<String, Located> get() = session.locatedInfo

/** describe has been looked at since the last write operation: coordinate taps in that window are treated as points obtained from looking at the image. */
internal var ToolContext.described: Boolean
    get() = session.described
    set(v) { session.described = v }
internal val ToolContext.locator: Locator? get() = session.locator

internal fun ToolContext.clearLocated() = session.clearLocated()

/** Turn the port's action result into a tool result: no change, being switched to another App, and the page not settling are all written into the hint; failures are explained per error code. */
internal fun ToolContext.action(r: ActionResult): ToolExecution = when (r) {
    is ActionResult.Ok -> {
        // Switched to another App (not a payment page opened by the target App itself, etc.): the payment declaration is voided
        if (r.appSwitched != null && !r.switchedByTarget) refusedPay = null
        val hints = listOfNotNull(
            HINT_TAP_UNCHANGED.takeIf { r.changed == false && !reportsEffect && r.opened == null },
            // Opened onto the user's home screen and then pulled back is explained by the next clause, so do not also report being switched away
            r.appSwitched?.takeIf { r.opened == null }?.let { switchHint(it, r.switchedByTarget) },
            HINT_NOT_SETTLED.takeIf { r.settled == false },
            r.coveredBy?.let { Feedback.format("covered_by", "pkg" to it) },
            r.opened?.let { openedHint(it) },
            // The system asks on the user's screen, so leave it to the user: do not say how to choose
            r.chooser?.takeIf { r.opened?.picker != true }?.let { Feedback.format("launch.chooser", "pkg" to it.packageName) },
            // When the other copy is the one wanted, opening it needs no reminder; when what was opened is not the wanted copy, say so plainly
            (r.opened?.inProfile ?: r.inProfile)?.takeIf { it == OpenedElsewhere.OTHER_PROFILE && r.requestedUser == null }?.let { Feedback["profile.other"] },
            r.requestedUser?.takeIf { r.profileUser != null && r.profileUser != it }?.let { wanted ->
                if (r.inProfile == OpenedElsewhere.MAIN_PROFILE) Feedback["profile.main_not_requested"]
                else Feedback.format("profile.copy_not_requested", "user" to "${r.profileUser}", "wanted" to "$wanted")
            },
            r.hint,
        )
        ToolExecution(okObj {
            r.changed?.let { put("changed", it) }
            r.appSwitched?.let { put("app_switched", it) }
            if (r.appSwitched != null && r.switchedByTarget) put("opened_by_target", true)
            if (r.relocated) put("relocated", true)
            r.relocatedTo?.takeIf { r.relocated }?.let { put("relocatedTo", it) }
            if (r.settled == false) put("settled", false)
            r.coveredBy?.let { put("coveredBy", it) }
            r.chooser?.let { put("chooser", it.toJson()) }
            r.opened?.let { put("opened", it.toJson()) }
            r.inProfile?.let { put("inProfile", it) }
            r.profileUser?.let { put("profileUser", it) }
            r.requestedUser?.let { wanted -> r.profileUser?.let { put("requestedCopy", it == wanted) } }
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = r.changed)
    }
    is ActionResult.Failed ->
        if (r.reason == "ref_expired") refExpired(Feedback["ref_expired"], "element_lost")
        else {
            val code = r.reason.takeIf { Regex("^[a-z][a-z0-9_]*$").matches(it) } ?: "action_failed"
            val message = FAILURE_MESSAGES[code] ?: Feedback.format("action_failed", "reason" to r.reason)
            fail(code, r.hint?.let { "$message $it" } ?: message)
        }
}

/**
 * What the model is told when an action opened the App on the user's home screen: if it has been pulled back, say to
 * continue on Claire's screen and not to tap again; if the system is asking on the user's screen which one to open, say
 * it is left for the user to choose, not to tap again or choose for the user, and that the next action will wait for the
 * user to finish; if the user closed that prompt or handled it personally, say so plainly;
 * for a popup that needs the user's own handling (login verification and the like) left on the user's screen, say not
 * to pull it back and ask the user to tap there; for something left untouched on the user's screen (the user is using
 * it), say not to come again and ask the user to handle it.
 */
internal fun openedHint(o: OpenedElsewhere): String {
    val app = o.app ?: o.packageName
    return when {
        o.closed -> Feedback["spill.picker_closed"]
        o.byUser -> Feedback.format("spill.user_done", "app" to app)
        o.picker && o.onUserScreen -> Feedback.format("spill.user_picking", "app" to app)
        o.needsUser && o.onUserScreen -> Feedback.format("spill.user_popup", "app" to app)
        o.onUserScreen -> Feedback.format("spill.in_use", "app" to app)
        else -> Feedback.format("spill.pulled_back", "app" to app)
    }
}

/**
 * With stable refs enabled, first translate the model ref into a port ref before handing it to [act]: if the ref has
 * been retired, return ref_expired without executing; a ref from an older screen that is still in the latest screen is
 * executed as usual and marked relocated. The port ref is recorded separately in the run record.
 */
internal suspend fun ToolContext.withPortRef(ref: Pair<String, String>?, act: suspend (Pair<String, String>?) -> ToolExecution): ToolExecution {
    val st = session.stabilizer
    if (st == null || ref == null) return act(ref)
    val r = when (val resolved = st.resolve(ref.first, ref.second)) {
        is Resolved.Current -> resolved
        is Resolved.Stale -> return refExpired(Feedback["ref_expired"], resolved.cause.wire)
    }
    val e = act(r.portRef to r.portSnapshotId)
    val content = if (r.relocated && e.content["ok"] == JsonPrimitive(true)) JsonObject(e.content + ("relocated" to JsonPrimitive(true))) else e.content
    return ToolExecution(content, changed = e.changed, outcome = e.outcome, frame = e.frame, images = e.images,
        record = JsonObject(e.record + buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }))
}

/** The screen used for coordinate conversion: the latest screen read, reading one first if nothing has been read yet. */
internal suspend fun ToolContext.lastOrSnapshot(): ScreenSnapshot = session.lastSnapshot ?: session.snapshot()

/** Validate the declared app as the current screen spells it (the package name on the phone, the site host on the web). */
internal fun ToolContext.confirmTarget(app: String): String = surface.declaredTarget(app)
