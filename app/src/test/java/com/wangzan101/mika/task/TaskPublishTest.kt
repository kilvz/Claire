package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminal states reported by the loop itself are not published: terminal states are written only by TaskRun.end, after the run id for this run has been recorded,
 * otherwise the UI receives the end first with an empty run id, and the card has no "View process".
 */
class TaskPublishTest {
    @Test fun loopTerminalStatesAreNotPublished() {
        assertFalse(TaskService.loopMayPublish(TaskState.Succeeded("ok")))
        assertFalse(TaskService.loopMayPublish(TaskState.Failed("x", FailKind.GaveUp)))
        assertFalse(TaskService.loopMayPublish(TaskState.Cancelled))
        assertFalse(TaskService.loopMayPublish(TaskState.Idle))
        assertTrue(TaskService.loopMayPublish(TaskState.Running(1, "看屏幕", "phone_observe")))
        assertTrue(TaskService.loopMayPublish(TaskState.WaitingInput("哪个?", null)))
    }
}
