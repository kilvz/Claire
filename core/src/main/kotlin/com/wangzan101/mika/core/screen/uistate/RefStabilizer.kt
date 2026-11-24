package com.wangzan101.mika.core.screen.uistate

import kotlin.math.hypot
import kotlin.math.min

/**
 * One screen as shown to the model: the refs in [elements] have been replaced with model refs, [snapshotId] is this
 * screen's prefix plus a sequence number ("p1", "w2"…); [port] is the snapshot exactly as the port returned it,
 * [toPort] maps model refs → port refs.
 */
data class ModelSnapshot(
    val snapshotId: String,
    val port: ScreenSnapshot,
    val elements: List<UiElement>,
    val toPort: Map<String, String>,
) {
    /** The snapshot with model refs; used for irreversibility checks and coordinate conversion. */
    val screen: ScreenSnapshot by lazy {
        val refs = toPort.entries.associate { (model, original) -> original to model }
        port.copy(
            snapshotId = snapshotId,
            elements = elements,
            windows = port.windows.map { window -> window.copy(nodes = window.nodes.map { it.withRefs(refs) }) },
        )
    }
}

/** Why a ref could not be resolved; [wire] is written into the tool result's `cause`. */
enum class StaleCause(val wire: String) {
    /** The snapshotId was never issued for this screen, or it is too old to remember. */
    SnapshotUnknown("snapshot_unknown"),
    /** The ref came into existence after the screen that was given: a new ref paired with an old snapshotId. */
    RefNotInSnapshot("ref_not_in_snapshot"),
    /** The ref is not in the latest screen: the element is gone, or the ref is mistyped. */
    RefRetired("ref_retired"),
    /** The ref still exists, but the element has moved too far since the screen the model saw. */
    ElementMoved("element_moved"),
}

sealed interface Resolved {
    /** [relocated]: the model gave the snapshotId of an older screen, and the ref still points to the same element in the latest screen. */
    data class Current(val portRef: String, val portSnapshotId: String, val relocated: Boolean) : Resolved
    data class Stale(val cause: StaleCause) : Resolved
}

/** Maximum center shift allowed when reusing an old ref: this fraction of the screen's short side (the same limit as the port's relocation). */
const val STABLE_REF_MAX_SHIFT = RELOCATE_MAX_SHIFT

/** How many screens back an old snapshotId can still be translated; anything older is treated as expired. */
private const val KEEP_CENTERS = 20

/**
 * Stable element refs across screen reads. Port refs are only valid within a single screen read; this keeps the same
 * model ref for elements that did not change between two adjacent screens, translates it back to a port ref when
 * acting, and leaves expiry checks and relocation to the port as before.
 *
 * The conditions for reusing an old ref are no looser than the port's relocation ([relocate]): the key (role + name +
 * resource ID) is unique in both the old and the new screen, the foreground App has not changed, the previous named
 * element in traversal order (the context) is the same, or the page as a whole did not change and it is still in
 * place, and the center shift does not exceed [STABLE_REF_MAX_SHIFT] of the short side; elements in a list (inside a
 * scrollable container) additionally require that the page did not scroll and that they did not move.
 * Duplicate same-named elements in a list (a row of "Follow" buttons) always get a new ref: after scrolling, the same
 * position is already a different row. Elements with neither a name nor a resource ID cannot be recognized as the
 * same one and also get a new ref. Refs increase monotonically within a task, and retired ones are never reused.
 *
 * snapshotId is [prefix] plus a sequence number: each screen has a prefix, so a ref tells you which screen it is from.
 */
class RefStabilizer(private val prefix: String = "s") {
    private var nextRef = 1
    private var count = 0
    var latest: ModelSnapshot? = null
        private set
    /** The screen each ref in the latest screen first appeared in; a ref never reappears once it disappears from a screen, so it exists continuously over [born, latest screen]. */
    private var born = mapOf<String, Int>()
    /** The center of each model ref in the most recent [KEEP_CENTERS] screens; bounds cumulative shift when translating an old snapshotId. */
    private val centers = LinkedHashMap<Int, Map<String, Pair<Int, Int>>>()

