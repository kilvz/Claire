package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Structured queries over the phone UI and cross-screen text merging. Tools and subsequent local calls share this same semantics. */
internal object PhoneAutomationEngine {
    data class Query(val text: String? = null, val resourceId: String? = null, val role: String? = null)

    fun find(screen: ScreenSnapshot, query: Query, limit: Int): JsonObject {
        val matches = mutableListOf<JsonObject>()
        var total = 0
        fun visit(node: UiTreeNode, parents: List<String>, clickableParent: String?, editableParent: String?) {
            val clickRef = if (node.clickable) node.ref ?: clickableParent else clickableParent
            val editRef = if (node.editable) node.ref ?: editableParent else editableParent
            val own = listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.hintText).filter(String::isNotBlank).joinToString(" ")
            // Text matching only looks at the node's own text (leaf text nodes match, actionRef points at the clickable ancestor); only when resourceId/role match a container do descendants' text fill in name.
            val hit = (query.text == null || own.contains(query.text, ignoreCase = true)) &&
                (query.resourceId == null || node.resourceId?.contains(query.resourceId, ignoreCase = true) == true) &&
                (query.role == null || node.role.contains(query.role, ignoreCase = true))
            if (hit) {
                // Accessible name: the container being tapped (tile/list item) often holds the visible text on child nodes with its own text/description/hintText empty; consistent with the element table, take the name from descendants.
                val name = own.ifBlank { if (node.clickable) descendantLabel(node) else "" }
                total++
                if (matches.size < limit) matches += buildJsonObject {
                    node.ref?.let { put("ref", it) }
                    clickRef?.let { put("actionRef", it) }
                    editRef?.let { put("editRef", it) }
                    put("role", node.role)
                    if (name.isNotBlank() && !node.secret) put("name", name)
                    node.resourceId?.let { put("resourceId", it) }
                    put("bounds", JsonArray(node.bounds.map(::JsonPrimitive)))
                    put("parents", JsonArray(parents.map(::JsonPrimitive)))
                    if (node.clickable) put("clickable", true)
                    if (node.editable) put("editable", true)
                    if (node.scrollable) put("scrollable", true)
                    if (!node.enabled) put("enabled", false)
                }
            }
            val next = (parents + listOfNotNull(node.text.takeUnless { node.secret }, node.description, node.resourceId)
                .firstOrNull { it.isNotBlank() }?.take(100).orEmpty()).filter(String::isNotBlank).takeLast(8)
            node.children.forEach { visit(it, next, clickRef, editRef) }
        }
        screen.windows.forEach { window -> window.nodes.forEach { visit(it, listOfNotNull(window.title, window.packageName), null, null) } }
        if (screen.windows.isEmpty()) {
            screen.elements.forEach { e ->
                if ((query.text == null || (!e.secret && e.name.contains(query.text, true))) &&
                    (query.resourceId == null || e.resourceId?.contains(query.resourceId, true) == true) &&
                    (query.role == null || e.role.contains(query.role, true))) {
                    total++
                    if (matches.size < limit) matches += buildJsonObject {
                        put("ref", e.ref); put("role", e.role)
                        if (e.clickable) put("actionRef", e.ref)
                        if (e.editable) put("editRef", e.ref)
                        if (!e.secret) put("name", e.name)
                        e.resourceId?.let { put("resourceId", it) }
                        put("bounds", JsonArray(e.bounds.map(::JsonPrimitive)))
                        put("parents", JsonArray(emptyList()))
                        if (e.clickable) put("clickable", true)
                        if (e.editable) put("editable", true)
                    }
                }
            }
        }
        return buildJsonObject {
            put("snapshotId", screen.snapshotId)
            put("currentApp", screen.packageName ?: screen.unreadableApp)
            put("total", total)
            put("truncated", total > matches.size)
            put("matches", JsonArray(matches))
        }
    }

    /** When a node has no readable text of its own, use its descendants' text as its name (the same rule the element table uses to name clickable containers). */
    private fun descendantLabel(node: UiTreeNode): String {
        val parts = mutableListOf<String>()
        fun collect(n: UiTreeNode) {
            listOfNotNull(n.text?.takeUnless { n.secret }, n.description?.takeUnless { n.secret })
                .filter(String::isNotBlank)
                .forEach { if (it !in parts) parts += it }
            n.children.forEach(::collect)
        }
        node.children.forEach(::collect)
        return parts.joinToString(" ")
    }

    /** Overlapping regions are merged in whole-run order, so two independent messages with identical text can still be kept. */
    fun appendPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> accumulated.takeLast(n) == page.take(n) } ?: 0
        return accumulated + page.drop(overlap)
    }

    fun prependPage(accumulated: List<String>, page: List<String>): List<String> {
        if (page.isEmpty()) return accumulated
        val overlap = (minOf(accumulated.size, page.size) downTo 1).firstOrNull { n -> page.takeLast(n) == accumulated.take(n) } ?: 0
        return page.dropLast(overlap) + accumulated
    }
}
