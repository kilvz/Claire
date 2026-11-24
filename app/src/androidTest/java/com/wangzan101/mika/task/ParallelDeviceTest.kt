package com.wangzan101.mika.task

import com.wangzan101.mika.DeviceTaskState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.shell.IMikaShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device test: with the parallelTasks flag on, two conversations' tasks run at the same time, each on its own virtual display.
 * Requires Android 14+, the built-in assistant running and Claire's accessibility service enabled. The scripted models are assigned to the two tasks in start order.
 */
@RunWith(AndroidJUnit4::class)
class ParallelDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IMikaShell
    private val models = listOf(ScriptedModel(), ScriptedModel())
    /** Hand out scripted models per conversation: title generation and the like also ask the factory for a model, so splitting by call count would drift; the same conversation (same session id) always gets the same model. */
    private val bySession = java.util.concurrent.ConcurrentHashMap<String, ScriptedModel>()
    private lateinit var priorFlags: SpeedFlags

    @Before
    fun setUp() {
        assumeTrue("parallel displays need Android 14+", Build.VERSION.SDK_INT >= ParallelCapability.MIN_SDK)
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val flags = FeatureFlags.get(context)
        // Second run of the kill-process test: the first run was killed before wrap-up, so the flag is still on; restore what was recorded before the first run started, not what is set now
        priorFlags = killRecord.takeIf { it.exists() }?.let { runCatching { SpeedFlags.parse(JSONObject(it.readText()).getString(FLAGS_KEY)) }.getOrNull() }
            ?: flags.speedFlags()
        flags.setSpeed(priorFlags.copy(parallelTasks = true))
        testTasks.modelFactory = { _, session ->
            synchronized(bySession) { bySession.getOrPut(session.id) { models[bySession.size.coerceAtMost(1)] } }
        }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        if (!::priorFlags.isInitialized) return
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        FeatureFlags.get(context).setSpeed(priorFlags)
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun clockPackage(): String =
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?: error("no clock app handles ACTION_SHOW_ALARMS")

    /** Start a task in a new conversation and wait until its model is first called; returns the conversation id. */
    private fun startTask(goal: String, model: ScriptedModel): String {
        val before = DeviceTaskState.conversationId.value
        TaskService.start(context, goal)
        assertEquals(1, model.awaitCall().number)
        return pollUntil(5_000) { DeviceTaskState.conversationId.value?.takeIf { it != before } } ?: error("task did not start")
    }

    private fun displayOf(conversationId: String): Int =
        pollUntil(10_000) { DeviceTaskState.run(conversationId)?.displayId?.value?.takeIf { it >= 0 } } ?: -1

    private fun finish(model: ScriptedModel) = model.answer("done")

    private fun ended(conversationId: String): TaskState? {
        val run = DeviceTaskState.run(conversationId) ?: return TaskState.Cancelled
        return run.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled }
    }

    /** Both tasks run at the same time on two displays and both can finish; meanwhile the user's main display is unaffected. */
    @Test
    fun twoTasksRunOnTwoDisplays() {
        val mainBefore = DeviceSupport.mainScreenState(shell)
        val (a, b) = models
        val first = startTask("设置版本号", a)
        val second = startTask("在时钟里新建一个带标记的闹钟", b)
        assertEquals(2, DeviceTaskState.runs.value.size)

        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"${clockPackage()}"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        val displayA = displayOf(first)
        val displayB = displayOf(second)
        assertTrue("displays $displayA / $displayB", displayA > 0 && displayB > 0)
        assertNotEquals(displayA, displayB)
        assertTrue(shell.topTaskId(displayA) >= 0)
        assertTrue(shell.topTaskId(displayB) >= 0)
        // Each display is operated separately, and neither the main display's focus nor its top task changes
        a.feed("phone_back")
        b.feed("phone_back")
        assertEquals(3, a.awaitCall().number)
        assertEquals(3, b.awaitCall().number)
        assertEquals(mainBefore, DeviceSupport.mainScreenState(shell))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(20_000) { ended(first) })
        assertNotNull(pollUntil(20_000) { ended(second) })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        assertFalse("Claire display left behind", "mika-agent" in sh("dumpsys display"))
    }

    /** Both tasks want to open settings: the later one gets app_in_use_by_other_task and does not take settings away from the first task. */
    @Test
    fun sameAppSecondTaskGetsConflictError() {
        val (a, b) = models
        val first = startTask("设置版本号", a)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, a.awaitCall().number)
        val displayA = displayOf(first)
        val settingsTask = shell.topTaskId(displayA)

        startTask("设置里看存储", b)
        b.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        val result = b.awaitCall().messages.last().toString()
        assertTrue(result, "app_in_use_by_other_task" in result)
        assertEquals(settingsTask, shell.topTaskId(displayA))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
    }

    /**
     * Review Focus 5: with two tasks in progress the Claire process is killed; on the next start PLAY_AUDIO is restored for both packages and none of Claire's virtual displays is left.
     * The test runs inside the Claire process, and killing it would end the test as well, so it is split into two runs with the kill done from outside in between:
     * the first run sets everything up and leaves a record in files/parallel-kill-test.json (both packages' original PLAY_AUDIO modes and the flags from before the start), then waits;
     * once the record appears, run `adb shell am force-stop <Claire package name>` on the dev machine (that run reports a crash).
     * The second run (the process is relaunched and recovery happens on startup) checks against the record and restores the flags from it (the first run was killed before wrap-up).
     * Note: force-stop removes Claire from the enabled accessibility services (system behavior), so it must be enabled again before the second run.
     */
    private val killRecord get() = File(context.filesDir, "parallel-kill-test.json")

    @Ignore("two-run manual procedure: needs an external `adb shell am force-stop` while this test waits; a normal run would sit for 120 s and then fail")
    @Test
    fun killDuringTwoTasksRecovers() {
        val record = killRecord
        if (record.exists()) {
            val saved = JSONObject(record.readText())
            record.delete()
            try {
                for (pkg in saved.keys()) {
                    if (pkg == FLAGS_KEY) continue
                    assertEquals(pkg, saved.getString(pkg), pollUntil(60_000) { shell.getAudioMode(pkg)?.takeIf { it == saved.getString(pkg) } })
                }
                assertFalse("Claire display left behind", "mika-agent" in sh("dumpsys display"))
            } finally {
                // Wrap-up restores it too; restore it here first so the flag is not left on even if an assertion fails
                FeatureFlags.get(context).setSpeed(priorFlags)
            }
            return
        }
        val clock = clockPackage()
        val prior = JSONObject().put(SETTINGS, shell.getAudioMode(SETTINGS)).put(clock, shell.getAudioMode(clock))
            .put(FLAGS_KEY, priorFlags.describe())
        val (a, b) = models
        startTask("设置版本号", a)
        startTask("在时钟里新建一个带标记的闹钟", b)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"$clock"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(clock)?.takeIf { it == "ignore" } })
        record.writeText(prior.toString())
        // A self-kill command backgrounded inside the process would not run (it dies with the test's shell session), so it has to be killed from outside:
        // after the record file appears, run adb shell am force-stop <package> on the dev machine. If that does not happen, clear the record and fail
        Thread.sleep(KILL_WAIT_MS)
        record.delete()
        error("Claire was not killed: run `adb shell am force-stop ${context.packageName}` after ${record.name} appears, then rerun this test")
    }

    private companion object {
        const val FLAGS_KEY = "speed_flags"
        const val KILL_WAIT_MS = 120_000L
    }
}
