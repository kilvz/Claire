package com.wangzan101.mika.voicecall

import com.wangzan101.mika.core.agent.Turn as ChatTurn
import com.wangzan101.mika.core.frontdesk.DeskTaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCallTaskLinkTest {
    private val saved = listOf(
        ChatTurn("开设置", "开好了", "succeeded", runId = "run-1"),
        ChatTurn("再看下蓝牙", "看了", "succeeded", runId = "run-2"),
        ChatTurn("谢谢", "", "desk"),
    )

    @Test fun `an in-progress task opens the run that is currently running`() {
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.Running, "run-3") { saved })
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.NeedsYou, "run-3") { saved })
    }

    @Test fun `in progress but not started yet does not impersonate the previous run`() {
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Running, null) { saved })
    }

    @Test fun `a queued task that has not started falls back to the conversation`() {
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Queued, "run-3") { saved })
    }

    @Test fun `a finished task opens the latest run in the conversation`() {
        assertEquals("run-2", VoiceCallTaskLink.runFor(DeskTaskPhase.Done, null) { saved })
        assertEquals("run-2", VoiceCallTaskLink.runFor(DeskTaskPhase.Failed, null) { saved })
        assertEquals("run-3", VoiceCallTaskLink.runFor(DeskTaskPhase.Cancelled, "run-3") { error("the running one still exists, no need to read storage") })
        assertNull(VoiceCallTaskLink.runFor(DeskTaskPhase.Done, null) { emptyList() })
    }
}
