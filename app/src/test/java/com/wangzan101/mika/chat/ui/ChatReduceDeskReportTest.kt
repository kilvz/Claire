package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.DeskTurn
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.frontdesk.DeskNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rounds the front desk writes into the conversation, read back: a front-desk exchange carries the tasks it dispatched, and a report is a normal reply, not a failure. */
class ChatReduceDeskReportTest {
    @Test fun deskReportRowRendersAsAReply() {
        val exchange = ChatReduce.fromTurn(Turn(userText = "", outcome = "订票：办好了：明早 8 点", status = DeskNotifier.STATUS))
        val status = exchange.status
        assertTrue("a desk_report must not render as a failure: $status", status is ExchangeStatus.Done)
        assertEquals("订票：办好了：明早 8 点", (status as ExchangeStatus.Done).answer)
        assertEquals("", exchange.goal)
        // Drawn as one front-desk reply
        assertEquals("订票：办好了：明早 8 点", exchange.deskReply)
    }

    @Test fun deskTurnRestoresBubbleReplyAndCards() {
        val ex = ChatReduce.fromTurn(Turn("帮我订日料,再查汇率", "好,两件都交给后面了。", DeskTurn.STATUS, runId = "desk:1",
            deskTasks = listOf("c1", "c2")))
        assertEquals("帮我订日料,再查汇率", ex.goal)
        assertEquals("好,两件都交给后面了。", ex.deskReply)
        assertEquals(listOf("c1", "c2"), ex.deskTasks)
        assertTrue(ex.status is ExchangeStatus.Done)
        assertEquals("desk:1", ex.runId)
        assertEquals(null, ex.recordedRun)
    }

    @Test fun restoredDeskRowsKeepOrderAndNoEmptyDoneBubble() {
        val turns = listOf(
            Turn("帮我订日料", "好,我让后面去订。", DeskTurn.STATUS, runId = "desk:1", deskTasks = listOf("c1")),
            // Only dispatched, with no front-desk words (common in calls): just the user bubble, no empty "done"
            Turn("顺便查汇率", "", DeskTurn.STATUS, deskTasks = listOf("c2")),
            Turn("", "订日料:办好了", DeskTurn.REPORT_STATUS, deskTasks = listOf("c1")),
            Turn("谢谢", "不客气。", DeskTurn.STATUS, runId = "desk:3"),
        )
        val exchanges = turns.map(ChatReduce::fromTurn)
        assertEquals(listOf("好,我让后面去订。", "", "订日料:办好了", "不客气。"), exchanges.map { it.deskReply })
        assertTrue(exchanges.all { it.status is ExchangeStatus.Done })
        assertTrue(deskReplyShown(exchanges[0]))
        assertTrue(!deskReplyShown(exchanges[1]))
        // The report row points at the item that was dispatched, so it is not a new dispatch ("the work in this conversation" is not listed twice); no task cards are embedded in the conversation
        assertEquals(emptyList<String>(), exchanges[2].deskCards)
        assertEquals("c1", exchanges[2].deskRef)
        assertEquals(
            listOf("u0", "r0", "u1", "r1", "r2", "u3", "r3"),
            chatItemKeys(exchanges, kept = null, phoneSetupRow = null, hasBlocked = false),
        )
    }
}
