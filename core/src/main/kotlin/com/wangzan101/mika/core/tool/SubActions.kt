package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.Part

/**
 * An action inside a tool that does not touch the screen itself (a page script) is handed back to the loop for execution: guards, lock-screen gates, irreversible-action checks and run records are the same as when the model calls that tool on its own.
 * When it is stopped without executing, the result has error set to confirmation_required / guard_stopped / target_unresolved and executed set to false.
 */
fun interface SubActions {
    suspend fun run(call: Part.ToolCall): ToolExecution
}
