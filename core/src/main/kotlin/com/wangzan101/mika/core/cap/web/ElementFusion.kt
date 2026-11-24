package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiStates
import com.wangzan101.mika.core.screen.uistate.normalize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Element fusion: merges the page script's element table (only visible, uncovered elements are listed) with the DevTools accessibility tree into one element list keyed by backendNodeId. Pure functions.
 * - In both: name, role, disabled/focused and other states come from the accessibility tree; position, visibility and occlusion come from the page script. Nodes ignored in the accessibility tree are not trusted.
 *   Textbox and drop-down names carry the value already filled in, so the page script's name is kept; roles of file controls and embedded-page placeholders are also kept from the page script.
 * - Only in the accessibility tree: only interactive roles are taken. For ones in cross-origin frames the page script cannot read (it lists just an "embedded page" placeholder),
 *   the box is clipped to the placeholder's visible range and inserted after the placeholder; for ones in documents the page script can read (such as a closed shadow root), they are taken only if a hit test confirms they are on top.
 * - Only in the page script: kept as-is; plain text/images whose element itself carries click-type listeners are promoted to clickable.
 * Order: document order. When [Input.budget] is exceeded, elements with no information are dropped first, then plain text, and interactive ones only last; the number dropped is recorded in omitted.
 * Refs are renumbered in order as @e1…; [Result.pageRefs] maps new refs → refs in the page script's ref table (absent for elements only in the accessibility tree).
 */
object ElementFusion {
    const val DEFAULT_BUDGET = 400
    /** At most this many accessibility-tree-only elements are added (each costs one box fetch and one hit test). */
    const val MAX_CANDIDATES = 60
    /** At most this many page-script elements are probed for listeners. */
    const val MAX_LISTENER_PROBES = 40
    private const val NAME_CHARS = 80

    /**
     * A node of the accessibility tree. [id]/[parent] are node ids within this frame's tree, [order] is the depth-first position within this frame's tree;
     * [states] are the names of boolean/tristate properties that are true (disabled, focused, readonly, editable, checked…).
     */
    data class AxNode(
        val id: String,
        val parent: String?,
        val frameId: String,
        val order: Int,
        val backendId: Int?,
        val role: String,
        val name: String,
        val ignored: Boolean = false,
        val states: Set<String> = emptySet(),
    )

    /** A rectangle in top-level viewport CSS pixels. */
    data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    data class Frame(val id: String, val parent: String?)

    enum class Origin { PAGE, BOTH, AX }

    /**
     * [page] is the PageTree result (view pixels) and [pageIds] is index-aligned with it; [owners] maps a frame element's backendNodeId → the frameId of the document inside it.
     * [boxes]/[onTop] are for candidate nodes present only in the accessibility tree (by backendNodeId): the box (already converted to top-level viewport coordinates) and the hit test result.
     * [listeners] are elements carrying click-type listeners. [scale] is the factor from CSS pixels → view pixels.
     */
    class Input(
        val page: List<UiElement>,
        val pageIds: List<Int?>,
        val owners: Map<Int, String> = emptyMap(),
        val frames: List<Frame> = emptyList(),
        val ax: List<AxNode> = emptyList(),
        val boxes: Map<Int, Box> = emptyMap(),
        val onTop: Map<Int, Boolean> = emptyMap(),
        val listeners: Set<Int> = emptySet(),
        val scale: Double = 1.0,
        val width: Int,
        val height: Int,
        val budget: Int = DEFAULT_BUDGET,
    )

    class Result(
        val elements: List<UiElement>,
        val pageRefs: Map<String, String>,
        val backendIds: Map<String, Int>,
        val origins: Map<String, Origin>,
        /** Placeholder element of the frame containing an accessibility-tree-only element (new ref → the placeholder's backendNodeId); empty for ones in the main document. */
        val frameOwners: Map<String, Int>,
        val omitted: Int,
    )

    /** A candidate node: [placeholder] is the index of its cross-origin frame's placeholder in the page script's element table, null inside documents the page script can read. */
    class Candidate(val node: AxNode, val placeholder: Int?, val ownerId: Int?)

    // ---------- Roles ----------

    private val CLICK_ROLES = setOf(
        "link", "button", "checkbox", "radio", "combobox", "switch", "tab", "menuitem", "option", "slider",
        "menuitemcheckbox", "menuitemradio", "treeitem", "listbox",
    )
    private val EDIT_ROLES = setOf("textbox")
    /** These roles supplied by the page script are not overridden by the accessibility tree: file is needed to recognize uploads, frame is a cross-origin frame placeholder. */
    private val KEEP_PAGE_ROLES = setOf("file", "frame")
    private val NON_INTERACTIVE_ROLES = setOf("heading", "image")

