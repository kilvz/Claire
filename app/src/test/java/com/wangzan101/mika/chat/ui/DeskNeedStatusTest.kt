package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.core.agent.GateReason
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.task.TaskGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The main chat's question card draws buttons from the running task's current state: takeover and "do something first" are drawn, while queued, locked-screen and running are not. */
class DeskNeedStatusTest {
    @Test fun takeoverKeepsReasonMessageAndPaymentLines() {
        val status = ChatReduce.needStatus(TaskState.WaitingTakeover("payment", "确认付款", "电影票", "¥45", "万达影城"), { it })
        assertEquals(ExchangeStatus.NeedsTakeover("payment", "确认付款", "电影票", "¥45", "万达影城"), status)
    }

    @Test fun heldThatNeedsYouIsAPausedCard() {
        val status = ChatReduce.needStatus(TaskState.Held(GateReason("x", "先打开无障碍", resumable = true, needsYou = true)), { it })
        assertEquals(ExchangeStatus.Paused("先打开无障碍", true), status)
    }

    @Test fun queuedLockedAndRunningDrawNothing() {
        assertNull(ChatReduce.needStatus(TaskState.Held(GateReason(GateReason.QUEUED, "排第 2")), { it }))
        assertNull(ChatReduce.needStatus(TaskState.Held(TaskGate.LOCKED), { it }))
        assertNull(ChatReduce.needStatus(TaskState.Idle, { it }))
    }
}
