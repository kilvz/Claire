package com.wangzan101.mika.takeover

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.shell.IMikaShell
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment

/**
 * Device test: the scripted model requests a takeover on a settings subpage of the virtual display; "I'll handle it" moves settings to the main display and restores the sound,
 * and "handled" moves it back to the virtual display and mutes it again, after which the Agent's next turn starts with observe.
 * It briefly takes over the main display (dedicated test device) and closes settings on the main display when finished. Requires the built-in assistant to be running and Claire's accessibility service enabled.
 */
@RunWith(AndroidJUnit4::class)
class TakeoverTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val model = ScriptedModel()
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        // On cancel the app stays on the main display (it is not pulled away from the user); on a test device the test closes it itself
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    /**
     * When this app has many notifications, the system automatically adds a group summary (no title and no buttons) that may reuse the same channel, so it must be skipped;
     * the earlier "got an empty title when running after other tests" was in fact picking up this summary.
     */
    private fun attentionRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun attention(): Notification? = attentionRecord()?.notification

    private fun attentionActions(): List<String>? = attention()?.actions?.map { it.title.toString() }

    /**
     * Tap a notification button: the button should open the UI-less [TakeoverActivity] (so the notification shade collapses) and let it forward to the task service.
     * Sending the button's PendingIntent from the test process itself is blocked by the background Activity launch restriction (taps from the notification shade are not),
     * so this checks the button's target and then starts it directly with the same Intent.
     */
    private fun tapAttentionAction(title: String, action: String) {
        val a = attention()?.actions?.firstOrNull { it.title.toString() == title } ?: error("no action $title: ${attentionActions()}")
        assertTrue(a.actionIntent.isActivity)
        context.startActivity(Intent(context, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun attentionTitle(): String? = attention()?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun handOverMovesToMainAndBack() {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        val appTask = shell.topTaskId(displayId)
        assertTrue(appTask >= 0)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        // Open a settings subpage and then request the takeover on top of it. Some systems open the subpage in a separate task (app sign-in and payment pages often do the same),
        // so the takeover should move whichever task is on top of the virtual display — the one the user has to deal with
        sh("am start --display $displayId -a android.settings.DISPLAY_SETTINGS")
        settingsTask = pollUntil(3_000) { shell.topTaskId(displayId).takeIf { it != appTask } } ?: appTask
        android.util.Log.i("TakeoverTest", "app task $appTask, sub-page task $settingsTask")

        model.feed("hand_to_user", """{"reason":"captcha","message":"请完成页面上的验证"}""")
        assertEquals(TaskState.WaitingTakeover("captcha", "请完成页面上的验证"), awaitState { it is TaskState.WaitingTakeover })
        assertEquals("需要你处理:验证码", pollUntil(5_000) { attentionTitle() })
        assertEquals(listOf("我来处理", "跳过", "取消任务"), pollUntil(5_000) { attentionActions() })

        // "I'll handle it": settings is on top of the main display and can play sound; this move is intentional, so it does not count as the user taking it away
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(priorAudio, pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == priorAudio } })
        assertEquals(listOf("处理好了", "我自己做完了", "取消任务"),
            pollUntil(5_000) { attentionActions()?.takeIf { it.size == 3 } })
        Thread.sleep(1_500) // let the "moved away" poll run at least two rounds
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingTakeover)
        assertEquals(Takeover.Stage.HandedOver, DeviceTaskState.takeoverStage.value)

        // "Handled" (the on-screen button uses the same entry point): back to the virtual display and muted again, and the Agent gets a hint to verify first
        TaskService.handBack(context)
        val history = model.awaitCall().messages
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        // The hand-back result is the tool result of hand_to_user: complete, and it asks the model to verify first
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        val text = last.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(text, "\"outcome\":\"complete\"" in text && AgentLoop.HANDBACK_NOTE in text)
        assertEquals(true, pollUntil(3_000) { if (attentionActions() == null) true else null })

        model.feed("phone_observe")
        val afterObserve = model.awaitCall().messages
        val result = afterObserve.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content, "\"currentApp\":\"$SETTINGS\"" in result.content)

        // The answer quotes an element name that was just read: it really was read and the literal check matches, so the outcome is a verified completion
        val quote = Regex("\"name\":\"([^\"\\\\]{2,40})\"").findAll(result.content).map { it.groupValues[1] }
            .firstOrNull { n -> n.count { it.isLetter() } >= 2 && n.none { it.isDigit() } } ?: error("no element name on screen: ${result.content}")
        val answer = "done,设置里有「$quote」"
        model.answer(answer)
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == answer && end.verified)
        // The state flips to finished first, while restoring the sound happens during wrap-up, so wait until busy turns false before checking
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /** Open settings and request a takeover, wait for the notification to appear; returns the Agent's virtual display id. */
    private fun requestTakeover(): Int {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        model.feed("hand_to_user", """{"reason":"login","message":"请在设置里看一下"}""")
        awaitState { it is TaskState.WaitingTakeover }
        pollUntil(5_000) { attentionActions() } ?: error("no takeover notification")
        return displayId
    }

    private fun lastToolResult(history: List<Message>): String {
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        return last.parts.filterIsInstance<Part.ToolResult>().single().content
    }

    /** Center of the first clickable entry in the settings window on the main display that is not a switch; tapping it only opens a subpage. */
    private fun clickableOnMainScreen(): Rect? = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == SETTINGS }
        ?.let { root ->
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isClickable && !n.isCheckable && n.isVisibleToUser && !n.text.isNullOrBlank()) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
                return null
            }
            find(root)?.let { Rect().also(it::getBoundsInScreen) }
        }

    @Test
    fun skipButtonPresent() {
        requestTakeover()
        assertEquals(listOf("我来处理", "跳过", "取消任务"), attentionActions())
        tapAttentionAction("跳过", TaskService.ACTION_SKIP)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"skipped\"" in text && "\"reason\":\"takeover_skipped\"" in text)
        // It was never handed over, so the app stayed on the virtual display the whole time
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
    }

    @Test
    fun handBackReportsInputCount() {
        requestTakeover()
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(Takeover.Stage.HandedOver, pollUntil(5_000) { DeviceTaskState.takeoverStage.value.takeIf { it == Takeover.Stage.HandedOver } })

        // The user taps the app twice on the main display: enter an entry, go back, enter again
        repeat(2) { i ->
            val target = pollUntil(5_000) { clickableOnMainScreen() } ?: error("nothing clickable in settings on the main screen")
            sh("input -d ${Display.DEFAULT_DISPLAY} tap ${target.centerX()} ${target.centerY()}")
            Thread.sleep(1_000)
            if (i == 0) {
                sh("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
                Thread.sleep(1_000)
            }
        }

        TaskService.handBack(context)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"complete\"" in text)
        val count = Regex("\"inputCount\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: error("no inputCount: $text")
        assertTrue(text, count >= 2)
        assertTrue(text, "\"stoppedAt\":" in text)
    }
}