    /** Accessibility tree role → the same vocabulary as the page script; null for roles without semantics (generic, StaticText…). */
    fun role(ax: String): String? = when (ax) {
        "searchbox", "spinbutton" -> "textbox"
        "img" -> "image"
        "PopUpButton" -> "combobox"
        "DisclosureTriangle", "ToggleButton" -> "button"
        "Iframe", "IframePresentational" -> "frame"
        in CLICK_ROLES, in EDIT_ROLES, in NON_INTERACTIVE_ROLES -> ax
        else -> null
    }

    private fun interactive(role: String?) = role != null && (role in CLICK_ROLES || role in EDIT_ROLES)

    // ---------- Step 1: candidates whose boxes must be filled in ----------

    /** Nodes only in the accessibility tree that are interactive and worth adding; ones in cross-origin frames the page script cannot read come first. */
    fun candidates(input: Input): List<Candidate> {
        val matched = input.pageIds.filterNotNull().toSet()
        val byFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        val pageIndex = input.pageIds.withIndex().filter { it.value != null }.associate { it.value!! to it.index }
        val behind = placeholders(input)
        val out = mutableListOf<Candidate>()
        for (n in input.ax) {
            val id = n.backendId ?: continue
            if (n.ignored || id in matched || !interactive(role(n.role))) continue
            val ph = behind[n.frameId]
            if (ph == null) {
                // In a readable document an outer layer already has an interactive element the page script listed: the page script saw the outer layer but did not list this, so it is most likely covered or merely part of the outer layer
                val nodes = byFrame[n.frameId].orEmpty()
                var p = n.parent?.let(nodes::get)
                var covered = false
                while (p != null) {
                    val pi = p.backendId?.let(pageIndex::get)
                    if (pi != null && input.page[pi].let { it.clickable || it.editable }) { covered = true; break }
                    p = p.parent?.let(nodes::get)
                }
                if (covered) continue
            }
            out += Candidate(n, ph?.first, ph?.second)
        }
        return out.sortedBy { if (it.placeholder != null) 0 else 1 }.take(MAX_CANDIDATES)
    }

    /** frameId → (index of the placeholder in the page script's element table, the placeholder's backendNodeId): covers the placeholder frame itself and the frames nested inside it. */
    private fun placeholders(input: Input): Map<String, Pair<Int, Int>> {
        val direct = HashMap<String, Pair<Int, Int>>()
        input.pageIds.forEachIndexed { i, id ->
            if (id != null && input.page[i].role == "frame") input.owners[id]?.let { direct[it] = i to id }
        }
        if (direct.isEmpty()) return direct
        val parent = input.frames.associate { it.id to it.parent }
        val out = HashMap<String, Pair<Int, Int>>()
        for (f in input.frames.map { it.id } + direct.keys) {
            var cur: String? = f
            var hops = 0
            while (cur != null && hops++ < 32) {
                direct[cur]?.let { out[f] = it }
                if (out.containsKey(f)) break
                cur = parent[cur]
            }
        }
        return out
    }