    fun next(port: ScreenSnapshot): ModelSnapshot {
        val n = ++count
        val prev = latest?.takeIf { it.port.packageName == port.packageName }
        val shortSide = min(port.width, port.height)
        val before = prev?.let { locators(it.screen) }.orEmpty()
        val after = locators(port)
        val still by lazy { stationary(before, after, shortSide * SAME_SPOT_SHIFT) }
        val prevByKey = prev?.elements?.withIndex()?.groupBy { key(it.value) }.orEmpty()
        val newCount = port.elements.groupingBy(::key).eachCount()
        val toPort = LinkedHashMap<String, String>()
        val bornNow = HashMap<String, Int>()
        val elements = port.elements.mapIndexed { i, e ->
            val k = key(e)
            val old = prevByKey[k]?.singleOrNull()?.takeIf { (j, _) ->
                identifiable(e) && newCount[k] == 1 && relocate(before[j], before, after, shortSide) == Relocation.Found(i) &&
                    // List items are reused: only count it as the same one if the page did not scroll and it is still in place
                    (!(before[j].inList || after[i].inList) || (still && shift(before[j], after[i]) <= shortSide * SAME_SPOT_SHIFT))
            }?.value
            val ref = old?.ref ?: "@e${nextRef++}"
            bornNow[ref] = born[ref] ?: n
            toPort[ref] = e.ref
            e.copy(ref = ref)
        }
        born = bornNow
        centers[n] = elements.associate { it.ref to (it.centerX to it.centerY) }
        while (centers.size > KEEP_CENTERS) centers.remove(centers.keys.first())
        return ModelSnapshot("$prefix$n", port, elements, toPort).also { latest = it }
    }

    fun resolve(ref: String, snapshotId: String): Resolved {
        val cur = latest ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
        val n = snapshotId.removePrefix(prefix).takeIf { snapshotId.startsWith(prefix) }?.toIntOrNull()
            ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
        if (n < 1 || n > count) return Resolved.Stale(StaleCause.SnapshotUnknown)
        // For a screen too old even the refs it had are not kept: report snapshot_unknown first, and never misreport an unknown/retired ref as ref_retired.
        if (n != count && n !in centers) return Resolved.Stale(StaleCause.SnapshotUnknown)
        // The ref must really belong to that screen; do not let mistyped combinations through
        val from = born[ref] ?: return Resolved.Stale(StaleCause.RefRetired)
        if (from > n) return Resolved.Stale(StaleCause.RefNotInSnapshot)
        val portRef = cur.toPort[ref] ?: return Resolved.Stale(StaleCause.RefRetired)
        if (n != count) {
            // It may not have moved far between any two screens, yet the shifts can add up: measure from the screen the model saw
            val then = centers[n]?.get(ref) ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
            val now = centers[count]?.get(ref) ?: return Resolved.Stale(StaleCause.SnapshotUnknown)
            val limit = min(cur.port.width, cur.port.height) * STABLE_REF_MAX_SHIFT
            if (hypot((then.first - now.first).toDouble(), (then.second - now.second).toDouble()) > limit) return Resolved.Stale(StaleCause.ElementMoved)
        }
        return Resolved.Current(portRef, cur.port.snapshotId, relocated = n != count)
    }

    private fun identifiable(e: UiElement) = e.name.isNotBlank() || !e.resourceId.isNullOrBlank()

    private fun key(e: UiElement) = e.role + "\u0000" + e.name + "\u0000" + (e.resourceId ?: "")

    private fun shift(a: ElementLocator, b: ElementLocator) =
        hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

    /**
     * The same characteristics the port uses when reading a screen: the context is the previous named element in
     * traversal order; an element whose center falls inside another scrollable element's bounds counts as being in a
     * list (the port snapshot has no parent-child relations, so it is inferred from bounds).
     */
    private fun locators(s: ScreenSnapshot): List<ElementLocator> {
        val panes = s.elements.filter { it.scrollable && it.bounds.size == 4 }
        var last: String? = null
        return s.elements.map { e ->
            val x = normalize(e.centerX, s.width); val y = normalize(e.centerY, s.height)
            val inList = panes.any { p -> p !== e && x in p.bounds[0]..p.bounds[2] && y in p.bounds[1]..p.bounds[3] }
            ElementLocator(e.role, e.name, e.resourceId, e.centerX, e.centerY, last, e.hint, inList)
                .also { if (e.name.isNotBlank()) last = e.name }
        }
    }
}
