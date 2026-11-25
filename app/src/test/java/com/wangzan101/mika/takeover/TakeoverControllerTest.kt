package com.wangzan101.mika.takeover

import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.TargetApp
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.restorePipSound
import com.wangzan101.mika.task.restoreTargetApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverControllerTest {
    /** The virtual display is display 9, the main display is 0. Task 1 is the App opened by phone_launch_app, task 2 is the login page it opened separately. */
    internal class FakePort : TakeoverPort {
        val log = mutableListOf<String>()
        val displays = mutableMapOf(1 to AGENT)
        val packages = mutableMapOf(1 to "a.app")
        private val targetApp = MutableStateFlow<TargetApp?>(TargetApp("a.app", 1))
        var target: Int
            get() = targetApp.value?.taskId ?: -1
            set(value) { targetApp.value = TargetApp("a.app", value).takeIf { value >= 0 } }
        var moveToMainOk = true
        var moveBackOk = true
        override suspend fun agentTopTask() = displays.filterValues { it == AGENT }.keys.maxOrNull() ?: -1
        override suspend fun targetTask() = target
        override fun targetPackage() = if (target >= 0) "a.app" else null
        override suspend fun packageOf(taskId: Int) = packages[taskId]
        override suspend fun displayOf(taskId: Int) = displays[taskId] ?: -1
        override suspend fun moveToMain(taskId: Int): Boolean {
            log += "toMain:$taskId"
            if (moveToMainOk) displays[taskId] = 0
            return moveToMainOk
        }
        override suspend fun moveBack(taskId: Int): Boolean {
            log += "back:$taskId"
            if (moveBackOk) displays[taskId] = AGENT
            return moveBackOk
        }
        private val shell = object : IMikaShell.Default() {
            override fun displayOfTask(taskId: Int) = displays[taskId] ?: -1

            override fun findTaskForPackage(packageName: String, userId: Int, displayId: Int): IntArray {
                val task = displays.entries.firstOrNull { packages[it.key] == packageName }
                return if (task == null) intArrayOf() else intArrayOf(task.key, task.value)
            }
        }
        override suspend fun restoreTarget() =
            restoreTargetApp(targetApp, shell, AGENT, fromMainOnly = true, moveBack = ::moveBack) {
                log += "reopen:${it.packageName}"
                ActionResult.Failed("app_not_found")
            }
        override fun setSound(packageName: String, on: Boolean) { log += (if (on) "unmute:" else "mute:") + packageName }
        override fun unmute(packageName: String) { log += "pip-unmute:$packageName" }
        val results = mutableListOf<HandBack>()
        var watching: String? = null
        var activity = HandoverActivity(0, null)
        override fun watchInput(packageName: String) { watching = packageName }
        override suspend fun handoverActivity() = activity.takeIf { watching != null }
        override fun stopWatching() { watching = null }
        override suspend fun resume(result: HandBack) {
            results += result
            log += "resume:${result.outcome}" + if (result.userFinished) "/finished" else ""
        }
        override fun cancel() { log += "cancel" }
    }

    internal companion object { const val AGENT = 9 }

    private val port = FakePort()
    private lateinit var takeover: Takeover

    /** Each case builds its takeover on its own test scheduler, and timing uses virtual time. */
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        takeover = Takeover(port, backgroundScope) { testScheduler.currentTime }
        body()
    }

    private val waiting = TaskState.WaitingTakeover("captcha", "请拖动滑块")

    @Test
    fun handOverMovesToMainAndRestoresSound() = test {
        takeover.onState(waiting)
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertTrue(takeover.handedOver)
    }

    @Test
    fun handOverMovesTheTopTaskAndItsPackage() = test {
        // The login page was opened by another App in a new task and sits on top
        port.displays[2] = AGENT
        port.packages[2] = "login.app"
        takeover.onState(waiting)
        takeover.handOver()
        assertEquals(listOf("toMain:2", "unmute:login.app"), port.log)
        port.log.clear()
        takeover.handBack()
        assertEquals(listOf("back:2", "mute:login.app", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun handBackMovesBackRemutesThenResumes() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handedOver)
    }

    @Test
    fun handBackMovesReplacementTargetAndUpdatesItsId() = test {
        takeover.onState(waiting)
        assertTrue(takeover.handOver())
        port.displays.remove(1)
        port.packages.remove(1)
        port.displays[3] = 0
        port.packages[3] = "a.app"
        port.log.clear()

        assertTrue(takeover.handBack())

        assertEquals(AGENT, port.displays[3])
        assertEquals(3, port.target)
        assertEquals(listOf("back:3", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun handBackResumesWithRestoreFailureWhenTargetCannotBeReopened() = test {
        takeover.onState(waiting)
        assertTrue(takeover.handOver())
        port.displays.remove(1)
        port.log.clear()

        assertTrue(takeover.handBack())

        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertNull(takeover.problem.value)
        assertTrue(port.results.single().appReturnFailure.orEmpty().contains("a.app"))
        assertEquals(listOf("reopen:a.app", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun moveBackOfVanishedTaskStillResumes() = test {
        port.displays[2] = AGENT
        port.packages[2] = "a.app"
        takeover.onState(waiting)
        takeover.handOver()
        // The user finished the payment page on the main screen and the page closed itself: task 2 is gone
        port.displays.remove(2)
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("mute:a.app", "resume:complete"), port.log)
        assertNull(takeover.problem.value)
    }

    @Test
    fun originalTaskLeftOnMainScreenIsMovedBackToo() = test {
        port.displays[2] = AGENT
        port.packages[2] = "a.app"
        takeover.onState(waiting)
        takeover.handOver()
        // After the page closed, the user went back to this App's original task on the main screen
        port.displays.remove(2)
        port.displays[1] = 0
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
    }

    @Test
    fun finishedMyselfMovesBackAndAsksOnlyToVerify() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        assertTrue(takeover.finishedMyself())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete/finished"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun finishedMyselfWithoutHandOverResumesWithoutMoving() = test {
        // The user may have finished elsewhere (another device, a computer) without tapping "I'll handle it"
        takeover.onState(waiting)
        assertTrue(takeover.finishedMyself())
        assertEquals(listOf("resume:complete/finished"), port.log)
    }

    @Test
    fun handOverWithNoAppStillHandsOver() = test {
        port.displays.clear()
        port.target = -1
        takeover.onState(waiting)
        assertTrue(takeover.handOver())
        assertTrue(takeover.handBack())
        assertEquals(listOf("resume:complete"), port.log)
    }

    @Test
    fun cancelWhileWaiting() = test {
        takeover.onState(waiting)
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun cancelWhileHandedOverLeavesAppOnMainScreen() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
        assertEquals(0, port.displays[1])
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun cancelWithoutTakeoverStillCancels() = test {
        takeover.onState(TaskState.Running(3, "phone_tap"))
        takeover.cancel()
        assertEquals(listOf("cancel"), port.log)
    }

    @Test
    fun failedMoveToMainStaysWaitingAndSaysSo() = test {
        takeover.onState(waiting)
        port.moveToMainOk = false
        assertFalse(takeover.handOver())
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveToMainFailed, takeover.problem.value)
        assertFalse(takeover.handedOver)
        assertEquals(listOf("toMain:1"), port.log)

        port.moveToMainOk = true
        assertTrue(takeover.handOver())
        assertNull(takeover.problem.value)
    }

    @Test
    fun failedMoveBackKeepsUserInChargeAndDoesNotResume() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        port.moveBackOk = false
        assertFalse(takeover.handBack())
        assertEquals(listOf("back:1"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveBackFailed, takeover.problem.value)

        port.moveBackOk = true
        port.log.clear()
        assertTrue(takeover.handBack())
        assertEquals(listOf("back:1", "mute:a.app", "resume:complete"), port.log)
        assertNull(takeover.problem.value)
    }

    @Test
    fun actionsIgnoredWhenNotWaitingForTakeover() = test {
        takeover.onState(TaskState.Running(2, "phone_observe"))
        assertFalse(takeover.handOver())
        assertFalse(takeover.handBack())
        assertFalse(takeover.finishedMyself())
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun handOverTwiceMovesOnce() = test {
        takeover.onState(waiting)
        takeover.handOver()
        assertFalse(takeover.handOver())
        assertEquals(listOf("toMain:1", "unmute:a.app"), port.log)
    }

    @Test
    fun pipUnmuteWithoutRealHandoverFallsBackToPhone() = test {
        var phoneCalls = 0
        takeover.onState(TaskState.Running(1, "phone_observe"))
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(1, phoneCalls)
        // The takeover target always exists; even while waiting for the user, an ordinary current App still goes through phone/AppMuter
        takeover.onState(waiting)
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(2, phoneCalls)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun pipUnmuteDuringAppHandoverUsesTakeoverAndRepeatedClicksRetry() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        var phoneCalls = 0
        restorePipSound(takeover) { phoneCalls++ }
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(listOf("pip-unmute:a.app", "pip-unmute:a.app"), port.log)
        assertEquals(0, phoneCalls)
    }

    @Test
    fun pipUnmuteDuringHandoverWithoutAppFallsBackToPhone() = test {
        port.displays.clear()
        port.target = -1
        takeover.onState(waiting)
        takeover.handOver()
        var phoneCalls = 0
        restorePipSound(takeover) { phoneCalls++ }
        assertEquals(1, phoneCalls)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun republishingTheAnsweredTakeoverDoesNotReArm() = test {
        takeover.onState(waiting)
        takeover.handBack()
        // Before the loop has changed the state to Running, the same waiting state may be published again
        takeover.onState(waiting)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handBack())
    }

    @Test
    fun secondIdenticalTakeoverAfterResumeIsArmed() = test {
        takeover.onState(waiting)
        takeover.handBack()
        takeover.onState(TaskState.Running(5, "phone_observe"))
        // A new takeover with the same content as the previous one enters waiting as usual
        takeover.onState(TaskState.WaitingTakeover("captcha", "请拖动滑块"))
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handBack())
    }

    @Test
    fun taskEndingClearsStage() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.onState(TaskState.Cancelled)
        assertEquals(Takeover.Stage.None, takeover.stage.value)
        assertFalse(takeover.handedOver)
    }

    @Test
    fun staleWaitKeyIsIgnored() = test {
        takeover.onState(waiting)
        val first = takeover.waitKey
        assertTrue(takeover.skip(first))
        takeover.onState(TaskState.Running(3, "phone_tap"))
        val second = TaskState.WaitingTakeover("login", "请登录")
        takeover.onState(second)
        assertTrue(takeover.waitKey != first)
        // A click left over from the previous wait: no action
        assertFalse(takeover.handOver(first))
        assertFalse(takeover.skip(first))
        assertEquals(Takeover.Stage.Waiting, takeover.stage.value)
        assertTrue(takeover.handOver(takeover.waitKey))
        assertFalse(takeover.handBack(first))
        assertFalse(takeover.finishedMyself(first))
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertTrue(takeover.handBack(takeover.waitKey))
    }

    @Test
    fun skipReturnsSkipped() = test {
        takeover.onState(waiting)
        assertTrue(takeover.skip())
        assertEquals(listOf("resume:skipped"), port.log)
        assertEquals(HandBack(HandBackOutcome.skipped), port.results.single())
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun skipWhileHandedOverMovesAppBackFirst() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.log.clear()
        port.moveBackOk = false
        // Cannot move it back: leave things as they are, the user is still on the main screen
        assertFalse(takeover.skip())
        assertEquals(listOf("back:1"), port.log)
        assertEquals(Takeover.Stage.HandedOver, takeover.stage.value)
        assertEquals(Takeover.Problem.MoveBackFailed, takeover.problem.value)

        port.moveBackOk = true
        port.log.clear()
        assertTrue(takeover.skip())
        assertEquals(listOf("back:1", "mute:a.app", "resume:skipped"), port.log)
        assertEquals(AGENT, port.displays[1])
        assertNull(port.watching)
    }

    @Test
    fun handBackReportsActivityOfHandedOverApp() = test {
        port.displays[2] = AGENT
        port.packages[2] = "login.app"
        takeover.onState(waiting)
        takeover.handOver()
        assertEquals("login.app", port.watching)
        port.activity = HandoverActivity(3, "登录")
        testScheduler.advanceTimeBy(42_000)
        assertTrue(takeover.handBack())
        assertEquals(HandBack(HandBackOutcome.complete, durationMs = 42_000, inputCount = 3, stoppedAt = "登录"), port.results.single())
        assertNull(port.watching)
    }

    @Test
    fun failedMoveBackKeepsCounting() = test {
        takeover.onState(waiting)
        takeover.handOver()
        port.moveBackOk = false
        takeover.handBack()
        assertEquals("a.app", port.watching)
    }

    @Test
    fun cancelStopsCounting() = test {
        takeover.onState(waiting)
        takeover.handOver()
        takeover.cancel()
        assertNull(port.watching)
    }

    @Test
    fun handBackWithoutHandOverHasNoCounts() = test {
        takeover.onState(waiting)
        assertTrue(takeover.handBack())
        assertEquals(HandBack(HandBackOutcome.complete), port.results.single())
    }
}
