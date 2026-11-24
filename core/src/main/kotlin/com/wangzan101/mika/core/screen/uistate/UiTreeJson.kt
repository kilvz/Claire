package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The phone's window hierarchy as shown to the model; nodes keep only structure and state bits (role/ref/children/interaction state), while text, position and resourceId live in elements keyed by ref, avoiding duplicate serialization. */
object UiTreeJson {
    fun windows(s: ScreenSnapshot): JsonArray = buildJsonArray {
        for (w in s.windows) add(buildJsonObject {
            put("id", w.id)
            put("type", w.type)
            put("layer", w.layer)
            w.title?.let { put("title", it) }
            w.packageName?.let { put("packageName", it) }
            if (w.active) put("active", true)
            if (w.focused) put("focused", true)
            put("nodes", JsonArray(w.nodes.map(::node)))
        })
    }

    private fun node(n: UiTreeNode): JsonObject = JsonObject(shallow(n) +
        (if (n.children.isEmpty()) emptyMap() else mapOf("children" to JsonArray(n.children.map(::node)))))

    private fun shallow(n: UiTreeNode): JsonObject = buildJsonObject {
        put("role", n.role)
        n.ref?.let { put("ref", it) }
        if (n.clickable) put("clickable", true)
        if (n.editable) put("editable", true)
        if (n.scrollable) put("scrollable", true)
        if (n.checkable) put("checked", n.checked)
        if (n.selected) put("selected", true)
        if (!n.enabled) put("enabled", false)
        if (n.focused) put("focused", true)
        if (n.secret) put("secret", true)
        if (n.wheel) put("wheel", true)
    }

    /** Paths are keyed by window and child index; attribute changes at the same path send only that node, while newly added nodes send the full subtree. */
    fun changes(before: ScreenSnapshot, current: ScreenSnapshot): JsonObject {
        val old = index(before.windows)
        val now = index(current.windows)
        val updated = buildJsonArray {
            for ((path, n) in now) {
                val previous = old[path]
                if (previous == null && parent(path) in now && parent(path) !in old) continue // the new parent already carries the full subtree
                if (previous == null || shallow(previous) != shallow(n)) add(buildJsonObject {
                    put("path", path)
                    put("node", if (previous == null) node(n) else shallow(n))
                })
            }
        }
        val gone = old.keys.filterTo(LinkedHashSet()) { it !in now }
        val removed = buildJsonArray { for (path in gone) if (parent(path) !in gone) add(JsonPrimitive(path)) }
        return buildJsonObject {
            put("updated", updated)
            put("removed", removed)
        }
    }

    fun sameWindows(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        a.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) } ==
            b.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) }

    private fun index(windows: List<UiWindowTree>): LinkedHashMap<String, UiTreeNode> = LinkedHashMap<String, UiTreeNode>().also { out ->
        fun walk(prefix: String, nodes: List<UiTreeNode>) {
            nodes.forEachIndexed { i, node ->
                val path = "$prefix/$i"
                out[path] = node
                walk(path, node.children)
            }
        }
        windows.forEach { walk("w${it.id}", it.nodes) }
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")
}
