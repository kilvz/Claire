package com.wangzan101.mika.core.safety.guard

import com.wangzan101.mika.core.model.Part
import kotlinx.serialization.json.JsonObject

/** Guard for tools that do not touch the screen (sandbox and the like), one per task; the screen guard ignores these tools. */
interface OffScreenGuard {
    /** The stop verdict; null when not stopped. */
    val stop: GuardVerdict?

    /** The result returned instead of executing once this kind of tool's quota is used up; null while quota remains. */
    fun exhausted(tool: String): JsonObject?

    /** Adjust the call according to the remaining quota before executing; returned unchanged when no adjustment is needed. */
    fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall

    /** Verdicts after execution. */
    fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict>
}
