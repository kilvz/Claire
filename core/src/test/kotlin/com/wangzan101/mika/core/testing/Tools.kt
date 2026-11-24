package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.assembly.recordedTools
import com.wangzan101.mika.core.cap.sandbox.SANDBOX
import com.wangzan101.mika.core.cap.web.WEB
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** Test helper: declarations for all of Claire's tools. */
internal val TOOLS: ToolIndex get() = knownTools()

/** Test helper: tool declarations for reading run records (old names from before the rename are recognized too). */
internal val RECORDED_TOOLS: ToolIndex get() = recordedTools()

/** Test helper: look up the action a tool performs by its name. */
internal fun verb(name: String): Verb? = TOOLS.verb(name)

/** Test helper: names of the tools that can go into do_steps, in declaration order. */
internal val BATCHABLE: List<String> get() = TOOLS.names.filter { TOOLS[it]!!.batchable }

/** Test helper: names of the tools in the web and sandbox groups. */
internal val WEB_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == WEB }.toSet()
internal val SANDBOX_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == SANDBOX }.toSet()

internal const val WEB_SCRIPT_TOOL = "web_script"
internal const val INTERACT_TOOL = "web_interact"

/** Test helper: a reply with no tool calls, i.e. the final answer handed to the user. */
internal fun answerReply(text: String) =
    com.wangzan101.mika.core.model.ModelReply(listOf(com.wangzan101.mika.core.model.Part.Text(text)), com.wangzan101.mika.core.model.StopReason.EndTurn, null)

/** Test helper: the task succeeded, the delivered answer is [answer], and verification found no problems (either it was verified, or there was nothing to verify; sources are tested separately). */
internal fun assertAnswered(answer: String, end: com.wangzan101.mika.core.agent.TaskState) {
    val s = end as? com.wangzan101.mika.core.agent.TaskState.Succeeded ?: throw AssertionError("not succeeded: $end")
    org.junit.Assert.assertEquals(answer, s.answer)
    org.junit.Assert.assertTrue("$end", !s.doubtful && s.unconfirmed.isEmpty())
}
