package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject
import com.wangzan101.mika.core.assembly.toolDispatcher

/** Drive the dispatcher directly from tests (testing the tools themselves, not execution order); production code can only obtain credentials through the execution entry point. */
internal suspend fun ToolDispatcher.execute(call: Part.ToolCall, step: Int? = null, sub: SubActions? = null): ToolExecution =
    execute(call, ActionPass(), step, sub)

/** Test helper: look up a tool's declaration by name (production code asks through this task's dispatcher). */
internal fun isWritingTool(name: String, @Suppress("UNUSED_PARAMETER") args: JsonObject): Boolean = toolDispatcher(FakeScreen()).writesScreen(name)

internal fun isSandboxTool(name: String): Boolean = toolDispatcher(FakeScreen()).index[name]?.capability == "sandbox"
