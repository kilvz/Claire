package com.wangzan101.mika.core.screen.uistate

import com.wangzan101.mika.core.learn.replay.Anchors
import com.wangzan101.mika.core.learn.replay.RouteAnchors
import com.wangzan101.mika.core.learn.replay.Selector
import com.wangzan101.mika.core.tool.LOCATED_REF
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The target of one call as seen by the execution entry (irreversibility decisions, run records, routes): which
 * screen to judge against, what an @vN is replaced with, what the port ref is, and the target element and anchors
 * before a write. Everything is looked up in the screen-read state of the surface the call belongs to ([session]);
 * read-only, no action is ever executed.
 */
internal class GateView {
    fun snapshotFor(session: ScreenSession, args: JsonObject): ScreenSnapshot? {
        val id = args.argStr("snapshotId")
        // An old screen's ref is still on the latest screen: the action lands on it in the latest screen, so judge against the latest screen (same ref, snapshotId as given by the model)
        val ref = args.argStr("ref")
        val stabilizer = session.stabilizer
        if (stabilizer != null && id != null && ref != null && !LOCATED_REF.matches(ref)) {
            val r = stabilizer.resolve(ref, id)
            if (r is Resolved.Current && r.relocated) stabilizer.latest?.let { return it.screen.copy(snapshotId = id) }
        }
        if (id != null) return session.recentSnapshots.lastOrNull { it.snapshotId == id }
        return session.lastSnapshot
    }

    fun portRecord(session: ScreenSession, args: JsonObject): JsonObject {
        val st = session.stabilizer ?: return JsonObject(emptyMap())
        val ref = args.argStr("ref")
        val id = args.argStr("snapshotId")
        if (ref == null || id == null || LOCATED_REF.matches(ref)) return JsonObject(emptyMap())
        val r = st.resolve(ref, id) as? Resolved.Current ?: return JsonObject(emptyMap())
        return buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }
    }

    /**
     * A tap on @vN is replaced with its registered coordinates and judged as a coordinate tap, along with what was
     * being looked for (target), the original text (text) and the text the vision model saw (seen); typing into or
     * clearing @vN is likewise replaced with coordinates, keeping the other arguments (Enter).
     * Everything else is returned unchanged (an unknown @vN becomes ref_expired at execution and is never tapped).
     */
    fun gateArgs(session: ScreenSession, verb: Verb?, args: JsonObject): JsonObject {
        val ref = locatedRef(verb, args) ?: return args
        val (p, _) = session.located.get(ref.first, ref.second) ?: return args
        val info = session.locatedInfo[ref.first]
        return buildJsonObject {
            if (verb != Verb.Tap) args.forEach { (k, v) -> if (k != "ref" && k != "snapshotId") put(k, v) }
            put("x", p.x); put("y", p.y)
            (info?.target ?: (args["target"] as? JsonPrimitive)?.content)?.let { put("target", it) }
            info?.text?.let { put("text", it) }
            info?.seen?.let { put("seen", it) }
        }
    }

    /**
     * A registered @vN, or a coordinate tap carrying a target after a describe has been seen (one without a target is
     * rejected by argument validation). Such taps are divided between the large and small models and left to the main
     * model (see [com.wangzan101.mika.core.action.ConfirmDecision.mayBeIrreversible]).
     */
    fun visionSourced(session: ScreenSession, verb: Verb?, args: JsonObject): Boolean {
        if (verb != Verb.Tap) return false
        locatedRef(verb, args)?.let { return session.located.get(it.first, it.second) != null }
        val target = args.argStr("target")
        return session.described && args["ref"] == null && args["x"] != null && !target.isNullOrBlank()
    }

    fun confirmedSeen(session: ScreenSession, verb: Verb?, args: JsonObject): String? {
        val ref = locatedRef(verb, args) ?: return null
        if (session.located.get(ref.first, ref.second) == null) return null
        return session.locatedInfo[ref.first]?.seen?.takeIf { it.isNotBlank() }
    }

    /** Taps, and typing at a point and clearing, accept @vN. */
    private fun locatedRef(verb: Verb?, args: JsonObject): Pair<String, String>? {
        if (verb != Verb.Tap && verb != Verb.TypeText && verb != Verb.ClearText) return null
        val ref = args.argStr("ref")?.takeIf(LOCATED_REF::matches) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        return ref to sid
    }

    /** For routes: the target element, the anchors and that screen taken from the screen read at that time before a write executed. */
    class Capture(val target: Selector?, val before: Anchors, val snapshot: ScreenSnapshot?)
    var capture: Capture? = null

    fun anchorsAfter(after: ScreenSnapshot): Anchors? = capture?.let { RouteAnchors.after(it.snapshot, after) }

    fun captureTarget(session: ScreenSession, verb: Verb, o: JsonObject): Capture {
        // Recognize @vN by its registered coordinates
        val checked = gateArgs(session, verb, o)
        val ref = checked.argStr("ref")
        val own = snapshotFor(session, checked)
        val s = own ?: session.lastSnapshot
        fun coord(k: String): Int? = when (val v = checked[k]) {
            is JsonPrimitive -> v.content.toDoubleOrNull()?.roundToInt()
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }.takeIf { it.size == 2 }?.average()?.roundToInt()
            else -> null
        }
        val x = coord("x"); val y = coord("y")
        val el = when {
            ref != null -> own?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.Tap && x != null && y != null -> s?.let { RouteAnchors.elementAt(it, x, y) }
            // Typing and clearing act on the focused input field
            verb == Verb.TypeText || verb == Verb.ClearText -> s?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        return Capture(el?.let { RouteAnchors.selector(s!!, it) }, RouteAnchors.before(s, el), s)
    }
}
