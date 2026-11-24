package com.wangzan101.mika.task

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.TaskStage
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ToolLabels

private val tools by lazy { knownTools() }
private val labels by lazy { ToolLabels(tools) }

/** When collapsed, show the things that need the user first; while running, show the current major stage. */
internal fun taskChip(origin: RunOrigin, state: TaskState, stage: TaskStage? = null): String? {
    if (origin != RunOrigin.Ui) return null
    val label = when (state) {
        is TaskState.WaitingConfirmation -> R.string.chat_wait_approval
        is TaskState.WaitingInput -> R.string.chat_wait_answer
        is TaskState.WaitingTakeover -> R.string.chip_take_over
        is TaskState.Held -> when {
            state.reason.queued -> return null
            state.reason == TaskGate.LOCKED -> R.string.task_wait_unlock
            state.reason.needsYou -> R.string.screens_needs_you
            else -> R.string.chat_paused
        }
        TaskState.Idle, is TaskState.Terminal -> return null
        is TaskState.Running -> return stage?.compact ?: tr(
            if (state.step <= 1) R.string.runtime_preparing else R.string.task_running)
    }
    return tr(label)
}

/** Without narration the expanded card should still make the current action readable; while waiting for the model it doesn't reuse the previous step's tool name. */
internal fun chipDetail(state: TaskState.Running, stage: TaskStage? = null): String {
    if (stage != null) return "${stage.ordinal} · ${stage.detail}"
    val tool = state.tool
    return when {
        state.progress != null || state.step == 0 -> TaskService.describe(state).second
        tool == null -> tr(R.string.chip_thinking)
        else -> tr(R.string.task_step_note, state.step, state.narration?.takeIf { it.isNotBlank() }
            ?: state.reason?.takeIf { it.isNotBlank() } ?: tools[tool]?.let { labels.label(tool) } ?: tr(R.string.task_running))
    }
}
