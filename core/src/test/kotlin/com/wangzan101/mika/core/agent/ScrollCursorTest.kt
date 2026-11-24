package com.wangzan101.mika.core.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q2 prototype validation: when scrolling to reveal more, contentKey stays stable and it stops correctly at the end;
 * ordinal depends on position and cannot serve as a cross-screen key.
 *
 * Simulates a scrollable list: `views` are the elements read screen by screen while scrolling (each scroll moves the
 * window down and reveals new items).
 */
class ScrollCursorTest {

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    private fun view(vararg names: String): List<JsonObject> =
        names.mapIndexed { i, n -> el("@e${i + 1}", "ViewGroup", n) }

    /** Drives one pass: the cursor decides act or scroll; returns the processing order, the final done set and the last step. */
    private fun simulate(views: List<List<JsonObject>>, role: String?, text: String?): Triple<List<String>, Set<String>, ScrollCursor.Step> {
        val cursor = ScrollCursor(role, text)
        val order = mutableListOf<String>()
        var idx = 0
        var justScrolled = false
        var final: ScrollCursor.Step = ScrollCursor.Step.Scroll
        repeat(1000) {
            if (idx >= views.size) return Triple(order, cursor.done.toSet(), ScrollCursor.Step.Scroll)
            when (val s = cursor.advance(views[idx], justScrolled)) {
                is ScrollCursor.Step.DoNext -> {
                    order += s.item.contentKey
                    cursor.finish(s.item)
                    justScrolled = false
                }
                ScrollCursor.Step.Scroll -> { idx++; justScrolled = true }
                ScrollCursor.Step.End -> { final = s; return Triple(order, cursor.done.toSet(), final) }
            }
        }
        return Triple(order, cursor.done.toSet(), final)
    }

    /** A list of 5 unique items: each is done once, it scrolls to the end, and stops exactly there. */
    @Test
    fun revealsAndStopsAtEnd() {
        val views = listOf(view("A", "B", "C"), view("B", "C", "D"), view("C", "D", "E"), view("D", "E"))
        val (order, done, final) = simulate(views, "ViewGroup", null)

        // Each item exactly once, in visible order A→E; the A/B/C that scrolled off screen are not redone
        assertEquals(listOf("ViewGroup|A", "ViewGroup|B", "ViewGroup|C", "ViewGroup|D", "ViewGroup|E"), order)
        assertEquals(5, done.size)
        assertTrue(final is ScrollCursor.Step.End)
    }

    /** Items with the same name (identical contentKey) collapse to a single action — element information is not enough to tell two same-named items apart across scrolling; this is a known boundary. */
    @Test
    fun identicalContentCollapsesToOnce() {
        val cursor = ScrollCursor("ViewGroup", null)
        val two = listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"))

        val s1 = cursor.advance(two, false)
        assertTrue(s1 is ScrollCursor.Step.DoNext)
        cursor.finish((s1 as ScrollCursor.Step.DoNext).item)

        // The second same-named item has a contentKey already in done, so it is skipped as "already done"; a real fix needs finer item matching (making contentKey unique)
        val s2 = cursor.advance(two, false)
        assertTrue(s2 is ScrollCursor.Step.Scroll)
        assertEquals(setOf("ViewGroup|运营"), cursor.done)
    }

    /** ordinal is position-dependent: after one same-named item scrolls out, the remaining one's ordinal goes from 1 to 0, so it cannot be a cross-screen key. */
    @Test
    fun ordinalIsNotStableAcrossScroll() {
        val cursor = EachCursor()
        val first = cursor.match(listOf(el("@e1", "ViewGroup", "运营"), el("@e2", "ViewGroup", "运营"), el("@e3", "ViewGroup", "C")), "ViewGroup", null)
        assertEquals(listOf(0, 1, 0), first.map { it.ordinal })

        // After scrolling: the first of the two same-named items scrolls off screen, and the remaining one's ordinal becomes 0 (its position changed, so the key changed with it)
        val after = cursor.match(listOf(el("@e9", "ViewGroup", "运营"), el("@e4", "ViewGroup", "C")), "ViewGroup", null)
        assertEquals(listOf(0, 0), after.map { it.ordinal })
        // But contentKey is unchanged — that is the cross-screen identity
        assertEquals(listOf("ViewGroup|运营", "ViewGroup|C"), after.map { it.contentKey })
    }
}
