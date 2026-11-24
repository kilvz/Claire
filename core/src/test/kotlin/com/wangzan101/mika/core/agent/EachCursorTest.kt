package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Q1 prototype validation: whether a stable key (contentKey = role|name) can, across snapshots, "recognise the same item and skip ones already done".
 *
 * The element structure follows a real screen-read result (ref/role/name/center/bounds); only ref/role/name are read here — center/bounds
 * are for locating a real tap and do not take part in the dedup key. "Ref reshuffling" between snapshots is simulated as in reality (refs are reassigned on every screen read).
 */
class EachCursorTest {

    private val cursor = EachCursor()

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    /** The same list read twice: all refs are reshuffled, but contentKey stays consistent across snapshots. */
    @Test
    fun refsChangeAcrossSnapshotsButContentKeysStayStable() {
        val first = cursor.match(
            listOf(el("@e1", "ViewGroup", "Java工程师"), el("@e2", "ViewGroup", "产品经理"), el("@e3", "ViewGroup", "运营")),
            role = "ViewGroup", text = null,
        )
        val second = cursor.match(
            listOf(el("@e9", "ViewGroup", "Java工程师"), el("@e4", "ViewGroup", "产品经理"), el("@e7", "ViewGroup", "运营")),
            role = "ViewGroup", text = null,
        )
        // The refs changed (which is exactly why ref cannot be the key)
        assertEquals(listOf("@e1", "@e2", "@e3"), first.map { it.ref })
        assertEquals(listOf("@e9", "@e4", "@e7"), second.map { it.ref })
        // But the stable keys are consistent across snapshots
        assertEquals(first.map { it.contentKey }, second.map { it.contentKey })
        assertEquals(listOf("ViewGroup|Java工程师", "ViewGroup|产品经理", "ViewGroup|运营"), first.map { it.contentKey })
    }

    /** Items with the same name are distinguished by ordinal within one snapshot, with no duplicates and no omissions. */
    @Test
    fun identicalNamesGetOrdinalWithinSnapshot() {
        val items = cursor.match(
            listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"), el("@e3", "ViewGroup", "产品经理")),
            role = "ViewGroup", text = null,
        )
        // Same contentKey (meaning the contents are indistinguishable); ordinal is the only way to tell them apart on one screen
        assertEquals(listOf("ViewGroup|运营", "ViewGroup|运营", "ViewGroup|产品经理"), items.map { it.contentKey })
        assertEquals(listOf(0, 1, 0), items.map { it.ordinal })
    }

    /** Filters items by text: only those whose name contains the keyword match; the rest are ignored. */
    @Test
    fun matchFiltersByText() {
        val items = cursor.match(
            listOf(el("@e1", "Button", "打招呼"), el("@e2", "ViewGroup", "Java工程师 · 打招呼"), el("@e3", "Button", "打招呼")),
            role = "Button", text = "打招呼",
        )
        assertEquals(listOf("@e1", "@e3"), items.map { it.ref })
        assertEquals(listOf(0, 1), items.map { it.ordinal })
    }

    /** The tap must land on a clickable ancestor: actionRef takes precedence over the item's own ref (when a non-clickable text node matches, ref is the non-clickable one). */
    @Test
    fun actionRefPreferredOverOwnRef() {
        val items = cursor.match(
            listOf(buildJsonObject {
                put("ref", JsonPrimitive("@t1"))
                put("actionRef", JsonPrimitive("@e1"))
                put("role", JsonPrimitive("TextView"))
                put("name", JsonPrimitive("打招呼"))
            }),
            role = null, text = null,
        )
        assertEquals(listOf("@e1"), items.map { it.ref })
    }
}
