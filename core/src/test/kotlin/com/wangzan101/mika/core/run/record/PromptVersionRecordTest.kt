package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * The run record stores the version of this task's prompt: a digest of the system prompt plus tool definitions the model would
 * receive with every group expanded, independent of which groups this run actually expanded (with phone only, that is what you see once the phone group is expanded).
 */
class PromptVersionRecordTest {
    private suspend fun run(tools: ToolDispatcher): Pair<String, String> {
        val events = mutableListOf<RunEvent>()
        val m = fakeModel("main") { call("phone_observe"); answer("好了") }
        AgentLoop(m, tools, SystemPrompt.identity(), sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("t")
        val r = m.requests.last()
        return events.filterIsInstance<RunEvent.PromptVersion>().single().hash to PromptVersion.of(r.system, r.tools)
    }

    @Test fun recordsHashOfWhatTheModelSaw() = runTest {
        val (phone, expected) = run(toolDispatcher(FakeScreen()))
        assertEquals(expected, phone)
        val (web, _) = run(toolDispatcher(FakeScreen(), web = FakeWebPort()))
        assertNotEquals(phone, web)
    }
}
