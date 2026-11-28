package com.wangzan101.mika.core.screen.uistate.tools

import com.wangzan101.mika.core.screen.uistate.ScreenFamily
import com.wangzan101.mika.core.safety.gate.GateTarget
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.COORD
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.REF
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SNAP
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.refExpired
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.screen.uistate.toPx
import com.wangzan101.mika.core.screen.uistate.DESCRIBED_ELEMENT
import com.wangzan101.mika.core.screen.uistate.DESCRIBED_ROLE
import com.wangzan101.mika.core.screen.vision.LOCATE_SCREEN_CHANGE
import com.wangzan101.mika.core.screen.vision.hamming
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.screen.uistate.action
import com.wangzan101.mika.core.screen.uistate.described
import com.wangzan101.mika.core.screen.uistate.lastOrSnapshot
import com.wangzan101.mika.core.screen.uistate.lastSnapshot
import com.wangzan101.mika.core.screen.uistate.located
import com.wangzan101.mika.core.screen.uistate.locatedInfo
import com.wangzan101.mika.core.screen.uistate.screen
import com.wangzan101.mika.core.screen.uistate.session
import com.wangzan101.mika.core.screen.uistate.withPortRef

internal class Tap(f: ScreenFamily) : ScreenTool(f, "tap") {
    override val verb = Verb.Tap
    override val batchable = true
    override val routable = f.routable
    override val label = "Tap"
    override val risk = Risk.External
    override val gateTarget = GateTarget.Tap
    override val showsScreenAfter = true
    override val reportsEffect = true
    override val logged = true
    override val def by lazy { ToolDef(name,
        describe(),
        mapOf(
            "ref" to str(REF), "snapshotId" to str(SNAP),
            "x" to int(COORD, 0, 999), "y" to int(COORD, 0, 999),
            "target" to str("Short label of what is being tapped (coordinate taps)."),
            DESCRIBED_ELEMENT to str(DESCRIBE_TAP), DESCRIBED_ROLE to str(DESCRIBE_ROLE),
            "clicks" to int("1 taps once (default), 2 double-taps.", 1, 2),
        )) }

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref(located = true)
        val x = a.coord("x"); val y = a.coord("y")
        val clicks = a.int("clicks", 1, 2) ?: 1
        val target = a.str("target", max = 80)
        // The execution entry point already turned the description into a ref; reaching here with a description means it bypassed the entry point, so do not guess
        if (a.str(DESCRIBED_ELEMENT) != null) throw ArgError(DESCRIBED_ELEMENT, "could not be resolved to an element here; use ref+snapshotId")
        if (ref == null && ctx.described && target == null) {
            throw ArgError("target", "required for a coordinate tap after ${family.tool("describe")}: say what you are tapping")
        }
        if (ref != null) {
            if (x != null || y != null) throw ArgError("x", "give either ref+snapshotId or x+y, not both")
        } else {
            if (x == null) throw ArgError("x", "required with y when no ref is given")
            if (y == null) throw ArgError("y", "required with x when no ref is given")
        }
        val twice = clicks == 2
        if (ref != null) {
            if (LOCATED_REF.matches(ref.first)) return tapLocated(ref.first, ref.second, twice, ctx)
            return ctx.withPortRef(ref) { r ->
                ctx.action(if (twice) ctx.screen.doubleTapRef(r!!.first, r.second) else ctx.screen.tapRef(r!!.first, r.second))
            }
        }
        val s = ctx.lastOrSnapshot()
        return ctx.action(tapAt(ctx, toPx(x!!, s.width), toPx(y!!, s.height), twice))
    }

    private suspend fun tapAt(ctx: ToolContext, x: Int, y: Int, twice: Boolean) =
        if (twice) ctx.screen.doubleTapXY(x, y) else ctx.screen.tapXY(x, y)

    /** Tap a point found by find_visually: take a fresh screenshot first, and do not tap if it differs too much from the picture when it was found (or cannot be captured). */
    private suspend fun tapLocated(ref: String, snapshotId: String, twice: Boolean, ctx: ToolContext): ToolExecution {
        val (x, y) = locatedPixel(ctx, ref, snapshotId) ?: return refExpired(Feedback["located_stale"], "located_stale")
        val info = ctx.locatedInfo[ref]
        val target = info?.target
        val r = ctx.action(tapAt(ctx, x, y, twice))
        if (target == null || r.content["ok"] != JsonPrimitive(true)) return r
        // An uncorroborated point: whether the tap did anything can only be judged from the effect, so say this in the hint first
        val hint = listOfNotNull(Feedback["located.unverified"].takeIf { info.unverified }, (r.content["hint"] as? JsonPrimitive)?.content)
        val extra = mapOf("located" to JsonPrimitive(target)) +
            (if (info.unverified) mapOf("unverifiedPoint" to JsonPrimitive(true)) else emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap())
        return ToolExecution(JsonObject(r.content + extra), changed = r.changed)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String =
        refTarget(o, r, ctx.lastSnapshot) ?: o.argStr("target") ?: "${o["x"]},${o["y"]}"
}

/**
 * The pixel position right now of a point found by find_visually ([ref] is @vN): take a fresh screenshot first; null when it differs too much from the picture when it was found (or cannot be captured), and do not tap there.
 * Pixels are converted per the current screen (falling back to the latest one when unreadable).
 */
internal suspend fun locatedPixel(ctx: ToolContext, ref: String, snapshotId: String): Pair<Int, Int>? {
    val (p, hash) = ctx.located.get(ref, snapshotId) ?: return null
    val screen = ctx.screen
    val png = if (screen.isProtected()) null else screen.screenshotPng()
    if (png == null || hamming(ctx.config.vision!!.ops.aHash(png), hash) > LOCATE_SCREEN_CHANGE) return null
    val s = ctx.session.peek() ?: ctx.lastOrSnapshot()
    return toPx(p.x, s.width) to toPx(p.y, s.height)
}

/** The target written in the action log when acting by ref: for a visually found point, what it was looking for; otherwise the element's name; the ref when neither is available. */
internal fun refTarget(o: JsonObject, r: ToolExecution, last: ScreenSnapshot?): String? = o.argStr("ref")?.let { ref ->
    (r.content["located"] as? JsonPrimitive)?.content
        ?: last?.elements?.firstOrNull { it.ref == ref }?.name?.takeIf(String::isNotBlank) ?: ref
}

internal const val DESCRIBE_TAP = "Instead of ref+snapshotId: the element's visible text or accessible name, as shown on the screen. It must match exactly one element on the screen right now."
internal const val DESCRIBE_ROLE = "Optional with element: the element's role as shown in the element list (e.g. button, link, textbox), to tell same-named elements apart."
