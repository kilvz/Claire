package com.wangzan101.mika.core.learn.memory

import com.wangzan101.mika.core.model.TokenEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full memory injection (the product default: include every item when it all fits, fall back to retrieval only when it does not).
 */
class MemoryContextFullModeTest {
    @Test fun fullGivesEveryActiveItem() {
        // Lexically unrelated to the goal; retrieval used to drop these
        val items = (1..12).map { mem("无关事实第${it}条:用户周末去爬山") }
        val block = MemoryContext.build(items, goal = "帮我点个外卖", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertEquals(items.map { it.id }.toSet(), block.usedIds.toSet())
        assertEquals(12, block.usedIds.size)
    }

    @Test fun autoGivesEveryItemWhenItFits() {
        val items = (1..30).map { mem("事实第${it}条") }
        val block = MemoryContext.build(items, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertEquals(30, block.usedIds.size)
    }

    @Test fun autoFallsBackToRelevantWhenTooBig() {
        val filler = "很".repeat(120)
        // Only the first 8 entries are about takeaway: falling back to retrieval should pick them out, not return nothing because every entry carries the same term
        val items = (1..120).map { mem("第${it}条$filler", keywords = if (it <= 8) listOf("外卖") else emptyList()) }
        val block = MemoryContext.build(items, goal = "点外卖", lastUserTurn = null, mode = MemoryContext.Mode.Auto)
        assertTrue(block.usedIds.size in 1..MemoryContext.MAX_RELEVANT)
        val message = block.message
        assertNotNull(message)
        assertTrue(TokenEstimate.of(message!!) <= MemoryContext.MAX_TOKENS)
    }

    @Test fun fullDropsOldestFactsBeforePinned() {
        val filler = "很".repeat(120)
        val pinned = mem("置顶的住址在张江$filler", pinned = true)
        val facts = (1..80).map { mem("第${it}条$filler") }
        val block = MemoryContext.build(facts + pinned, goal = "随便", lastUserTurn = null, mode = MemoryContext.Mode.Full)
        assertTrue(pinned.id in block.usedIds)
        assertTrue(TokenEstimate.of(block.message!!) <= MemoryContext.FULL_MAX_TOKENS)
        assertTrue(block.usedIds.size < facts.size)
    }

    @Test fun pendingNeverInjected() {
        val pending = mem("还没批准的做法", kind = MemoryKind.procedure, status = MemoryStatus.pending)
        assertNull(MemoryContext.build(listOf(pending), "随便", null, mode = MemoryContext.Mode.Full).message)
    }

    // In auto mode procedures are all given when they fit: truncating the first few by update time may drop exactly the one needed this time
    @Test fun autoGivesEveryProcedureWhenItFits() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Auto)
        assertEquals(procs.map { it.id }.toSet(), block.usedIds.toSet())
    }

    @Test fun fullKeepsProceduresCapped() {
        val procs = (1..6).map { mem("做法$it", kind = MemoryKind.procedure) }
        val block = MemoryContext.build(procs, "随便", null, mode = MemoryContext.Mode.Full)
        assertEquals(MemoryContext.MAX_PROCEDURES, block.usedIds.size)
    }
}
