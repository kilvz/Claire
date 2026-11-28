package com.wangzan101.mika.cap.phone.shell

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One control seen during read-only screen inspection; on a device it wraps AccessibilityNodeInfo, in tests it is replaced with a fake. */
internal interface InspectNode {
    val packageName: CharSequence?
    val className: CharSequence?
    val resourceId: String?
    val text: CharSequence?
    val description: CharSequence?
    val isPassword: Boolean
    /** left, top, right, bottom (screen coordinates). */
    val bounds: IntArray
    val isClickable: Boolean
    val isEditable: Boolean
    val isEnabled: Boolean
    val isVisible: Boolean
    val childCount: Int
    fun child(index: Int): InspectNode?
    /** Hand back when done (old systems must recycle the node). */
    fun release()
}

/**
 * Limits for one screen inspection: total controls, tree depth, length of each text segment, and the approximate character count of the whole output ([maxOutput],
 * each control counted as [ScreenInspectTree.NODE_OVERHEAD] characters of field overhead plus its text segments). Whichever limit is exceeded is recorded in [truncatedBy].
 */
internal class InspectBudget(
    val maxNodes: Int = ScreenInspectTree.MAX_NODES,
    val maxDepth: Int = ScreenInspectTree.MAX_DEPTH,
    val maxText: Int = ScreenInspectTree.MAX_TEXT,
    val maxOutput: Long = ScreenInspectTree.MAX_OUTPUT,
) {
    var nodes = 0
    /** Approximate number of characters already written. */
    var output = 0L
    /** Which limits truncated the output: `nodes`, `depth`, `text`, `output` (in that order). */
    val truncatedBy = LinkedHashSet<String>()
    val truncated: Boolean get() = truncatedBy.isNotEmpty()

    fun cut(reason: String) { truncatedBy += reason }

    /** Records it and returns true when [chars] more characters still fit within the total limit; otherwise records the truncation. */
    fun charge(chars: Long): Boolean {
        if (output + chars > maxOutput) {
            cut(ScreenInspectTree.CUT_OUTPUT)
            return false
        }
        output += chars
        return true
    }
}

/**
 * Writes the control tree as JSON. Password fields (and all their descendants) write no text or description, only `password: true`;
 * parts beyond [InspectBudget] are not read (not even child nodes are fetched), only the reason for truncation is recorded.
 */
internal object ScreenInspectTree {
    const val MAX_NODES = 2_500
    const val MAX_DEPTH = 60
    const val MAX_TEXT = 1_000
    /** Character limit for the whole output; the CLI accepts at most 4 MiB, leaving headroom at up to 3 bytes per character in UTF-8. */
    const val MAX_OUTPUT = 1_000_000L
    /** Approximate number of characters a control's non-text fields (key names, booleans, coordinates, brackets) take. */
    const val NODE_OVERHEAD = 220L
    const val CUT_NODES = "nodes"
    const val CUT_DEPTH = "depth"
    const val CUT_TEXT = "text"
    const val CUT_OUTPUT = "output"

    /** [node] is handed back here; returns null when the total count, depth or output limit is exceeded. */
    fun node(node: InspectNode, budget: InspectBudget, depth: Int = 0, inSecret: Boolean = false): JsonObject? {
        try {
            if (budget.nodes >= budget.maxNodes) { budget.cut(CUT_NODES); return null }
            if (depth >= budget.maxDepth) { budget.cut(CUT_DEPTH); return null }
            val secret = inSecret || node.isPassword
            val fields = listOf(
                "package" to plain(node.packageName, budget),
                "class" to plain(node.className, budget),
                "resource_id" to plain(node.resourceId, budget),
                "text" to if (secret) JsonNull else plain(node.text, budget),
                "description" to if (secret) JsonNull else plain(node.description, budget),
            )
            val chars = NODE_OVERHEAD + fields.sumOf { (_, v) -> if (v is JsonNull) 0L else v.content.length.toLong() }
            if (!budget.charge(chars)) return null
            budget.nodes++
            val children = buildJsonArray {
                val count = node.childCount
                for (i in 0 until count) {
                    if (budget.nodes >= budget.maxNodes) { budget.cut(CUT_NODES); break }
                    if (depth + 1 >= budget.maxDepth) { budget.cut(CUT_DEPTH); break }
                    if (budget.output + NODE_OVERHEAD > budget.maxOutput) { budget.cut(CUT_OUTPUT); break }
                    val child = node.child(i) ?: continue
                    node(child, budget, depth + 1, secret)?.let { add(it) }
                }
            }
            return buildJsonObject {
                for ((k, v) in fields) put(k, v)
                put("password", secret)
                put("bounds", bounds(node.bounds))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("enabled", node.isEnabled)
                put("visible", node.isVisible)
                put("children", children)
            }
        } finally {
            node.release()
        }
    }

    fun plain(value: CharSequence?, budget: InspectBudget): JsonPrimitive {
        if (value == null) return JsonNull
        val s = value.toString()
        if (s.length <= budget.maxText) return JsonPrimitive(s)
        budget.cut(CUT_TEXT)
        // Do not split a character's surrogate pair in half
        val end = if (s[budget.maxText - 1].isHighSurrogate()) budget.maxText - 1 else budget.maxText
        return JsonPrimitive(s.substring(0, end))
    }

    /** Window read order: focused first, then active, the rest by display and in the order the system gives; the budget goes to the earlier ones first. */
    fun <W> windowOrder(windows: List<W>, focused: (W) -> Boolean, active: (W) -> Boolean): List<W> =
        windows.withIndex().sortedWith(
            compareBy<IndexedValue<W>>({ if (focused(it.value)) 0 else 1 }, { if (active(it.value)) 0 else 1 }, { it.index }),
        ).map { it.value }

    fun bounds(b: IntArray): JsonArray = buildJsonArray { for (v in b.take(4)) add(JsonPrimitive(v)) }
}