    /** The backendNodeIds of a node's descendants (including itself) in this frame's accessibility tree: a hit test landing on any of them counts as hitting it. */
    fun subtreeIds(ax: List<AxNode>, node: AxNode): Set<Int> {
        val children = ax.filter { it.frameId == node.frameId }.groupBy { it.parent }
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(node))
        while (stack.isNotEmpty() && out.size < 500) {
            val n = stack.removeLast()
            n.backendId?.let(out::add)
            children[n.id]?.let(stack::addAll)
        }
        return out
    }

    /** The visible range of a candidate node (CSS pixels): inside cross-origin frames it is the placeholder's visible part, otherwise the viewport. The hit test point is the center of where the box intersects this range. */
    fun clip(input: Input, c: Candidate): Box {
        val k = input.scale.takeIf { it > 0 } ?: 1.0
        val r = c.placeholder?.let { rectOf(input.page[it], input.width, input.height) }
            ?: Rect(0.0, 0.0, input.width.toDouble(), input.height.toDouble())
        return Box(r.l / k, r.t / k, r.r / k, r.b / k)
    }

    /** Indices of page-script elements to probe for listeners: non-clickable plain text and images that the accessibility tree also does not report as interactive. */
    fun listenerProbes(input: Input): List<Int> {
        val axById = axByBackend(input.ax)
        return input.page.indices.filter { i ->
            val e = input.page[i]
            val id = input.pageIds.getOrNull(i) ?: return@filter false
            !e.clickable && !e.editable && (e.role == "text" || e.role == "image") &&
                !interactive(axById[id]?.takeIf { !it.ignored }?.let { role(it.role) })
        }.take(MAX_LISTENER_PROBES)
    }

    private fun axByBackend(ax: List<AxNode>): Map<Int, AxNode> {
        val m = HashMap<Int, AxNode>()
        for (n in ax) {
            val id = n.backendId ?: continue
            val old = m[id]
            if (old == null || (old.ignored && !n.ignored)) m[id] = n
        }
        return m
    }

    // ---------- Step 2: merge ----------

    fun fuse(input: Input): Result {
        val axById = axByBackend(input.ax)
        val merged = input.page.mapIndexed { i, e ->
            val id = input.pageIds.getOrNull(i)
            Item(merge(e, id?.let(axById::get), id != null && id in input.listeners), id, e.ref, if (id?.let(axById::get) != null) Origin.BOTH else Origin.PAGE, null)
        }
        val extra = axOnly(input)
        // Insertion: ones in cross-origin frames go right after the placeholder; ones in readable documents go after the last matched element of the same frame that precedes them in accessibility tree order
        val pageOrder = HashMap<Int, Pair<String, Int>>()
        input.pageIds.forEachIndexed { i, id -> id?.let(axById::get)?.let { pageOrder[i] = it.frameId to it.order } }
        val after = extra.groupBy { c ->
            c.placeholder ?: pageOrder.entries.filter { it.value.first == c.node.frameId && it.value.second < c.node.order }
                .maxByOrNull { it.value.second }?.key ?: (input.page.size - 1)
        }
        val all = ArrayList<Item>()
        if (input.page.isEmpty()) extra.forEach { all += it.item }
        merged.forEachIndexed { i, item ->
            all += item
            after[i]?.sortedBy { it.node.order }?.forEach { all += it.item }
        }
        val kept = budget(all, input.budget)
        val elements = ArrayList<UiElement>(kept.size)
        val pageRefs = HashMap<String, String>()
        val ids = HashMap<String, Int>()
        val origins = HashMap<String, Origin>()
        val owners = HashMap<String, Int>()
        kept.forEachIndexed { i, it ->
            val ref = "@e${i + 1}"
            elements += it.element.copy(ref = ref)
            it.pageRef?.let { r -> pageRefs[ref] = r }
            it.backendId?.let { b -> ids[ref] = b }
            it.ownerId?.let { o -> owners[ref] = o }
            origins[ref] = it.origin
        }
        return Result(elements, pageRefs, ids, origins, owners, all.size - kept.size)
    }

    private class Item(val element: UiElement, val backendId: Int?, val pageRef: String?, val origin: Origin, val ownerId: Int?)

    private class Extra(val node: AxNode, val placeholder: Int?, val item: Item)

    private fun merge(e: UiElement, ax: AxNode?, listener: Boolean): UiElement {
        val heard = listener && !e.clickable && !e.editable && (e.role == "text" || e.role == "image")
        val base = if (heard) e.copy(role = if (e.role == "text") "button" else e.role, clickable = true) else e
        if (ax == null || ax.ignored) return base
        val axRole = role(ax.role)
        val roleOut = when {
            base.role in KEEP_PAGE_ROLES || axRole == null || axRole == "frame" -> base.role
            interactive(axRole) -> axRole
            base.role == "text" -> axRole
            else -> base.role
        }
        val axName = cut(ax.name)
        // Textbox, drop-down and slider names contain the value (a password box only says how many characters are filled): keep the page script's
        val valued = base.editable || base.role in setOf("textbox", "combobox", "slider") || roleOut in EDIT_ROLES
        val name = when {
            valued -> base.name.ifBlank { axName }
            axName.isNotBlank() -> axName
            else -> base.name
        }
        val disabled = "disabled" in ax.states
        val readonly = "readonly" in ax.states
        return base.copy(
            role = roleOut,
            name = name,
            clickable = !disabled && (base.clickable || roleOut in CLICK_ROLES),
            editable = !disabled && !readonly && (base.editable || (roleOut in EDIT_ROLES && "editable" in ax.states)),
            focused = base.focused || "focused" in ax.states,
            states = base.states + ax.states.filter { it in UiStates.ALL },
        )
    }

    private fun axOnly(input: Input): List<Extra> {
        val w = input.width
        val h = input.height
        val view = Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        val pageRects = input.page.map { rectOf(it, w, h) }
        val out = ArrayList<Extra>()
        val keptRects = ArrayList<Pair<Extra, Rect>>()
        val nodesByFrame = input.ax.groupBy { it.frameId }.mapValues { (_, l) -> l.associateBy { it.id } }
        for (c in candidates(input)) {
            val id = c.node.backendId ?: continue
            val box = input.boxes[id] ?: continue
            val top = input.onTop[id]
            // In a readable document: the page script did not list it, so the hit test must explicitly say it is on top; in a cross-origin frame: it is only skipped when the hit test says it is covered
            if (c.placeholder == null && top != true) continue
            if (c.placeholder != null && top == false) continue
            val clip = c.placeholder?.let { pageRects[it] } ?: view
            val raw = Rect(box.l * input.scale, box.t * input.scale, box.r * input.scale, box.b * input.scale)
            val vis = raw.intersect(clip)?.intersect(view) ?: continue
            if (vis.r - vis.l < 1 || vis.b - vis.t < 1) continue
            val roleOut = role(c.node.role) ?: continue
            val name = cut(c.node.name)
            // No node id match on the page-script side but actually the same element (same name, or interactive and largely overlapping): do not list it twice
            val dup = input.page.indices.any { i ->
                val e = input.page[i]
                if (input.pageIds[i] != null || e.role == "frame") return@any false
                val r = pageRects[i]
                r.contains(vis.cx, vis.cy) && (e.name.equals(name, ignoreCase = true) || ((e.clickable || e.editable) && r.iou(vis) > 0.5))
            }
            if (dup) continue
            // Same-named interactive nodes nested inside in the accessibility tree (a button inside a link), or ones with the same role and name in nearly the same box: keep only the outer/first
            val nested = keptRects.any { (k, r) ->
                val sameName = k.item.element.name.equals(name, ignoreCase = true)
                (sameName || name.isBlank()) && isAncestor(nodesByFrame[c.node.frameId].orEmpty(), k.node, c.node) ||
                    (sameName && k.item.element.role == roleOut && r.iou(vis) > 0.8)
            }
            if (nested) continue
            val disabled = "disabled" in c.node.states
            val l = vis.l.roundToInt()
            val t = vis.t.roundToInt()
            val r = vis.r.roundToInt()
            val b = vis.b.roundToInt()
            val el = UiElement(
                ref = "",
                role = roleOut,
                name = name,
                centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
                centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
                bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
                resourceId = null,
                clickable = !disabled && roleOut in CLICK_ROLES,
                editable = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
                scrollable = false,
                focused = "focused" in c.node.states,
                states = c.node.states.filter { it in UiStates.ALL }.toSet(),
                // A textbox only the accessibility tree can see (cross-origin frame, not listed by the page script): there is no way to tell whether it is a password box, so treat it as one
                secret = !disabled && "readonly" !in c.node.states && roleOut in EDIT_ROLES,
            )
            val x = Extra(c.node, c.placeholder, Item(el, id, null, Origin.AX, c.ownerId))
            out += x
            keptRects += x to vis
        }
        return out
    }

    private fun isAncestor(nodes: Map<String, AxNode>, outer: AxNode, inner: AxNode): Boolean {
        if (outer.frameId != inner.frameId) return false
        var p = inner.parent?.let(nodes::get)
        var hops = 0
        while (p != null && hops++ < 256) {
            if (p.id == outer.id) return true
            p = p.parent?.let(nodes::get)
        }
        return false
    }

    /** Over budget, drops in the order "no information → plain text and other non-interactive → interactive", each from the back, while kept items keep their original order. */
    private fun budget(all: List<Item>, budget: Int): List<Item> {
        if (all.size <= budget) return all
        fun tier(e: UiElement) = when {
            !e.meaningful -> 0
            !(e.clickable || e.editable || e.scrollable) -> 1
            else -> 2
        }
        val drop = HashSet<Int>()
        var need = all.size - budget.coerceAtLeast(0)
        for (t in 0..2) {
            for (i in all.indices.reversed()) {
                if (need == 0) break
                if (tier(all[i].element) == t) { drop += i; need-- }
            }
        }
        return all.filterIndexed { i, _ -> i !in drop }
    }

    private fun cut(s: String): String {
        val c = s.replace(Regex("\\s+"), " ").trim()
        return if (c.length > NAME_CHARS) c.substring(0, NAME_CHARS) else c
    }

    private data class Rect(val l: Double, val t: Double, val r: Double, val b: Double) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
        fun intersect(o: Rect): Rect? {
            val nl = max(l, o.l); val nt = max(t, o.t); val nr = min(r, o.r); val nb = min(b, o.b)
            return if (nr > nl && nb > nt) Rect(nl, nt, nr, nb) else null
        }
        fun contains(x: Double, y: Double) = x >= l && x <= r && y >= t && y <= b
        private fun area() = max(0.0, r - l) * max(0.0, b - t)
        fun iou(o: Rect): Double {
            val i = intersect(o)?.area() ?: return 0.0
            val u = area() + o.area() - i
            return if (u <= 0) 0.0 else i / u
        }
    }

    // Converts the normalized 0–999 back to view pixels; the error is under one pixel
    private fun rectOf(e: UiElement, w: Int, h: Int): Rect {
        fun dx(v: Int) = v * (w - 1).coerceAtLeast(1) / 999.0
        fun dy(v: Int) = v * (h - 1).coerceAtLeast(1) / 999.0
        return Rect(dx(e.bounds[0]), dy(e.bounds[1]), dx(e.bounds[2]), dy(e.bounds[3]))
    }
}
