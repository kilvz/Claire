package com.wangzan101.mika.chat.ui

import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.UiAutomation
import kotlinx.coroutines.runBlocking
import com.wangzan101.mika.cap.sandbox.ShareExporter
import com.wangzan101.mika.cap.sandbox.SandboxState
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.cap.sandbox.WorkspaceKey
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.chat.ConversationStore
import androidx.compose.ui.test.onAllNodesWithContentDescription
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.wangzan101.mika.ui.viewer.FileViewerActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.task.ScriptedModel
import com.wangzan101.mika.task.TaskService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.wangzan101.mika.task.testTasks
import com.wangzan101.mika.task.TaskEnvironment
import com.wangzan101.mika.settings.ui.models.use
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.typeAndSend

/**
 * Walks through the chat screen on a real device: the scripted model opens Settings on the virtual display, and the test checks the steps, the small window (collapsed, expanded, dragged, auto-expanded when you are needed) and the result card,
 * taking one screenshot per stage into the app's external files directory shots/ (pulled off with adb from the development machine for manual review).
 * Requires the built-in assistant to be running and Claire's accessibility service to be enabled.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    // A task started from the UI asks once more for the intent before its first action; the answer is fixed to "ACT", so every question in the script is one step of the task loop itself
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val vm get() = (context.applicationContext as MikaApp).chat
    private val anyway = Prerequisites.Check(emptyList())
    private var savedSpeed: SpeedFlags? = null

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        File(context.getExternalFilesDir(null), "shots").mkdirs()
        // The UI state is shared by the whole process: wait for the previous test's tasks to finish, then switch to a new conversation so we do not inherit its finished state
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // The parallel switch is a persistent setting that other tests (or ones that crashed midway) may have left on; each test decides for itself, off by default
        FeatureFlags.get(context).let { f -> savedSpeed = f.speedFlags(); f.setSpeed(savedSpeed!!.copy(parallelTasks = false)) }
        // While the chat screen still shows the previous conversation's finished state, newConversation is treated as "busy" and does not run: wait for it to become idle and confirm the switch to an empty conversation really happened
        assertNotNull("composer never went idle", pollUntil(20_000) { true.takeIf { !vm.busy() } })
        compose.runOnUiThread { vm.newConversation() }
        compose.waitForIdle()
        assertTrue("not a fresh conversation: ${vm.ui.value.exchanges}", vm.ui.value.exchanges.isEmpty())
    }

    @After
    fun tearDown() {
        DeviceTaskState.runs.value.forEach { it.cancel() }
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.active() } }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
        // Restore the persistent settings to what they were before this test, so the device is not left with test-only switches on
        savedSpeed?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    private fun TaskState.active() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled && this != TaskState.Idle

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        // The default UiAutomation suppresses other accessibility services, which would disconnect Claire's service
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The dumpsys line for the Activity that is in the foreground right now. */
    private fun topResumed(dump: String): String? =
        dump.lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }

    /** Whether the foreground Activity is this app's [cls] (dumpsys prints it as "package/.RelativeClassName"). */
    private fun onTop(cls: Class<*>): Boolean {
        val line = topResumed(sh("dumpsys activity activities")) ?: return false
        val short = "${context.packageName}/" + cls.name.removePrefix(context.packageName)
        return line.contains("$short ") || line.contains("${context.packageName}/${cls.name} ")
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    @Test
    fun titleShowsLiveAndCompletedConversationUsage() {
        val counted = object : ChatModel by model {
            override val name = "fixture"
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (tools.isEmpty()) return ModelReply(listOf(Part.Text("OK")), StopReason.EndTurn, Usage(0, 0))
                return model.complete(system, messages, tools).copy(usage = Usage(1_000, 120, 800))
            }
        }.metered(UsageSource("fixture", "Fixture", "fixture", UsagePurpose.Main), UsageSink { _, _ -> })
        testTasks.modelFactory = { _, _ -> counted }

        fun open() = compose.onNodeWithTag("chat-usage-title").performClick()
        fun waitFor(text: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        fun close() = compose.onNodeWithTag("close-conversation-usage").performClick()
        // The user's configured start page can be Personal or onboarding. Open this chat via its normal entry point.
        val id = ConversationStore.shared(context).newConversation()
        compose.runOnUiThread {
            val intent = android.content.Intent(compose.activity.intent).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, id)
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, intent)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("chat-usage-title").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { assertTrue(vm.send("你好", anyway)) }
        model.awaitCall()
        open()
        waitFor("最近一次主模型请求")
        compose.onNodeWithText("系统提示与工具定义").assertExists()
        model.answer("你好。")
        waitFor("本对话共 1.1K Token")
        compose.onNodeWithText("80%").assertExists()
        close()
        compose.waitUntil(15_000) { !vm.busy() }
        compose.onNodeWithText("80%").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(vm.send("再问一次", anyway)) }
        model.awaitCall()
        model.answer("好的。")
        compose.waitUntil(15_000) { !vm.busy() }
        open()
        waitFor("本对话共 2.2K Token")
        compose.onNodeWithText("80%").assertExists()
        close()
        compose.runOnUiThread { vm.newConversation() }
        open()
        waitFor("本对话共 0 Token")
        compose.onNodeWithText("还没有记录到请求的上下文构成。").assertExists()
    }

    @Test
    fun runsTaskWithStepsPipAndResult() {
        shot("01-idle")

        compose.typeAndSend("打开设置看看电池")
        if (vm.ui.value.blocked != null) {
            // This phone has no model configured etc.: screenshot the blocking notice too, then bypass the check and run with the scripted model
            shot("02-blocked")
            // Bypassing the check hands it straight to the UI state, and the UI will not clear the text in the input box for it; the input box is not editable once the task starts, so clear it first
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.runOnUiThread { vm.dismissBlocked(); assertTrue(vm.send("打开设置看看电池", anyway)) }
        }

        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS","reason":"先打开系统设置"}""")
        assertEquals(2, model.awaitCall().number)
        model.feed("phone_observe", """{"reason":"看看设置首页有什么"}""")
        assertEquals(3, model.awaitCall().number)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        // While running, one line shows the current progress and the vertical line below lists what has been done: actions use the model's own words, and reads say where it looked (the reason the model gave for the read is not shown).
        // Right now it is waiting for the model's 3rd call, so the progress line says "working on it" and the last step, "looked at Settings", is the current step
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在处理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("看了设置").assertExists()
        compose.onNodeWithText(ChatCopy.BACKGROUND_NOTE).assertExists()
        compose.onNodeWithText("先打开系统设置").assertExists()
        // On devices that support press-to-talk the input bar shows the voice variant of the gray hint, otherwise the typing variant; both mean "you can add something right now"
        compose.onNode(hasText(ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true)) or
            hasText(ChatCopy.voicePlaceholder(ComposerMode.Busy), substring = true)).assertExists()
        assertTrue(vm.ui.value.pip.visible)
        shot("03-running-collapsed")

        compose.onNodeWithContentDescription("Claire 的虚拟屏").performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        shot("04-running-expanded")
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()

        // The small window normally sits in the bottom-right corner (above the input bar): fling it up and to the left, and it should snap to the top-left corner
        val d = context.resources.displayMetrics.density
        val pip = compose.onNodeWithContentDescription("Claire 的虚拟屏").fetchSemanticsNode().boundsInWindow
        val x = pip.center.x
        val y = pip.center.y
        sh("input swipe ${x.toInt()} ${y.toInt()} ${(x - 250 * d).toInt()} ${(y - 300 * d).toInt()} 120")
        compose.waitForIdle()
        val moved = compose.onNodeWithContentDescription("Claire 的虚拟屏").fetchSemanticsNode().boundsInWindow
        assertTrue("pip did not move up-left: $pip -> $moved", moved.center.x < x && moved.center.y < y)
        shot("05-dragged")

        model.feed("hand_to_user", """{"reason":"captcha","message":"设置里弹出了验证(脚本演示),需要你处理。"}""")
        awaitState { it is TaskState.WaitingTakeover }
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        compose.onNodeWithText("需要你处理:验证码").assertExists()
        compose.onNodeWithText("已暂停").assertExists()
        shot("06-takeover")

        // The expanded small window and its scrim cover the card in the chat: collapse it first, then tap the button on the card
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消任务").performClick()
        awaitState { it == TaskState.Cancelled }
        // The wrap-up added after stopping (the scripted model does not write it; it is assembled from the record) sits below the "Stopped." message
        compose.onNodeWithText("已停止。").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("已经停下,停止前做了这些:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("06b-stopped-summary")

        // Start a new task right after: the service may still be finishing the previous one, so the UI has to wait for that to finish before handing over
        compose.runOnUiThread { assertTrue(vm.send("设置里电池是多少", anyway)) }
        model.awaitCall() // The scripted model's call numbers continue from the previous task
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "打开设置")
        model.awaitCall()
        model.answer("脚本演示:电池信息在「设置 › 电池」里。")
        awaitState { it is TaskState.Succeeded }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("来源", substring = true).fetchSemanticsNodes().isNotEmpty())
        // The cancelled question and the completed one each have their own "view process" entry
        assertEquals(2, compose.onAllNodesWithContentDescription("查看过程").fetchSemanticsNodes().size)
        shot("07-done")

        // Drawer: opens by tapping "menu" (with the drawer closed, back goes to the system and is no longer treated as opening the menu); the drawer keeps only the main line: search, projects, recent conversations, settings
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithTag("drawer-search").assertIsDisplayed()
        compose.onNodeWithText("项目").assertIsDisplayed()
        compose.onNodeWithText("最近").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed()
        shot("08-drawer")
    }

    @Test
    fun sentTurnIsVisibleAtTheTailOfALongConversation() {
        val store = ConversationStore(context)
        val id = store.newConversation()
        repeat(30) { i ->
            store.append(id, Turn("旧问题 $i", "旧回答 $i\n" + "这是一段用于撑满长会话视口的历史内容。\n".repeat(8), "succeeded"), emptyList())
        }
        compose.runOnUiThread { vm.openConversation(id) }
        compose.waitUntil(5_000) { vm.ui.value.conversationId == id && vm.ui.value.exchanges.size == 30 }
        compose.waitForIdle()

        val message = "刚发送的新一轮必须直接显示"
        compose.runOnUiThread { assertTrue(vm.send(message, anyway)) }
        compose.waitUntil(5_000) { vm.ui.value.exchanges.size == 31 }
        // assertExists alone is not enough: the regression was exactly that the new row existed but was left outside the viewport by the old layout row count.
        compose.onNodeWithText(message).assertIsDisplayed()
    }

    /** The attach button appears only when the sandbox is not unsupported on this device (not installed, installing, and broken can all still be installed or repaired). */
    @Test
    fun attachButtonHiddenOnlyWhenSandboxUnsupported() {
        val unsupported = SandboxState.get(context).status.value is SandboxStatus.Unsupported
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithContentDescription("添加附件").fetchSemanticsNodes()
        assertEquals("sandbox unsupported=$unsupported", !unsupported, nodes.isNotEmpty())
    }

    /** The file card shows the name and size; tapping a text file opens the in-app viewer (no longer handed to the system to pick an app), the file contents are visible, and back returns to the chat. */
    @Test
    fun fileCardOpens() {
        val ws = SandboxDirs(context).workspace(WorkspaceKey.Conversation("card-device-test")).apply { mkdirs() }
        val f = File(ws, "card.txt").apply { writeText("hello") }
        val name = "mika-card-${System.currentTimeMillis()}.txt"
        val card = runBlocking { com.wangzan101.mika.cap.sandbox.AndroidFdSys.gate.openRead(ws, f.name).use { ShareExporter(context).export(it, name) } }
        val store = ConversationStore(context)
        val id = store.newConversation()
        store.append(id, Turn("导出", "好了", "succeeded"), listOf(card))
        try {
            compose.runOnUiThread { vm.openConversation(id) }
            compose.onNodeWithText(card.name).assertExists()
            compose.onNodeWithText(card.name).performClick()
            val shown = pollUntil(5_000) { true.takeIf { onTop(FileViewerActivity::class.java) } }
            assertNotNull("file viewer did not open: ${topResumed(sh("dumpsys activity activities"))}", shown)
            // The viewer is another Activity but lives in the same process as the chat screen, so its UI can be queried here too: the name is in the top bar and the content is shown as-is
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello", substring = true, useUnmergedTree = true).assertIsDisplayed()
            shot("file-viewer")
            sh("input keyevent KEYCODE_BACK")
            val back = pollUntil(5_000) { true.takeIf { onTop(MainActivity::class.java) } }
            assertNotNull("back did not return to the chat page", back)
        } finally {
            context.contentResolver.delete(Uri.parse(card.uri), null, null)
            ws.deleteRecursively()
        }
    }

    /** With the parallel switch on: two conversations each run a task, a small dot appears on the menu, and the other conversation can be switched to from the "in progress" section in the drawer. */
    @Test
    fun otherTaskChipSwitches() {
        val flags = FeatureFlags.get(context)
        val prior = flags.speedFlags()
        flags.setSpeed(prior.copy(parallelTasks = true))
        val models = listOf(ScriptedModel().apply { intent = "ACT" }, ScriptedModel().apply { intent = "ACT" })
        // The model factory is not only fetched when a task starts: titling a new conversation also fetches one in the background, in no fixed order. Rotating by call count would give the first task the second model;
        // instead, hand out the first one until the first task has actually queried the model, and the second one after that (the titling question is answered the same way by either model)
        val firstStarted = AtomicBoolean(false)
        testTasks.modelFactory = { _, _ -> if (firstStarted.get()) models[1] else models[0] }
        try {
            compose.runOnUiThread { assertTrue(vm.send("第一个任务", anyway)) }
            assertEquals(1, models[0].awaitCall().number)
            firstStarted.set(true)
            val first = vm.ui.value.conversationId
            compose.runOnUiThread {
                vm.newConversation()
                assertTrue(vm.send("第二个任务", anyway))
            }
            assertEquals(1, models[1].awaitCall().number)
            val second = vm.ui.value.conversationId
            assertTrue(first != second)

            compose.onNodeWithContentDescription("菜单").performClick()
            compose.waitForIdle()
            // "In progress" is both a section header and the small text under the other conversation's row: tell them apart by position — the header is above, the row is below (the header and the small text merge into one clickable node)
            val inDrawer = hasAnyAncestor(hasTestTag("drawer"))
            val other = compose.onNode(hasText("第一个任务") and hasText("进行中") and inDrawer)
            other.assertIsDisplayed()
            val header = compose.onNode(hasText("进行中") and !hasText("第一个任务") and inDrawer)
            header.assertIsDisplayed()
            val headerTop = header.fetchSemanticsNode().boundsInWindow.top
            val otherTop = other.fetchSemanticsNode().boundsInWindow.top
            assertTrue("running entry not under the in-progress header: $headerTop vs $otherTop", otherTop > headerTop)
            other.performClick()
            compose.waitForIdle()
            assertEquals(first, vm.ui.value.conversationId)
            assertEquals("第一个任务", vm.ui.value.exchanges.last().goal)
            shot("parallel-drawer")

            models.forEach { it.answer("done") }
            assertNotNull(pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } })
        } finally {
            DeviceTaskState.runs.value.forEach { it.cancel() }
            pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
            flags.setSpeed(prior)
        }
    }
}
