package com.wangzan101.mika.personal

import com.wangzan101.mika.core.learn.memory.MemoryContext
import com.wangzan101.mika.core.learn.memory.MemoryItem
import com.wangzan101.mika.core.learn.memory.MemoryKind
import com.wangzan101.mika.core.learn.memory.MemorySource
import com.wangzan101.mika.core.learn.memory.MemoryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryTextTest {
    private fun item(i: Int, content: String, type: String = "user_said", status: MemoryStatus = MemoryStatus.active, pinned: Boolean = false) = MemoryItem(
        id = "m$i", kind = MemoryKind.fact, content = content, keywords = emptyList(),
        source = MemorySource(type, atMs = 0L), status = status, pinned = pinned, createdAt = 0L, updatedAt = i.toLong(),
    )

    // Pinned first, then newest first; unapproved items are left out; an entry Claire inferred itself carries the mark at the very front
    @Test fun ordersItemsAndMarksInferredOnesUpFront() {
        val text = personalMemoryText(listOf(
            item(1, "用户喜欢喝茶"),
            item(2, "用户常在张江一带活动", type = MemoryContext.INFERRED),
            item(3, "置顶的事", pinned = true),
            item(4, "还没批准的做法", status = MemoryStatus.pending),
        ))
        assertEquals(listOf("置顶的事", PERSONAL_INFERRED_MARK + "用户常在张江一带活动", "用户喜欢喝茶"), text.lines())
    }

    // Filling up to the limit counts whole items: each item is either included in full (inferred ones together with their mark) or left out entirely, never cut in half and losing the "not what the user said" distinction
    @Test fun neverCutsAnItemOrItsMarkAtTheLimit() {
        val body = "字".repeat(200)
        // After 32 items of 200 characters each, an inferred item is exactly next: the old approach truncated the whole block to 6500 characters, leaving only the first 68 characters of this item with its mark cut off at the end
        val items = (1..32).map { item(100 - it, body) } + item(1, "归".repeat(200), type = MemoryContext.INFERRED) + (1..7).map { item(-it, body) }
        val text = personalMemoryText(items)
        assertTrue(text.length <= 6500)
        val lines = text.lines()
        assertTrue(lines.all { it == body || it == PERSONAL_INFERRED_MARK + "归".repeat(200) })
        assertFalse("the inferred entry is left out whole when it does not fit", lines.any { it.startsWith("归") })
        // Widen the limit a little and the whole item is included, with the mark in front
        assertTrue(personalMemoryText(items, max = 7000).lines().any { it == PERSONAL_INFERRED_MARK + "归".repeat(200) })
    }
}
