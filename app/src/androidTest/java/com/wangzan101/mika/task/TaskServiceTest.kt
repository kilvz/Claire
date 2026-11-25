package com.wangzan101.mika.task

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.Conversation
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.personal.PersonalTask
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.safety.verify.Verifier
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Device test: drive TaskService with a scripted model, open system settings on the virtual display, and check keeping the screen on, muting, pausing when moved away, and waiting on the lock screen.
 * Requires the built-in assistant to be running and Claire's accessibility service enabled.
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    /**
     * One scripted model per task: call numbers and verification counts are tallied separately per task. With a shared one, calls the previous task did not consume
     * (verification, wrap-up) would be picked up by the next task's wait, and the test would run ahead of the task.
     * Within a single task, the task itself is not the only thing that asks the factory for a model: titling a new conversation and writing the post-stop summary each ask once too, and they run concurrently with the task,
     * so which one asks first is unpredictable. Therefore the factory does not hand out models by "which ask this is", but always gives the model of the most recently started task (see [model]).
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    /** The factory reads it on the task service's thread. */
    @Volatile private var started = 0
    /** The model of the task started by the most recent [start] (before any task has started, the one the first task will use). */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        // The tasks here are all started from the UI, so an extra intent question comes before the first tool call; always answer "act", which does not consume scripted steps
        models.forEach { it.intent = "ACT" }
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // If the test fails midway, settings may still be on the main display: only stop the task this test moved there itself
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /** Start a task and have it open settings on the virtual display, returning the display id; at this point the model is waiting for the second step. */
    private fun startWithSettingsOpen(): Int {
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /** Hand over an answer with no verifiable value and wait for the task to end: the literal check has nothing to compare, so no separate verification is requested and the outcome is done. [appOpened] only exists so call sites read clearly. */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        // The state flips to finished first; restoring the sound happens during wrap-up, and only then does busy turn back to false
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun mikaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Claire:task'" in it }

    /**
     * On the product path the answer is not separately verified (the task runs with reviewFinalAnswer = false): the model's answer is handed over as-is, with the outcome done and unreviewed;
     * neither the main model nor the light model receives a verification request. The rules of verification itself are covered by the core unit tests.
     */
    @Test
    fun answerHandedOverWithoutReview() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // With a light model configured, intent classification and title generation ask it first; anything else can only be verification, so count it
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system == TITLE) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
                    if (system == Verifier.PROMPT) lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "电量是 9731 毫安时" && !end.reviewed && end.unconfirmed.isEmpty())
        assertEquals(0, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    /** A task that does not open an app creates no virtual display: reading the screen gives an empty screen telling it to use phone_launch_app first, write operations fail with no_app_open, and there are no frames. */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("你好")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        assertEquals(null, DeviceTaskState.runs.value.single().phoneFrames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        // Only the first phone_launch_app creates a display, and it creates just one. The second task has its own scripted model, so calls are counted from 1 again
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        val run = DeviceTaskState.runs.value.single()
        assertNotNull(pollUntil(5_000) { run.phoneFrames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, run.phoneFrames.value)
    }

    @Test
    fun personalReplayKeepsAFrameFromTheTaskDisplay() {
        val personal = (context.applicationContext as MikaApp).personal
        val id = UUID.randomUUID().toString()
        val task = PersonalTask(id, id, "打开设置", "打开设置", "test", System.currentTimeMillis())
        personal.store.update { it.copy(tasks = it.tasks + task) }
        try {
            started++
            TaskService.start(context, task.goal, task.conversationId)
            assertEquals(1, model.awaitCall().number)
            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            assertEquals(2, model.awaitCall().number)
            val replay = pollUntil(8_000) { runBlocking { personal.replayFrames(id).firstOrNull() } }
            assertNotNull(replay)
            requireNotNull(BitmapFactory.decodeFile(replay!!.file.path)).recycle()
            finishAndAwait()
            assertTrue(replay.file.isFile)
        } finally {
            personal.store.update {
                it.copy(tasks = it.tasks.filterNot { t -> t.id == id }, messages = it.messages.filterNot { m -> m.taskId == id })
            }
        }
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        // Muting happens in a side coroutine after the app opens, so give it a moment
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        // Simulate the user opening the same app on the main display: the task is moved to the main display
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        // The pause must show up immediately even while the model is still "thinking" (has not reached the gate)
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        // It is not moved back automatically while paused
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     * Only reads dumpsys power and changes no global settings: temporarily turning off "stay awake while USB is connected" to wait for the real screen timeout
     * makes the main display turn off and lock immediately when the last user interaction is long past (tried in practice), which is unacceptable unattended.
     * When the lock is disabled by the system (for example by background restrictions), dumpsys marks it DISABLED, which is excluded here as well.
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = mikaScreenLock()
        assertNotNull("Claire holds no screen wake lock while running", line)
        // Some systems prefix the type with a lock object id (mLock:<id>), so only match the type wording
        // Only keep the screen on and allow the system to dim it, rather than locking it at full brightness
        assertTrue(line!!, "SCREEN_DIM_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        // The summary is a bitmask (0x..), where AOSP's WAKE_LOCK_SCREEN_DIM = 1 shl 2; some versions print it as text
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 2) != 0 else "SCREEN_DIM" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (mikaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${mikaScreenLock()}", released == true)
    }

    /** The preparation before the first step (opening the model, reading knowledge, choosing a route) also counts as running: the screen lock is already held before the model has been opened. */
    @Test
    fun keepsScreenOnWhilePreparing() {
        val opening = java.util.concurrent.CountDownLatch(1)
        testTasks.modelFactory = { _, _ -> opening.await(); model }
        start("测试任务")
        val held = pollUntil(5_000) { mikaScreenLock() }
        opening.countDown()
        assertNotNull("Claire holds no screen wake lock while preparing", held)
        assertEquals(1, model.awaitCall().number)
        finishAndAwait()
    }

    /**
     * Stop: the UI shows stopped right away, then a short wrap-up is appended as this turn's answer (the outcome remains cancelled). The wrap-up is written by the light model, with no tools;
     * if the light model fails it falls back to the main model, and if the main model cannot write it either, it is assembled straight from the record.
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // The intent question at the start answers "act"; the only other light call is the one writing the wrap-up
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    // Do not finish the wrap-up yet: this makes the "organizing" state observable
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("停止前打开了设置,还没看任何页面。")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.wangzan101.mika.chat.ConversationStore.shared(context)
        // Record "cancelled" first; stopping does not wait for the wrap-up
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to Conversation.CANCELLED_OUTCOME, placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != Conversation.CANCELLED_OUTCOME } }!!
        assertEquals("停止前打开了设置,还没看任何页面。", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        // The light model fails and the main model hands in a blank: assemble it straight from the record
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != Conversation.CANCELLED_OUTCOME } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("已经停下,停止前做了这些:"))
        assertEquals(1, model.summaryRequests.get())
    }

    // Must never be enabled unattended: it sends KEYCODE_SLEEP, which turns the phone's screen off and locks it, and only a person can type the password to unlock.
    // Only remove @Ignore and run it manually while the user is present and ready to unlock.
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        // Unlocked by a person; after unlocking it should continue automatically without pressing continue
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    @Test
    fun scheduledTaskFailsAndNotifiesWhenUnlockIsNotAllowed() {
        val access = (context.applicationContext as MikaApp).deviceAccess
        val wasEnabled = access.settings.config.value.enabled
        val power = context.getSystemService(PowerManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.key }.toSet()
        val conversation = "test-scheduled-unlock-${System.nanoTime()}"
        access.settings.setEnabled(false)
        try {
            TaskService.startTrigger(context, "测试定时任务", conversation, conversation, null)
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            val end = awaitState { it is TaskState.Failed } as TaskState.Failed
            val expected = context.getString(com.wangzan101.mika.R.string.trigger_unlock_failed) + " [disabled]"
            assertEquals(expected, end.reason)
            assertEquals(FailKind.Environment, end.kind)

            val posted = pollUntil(5_000) {
                notifications.activeNotifications.firstOrNull {
                    it.key !in before && it.notification.channelId == TaskService.CHANNEL_RESULT &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == expected
                }
            }
            assertNotNull("missing scheduled-task failure notification", posted)
        } finally {
            access.settings.setEnabled(wasEnabled)
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    @Test
    fun scheduledPartialOutcomeKeepsOneShotPlan() {
        val id = "test-partial-trigger-${System.nanoTime()}"
        val plan = TriggerDefinition(id, "thread-$id", "执行部分结果测试", System.currentTimeMillis() + 86_400_000)
        val judge = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                ModelReply(listOf(Part.Text("""{"outcome":"partial","reason":"只完成了一部分"}""")), StopReason.EndTurn, null)
        }
        started++
        testTasks.lightModelFactory = { _, _ -> judge }
        val flags = FeatureFlags.get(context)
        val oldQuit = flags.quitRequested()
        flags.setQuitRequested(false)
        try {
            TriggerRuntime.schedule(context, plan)
            TriggerRuntime.runNow(context, id)
            assertEquals(1, model.awaitCall().number)
            model.answer("已完成一部分")

            val key = pollUntil(5_000) {
                TriggerRuntime.snapshot(context).windows.firstOrNull { it.triggerId == id }?.key
            } ?: error("trigger window was not created")
            val failed = pollUntil(20_000) {
                TriggerRuntime.snapshot(context).windows.firstOrNull { it.key == key }?.state
                    ?.takeIf { it == TriggerWindowState.Failed }
            }
            assertEquals(TriggerWindowState.Failed, failed)
            assertNotNull(TriggerRuntime.list(context).singleOrNull { it.id == id })
        } finally {
            TriggerRuntime.remove(context, id)
            testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
            flags.setQuitRequested(oldQuit)
        }
    }

    @Test
    fun uiTaskStillWaitsForManualUnlock() {
        val power = context.getSystemService(PowerManager::class.java)
        try {
            start("测试普通任务")
            assertEquals(1, model.awaitCall().number)
            sh("input keyevent KEYCODE_SLEEP")
            assertNotNull("screen did not turn off", pollUntil(5_000) { true.takeIf { !power.isInteractive } })

            model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
            assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
            Thread.sleep(1_500)
            assertEquals("ordinary task attempted unattended unlock", TaskState.Held(TaskGate.LOCKED), DeviceTaskState.state.value)
        } finally {
            sh("input keyevent KEYCODE_WAKEUP")
        }
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.wangzan101.mika.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.wangzan101.mika.core.prompt.Prompts.role("stop_summary") }
        val TITLE by lazy { com.wangzan101.mika.core.prompt.Prompts.role("conversation_title") }
    }
}
