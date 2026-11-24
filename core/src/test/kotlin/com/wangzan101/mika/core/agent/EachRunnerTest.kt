package com.wangzan101.mika.core.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Q3+Q4 prototype validation: in-place looping and failure semantics.
 */
class EachRunnerTest {

    private fun el(ref: String, role: String, name: String): JsonObject = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("role", JsonPrimitive(role))
        put("name", JsonPrimitive(name))
    }

    /** Q3: an in-place loop (remove each item as it is done, so the list shifts up) makes no difference to the runner compared with "open → back", and contentKey dedups as usual. */
    @Test
    fun inPlaceRemovalDedupsWhenItemsShiftUp() = runBlocking {
        val list = mutableListOf("A", "B", "C")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Stop)
        val processed = mutableListOf<String>()

        val summary = runner.run(
            read = { list.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { /* an in-place loop has no scrolling */ },
            doItem = { item -> processed += item.name; list.remove(item.name); true },
        )

        assertEquals(listOf("A", "B", "C"), processed)  // Each item exactly once, even though the list shifts up item by item
        assertEquals(3, summary.done)
        assertEquals(0, summary.failed)
        assertNull(summary.stoppedAt)
    }

    /** Q4 Stop (safe default): stop at the first failure; the failed item is not counted as done (left to the caller to decide on retry), and later items are not touched. */
    @Test
    fun stopHaltsAtFirstFailure() = runBlocking {
        val items = listOf("A", "B", "C", "D", "E")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Stop)
        val summary = runner.run(
            read = { items.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { },
            doItem = { it.name != "B" },   // B fails
        )
        assertEquals(1, summary.done)                    // Only A
        assertEquals(1, summary.failed)                  // B
        assertEquals("ViewGroup|B", summary.stoppedAt)   // Stopped at B
        assertEquals(listOf("ViewGroup|A", "ViewGroup|B"), summary.perItem.map { it.contentKey })
    }

    /** Q4 Skip: a failure is still counted as done and later items continue; used for delivery-style work where "one failure must not block a screen". */
    @Test
    fun skipContinuesPastFailure() = runBlocking {
        val items = listOf("A", "B", "C", "D", "E")
        val runner = EachRunner("ViewGroup", null, EachRunner.OnError.Skip)
        val summary = runner.run(
            read = { items.mapIndexed { i, n -> el("@e$i", "ViewGroup", n) } },
            scroll = { },
            doItem = { it.name != "B" },
        )
        assertEquals(4, summary.done)                    // A, C, D, E
        assertEquals(1, summary.failed)                  // B
        assertNull(summary.stoppedAt)                    // Ran to completion, never stopped
        assertEquals(5, summary.perItem.size)
        assertEquals(
            listOf(true, false, true, true, true),
            summary.perItem.map { it.ok },
        )
    }
}
