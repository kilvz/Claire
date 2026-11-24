package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.screen.uistate.APP_IN_USE_BY_OTHER_TASK
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.AppWait
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** phone_launch_app's wait: while another task is using the app, the model can ask to wait until it is released before launching. */
class LaunchWaitTest {
    /** The first open reports the app as in use, then it stays busy [busyTimes] times; [wait] is the result of waiting. */
    private class BusyScreen(private val wait: AppWait, private var busyTimes: Int = 1) : ScreenPort by FakeScreen() {
        val calls = mutableListOf<String>()
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            calls += "open $packageName"
            return if (busyTimes-- > 0) ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK) else ActionResult.Ok()
        }
        override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
            calls += "wait $packageName $timeoutMs"
            return wait
        }
    }

    private suspend fun launch(screen: ScreenPort, args: String) =
        toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))

    private fun error(r: com.wangzan101.mika.core.tool.ToolExecution) = r.content["error"]?.jsonPrimitive?.content

    @Test fun withoutWaitTheInUseResultComesBackAtOnce() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat"}""")
        assertEquals(APP_IN_USE_BY_OTHER_TASK, error(r))
        assertEquals(listOf("open com.example.chat"), screen.calls)
        // the message tells the model it can ask to wait
        assertTrue(r.content["message"]!!.jsonPrimitive.content.contains("wait"))
    }

    @Test fun waitOpensTheAppOnceItIsFree() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat","wait":30}""")
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 30000", "open com.example.chat"), screen.calls)
    }

    @Test fun mutualWaitFailsFastWithItsOwnCode() = runTest {
        val screen = BusyScreen(AppWait.WouldDeadlock)
        val r = launch(screen, """{"package":"com.example.chat","wait":60}""")
        assertEquals(LaunchApp.WAIT_WOULD_DEADLOCK, error(r))
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 60000"), screen.calls)
    }

    @Test fun waitingTooLongSaysItIsStillInUse() = runTest {
        val screen = BusyScreen(AppWait.TimedOut)
        assertEquals(LaunchApp.WAIT_TIMED_OUT, error(launch(screen, """{"package":"com.example.chat","wait":5}""")))
    }

    /** wait has no effect when the app is not busy; a wait beyond the limit is an argument error and nothing is opened. */
    @Test fun waitIsBoundedAndOnlyUsedWhenBusy() = runTest {
        val free = BusyScreen(AppWait.Free, busyTimes = 0)
        assertEquals("true", launch(free, """{"package":"com.example.chat","wait":10}""").content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat"), free.calls)
        val tooLong = BusyScreen(AppWait.Free)
        val r = launch(tooLong, """{"package":"com.example.chat","wait":${LaunchApp.MAX_WAIT_S + 1}}""")
        assertEquals("invalid_arguments", error(r))
        assertEquals(emptyList<String>(), tooLong.calls)
    }
}
