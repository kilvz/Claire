package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * Device test: the scripted model declares a paid action inside settings on the virtual display (it only declares it and never actually performs it; only spending needs consent, and every declaration carries an amount),
 * then this checks the high-priority notification's title, body and "agree"/"disagree" buttons, plus the three paths: disagree, agree, and automatic rejection by the evaluator.
 * Requires the built-in assistant to be running and Claire's accessibility service enabled.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    // A task started from the UI asks for the intent once more before the first tool call (when no light model is configured, it asks this model): always answer ACT so it does not consume scripted steps
    private val model = ScriptedModel().apply { intent = "ACT" }
    private var appTask = -1

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        if (appTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == appTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun attention(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        ?.notification

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content

    /** Open settings on the virtual display, then declare an "order"; when this returns the task is waiting for user consent. */
    private fun startAndRequest() {
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"order","target":"测试项","content":"买测试项\n第二行","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation }
    }

    @Test
    fun notificationHasApproveDecline() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        assertEquals("要你同意:下单", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("在 ") && "对象:测试项" in text && "内容:买测试项 第二行" in text && "金额:¥25" in text)
        assertEquals(listOf("同意", "不同意"), n.actions.map { it.title.toString() })
        // Same channel as takeover. It is high importance when created; the user or system may lower it later (the app cannot change it back), so only require at least default and note the actual level
        assertEquals(TaskService.CHANNEL_ATTENTION, n.channelId)
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TaskService.CHANNEL_ATTENTION).importance
        Log.i("ConfirmationDeviceTest", "attention channel importance $importance")
        assertTrue("importance $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
        // On the lock screen only the actions are exposed, not the target and content
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // The generic text already contains the word "target", so what must be checked is that the concrete target and content are not exposed
        val publicText = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, "测试项" !in publicText && "第二行" !in publicText)
        // Both buttons are also visible in the system notification dump
        val dump = sh("dumpsys notification --noredact")
        assertTrue("buttons not in dumpsys notification", "同意" in dump && "不同意" in dump)

        // An answer tapped on a stale notification (whose request no longer matches) does not count
        context.startService(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_APPROVE)
            .putExtra(ConfirmationNotifier.EXTRA_REQUEST, 12345))
        Thread.sleep(1_000)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)

        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        assertEquals(true, pollUntil(3_000) { if (attention() == null) true else null })
        model.feed("end_not_done", """{"message":"用户没有同意下单,没有做这一步"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed && "用户没有同意下单" in end.reason)
    }

    @Test
    fun approveButtonAnswersTheLoop() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        // The Intent sent by the notification's own "agree" button
        n.actions.first { it.title.toString() == "同意" }.actionIntent.send()
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "\"approved\":true" in result)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value !is TaskState.WaitingConfirmation)
        // After approving it does not really go through with it: just end
        model.feed("end_not_done", """{"message":"测试到此为止"}""")
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
    }

    @Test
    fun evalAutoDeclineSkipsTheUser() {
        testTasks.evalHooks.autoDecideConfirmation = { false }
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"send","target":"测试","content":"转账","amount":"¥25","app":"$SETTINGS"}""")
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        model.feed("end_not_done", """{"message":"没有同意"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed)
    }

    /** After a second declaration, the first notification's own "agree" button no longer counts. */
    @Test
    fun firstNotificationCannotApproveSecondRequest() {
        startAndRequest()
        val first = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        val firstApprove = first.actions.first { it.title.toString() == "同意" }.actionIntent
        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        assertTrue(lastToolResult(model.awaitCall()).contains("confirmation_declined"))
        // Declare again with a different action type (the same type for the same app is not asked again once declined)
        model.feed("request_confirmation", """{"action":"submit","target":"测试项","content":"提交测试项","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation && it.request.action.name == "submit" }
        pollUntil(5_000) { attention()?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "要你同意:提交" } }
        runCatching { firstApprove.send() } // the stale button may already have been invalidated by the system; being invalidated also passes
        Thread.sleep(1_500)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)
    }
}

