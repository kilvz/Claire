package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.task.SteerNote
import com.wangzan101.mika.task.SteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The front desk steers a subtask through a local steer ([com.wangzan101.mika.task.TaskRun.steer]), not remote append:
 * remote append requires the task to have an MCP external id, and subtasks dispatched by the front desk do not, so that path could never steer them.
 * A real TaskRun needs a running task service to build, which a JVM test cannot do; here acceptance is judged by where TaskRun.steer recorded it going.
 */
class DeskSteerTest {
    private class Steers(private val outcome: SteerState) {
        val notes = mutableListOf<SteerNote>()
        fun steer(text: String, id: String) { notes += SteerNote(id, text, outcome) }
    }

    @Test fun receivedByInboxIsAccepted() {
        val run = Steers(SteerState.Received)
        assertTrue(steerLocally("顺便看明天", run::steer) { run.notes })
        assertEquals("顺便看明天", run.notes.single().text)
    }

    @Test fun deferredToNextIsAccepted() {
        val run = Steers(SteerState.Next)
        assertTrue(steerLocally("顺便看明天", run::steer) { run.notes })
    }

    @Test fun cancelledBecauseStoppedIsRefused() {
        val run = Steers(SteerState.Cancelled)
        assertFalse(steerLocally("顺便看明天", run::steer) { run.notes })
    }

    @Test fun missingNoteIsRefused() {
        assertFalse(steerLocally("顺便看明天", { _, _ -> }) { emptyList() })
    }
}
