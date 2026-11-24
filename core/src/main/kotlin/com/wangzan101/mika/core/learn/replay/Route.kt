package com.wangzan101.mika.core.learn.replay

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.LegacyNames
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/**
 * How a route identifies an element: the ID is valid only within one screen read, so it is replaced by role, name,
 * resource ID, bounds (0–999) and the ordinal among elements of the same kind.
 * An empty [name] means the element is not identified by name; a form like `{1}` means the name comes from a slot in
 * the current utterance.
 * [uniqueAtRecord]: when recorded, this was the only element on that screen matching these traits.
 */
@Serializable
data class Selector(
    val role: String,
    val name: String,
    val resourceId: String?,
    val bounds: List<Int>,
    val ordinal: Int,
    val uniqueAtRecord: Boolean = false,
)

/** Anchors for one screen: the foreground package name plus at most [RouteAnchors.MAX] stable text fragments. */
@Serializable
data class Anchors(val packageName: String?, val texts: List<String>)

/** [argsJson] already has the ID removed; slots in typed text are written as `{1}`. */
@Serializable
data class RouteStep(val tool: String, val argsJson: String, val target: Selector?, val before: Anchors, val after: Anchors)

/** A way of doing something that has succeeded before. [template] is the original sentence with the slot values replaced by `{n}`. */
@Serializable
data class Route(
    val id: String,
    val template: String,
    val slots: Int,
    val packages: List<String>,
    val appVersions: Map<String, String>,
    val steps: List<RouteStep>,
    val successes: Int = 0,
    val consecutiveMisses: Int = 0,
    val missStep: Int? = null,
    val disabled: Boolean = false,
    val lastUsedMs: Long,
)

/** Size limits for a route: step count and persisted bytes. */
const val MAX_ROUTE_STEPS = 60
const val MAX_ROUTE_BYTES = 64 * 1024

/**
 * Routes recorded before a rename: old tool names in the steps are replaced with the tool that can be recorded into
 * routes now (of the verbs present on several screens, take the one that is replayable).
 * Current names are left alone; names that cannot be resolved are kept, and replay stops on them as "not a replayable
 * action".
 */
fun Route.withCurrentToolNames(legacy: LegacyNames, tools: ToolIndex): Route {
    if (steps.all { tools[it.tool] != null }) return this
    return copy(steps = steps.map { st ->
        if (tools[st.tool] != null) st else legacy.resolve(st.tool) { tools[it]?.routable == true }?.let { st.copy(tool = it) } ?: st
    })
}

object RouteCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: Route): String = json.encodeToString(Route.serializer(), r)
    fun decode(s: String): Route = json.decodeFromString(Route.serializer(), s)
}

/** Extracts selectors and anchors from a screen read. */
object RouteAnchors {
    const val MAX = 3
    private const val MAX_LEN = 12
    /** Numbers, times, amounts and the like can change every time, so they are not used as anchors. */
    private val VOLATILE = Regex("[0-9０-９¥￥$€£%]")
    private val LIST_ROLES = setOf("list", "grid", "recycler", "scroll")

    fun stable(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..MAX_LEN && !VOLATILE.containsMatchIn(t)
    }

    private fun container(e: UiElement) = e.scrollable || e.role.lowercase() in LIST_ROLES

    private fun inside(e: UiElement, c: UiElement) = e !== c && e.bounds.size == 4 && c.bounds.size == 4 &&
        e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] && e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]

    /**
     * Texts that can serve as anchors, deduplicated in screen order, with those having a resource ID first. Entries
     * inside lists and scrollable areas are content (contacts, messages, products), and text inside input fields was
     * written by the user — neither counts; only the page's fixed frame (title bar, tabs, buttons) is taken.
     */
    fun texts(s: ScreenSnapshot): List<String> {
        val boxes = s.elements.filter(::container)
        return s.elements
            .filter { e -> !e.editable && !container(e) && boxes.none { inside(e, it) } && stable(e.name) }
            .sortedBy { it.resourceId == null }
            .map { it.name.trim() }.distinct()
    }

    fun before(s: ScreenSnapshot?, target: UiElement?): Anchors {
        if (s == null) return Anchors(null, emptyList())
        val self = target?.name?.trim()
        return Anchors(s.packageName, texts(s).filter { it != self }.take(MAX))
    }

    /** Text that newly appeared after the action (not present on the screen before it). */
    fun after(before: ScreenSnapshot?, after: ScreenSnapshot): Anchors {
        val old = before?.let(::texts)?.toSet().orEmpty()
        return Anchors(after.packageName, texts(after).filter { it !in old }.take(MAX))
    }

    /**
     * The name stored in a selector: for input fields only the hint text is kept (empty if there is none), since that
     * belongs to the UI rather than being typed by the user;
     * the names of other elements must be as stable as anchors (short, no digits), otherwise no name is stored and the
     * element is identified only by role, resource ID and ordinal.
     */
    fun storedName(e: UiElement): String = when {
        e.editable -> if (e.hint && stable(e.name)) e.name.trim() else ""
        stable(e.name) -> e.name.trim()
        else -> ""
    }

    fun selector(s: ScreenSnapshot, e: UiElement): Selector {
        val name = storedName(e)
        // identify by the stored traits: when no name was stored, compare only role and resource ID
        fun same(x: UiElement) = x.role == e.role && x.resourceId == e.resourceId && (name.isEmpty() || x.name.trim() == e.name.trim())
        val ordinal = s.elements.takeWhile { it !== e }.count(::same)
        return Selector(e.role, name, e.resourceId, e.bounds, ordinal, uniqueAtRecord = s.elements.count(::same) == 1)
    }

    /** The smallest element containing the point (0–999); among equals, prefer the clickable one. */
    fun elementAt(s: ScreenSnapshot, x: Int, y: Int): UiElement? = s.elements
        .filter { it.bounds.size == 4 && x in it.bounds[0]..it.bounds[2] && y in it.bounds[1]..it.bounds[3] }
        .minWithOrNull(compareBy<UiElement> { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }.thenBy { !it.clickable })
}

/**
 * A write that took effect: ok and the screen changed, or the input reads back identically; launching an app or
 * opening a link counts as soon as it succeeds (the gate has already waited for the target to be in the foreground).
 */
fun effectiveWrite(verb: Verb?, result: JsonObject): Boolean {
    if (result["ok"] != JsonPrimitive(true)) return false
    return result["changed"] == JsonPrimitive(true) || result["verification"] == JsonPrimitive("match") ||
        (verb in OPENS && result["changed"] != JsonPrimitive(false))
}

/** Launching an app or opening a link: succeeding means having arrived at the target. */
internal val OPENS = setOf(Verb.LaunchApp, Verb.OpenLink)
