package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** When the diff text exceeds this fraction of the full snapshot, send the full one: the diff saves little and is harder to read. */
private const val MAX_DIFF_RATIO = 0.5
private const val DEFAULT_MAX_ELEMENTS = 200

/**
 * The difference between two screens (aligned by stable ref), for a write result's `screen` field:
 * `{"snapshotId","base","currentApp","full":false,"added":[elements],"removed":[{ref,name,role}],"changed":[element+"was"],"unchanged":N}`.
 * A full snapshot is sent when there is no base, [baseVisible] is false (the model can no longer see the screen the
 * diff depends on), the foreground app changed, or the diff text exceeds half of the full snapshot:
 * `{"snapshotId","currentApp","full":true,"elements":[…],"truncated"}`.
 * The diff is computed from the two screens' complete element lists; the output has at most [maxElements] added and
 * changed entries combined, with anything beyond that marked truncated.
 * When a phone snapshot has a window tree, full results carry tree and diff results carry treeChanges; the caller
 * passes the complete element count for the phone.
 */
object InterfaceUpdate {
    fun between(base: ModelSnapshot?, current: ModelSnapshot, baseVisible: Boolean, maxElements: Int = DEFAULT_MAX_ELEMENTS): JsonObject {
        val (all, _) = ScreenJson.elements(current.screen, Int.MAX_VALUE)
        fun full() = buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", true)
            flags(current)
            put("elements", JsonArray(all.take(maxElements)))
            put("truncated", all.size > maxElements)
            if (current.screen.windows.isNotEmpty()) put("tree", UiTreeJson.windows(current.screen))
        }
        if (base == null || !baseVisible || base.port.packageName != current.port.packageName) return full()
        if (current.screen.windows.isNotEmpty() && !UiTreeJson.sameWindows(base.screen, current.screen)) return full()
        val before = ScreenJson.elements(base.screen, Int.MAX_VALUE).first.associateBy(::ref)
        val now = all.associateBy(::ref)
        val added = all.filter { ref(it) !in before }
        val removed = before.filter { it.key !in now }.values.map { e ->
            val o = e as JsonObject
            buildJsonObject { put("ref", ref(e)); o["name"]?.let { put("name", it) }; o["role"]?.let { put("role", it) } }
        }
        val changed = all.mapNotNull { e ->
            val old = before[ref(e)] ?: return@mapNotNull null
            if (old == e) return@mapNotNull null
            val was = (old as JsonObject)["name"]
            if (was != (e as JsonObject)["name"]) JsonObject(e + ("was" to (was ?: JsonPrimitive("")))) else e
        }
        val diffChars = JsonArray(added).toString().length + JsonArray(removed).toString().length + JsonArray(changed).toString().length
        if (diffChars > all.toString().length * MAX_DIFF_RATIO) return full()
        val treeChanges = if (current.screen.windows.isEmpty()) null else UiTreeJson.changes(base.screen, current.screen)
        if (treeChanges != null && treeChanges.toString().length > UiTreeJson.windows(current.screen).toString().length * MAX_DIFF_RATIO) return full()
        val shownAdded = added.take(maxElements)
        val shownChanged = changed.take((maxElements - shownAdded.size).coerceAtLeast(0))
        return buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("base", base.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", false)
            flags(current)
            put("added", JsonArray(shownAdded))
            put("removed", JsonArray(removed))
            put("changed", JsonArray(shownChanged))
            put("unchanged", all.size - added.size - changed.size)
            if (shownAdded.size + shownChanged.size < added.size + changed.size) put("truncated", true)
            treeChanges?.let { put("treeChanges", it) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.flags(s: ModelSnapshot) {
        if (s.port.changedDuringRead) put("screenChangedDuringRead", true)
    }

    private fun ref(e: JsonElement) = ((e as JsonObject)["ref"] as JsonPrimitive).content
}
