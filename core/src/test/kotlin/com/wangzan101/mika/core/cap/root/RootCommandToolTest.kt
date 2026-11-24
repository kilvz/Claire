package com.wangzan101.mika.core.cap.root

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.safety.gate.ConfirmationGate
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the commands that ran; every one succeeds and its output is the command itself. */
private class FakeRoot(var answer: RootRun? = null) : RootPort {
    val ran = mutableListOf<String>()
    override suspend fun run(command: String, timeoutMs: Long): RootRun {
        ran += command
        return answer ?: RootRun.Done(0, false, "out:$command", "", 0, 5)
    }
}

private class Events : RunSink {
    val all = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { all += e }
}

private var seq = 0
private fun root(command: String) = ModelReply(
    listOf(Part.ToolCall("r${++seq}", "root_run_command", JsonObject(mapOf("command" to kotlinx.serialization.json.JsonPrimitive(command))).toString())),
    StopReason.ToolUse, null,
)
private val FINISH get() = answerReply("好了")

private fun FakeModel.result(i: Int): JsonObject =
    Json.parseToJsonElement(requests[i].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content).jsonObject
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

/**
 * root commands: registered only when a port is provided; read-only ones run directly; anything that changes
 * things first asks the user with a confirmation card (which shows this very command), and runs only if they
 * agree; autonomous mode does not ask; unattended runs do not run them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandToolTest {
    private fun loop(m: FakeModel, port: RootPort, autonomous: Boolean = false, sink: RunSink = RunSink.NONE) =
        AgentLoop(m, toolDispatcher(FakeScreen(), root = port), "BASE", sink = sink, confirmations = ConfirmationGate(),
            approvals = ApprovalSettings { autonomous })

    @Test
    fun registeredOnlyWithAPort() {
        assertTrue(toolDispatcher(FakeScreen()).offeredNow(all = true).none { it.name == "root_run_command" })
        val with = toolDispatcher(FakeScreen(), root = FakeRoot())
        assertTrue(with.offeredNow().any { it.name == "root_run_command" })
        // it is recognized when mentioned in skills and knowledge too (the name is registered)
        assertTrue("root_run_command" in knownToolNames())
    }

    /** Read-only commands run directly and the result carries the exit code and output; the user is not asked and no approval is recorded. */
    @Test
    fun readOnlyCommandsRunWithoutAsking() = runTest {
        val port = FakeRoot(); val sink = Events()
        val m = FakeModel(root("ls -l /data/data/com.example.app"), FINISH)
        loop(m, port, sink = sink).run("t")
        assertEquals(listOf("ls -l /data/data/com.example.app"), port.ran)
        val r = m.result(1)
        assertEquals("true", r.s("ok"))
        assertEquals("0", r.s("exitCode"))
        assertTrue(r.s("stdout"), "out:ls" in r.s("stdout"))
        assertTrue(sink.all.none { it is RunEvent.Confirmation })
    }

    /** Changing commands run directly: the user turning on "allow root commands" is itself the authorization, so they are not asked one by one. */
    @Test
    fun changingCommandsRunWithoutAsking() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/Download/old.apk"), FINISH)
        loop(m, port).run("t")
        assertEquals(listOf("rm /sdcard/Download/old.apk"), port.ran)
        assertEquals("true", m.result(1).s("ok"))
    }

    @Test
    fun autonomousModeDoesNotAsk() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), FINISH)
        loop(m, port, autonomous = true).run("t")
        assertEquals(listOf("rm /sdcard/x"), port.ran)
    }

    /** Runs that cannot reach a user (scheduled, unattended): changing commands do not run, read-only ones still do. */
    @Test
    fun unattendedRunsDoNotChangeAnything() = runTest {
        val port = FakeRoot()
        val m = FakeModel(root("rm /sdcard/x"), root("ls /sdcard"), FINISH)
        val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        loop(m, port).run(spec, RunInbox())
        assertEquals(listOf("ls /sdcard"), port.ran)
        assertEquals("approval_unavailable", m.result(1).s("error"))
    }

    /** su was denied: report it as it is and do not retry as if the command had failed. */
    @Test
    fun deniedSuIsReported() = runTest {
        val m = FakeModel(root("ls /data"), FINISH)
        loop(m, FakeRoot(RootRun.Denied)).run("t")
        val r = m.result(1)
        assertEquals("root_denied", r.s("error"))
        assertFalse(r.s("message").isBlank())
        assertNull(r["stdout"])
    }
}
